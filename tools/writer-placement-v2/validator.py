"""Independent, conservative proof rules for the diagnostic writer hook grammar.

No transformer code is imported or run. The rules compare complete V2 facts;
unsupported metadata rewrites remain blockers rather than wildcard matches.
"""
from collections import Counter, deque
from copy import deepcopy
import json

FACADE = "com/rustcraft/bridge/capture/LiveWriterHooks"
END_DESC = "(Ljava/lang/Object;Ljava/lang/Throwable;)V"
RETURNS = set(range(172, 178))
JUMPS = set(range(153, 170)) | {198, 199}
TOP = ["verification_tag", 0]
OBJECT = ["object", "java/lang/Object"]


class Failure(ValueError):
    pass


def need(condition, reason):
    if not condition:
        raise Failure(reason)


def ins(op, *operands):
    return [op, list(operands), []]


def call(name, desc):
    return ins(184, FACADE, name, desc, False)


def relocation(node, boundary):
    n = deepcopy(node)
    op, operands, _ = n
    if op in JUMPS:
        need(op not in (168, 169), "JSR/RET unsupported")
        operands[0] = boundary[operands[0]]
    elif op == 170:
        operands[2] = boundary[operands[2]]
        operands[3] = [boundary[x] for x in operands[3]]
    elif op == 171:
        operands[1] = boundary[operands[1]]
        operands[2] = [boundary[x] for x in operands[2]]
    return n


def descriptor_types(desc):
    need(isinstance(desc, str) and desc.startswith("("), "bad method descriptor")
    args, p = [], 1
    while desc[p] != ")":
        start = p
        while desc[p] == "[":
            p += 1
        if desc[p] == "L":
            p = desc.index(";", p) + 1
        else:
            need(desc[p] in "BCDFIJSZ", "bad descriptor type")
            p += 1
        args.append(desc[start:p])
    return args, desc[p + 1:]


def width(desc):
    return 0 if desc == "V" else 2 if desc in ("J", "D") else 1


def initial_locals(owner, method):
    values = [] if method[3] & 8 else [["object", owner]]
    for arg in descriptor_types(method[1])[0]:
        if arg.startswith("["):
            values.append(["object", arg])
        elif arg.startswith("L"):
            values.append(["object", arg[1:-1]])
        else:
            values.append(["verification_tag", {"F": 2, "D": 3, "J": 4}.get(arg, 1)])
    return values


def slots(values):
    result = []
    for value in values:
        result.append(value)
        if value in (["verification_tag", 3], ["verification_tag", 4]):
            result.append(["wide_second"])
    return result


def compress(values):
    result = [v for v in values if v != ["wide_second"]]
    while result and result[-1] == TOP:
        result.pop()
    return result


def delta(node):
    op, a, _ = node
    if op in (0, 132, 167, 192, 193, 190): return 0
    if 1 <= op <= 8 or 11 <= op <= 13 or op in (16, 17, 187): return 1
    if op in (9, 10, 14, 15): return 2
    if op == 18: return 2 if a[0][0] in ("long", "double_bits") else 1
    if 21 <= op <= 25: return 2 if op in (22, 24) else 1
    if 46 <= op <= 53: return 0 if op in (47, 49) else -1
    if 54 <= op <= 58: return -2 if op in (55, 57) else -1
    if 79 <= op <= 86: return -4 if op in (80, 82) else -3
    if op in (87, 88): return -1 if op == 87 else -2
    if 89 <= op <= 91: return 1
    if 92 <= op <= 94: return 2
    if op == 95: return 0
    if 96 <= op <= 115: return -2 if op % 4 in (1, 3) else -1
    if 116 <= op <= 119: return 0
    if 120 <= op <= 125: return -1
    if 126 <= op <= 131: return -2 if op % 2 else -1
    if 133 <= op <= 147: return {133:1, 135:1, 136:-1, 137:-1, 140:1, 141:1, 142:-1, 144:-1}.get(op, 0)
    if 148 <= op <= 152: return -3 if op in (148, 151, 152) else -1
    if 153 <= op <= 158 or op in (170, 171, 198, 199): return -1
    if 159 <= op <= 166: return -2
    if op in RETURNS: return 0 if op == 177 else -2 if op in (173, 175) else -1
    if 178 <= op <= 181:
        w = width(a[2])
        return {178:w, 179:-w, 180:w-1, 181:-w-1}[op]
    if 182 <= op <= 186:
        args, result = descriptor_types(a[1] if op == 186 else a[2])
        return width(result) - sum(width(x) for x in args) - (0 if op in (184, 186) else 1)
    if op in (188, 189): return 0
    if op in (191, 194, 195): return -1
    if op == 197: return 1 - a[1]
    raise Failure("unsupported opcode in stack-height proof: " + str(op))


def stack_max(instructions, handlers):
    if not instructions: return 0
    queue, seen, maximum = deque([(0, 0)] + [(h[2], 1) for h in handlers]), {}, 0
    while queue:
        pc, height = queue.popleft()
        need(0 <= pc < len(instructions), "control flow outside method")
        if pc in seen:
            need(seen[pc] == height, "inconsistent stack height at merge")
            continue
        seen[pc] = height
        n = instructions[pc]
        after = height + delta(n)
        need(after >= 0, "stack underflow")
        maximum = max(maximum, height, after)
        op, a, _ = n
        if op in RETURNS or op == 191: continue
        if op == 167:
            successors = [a[0]]
        elif op in JUMPS:
            need(op not in (168, 169), "JSR/RET unsupported")
            successors = [a[0], pc + 1]
        elif op == 170: successors = [a[2], *a[3]]
        elif op == 171: successors = [a[1], *a[2]]
        else: successors = [pc + 1]
        queue.extend((target, after) for target in successors)
    return maximum


def anchor_indices(hook, method, byte_offsets):
    fp = hook.get("fingerprint")
    need(isinstance(fp, dict), "missing explicit fingerprint kind")
    if fp["kind"] == "DECLARATION": return []
    if fp["kind"] == "CLASS_STRUCTURE":
        need(hook["id"] == "S01" and fp.get("markers") == ["declares field_175590_aa (Thread)", "declares func_175583_aK", "run calls func_71217_p"], "unknown class-structure contract")
        return []
    need(fp["kind"] == "BCI_ASSERTIONS", "unsupported fingerprint kind")
    points, prev = [], -1
    # The plan's legacy display fragment only checks the source at an EXACT BCI.
    # It never matches post instructions or selects among candidate insertions.
    names = {178:"getstatic",179:"putstatic",180:"getfield",181:"putfield",182:"invokevirtual",183:"invokespecial",184:"invokestatic",185:"invokeinterface",177:"return",176:"areturn",172:"ireturn",198:"ifnull",199:"ifnonnull",84:"bastore",79:"iastore",89:"dup",191:"athrow",1:"aconst_null",194:"monitorenter",195:"monitorexit"}
    for assertion in fp.get("assertions", []):
        need(type(assertion.get("bci")) is int and assertion["bci"] in byte_offsets, "anchor BCI not instruction boundary")
        index = byte_offsets.index(assertion["bci"])
        need(index > prev, "anchors not strictly ordered")
        prev = index
        op, args, _ = method[12][index]
        fragment = assertion["expect"]
        opcode, _, suffix = fragment.partition(" ")
        actual_op = "aload_" + str(args[0]) if op == 25 and args[0] <= 3 else names.get(op, "aload" if op == 25 else "op" + str(op))
        need(opcode == actual_op, "anchor source opcode mismatch")
        if suffix:
            candidates = []
            if op in range(182, 186):
                name = '"<init>"' if args[1] == "<init>" else args[1]
                candidates = [args[0] + "." + name, name, args[0] + "." + args[1], args[1]]
            elif op in range(178, 182): candidates = [args[1] + ":" + args[2]]
            elif op == 25: candidates = [str(args[0])]
            need(suffix in candidates, "anchor source operand fragment mismatch")
        points.append(index)
    need(points, "empty anchor assertions")
    return points


def layout(owner, super_name, method, hook, offsets):
    original = method[12]
    need(original and not method[3] & (0x100 | 0x400), "hook requires concrete method")
    need(not any(n[0] in range(182, 186) and n[1][0] == FACADE for n in original), "pre-hook facade calls forbidden")
    anchors = anchor_indices(hook, method, offsets)
    kind, hid = hook["hook_type"], hook["id"]
    opid = "liveWriter." + hid + "." + owner.rsplit("/", 1)[-1] + "." + method[0].replace("<init>", "ctor")
    prefix, before, after, suffix = [], {}, {}, []
    scopes, extra_handlers = [], []
    max_locals = method[11]
    def add(where, at, nodes): where.setdefault(at, []).extend(nodes)
    def bracket(begin, args, desc, end, token, ex):
        start = len(prefix) + 2
        prefix.extend([ins(1), ins(58, token), *args, call(begin, desc), ins(58, token)])
        scopes.append(dict(start=start, end=end, token=token, ex=ex))
    writer_args = [ins(1) if method[3] & 8 else ins(25, 0), ins(18, ["string", opid])]
    writer_desc = "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
    if hid in ("W56", "W57"):
        need(kind == "WRITE_BEGIN", "nested publication hook type mismatch")
        bracket("writerBegin", writer_args, writer_desc, "writerEnd", max_locals + 2, max_locals + 3)
        bracket("publicationScopeBegin", [ins(25, 0), ins(180, owner, "field_73251_h", "Lnet/minecraft/world/WorldServer;"), ins(18, ["string", opid + ".publication"])], writer_desc, "publicationScopeEnd", max_locals, max_locals + 1)
        max_locals += 4
    elif kind == "WRITE_BEGIN" or hid == "W58":
        need(method[0] != "<init>", "writer bracket on constructor unsupported")
        bracket("writerBegin", writer_args, writer_desc, "writerEnd", max_locals, max_locals + 1)
        max_locals += 2
        if hid == "W58":
            need(kind == "LIFECYCLE_RETIRE" and len(anchors) == 5, "retire anchor inventory")
            pos = anchors[0]
            need(original[pos][0:2] == [182, ["net/minecraft/world/chunk/Chunk", "func_76623_d", "()V", False]], "retire target operand")
            need(pos > 0 and original[pos - 1][0] == 25, "retire receiver local not explicit")
            add(before, pos, [ins(25, 0), ins(180, owner, "field_73251_h", "Lnet/minecraft/world/WorldServer;"), ins(25, original[pos - 1][1][0]), call("retireBeforeUnload", "(Ljava/lang/Object;Ljava/lang/Object;)V")])
    elif hid == "W60":
        need(kind == "PUBLICATION_BEGIN", "IO publication hook type")
        bracket("ioPublicationBegin", [ins(25, 0)], "(Ljava/lang/Object;)Ljava/lang/Object;", "ioPublicationEnd", max_locals, max_locals + 1)
        max_locals += 2
    elif hid == "W59":
        need(kind == "PRIVATE_BUILD_END" and len(anchors) == 4, "IO release anchor inventory")
        prefix = [ins(25, 0), call("ioTaskBegin", "(Ljava/lang/Object;)V")]
        for at, name in zip(anchors[2:], ("ioReleaseSuccess", "ioReleaseFailure")):
            need(original[at][0:2] == [181, [owner, "ran", "Z"]], "IO release field operand")
            add(before, at, [ins(25, 0), call(name, "(Ljava/lang/Object;)V")])
    elif hid == "S01":
        need(kind == "DIAGNOSTIC_ONLY" and method[:2] == ["run", "()V"], "session run shape")
        prefix = [call("diagnosticSessionStart", "()V")]
        for i, n in enumerate(original):
            if n[0] in RETURNS: add(before, i, [ins(1), call("diagnosticSessionEnd", "(Ljava/lang/Throwable;)V")])
        suffix = [ins(58, max_locals), ins(25, max_locals), call("diagnosticSessionEnd", "(Ljava/lang/Throwable;)V"), ins(25, max_locals), ins(191)]
        extra_handlers.append(dict(start=0, suffix=0, scope=None))
        max_locals += 1
    elif hid == "S02":
        need(kind == "PACKET_CAPTURE" and method[0] == "<init>", "packet constructor shape")
        need(anchors and original[anchors[0]][0:2] == [183, [super_name, "<init>", "()V", False]], "packet super call")
        token, ex = max_locals, max_locals + 1
        max_locals += 2
        prefix = [ins(1), ins(58, token)]
        add(after, anchors[0], [ins(25, 0), ins(25, 1), ins(21, 2), call("packetCaptureObserve", "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;"), ins(58, token)])
        for i, n in enumerate(original):
            if n[0] in RETURNS:
                need(n[0] == 177, "packet nonvoid return")
                add(before, i, [ins(25, token), ins(25, 0), ins(25, 1), ins(21, 2), call("packetCaptureCommit", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V")])
        suffix = [ins(58, ex), ins(25, token), ins(25, ex), call("packetCaptureAbort", END_DESC), ins(25, ex), ins(191)]
        extra_handlers.append(dict(start_after=anchors[0], suffix=0, scope=dict(token=token, ex=ex)))
    elif hid == "S03":
        need(kind == "PRIVATE_BUILD_BEGIN" and len(anchors) == 6 and not method[3] & 8, "private IO entry shape")
        prefix = [ins(25, 1), ins(21, 2), ins(21, 3), call("ioPrivateLoadScope", "(Ljava/lang/Object;II)V")]
        add(after, anchors[0], [ins(89), call("ioPendingNbt", "(Ljava/lang/Object;)V")])
        add(after, anchors[2], [ins(89), call("ioDiskRoot", "(Ljava/lang/Object;)V")])
    elif hid in ("S04", "S05", "S06"):
        need(kind == ("DIAGNOSTIC_ONLY" if hid == "S06" else "PRIVATE_BUILD_BEGIN"), "entry marker hook type")
        prefix = [ins(25, 0), call("generatorScopeBegin" if hid == "S06" else "ioPrivateConstructionSite", "(Ljava/lang/Object;)V")]
    elif kind == "PRIVATE_BUILD_BEGIN" and method[0] == "<init>":
        constructors = [i for i, n in enumerate(original) if n[0] == 183 and n[1][1] == "<init>"]
        need(constructors and original[constructors[0]][1][0] in (owner, super_name), "constructor delegation unknown")
        name = "ownerChunkConstructed" if owner == "net/minecraft/world/chunk/Chunk" else "registerNew"
        backing = ins(25, 1) if name == "ownerChunkConstructed" or (owner == "net/minecraft/world/chunk/NibbleArray" and method[1] == "([B)V") else ins(1)
        add(after, constructors[0], [ins(25, 0), backing, call(name, "(Ljava/lang/Object;Ljava/lang/Object;)V")])
    else:
        raise Failure("unknown hook shape: " + hid + "/" + kind)
    # Normal exits close inner-to-outer. Appended handlers use the same order.
    for scope in reversed(scopes):
        for i, n in enumerate(original):
            if n[0] in RETURNS:
                add(before, i, [ins(25, scope["token"]), ins(1), call(scope["end"], END_DESC)])
        offset = len(suffix)
        suffix += [ins(58, scope["ex"]), ins(25, scope["token"]), ins(25, scope["ex"]), call(scope["end"], END_DESC), ins(25, scope["ex"]), ins(191)]
        extra_handlers.append(dict(start=scope["start"], suffix=offset, scope=scope))
    expected, boundary, original_at = list(prefix), {}, {}
    for i, n in enumerate(original):
        boundary[i] = len(expected)
        expected.extend(before.get(i, []))
        original_at[i] = len(expected)
        expected.append(n)
        expected.extend(after.get(i, []))
    boundary[len(original)] = len(expected)
    suffix_start = len(expected)
    expected.extend(suffix)
    for i, pos in original_at.items(): expected[pos] = relocation(original[i], boundary)
    handlers = [[boundary[h[0]], boundary[h[1]], boundary[h[2]], *deepcopy(h[3:])] for h in method[13]]
    for h in extra_handlers:
        start = original_at[h["start_after"]] + 1 if "start_after" in h else h["start"]
        end = suffix_start + h["suffix"]
        handlers.append([start, end, end, None, []])
        h.update(pc=end, start=start)
    # Anchor-before insertion is allowed only where no original branch/handler
    # jumps can bypass it; return boundaries deliberately enter the end group.
    targeted = set()
    for n in original:
        if n[0] in JUMPS: targeted.add(n[1][0])
        if n[0] == 170: targeted.update([n[1][2], *n[1][3]])
        if n[0] == 171: targeted.update([n[1][1], *n[1][2]])
    targeted.update(h[2] for h in method[13])
    need(not any(i in targeted and original[i][0] not in RETURNS for i in before), "anchor insertion at alternate control-flow entry")
    return dict(instructions=expected, handlers=handlers, boundary=boundary, original_at=original_at,
                inserted=[i for i in range(len(expected)) if i not in set(original_at.values())],
                extra_handlers=extra_handlers, scopes=scopes, max_locals=max_locals, anchors=anchors)


def mapped_values(values, boundary):
    return [[v[0], boundary[v[1]]] if v[0] == "uninitialized" else v for v in values]


def metadata(method, plan):
    result, b = deepcopy(method), plan["boundary"]
    result[12], result[13] = plan["instructions"], plan["handlers"]
    result[11] = plan["max_locals"]
    result[10] = stack_max(result[12], result[13])
    result[15] = [[line, b[pc]] for line, pc in method[15]]
    result[16] = [[n, d, s, b[start], b[end], index] for n, d, s, start, end, index in method[16]]
    result[17] = [[*a[:5], [b[x] for x in a[5]], [b[x] for x in a[6]], a[7]] for a in method[17]]
    return result


def frame_checks(owner, before, after, plan):
    """Conservative exact old-frame projection; unknown recomputation blocks PASS.

    Added catch-all frames have a strict proof for unchanged argument slots:
    their range includes begin, so every other original slot meets TOP at entry.
    More general argument reassignment is not silently widened.
    """
    b, failures = plan["boundary"], []
    old = {b[f[0]]: [mapped_values(f[2], b), mapped_values(f[3], b)] for f in before[14]}
    need(len(old) == len(before[14]), "duplicate original stack-map position")
    actual = {f[0]: f for f in after[14]}
    need(len(actual) == len(after[14]), "duplicate stack-map position")
    added = {h["pc"]: h for h in plan["extra_handlers"]}
    need(set(actual) == set(old) | set(added), "stack-map position inventory changed")
    for pc, (local, stack) in old.items():
        f = actual[pc]
        projected = compress(slots(f[2])[:before[11]])
        if projected != compress(slots(local)) or f[3] != stack or f[1] != -1:
            failures.append(dict(kind="ORIGINAL_FRAME_REWRITE_UNPROVEN", pc=pc, expected=[local, stack], observed=[projected, f[3]]))
        # No arbitrary added verifier local is permitted at original targets.
        allowed = {s["token"] for s in plan["scopes"]}
        if plan["extra_handlers"] and not plan["scopes"]:
            allowed.update(h["scope"]["token"] for h in plan["extra_handlers"] if h["scope"])
        extra = slots(f[2])[before[11]:]
        expected_extra = compress([OBJECT if i in allowed else TOP for i in range(before[11], plan["max_locals"])])
        if compress(extra) != expected_extra:
            failures.append(dict(kind="ADDED_FRAME_LOCAL_UNPROVEN", pc=pc, observed=extra))
    args = slots(initial_locals(owner, before))
    # ASTORE into an argument can alter its precise reference type. Unsupported
    # until a complete independent verifier relation has been supplied.
    mutates_args = any(n[0] in (54,55,56,57,58) and n[1][0] < len(args) for n in before[12])
    for pc, h in added.items():
        f = actual[pc]
        expected = deepcopy(args) + [TOP] * (plan["max_locals"] - len(args))
        for scope in plan["scopes"]:
            if scope["start"] <= h["start"]: expected[scope["token"]] = OBJECT
        if h["scope"]: expected[h["scope"]["token"]] = OBJECT
        expected = compress(expected)
        if mutates_args or f[1:] != [-1, expected, [["object", "java/lang/Throwable"]]]:
            failures.append(dict(kind="HANDLER_FRAME_RELATION_UNPROVEN", pc=pc, argument_reassignment=mutates_args,
                                 expected=expected, observed=f[2]))
    return failures


def verify_method(owner, super_name, before, after, hook, offsets):
    plan = layout(owner, super_name, before, hook, offsets)
    expected = metadata(before, plan)
    for i in list(range(14)) + list(range(15, 20)):
        need(after[i] == expected[i], "method V2 field %d differs (instruction/operand/target/handler/metadata preservation)" % i)
    blockers = frame_checks(owner, before, after, plan)
    return dict(id=hook["id"], status="INCOMPLETE" if blockers else "PASS", blockers=blockers,
                original_instructions=len(before[12]), inserted_instructions=len(plan["inserted"]),
                original_handlers=len(before[13]), added_handlers=len(plan["extra_handlers"]),
                normal_exits=sum(n[0] in RETURNS for n in before[12]),
                anchors=[dict(bci=offsets[i], ordinal=i, exact_instruction=before[12][i]) for i in plan["anchors"]],
                plan=plan)


def pool_entries(node):
    out = []
    def add(value):
        out.append(json.dumps(value, ensure_ascii=True, separators=(",", ":")))
    op, a, _ = node
    if op == 18 and a[0][0] == "string":
        add(a[0]); add(["utf8", a[0][1]])
    elif op in range(178, 186):
        cls, name, desc = a[:3]
        add(["class", cls]); add(["utf8", cls]); add(["utf8", name]); add(["utf8", desc]); add(["name_type", name, desc])
        add(["field" if op <= 181 else "interface_method" if a[3] else "method", ["class", cls], ["name_type", name, desc]])
    return out


def verify_class(pre, post, hooks):
    a, b = pre["dump"], post["dump"]
    need(len(a) == len(b) == 19 and a[0] == b[0] == "CANONICAL_ID_V2", "V2 class schema")
    need(a[:16] == b[:16], "class/field/annotation metadata changed")
    need(a[18] == b[18], "symbolic bootstrap inventory changed")
    need(pre["receipt"][3] == post["receipt"][3], "member declaration order changed")
    before = {tuple(m[:2]): m for m in a[16]}
    after = {tuple(m[:2]): m for m in b[16]}
    need(len(before) == len(a[16]) and len(after) == len(b[16]) and before.keys() == after.keys(), "method inventory changed")
    hook_map = {(h["method"], h["descriptor"]): h for h in hooks}
    need(len(hook_map) == len(hooks) and hook_map.keys() <= before.keys(), "duplicate/missing required method")
    if any(h["id"] == "S01" for h in hooks):
        need(any(f[1:3] == ["field_175590_aa", "Ljava/lang/Thread;"] for f in a[15]), "S01 Thread field missing")
        need(("func_175583_aK", "()Ljava/lang/Thread;") in before, "S01 Thread getter missing")
        need(any(n == ins(182, a[3], "func_71217_p", "()V", False) for n in before[("run", "()V")][12]), "S01 exact tick call missing")
    rows, unhooked, allowed_pool = [], [], set()
    for key, method in before.items():
        if key in hook_map:
            try:
                row = verify_method(a[3], a[5], method, after[key], hook_map[key], pre["offsets"][key[0]+key[1]])
                plan = row.pop("plan")
                for pc in plan["inserted"]: allowed_pool.update(pool_entries(plan["instructions"][pc]))
                rows.append(row)
            except (ValueError, KeyError, IndexError, TypeError) as error:
                rows.append(dict(id=hook_map[key]["id"], status="FAIL", error=str(error)))
        else:
            differences = [i for i in range(20) if method[i] != after[key][i]]
            if differences:
                unhooked.append(dict(method=list(key), status="INCOMPLETE" if differences == [14] else "FAIL",
                                     fields=differences, before_frames=method[14], after_frames=after[key][14]))
    # New bookkeeping constants are finite and explicit. Original pool entries,
    # including unused/duplicate entries, must survive; arbitrary new constants fail.
    for cls in ("java/lang/Object", "java/lang/Throwable"):
        allowed_pool.update(json.dumps(x,separators=(",",":")) for x in (["class",cls],["utf8",cls]))
    allowed_pool.add('["utf8","StackMapTable"]')
    old_pool, new_pool = Counter(a[17]), Counter(b[17])
    removed, added = old_pool-new_pool, new_pool-old_pool
    # Canonicalizer escapes non-ASCII using Java spelling; injected entries are ASCII.
    frame_pool = set()
    for m in b[16]:
        for f in m[14]:
            for v in f[2] + f[3]:
                if v[0] == "object":
                    frame_pool.update(json.dumps(x,separators=(",",":")) for x in (["class",v[1]],["utf8",v[1]]))
    duplicate_added = [v for v,n in added.items() if n > (0 if old_pool[v] else 1)]
    unknown_added = [v for v in added.elements() if v not in allowed_pool]
    frame_pending = [v for v in unknown_added if v in frame_pool]
    pool_failures = dict(removed=list(removed.elements()), unexpected_added=[v for v in unknown_added if v not in frame_pool], duplicate_added=duplicate_added)
    status = "FAIL" if any(r["status"] == "FAIL" for r in rows+unhooked) or any(pool_failures.values()) else "INCOMPLETE" if unhooked or frame_pending or any(r["status"] == "INCOMPLETE" for r in rows) else "PASS"
    return dict(class_name=a[3], status=status, sites=rows, unhooked_method_differences=unhooked, pool_failures=pool_failures,
                frame_related_pool_additions_unproven=frame_pending,
                pre_identity=pre["receipt"], post_identity=post["receipt"], original_methods=len(before), required_sites=len(hooks))


def verify(pre, post, hooks):
    need(isinstance(hooks, list) and 0 < len(hooks) <= 256, "bounded nonempty hook inventory")
    need(len({h["id"] for h in hooks}) == len(hooks), "duplicate hook id")
    names = {h["class"].replace(".", "/") for h in hooks}
    need(set(pre) == set(post) == names, "exact required class inventory differs")
    records = []
    for name in sorted(names):
        try:
            records.append(verify_class(pre[name], post[name], [h for h in hooks if h["class"].replace(".", "/") == name]))
        except (ValueError, KeyError, IndexError, TypeError) as error:
            records.append(dict(class_name=name, status="FAIL", error=str(error)))
    status = "FAIL" if any(r["status"] == "FAIL" for r in records) else "INCOMPLETE" if any(r["status"] == "INCOMPLETE" for r in records) else "PASS"
    return dict(schema="RUSTCRAFT_WRITER_PLACEMENT_AUDIT_V2", status=status, production_authority=False,
                scope="OFFLINE_EXACT_INSERTION_AND_V2_PRESERVATION; no live writer/lifecycle closure", records=records,
                required_hook_count=len(hooks), observed_class_count=len(records))
