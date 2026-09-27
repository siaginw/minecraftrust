"""Pin consulted primary source bytes, with raw copies only in isolated evidence."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
REV="1358e1b273e2"
FILES=["LICENSE","ASSEMBLY_EXCEPTION","hotspot/src/share/vm/prims/jvm.cpp",
       "hotspot/src/share/vm/oops/instanceKlass.cpp","hotspot/src/share/vm/classfile/verifier.cpp",
       "hotspot/src/share/vm/classfile/verificationType.cpp",
       "hotspot/src/share/vm/runtime/arguments.cpp","hotspot/src/share/vm/runtime/globals.hpp"]


def read(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"RustCraft-offline-source-review"}),timeout=20) as response:
        raw=response.read(4*1024*1024+1)
        if len(raw)>4*1024*1024:raise ValueError("primary-source byte bound")
        return raw


def main():
    out=ROOT/"target/hotspot-verification-proof"/("primary-"+uuid.uuid4().hex)
    out.mkdir(parents=True,exist_ok=False)
    api="https://api.github.com/repos/adoptium/jdk8u/commits/"+REV
    commit_raw=read(api);commit=json.loads(commit_raw)["sha"]
    (out/"commit.json").write_bytes(commit_raw)
    def fetch(path):
        url="https://raw.githubusercontent.com/adoptium/jdk8u/"+commit+"/"+path
        raw=read(url);dest=out/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(raw)
        return dict(path=path,url=url,sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),evidence_file=str(dest))
    with ThreadPoolExecutor(max_workers=4) as pool:rows=list(pool.map(fetch,FILES))
    release=Path("D:/rustcraft-toolchains/temurin8/jdk8u504-b01/release")
    record=dict(schema="HOTSPOT_PRIMARY_SOURCE_REVIEW_V1",retrieved_utc=datetime.now(timezone.utc).isoformat(),
                repository="https://github.com/adoptium/jdk8u",commit=commit,
                commit_api_sha256=hashlib.sha256(commit_raw).hexdigest(),source_lead=REV+"+",
                local_release_sha256=hashlib.sha256(release.read_bytes()).hexdigest(),
                source_identity_limit="Local release SOURCE ends '+': cited vendor source lead is not a reproducible build guarantee. Native binary hashes and actual controls are separately required.",
                license="Consulted OpenJDK HotSpot source is GPL-2.0-only with applicable OpenJDK Assembly Exception; no HotSpot code is compiled into or copied into the original probe implementation.",
                sources=rows,
                specification_urls=["https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-4.html#jvms-4.7.4",
                                    "https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.1",
                                    "https://docs.oracle.com/javase/8/docs/platform/jvmti/jvmti.html#GetClassStatus"])
    path=Path(__file__).parent/"primary-source-provenance.json"
    path.write_text(json.dumps(record,indent=2)+"\n")
    print(json.dumps(dict(commit=commit,provenance=str(path),evidence=str(out))))


if __name__=="__main__":main()
