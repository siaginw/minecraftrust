"""Bounded, pinned HotSpot link-trigger controls; never a runtime qualification gate."""
import argparse
import json
import os
from pathlib import Path
import secrets
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools/writer-placement-v2"))
from capture import capture
from io_utils import process, sha, strict, write
from validate import ensure_inventory, inside_target
sys.path.insert(0, str(ROOT / "tools/testing"))
from hardening_guard import inspect
from contract import require, validate_row, proposal_pair, frame_types


def inventory(paths):
    return {str(p.resolve()): sha(p) for p in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--java-home", type=Path, default=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01"))
    parser.add_argument("--asm", type=Path, default=Path("D:/rustcraft-runtime-targets/clean-forge-2860/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"))
    parser.add_argument("--rust-parser", type=Path, default=ROOT / "tools/classfile-crosscheck/target/debug/rustcraft-classfile-crosscheck.exe")
    args = parser.parse_args()
    output = inside_target(args.output or ROOT / "target/hotspot-verification-proof" / uuid.uuid4().hex)
    require(output.is_relative_to(ROOT / "target/hotspot-verification-proof"), "output must stay in isolated proof target")
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(schema="HOTSPOT_VERIFICATION_PROOF_CAMPAIGN_V1", status="FAIL", output=str(output),
                   production_authority=False, actual_forge_integration=False,
                   general_frame_qualification=False, placement_gate_changed=False,
                   scope="pinned Java8 closed fixture loader; source-justified link trigger and refusal controls",
                   environment_policy=dict(removed=["JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "CLASSPATH"],
                                           explicit_classpath=True, shell=False, no_agents=True),
                   budgets=dict(subprocess_seconds=60, per_stream_bytes=32*1024*1024, retained_bytes=128*1024*1024))
    started = time.monotonic()
    try:
        receipt["guard_before"] = inspect(ROOT)
        require(receipt["guard_before"]["status"] == "PASS", "isolation before")
        source_files = [p for p in HERE.rglob("*") if p.is_file() and "__pycache__" not in p.parts]
        source_files += [ROOT / "tools/writer-placement-v2" / name for name in
                         ("capture.py", "classfile.py", "io_utils.py", "validate.py", "validator.py")]
        source_files += [ROOT / name for name in ("tools/classfile-crosscheck/crosscheck.py",
                         "tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java",
                         "tools/testing/hardening_guard.py", "machine/architecture-hardening/isolation.json",
                         "docs/engineering/hotspot-verification-proof.md")]
        sources = inventory(source_files)
        receipt["source_hashes"] = sources
        provenance = strict((HERE / "primary-source-provenance.json").read_bytes())
        require(provenance["schema"] == "HOTSPOT_PRIMARY_SOURCE_REVIEW_V1", "source provenance schema")
        primary = {row["evidence_file"]: row["sha256"] for row in provenance["sources"]}
        ensure_inventory(primary)
        receipt["primary_source_evidence_hashes"] = primary
        receipt["source_commit"] = provenance["commit"]
        receipt["source_identity_limit"] = provenance["source_identity_limit"]
        require(sha(args.java_home / "release") == provenance["local_release_sha256"], "runtime source lead changed")
        tool_files = [args.java_home / p for p in ("bin/java.exe", "bin/javac.exe", "release", "jre/lib/rt.jar", "lib/tools.jar")]
        tool_files += list((args.java_home / "jre/bin").rglob("*.dll"))
        tool_files += [args.asm, args.rust_parser, Path(sys.executable)]
        tools = inventory(tool_files)
        receipt["tool_hashes"] = tools
        logs = output / "logs"; logs.mkdir()
        classes = output / "classes"; classes.mkdir()
        java = (args.java_home / "bin/java.exe").resolve()
        javac = (args.java_home / "bin/javac.exe").resolve()
        process([javac, "-Xlint:all", "-Werror", "-cp", args.asm, "-d", classes,
                 HERE / "VerificationFixtures.java", HERE / "VerificationProbe.java"], logs, "compile-probes")
        process([javac, "-cp", args.asm, "-d", classes,
                 ROOT / "tools/bridge/src/com/rustcraft/coremod/CanonicalClassIdentityV2.java"], logs, "compile-identity")
        compiled = inventory(list(classes.rglob("*.class")))
        receipt["compiled_class_hashes"] = compiled
        classpath = str(classes.resolve()) + os.pathsep + str(args.asm.resolve())
        generated = output / "fixtures"
        require(process([java, "-cp", classpath, "VerificationFixtures", generated], logs, "generate").strip() == "FIXTURES 8", "fixture cardinality")
        fixture_files = sorted(generated.glob("*.class"))
        require(len(fixture_files) == 8, "fixture inventory")
        fixture_pins = inventory(fixture_files)
        receipt["fixture_hashes"] = fixture_pins
        fixtures = capture(fixture_files, output / "parser", str(java), classpath, str(args.rust_parser.resolve()), False)
        expected = {name: ("REJECTED_VERIFY_ERROR" if name in ("bad-dormant", "bad-frame") else "VERIFIED_BY_PINNED_LINK_TRIGGER") for name in fixtures}
        cases = [(name, name, "-Xverify:all", fixture_pins[str((generated / (name + ".class")).resolve())], status) for name, status in sorted(expected.items())]
        valid_pin = fixture_pins[str((generated / "valid-object.class").resolve())]
        cases += [("foreign-loader", "valid-object", "-Xverify:all", valid_pin, "REFUSED_LOADER"),
                  ("changed-byte-pin", "valid-object", "-Xverify:all", "0" * 64, "REFUSED_HASH"),
                  ("disabled-verification", "valid-object", "-Xverify:none", valid_pin, "REFUSED_VERIFY_FLAGS")]
        rows = {}
        for name, fixture, flag, pin, status in cases:
            session, challenge = uuid.uuid4().hex, secrets.token_hex(32)
            types = frame_types(fixtures[fixture]["dump"])
            text = process([java, flag, "-cp", classes, "VerificationProbe", generated / (fixture + ".class"), pin, session, challenge, name, *types], logs, "probe-" + name)
            require(len(text.splitlines()) == 1, "proof output count")
            row = strict(text)
            validate_row(row, session, challenge, name, status, pin, types)
            rows[name] = row
        write(output / "proof-rows.json", rows)
        known = {binding[0] for row in rows.values() for binding in row.get("frame_type_bindings", []) if binding[1].startswith("BOUND_")}
        relations = []
        for before, after in (("valid-top", "valid-refined"), ("valid-object", "valid-refined"), ("valid-object", "valid-max")):
            require(rows[before]["status"] == rows[after]["status"] == "VERIFIED_BY_PINNED_LINK_TRIGGER", "pair did not both verify")
            relation = proposal_pair(fixtures[before], fixtures[after], known)
            require(fixtures[before]["receipt"][2] != fixtures[after]["receipt"][2], "changed verification metadata lost from V2 hash")
            relations.append(dict(before=before, after=after, before_identity=fixtures[before]["receipt"], after_identity=fixtures[after]["receipt"], relation=relation))
        write(output / "fixture-relations.json", relations)
        missing = rows["null-missing-type"]
        require(missing["status"] == "VERIFIED_BY_PINNED_LINK_TRIGGER", "missing-type counterexample changed")
        require("missing/Unknown" in frame_types(fixtures["null-missing-type"]["dump"]), "missing frame type absent")
        require("missing.Unknown" not in missing["verification_loader_requests"], "verifier requested missing frame type")
        require("missing.Unknown" in missing["loader_requests"] and missing["frame_type_binding_complete"] is False, "explicit frame binding failed to expose missing type")
        counterexample = dict(status="VERIFIED_BUT_HIERARCHY_UNQUALIFIED", fixture="null-missing-type",
                              actual_sha256=missing["actual_sha256"], frame_types=frame_types(fixtures["null-missing-type"]["dump"]),
                              verifier_loader_requests=missing["verification_loader_requests"], explicit_binding_loader_requests=missing["loader_requests"], proof_row=missing,
                              implication="Whole-class verification does not prove every frame object type was resolved or exists in the qualified hierarchy.",
                              production_authority=False)
        write(output / "unresolved-frame-type-counterexample.json", counterexample)
        process([sys.executable, "-B", HERE / "test_contract.py", output], logs, "contract-tests")
        require(inventory(list(generated.glob("*.class"))) == fixture_pins, "fixture inventory changed")
        require(inventory(list(classes.rglob("*.class"))) == compiled, "compiled inventory changed")
        ensure_inventory({**sources, **tools, **compiled, **fixture_pins, **primary})
        receipt.update(status="PASS", unit_tests=8, independent_parser_agreements=8, fresh_java_process_controls=11,
                       fixture_relation_controls=3, invalid_private_method_rejected_after_load=True,
                       invalid_stackmap_rejected_after_load=True, target_initializer_executed=False,
                       unresolved_frame_type_counterexample="PRESERVED", source_review_required_for_other_vms=True)
    except Exception as error:
        receipt.update(status="FAIL", error=type(error).__name__ + ": " + str(error))
    finally:
        try:
            receipt["guard_after"] = inspect(ROOT)
            if receipt["guard_after"]["status"] != "PASS": receipt.update(status="FAIL", error="isolation after")
        except Exception as error: receipt.update(status="FAIL", guard_error=str(error))
        receipt["elapsed_seconds"] = time.monotonic() - started
        receipt["retained_bytes"] = sum(p.stat().st_size for p in output.rglob("*") if p.is_file())
        if receipt["retained_bytes"] > receipt["budgets"]["retained_bytes"]:
            receipt.update(status="FAIL", error="retained-byte budget exceeded")
        write(output / "campaign.json", receipt)
    print(json.dumps(dict(status=receipt["status"], receipt=str(output / "campaign.json"), sha256=sha(output / "campaign.json"), error=receipt.get("error"))))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__": raise SystemExit(main())
