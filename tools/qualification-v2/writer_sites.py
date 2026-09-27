"""Writer-site contracts derived from PRE-writer bytecode only.

The qualification engine recomputes each site's hook-call count from the
transformed class itself. The profile therefore has to state the expected count
*without* looking at the transformed class, or the control would only ever
confirm what the writer already did.

Every count below is derived from the pre-writer method facts: how many
instructions precede the injected region, how many returns the original method
had, and which facade method the plan's hook type injects. The one rule that
matters is the scope bracket in LiveHookSupport.injectScopeBracket -- it emits
one `begin` at method entry, one `end` before every return, and one more `end`
in a catch-all handler, so `end` count is exactly (original returns + 1).

If the writer ever inserted, omitted, or duplicated a call, the derived count
and the engine's recount disagree and the run FAILs. That disagreement is the
control; nothing in this module is allowed to see the post-writer bytes.
"""
from __future__ import annotations

HOOKS = "com/rustcraft/bridge/capture/LiveWriterHooks"

INVOKESTATIC = 184
# 172..177 are IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN. Note that
# plain RETURN is 177 and ARETURN is 176: treating the range as ending at 176
# silently misses every void method's return, which is most of them.
RETURN_OPCODES = frozenset((172, 173, 174, 175, 176, 177))

BEGIN_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
END_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Throwable;)V"


def _call(name: str, descriptor: str) -> dict:
    """A one-per-site call. Overridable with count for the per-return ends."""
    return {"opcode": INVOKESTATIC, "owner": HOOKS, "name": name,
            "descriptor": descriptor, "count": 1}


def method_facts(dump: list, method: str, descriptor: str) -> list:
    """The single method whose name and descriptor match exactly."""
    matches = [m for m in dump[16] if m[:2] == [method, descriptor]]
    if len(matches) != 1:
        raise ValueError(f"method {method}{descriptor} is not uniquely present in the pre-writer class")
    return matches[0]


def return_count(facts: list) -> int:
    return sum(1 for insn in facts[12] if insn[0] in RETURN_OPCODES)


def plain_return_count(facts: list) -> int:
    """Opcode 177 only. The packet-commit hook keys on Opcodes.RETURN rather
    than on "some return", so a contract that counted all of 172..177 would
    demand commits on value-returning paths the transformer never writes to."""
    return sum(1 for insn in facts[12] if insn[0] == 177)


def expected_calls(hook: dict, pre_facts: list) -> list[dict]:
    """The hook calls a correctly applied writer MUST have inserted.

    Dispatched on the hook ID, not just its type, because that is how the
    transformers actually branch: W58 is a writer bracket PLUS a retire call,
    W59 is three fixed ticket calls rather than any bracket, and the
    PRIVATE_BUILD_BEGIN constructors differ in whether the component being
    registered owns its storage or aliases a constructor argument.

    Every count is derived from the original method alone. The bracket's
    per-return ends are a function of the original return count, so an observer
    reading only the pre-writer bytecode can state what the writer owes.
    """
    ends = return_count(pre_facts) + 1
    begin = _call("writerBegin", BEGIN_DESCRIPTOR)
    end = dict(_call("writerEnd", END_DESCRIPTOR), count=ends)
    hook_id, hook_type = hook["id"], hook["hook_type"]
    if hook_id in ("W56", "W57"):
        return expected_calls_nested_writer(hook_type, pre_facts)
    if hook_id == "W58":
        return [begin, end, _call("retireBeforeUnload", "(Ljava/lang/Object;Ljava/lang/Object;)V")]
    if hook_id == "W59":
        return [_call("ioTaskBegin", "(Ljava/lang/Object;)V"),
                _call("ioReleaseSuccess", "(Ljava/lang/Object;)V"),
                _call("ioReleaseFailure", "(Ljava/lang/Object;)V")]
    if hook_id == "W60":
        return [_call("ioPublicationBegin", "(Ljava/lang/Object;)Ljava/lang/Object;"),
                dict(_call("ioPublicationEnd", END_DESCRIPTOR), count=ends)]
    if hook_id == "S01":
        return [_call("diagnosticSessionStart", "()V"),
                dict(_call("diagnosticSessionEnd", "(Ljava/lang/Throwable;)V"), count=ends)]
    if hook_id == "S02":
        # SPacketChunkDataTransformer: one observe after the super call, one
        # commit before each plain RETURN, one abort in the catch-all.
        return [_call("packetCaptureObserve", "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;"),
                dict(_call("packetCaptureCommit", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"),
                     count=plain_return_count(pre_facts)),
                _call("packetCaptureAbort", "(Ljava/lang/Object;Ljava/lang/Throwable;)V")]
    if hook_type == "WRITE_BEGIN":
        return [begin, end]
    if hook_type == "PRIVATE_BUILD_BEGIN":
        # Two different families share this hook type. The ownership transformer
        # owns constructors (REGISTER_NEW_IDENTITY_AFTER_SUPER_BEFORE_ESCAPE)
        # and registers the component's fresh identity; the publication
        # transformer's supplemental path owns the METHOD_ENTRY loader methods
        # and records private I/O provenance instead. Keying on the hook type
        # alone sent the loader methods to the ownership family and demanded
        # registerNew calls the transformer never writes.
        if hook["method"] == "<init>":
            if hook["class"] == "net.minecraft.world.chunk.Chunk":
                return [_call("ownerChunkConstructed", "(Ljava/lang/Object;Ljava/lang/Object;)V")]
            return [_call("registerNew", "(Ljava/lang/Object;Ljava/lang/Object;)V")]
        if hook_id == "S03":
            # The load scope opens before anything else; the pending-NBT marker
            # and the disk-root marker are each anchored to a single verified
            # read in the pre-writer bytecode, so all three are single calls.
            # The declared call is the qualified SAFE wrapper, because that is
            # the call the writer now injects at the anchor: the wrapper is where
            # a failing observation is contained. The engine still recounts every
            # one of these from the transformed bytecode by owner, name and
            # descriptor, so this states what must be there and not what is
            # believed to be there. The wrapper's own containment is proved from
            # the wrapper class, separately.
            return [_call("safeIoPrivateLoadScope", "(Ljava/lang/Object;II)V"),
                    _call("safeIoPendingNbt", "(Ljava/lang/Object;)V"),
                    _call("safeIoDiskRoot", "(Ljava/lang/Object;)V")]
        if hook_id in ("S04", "S05"):
            return [_call("safeIoPrivateConstructionSite", "(Ljava/lang/Object;)V")]
        if hook_id == "S06":
            return [_call("safeGeneratorScopeBegin", "(Ljava/lang/Object;)V")]
    raise ValueError("no derived call contract for hook " + hook_id + " (" + hook_type + ")")


def expected_calls_nested_writer(hook_type: str, pre_facts: list) -> list[dict]:
    """W56/W57 carry BOTH a publication scope and the whole-operation bracket."""
    ends = return_count(pre_facts) + 1
    return [_call("publicationScopeBegin", BEGIN_DESCRIPTOR),
            dict(_call("publicationScopeEnd", END_DESCRIPTOR), count=ends),
            _call("writerBegin", BEGIN_DESCRIPTOR),
            dict(_call("writerEnd", END_DESCRIPTOR), count=ends)]
