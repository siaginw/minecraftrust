"""Independent Python generated-record and full-index oracle (not MCA/NBT)."""
import hashlib
import json
import struct

MAX_RECORDS=131072
PHASES=("snapshot_copy_sync","source_hash","map_create","scan","index_sort_hash","sidecar_open_commit","sidecar_verify","sidecar_queries","sidecar_reopen_verify","complete")
def generate(count,epoch=7,seed=23):
    if count<1 or count>MAX_RECORDS or count&(count-1):raise ValueError("count")
    header=struct.pack("<8sIIQQQ24s",b"RCSTOR01",1,32,count,epoch,seed,b"\0"*24)
    rows=[]
    for position in range(count):
        key=(position*12345+17)%count
        value=(key*1103515245+seed)%2000001-1000000
        rows.append(struct.pack("<QqIIQ",key,value,key%256,(key>>4)&15,1+key%7))
    return header+b"".join(rows)

def expected(data):
    if len(data)<64:raise ValueError("header")
    magic,schema,size,count,epoch,seed,reserved=struct.unpack("<8sIIQQQ24s",data[:64])
    if (magic,schema,size,reserved)!=(b"RCSTOR01",1,32,b"\0"*24) or count<1 or count>MAX_RECORDS or not epoch or len(data)!=64+32*count:raise ValueError("format")
    index={}
    for position,raw in enumerate(struct.iter_unpack("<QqIIQ",data[64:])):
        key,value,group,flags,version=raw
        if key in index or key>=count or group>255 or flags>15 or not version:raise ValueError("record")
        index[key]=struct.pack("<QQqIIQ",key,64+position*32,value,group,flags,version)
    canonical=b"".join(index[key] for key in sorted(index))
    queries=b"".join(index[(i*7919+17)%count] for i in range(256))
    return dict(epoch=epoch,records=count,source_bytes=len(data),source_sha256=hashlib.sha256(data).hexdigest(),index_sha256=hashlib.sha256(canonical).hexdigest(),query_sha256=hashlib.sha256(queries).hexdigest())

def unique(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError("duplicate key")
        result[key]=value
    return result
def load(text):
    def invalid(_):raise ValueError("nonfinite")
    return json.loads(text,object_pairs_hook=unique,parse_constant=invalid)
def validate(text,reference,mode,session,challenge):
    if len(text.splitlines())!=1:raise ValueError("exactly one result row required")
    row=load(text)
    fixed=dict(schema="OFFLINE_STORAGE_SAMPLE_V1",session=session,challenge=challenge,mode=mode,production_authority=False,**reference)
    if set(row)!=set(fixed)|{"sidecar_bytes","timings_ns"}:raise ValueError("result fields")
    for key,value in fixed.items():
        if type(row[key]) is not type(value) or row[key]!=value:raise ValueError("result mismatch: "+key)
    if type(row["sidecar_bytes"]) is not int or not 0<row["sidecar_bytes"]<=64<<20:raise ValueError("database bytes")
    times=row["timings_ns"]
    if set(times)!=set(PHASES) or any(type(v) is not int or v<0 for v in times.values()) or times["complete"]<sum(times[p] for p in PHASES if p!="complete"):raise ValueError("timing shape")
    return row
