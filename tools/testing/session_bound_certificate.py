"""Session-bound identity evidence certificate: schema, strict validation and
canonical rendering.

The governing rule this module exists to enforce: the class STRUCTURE determines
WHAT may be normalized, and this certificate determines WHETHER normalization is
authorized in one specific process/transformation session. Structure alone never
authorizes masking, and a missing or disagreeing certificate is INCOMPLETE or a
fail-closed refusal -- never a silent fallback to session-bound masking.

The rendering here is byte-identical to
`com.rustcraft.coremod.SessionBoundIdentityCertificate.toJson()` so the
certificate hash computed offline is the hash the transforming JVM re-computes.
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Dict, Iterable, List

SCHEMA = "RUSTCRAFT_SESSION_BOUND_IDENTITY_CERTIFICATE"
SCHEMA_VERSION = 1
PROVENANCE = "session-bound-identity-certificate/v1"

SESSION_BOUND_SCHEMA = "CANONICAL_ID_V2_SESSION_BOUND"
EXACT_SCHEMA = "CANONICAL_ID_V2"

#: Canonical key order. The certificate hash is the hash of this exact rendering.
KEYS = (
    "schema",
    "schema_version",
    "provenance",
    "process_id",
    "transformation_session_id",
    "defining_loader_identity",
    "class_name",
    "pre_writer_raw_sha256",
    "exact_semantic_sha256",
    "exact_declaration_order_sha256",
    "session_invariant_sha256",
    "expected_session_uuid",
    "masked_annotation_locations",
    "distinct_masked_uuid_count",
    "masked_occurrence_count",
    "recipe_sha256",
    "runtime_manifest_sha256",
    "policy_sha256",
    "acquisition_evidence_sha256",
)

#: Bindings that may legitimately be absent. The first two are plan-revision
#: identities a plan need not publish; the third is the acquisition digest, which
#: a certificate minted during a live class transformation cannot know yet,
#: because the record it would name describes a writer run still to come. The
#: chain binds certificate and acquisition together after the fact instead.
CONDITIONAL_KEYS = ("recipe_sha256", "runtime_manifest_sha256", "acquisition_evidence_sha256")

SHA256 = re.compile(r"[0-9a-f]{64}")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

_STRING_KEYS = {k for k in KEYS if k not in {
    "schema_version", "masked_annotation_locations",
    "distinct_masked_uuid_count", "masked_occurrence_count"}}
_HASH_KEYS = {k for k in KEYS if k.endswith("_sha256")}
_UUID_KEYS = {"process_id", "transformation_session_id", "expected_session_uuid"}
_LIST_KEYS = {"masked_annotation_locations"}
_COUNT_KEYS = {"distinct_masked_uuid_count", "masked_occurrence_count"}


class CertificateError(ValueError):
    """The document is not a usable session-bound certificate."""


def render(document: Dict[str, Any]) -> str:
    """Canonical JSON rendering. Byte-identical to the Java implementation."""
    parts = []
    for key in KEYS:
        if key in CONDITIONAL_KEYS and key not in document:
            continue
        value = document[key]
        if key in _LIST_KEYS:
            rendered = "[" + ",".join(_quote(item) for item in value) + "]"
        elif key in _COUNT_KEYS or key == "schema_version":
            rendered = str(int(value))
        else:
            rendered = _quote(value)
        parts.append(_quote(key) + ":" + rendered)
    return "{" + ",".join(parts) + "}"


def _quote(value: str) -> str:
    # Non-ASCII passes through unescaped, matching the Java writer.
    out = ['"']
    for character in str(value):
        if character in '"\\':
            out.append("\\" + character)
        elif character < " ":
            out.append("\\u%04x" % ord(character))
        else:
            out.append(character)
    out.append('"')
    return "".join(out)


def certificate_sha256(document: Dict[str, Any]) -> str:
    return hashlib.sha256(render(document).encode("utf-8")).hexdigest()


def validate(document: Any) -> Dict[str, Any]:
    """Strict validation mirroring the Java parser. Returns the document.

    Unknown fields, missing fields, wrong types, malformed values, unsorted
    locations, several distinct session UUIDs and impossible counts all raise:
    a certificate that cannot be read exactly is not a certificate.
    """
    if not isinstance(document, dict):
        raise CertificateError("certificate must be a JSON object")
    unknown = sorted(set(document) - set(KEYS))
    if unknown:
        raise CertificateError("unknown certificate field(s): %s" % ", ".join(unknown))
    for key in KEYS:
        if key in CONDITIONAL_KEYS:
            continue
        if key not in document:
            raise CertificateError("certificate is missing %s" % key)
    if document["schema"] != SCHEMA:
        raise CertificateError("unexpected certificate schema")
    if document["schema_version"] != SCHEMA_VERSION or isinstance(document["schema_version"], bool):
        raise CertificateError("unsupported certificate version")
    if document["provenance"] != PROVENANCE:
        raise CertificateError("unexpected certificate provenance")
    for key in _STRING_KEYS:
        if key not in document:
            continue
        value = document[key]
        if not isinstance(value, str) or not value:
            raise CertificateError("%s must be a non-empty string" % key)
        if key in _HASH_KEYS and not SHA256.fullmatch(value):
            raise CertificateError("%s is not a SHA-256 digest" % key)
        if key in _UUID_KEYS and not UUID.fullmatch(value):
            raise CertificateError("%s is not a canonical UUID" % key)
    locations = document["masked_annotation_locations"]
    if (not isinstance(locations, list) or not locations
            or any(not isinstance(item, str) or not item for item in locations)):
        raise CertificateError("masked_annotation_locations must be a non-empty string array")
    if list(locations) != sorted(locations):
        raise CertificateError("masked_annotation_locations must be sorted")
    for key in _COUNT_KEYS:
        value = document[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise CertificateError("%s must be a positive integer" % key)
    if document["distinct_masked_uuid_count"] != 1:
        # Two different qualified session UUIDs in one class/session fail closed.
        raise CertificateError("a certificate must bind exactly one session UUID, found %d"
                               % document["distinct_masked_uuid_count"])
    if document["masked_occurrence_count"] < document["distinct_masked_uuid_count"]:
        raise CertificateError("masked occurrence count below distinct count")
    return document


def issue(*, process_id: str, transformation_session_id: str, defining_loader_identity: str,
          class_name: str, pre_writer_raw_sha256: str, exact_semantic_sha256: str,
          exact_declaration_order_sha256: str, session_invariant_sha256: str,
          expected_session_uuid: str, masked_annotation_locations: Iterable[str],
          distinct_masked_uuid_count: int, masked_occurrence_count: int, recipe_sha256: str,
          runtime_manifest_sha256: str, policy_sha256: str,
          acquisition_evidence_sha256: str = None) -> Dict[str, Any]:
    """Builds a certificate from a recorded same-process acquisition observation.

    `policy_sha256` is required: a certificate is evidence that a named static
    policy admitted these bytes, so it must say which policy. It is not
    permission -- the policy is what authorizes, and it cannot name a session.
    """
    document = {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "provenance": PROVENANCE,
        "process_id": process_id,
        "transformation_session_id": transformation_session_id,
        "defining_loader_identity": defining_loader_identity,
        "class_name": class_name,
        "pre_writer_raw_sha256": pre_writer_raw_sha256,
        "exact_semantic_sha256": exact_semantic_sha256,
        "exact_declaration_order_sha256": exact_declaration_order_sha256,
        "session_invariant_sha256": session_invariant_sha256,
        "expected_session_uuid": expected_session_uuid,
        "masked_annotation_locations": sorted(masked_annotation_locations),
        "distinct_masked_uuid_count": distinct_masked_uuid_count,
        "masked_occurrence_count": masked_occurrence_count,
        "policy_sha256": policy_sha256,
    }
    if recipe_sha256 is not None:
        document["recipe_sha256"] = recipe_sha256
    if runtime_manifest_sha256 is not None:
        document["runtime_manifest_sha256"] = runtime_manifest_sha256
    if acquisition_evidence_sha256 is not None:
        document["acquisition_evidence_sha256"] = acquisition_evidence_sha256
    return validate(document)


def from_identity(*, process_id: str, transformation_session_id: str,
                  defining_loader_identity: str, identity: Any,
                  recipe_sha256: str, runtime_manifest_sha256: str,
                  policy_sha256: str, acquisition_evidence_sha256: str = None) -> Dict[str, Any]:
    """Builds a certificate from a session-bound identity receipt.

    `identity` is the parsed `sessionBoundReceiptJson()` document from
    `com.rustcraft.coremod.CanonicalClassIdentityV2.identifySessionBound`:
    [schema, class_name, semantic, declaration_order, raw, session_invariant,
     distinct_masked, masked_values, masked_occurrences, masked_locations].
    """
    if (not isinstance(identity, list) or len(identity) != 10
            or identity[0] != SESSION_BOUND_SCHEMA):
        raise CertificateError("a %s receipt is required to issue a certificate" % SESSION_BOUND_SCHEMA)
    (_, class_name, semantic, declaration_order, raw, invariant,
     distinct, values, occurrences, locations) = identity
    if distinct != 1:
        # Two different qualified session UUIDs in one class/session fail closed.
        raise CertificateError("cannot certify %r distinct qualified session UUIDs: %r"
                               % (distinct, values))
    return issue(
        process_id=process_id,
        transformation_session_id=transformation_session_id,
        defining_loader_identity=defining_loader_identity,
        class_name=class_name,
        pre_writer_raw_sha256=raw,
        exact_semantic_sha256=semantic,
        exact_declaration_order_sha256=declaration_order,
        session_invariant_sha256=invariant,
        expected_session_uuid=values[0],
        masked_annotation_locations=locations,
        distinct_masked_uuid_count=distinct,
        masked_occurrence_count=occurrences,
        recipe_sha256=recipe_sha256,
        runtime_manifest_sha256=runtime_manifest_sha256,
        policy_sha256=policy_sha256,
        acquisition_evidence_sha256=acquisition_evidence_sha256,
    )


def recipe_binding_sha256(recipe: Dict[str, Any]) -> str:
    """Hash of a writer-plan recipe with the certificate block removed.

    The certificates themselves carry a `recipe_sha256`; hashing the recipe
    *without* them keeps that binding non-circular while still binding every
    other recipe field -- hook inventory, manifest hash, identity mode, the
    declared session-bound class list and the runtime binding -- to each
    certificate.
    """
    if not isinstance(recipe, dict):
        raise CertificateError("recipe must be a JSON object")
    stripped = {k: v for k, v in recipe.items()
                if k not in ("session_certificates", "recipe_binding_sha256")}
    return hashlib.sha256(
        json.dumps(stripped, sort_keys=True, separators=(",", ":"),
                   ensure_ascii=False).encode("utf-8")).hexdigest()


def describe(document: Dict[str, Any]) -> List[str]:
    """Human-readable provenance lines recorded alongside the certificate."""
    return [
        "schema=%s/v%d" % (document["schema"], document["schema_version"]),
        "provenance=%s" % document["provenance"],
        "certificate_sha256=%s" % certificate_sha256(document),
        "class=%s" % document["class_name"],
        "process=%s" % document["process_id"],
        "transformation_session=%s" % document["transformation_session_id"],
        "defining_loader=%s" % document["defining_loader_identity"],
        "pre_writer_raw_sha256=%s" % document["pre_writer_raw_sha256"],
        "exact_semantic_sha256=%s" % document["exact_semantic_sha256"],
        "exact_declaration_order_sha256=%s" % document["exact_declaration_order_sha256"],
        "session_invariant_sha256=%s" % document["session_invariant_sha256"],
        "expected_session_uuid=%s" % document["expected_session_uuid"],
        "distinct_masked_uuid_count=%d" % document["distinct_masked_uuid_count"],
        "masked_occurrence_count=%d" % document["masked_occurrence_count"],
        "recipe_sha256=%s" % document["recipe_sha256"],
        "runtime_manifest_sha256=%s" % document["runtime_manifest_sha256"],
        "acquisition_evidence_sha256=%s" % document["acquisition_evidence_sha256"],
    ] + ["masked_location=%s" % location
         for location in document["masked_annotation_locations"]]
