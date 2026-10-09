"""Strict external evidence binding and independently checked loader/Class graph."""
from hashlib import sha256
import re

from frames import need

EMPTY = sha256(b"").hexdigest()


def keys(row, fields):
    need(isinstance(row,dict) and set(row) == set(fields.split()), "schema keys: " + fields)


def pin(value, nullable=False):
    need((nullable and value is None) or (isinstance(value,str) and re.fullmatch("[0-9a-f]{64}",value)), "invalid SHA256 pin")


def identifier(value):
    need(isinstance(value,str) and 0 < len(value) <= 256 and all(ord(c)>=32 for c in value), "invalid opaque identity")


def sequence(value, maximum):
    need(isinstance(value,list) and len(value) <= maximum, "bounded array schema")
    return value


def request_schema(request):
    keys(request,"schema session challenge bindings classes types assignability")
    need(request["schema"] == "QUALIFIED_FRAME_REQUEST_V1", "request version")
    need(isinstance(request["session"],str) and re.fullmatch("[0-9a-f]{32}",request["session"]), "session syntax")
    pin(request["challenge"])
    keys(request["bindings"],"profile_sha256 manifest_sha256 observation_sha256 plan_sha256 validator_sources_sha256")
    for value in request["bindings"].values(): pin(value)
    classes=sequence(request["classes"],256)
    need(classes and [c["name"] for c in classes] == sorted({c["name"] for c in classes}), "class request inventory")
    for row in classes:
        keys(row,"name pre_sha256 post_sha256");identifier(row["name"]);pin(row["pre_sha256"]);pin(row["post_sha256"])
    sequence(request["types"],16384)
    need(request["types"] == sorted(set(request["types"])), "type request uniqueness/order")
    for name in request["types"]: identifier(name)
    sequence(request["assignability"],16384)
    pairs=[]
    for query in request["assignability"]:
        keys(query,"source target")
        need(query["source"] in request["types"] and query["target"] in request["types"], "query outside type inventory")
        pairs.append((query["source"],query["target"]))
    need(pairs == sorted(set(pairs)), "query inventory duplicates/order")


def authenticate(request, request_hash, witness, witness_hash, trust, receipt, receipt_hash):
    keys(trust,"schema session challenge request_sha256 witness_sha256 collector_receipt_sha256 collector_sources_sha256 vm_inventory_sha256 observer_policy loader_effect_policy")
    keys(receipt,"schema session challenge request_sha256 witness_sha256 collector_sources_sha256 vm_inventory_sha256 exit_code stderr_sha256 fresh_processes inputs_before_sha256 inputs_after_sha256")
    keys(witness,"schema session challenge request_sha256 collector_sources_sha256 vm_inventory_sha256 phases loader_pairs scope")
    need(trust["schema"] == "QUALIFIED_FRAME_TRUST_V1" and receipt["schema"] == "QUALIFIED_FRAME_COLLECTOR_RECEIPT_V1" and witness["schema"] == "QUALIFIED_FRAME_WITNESS_V1", "evidence schema version")
    for row in (trust,receipt,witness):
        need(row["session"] == request["session"] and row["challenge"] == request["challenge"] and row["request_sha256"] == request_hash, "stale/foreign evidence request binding")
        for field in ("collector_sources_sha256","vm_inventory_sha256"):
            pin(row[field]);need(row[field] == trust[field], "collector/VM provenance mismatch")
    need(trust["witness_sha256"] == receipt["witness_sha256"] == witness_hash and trust["collector_receipt_sha256"] == receipt_hash, "witness/receipt byte pin mismatch")
    need(type(receipt["exit_code"]) is int and receipt["exit_code"] == 0 and receipt["stderr_sha256"] == EMPTY and receipt["fresh_processes"] is True, "actual collector execution not clean/fresh")
    pin(receipt["inputs_before_sha256"]);need(receipt["inputs_before_sha256"] == receipt["inputs_after_sha256"], "collector input drift")


def phase_graph(phase, data, request, blockers):
    keys(data,"loaders classes resolutions verification assignability")
    loaders={}
    for row in sequence(data["loaders"],256):
        keys(row,"id parent implementation_sha256 configuration_sha256 policy_sha256")
        identifier(row["id"]);need(row["id"] not in loaders,"duplicate loader identity")
        for field in ("implementation_sha256","configuration_sha256","policy_sha256"):pin(row[field])
        loaders[row["id"]]=row
    need(loaders,"missing loader graph")
    roots=[row["id"] for row in loaders.values() if row["parent"] is None]
    need(len(roots)==1,"exactly one bootstrap loader root required")
    for row in loaders.values():
        seen=set();current=row["id"]
        while current is not None:
            need(current in loaders and current not in seen,"missing/cyclic loader parent")
            seen.add(current);current=loaders[current]["parent"]
    classes={}; identities=set()
    for row in sequence(data["classes"],32768):
        keys(row,"id name kind is_interface loader raw_sha256 artifact_sha256 super interfaces component")
        identifier(row["id"]);identifier(row["name"])
        need(row["id"] not in classes and row["id"] not in loaders,"duplicate/aliased Class identity")
        need(row["loader"] in loaders and row["kind"] in ("class","array","primitive") and type(row["is_interface"]) is bool,"Class kind/loader schema")
        identity=(row["loader"],row["name"])
        need(identity not in identities,"same loader/name has multiple Class identities");identities.add(identity)
        sequence(row["interfaces"],4096);need(len(row["interfaces"])==len(set(row["interfaces"])),"duplicate interface edge")
        if row["kind"] == "class":
            pin(row["raw_sha256"]);pin(row["artifact_sha256"])
            need(row["component"] is None and not row["name"].startswith("["),"ordinary Class shape")
        else:
            need(row["raw_sha256"] is None and row["artifact_sha256"] is None and row["is_interface"] is False,"derived Class facts")
            if row["kind"] == "primitive":
                need(row["name"] in "BCDFIJSZV" and len(row["name"])==1 and row["loader"]==roots[0] and row["super"] is None and not row["interfaces"] and row["component"] is None,"primitive Class shape")
            else:
                need(row["name"].startswith("[") and row["component"] is not None,"array Class shape")
        classes[row["id"]]=row
    need(classes,"empty Class graph")
    for row in classes.values():
        edges=[v for v in [row["super"],row["component"],*row["interfaces"]] if v is not None]
        need(all(v in classes for v in edges),"missing hierarchy/component edge")
        need(all(classes[v]["kind"]=="class" and classes[v]["is_interface"] for v in row["interfaces"]),"interface edge not interface Class")
        if row["kind"] == "class":
            if row["super"] is not None:
                sup=classes[row["super"]]
                need(sup["kind"]=="class" and not sup["is_interface"] and not row["is_interface"],"superclass kind")
            elif not row["is_interface"]:
                need(row["name"]=="java/lang/Object" and row["loader"]==roots[0],"non-Object class missing superclass")
        if row["kind"] == "array":
            component=classes[row["component"]]
            descriptor=component["name"] if component["kind"] in ("array","primitive") else "L"+component["name"]+";"
            need(component["name"]!="V" and row["name"]=="["+descriptor and row["loader"]==component["loader"],"array component/rank/defining loader mismatch")
            need(row["super"] is not None and classes[row["super"]]["name"]=="java/lang/Object" and classes[row["super"]]["loader"]==roots[0],"array superclass")
            need([(classes[v]["name"],classes[v]["loader"]) for v in row["interfaces"]]==[("java/lang/Cloneable",roots[0]),("java/io/Serializable",roots[0])],"array interface identity/order")
    # Iterative cycle detection avoids hostile recursion depths.
    completed=set()
    for start in classes:
        stack=[(start,False)];active=set()
        while stack:
            ident,exit=stack.pop()
            if exit:active.remove(ident);completed.add(ident);continue
            if ident in completed:continue
            need(ident not in active,"cyclic hierarchy/component graph");active.add(ident)
            stack.append((ident,True));row=classes[ident]
            stack.extend((v,False) for v in [row["super"],row["component"],*row["interfaces"]] if v is not None)
    resolutions={}
    for row in sequence(data["resolutions"],16384):
        keys(row,"type class_id error");need(row["type"] not in resolutions,"duplicate type resolution")
        if row["class_id"] is None:
            identifier(row["error"]);blockers.append(dict(kind="UNRESOLVED_FRAME_OR_TARGET_TYPE",phase=phase,type=row["type"],error=row["error"]))
        else:
            need(row["error"] is None and row["class_id"] in classes and classes[row["class_id"]]["name"]==row["type"],"resolution Class identity/name mismatch")
        resolutions[row["type"]]=row["class_id"]
    need(sorted(resolutions)==request["types"],"exact type-resolution inventory")
    verifications={}
    expected={row["name"]:row[phase+"_sha256"] for row in request["classes"]}
    for row in sequence(data["verification"],256):
        keys(row,"name class_id raw_sha256 status trigger verify_local verify_remote initialized")
        name=row["name"];need(name in expected and name not in verifications,"verification target inventory")
        need(row["raw_sha256"]==expected[name],"verified bytes differ from exact relation input")
        need(row["status"] in ("VERIFIED","REJECTED","UNAVAILABLE"),"verification status")
        need(all(type(row[k]) is bool or row[k] is None for k in ("verify_local","verify_remote","initialized")),"verification boolean schema")
        need(row["trigger"] is None or isinstance(row["trigger"],str),"verification trigger schema")
        need(row["status"]!="REJECTED","actual whole-class verification rejected")
        if row["status"]=="UNAVAILABLE":blockers.append(dict(kind="WHOLE_CLASS_VERIFICATION_UNAVAILABLE",phase=phase,name=name))
        else:
            need(row["trigger"]=="PINNED_HOTSPOT_GET_DECLARED_METHODS_V1" and row["verify_local"] is True and row["verify_remote"] is True and row["initialized"] is False,"unsupported trigger/flags/initialization")
            need(row["class_id"] is not None and row["class_id"]==resolutions[name] and classes[row["class_id"]]["raw_sha256"]==expected[name],"foreign verification Class object/loader")
        verifications[name]=row
    need(set(verifications)==set(expected),"missing verification target")
    # Only a complete reachable graph is admitted; unrelated invented nodes may
    # not camouflage a missing dependency or expand the claimed closure.
    reachable=set();stack=[v for v in resolutions.values() if v is not None]
    while stack:
        ident=stack.pop()
        if ident in reachable:continue
        reachable.add(ident);row=classes[ident]
        stack.extend(v for v in [row["super"],row["component"],*row["interfaces"]] if v is not None)
    need(reachable==set(classes),"unrequested/unreachable Class graph nodes")
    used={r["loader"] for r in classes.values()};stack=list(used)
    while stack:
        parent=loaders[stack.pop()]["parent"]
        if parent is not None and parent not in used:used.add(parent);stack.append(parent)
    need(used==set(loaders),"unreachable loader graph nodes")
    return dict(loaders=loaders,classes=classes,resolutions=resolutions,bootstrap=roots[0])


def assignable(graph, source, target):
    classes=graph["classes"]
    # Array rank is finite and class graph cycles were rejected.
    if source==target:return True
    a,b=classes[source],classes[target]
    if a["kind"]=="primitive" or b["kind"]=="primitive":return False
    if b["name"]=="java/lang/Object" and b["loader"]==graph["bootstrap"]:return True
    if a["kind"]==b["kind"]=="array":return assignable(graph,a["component"],b["component"])
    seen=set();stack=[source]
    while stack:
        ident=stack.pop()
        if ident==target:return True
        if ident in seen:continue
        seen.add(ident);row=classes[ident]
        stack.extend(v for v in [row["super"],*row["interfaces"]] if v is not None)
    return False


def graph_correspondence(pre, post, pairs, request):
    mapping={}
    for row in sequence(pairs,256):
        keys(row,"pre post");need(row["pre"] not in mapping,"duplicate loader correspondence");mapping[row["pre"]]=row["post"]
    need(set(mapping)==set(pre["loaders"]) and set(mapping.values())==set(post["loaders"]) and len(set(mapping.values()))==len(mapping),"loader correspondence not total bijection")
    need(not(set(pre["loaders"])&set(post["loaders"])) and not(set(pre["classes"])&set(post["classes"])),"cross-process object identity reused")
    for left,right in mapping.items():
        a,b=pre["loaders"][left],post["loaders"][right]
        need(b["parent"]==(mapping[a["parent"]] if a["parent"] is not None else None),"loader parent correspondence")
        need(all(a[k]==b[k] for k in ("implementation_sha256","configuration_sha256","policy_sha256")),"loader implementation/configuration/policy drift")
    keyed={(r["loader"],r["name"],r["kind"]):ident for ident,r in post["classes"].items()}
    classes={}
    for ident,row in pre["classes"].items():
        key=(mapping[row["loader"]],row["name"],row["kind"])
        need(key in keyed,"unmatched Class across loader graphs");classes[ident]=keyed[key]
    need(set(classes.values())==set(post["classes"]),"extra post hierarchy Class")
    targets={row["name"]:row for row in request["classes"]}
    for left,right in classes.items():
        a,b=pre["classes"][left],post["classes"][right]
        need(a["is_interface"]==b["is_interface"] and a["artifact_sha256"]==b["artifact_sha256"],"class kind/origin artifact drift")
        for edge in ("super","component"):
            need(b[edge]==(classes[a[edge]] if a[edge] is not None else None),"hierarchy/component correspondence drift")
        need(b["interfaces"]==[classes[x] for x in a["interfaces"]],"interface identity/order drift")
        target=targets.get(a["name"])
        if target and pre["resolutions"][a["name"]]==left:
            need(a["raw_sha256"]==target["pre_sha256"] and b["raw_sha256"]==target["post_sha256"],"target hierarchy bytes not relation bytes")
        else:need(a["raw_sha256"]==b["raw_sha256"],"unproved dependency byte drift")
    for name,left in pre["resolutions"].items():
        right=post["resolutions"][name]
        need((left is None and right is None) or (left is not None and right==classes[left]),"type resolution chooses foreign counterpart")
    return classes


def evaluate(request, request_hash, structure, witness=None, witness_hash=None, trust=None, receipt=None, receipt_hash=None):
    report=dict(schema="QUALIFIED_FRAME_RESULT_V1",status="INCOMPLETE",production_authority=False,live_writer_closure=False,
                authority="NONE",blockers=[],external_evidence_trust="CALLER_MUST_ESTABLISH_PINNED_FRESH_COLLECTOR")
    try:
        request_schema(request)
        need(structure["status"]!="FAIL","exact insertion/non-frame preservation failed")
        need(structure["schema"]=="QUALIFIED_FRAME_STRUCTURE_V1","structural proof version")
        need(request["classes"]==[{k:r[k] for k in ("name","pre_sha256","post_sha256")} for r in structure["records"]],"request targets differ from proved exact buffers")
        need(request["types"]==sorted({t for r in structure["records"] for t in r["types"]}),"request omits structural frame types")
        need(request["assignability"]==[dict(source=a,target=b) for a,b in sorted({(q["source"],q["target"]) for r in structure["records"] for q in r["questions"]})],"request omits frame assignability obligations")
        for cls in structure["records"]:
            for method in cls["methods"]:
                report["blockers"].extend(dict(class_name=cls["name"],method=method["method"],**b) for b in method["blockers"])
        if witness is None or trust is None or receipt is None:
            report["blockers"].append(dict(kind="MISSING_EXTERNALLY_PINNED_ACTUAL_WITNESS"));return report
        authenticate(request,request_hash,witness,witness_hash,trust,receipt,receipt_hash)
        keys(witness["phases"],"pre post")
        graphs={p:phase_graph(p,witness["phases"][p],request,report["blockers"]) for p in ("pre","post")}
        need(all(len(request["assignability"])*len(graph["classes"])<=2097152 for graph in graphs.values()),"assignability graph work budget exceeded")
        complete=all(v is not None for graph in graphs.values() for v in graph["resolutions"].values())
        correspondence=graph_correspondence(graphs["pre"],graphs["post"],witness["loader_pairs"],request) if complete else {}
        answers={}
        wanted={(q["source"],q["target"]) for q in request["assignability"]}
        for phase,graph in graphs.items():
            seen=set();values={}
            for row in sequence(witness["phases"][phase]["assignability"],16384):
                keys(row,"source target value");pair=(row["source"],row["target"])
                need(pair in wanted and pair not in seen,"query answer inventory");seen.add(pair)
                source,target=(graph["resolutions"][name] for name in pair)
                if source is None or target is None or row["value"] is None:
                    need(row["value"] is None,"claimed assignability without resolved Class objects")
                    report["blockers"].append(dict(kind="ASSIGNABILITY_UNAVAILABLE",phase=phase,source=pair[0],target=pair[1]))
                else:
                    need(type(row["value"]) is bool and row["value"]==assignable(graph,source,target),"actual assignability contradicts independently checked graph")
                values[pair]=row["value"]
            need(seen==wanted,"missing assignability query answer");answers[phase]=values
        need(answers["pre"]==answers["post"],"assignability differs across corresponding hierarchy graphs")
        for a,b in sorted(wanted):
            if answers["post"][(a,b)] is False and answers["post"].get((b,a)) is False:
                report["blockers"].append(dict(kind="UNSUPPORTED_INCOMPARABLE_REFERENCE_REWRITE",source=a,target=b))
        keys(witness["scope"],"model observers loader_effects")
        need(witness["scope"]["model"]=="OFFLINE_BYTECODE_EXECUTION_V1","unsupported observer model")
        for name,trust_name in (("observers","observer_policy"),("loader_effects","loader_effect_policy")):
            scope=witness["scope"][name];keys(scope,"status policy_sha256 inventory_sha256 evidence_sha256")
            need(scope["status"] in ("CLOSED","INCOMPLETE"),"scope status")
            for field in ("policy_sha256","inventory_sha256","evidence_sha256"):pin(scope[field],nullable=scope["status"]=="INCOMPLETE")
            expected=trust[trust_name]
            if expected is not None:
                keys(expected,"policy_sha256 inventory_sha256 evidence_sha256")
                for field in expected:pin(expected[field])
            if scope["status"]!="CLOSED" or expected is None:
                report["blockers"].append(dict(kind="SCOPE_NOT_INDEPENDENTLY_CLOSED",scope=name))
            else:
                keys(expected,"policy_sha256 inventory_sha256 evidence_sha256")
                for field in expected:pin(scope[field]);need(scope[field]==expected[field],"scope policy/evidence pin mismatch")
        report.update(status="INCOMPLETE" if report["blockers"] else "PASS",verified_targets=len(request["classes"])*2,
                      resolved_type_requests=len(request["types"])*2,corresponding_class_nodes=len(correspondence),
                      directed_assignability_answers=len(wanted)*2,request_sha256=request_hash,witness_sha256=witness_hash,
                      scope="OFFLINE_FINITE_FRAME_RELATION_WITH_EXTERNAL_VERIFICATION_AND_QUALIFIED_OBSERVER_SCOPE")
    except (ValueError,KeyError,IndexError,TypeError,RecursionError) as error:
        report.update(status="FAIL",error=type(error).__name__+": "+str(error))
    return report
