"""Session-bound admission POLICY: what may be accepted, before any launch.

The circularity this replaces
-----------------------------
`LiveWriterPlan` is a static artifact, but it used to carry a concrete
`SessionBoundIdentityCertificate`. That certificate binds a process id, a
transformation session id, a defining-loader object identity, a session UUID
and a pre-writer byte hash -- none of which exist until a JVM is running, and
none of which survive into a different process. "Launch, capture facts, issue a
certificate, relaunch with the certificate" cannot work: the second launch has
a new process and a new `MixinMerged.sessionId`, so the imported certificate
describes a JVM that is not the one asking.

The split
---------
STATIC POLICY SAYS WHAT MAY BE ACCEPTED.
THE RUNNING JVM RECORDS WHAT ACTUALLY WAS ACCEPTED.

A policy states only facts knowable before launch: the session-INVARIANT
identity (the masked rendering, which is by construction stable across
sessions), the exact structural provenance contract, and the runtime, manifest,
recipe and loader constraints. It never names one launch's session.

A certificate is then issued IN-PROCESS by the transforming JVM, from the
pre-writer buffer that JVM is actually holding, after that buffer has been
admitted against the policy. The certificate is evidence of an admission that
happened, not permission imported from somewhere else.

Prohibitions, enforced below rather than merely documented
---------------------------------------------------------
A policy that carries a concrete process id, session UUID, pre-writer hash from
another process, loader object identity, or acquisition evidence hash is
rejected at the schema. Those are exactly the fields that made the old design
circular, and a policy is the wrong place for any of them.
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Dict, Iterable, List

SCHEMA = "RUSTCRAFT_SESSION_BOUND_ADMISSION_POLICY"
SCHEMA_VERSION = 1
PROVENANCE = "session-bound-admission-policy/v1"

SESSION_BOUND_SCHEMA = "CANONICAL_ID_V2_SESSION_BOUND"

#: Canonical key order. The policy hash is the hash of this exact rendering,
#: so the plan's embedded copy and the offline copy are the same bytes.
KEYS = (
    "schema",
    "schema_version",
    "provenance",
    "identity_mode",
    "class_name",
    "expected_session_invariant_sha256",
    "expected_declaration_order_sha256",
    "expected_annotation_descriptor",
    "expected_annotation_element",
    "expected_masked_locations",
    "expected_masked_occurrence_count",
    "expected_distinct_masked_uuid_count",
    "expected_session_uuid_shape",
    "expected_loader_class",
    "expected_loader_scope",
    "runtime_profile",
    "runtime_manifest_sha256",
    "writer_plan_sha256",
    "recipe_sha256",
    "required_hook_ids",
)

#: Fields whose presence would reintroduce the circularity. A policy is static;
#: anything only knowable inside a running JVM does not belong in it. This is
#: the machine-checkable form of the design rule, and it is enforced by
#: `validate` rather than trusted to reviewers.
FORBIDDEN_KEYS = frozenset({
    "process_id",
    "transformation_session_id",
    "session_uuid",
    "expected_session_uuid",
    "defining_loader_identity",
    "loader_identity",
    "pre_writer_raw_sha256",
    "raw_sha256",
    "exact_semantic_sha256",
    "acquisition_evidence_sha256",
    "acquisition_evidence_id",
    "certificate",
    "certificate_sha256",
    "session_certificates",
})

#: Substrings that mark a value as a concrete per-launch fact wherever they
#: appear. A UUID shape in a policy slot is always a leak, even under a name
#: the allowlist does not cover, because the only UUID a policy legitimately
#: names is the SHAPE of one.
_UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                   r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

SHA256 = re.compile(r"[0-9a-f]{64}")
#: The only session value a policy may express: what a UUID must look like.
UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
ANNOTATION_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;"
ANNOTATION_ELEMENT = "sessionId"
#: The qualified annotation contract this policy names. The schema may be exact
#: about the provenance contract; the qualification ENGINE stays profile-driven
#: and never branches on a profile or class name.
_LOADER_SCOPE_ALL = "ANY"
_LOADER_SCOPES = (_LOADER_SCOPE_ALL, "LAUNCHWRAPPER", "APPLICATION", "PLATFORM")


class PolicyError(ValueError):
    """The document is not a usable session-bound admission policy."""


def _quote(value: str) -> str:
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


# Bindings to a plan revision rather than statements about the class. A plan
# that publishes no such revision cannot be named, so a policy may omit these;
# the runtime refuses a policy that invents one the plan never published. They
# must be present whenever the plan carries the value, which the generator
# checks, so omission is never a way to escape the binding.
CONDITIONAL_KEYS = ("runtime_manifest_sha256", "writer_plan_sha256", "recipe_sha256")


def render(document: Dict[str, Any]) -> str:
    """Canonical JSON rendering. Byte-identical to the Java implementation."""
    parts = []
    for key in KEYS:
        if key in CONDITIONAL_KEYS and key not in document:
            continue
        value = document[key]
        if key in ("expected_masked_locations", "required_hook_ids"):
            rendered = "[" + ",".join(_quote(item) for item in value) + "]"
        elif key in ("schema_version", "expected_masked_occurrence_count",
                     "expected_distinct_masked_uuid_count"):
            rendered = str(int(value))
        else:
            rendered = _quote(value)
        parts.append(_quote(key) + ":" + rendered)
    return "{" + ",".join(parts) + "}"


def policy_sha256(document: Dict[str, Any]) -> str:
    return hashlib.sha256(render(document).encode("utf-8")).hexdigest()


def _reject_concrete_facts(document: Dict[str, Any], where: str) -> None:
    """Refuse a policy that names one launch's runtime facts."""
    for key, value in document.items():
        if key in FORBIDDEN_KEYS:
            raise PolicyError(
                "%s must not carry %r: a static policy states what MAY be accepted, "
                "and that is a fact about one running process" % (where, key))
        if isinstance(value, str) and _UUID.fullmatch(value.strip()):
            raise PolicyError(
                "%s.%s is a concrete session UUID; a policy may state only the "
                "session-invariant identity and the shape a UUID must have"
                % (where, key))
        if isinstance(value, list):
            for item in value:
                if isinstance(item, str) and _UUID.fullmatch(item.strip()):
                    raise PolicyError(
                        "%s.%s contains a concrete session UUID" % (where, key))
        if isinstance(value, dict):
            _reject_concrete_facts(value, "%s.%s" % (where, key))


def validate(document: Any) -> Dict[str, Any]:
    """Strict validation. Returns the document.

    Rejects unknown fields, missing fields, wrong types, malformed digests, an
    unsorted location list, a distinct-UUID count other than 1, and -- the point
    of the whole module -- any concrete per-launch fact.
    """
    if not isinstance(document, dict):
        raise PolicyError("policy must be a JSON object")
    _reject_concrete_facts(document, "policy")
    unknown = sorted(set(document) - set(KEYS))
    if unknown:
        raise PolicyError("unknown policy field(s): %s" % ", ".join(unknown))
    for key in KEYS:
        if key not in document:
            raise PolicyError("policy is missing %s" % key)
    if document["schema"] != SCHEMA:
        raise PolicyError("unexpected policy schema")
    if document["schema_version"] != SCHEMA_VERSION or isinstance(document["schema_version"], bool):
        raise PolicyError("unsupported policy version")
    if document["provenance"] != PROVENANCE:
        raise PolicyError("unexpected policy provenance")
    if document["identity_mode"] != SESSION_BOUND_SCHEMA:
        # A policy authorizes session-bound admission and nothing else. An exact
        # mode has no admission to authorize, and RAW would be a downgrade.
        raise PolicyError("policy identity_mode must be %s" % SESSION_BOUND_SCHEMA)
    for key in ("class_name", "expected_annotation_descriptor", "expected_annotation_element",
                "expected_session_uuid_shape", "expected_loader_class", "runtime_profile",
                "expected_loader_scope"):
        value = document[key]
        if not isinstance(value, str) or not value:
            raise PolicyError("%s must be a non-empty string" % key)
    for key in ("expected_session_invariant_sha256", "expected_declaration_order_sha256",
                "runtime_manifest_sha256", "writer_plan_sha256", "recipe_sha256"):
        value = document[key]
        if not isinstance(value, str) or not SHA256.fullmatch(value):
            raise PolicyError("%s is not a SHA-256 digest" % key)
    if document["expected_loader_scope"] not in _LOADER_SCOPES:
        raise PolicyError("unknown loader scope %r" % document["expected_loader_scope"])
    if document["expected_annotation_descriptor"] != ANNOTATION_DESCRIPTOR:
        raise PolicyError("unexpected qualified annotation descriptor")
    if document["expected_annotation_element"] != ANNOTATION_ELEMENT:
        raise PolicyError("unexpected qualified annotation element")
    if document["expected_session_uuid_shape"] != UUID_SHAPE:
        raise PolicyError("session UUID shape must be the canonical lowercase UUID pattern")
    if document["expected_distinct_masked_uuid_count"] != 1:
        # Two different qualified session UUIDs in one class/session is not a
        # policy question; it is refused at admission, every time.
        raise PolicyError("a policy expects exactly one distinct session UUID, found %r"
                          % (document["expected_distinct_masked_uuid_count"],))
    for key in ("expected_masked_occurrence_count", "expected_distinct_masked_uuid_count"):
        value = document[key]
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise PolicyError("%s must be a positive integer" % key)
    if document["expected_masked_occurrence_count"] < document["expected_distinct_masked_uuid_count"]:
        raise PolicyError("expected occurrence count below distinct count")
    locations = document["expected_masked_locations"]
    if (not isinstance(locations, list) or not locations
            or any(not isinstance(item, str) or not item for item in locations)):
        raise PolicyError("expected_masked_locations must be a non-empty string array")
    if list(locations) != sorted(locations):
        raise PolicyError("expected_masked_locations must be sorted")
    for location in locations:
        if ANNOTATION_DESCRIPTOR not in location or ANNOTATION_ELEMENT not in location:
            raise PolicyError("masked location %r does not name the qualified annotation" % location)
    hooks = document["required_hook_ids"]
    if (not isinstance(hooks, list) or not hooks
            or any(not isinstance(item, str) or not item for item in hooks)):
        raise PolicyError("required_hook_ids must be a non-empty string array")
    if list(hooks) != sorted(hooks) or len(set(hooks)) != len(hooks):
        raise PolicyError("required_hook_ids must be sorted and unique")
    if document["expected_session_invariant_sha256"] == document["expected_declaration_order_sha256"]:
        # A session projection that removes nothing is not a session projection;
        # admitting it would let a process-bound value pass as session-invariant.
        raise PolicyError("session invariant equals the declaration-order identity, so the "
                          "policy projects away nothing")
    return document


def build(*, class_name: str, expected_session_invariant_sha256: str,
          expected_declaration_order_sha256: str, expected_masked_locations: Iterable[str],
          expected_masked_occurrence_count: int, runtime_profile: str,
          runtime_manifest_sha256: str, writer_plan_sha256: str, recipe_sha256: str,
          required_hook_ids: Iterable[str], expected_loader_class: str,
          expected_loader_scope: str = _LOADER_SCOPE_ALL) -> Dict[str, Any]:
    """Builds a policy from cross-launch evidence.

    Everything here is derived from facts that hold across launches: the masked
    rendering, the structural provenance locations and counts, and the pinned
    runtime/manifest/recipe identities. No single launch contributes a session
    UUID or a process id, which is what makes the result usable by a JVM that
    has not started yet.
    """
    return validate({
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "provenance": PROVENANCE,
        "identity_mode": SESSION_BOUND_SCHEMA,
        "class_name": class_name,
        "expected_session_invariant_sha256": expected_session_invariant_sha256,
        "expected_declaration_order_sha256": expected_declaration_order_sha256,
        "expected_annotation_descriptor": ANNOTATION_DESCRIPTOR,
        "expected_annotation_element": ANNOTATION_ELEMENT,
        "expected_masked_locations": sorted(expected_masked_locations),
        "expected_masked_occurrence_count": int(expected_masked_occurrence_count),
        "expected_distinct_masked_uuid_count": 1,
        "expected_session_uuid_shape": UUID_SHAPE,
        "expected_loader_class": expected_loader_class,
        "expected_loader_scope": expected_loader_scope,
        "runtime_profile": runtime_profile,
        "runtime_manifest_sha256": runtime_manifest_sha256,
        "writer_plan_sha256": writer_plan_sha256,
        "recipe_sha256": recipe_sha256,
        "required_hook_ids": sorted(required_hook_ids),
    })


def admits(document: Dict[str, Any], *, session_invariant_sha256: str,
           declaration_order_sha256: str, masked_locations: Iterable[str],
           masked_occurrence_count: int, distinct_masked_uuid_count: int,
           loader_class: str, runtime_profile: str, recipe_sha256: str,
           manifest_sha256: str) -> List[str]:
    """Checks one OBSERVED pre-writer buffer against the policy.

    Returns the list of reasons it was refused; empty means admitted. This is
    the whole admission rule, and it is deliberately a function of the
    observation and the policy only -- no process identity, no session
    identity, nothing that could have been carried in from another launch.
    """
    refusals: List[str] = []
    if distinct_masked_uuid_count != 1:
        refusals.append("DISTINCT_SESSION_UUIDS: observed %d distinct qualified session UUIDs, "
                        "policy expects 1" % distinct_masked_uuid_count)
    if session_invariant_sha256 != document["expected_session_invariant_sha256"]:
        refusals.append("INVARIANT_MISMATCH: observed %s, policy expects %s"
                        % (session_invariant_sha256, document["expected_session_invariant_sha256"]))
    if declaration_order_sha256 != document["expected_declaration_order_sha256"]:
        refusals.append("DECLARATION_ORDER_MISMATCH: observed %s, policy expects %s"
                        % (declaration_order_sha256, document["expected_declaration_order_sha256"]))
    observed_locations = sorted(masked_locations)
    if observed_locations != sorted(document["expected_masked_locations"]):
        refusals.append("MASKED_LOCATION_MISMATCH: observed %s, policy expects %s"
                        % (observed_locations, sorted(document["expected_masked_locations"])))
    if masked_occurrence_count != document["expected_masked_occurrence_count"]:
        refusals.append("MASKED_OCCURRENCE_MISMATCH: observed %d, policy expects %d"
                        % (masked_occurrence_count, document["expected_masked_occurrence_count"]))
    if loader_class != document["expected_loader_class"]:
        refusals.append("LOADER_CLASS_MISMATCH: observed %s, policy expects %s"
                        % (loader_class, document["expected_loader_class"]))
    if runtime_profile != document["runtime_profile"]:
        refusals.append("RUNTIME_PROFILE_MISMATCH: observed %s, policy expects %s"
                        % (runtime_profile, document["runtime_profile"]))
    if recipe_sha256 != document["recipe_sha256"]:
        refusals.append("RECIPE_MISMATCH: observed %s, policy expects %s"
                        % (recipe_sha256, document["recipe_sha256"]))
    if manifest_sha256 != document["runtime_manifest_sha256"]:
        refusals.append("MANIFEST_MISMATCH: observed %s, policy expects %s"
                        % (manifest_sha256, document["runtime_manifest_sha256"]))
    return refusals


def recipe_binding_sha256(recipe: Dict[str, Any]) -> str:
    """Hash of a writer-plan recipe with the policy block removed.

    The policies carry a `recipe_sha256`; hashing the recipe *without* them keeps
    that binding non-circular while still binding every other recipe field -- hook
    inventory, manifest hash, identity mode, the declared session-bound class list
    and the runtime binding -- to each policy.

    `session_certificates` is stripped as well, and the generator refuses a recipe
    that carries one at all: a concrete certificate in a static plan can only be
    satisfied by importing it from another JVM.
    """
    if not isinstance(recipe, dict):
        raise PolicyError("recipe must be a JSON object")
    stripped = {k: v for k, v in recipe.items()
                if k not in ("session_admission_policies", "session_certificates",
                             "recipe_binding_sha256")}
    return hashlib.sha256(
        json.dumps(stripped, sort_keys=True, separators=(",", ":"),
                   ensure_ascii=False).encode("utf-8")).hexdigest()


def describe(document: Dict[str, Any]) -> List[str]:
    """Human-readable provenance lines recorded alongside the policy."""
    return [
        "schema=%s/v%d" % (document["schema"], document["schema_version"]),
        "provenance=%s" % document["provenance"],
        "policy_sha256=%s" % policy_sha256(document),
        "class=%s" % document["class_name"],
        "identity_mode=%s" % document["identity_mode"],
        "expected_session_invariant_sha256=%s" % document["expected_session_invariant_sha256"],
        "expected_declaration_order_sha256=%s" % document["expected_declaration_order_sha256"],
        "expected_annotation=%s#%s" % (document["expected_annotation_descriptor"],
                                       document["expected_annotation_element"]),
        "expected_masked_occurrence_count=%d" % document["expected_masked_occurrence_count"],
        "expected_distinct_masked_uuid_count=%d" % document["expected_distinct_masked_uuid_count"],
        "expected_session_uuid_shape=%s" % document["expected_session_uuid_shape"],
        "expected_loader=%s scope=%s" % (document["expected_loader_class"],
                                         document["expected_loader_scope"]),
        "runtime_profile=%s" % document["runtime_profile"],
        "runtime_manifest_sha256=%s" % document["runtime_manifest_sha256"],
        "writer_plan_sha256=%s" % document["writer_plan_sha256"],
        "recipe_sha256=%s" % document["recipe_sha256"],
    ] + ["expected_masked_location=%s" % location
         for location in document["expected_masked_locations"]] \
      + ["required_hook_id=%s" % hook for hook in document["required_hook_ids"]]
