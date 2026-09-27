"""Builds the real Revelation MIXED identity recipe from measured evidence.

Revelation is a test case for a generic modded-runtime compatibility system, so
nothing here names a mod, a class it special-cases, or a profile it branches on.
The script reads facts that were MEASURED on two independent real launches and
derives a recipe from them:

  * a class whose two launches both carried qualified MixinMerged.sessionId
    provenance, and whose session-invariant identity was identical across them
    while its exact digests and its UUID moved, is admitted SESSION-BOUND -- its
    exact identity is a property of one launch and cannot appear in a static
    plan;
  * a class with no such provenance, whose exact digests were STABLE across both
    launches, stays EXACT V2. Weakening it to session-bound would authorize
    value-level normalization the runtime does not need, and a policy that
    projects nothing is a policy that authorizes nothing.

The split is read from the evidence, never asserted. If the two launches
disagree about which classes carry provenance, or about any invariant, this
refuses instead of picking a side: a recipe built over a contested set would be
a plan for a runtime that may not exist.

    python -B tools/qualification-v2/rev_mixed_recipe.py \
        --launch-one <identities.json> --launch-two <identities.json> \
        --manifest tools/live-capture/required-live-writer-hooks.json \
        --forge-build 14.23.5.2846 --server-jar-sha256 <sha> \
        --runtime-profile <profile> --runtime-manifest-sha256 <sha> \
        --loader-class net.minecraft.launchwrapper.LaunchClassLoader \
        --out <recipe.json>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/testing"))
import session_bound_policy as policy_schema  # noqa: E402
from text_digest import normalized_sha256  # noqa: E402

SESSION_BOUND_SCHEMA = "CANONICAL_ID_V2_SESSION_BOUND"
EXACT_SCHEMA = "CANONICAL_ID_V2"


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def derive_split(one: dict, two: dict) -> tuple[list[str], list[str]]:
    """The 6/6 split, derived from both launches and required to agree."""
    if one["session_bound_classes"] != two["session_bound_classes"]:
        raise SystemExit("REFUSED: the two launches disagree on which classes carry session "
                         "provenance; a recipe built over a contested set describes no runtime")
    names = sorted(one["classes"])
    session_bound, exact_only = [], []
    for name in names:
        a, b = one["classes"][name], two["classes"][name]
        if a["session_provenance"] != b["session_provenance"]:
            raise SystemExit("REFUSED: provenance for " + name + " differs between launches")
        if not a["session_provenance"]:
            # An exact-mode class is one whose bytes are reproducible. Asserting
            # that is the whole justification for pinning it exactly; if it moved,
            # it belongs on the session-bound path or nowhere.
            for field in ("raw_sha256", "semantic_sha256"):
                if a[field] != b[field]:
                    raise SystemExit("REFUSED: " + name + " has no session provenance yet its "
                                     + field + " moved between launches; it is not exact-pinnable "
                                     "and must not be treated as session-bound either")
            exact_only.append(name)
            continue
        if a["session_invariant_sha256"] != b["session_invariant_sha256"]:
            raise SystemExit("REFUSED: session invariant for " + name + " is not launch-invariant, "
                             "so no static policy could authorize it")
        for field in ("raw_sha256", "semantic_sha256"):
            if a[field] == b[field]:
                raise SystemExit("REFUSED: " + name + " is treated as session-bound but its "
                                 + field + " did not move; the split is not evidence-backed")
        if a["masked_locations"] != b["masked_locations"]:
            raise SystemExit("REFUSED: mask sites for " + name + " differ between launches")
        session_bound.append(name)
    if not session_bound:
        raise SystemExit("REFUSED: no class carries session provenance; a session-bound recipe "
                         "would authorize nothing")
    return session_bound, exact_only


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launch-one", type=Path, required=True)
    parser.add_argument("--launch-two", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--forge-build", required=True)
    parser.add_argument("--server-jar-sha256", required=True)
    parser.add_argument("--runtime-profile", required=True)
    parser.add_argument("--runtime-manifest-sha256", required=True)
    parser.add_argument("--loader-class", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    one, two = load(args.launch_one), load(args.launch_two)
    session_bound, exact_only = derive_split(one, two)

    manifest = load(args.manifest)
    manifest_sha = normalized_sha256(args.manifest)
    # The manifest and the recipe both key classes by binary (dotted) name; the
    # identity records key them by JVM internal name. Keep the conversion in one
    # place rather than sprinkling .replace across the build.
    hooks_by_class: dict[str, list[str]] = {}
    for hook in manifest["required_hooks"]:
        hooks_by_class.setdefault(hook["class"], []).append(hook["id"])
    for ids in hooks_by_class.values():
        ids.sort()

    dotted_session = sorted(name.replace("/", ".") for name in session_bound)
    identities = {}
    for name in sorted(one["classes"]):
        record = one["classes"][name]
        if name in set(session_bound):
            identities[name.replace("/", ".")] = {
                "schema": SESSION_BOUND_SCHEMA,
                "class_name": name,
                "declaration_order_sha256": record["declaration_order_sha256"],
                "session_invariant_sha256": record["session_invariant_sha256"],
            }
        else:
            identities[name.replace("/", ".")] = {
                "schema": EXACT_SCHEMA,
                "class_name": name,
                "raw_sha256": record["raw_sha256"],
                "semantic_sha256": record["semantic_sha256"],
                "declaration_order_sha256": record["declaration_order_sha256"],
            }

    recipe = {
        "schema_version": 2,
        "kind": "RUSTCRAFT_V2_WRITER_PLAN_RECIPE",
        # The recipe as a whole is session-bound: that is the strictest mode it
        # contains. Classes without provenance keep their own EXACT_V2 identity
        # per class, which the identity inventory below records explicitly.
        "identity_mode": SESSION_BOUND_SCHEMA,
        "all_required_observed": True,
        "required_hooks_manifest_sha256": manifest_sha,
        "required_hooks": [{"id": hook["id"], "status": "OBSERVED"}
                            for hook in manifest["required_hooks"]],
        "expected_class_identities": identities,
        "session_bound_classes": dotted_session,
        "forge_build": args.forge_build,
        "qualification": {
            "profile": args.runtime_profile,
            "minecraft_server_jar_sha256": args.server_jar_sha256,
            "runtime_manifest_sha256": args.runtime_manifest_sha256,
        },
    }

    # The binding is computed over the recipe with the policy block absent, so a
    # policy can carry it without the binding being circular.
    binding = policy_schema.recipe_binding_sha256(recipe)

    policies = {}
    for name in dotted_session:
        record = one["classes"][name.replace(".", "/")]
        policies[name] = policy_schema.build(
            class_name=name,
            expected_session_invariant_sha256=record["session_invariant_sha256"],
            expected_declaration_order_sha256=record["declaration_order_sha256"],
            expected_masked_locations=record["masked_locations"],
            expected_masked_occurrence_count=record["masked_occurrences"],
            runtime_profile=args.runtime_profile,
            runtime_manifest_sha256=args.runtime_manifest_sha256,
            # writer_plan_sha256 names the plan this policy belongs to, which is
            # the file being generated; the generator publishes it, so the
            # placeholder is the binding that IS derivable before the plan
            # exists rather than a digest of a file that does not.
            writer_plan_sha256=binding,
            recipe_sha256=binding,
            required_hook_ids=hooks_by_class[name],
            expected_loader_class=args.loader_class,
            expected_loader_scope="LAUNCHWRAPPER",
        )
    recipe["session_admission_policies"] = policies
    recipe["recipe_binding_sha256"] = binding

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(recipe, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "session_bound_classes": dotted_session,
        "exact_only_classes": sorted(n.replace("/", ".") for n in exact_only),
        "recipe_binding_sha256": binding,
        "out": str(args.out),
    }, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
