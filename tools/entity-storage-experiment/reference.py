"""Independent plain Python entity model and brute/grid spatial checks.

No ECS handles, Rust storage code, or backend-provided expected data are used.
All arithmetic and seed formulas are part of the bounded synthetic specification.
"""
from copy import deepcopy
from itertools import combinations, product

MASK=(1<<64)-1
OFFSET=14695981039346656037
def wrap(value):return (value+(1<<63))%(1<<64)-(1<<63)
def record(slot,seed,generation=1):
    return dict(key=[9,slot,generation],position=[seed*17%8192,seed*43%8192,seed*101%8192],velocity=[seed%3-1,seed//3%3-1,seed//9%3-1],bounds=[1+seed%4]*3,lifecycle=1,behavior=seed%7,capabilities=[seed,seed+1],nbt=[(seed+i)%256 for i in range(32)],java_token=seed+100000,mods=[[7,[seed%256]*8]] if seed%4==0 else [],extension=None)
def tick(rows):
    for row in rows.values():
        if row["lifecycle"]==1:row["position"]=[wrap(p+v) for p,v in zip(row["position"],row["velocity"])]
def intersects(a,b):return all(abs(a["position"][axis]-b["position"][axis])<=a["bounds"][axis]+b["bounds"][axis] for axis in range(3))
def query(rows,minimum,maximum):
    return sorted(r["key"] for r in rows.values() if all(r["position"][a]+r["bounds"][a]>=minimum[a] and r["position"][a]-r["bounds"][a]<=maximum[a] for a in range(3)))
def brute_pairs(rows):return sorted([a["key"],b["key"]] for a,b in combinations(sorted(rows.values(),key=lambda r:r["key"]),2) if intersects(a,b))
def grid_pairs(rows):
    buckets={};pairs=set()
    for r in rows.values():
        ranges=[range((r["position"][a]-r["bounds"][a])//32,(r["position"][a]+r["bounds"][a])//32+1) for a in range(3)]
        for cell in product(*ranges):
            bucket=buckets.setdefault(cell,[])
            for other in bucket:
                if intersects(r,other):pairs.add(tuple(sorted((tuple(r["key"]),tuple(other["key"])))))
            bucket.append(r)
    return sorted(pairs)

class Reference:
    def __init__(self):self.rows={};self.generations={}
    def apply(self,command):
        words=command.split();op=words[0];v=list(map(int,words[1:]));status="OK";events=[];hits=[];pairs=[];probe=None
        if op=="SPAWN":
            slot,seed=v
            if slot>=20000 or seed>1000000:status="LIMIT"
            elif slot in self.rows:status="OCCUPIED"
            else:self.rows[slot]=record(slot,seed,self.generations.get(slot,1))
        elif op in ("DESPAWN","VELOCITY","EXT","LIFE","PROBE"):
            world,slot,generation=v[:3];row=self.rows.get(slot)
            if row is None or row["key"]!=[world,slot,generation]:status="STALE"
            elif op=="DESPAWN":del self.rows[slot];self.generations[slot]=generation+1
            elif op=="VELOCITY":row["velocity"]=v[3:]
            elif op=="EXT":row["extension"]=None if v[3]==-1 else v[3]
            elif op=="LIFE":
                if v[3]>1:status="LIMIT"
                else:row["lifecycle"]=v[3]
            else:probe=deepcopy(row)
        elif op=="TICK":tick(self.rows);events=sorted(r["key"] for r in self.rows.values())
        elif op=="QUERY":hits=query(self.rows,v[:3],v[3:])
        elif op=="PAIRS":pairs=brute_pairs(self.rows)
        elif op!="INITIAL":raise ValueError("unsupported reference command")
        return dict(status=status,rows=deepcopy(sorted(self.rows.values(),key=lambda r:r["key"])),events=events,query=hits,pairs=pairs,probe=probe)

def mix(h,value):
    for b in (value&MASK).to_bytes(8,"little"):h=((h^b)*1099511628211)&MASK
    return h
def hot_checksum(rows):
    h=OFFSET
    for r in rows:
        for value in r["key"]+r["position"]+r["velocity"]+r["bounds"]+[r["lifecycle"],r["behavior"]]:h=mix(h,value)
    return h
def state_checksum(rows):
    h=OFFSET
    for r in rows:
        values=[hot_checksum([r]),len(r["capabilities"]),*r["capabilities"],len(r["nbt"]),*r["nbt"],r["java_token"],len(r["mods"])]
        for identifier,raw in r["mods"]:values.extend([identifier,len(raw),*raw])
        values.append(MASK if r["extension"] is None else r["extension"])
        for value in values:h=mix(h,value)
    return h
def benchmark_expected(n):
    rows={i:record(i,i) for i in range(n)}
    for _ in range(32):tick(rows)
    lookup=sum(rows[i*104729%n]["position"][0] for i in range(4*n))&MASK
    for i in range(0,n,4):rows[i]=record(i,i+100000,2)
    ordered=hot_checksum([rows[i] for i in range(n)])
    hits=sum(len(query(rows,[i*200,0,0],[i*200+512,8192,8192])) for i in range(32))
    pairs=len(grid_pairs(rows));pipeline=0
    for _ in range(8):
        tick(rows);pipeline=hot_checksum([rows[i] for i in range(n)])^len(grid_pairs(rows))
        for i in range(4):pipeline^=len(query(rows,[i*500,0,0],[i*500+512,8192,8192]))
    final=[rows[i] for i in range(n)]
    return dict(lookup_checksum=lookup,ordered_checksum=ordered,spatial_query_hits=hits,broadphase_pairs=pairs,pipeline_checksum=pipeline,final_hot_checksum=hot_checksum(final),final_state_checksum=state_checksum(final))
