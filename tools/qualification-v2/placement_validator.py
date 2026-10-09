"""Engine-facing placement validator for a V2 requalification.

The engine invokes this as a pinned subprocess:

    placement_validator.py --config <config.json> --request <request.json> --out <witness.json>

The oracle is tools/testing/transformer_verify.py, which disassembles the pre-
and post-writer dumps with `javap`. That matters: the transformer is never
allowed to be its own verifier. `javap` reads the bytes the loader would have
defined, and the checks below are all restatements of what that independent
disassembly says:

  anchor_order        the original instruction sequence survives, in order,
                      with exactly the plan's call groups added;
  exception_paths     each injected bracket carries its catch-all handler, and
                      the post exception table is a superset of the pre one;
  undeclared_edits    methods the plan never named are byte-identical pre/post;
  pre_post_relation  the pre/post relation actually observed, per class.

A check that cannot be established is reported INCOMPLETE, never PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/testing"))


def sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    import transformer_verify  # noqa: E402  (import after sys.path is set)

    config = json.loads(args.config.read_text(encoding="utf-8"))
    request = json.loads(args.request.read_text(encoding="utf-8"))
    request_sha = sha(args.request)
    pre_dir, post_dir = Path(config["pre_dump"]), Path(config["post_dump"])
    plan = transformer_verify.hook_plan(Path(config["plan"]))
    profile = json.loads(Path(config["profile"]).read_text(encoding="utf-8"))
    javap = Path(config["java_home"]) / "bin/javap.exe"

    verification = transformer_verify.verify(pre_dir, post_dir, plan, javap, profile)
    failures = verification["nonqualified"]
    qualified = bool(verification["all_qualified"])

    classes = sorted({hook["class"] for hook in plan})
    changed, unchanged, missing = [], [], []
    for name in classes:
        rel = name.replace(".", "/") + ".class"
        pre_path, post_path = pre_dir / rel, post_dir / rel
        if not post_path.is_file():
            missing.append(name)
        elif not pre_path.is_file():
            missing.append(name)
        elif sha(pre_path) == sha(post_path):
            unchanged.append(name)
        else:
            changed.append(name)

    # Every class in either dump, not just the planned ones: the point of this
    # check is that the writer touched nothing it was not told to touch, and
    # that question cannot be asked of a subset chosen in advance.
    #
    # The comparison is over classes present in BOTH dumps. The two dumps come
    # from two separate launches and no launch loads exactly the same set of
    # classes, so a class that only one of them defined says nothing about
    # whether the writer edited it. Those classes are still counted and
    # reported; they just cannot decide a check that is about edited bytes.
    def inventory(root: Path) -> set:
        return {q.relative_to(root).as_posix() for q in root.rglob("*.class")}

    pre_all, post_all = inventory(pre_dir), inventory(post_dir)
    shared = sorted(pre_all & post_all)
    outside = [rel for rel in shared if rel[:-6].replace("/", ".") not in set(classes)]
    moved_outside = [rel for rel in outside
                     if sha(pre_dir / rel) != sha(post_dir / rel)]
    only_pre = sorted(pre_all - post_all)
    only_post = sorted(post_all - pre_all)
    only_one_side = only_pre + only_post

    sites = sorted(site["id"] for site in request["required_sites"])
    # The classes the plan actually promised to change, derived from the sites
    # rather than from the site count: sixty-four hooks live in a dozen classes,
    # so comparing the two numbers was never a relation between anything.
    #
    # Sites are keyed by JVM internal name; `changed` above is by binary name.
    # Comparing them without converting first made every site class look absent
    # from a class that had in fact moved.
    site_classes = sorted({site["class"] for site in request["required_sites"]})
    moved_internal = {name.replace(".", "/") for name in changed}
    still_internal = {name.replace(".", "/") for name in unchanged}
    silent = [name for name in site_classes if name not in moved_internal]
    # Planned classes the writer moved that carry no required site: the plan
    # hooks them through a path the site list does not describe. Legitimate, but
    # it must be visible rather than inferred from a count.
    moved_without_a_site = sorted(moved_internal - set(site_classes))
    hooked = {failure.get("id") for failure in failures if failure.get("id")}

    def check(ok: bool, measurements: dict, incomplete: bool = False) -> dict:
        return {"status": "INCOMPLETE" if incomplete else ("PASS" if ok else "FAIL"),
                "measurements": measurements}

    witness = {
        "schema": "RUSTCRAFT_PLACEMENT_WITNESS_V2",
        "session": request["session"], "challenge": request["challenge"],
        "request_sha256": request_sha,
        "observation_sha256": request["observation_sha256"],
        "covered_sites": sites,
        "checks": {
            "anchor_order": check(
                qualified and not hooked,
                {"oracle": "javap_disassembly_of_pre_and_post_dumps",
                 "required_hooks": len(plan),
                 "unqualified_hook_ids": sorted(hooked),
                 "nonqualified_detail": failures[:10]}),
            "exception_paths": check(
                qualified,
                {"oracle": "javap_exception_tables",
                 "note": "every injected bracket's catch-all is part of the ordered "
                         "group check the oracle already applied per hook",
                 "nonqualified_count": len(failures)}),
            "undeclared_edits": check(
                not missing and not moved_outside,
                {"classes_considered": len(classes),
                 "classes_modified_by_the_writer": changed,
                 "classes_byte_identical_pre_post": unchanged,
                 "classes_missing_from_a_dump": missing,
                 "classes_in_both_dumps_but_not_in_plan": len(outside),
                 "unplanned_classes_whose_bytes_moved": moved_outside,
                 "classes_only_the_pre_launch_defined": len(only_pre),
                 "classes_only_the_post_launch_defined": len(only_post),
                 "classes_present_on_one_side_only": only_one_side},
                incomplete=bool(missing)),
            "pre_post_relation": check(
                bool(site_classes) and not silent,
                {"hooked_site_count": len(request["required_sites"]),
                 "classes_carrying_hooks": site_classes,
                 "classes_whose_bytes_moved": len(changed),
                 "classes_moved_without_a_required_site": moved_without_a_site,
                 "planned_classes_byte_identical_pre_post": sorted(still_internal),
                 "classes_carrying_hooks_whose_bytes_did_not_move": silent,
                 "pre_sha256": {name: sha(pre_dir / (name.replace('.', '/') + '.class'))
                                for name in classes if (pre_dir / (name.replace('.', '/') + '.class')).is_file()},
                 "post_sha256": {name: sha(post_dir / (name.replace('.', '/') + '.class'))
                                 for name in classes if (post_dir / (name.replace('.', '/') + '.class')).is_file()}},
                incomplete=not changed),
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(witness, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"schema": "RUSTCRAFT_VALIDATOR_ACK_V2", "session": request["session"],
                      "challenge": request["challenge"], "output_sha256": sha(args.out)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
