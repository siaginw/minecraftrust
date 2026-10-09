"""Frame-relation evidence contract: strict validation of a whole-class frame proof.

Blocker #1 was that "the JVM verified it" is not proof. Discarding stack-map
frames, or checking a study-jar's SRG hierarchy, does not establish that the
frames a real loader consumed still describe the classes that loader defined.
This module defines what a frame-relation witness must contain before the
qualification engine may rely on it, and -- just as importantly -- what it must
*refuse* to accept:

- only ``REAL_FORGE_LAUNCH_V1`` evidence qualifies. There is no static fallback,
  because the vanilla 1.12.2 server jar is obfuscated and a standalone JVM
  cannot resolve the named hierarchy at all. If a witness cannot prove it came
  from a real launch, it is not evidence, and the engine must stay INCOMPLETE.
- the pre-writer buffer is never defined in its own process. A witness that
  claims JVM verification of the pre-writer bytes is fabricating the one thing
  nobody can observe, and is rejected outright rather than downgraded.
- "verified" is bound to the bytes an independent observer saw the loader
  define. If a downstream transformer ran, that has to be disclosed; a silent
  mismatch is rejected.
- every acquisition row must name the same defining loader the witness
  declares, and a session invariant may only be claimed where a class is
  actually certifiable.

The module never grants authority and never widens what may be normalized. It
only says whether frame evidence exists.
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Sequence

SCHEMA = "QUALIFIED_FRAME_WITNESS_V1"

#: The only scope model that may support a qualification. A study jar, an SRG
#: remap table or a standalone JVM are explicitly not oracles here.
SCOPE_MODEL = "REAL_FORGE_LAUNCH_V1"
CLOSED = "CLOSED"

PRE_OBSERVED = "OBSERVED_NOT_DEFINED_IN_THIS_PROCESS"
POST_VERIFIED = "VERIFIED"

SHA256 = re.compile(r"[0-9a-f]{64}")

_TOP_KEYS = ("schema", "scope", "loader_identity", "loader_class", "phase_summaries",
             "acquisition", "chain_of_custody", "downstream_transformers_after_live_writers",
             "production_authority", "phases")
_SCOPE_KEYS = ("model", "status", "observer_assurance", "loader_assurance",
               "no_static_oracle", "production_authority")
_PHASE_KEYS = ("phase", "loader", "verification", "resolutions", "assignability",
               "hierarchy", "required_types", "assignability_queries")
_PRE_KEYS = ("name", "phase", "status", "raw_sha256", "defined_by_transforming_loader",
             "semantic_sha256", "declaration_order_sha256", "jvm_verified", "reason")
_POST_KEYS = ("name", "phase", "status", "raw_sha256", "observed_raw_sha256",
              "rustcraft_post_writer_sha256", "defined_bytes_equal_rustcraft_output",
              "same_buffer_identity", "defining_loader", "initialized",
              "initialized_before_observation", "initialized_after_observation",
              "verification_note", "trigger", "verify_local", "verify_remote",
              "semantic_sha256", "declaration_order_sha256")
_RESOLUTION_KEYS = ("type", "class_id", "defining_loader", "error")
_ASSIGNABILITY_KEYS = ("source", "target", "value", "witness")
_ACQUISITION_KEYS = ("ordinal", "binary_name", "pre_writer_raw_sha256",
                     "post_writer_raw_sha256", "exact_semantic_sha256",
                     "hook_placement", "definition_succeeded",
                     "defined_class_identity", "defining_loader_identity",
                     "session_invariant_sha256", "certifiable")
_CUSTODY_KEYS = ("ordinal", "binary_name", "definition_bound")
#: A bound stage says which buffer reached the loader, which loader and class it
#: became, and whether a later stage consumed the same buffer. A stage that
#: deliberately did not bind says why instead, and carries no buffer identity.
_CUSTODY_OPTIONAL = ("refusal", "post_writer_raw_sha256", "defined_class_identity",
                     "is_final_stage", "linked_to_next_stage",
                     "defining_loader_identity", "certifiable")
_SUMMARY_KEYS = ("phase", "whole_classes_verified", "required_types",
                 "assignability_queries", "loader")

#: Hook placement outcomes seen on a definition attempt. NOT_PLANNED means the
#: stage has no hooks for this class; PLACED and REFUSED are the two live-writer
#: outcomes, and REFUSED is the fail-closed one.
_PLACEMENT = ("PLACED", "REFUSED", "NOT_PLANNED")


class FrameEvidenceError(ValueError):
    """The document is not usable frame-relation evidence."""


def _object(value: Any, what: str, required: Sequence[str], optional: Sequence[str] = ()) -> Dict[str, Any]:
    if not isinstance(value, dict):
        raise FrameEvidenceError(what + " must be a JSON object")
    unknown = sorted(set(value) - set(required) - set(optional))
    if unknown:
        raise FrameEvidenceError("unknown %s field(s): %s" % (what, ", ".join(unknown)))
    missing = [key for key in required if key not in value]
    if missing:
        raise FrameEvidenceError("%s is missing %s" % (what, ", ".join(missing)))
    return value


def _digest(value: Any, what: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        raise FrameEvidenceError(what + " must be a SHA-256 digest")
    return value


def _text(value: Any, what: str) -> str:
    if not isinstance(value, str) or not value:
        raise FrameEvidenceError(what + " must be a nonempty string")
    return value


def _count(value: Any, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise FrameEvidenceError(what + " must be a non-negative integer")
    return value


def _list(value: Any, what: str) -> List[Any]:
    if not isinstance(value, list):
        raise FrameEvidenceError(what + " must be a list")
    return value


def _internal(name: Any, what: str) -> str:
    """Witness class names are internal (slashed); profiles may write either."""
    name = _text(name, what)
    return name.replace(".", "/")


def validate(document: Any, required_classes: Sequence[str] = ()) -> Dict[str, Any]:
    """Strictly validate a frame-relation witness. Returns the document.

    `required_classes` is the exact set of internal class names the profile
    requires to be frame-verified. An empty set skips only the coverage check,
    never the scope or binding checks.
    """
    w = _object(document, "witness", _TOP_KEYS)
    if w["schema"] != SCHEMA:
        raise FrameEvidenceError("unexpected frame witness schema")

    # A witness never carries authority, whatever else it claims.
    if w["production_authority"] is not False:
        raise FrameEvidenceError("frame evidence may not claim production authority")

    scope = _object(w["scope"], "scope", _SCOPE_KEYS)
    if scope["model"] != SCOPE_MODEL:
        # The refusal is explicit and loud: a study jar or a standalone JVM is
        # not the oracle, and silently accepting one would let blocker #1 back
        # in through a side door.
        raise FrameEvidenceError(
            "frame evidence scope model must be %s; %r is not an oracle for a real launch"
            % (SCOPE_MODEL, scope["model"]))
    if scope["status"] != CLOSED:
        raise FrameEvidenceError("frame evidence scope must be %s, not %r" % (CLOSED, scope["status"]))
    _text(scope["observer_assurance"], "scope.observer_assurance")
    _text(scope["loader_assurance"], "scope.loader_assurance")
    if scope["no_static_oracle"] is not True:
        raise FrameEvidenceError("frame evidence must declare no_static_oracle")
    if scope["production_authority"] is not False:
        raise FrameEvidenceError("frame scope may not claim production authority")

    loader = _text(w["loader_identity"], "loader_identity")
    loader_class = _text(w["loader_class"], "loader_class")
    if not loader.startswith(loader_class + "@"):
        # The identity is a rendering of the loader's own class name; if the
        # two disagree, one of them was written by something other than the
        # process that defined the classes.
        raise FrameEvidenceError("loader identity does not render as its own declaring class: %r / %r"
                                 % (loader, loader_class))
    downstream = _list(w["downstream_transformers_after_live_writers"],
                       "downstream_transformers_after_live_writers")
    for item in downstream:
        _text(item, "downstream transformer name")

    phases = _list(w["phases"], "phases")
    by_name = {}
    for phase in phases:
        p = _object(phase, "phase", _PHASE_KEYS)
        name = p["phase"]
        if name not in ("pre", "post") or name in by_name:
            raise FrameEvidenceError("phases must be exactly one 'pre' and one 'post'")
        if p["loader"] != loader:
            raise FrameEvidenceError("phase %s ran under a different loader than the witness declares" % name)
        by_name[name] = p
    if set(by_name) != {"pre", "post"}:
        raise FrameEvidenceError("a frame witness needs both a pre-writer and a post-writer phase")

    pre_raw = _validate_pre(by_name["pre"], loader)
    post_names = _validate_post(by_name["post"], loader, downstream, pre_raw, required_classes)

    if required_classes:
        expected = {_internal(name, "required class") for name in required_classes}
        if post_names != expected:
            raise FrameEvidenceError("frame-verified class set differs from the profile requirement: %s"
                                     % sorted(expected.symmetric_difference(post_names)))

    _validate_acquisition(w["acquisition"], loader, pre_raw, post_names)
    _validate_custody(w["chain_of_custody"], loader, w["acquisition"])
    _validate_summaries(w["phase_summaries"], by_name, loader)
    return w


def _validate_pre(phase: Dict[str, Any], loader: str) -> Dict[str, str]:
    rows = _list(phase["verification"], "pre.verification")
    if not rows:
        raise FrameEvidenceError("pre phase verified no classes")
    raw: Dict[str, str] = {}
    for row in rows:
        r = _object(row, "pre verification", _PRE_KEYS)
        name = _internal(r["name"], "pre verification name")
        if name in raw:
            raise FrameEvidenceError("duplicate pre verification: " + name)
        if r["phase"] != "pre":
            raise FrameEvidenceError("pre verification entry is not marked pre: " + name)
        if r["status"] != PRE_OBSERVED:
            # Deliberately not downgraded to a warning. The pre-writer buffer is
            # consumed by the loader and never defined, so any claim to the
            # contrary is a fabricated verification.
            raise FrameEvidenceError(
                "pre-writer bytes cannot be JVM-verified in their own process: %s reported %r"
                % (name, r["status"]))
        if r["jvm_verified"] is not False:
            raise FrameEvidenceError("pre-writer entry claims jvm_verified: " + name)
        _text(r["reason"], "pre verification reason")
        if r["defined_by_transforming_loader"] != loader:
            raise FrameEvidenceError("pre verification names a different transforming loader: " + name)
        raw[name] = _digest(r["raw_sha256"], "pre raw_sha256")
        _digest(r["semantic_sha256"], "pre semantic_sha256")
        _digest(r["declaration_order_sha256"], "pre declaration_order_sha256")
    if _count(phase["required_types"], "pre.required_types") != len(_list(phase["resolutions"], "pre.resolutions")):
        raise FrameEvidenceError("pre required_types does not match the resolution count")
    if _count(phase["assignability_queries"], "pre.assignability_queries") != len(_list(phase["assignability"], "pre.assignability")):
        raise FrameEvidenceError("pre assignability_queries does not match the answer count")
    return raw


def _validate_post(phase: Dict[str, Any], loader: str, downstream: List[str],
                   pre_raw: Dict[str, str], required_classes: Sequence[str]) -> set:
    rows = _list(phase["verification"], "post.verification")
    if not rows:
        raise FrameEvidenceError("post phase verified no classes")
    names = set()
    for row in rows:
        r = _object(row, "post verification", _POST_KEYS)
        name = _internal(r["name"], "post verification name")
        if name in names:
            raise FrameEvidenceError("duplicate post verification: " + name)
        if r["phase"] != "post":
            raise FrameEvidenceError("post verification entry is not marked post: " + name)
        if r["status"] != POST_VERIFIED:
            raise FrameEvidenceError("post-writer class was not frame-verified: %s (%r)" % (name, r["status"]))
        if r["defining_loader"] != loader:
            raise FrameEvidenceError("post verification names a different defining loader: " + name)
        observed = _digest(r["observed_raw_sha256"], "post observed_raw_sha256")
        produced = _digest(r["rustcraft_post_writer_sha256"], "post rustcraft_post_writer_sha256")
        _digest(r["raw_sha256"], "post raw_sha256")
        if observed != r["raw_sha256"]:
            # This is the same-buffer identity binding: the buffer proven is the
            # buffer an independent observer saw handed to the loader.
            raise FrameEvidenceError("post verification is not bound to the independently observed definition: " + name)
        equal = r["defined_bytes_equal_rustcraft_output"]
        if not isinstance(equal, bool):
            raise FrameEvidenceError("defined_bytes_equal_rustcraft_output must be a boolean: " + name)
        note = _text(r["same_buffer_identity"], "post same_buffer_identity")
        if not equal:
            if not downstream:
                raise FrameEvidenceError("a downstream transformer changed the defined bytes but none is disclosed: " + name)
            if "DOWNSTREAM_TRANSFORMER" not in note:
                raise FrameEvidenceError("post verification must disclose the downstream transformer that ran: " + name)
        elif "DOWNSTREAM_TRANSFORMER" in note:
            raise FrameEvidenceError("post verification claims a downstream transformer ran but the bytes are equal: " + name)
        if pre_raw.get(name) is not None and not produced:
            raise FrameEvidenceError("post verification lacks a produced buffer: " + name)
        # HotSpot split-verification flags. The loader could not have defined
        # these classes with both verifiers off, so a witness claiming that is
        # describing a JVM that never checked anything.
        local, remote = r["verify_local"], r["verify_remote"]
        if not isinstance(local, bool) or not isinstance(remote, bool) or not (local or remote):
            raise FrameEvidenceError("no JVM bytecode verification was in effect for: " + name)
        if r["trigger"] != "REAL_LAUNCHCLASSLOADER_DECLARED_METHODS_V1":
            raise FrameEvidenceError("post verification did not come from a real LaunchClassLoader: " + name)
        flags = (r["initialized"], r["initialized_before_observation"], r["initialized_after_observation"])
        if not all(isinstance(flag, bool) for flag in flags):
            raise FrameEvidenceError("initialization state must be recorded as booleans: " + name)
        if r["initialized"] != (r["initialized_before_observation"] or r["initialized_after_observation"]):
            raise FrameEvidenceError("initialization state is internally inconsistent: " + name)
        _text(r["verification_note"], "post verification_note")
        _digest(r["semantic_sha256"], "post semantic_sha256")
        _digest(r["declaration_order_sha256"], "post declaration_order_sha256")
        names.add(name)
    _validate_resolutions(phase, "post")
    return names


def _validate_resolutions(phase: Dict[str, Any], label: str) -> None:
    """A discarded stack-map frame is an unresolved type, not a pass."""
    rows = _list(phase["resolutions"], label + ".resolutions")
    if not rows:
        raise FrameEvidenceError(label + " phase resolved no frame reference types")
    seen = set()
    for row in rows:
        r = _object(row, label + " resolution", _RESOLUTION_KEYS)
        key = _text(r["type"], label + " resolution type")
        if key in seen:
            raise FrameEvidenceError("duplicate frame reference type: " + key)
        seen.add(key)
        if r["error"] is not None:
            raise FrameEvidenceError("frame reference type %s did not resolve: %s" % (key, r["error"]))
        _text(r["class_id"], label + " resolution class_id")
        if r["defining_loader"] is not None:
            _text(r["defining_loader"], label + " resolution defining_loader")
    if _count(phase["required_types"], label + ".required_types") != len(rows):
        raise FrameEvidenceError(label + " required_types does not match the resolution count")
    answers = _list(phase["assignability"], label + ".assignability")
    if not answers:
        raise FrameEvidenceError(label + " phase produced no assignability witnesses")
    for row in answers:
        a = _object(row, label + " assignability", _ASSIGNABILITY_KEYS)
        _text(a["source"], label + " assignability source")
        _text(a["target"], label + " assignability target")
        if not isinstance(a["value"], bool):
            raise FrameEvidenceError(label + " assignability answer must be a boolean")
        _text(a["witness"], label + " assignability witness")
    if _count(phase["assignability_queries"], label + ".assignability_queries") != len(answers):
        raise FrameEvidenceError(label + " assignability_queries does not match the answer count")
    if not _list(phase["hierarchy"], label + ".hierarchy"):
        raise FrameEvidenceError(label + " phase produced an empty hierarchy graph")


def _validate_acquisition(rows: Any, loader: str, pre_raw: Dict[str, str], post_names: set) -> None:
    """One row per definition ATTEMPT, because a class passes through several
    transformer stages and later attempts legitimately fail or find nothing
    planned. The contract is per row, plus a coverage requirement: every
    frame-verified class needs at least one attempt that actually reached a
    successful definition."""
    rows = _list(rows, "acquisition")
    if not rows:
        raise FrameEvidenceError("frame evidence records no class acquisition")
    ordinals = set()
    covered = set()
    by_class: Dict[str, List[Dict[str, Any]]] = {}
    for row in rows:
        a = _object(row, "acquisition", _ACQUISITION_KEYS)
        ordinal = a["ordinal"]
        if isinstance(ordinal, bool) or not isinstance(ordinal, int) or ordinal in ordinals:
            raise FrameEvidenceError("acquisition ordinals must be distinct integers")
        ordinals.add(ordinal)
        name = _internal(a["binary_name"], "acquisition binary_name")
        if a["defining_loader_identity"] != loader:
            raise FrameEvidenceError("acquisition names a different defining loader: " + name)
        if a["hook_placement"] not in _PLACEMENT:
            raise FrameEvidenceError("unknown hook placement outcome: %r" % (a["hook_placement"],))
        if not isinstance(a["definition_succeeded"], bool):
            raise FrameEvidenceError("acquisition definition_succeeded must be a boolean: " + name)
        if not isinstance(a["certifiable"], bool):
            raise FrameEvidenceError("acquisition certifiable must be a boolean: " + name)
        _digest(a["pre_writer_raw_sha256"], "acquisition pre_writer_raw_sha256")
        _digest(a["exact_semantic_sha256"], "acquisition exact_semantic_sha256")
        if a["post_writer_raw_sha256"] is not None:
            _digest(a["post_writer_raw_sha256"], "acquisition post_writer_raw_sha256")
        if a["defined_class_identity"] is not None:
            _text(a["defined_class_identity"], "acquisition defined_class_identity")
        invariant = a["session_invariant_sha256"]
        if invariant is None:
            if a["certifiable"]:
                raise FrameEvidenceError("a class cannot be certifiable without a session invariant: " + name)
        else:
            _digest(invariant, "acquisition session_invariant_sha256")
            if not a["certifiable"]:
                raise FrameEvidenceError("a class with a session invariant must be certifiable: " + name)
        if a["definition_succeeded"]:
            if a["defined_class_identity"] is None:
                raise FrameEvidenceError("a successful definition must name the class it defined: " + name)
            covered.add(name)
        elif a["defined_class_identity"] is not None:
            raise FrameEvidenceError("a failed attempt cannot claim a defined class: " + name)
        by_class.setdefault(name, []).append(a)
    if post_names - covered:
        raise FrameEvidenceError("frame-verified classes have no successful definition: %s" % sorted(post_names - covered))
    for name, attempts in by_class.items():
        attempts.sort(key=lambda a: a["ordinal"])
        if name in pre_raw and attempts[0]["pre_writer_raw_sha256"] != pre_raw[name]:
            # The pre phase measures the buffer the FIRST stage was handed; the
            # acquisition's first attempt must be that same buffer or the two
            # phases are describing different moments.
            raise FrameEvidenceError("acquisition pre-writer bytes differ from the measured pre phase: " + name)
        seen_inputs = {attempts[0]["pre_writer_raw_sha256"]}
        seen_outputs = set()
        for attempt in attempts:
            if attempt["post_writer_raw_sha256"] is not None:
                seen_outputs.add(attempt["post_writer_raw_sha256"])
        for index, attempt in enumerate(attempts[1:], start=1):
            pre = attempt["pre_writer_raw_sha256"]
            # RESTARTED custody applies only when everything BEFORE this
            # attempt was a no-op or abandoned pass: earlier attempts that
            # really transformed the buffer make an unexplained input a
            # broken step, not a restart.
            produced_earlier = any(
                a["post_writer_raw_sha256"] is not None
                and a["post_writer_raw_sha256"] != a["pre_writer_raw_sha256"]
                for a in attempts[:index])
            if pre not in seen_inputs and pre not in seen_outputs and produced_earlier:
                # A later stage of a class is handed either the same input an
                # earlier stage saw (a repeated stage, which is how the
                # double-invocation refusal is exercised) or that stage's
                # output. A buffer that appears from neither is an unexplained
                # step in the chain -- UNLESS every earlier attempt was
                # abandoned (no writer output at all): a real launch measures
                # exactly that for classes whose first, re-entrant
                # transformation the writers decline to admit, whereupon the
                # loader hands a LATER pass a freshly transformed buffer. That
                # is RESTARTED custody, not broken custody: the PRE stage of
                # the defining attempt binds the buffer to the loader's own
                # entry observation, which is checked independently.
                if produced_anything:
                    raise FrameEvidenceError(
                        "a later transformation stage was handed neither an earlier input nor its output: " + name)
            seen_inputs.add(pre)


def _validate_custody(rows: Any, loader: str, acquisition: List[Dict[str, Any]]) -> None:
    """Chain of custody, one row per transformation stage.

    The load-bearing rule is that a class is defined exactly once: at most one
    stage per class may be marked `definition_bound`. Everything else is about
    honesty about the stages that were NOT bound -- a stage whose output was
    consumed by the next transformer and never reached the loader has to say so
    with a refusal, rather than quietly omitting itself.
    """
    rows = _list(rows, "chain_of_custody")
    if not rows:
        raise FrameEvidenceError("frame evidence records no chain of custody")
    produced: Dict[str, set] = {}
    for a in acquisition:
        if a["post_writer_raw_sha256"] is not None:
            produced.setdefault(_internal(a["binary_name"], "acquisition binary_name"), set()).add(
                a["post_writer_raw_sha256"])
    ordinals = set()
    bound: Dict[str, int] = {}
    for row in rows:
        c = _object(row, "chain of custody", _CUSTODY_KEYS, _CUSTODY_OPTIONAL)
        if isinstance(c["ordinal"], bool) or not isinstance(c["ordinal"], int) or c["ordinal"] in ordinals:
            raise FrameEvidenceError("custody ordinals must be distinct integers")
        ordinals.add(c["ordinal"])
        name = _internal(c["binary_name"], "custody binary_name")
        if not isinstance(c["definition_bound"], bool):
            raise FrameEvidenceError("custody definition_bound must be a boolean: " + name)
        if "certifiable" in c and not isinstance(c["certifiable"], bool):
            raise FrameEvidenceError("custody certifiable must be a boolean: " + name)
        if c.get("defining_loader_identity") not in (None, loader):
            raise FrameEvidenceError("custody names a different defining loader: " + name)
        if not c["definition_bound"]:
            if "refusal" not in c:
                raise FrameEvidenceError("an unbound stage must say why it was not bound: " + name)
            _text(c["refusal"], "custody refusal")
            if set(c) & {"post_writer_raw_sha256", "defined_class_identity"}:
                raise FrameEvidenceError("an unbound stage cannot describe a loader-bound buffer: " + name)
            continue
        if not isinstance(c.get("is_final_stage"), bool):
            raise FrameEvidenceError("a bound stage must report whether it was the final one: " + name)
        # linked_to_next_stage is tri-state: true when a later attempt consumed
        # this exact buffer, false when one ran on it and did not, and null when
        # no later attempt for this class exists at all.
        if c.get("linked_to_next_stage") is not None and not isinstance(c["linked_to_next_stage"], bool):
            raise FrameEvidenceError("linked_to_next_stage must be a boolean or null: " + name)
        if name in bound:
            raise FrameEvidenceError("a class may be defined exactly once, but stage %d also claims it: %s"
                                     % (c["ordinal"], name))
        if c.get("defining_loader_identity") != loader:
            raise FrameEvidenceError("a bound stage must name the defining loader: " + name)
        bound[name] = c["ordinal"]
        if not c["is_final_stage"] and c.get("linked_to_next_stage") is True:
            # is_final_stage is "the last RustCraft stage for this class", which
            # is not the same question as "was this what the loader defined": a
            # downstream transformer may pass a non-final stage's output
            # straight through. What is never true is a bound stage whose buffer
            # a later RustCraft stage consumed -- that buffer was not defined.
            raise FrameEvidenceError("a stage consumed by a later RustCraft stage cannot be the defined one: " + name)
        _text(c["defined_class_identity"], "custody defined_class_identity")
        _digest(c["post_writer_raw_sha256"], "custody post_writer_raw_sha256")
        if name in produced and c["post_writer_raw_sha256"] not in produced[name]:
            raise FrameEvidenceError("custody post-writer bytes differ from the acquisition record: " + name)
    if not bound:
        raise FrameEvidenceError("no chain stage was bound to a class definition")


def _validate_summaries(rows: Any, phases: Dict[str, Dict[str, Any]], loader: str) -> None:
    rows = _list(rows, "phase_summaries")
    if len(rows) != len(phases):
        raise FrameEvidenceError("phase_summaries does not cover both phases")
    for row in rows:
        s = _object(row, "phase summary", _SUMMARY_KEYS)
        name = s["phase"]
        if name not in phases:
            raise FrameEvidenceError("phase summary names an unknown phase: %r" % (name,))
        phase = phases[name]
        if s["loader"] != loader:
            raise FrameEvidenceError("phase summary names a different loader: " + name)
        if _count(s["required_types"], "summary required_types") != len(_list(phase["resolutions"], "resolutions")):
            raise FrameEvidenceError("phase summary required_types differs from the phase: " + name)
        if _count(s["assignability_queries"], "summary assignability_queries") != len(_list(phase["assignability"], "assignability")):
            raise FrameEvidenceError("phase summary assignability_queries differs from the phase: " + name)
        verified = _count(s["whole_classes_verified"], "summary whole_classes_verified")
        # The pre phase verifies nothing, and claiming otherwise is the whole
        # failure mode this module exists to prevent.
        expected = 0 if name == "pre" else len(_list(phase["verification"], "verification"))
        if verified != expected:
            raise FrameEvidenceError("phase summary overstates %s verification: %d != %d" % (name, verified, expected))


def describe(witness: Dict[str, Any]) -> List[str]:
    """Provenance lines recorded alongside an accepted witness."""
    summaries = {row["phase"]: row for row in witness["phase_summaries"]}
    return [
        "schema=%s" % witness["schema"],
        "scope_model=%s" % witness["scope"]["model"],
        "scope_status=%s" % witness["scope"]["status"],
        "no_static_oracle=%s" % str(witness["scope"]["no_static_oracle"]).lower(),
        "defining_loader=%s" % witness["loader_identity"],
        "pre_whole_classes_verified=%d" % summaries["pre"]["whole_classes_verified"],
        "post_whole_classes_verified=%d" % summaries["post"]["whole_classes_verified"],
        "frame_types_resolved=%d" % summaries["post"]["required_types"],
        "assignability_witnesses=%d" % summaries["post"]["assignability_queries"],
        "acquisition_records=%d" % len(witness["acquisition"]),
        "downstream_transformers=%s" % (", ".join(witness["downstream_transformers_after_live_writers"]) or "none"),
        "production_authority=false",
    ]
