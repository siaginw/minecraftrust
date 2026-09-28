"""The V2 live-shadow campaign receipt.

Built BEFORE any shadow event producer exists (Phase A, per the architecture
document), because the receipt is the contract the campaign will be judged
against: defining it after seeing results would let the results shape it.

Two identities are kept strictly apart, mirroring the offline qualification's
static-recipe/dynamic-observation split:

  canonical   -- what is being run: runtime manifest identity, static recipe,
                 writer plan, DLL, all by CONTENT. Must be identical across
                 output locations (proven for the offline path; asserted the
                 same way here).
  provenance  -- machine-local execution facts: paths, ports, process ids.
                 Recorded, never part of identity.

Every receipt states production_authority: false, and the receipt refuses to
serialise with it true. Phase A-C receipts carry zero event counters: that is
correct, because no shadow events have happened, and fabricating them would be
the one thing this schema exists to make detectable.
"""
from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path

from taxonomy import ALL, Outcome, parity_denominator, parity_rate, validate_counts

SCHEMA = "RUSTCRAFT_V2_LIVE_SHADOW_CAMPAIGN"
SCHEMA_VERSION = 1


class ReceiptError(ValueError):
    pass


def _digest(value) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
        .encode("utf-8")).hexdigest()


def empty_counts() -> dict:
    """Every outcome zero. Phases A-C produce exactly this, and that is right."""
    return {o.value: 0 for o in ALL}


def empty_scope_reasons() -> dict:
    from taxonomy import SCOPE_REASONS
    return {r: 0 for r in SCOPE_REASONS}


class CampaignReceipt:
    """Mutable builder with a validating, deterministic serialisation."""

    def __init__(self, campaign_id: str, static: dict, provenance: dict):
        if not campaign_id:
            raise ReceiptError("a campaign must be named")
        # Canonical identity is by content. If a caller passes an absolute
        # path as identity, that is the exact bug the split exists to catch.
        blob = json.dumps(static, sort_keys=True)
        for key in ("runtime_root", "output_dir", "workspace", "java_path",
                    "client_path", "world_path", "server_dir"):
            if key in static:
                raise ReceiptError(
                    "static identity must not carry the machine-local field %r; put it "
                    "in provenance -- location is not identity" % key)
        self.campaign_id = campaign_id
        self.static = static
        self.provenance = dict(provenance)
        self.created_at = time.time()
        self.capability_state = "UNAVAILABLE"
        self.transition_history: list[dict] = []
        self.counts = empty_counts()
        self.scope_reasons = empty_scope_reasons()
        self.phases: dict[str, dict] = {}
        self.client: dict = {}
        self.world_fixture: dict = {}
        self.verdict = "OPEN"
        self.production_authority = False

    # ---- counters ----------------------------------------------------------

    def observe(self, outcome: Outcome, reason: str | None = None) -> None:
        """Record one event outcome. The only way counts move."""
        if not isinstance(outcome, Outcome):
            raise ReceiptError("unknown outcome: %r" % (outcome,))
        self.counts[outcome.value] += 1
        self.counts["observed"] = self.counts.get("observed", 0) + 1
        if outcome is Outcome.EXCLUDED:
            if reason is None:
                raise ReceiptError("an exclusion must name its reason")
            if reason not in self.scope_reasons:
                raise ReceiptError(
                    "unknown scope reason %r; a reason outside the fixed vocabulary "
                    "cannot be counted" % reason)
            self.scope_reasons[reason] += 1
        validate_counts(self.counts)

    def parity(self) -> int:
        return parity_denominator(self.counts)

    def parity_rate(self):
        return parity_rate(self.counts)

    # ---- phases -----------------------------------------------------------

    def phase(self, name: str, result: dict) -> None:
        self.phases[name] = result

    # ---- serialisation ------------------------------------------------------

    def to_document(self) -> dict:
        validate_counts(self.counts)
        if self.production_authority is not False:
            raise ReceiptError(
                "refusing to serialise a campaign receipt with production_authority "
                "true: a live-shadow campaign cannot grant authority, so this value "
                "is corrupt rather than merely unexpected")
        for record in self.transition_history:
            if record.get("authority"):
                raise ReceiptError("an authority transition was recorded")
        document = {
            "schema": SCHEMA,
            "schema_version": SCHEMA_VERSION,
            "campaign_id": self.campaign_id,
            "created_at": self.created_at,
            # Canonical: by content, identical across output locations.
            "canonical": self.static,
            "canonical_sha256": _digest(self.static),
            # Machine-local facts. Recorded for diagnosis, never identity.
            "execution_provenance": self.provenance,
            "capability_state": self.capability_state,
            "transition_history": self.transition_history,
            "counts": dict(self.counts, observed=self.counts.get("observed", 0)),
            "parity_denominator": self.parity(),
            "parity_rate": self.parity_rate(),
            "scope_reasons": dict(self.scope_reasons),
            "phases": self.phases,
            "client": self.client,
            "world_fixture": self.world_fixture,
            "production_authority": False,
            "verdict": self.verdict,
        }
        return document

    def write(self, path: Path) -> str:
        document = self.to_document()
        text = json.dumps(document, indent=2, sort_keys=True) + "\n"
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    @staticmethod
    def load(path: Path) -> dict:
        document = json.loads(Path(path).read_text(encoding="utf-8"))
        CampaignReceipt.validate(document)
        return document

    @staticmethod
    def validate(document: dict) -> None:
        """Re-derive everything that can be re-derived and refuse disagreement."""
        if document.get("schema") != SCHEMA:
            raise ReceiptError("unsupported receipt schema")
        if document.get("production_authority") is not False:
            raise ReceiptError("production_authority must be false")
        counts = {k: v for k, v in document.get("counts", {}).items()
                  if k != "observed"}
        validate_counts(counts)
        if document.get("parity_denominator") != parity_denominator(counts):
            raise ReceiptError(
                "parity denominator disagrees with the counters: %s vs %s"
                % (document.get("parity_denominator"), parity_denominator(counts)))
        canonical = document.get("canonical")
        if document.get("canonical_sha256") != _digest(canonical):
            raise ReceiptError("canonical identity hash does not cover the static block")
        for record in document.get("transition_history", []):
            if record.get("authority"):
                raise ReceiptError("an authority transition was recorded in history")
        # Closing requires closure evidence, which only a real campaign can
        # produce. A Phase A-C receipt that claims CLOSED is claiming something
        # no code in this phase is able to have done.
        if document.get("verdict") == "CLOSED":
            compared = parity_denominator(counts)
            if compared <= 0:
                raise ReceiptError(
                    "a closed campaign must have compared events; a verdict of CLOSED "
                    "with an empty denominator is a closure claim without closure "
                    "evidence")
