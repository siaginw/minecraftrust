"""Retain exact primary source/license bytes for the fixture initialization-state probe."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
COMMIT="1358e1b273e29392787e6feb32732304befaf1da"


def main():
    output=ROOT/"target/qualified-frame-relation"/("primary-"+uuid.uuid4().hex)
    output.mkdir(parents=True,exist_ok=False)
    def fetch(path):
        url="https://raw.githubusercontent.com/adoptium/jdk8u/"+COMMIT+"/"+path
        with urllib.request.urlopen(urllib.request.Request(url,headers={"User-Agent":"RustCraft-bounded-source-review"}),timeout=20) as response:raw=response.read(4*1024*1024+1)
        if len(raw)>4*1024*1024:raise ValueError("primary source byte bound")
        destination=output/path;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(raw)
        return dict(path=path,url=url,sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),evidence_file=str(destination))
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows=list(pool.map(fetch,("LICENSE","ASSEMBLY_EXCEPTION","hotspot/src/share/vm/prims/unsafe.cpp","jdk/src/share/classes/sun/misc/Unsafe.java",
                                  "hotspot/src/share/vm/oops/instanceKlass.hpp","hotspot/src/share/vm/oops/klass.hpp")))
    record=dict(schema="FRAME_RELATION_PRIMARY_SOURCE_V1",retrieved_utc=datetime.now(timezone.utc).isoformat(),commit=COMMIT,
                license="Consulted GPL-2.0-only OpenJDK source with applicable Assembly Exception; retained license files; no implementation code copied into or compiled into the original probe.",
                source_identity_limit="Vendor SOURCE contains '+': source lead is not a reproducible build identity; actual native hashes and controls are independently bound.",sources=rows)
    path=Path(__file__).parent/"primary-source-provenance.json";path.write_text(json.dumps(record,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(provenance=str(path),evidence=str(output))))


if __name__=="__main__":main()
