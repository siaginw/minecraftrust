"""Offline coverage and provenance-shape checks; not upstream or benchmark proof."""

from __future__ import annotations

import copy
import datetime
import json
from pathlib import Path
import re
import unittest
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "machine/external-reuse/ultra-candidates.json"
DOCUMENT = ROOT / "docs/research/external-candidate-decisions.md"
REQUEST = ROOT / "docs/engineering/architecture-hardening-h22-5.md"
EXTRAS = {
    "jni-rs", "jbindgen", "ristretto_classfile", "cafebabe", "slab",
    "RocksDB", "rjvm", "Feather", "legion", "generational-arena",
    "valence_nbt", "miniz_oxide", "Tokio", "mio", "fastanvil",
    "Flecs-Rust", "mcproto-rs", "mc_protocol", "MCSLTeam mcproto",
    "Prismarine minecraft-data", "node-minecraft-protocol", "std::simd",
}
DECISIONS = {"ADOPT", "PROTOTYPE", "STUDY", "BORROW_ALGORITHM", "AVOID"}


def required_names(request: str) -> set[str]:
    match = re.search(r"Cover at least:\s*(.*?)\s*Also retain decisions", request, re.S)
    if not match:
        raise ValueError("H22.5 candidate list not found")
    return {part.strip().rstrip(".") for part in match[1].split(";")}


def validate(data: dict, document: str, required: set[str]) -> None:
    def check(condition: bool, message: str) -> None:
        if not condition:
            raise ValueError(message)

    def text(value: object, context: str) -> None:
        check(isinstance(value, str) and bool(value.strip()), context + ": missing text")

    def url(value: object, context: str) -> None:
        text(value, context)
        parsed = urlparse(value)
        check(parsed.scheme == "https" and bool(parsed.netloc), context + ": HTTPS URL required")

    check(data.get("schema") == "RUSTCRAFT_EXTERNAL_CANDIDATE_DECISIONS_V1", "schema")
    check(data.get("completion_status") == "DECISION_LEDGER_ONLY_EXPERIMENTS_PENDING", "completion status")
    check(bool(re.fullmatch(r"[0-9a-f]{40}", data.get("source_baseline", ""))), "baseline")
    datetime.date.fromisoformat(data["reviewed_at"])
    expected_constraints = {
        "production_native_packet_authority": "DISABLED",
        "mck6_semantics": "UNCHANGED",
        "compression_backend": "UNCHANGED",
        "custom_jvm": "RESEARCH_ONLY_DO_NOT_BUILD_WITHOUT_H19_PROOF",
        "mca_nbt": "AUTHORITATIVE_COMPATIBLE_FORMAT_RETAINED",
        "new_dependencies_installed_by_ledger": False,
    }
    check(data.get("constraints") == expected_constraints, "production constraints")
    declared = data.get("required_candidates", [])
    check(len(declared) == len(set(declared)) and set(declared) == required, "required inventory drift")
    entries = data.get("candidates", [])
    check(isinstance(entries, list) and bool(entries), "candidate list")
    names = [entry["name"] for entry in entries]
    ids = [entry["id"] for entry in entries]
    check(len(names) == len(set(names)) and len(ids) == len(set(ids)), "duplicate candidate")
    check(required <= set(names), "missing required candidates")
    check(EXTRAS <= set(names), "missing serious extras")
    check(data.get("candidate_count") == len(entries), "candidate count")

    for entry in entries:
        name = entry["name"]
        check(bool(re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", entry["id"])), name + ": id")
        for field in ("language", "reviewed_ref", "subsystem", "rationale", "performance_value",
                      "migration_value", "integration_risk", "integration_difficulty",
                      "integration_difficulty_basis", "reuse_mode"):
            text(entry.get(field), name + ": " + field)
        url(entry.get("repository_url"), name + ": repository")
        check(entry.get("reviewed_at") == data["reviewed_at"], name + ": review date")
        check(entry.get("decision") in DECISIONS, name + ": decision")
        check(entry.get("decision") != "ADOPT", name + ": unsupported adoption without experiment evidence")
        check(entry.get("priority") in {"P1", "P2", "P3"}, name + ": priority")
        check(entry.get("decision_maturity") == "RESEARCH_HYPOTHESIS", name + ": maturity")
        check(entry.get("production_dependency_added_by_this_ledger") is False, name + ": dependency claim")
        check(entry.get("production_authority_eligible") is False, name + ": authority claim")
        activity = entry.get("activity", {})
        text(activity.get("observation"), name + ": activity")
        url(activity.get("evidence_url"), name + ": activity evidence")
        check(activity.get("observed_at") == data["reviewed_at"], name + ": activity date")
        check(bool(entry.get("semantic_differences")), name + ": semantic differences")
        for field in ("sources", "notice_obligations", "licenses"):
            check(isinstance(entry.get(field), list) and bool(entry[field]), name + ": " + field)
        for source in entry["sources"]:
            url(source, name + ": source")
        for license_entry in entry["licenses"]:
            for field in ("component", "expression", "scope", "notice_obligations"):
                text(license_entry.get(field), name + ": license " + field)
            url(license_entry.get("evidence_url"), name + ": license evidence")
        pin = entry.get("pin")
        if entry["decision"] == "PROTOTYPE":
            check(isinstance(pin, dict), name + ": prototype pin")
            check(pin.get("kind") in {"crate_version", "upstream_tag", "commit"}, name + ": pin kind")
            text(pin.get("package"), name + ": pin package")
            version = pin.get("version", "")
            patterns = {
                "commit": r"[0-9a-f]{40}",
                "crate_version": r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?",
                "upstream_tag": r"v?\d+\.\d+(?:\.\d+)?(?:[-+][A-Za-z0-9.-]+)?",
            }
            pattern = patterns[pin["kind"]]
            check(bool(re.fullmatch(pattern, version)), name + ": exact pin required")
            url(pin.get("evidence_url"), name + ": pin evidence")
            expected_status = "EXISTING_H1_PIN_REFERENCED_ONLY" if name == "cafebabe" else "PLANNED_NOT_INSTALLED"
            check(pin.get("execution_status") == expected_status, name + ": pin execution claim")
            checksum = pin.get("registry_checksum")
            check(checksum is None or bool(re.fullmatch(r"[0-9a-f]{64}", checksum)), name + ": checksum")
        else:
            check(pin is None, name + ": non-prototype unexpected pin")
        experiment = entry.get("experiment", {})
        expected_status = "REFERENCED_EXISTING_H1_PROTOTYPE_NOT_REVALIDATED" if name == "cafebabe" else "PENDING"
        check(experiment.get("status") == expected_status, name + ": experiment completion claim")
        text(experiment.get("plan"), name + ": experiment plan")
        check(bool(experiment.get("required_evidence")), name + ": required evidence")
        check(bool(experiment.get("stop_conditions")), name + ": stop conditions")
        check(experiment.get("results") == [], name + ": unsupported results")
        check(experiment.get("measured_performance") is None, name + ": unsupported measurement")
        for reference in entry.get("related_evidence", []):
            check(not Path(reference).is_absolute() and ".." not in Path(reference).parts, name + ": unsafe evidence path")
            check((ROOT / reference).is_file(), name + ": missing related evidence")
        check("[" + name + "](" in document, name + ": missing documentation row")
    by_name = {entry["name"]: entry for entry in entries}
    for first, second in (("FerrumC", "Temper"), ("Espresso", "Crema"), ("Ristretto", "ristretto_classfile")):
        check(by_name[first]["id"] != by_name[second]["id"], first + ": conflated candidate")
    check(by_name["FerrumC"]["repository_url"] != by_name["Temper"]["repository_url"], "FerrumC/Temper repository conflation")
    check(by_name["cafebabe"]["related_evidence"] == ["machine/external-reuse/classfile-crosscheck.json"], "cafebabe evidence ownership")
    check(by_name["jbindgen"]["repository_url"] == "https://github.com/jni-rs/jbindgen", "wrong jbindgen project")


class ExternalReuseLedgerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = json.loads(LEDGER.read_text(encoding="utf-8"))
        cls.document = DOCUMENT.read_text(encoding="utf-8")
        cls.required = required_names(REQUEST.read_text(encoding="utf-8"))

    def mutated(self):
        return copy.deepcopy(self.data)

    def reject(self, data, message):
        with self.assertRaisesRegex(ValueError, message):
            validate(data, self.document, self.required)

    def test_complete_inventory(self):
        validate(self.data, self.document, self.required)

    def test_missing_required_candidate(self):
        data = self.mutated()
        data["candidates"] = [c for c in data["candidates"] if c["name"] != "Temper"]
        self.reject(data, "missing required")

    def test_missing_serious_extra(self):
        data = self.mutated()
        data["candidates"] = [c for c in data["candidates"] if c["name"] != "slab"]
        self.reject(data, "missing serious extras")

    def test_duplicate_candidate(self):
        data = self.mutated()
        data["candidates"].append(copy.deepcopy(data["candidates"][0]))
        self.reject(data, "duplicate candidate")

    def test_missing_prototype_pin(self):
        data = self.mutated()
        next(c for c in data["candidates"] if c["decision"] == "PROTOTYPE")["pin"] = None
        self.reject(data, "prototype pin")

    def test_mutable_prototype_pin(self):
        data = self.mutated()
        next(c for c in data["candidates"] if c["decision"] == "PROTOTYPE")["pin"]["version"] = "latest"
        self.reject(data, "exact pin")

    def test_missing_license_evidence(self):
        data = self.mutated()
        data["candidates"][0]["licenses"][0]["evidence_url"] = ""
        self.reject(data, "license evidence")

    def test_unsupported_completion(self):
        data = self.mutated()
        data["candidates"][0]["experiment"]["status"] = "COMPLETE"
        self.reject(data, "completion claim")

    def test_unsupported_measurement(self):
        data = self.mutated()
        data["candidates"][0]["experiment"]["measured_performance"] = {"speedup": 2}
        self.reject(data, "unsupported measurement")

    def test_authority_claim(self):
        data = self.mutated()
        data["candidates"][0]["production_authority_eligible"] = True
        self.reject(data, "authority claim")

    def test_backend_change(self):
        data = self.mutated()
        data["constraints"]["compression_backend"] = "LIBDEFLATE"
        self.reject(data, "production constraints")

    def test_unsupported_adoption(self):
        data = self.mutated()
        data["candidates"][0]["decision"] = "ADOPT"
        self.reject(data, "unsupported adoption")

    def test_documentation_omission(self):
        with self.assertRaisesRegex(ValueError, "missing documentation row"):
            validate(self.data, self.document.replace("[Valence](", "[Removed]("), self.required)


if __name__ == "__main__":
    unittest.main(verbosity=2)
