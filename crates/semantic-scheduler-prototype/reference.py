"""Independent integer specification for the bounded public-API fixture.

This specifies only four batches of eight closed affine jobs. Python big integers
check multiplication and addition separately against the signed 64-bit domain.
Every admitted job snapshots its read set before this batch's ordered commits.
Numeric sequence fields are output-only ordering facts. The Rust adapter retains
opaque origin-bound TaskId handles for dispatch/cancel; this oracle cannot turn
serialized sequence fields into scheduler control handles.
"""
from __future__ import annotations
import json

SCENARIOS=("ordered","conflict","cancel","deadline","revoke","unload","external","budget","arithmetic")
MIN=-(1<<63)
MAX=(1<<63)-1

def expected(session,challenge,scenario,seed):
    if scenario not in SCENARIOS or not 0<=seed<1<<64:raise ValueError("fixture inputs")
    values=[seed%97+i for i in range(32)];versions=[1]*32;tick=0
    if scenario=="arithmetic":values[0]=MAX
    def row(boundary,sequence,outcome,effects):
        return dict(schema="SEMANTIC_SCHEDULER_BOUNDARY_V1",session=session,challenge=challenge,scenario=scenario,seed=seed,boundary=boundary,sequence=sequence,outcome=outcome,tick=tick,effects=effects,values=list(values),production_authority=False)
    yield row(0,None,"INITIAL",[])
    for batch in range(4):
        inputs=[(i%4 if scenario=="conflict" else i)*4 for i in range(8)]
        snapshots=[[(values[p],versions[p]) for p in range(start,start+4)] for start in inputs]
        if scenario=="deadline":tick+=1000
        if scenario=="external":values[31]=-777-batch;versions[31]+=1
        for job,(start,snapshot) in enumerate(zip(inputs,snapshots)):
            outcome="Committed";effects=[]
            if scenario in ("deadline","revoke","unload"):outcome={"deadline":"Deadline","revoke":"Revoked","unload":"Unloaded"}[scenario]
            elif scenario=="cancel" and job==0:outcome="Cancelled"
            elif scenario=="budget" and job==3:outcome="CpuBudget"
            else:
                computed=[]
                for offset,(value,_) in enumerate(snapshot):
                    multiplied=value*2;after=multiplied+batch+1
                    if not (MIN<=multiplied<=MAX and MIN<=after<=MAX):outcome="Arithmetic";break
                    computed.append([start+offset,value,after])
                if outcome=="Committed" and any((values[start+j],versions[start+j])!=old for j,old in enumerate(snapshot)):outcome="StaleInput"
                if outcome=="Committed":
                    effects=computed
                    for position,_,value in effects:values[position]=value;versions[position]+=1
            yield row(1+8*batch+job,8*batch+job,outcome,effects)

def unique(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError("duplicate JSON key: "+key)
        result[key]=value
    return result

def load(text):
    def nonfinite(_):raise ValueError("nonfinite JSON")
    return json.loads(text,object_pairs_hook=unique,parse_constant=nonfinite)

def compare(text,session,challenge,scenario,seed):
    lines=text.splitlines()
    if len(lines)!=33:raise ValueError("expected exactly 33 boundary rows")
    rows=[load(line) for line in lines]
    for boundary,(actual,wanted) in enumerate(zip(rows,expected(session,challenge,scenario,seed))):
        # Encoding comparison also rejects bool/int and 1/1.0 schema confusion.
        if json.dumps(actual,sort_keys=True,separators=(",",":"))!=json.dumps(wanted,sort_keys=True,separators=(",",":")):
            return dict(boundary=boundary,expected=wanted,actual=actual)
    return None
