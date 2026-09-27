"""Generates tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java from the
committed pre-hook live-shadow profile. Fail-closed: refuses unless every required
hook is QUALIFIED and the manifest hash matches. The plan is the single source of
truth for which hook sites the transformers may instrument."""
import argparse
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
_parser = argparse.ArgumentParser(description=__doc__)
_parser.add_argument("--profile", type=pathlib.Path,
                     default=ROOT / "tools/live-capture/live-shadow-profile.json",
                     help="qualified pre-hook profile JSON (live-shadow shape)")
_parser.add_argument("--manifest", type=pathlib.Path,
                     default=ROOT / "tools/live-capture/required-live-writer-hooks.json")
_parser.add_argument("--out", type=pathlib.Path,
                     default=ROOT / "tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java")
_parser.add_argument("--class-name", default="LiveWriterPlan",
                     help="generated plan class name (unique per profile)")
_parser.add_argument("--legacy-v1-reproduction", action="store_true",
                     help="reproduce historical V1 diagnostics only; never V2 qualification")
_args = _parser.parse_args()
PROFILE = _args.profile
MANIFEST = _args.manifest
OUT = _args.out
PLAN_CLASS = _args.class_name
if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", PLAN_CLASS):
    raise SystemExit("REFUSED: invalid generated Java class name")
OUT = OUT.resolve()
if not OUT.is_relative_to(ROOT.resolve()):
    raise SystemExit("REFUSED: output must remain in this checkout")

profile = json.loads(PROFILE.read_bytes())
# Parse and bind one captured manifest buffer; a second read could name other bytes.
manifest_bytes = MANIFEST.read_bytes()
manifest = json.loads(manifest_bytes)
manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()

mode = profile.get("identity_mode", "RAW")
if (profile.get("schema_version") == 2 or "expected_class_identities" in profile
        or profile.get("kind") == "RUSTCRAFT_V2_WRITER_PLAN_RECIPE") and mode != "CANONICAL_ID_V2":
    raise SystemExit("REFUSED: V2 recipe may not downgrade its identity mode")
if mode not in ("RAW", "CANONICAL", "CANONICAL_ID_V2"):
    raise SystemExit("REFUSED: unknown identity mode cannot fall back to RAW")
if mode == "CANONICAL" and not _args.legacy_v1_reproduction:
    raise SystemExit("REFUSED: V1 requires explicit historical reproduction")
if mode == "CANONICAL_ID_V2":
    if profile.get("schema_version") != 2 or profile.get("kind") != "RUSTCRAFT_V2_WRITER_PLAN_RECIPE" or profile.get("all_required_observed") is not True:
        raise SystemExit("REFUSED: explicit observed V2 recipe required; a recipe is not a qualification certificate")
    required_status = "OBSERVED"
    if "expected_class_hashes" in profile or "all_required_qualified" in profile:
        raise SystemExit("REFUSED: V2 recipe cannot reuse historical qualification/hash claims")
else:
    if profile.get("all_required_qualified") is not True:
        raise SystemExit("REFUSED: historical profile is not fully qualified")
    required_status = "QUALIFIED"
if profile.get("required_hooks_manifest_sha256") != manifest_sha:
    raise SystemExit("REFUSED: profile was built from a different manifest revision")
for hook in profile["required_hooks"]:
    if hook["status"] != required_status:
        raise SystemExit("REFUSED: hook %s is %s" % (hook["id"], hook["status"]))
manifest_ids = [h["id"] for h in manifest["required_hooks"]]
profile_ids = [h["id"] for h in profile["required_hooks"]]
if (not manifest_ids or len(set(manifest_ids)) != len(manifest_ids)
        or len(set(profile_ids)) != len(profile_ids) or set(profile_ids) != set(manifest_ids)):
    raise SystemExit("REFUSED: required hook inventory is incomplete or duplicated")
required_classes = {h["class"] for h in manifest["required_hooks"]}
qualification = profile.get("qualification", {})
if (not re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", profile.get("forge_build", ""))
        or not isinstance(qualification, dict)
        or not re.fullmatch(r"[A-Za-z0-9_.-]+", qualification.get("profile", ""))
        or not re.fullmatch(r"[0-9a-f]{64}", qualification.get("minecraft_server_jar_sha256", ""))):
    raise SystemExit("REFUSED: malformed runtime/profile metadata")
identity_by_class = {}
if mode == "CANONICAL_ID_V2":
    identities = profile.get("expected_class_identities")
    if not isinstance(identities, dict) or set(identities) != required_classes:
        raise SystemExit("REFUSED: exact V2 class identity inventory required")
    for name, identity in identities.items():
        if (not isinstance(identity, dict) or set(identity) != {"schema", "class_name", "raw_sha256", "semantic_sha256", "declaration_order_sha256"}
                or identity["schema"] != "CANONICAL_ID_V2" or identity["class_name"] != name.replace(".", "/")
                or any(not isinstance(identity[k], str) or not re.fullmatch(r"[0-9a-f]{64}", identity[k]) for k in ("raw_sha256", "semantic_sha256", "declaration_order_sha256"))):
            raise SystemExit("REFUSED: malformed/unbound V2 identity for " + name)
        identity_by_class[name] = identity
else:
    hashes = profile.get("expected_class_hashes")
    if (not isinstance(hashes, dict) or not required_classes.issubset(hashes)
            or any(not isinstance(hashes[c], str) or not re.fullmatch(r"[0-9a-f]{64}", hashes[c]) for c in required_classes)):
        raise SystemExit("REFUSED: historical class hashes missing or malformed")

OWNER = {"net.minecraft.world.World", "net.minecraft.world.chunk.Chunk",
         "net.minecraft.world.chunk.storage.ExtendedBlockStorage",
         "net.minecraft.world.chunk.BlockStateContainer",
         "net.minecraft.world.chunk.NibbleArray", "net.minecraft.util.BitArray"}
PACKET = {"net.minecraft.network.play.server.SPacketChunkData"}


def transformer_for(hook):
    cls = hook["class"]
    if cls in PACKET:
        return "PACKET"
    if cls in OWNER:
        return "OWNERSHIP"
    return "PUBLICATION"


def jstr(s):
    if not isinstance(s, str):
        raise SystemExit("REFUSED: Java string input must be text")
    return json.dumps(s, ensure_ascii=True)


plan_entries = []
hooks = manifest["required_hooks"]
status_by_id = {h["id"]: h["status"] for h in profile["required_hooks"]}
hash_by_class = profile.get("expected_class_hashes", {})
for hook in hooks:
    cls = hook["class"]
    fp = hook["fingerprint"]
    if fp.get("kind") not in ("DECLARATION", "CLASS_STRUCTURE", "BCI_ASSERTIONS"):
        raise SystemExit("REFUSED: unsupported fingerprint kind")
    if fp["kind"] == "BCI_ASSERTIONS":
        if (not isinstance(fp.get("assertions"), list) or not fp["assertions"]
                or any(type(a.get("bci")) is not int or a["bci"] < 0
                       or not isinstance(a.get("expect"), str) or not a["expect"] for a in fp["assertions"])):
            raise SystemExit("REFUSED: malformed instruction anchor")
        fingerprint_expr = "new String[][]{%s}" % ", ".join(
            "{%s, %s}" % (jstr(str(a["bci"])), jstr(a["expect"])) for a in fp["assertions"])
    else:
        fingerprint_expr = "EMPTY_FINGERPRINT"
    identity = identity_by_class.get(cls)
    plan_entries.append(
        "new Hook(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)" % (
            jstr(hook["id"]), jstr(transformer_for(hook)), jstr(cls), jstr(hook["method"]),
            jstr(hook["descriptor"]), jstr(hook["hook_type"]),
            jstr("liveWriter." + hook["id"] + "." + cls.rsplit(".", 1)[-1] + "." + hook["method"].replace("<init>", "ctor")),
            fingerprint_expr,
            jstr(identity["semantic_sha256"] if identity else hash_by_class[cls]),
            jstr("CANONICAL_ID_V1" if mode == "CANONICAL" else mode),
            jstr(identity["declaration_order_sha256"]) if identity else "null"))

java = f"""package com.rustcraft.coremod;

/**
 * GENERATED by tools/live-capture/generate_live_writer_plan.py from the committed
 * pre-hook profile tools/live-capture/live-shadow-profile.json
 * (manifest tools/live-capture/required-live-writer-hooks.json @ {manifest_sha[:16]}…).
 * Do not edit by hand. V2 entries are observed recipe inputs, not certificates.
 * Historical entries retain their original diagnostic classification. The
 * transformers refuse to instrument anything that is not in this plan, and each
 * entry carries the pre-hook class hash that the source bytes must match.
 *
 * Profile identity bound at generation: minecraft_server {profile["qualification"]["minecraft_server_jar_sha256"][:16]}…,
 * forge build {profile["forge_build"]}, profile {profile["qualification"]["profile"]}.
 */
public final class {PLAN_CLASS} {{
    public static final String[][] EMPTY_FINGERPRINT = new String[0][];

    public static final class Hook {{
        public final String id;
        public final String transformer;   // OWNERSHIP | PUBLICATION | PACKET
        public final String className;     // fully-qualified transformed name
        public final String methodName;    // SRG/runtime name; <init> for constructors
        public final String descriptor;    // exact JVM descriptor
        public final String hookType;      // WRITE_BEGIN | PRIVATE_BUILD_BEGIN | ...
        public final String operationId;   // unique injected marker + duplicate-detection anchor
        public final String[][] fingerprint; // {{bci, expected instruction fragment}} or empty
        public final String preHookClassSha256;
        public final boolean canonicalIdentity;
        public final String identitySchema;
        public final String declarationOrderSha256;

        Hook(String id, String transformer, String className, String methodName, String descriptor,
             String hookType, String operationId, String[][] fingerprint, String preHookClassSha256,
             boolean canonicalIdentity) {{
            this(id, transformer, className, methodName, descriptor, hookType, operationId,
                    fingerprint, preHookClassSha256,
                    canonicalIdentity ? "CANONICAL_ID_V1" : "RAW", null);
        }}

        Hook(String id, String transformer, String className, String methodName, String descriptor,
             String hookType, String operationId, String[][] fingerprint, String preHookClassSha256,
             String identitySchema, String declarationOrderSha256) {{
            this.id = id;
            this.transformer = transformer;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.hookType = hookType;
            this.operationId = operationId;
            this.fingerprint = fingerprint;
            this.preHookClassSha256 = preHookClassSha256;
            this.canonicalIdentity = "CANONICAL_ID_V1".equals(identitySchema);
            this.identitySchema = identitySchema;
            this.declarationOrderSha256 = declarationOrderSha256;
        }}

        public boolean hasFingerprint() {{
            return fingerprint != EMPTY_FINGERPRINT && fingerprint.length > 0;
        }}
    }}

    /** Negative-control factory: same hook with a wrong pre-hook class hash. */
    public static Hook doctoredSha(Hook hook, String sha) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, hook.descriptor,
                hook.hookType, hook.operationId, hook.fingerprint, sha, hook.identitySchema, hook.declarationOrderSha256);
    }}

    /** Negative-control factory: same hook with an altered descriptor. */
    public static Hook doctoredDescriptor(Hook hook, String descriptor) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, descriptor,
                hook.hookType, hook.operationId, hook.fingerprint, hook.preHookClassSha256, hook.identitySchema, hook.declarationOrderSha256);
    }}

    /** Negative-control factory: same hook with shifted/missing/duplicated anchors. */
    public static Hook doctoredFingerprint(Hook hook, String[][] fingerprint) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, hook.descriptor,
                hook.hookType, hook.operationId, fingerprint, hook.preHookClassSha256, hook.identitySchema, hook.declarationOrderSha256);
    }}

    /** Profile identity binding: any change here requires regeneration + requalification. */
    public static final String FORGE_BUILD = {jstr(profile["forge_build"])};
    public static final String QUALIFICATION_PROFILE = {jstr(profile["qualification"]["profile"])};
    public static final String REQUIRED_HOOKS_MANIFEST_SHA256 = {jstr(manifest_sha)};
    public static final String IDENTITY_MODE = {jstr(profile.get("identity_mode", "RAW"))};

    public static final Hook[] HOOKS = {{
        {",\n        ".join(plan_entries)}
    }};

    private {PLAN_CLASS}() {{ }}
}}
"""
OUT.write_text(java, encoding="utf-8", newline="")
print("generated", OUT, "hooks:", len(plan_entries))
