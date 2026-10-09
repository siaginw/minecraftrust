"""One digest rule for committed text artifacts, shared by every writer.

Why this exists
---------------
`core.autocrlf=true` checks an LF blob out as CRLF. A tool that hashes the
working-tree file therefore reports a different SHA-256 on Windows than on
Linux for the SAME commit, and a value pinned into a generated artifact becomes
unreproducible on any other checkout. That is not hypothetical: the committed
LiveWriterPlan recorded 738f672d..., which is the CRLF rendering of a manifest
revision that no committed blob hashes to. The drift looked like a content
mismatch and was really a platform dependency.

The rule
--------
Hash the bytes with line endings normalized to LF. That is exactly the form git
stores in the object database, so the digest equals the identity of the
committed blob and is identical on every platform.

What this must NOT be used for
------------------------------
Captured or raw evidence: replay receipts, network payloads, and anything
captured as bytes rather than authored as text. Normalizing those would hide a
real difference. Those files carry `-text` in .gitattributes so their bytes
survive checkout unchanged, and are hashed raw on purpose.
"""
from __future__ import annotations

import hashlib
from pathlib import Path


def normalized_bytes(data: bytes) -> bytes:
    """The LF rendering of `data`, which is what git holds in the object store."""
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def normalized_sha256(path: Path | str) -> str:
    """SHA-256 over the LF-normalized bytes of `path`."""
    return hashlib.sha256(normalized_bytes(Path(path).read_bytes())).hexdigest()
