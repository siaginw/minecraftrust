"""Exact insertion and non-frame preservation plus explicit finite frame obligations."""
from collections import Counter
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/writer-placement-v2"))
from validator import layout, metadata, pool_entries, ins, stack_max
from frames import need, relation, references


def unchanged_plan(method):
    size = len(method[12])
    return dict(boundary={i:i for i in range(size+1)}, original_at={i:i for i in range(size)},
                inserted=[], extra_handlers=[], scopes=[], max_locals=method[11], instructions=method[12], handlers=method[13])


def class_proof(pre, post, hooks):
    a, b = pre["dump"], post["dump"]
    need(len(a) == len(b) == 19 and a[0] == b[0] == "CANONICAL_ID_V2", "canonical schema")
    need(a[:16] == b[:16] and a[18] == b[18], "class/field/annotation/bootstrap preservation")
    need(pre["receipt"][3] == post["receipt"][3], "declaration order changed")
    old, new = ({tuple(m[:2]):m for m in values[16]} for values in (a,b))
    need(len(old) == len(a[16]) and len(new) == len(b[16]) and old.keys() == new.keys(), "method inventory")
    sites = {(h["method"],h["descriptor"]):h for h in hooks}
    need(len(sites) == len(hooks) and sites.keys() <= old.keys(), "hook inventory")
    if any(h["id"] == "S01" for h in hooks):
        need(any(f[1:3] == ["field_175590_aa", "Ljava/lang/Thread;"] for f in a[15]), "S01 Thread field")
        need(("func_175583_aK", "()Ljava/lang/Thread;") in old, "S01 Thread getter")
        need(any(n == ins(182,a[3],"func_71217_p","()V",False) for n in old[("run","()V")][12]), "S01 tick call")
    allowed, types, queries, rows = set(), {a[3]}, set(), []
    for key, before in old.items():
        after = new[key]
        need(len(before) == len(after) == 20, "method schema")
        types.update(references(before)); types.update(references(after))
        if before[13] or after[13]: types.add("java/lang/Throwable")
        if key in sites:
            plan = layout(a[3], a[5], before, sites[key], pre["offsets"][key[0]+key[1]])
            expected = metadata(before, plan)
            for field in range(20):
                if field != 14:
                    need(after[field] == expected[field], "exact inserted/original method field differs: " + str(key) + " field " + str(field))
            for pc in plan["inserted"]: allowed.update(pool_entries(plan["instructions"][pc]))
        else:
            plan = unchanged_plan(before)
            for field in range(20):
                if field not in (10,14): need(before[field] == after[field], "unhooked non-frame metadata/code changed: " + str(key))
            # A higher declared maximum is allowed only as a visible, bounded
            # verifier-only resource declaration; every class still must verify.
            height = stack_max(before[12], before[13])
            need(before[10] >= height and after[10] >= height, "maximum stack below independently propagated height")
        frames = relation(before, after, plan)
        queries.update((q["source"],q["target"]) for q in frames["questions"])
        rows.append(dict(method=list(key), hook_id=sites[key]["id"] if key in sites else None,
                         original_instructions=len(before[12]), inserted_instructions=len(plan["inserted"]),
                         original_handlers=len(before[13]), added_handlers=len(plan["extra_handlers"]),
                         before_max_stack=before[10], after_max_stack=after[10], **frames))
    for name in ("java/lang/Object", "java/lang/Throwable"):
        allowed.update(json.dumps(x,separators=(",",":")) for x in (["class",name],["utf8",name]))
    allowed.add('["utf8","StackMapTable"]')
    frame_pool = {json.dumps(x,separators=(",",":")) for method in b[16] for name in references(method) for x in (["class",name],["utf8",name])}
    old_pool, new_pool = Counter(a[17]), Counter(b[17])
    added, removed = new_pool-old_pool, old_pool-new_pool
    need(not removed, "original symbolic pool entries removed")
    need(all(count == 1 and old_pool[item] == 0 and item in allowed|frame_pool for item,count in added.items()), "unattributable/duplicate added symbolic pool constant")
    return dict(name=a[3], status="INCOMPLETE" if any(r["blockers"] for r in rows) else "STRUCTURALLY_ELIGIBLE",
                methods=rows, types=sorted(types), questions=[dict(source=x,target=y) for x,y in sorted(queries)],
                original_pool_preserved=True, frame_pool_additions=sorted(item for item in added if item not in allowed),
                pre_sha256=pre["receipt"][4], post_sha256=post["receipt"][4])


def prove(pre, post, hooks):
    need(isinstance(hooks,list) and 0 < len(hooks) <= 256 and len({h["id"] for h in hooks}) == len(hooks), "bounded hook inventory")
    names = {h["class"].replace(".","/") for h in hooks}
    need(set(pre) == set(post) == names, "exact class inventory")
    records = []
    for name in sorted(names):
        try:
            row = class_proof(pre[name], post[name], [h for h in hooks if h["class"].replace(".","/") == name])
        except (ValueError, KeyError, IndexError, TypeError) as error:
            row = dict(name=name, status="FAIL", error=str(error))
        records.append(row)
    status = "FAIL" if any(r["status"] == "FAIL" for r in records) else "INCOMPLETE" if any(r["status"] == "INCOMPLETE" for r in records) else "STRUCTURALLY_ELIGIBLE"
    return dict(schema="QUALIFIED_FRAME_STRUCTURE_V1", status=status, production_authority=False, records=records,
                required_classes=len(names), required_hooks=len(hooks), external_verification_and_scope_required=True)


def request(report, session, challenge, bindings):
    need(report["status"] != "FAIL", "cannot request witness for failed insertion proof")
    types, queries = set(), set()
    for row in report["records"]:
        types.update(row["types"])
        queries.update((q["source"],q["target"]) for q in row["questions"])
    return dict(schema="QUALIFIED_FRAME_REQUEST_V1", session=session, challenge=challenge, bindings=bindings,
                classes=[{k:r[k] for k in ("name","pre_sha256","post_sha256")} for r in report["records"]],
                types=sorted(types), assignability=[dict(source=x,target=y) for x,y in sorted(queries)])
