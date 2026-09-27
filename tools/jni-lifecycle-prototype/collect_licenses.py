"""One-time provenance collection from locked cached crates and exact upstream refs.

Does not install dependencies. Run.py verifies the retained inventory offline.
"""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import tarfile
import urllib.request

HERE=Path(__file__).resolve().parent
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def source_inventory(source,archive,key):
    expected={}
    with tarfile.open(archive,"r:gz") as packed:
        for member in packed.getmembers():
            if not member.isfile():continue
            relative=Path(member.name).relative_to(key)
            if ".." in relative.parts:raise RuntimeError("archive traversal")
            expected[relative.as_posix()]=hashlib.sha256(packed.extractfile(member).read()).hexdigest()
    actual={p.relative_to(source).as_posix():sha(p) for p in source.rglob("*") if p.is_file() and p.name not in (".cargo-ok",".cargo-checksum.json")}
    if expected!=actual:raise RuntimeError("cached source differs from locked archive: "+key)
    return hashlib.sha256(json.dumps(actual,sort_keys=True,separators=(",",":")).encode()).hexdigest(),len(actual)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--cache",type=Path,required=True);p.add_argument("--crate-cache",type=Path,required=True);a=p.parse_args()
    rows=[]
    for package in tomllib.loads((HERE/"Cargo.lock").read_text())["package"]:
        if "source" not in package:continue
        key=package["name"]+"-"+package["version"];source=a.cache/key
        manifest=tomllib.loads((source/"Cargo.toml").read_text())["package"]
        vcs=json.loads((source/".cargo_vcs_info.json").read_text());commit=vcs["git"]["sha1"]
        archive=a.crate_cache/(key+".crate")
        if sha(archive)!=package["checksum"]:raise RuntimeError("crate archive checksum differs: "+key)
        inventory_hash,source_count=source_inventory(source,archive,key)
        folder=HERE/"third-party"/key;folder.mkdir(parents=True,exist_ok=True)
        licenses=[]
        files=[x for x in source.rglob("*") if x.is_file() and any(word in x.name.lower() for word in ("license","licence","notice","copying","copyright"))]
        for file in files:
            relative=file.relative_to(source);target=folder/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(file.read_bytes())
            licenses.append(dict(path=target.relative_to(HERE).as_posix(),sha256=sha(target),source_kind="locked_crate",source_path=relative.as_posix(),source_archive_sha256=package["checksum"]))
        if not files:
            repository=manifest["repository"].removesuffix(".git")
            if not repository.startswith("https://github.com/"):raise RuntimeError("unsupported provenance host")
            for name in ("LICENSE-MIT","LICENSE-APACHE"):
                url="https://raw.githubusercontent.com/"+repository.removeprefix("https://github.com/")+"/"+commit+"/"+name
                with urllib.request.urlopen(url,timeout=30) as response:data=response.read(1<<20)
                if not data or len(data)>=1<<20:raise RuntimeError("missing/oversize license")
                target=folder/name;target.write_bytes(data)
                licenses.append(dict(path=target.relative_to(HERE).as_posix(),sha256=sha(target),source_kind="upstream_exact_commit",source_url=url,vcs_commit=commit))
        rows.append(dict(name=package["name"],version=package["version"],registry=package["source"],archive_sha256=package["checksum"],license_expression=manifest.get("license"),repository=manifest.get("repository"),vcs=vcs,cargo_manifest_sha256=sha(source/"Cargo.toml"),source_inventory_sha256=inventory_hash,source_file_count=source_count,licenses=licenses))
    output=dict(schema="LOCKED_DEPENDENCY_LICENSE_INVENTORY_V1",cargo_lock_sha256=sha(HERE/"Cargo.lock"),registry_package_count=len(rows),scope="isolated_prototype_including_build_and_target_specific_dependencies",packages=rows)
    (HERE/"third-party"/"inventory.json").write_text(json.dumps(output,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(dict(packages=len(rows),license_files=sum(len(r["licenses"]) for r in rows),inventory_sha256=sha(HERE/"third-party"/"inventory.json"))))

if __name__=="__main__":main()
