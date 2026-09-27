"""Read-only isolation and production-gate checks for the hardening program."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[2]


def git_lines(root: Path, *args: str) -> list[str]:
    result = subprocess.run(["git", "--no-optional-locks", "-C", str(root), *args],
                            capture_output=True, text=True, check=True)
    return result.stdout.splitlines()


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def closed_body(source: str, signature: str, expected: str) -> bool:
    """Conservative source guard; any changed body needs explicit reconsideration.

    This is deliberately not a Java parser or a substitute for runtime tests.
    It accepts only the existing simple closed bodies after removing comments
    and whitespace. Nested code or additional statements do not pass.
    """
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    match = re.search(re.escape(signature) + r"\s*\{([^{}]*)\}", source)
    return bool(match and re.sub(r"\s+", "", match[1]) ==
                re.sub(r"\s+", "", expected))


def inspect(root: Path = ROOT) -> dict:
    root = root.resolve()
    snapshot = json.loads((root / "machine/architecture-hardening/isolation.json").read_text())
    original = Path(snapshot["original_worktree"]).resolve()
    failures = []
    if root == original or root != Path(snapshot["isolated_worktree"]).resolve():
        raise ValueError("hardening command is outside the isolated checkout")
    actual = {
        "head": git_lines(original, "rev-parse", "HEAD")[0],
        "branch": git_lines(original, "branch", "--show-current")[0],
        "status": git_lines(original, "status", "--porcelain=v1"),
        "files": [],
    }
    for key in ("head", "branch", "status"):
        if actual[key] != snapshot["original_" + key]:
            failures.append("original worktree " + key + " changed")
    for record in snapshot["preserved_files"]:
        path = Path(record["path"])
        digest = sha256(path) if path.is_file() else None
        actual["files"].append({"path": str(path), "sha256": digest})
        if digest != record["sha256"].lower():
            failures.append("preserved file changed: " + str(path))
    payload = root / "tools/bridge/src/com/rustcraft/bridge/M4NativeStatePayload.java"
    contract = root / "tools/bridge/src/com/rustcraft/bridge/capture/CaptureContract.java"
    gates = {
        "tryEncode_fail_closed": closed_body(
            payload.read_text(encoding="utf-8"),
            "public static byte[] tryEncode(Object chunk, int filter)",
            "FALLBACK_CAPTURE_UNSAFE.incrementAndGet(); return null;"),
        "productionAuthorityEligible_false": closed_body(
            contract.read_text(encoding="utf-8"),
            "public boolean productionAuthorityEligible()", "return false;"),
    }
    for name, passed in gates.items():
        if not passed:
            failures.append("production gate guard failed: " + name)
    return {"schema_version": 1, "kind": "HARDENING_ISOLATION_GATE_CHECK",
            "status": "FAIL" if failures else "PASS", "failures": failures,
            "original_observed": actual, "isolated_worktree": str(root),
            "isolated_head": git_lines(root, "rev-parse", "HEAD")[0],
            "isolated_branch": git_lines(root, "branch", "--show-current")[0],
            "production_gate_checks": gates,
            "scope": "read-only original HEAD/status/two paused source hashes plus conservative source guards; runtime authority tests remain required"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = inspect()
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        output = args.output.resolve()
        if not output.is_relative_to((ROOT / "target").resolve()):
            parser.error("guard output must be below this checkout's target/")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    print(text, end="")
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
