"""Receipt checks and a deliberately fixture-only frame relation demonstration."""
from collections import Counter
import json


def require(test, reason):
    if not test: raise ValueError(reason)


def validate_row(row, session, challenge, mode, expected_status, pin, expected_frame_types=()):
    require(isinstance(row, dict), "proof root type")
    required={"schema","session","challenge","mode","production_authority","target_initialized_before","target_initialized_after","runtime_version","vm_version","vm_vendor","input_arguments","verify_local","verify_remote","security_manager","defined","trigger_started","trigger_returned","status"}
    optional={"actual_sha256","expected_sha256","defining_loader_is_exact","target_initialized_after_define","same_binary_name","same_class_object","declared_method_count","method_names","error_class","error_message","loader_requests","verification_loader_requests","frame_type_bindings","frame_type_binding_complete"}
    require(required<=row.keys() and row.keys()<=required|optional,"proof schema keys")
    require(row["schema"]=="HOTSPOT_VERIFICATION_PROBE_V1" and row["session"]==session and row["challenge"]==challenge and row["mode"]==mode,"proof binding")
    require(row["runtime_version"]=="1.8.0_504-b01" and row["vm_version"]=="25.504-b01" and row["vm_vendor"]=="Temurin","pinned runtime facts")
    for key in ("production_authority","target_initialized_before","target_initialized_after","security_manager"):
        require(row[key] is False,"unexpected authority/initialization/security manager")
    require(row["status"]==expected_status,"proof outcome")
    for key in ("defined","trigger_started","trigger_returned","verify_local","verify_remote"):
        require(type(row[key]) is bool,"proof boolean type")
    if expected_status=="REFUSED_VERIFY_FLAGS":
        require(not row["defined"] and not row["trigger_started"] and not row["trigger_returned"],"disabled verification proceeded")
        require(not row["verify_local"] and not row["verify_remote"] and row["input_arguments"]==["-Xverify:none"],"disabled control flags")
        return
    require(row["verify_local"] and row["verify_remote"] and row["input_arguments"]==["-Xverify:all"],"effective verification not enabled")
    require(row["expected_sha256"]==pin,"expected hash substituted")
    if expected_status=="REFUSED_HASH":
        require(row["actual_sha256"]!=pin and not row["defined"] and not row["trigger_started"],"byte mismatch reached definition")
        return
    require(row["actual_sha256"]==pin and row["defined"] and row["defining_loader_is_exact"] is True,"actual class definition not bound")
    require(isinstance(row["loader_requests"],list) and row["loader_requests"][0]=="proof.Target","loader observations missing")
    if expected_status=="REFUSED_LOADER":
        require(row["same_binary_name"] is True and row["same_class_object"] is False and not row["trigger_started"],"foreign loader control")
        return
    require(row["trigger_started"] and row["target_initialized_after_define"] is False,"missing independent link trigger")
    if expected_status=="REJECTED_VERIFY_ERROR":
        require(not row["trigger_returned"] and row["error_class"]=="java.lang.VerifyError" and "proof/Target.dormant" in row["error_message"],"wrong verifier rejection")
    else:
        require(expected_status=="VERIFIED_BY_PINNED_LINK_TRIGGER" and row["trigger_returned"] and row["declared_method_count"]==1 and row["method_names"]==["dormant:10"],"incomplete reflection link observation")
        bindings=row["frame_type_bindings"]
        require(isinstance(bindings,list) and all(isinstance(b,list) and len(b)==3 for b in bindings),"frame binding schema")
        require([b[0] for b in bindings]==list(expected_frame_types),"frame type inventory mismatch")
        require(all((b[1]=="UNRESOLVED" and b[2]=="java.lang.ClassNotFoundException") or (b[1]=="BOUND_BOOTSTRAP" and b[0].startswith("java/") and b[2] is None) or (b==["proof/Target","BOUND_EXACT_TARGET",None]) for b in bindings),"frame binding scope")
        require(row["frame_type_binding_complete"] is all(b[1]!="UNRESOLVED" for b in bindings),"frame binding result mismatch")
        requests=row["verification_loader_requests"]
        require(isinstance(requests,list) and requests and row["loader_requests"][:len(requests)]==requests,"verification requests omitted")


def frame_types(dump):
    return sorted({v[1] for m in dump[16] for f in m[14] for v in f[2]+f[3] if v[0]=="object"})


def proposal_pair(before, after, known_types):
    """Checks only the bounded fixture relation; it cannot qualify arbitrary mods.

    No permitted difference is discarded from the V2 identity or receipt.
    Runtime verification is a separate required observation in the caller.
    """
    a,b=before["dump"],after["dump"]
    types=frame_types(b)
    missing=sorted(set(types)-set(known_types))
    require(not missing,"unqualified frame object types: "+str(missing))
    require(a[:16]==b[:16] and a[18]==b[18] and before["receipt"][3]==after["receipt"][3],"observable class metadata differs")
    require(len(a[16])==len(b[16]),"method inventory differs")
    changes=[]
    for x,y in zip(a[16],b[16]):
        require(all(x[i]==y[i] for i in range(20) if i not in (10,14)),"code/CFG/handlers/observable method metadata differs")
        require(type(y[10]) is int and y[10]>=0,"invalid declared stack maximum")
        require(len(x[14])==len(y[14]),"frame position inventory differs")
        for f,g in zip(x[14],y[14]):
            require(f[:2]==g[:2] and f[3]==g[3],"frame position/type/stack differs")
            require(len(f[2])==len(g[2]),"fixture local shape differs")
            for old,new in zip(f[2],g[2]):
                if old==new:continue
                permitted=(old==["verification_tag",0] and (new[0]=="object" or new==["verification_tag",1])) or (old==["object","java/lang/Object"] and new==["object","java/lang/String"])
                require(permitted,"frame delta outside explicitly demonstrated relation")
        if x[10]!=y[10] or x[14]!=y[14]:changes.append(dict(method=x[:2],before_max_stack=x[10],after_max_stack=y[10],before_frames=x[14],after_frames=y[14]))
    old,new=Counter(a[17]),Counter(b[17]);added=new-old;removed=old-new
    require(not removed,"original symbolic pool dropped")
    allowed={json.dumps(v,separators=(",",":")) for name in types for v in (["class",name],["utf8",name])}
    require(all(v in allowed and n==1 and old[v]==0 for v,n in added.items()),"pool delta not attributable to verification types")
    return dict(status="FIXTURE_RELATION_ONLY",changes=changes,frame_types=types,pool_added=list(added.elements()),
                production_authority=False,general_frame_relation_implemented=False)
