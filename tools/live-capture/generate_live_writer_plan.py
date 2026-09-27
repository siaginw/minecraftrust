"""Generates tools/bridge/src/com/rustcraft/coremod/LiveWriterPlan.java from the
committed pre-hook live-shadow profile. Fail-closed: refuses unless every required
hook is QUALIFIED and the manifest hash matches. The plan is the single source of
truth for which hook sites the transformers may instrument."""
import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "testing"))
import session_bound_certificate as certificate_schema
from text_digest import normalized_sha256
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
# The pinned digest is taken over the LF rendering, which is what git stores. A raw
# working-tree hash would make the value embedded in the generated plan depend on the
# checker's core.autocrlf, so the same commit would pin a different constant on every
# platform. See tools/testing/text_digest.py.
manifest_sha = normalized_sha256(MANIFEST)

mode = profile.get("identity_mode", "RAW")
SESSION_BOUND = certificate_schema.SESSION_BOUND_SCHEMA
EXACT_V2 = certificate_schema.EXACT_SCHEMA
if (profile.get("schema_version") == 2 or "expected_class_identities" in profile
        or profile.get("kind") == "RUSTCRAFT_V2_WRITER_PLAN_RECIPE") and mode not in (EXACT_V2, SESSION_BOUND):
    raise SystemExit("REFUSED: V2 recipe may not downgrade its identity mode")
if mode not in ("RAW", "CANONICAL", EXACT_V2, SESSION_BOUND):
    raise SystemExit("REFUSED: unknown identity mode cannot fall back to RAW")
if mode == "CANONICAL" and not _args.legacy_v1_reproduction:
    raise SystemExit("REFUSED: V1 requires explicit historical reproduction")
if mode in (EXACT_V2, SESSION_BOUND):
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
if mode in (EXACT_V2, SESSION_BOUND):
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

# ---------------------------------------------------------------- session-bound
# Generic, policy-driven: nothing here names a class, a profile or a mod. A site
# declares its identity mode; session-bound mode additionally REQUIRES a
# certificate per session-bound class. Structure alone never authorizes masking.
identity_by_class_mode = {name: EXACT_V2 for name in identity_by_class}
certificate_by_class = {}
if mode == SESSION_BOUND:
    session_classes = profile.get("session_bound_classes")
    certificates = profile.get("session_certificates")
    if (not isinstance(session_classes, list) or not session_classes
            or len(set(session_classes)) != len(session_classes)
            or any(not isinstance(c, str) or c not in required_classes for c in session_classes)):
        raise SystemExit("REFUSED: session-bound mode requires an explicit in-scope class list")
    if not isinstance(certificates, dict):
        raise SystemExit("REFUSED: session-bound mode requires an acquisition certificate per class")
    session_set = set(session_classes)
    if set(certificates) != session_set:
        raise SystemExit("REFUSED: certificate inventory does not match the declared session-bound classes")
    binding = profile.get("recipe_binding_sha256")
    if not isinstance(binding, str) or not re.fullmatch(r"[0-9a-f]{64}", binding):
        raise SystemExit("REFUSED: explicit recipe binding required for session-bound mode")
    if binding != certificate_schema.recipe_binding_sha256(profile):
        raise SystemExit("REFUSED: recipe binding does not cover this recipe revision")
    for name in sorted(session_set):
        try:
            document = certificate_schema.validate(certificates[name])
        except certificate_schema.CertificateError as invalid:
            raise SystemExit("REFUSED: certificate for " + name + " is unusable: " + str(invalid))
        identity = identity_by_class[name]
        # The certificate must describe the SAME observed pre-writer bytes the
        # plan already binds exactly. A session certificate can never replace or
        # relax the exact CANONICAL_ID_V2 identity requirement.
        if (document["class_name"] != identity["class_name"]
                or document["pre_writer_raw_sha256"] != identity["raw_sha256"]
                or document["exact_semantic_sha256"] != identity["semantic_sha256"]
                or document["exact_declaration_order_sha256"] != identity["declaration_order_sha256"]):
            raise SystemExit("REFUSED: certificate for " + name + " is not bound to the observed exact identity")
        if document["recipe_sha256"] != binding:
            raise SystemExit("REFUSED: certificate for " + name + " binds a different recipe revision")
        if document["runtime_manifest_sha256"] != qualification.get("runtime_manifest_sha256", document["runtime_manifest_sha256"]):
            raise SystemExit("REFUSED: certificate for " + name + " binds a different runtime manifest")
        certificate_by_class[name] = document
        identity_by_class_mode[name] = SESSION_BOUND
elif "session_bound_classes" in profile or "session_certificates" in profile:
    raise SystemExit("REFUSED: session certificates may not ride along on a non-session-bound recipe")


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
    entry_mode = identity_by_class_mode.get(cls, "CANONICAL_ID_V1" if mode == "CANONICAL" else mode)
    document = certificate_by_class.get(cls)
    certificate_arg = "null" if document is None else jstr(certificate_schema.render(document))
    invariant_arg = "null" if document is None else jstr(document["session_invariant_sha256"])
    plan_entries.append(
        "new Hook(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)" % (
            jstr(hook["id"]), jstr(transformer_for(hook)), jstr(cls), jstr(hook["method"]),
            jstr(hook["descriptor"]), jstr(hook["hook_type"]),
            jstr("liveWriter." + hook["id"] + "." + cls.rsplit(".", 1)[-1] + "." + hook["method"].replace("<init>", "ctor")),
            fingerprint_expr,
            jstr(identity["semantic_sha256"] if identity else hash_by_class[cls]),
            jstr(entry_mode),
            jstr(identity["declaration_order_sha256"]) if identity else "null",
            certificate_arg, invariant_arg))

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
        /** Session-bound identity mode requires this certificate; null for every other mode. */
        public final String sessionCertificateJson;
        public final String sessionInvariantSha256;

        Hook(String id, String transformer, String className, String methodName, String descriptor,
             String hookType, String operationId, String[][] fingerprint, String preHookClassSha256,
             boolean canonicalIdentity) {{
            this(id, transformer, className, methodName, descriptor, hookType, operationId,
                    fingerprint, preHookClassSha256,
                    canonicalIdentity ? "CANONICAL_ID_V1" : "RAW", null, null, null);
        }}

        Hook(String id, String transformer, String className, String methodName, String descriptor,
             String hookType, String operationId, String[][] fingerprint, String preHookClassSha256,
             String identitySchema, String declarationOrderSha256) {{
            this(id, transformer, className, methodName, descriptor, hookType, operationId,
                    fingerprint, preHookClassSha256, identitySchema, declarationOrderSha256, null, null);
        }}

        Hook(String id, String transformer, String className, String methodName, String descriptor,
             String hookType, String operationId, String[][] fingerprint, String preHookClassSha256,
             String identitySchema, String declarationOrderSha256, String sessionCertificateJson,
             String sessionInvariantSha256) {{
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
            this.sessionCertificateJson = sessionCertificateJson;
            this.sessionInvariantSha256 = sessionInvariantSha256;
        }}

        public boolean sessionBound() {{
            return "CANONICAL_ID_V2_SESSION_BOUND".equals(identitySchema);
        }}

        public boolean hasFingerprint() {{
            return fingerprint != EMPTY_FINGERPRINT && fingerprint.length > 0;
        }}
    }}

    /** Negative-control factory: same hook with a wrong pre-hook class hash. */
    public static Hook doctoredSha(Hook hook, String sha) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, hook.descriptor,
                hook.hookType, hook.operationId, hook.fingerprint, sha, hook.identitySchema,
                hook.declarationOrderSha256, hook.sessionCertificateJson, hook.sessionInvariantSha256);
    }}

    /** Negative-control factory: same hook with an altered descriptor. */
    public static Hook doctoredDescriptor(Hook hook, String descriptor) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, descriptor,
                hook.hookType, hook.operationId, hook.fingerprint, hook.preHookClassSha256, hook.identitySchema,
                hook.declarationOrderSha256, hook.sessionCertificateJson, hook.sessionInvariantSha256);
    }}

    /** Negative-control factory: same hook with shifted/missing/duplicated anchors. */
    public static Hook doctoredFingerprint(Hook hook, String[][] fingerprint) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, hook.descriptor,
                hook.hookType, hook.operationId, fingerprint, hook.preHookClassSha256, hook.identitySchema,
                hook.declarationOrderSha256, hook.sessionCertificateJson, hook.sessionInvariantSha256);
    }}

    /** Negative-control factory: same hook with a doctored session certificate. */
    public static Hook doctoredCertificate(Hook hook, String sessionCertificateJson) {{
        return new Hook(hook.id, hook.transformer, hook.className, hook.methodName, hook.descriptor,
                hook.hookType, hook.operationId, hook.fingerprint, hook.preHookClassSha256, hook.identitySchema,
                hook.declarationOrderSha256, sessionCertificateJson, hook.sessionInvariantSha256);
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
