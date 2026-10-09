"""Evidence dependency evaluation; certificates never enable production authority.

This module evaluates verifier-produced records, not arbitrary profile expectations.
Fresh observation and concrete artifact verification belong to QualificationEngine.
The graph retains failures and propagates invalidation through every descendant.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from typing import Iterable, Mapping


class EvidenceStatus(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    INCOMPLETE = "INCOMPLETE"
    INVALIDATED = "INVALIDATED"


class Maturity(str, Enum):
    OBSERVED = "OBSERVED"
    OFFLINE_QUALIFIED = "OFFLINE_QUALIFIED"
    LIVE_QUALIFIED = "LIVE_QUALIFIED"
    SHADOW_VALIDATED = "SHADOW_VALIDATED"
    PERFORMANCE_QUALIFIED = "PERFORMANCE_QUALIFIED"
    AUTHORITY_AUTHORIZED = "AUTHORITY_AUTHORIZED"


STAGES = tuple(Maturity)


@dataclass(frozen=True)
class Evidence:
    id: str
    kind: str
    status: EvidenceStatus
    # Digest binds actual observed evidence, not an expected profile value.
    observed_sha256: str | None
    dependencies: tuple[str, ...] = ()
    detail: str = ""

    def __post_init__(self):
        if not isinstance(self.id, str) or not isinstance(self.kind, str) or not self.id or not self.kind:
            raise ValueError("evidence id and kind must be nonempty")
        if isinstance(self.dependencies, str):
            raise ValueError("dependencies must be an iterable of evidence ids")
        object.__setattr__(self, "dependencies", tuple(self.dependencies))
        if any(not isinstance(key, str) or not key for key in self.dependencies):
            raise ValueError("dependencies must contain nonempty evidence ids")
        if not isinstance(self.status, EvidenceStatus):
            raise ValueError("unknown evidence status")
        if len(set(self.dependencies)) != len(self.dependencies):
            raise ValueError("duplicate dependency: " + self.id)
        if self.status == EvidenceStatus.PASS:
            digest = self.observed_sha256
            if not isinstance(digest, str) or len(digest) != 64 or any(
                c not in "0123456789abcdef" for c in digest
            ):
                raise ValueError("PASS requires observed SHA-256: " + self.id)


def digest_json(value: object) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"),
                     ensure_ascii=True, allow_nan=False).encode("ascii")
    return hashlib.sha256(raw).hexdigest()


def evaluate(
    records: Iterable[Evidence],
    requirements: Mapping[Maturity, Iterable[str]],
    *,
    context: Mapping[str, object],
    requested: Maturity = Maturity.OFFLINE_QUALIFIED,
) -> dict:
    """Produce a deterministic, non-authorizing certificate for an acyclic graph.

    Requirements are cumulative. Empty/missing stages are INCOMPLETE, never
    vacuous success. A higher stage cannot leap over an incomplete lower stage.
    AUTHORITY_AUTHORIZED is always blocked by the program's production gate.
    """
    if not isinstance(requested, Maturity):
        raise ValueError("unknown requested maturity")
    if not context.get("profile_id") or not context.get("observation_session"):
        raise ValueError("profile and fresh observation session binding required")
    if context.get("identity_schema") not in ("RAW_SHA256", "CANONICAL_ID_V2", "CANONICAL_ID_V2_SESSION_BOUND"):
        raise ValueError("V1/unknown identity cannot issue a new certificate")
    nodes: dict[str, Evidence] = {}
    for node in records:
        if node.id in nodes:
            raise ValueError("duplicate evidence id: " + node.id)
        nodes[node.id] = node
    for stage in requirements:
        if not isinstance(stage, Maturity):
            raise ValueError("unknown requirement maturity")
    requirements = {stage: tuple(keys) for stage, keys in requirements.items()}
    resolved: dict[str, dict] = {}
    visiting: set[str] = set()

    def visit(key: str) -> dict:
        if key in resolved:
            return resolved[key]
        if key in visiting:
            raise ValueError("cyclic evidence dependency: " + key)
        node = nodes.get(key)
        if node is None:
            resolved[key] = {"id": key, "kind": "MISSING_EVIDENCE",
                             "status": EvidenceStatus.INCOMPLETE.value,
                             "detail": "missing dependency", "observed_sha256": None,
                             "dependencies": [], "blocked_by": []}
            return resolved[key]
        visiting.add(key)
        dependencies = [visit(dep) for dep in node.dependencies]
        visiting.remove(key)
        blockers = [row["id"] for row in dependencies
                    if row["status"] != EvidenceStatus.PASS.value]
        status = node.status
        if blockers and status == EvidenceStatus.PASS:
            status = (EvidenceStatus.INVALIDATED
                      if any(row["status"] in ("FAIL", "INVALIDATED")
                             for row in dependencies)
                      else EvidenceStatus.INCOMPLETE)
        row = {"id": key, "kind": node.kind, "status": status.value,
               "observed_sha256": node.observed_sha256,
               "dependencies": list(node.dependencies), "blocked_by": blockers,
               "detail": node.detail}
        resolved[key] = row
        return row

    for key in sorted(nodes):
        visit(key)
    stage_results = []
    achieved = None
    prior_pass = True
    for stage in STAGES:
        required = tuple(requirements.get(stage, ()))
        if len(set(required)) != len(required):
            raise ValueError("duplicate stage requirement: " + stage.value)
        rows = [visit(key) for key in required]
        blockers = [row["id"] for row in rows
                    if row["status"] != EvidenceStatus.PASS.value]
        if not required:
            blockers.append("NO_REQUIREMENTS_DEFINED")
        if not prior_pass:
            blockers.append("PRIOR_STAGE_NOT_QUALIFIED")
        if stage == Maturity.AUTHORITY_AUTHORIZED:
            blockers.append("PRODUCTION_AUTHORITY_DISABLED")
        passed = not blockers
        if passed:
            achieved = stage.value
        stage_results.append({"maturity": stage.value,
                              "status": "PASS" if passed else "INCOMPLETE",
                              "required": list(required), "blocked_by": blockers})
        prior_pass = passed
    selected = stage_results[STAGES.index(requested)]
    bad = [row for row in resolved.values()
           if row["status"] in ("FAIL", "INVALIDATED")]
    # Unrelated bad evidence stays visible but does not revoke an independent
    # operation's graph. The requested stage must actually depend on it.
    status = selected["status"]
    required_closure: set[str] = set()

    def collect(key: str):
        if key in required_closure:
            return
        required_closure.add(key)
        if key in nodes:
            for dependency in nodes[key].dependencies:
                collect(dependency)

    for stage in STAGES[: STAGES.index(requested) + 1]:
        for key in requirements.get(stage, ()):
            collect(key)
    if any(row["id"] in required_closure for row in bad):
        status = "FAIL"
    result = {"schema_version": 2, "kind": "QUALIFICATION_CERTIFICATE_V2",
              "context": dict(context), "requested_maturity": requested.value,
              "maturity": achieved, "status": status,
              "production_authority": False, "stages": stage_results,
              "evidence": [resolved[key] for key in sorted(resolved)]}
    result["certificate_sha256"] = digest_json(result)
    return result
