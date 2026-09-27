"""Finite verifier metadata grammar. This is not a replacement JVM type verifier."""
TOP = ("tag", 0)
SECOND = ("wide_second",)


def need(value, reason):
    if not value:
        raise ValueError(reason)


def value(raw, method):
    need(isinstance(raw, list) and len(raw) == 2, "verification value shape")
    kind, arg = raw
    if kind == "verification_tag":
        need(type(arg) is int and 0 <= arg <= 6, "verification tag outside finite grammar")
        need(arg != 6 or method[0] == "<init>", "uninitializedThis outside constructor")
        return ("tag", arg)
    if kind == "object":
        need(isinstance(arg, str) and arg, "empty verifier reference type")
        return ("object", arg)
    need(kind == "uninitialized" and type(arg) is int and 0 <= arg < len(method[12]), "uninitialized value position")
    need(method[12][arg][0] == 187, "uninitialized value must name NEW")
    return ("new", arg)


def slots(raw, method, maximum, locals_vector):
    result = []
    for item in raw:
        typed = value(item, method)
        result.append(typed)
        if typed in (("tag", 3), ("tag", 4)):
            result.append(SECOND)
    need(len(result) <= maximum, "frame exceeds declared slot limit")
    if locals_vector:
        result += [TOP] * (maximum - len(result))
    return result


def frame_inventory(method):
    need(type(method[10]) is int and 0 <= method[10] <= 65535, "max stack range")
    need(type(method[11]) is int and 0 <= method[11] <= 65535, "max locals range")
    need(isinstance(method[14],list) and len(method[14]) <= 65535, "frame inventory bound")
    need(len(method[14]) * (method[11] + method[10]) <= 524288, "expanded frame slot budget exceeded")
    out = {}
    previous = -1
    for frame in method[14]:
        need(isinstance(frame, list) and len(frame) == 4 and frame[1] == -1, "expanded frame schema")
        pc = frame[0]
        need(type(pc) is int and previous < pc < len(method[12]), "frame boundary/order")
        previous = pc
        out[pc] = (slots(frame[2], method, method[11], True), slots(frame[3], method, method[10], False))
    return out


def references(method):
    return {v[1] for frame in method[14] for v in frame[2] + frame[3] if v[0] == "object"}


def relation(before, after, plan):
    """Original code is proved separately. Both complete classes must verify later.

    Primitive changes require a TOP slot; unrelated live primitive types never
    become equivalent. Category-two halves cannot be split. Uninitialized values
    must preserve the same mapped original allocation, including constructor-this.
    Reference changes require actual cross-graph assignability evidence later.
    """
    old, new = frame_inventory(before), frame_inventory(after)
    boundary, positions = plan["boundary"], plan["original_at"]
    mapped = {boundary[pc]: vector for pc, vector in old.items()}
    added = {h["pc"]: h for h in plan["extra_handlers"]}
    blockers, changes, questions = [], [], set()
    if set(new) != set(mapped) | set(added):
        blockers.append(dict(kind="UNSUPPORTED_FRAME_POSITION_CHANGE", old=sorted(mapped), added=sorted(added), observed=sorted(new)))
        return dict(blockers=blockers, changes=changes, questions=[])

    def mapped_value(v):
        return ("new", positions[v[1]]) if v[0] == "new" else v

    def compare(a, b, context, pc):
        if len(a) != len(b):
            blockers.append(dict(kind="FRAME_SLOT_COUNT_CHANGED", context=context, pc=pc, old=len(a), new=len(b)))
            return
        for index, (x, y) in enumerate(zip(a, b)):
            x = mapped_value(x)
            if x == y:
                continue
            kind = None
            if x == SECOND or y == SECOND:
                # A category-two value can only replace/be replaced by two TOPs.
                if context == "locals" and index > 0 and ((x == TOP and y == SECOND and a[index-1] == TOP) or (x == SECOND and y == TOP and b[index-1] == TOP)):
                    continue
            elif x[0] == "new" or y[0] == "new" or x == ("tag", 6) or y == ("tag", 6):
                pass
            elif context == "locals" and (x == TOP or y == TOP):
                width = 2 if x in (("tag",3),("tag",4)) or y in (("tag",3),("tag",4)) else 1
                if width == 1 or (index+1 < len(a) and ((x == TOP and a[index+1] == TOP) or (y == TOP and b[index+1] == TOP))):
                    kind = "LOCAL_TOP_REFINEMENT_OR_FORGETTING"
            elif x[0] == y[0] == "object":
                questions.update(((x[1], y[1]), (y[1], x[1])))
                kind = "RESOLVED_COMPARABLE_REFERENCE_REQUIRED"
            elif (x == ("tag",5) and y[0] == "object") or (y == ("tag",5) and x[0] == "object"):
                kind = "NULL_REFERENCE_RELATION_REQUIRES_TYPE_RESOLUTION"
            row = dict(context=context, pc=pc, slot=index, old=list(x), new=list(y))
            if kind:
                changes.append(dict(kind=kind, **row))
            else:
                blockers.append(dict(kind="UNSUPPORTED_TYPED_FRAME_DELTA", **row))

    tokens = {s["token"] for s in plan["scopes"]}
    tokens.update(h["scope"]["token"] for h in plan["extra_handlers"] if h["scope"])
    for pc, (local, stack) in mapped.items():
        actual_local, actual_stack = new[pc]
        compare(local, actual_local[:before[11]], "locals", pc)
        compare(stack, actual_stack, "stack", pc)
        for index in range(before[11], after[11]):
            expected = ("object", "java/lang/Object") if index in tokens else TOP
            if actual_local[index] != expected:
                blockers.append(dict(kind="UNSUPPORTED_INSERTED_LOCAL", pc=pc, slot=index, expected=list(expected), actual=list(actual_local[index])))
    for pc, handler in added.items():
        local, stack = new[pc]
        need(stack == [("object", "java/lang/Throwable")], "catch-all frame stack must be exactly Throwable")
        # The exact appended handler body reads no original slots. Its original
        # local vector can therefore use any grammar-valid verifier merge, but
        # no uninitialized allocation is admitted by this bounded relation.
        if any(v[0] == "new" or v == ("tag", 6) for v in local[:before[11]]):
            blockers.append(dict(kind="UNSUPPORTED_UNINITIALIZED_SYNTHETIC_HANDLER", pc=pc))
        active = {s["token"] for s in plan["scopes"] if s["start"] <= handler["start"]}
        if handler["scope"]:
            active.add(handler["scope"]["token"])
        for index in range(before[11], after[11]):
            expected = ("object", "java/lang/Object") if index in active else TOP
            if local[index] != expected:
                blockers.append(dict(kind="UNSUPPORTED_HANDLER_BOOKKEEPING_LOCAL", pc=pc, slot=index, expected=list(expected), actual=list(local[index])))
        changes.append(dict(kind="VERIFIER_VALID_ORIGINAL_LOCALS_UNUSED_BY_EXACT_NEW_HANDLER", pc=pc,
                            original_local_slots=[list(v) for v in local[:before[11]]]))
    return dict(blockers=blockers, changes=changes,
                questions=[dict(source=a,target=b) for a,b in sorted(questions)])
