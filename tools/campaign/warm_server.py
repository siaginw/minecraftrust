"""Warm-server management: boot Gate A once, reuse across campaigns.

ensure_rcon: connect to an already-running server's RCON (health check).
skip_world_copy: read-only campaigns reuse a prepared server dir (reads
never mutate the world; integrity is verified by the §29 before/after hash).

The RCON password/port come from the prepared server's server.properties
(enable-rcon=1, rcon.port, rcon.password are set by prepare).
"""
from __future__ import annotations

import socket
from pathlib import Path

from .rcon import RconClient


def rcon_props(server_dir: Path) -> tuple[str, int, str]:
    props = {}
    pf = Path(server_dir) / "server.properties"
    for line in pf.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return (props.get("rcon.host_override", "127.0.0.1"),
            int(props.get("rcon.port", props.get("port", "25575"))),
            props.get("rcon.password", "rustcraft"))


def ensure_rcon(server_dir: Path, timeout_s: float = 3.0) -> RconClient | None:
    """Connect to the warm server's RCON. Returns None if not running —
    caller then boots fresh. One health command verifies liveness."""
    host, port, password = rcon_props(server_dir)
    try:
        client = RconClient(host, port, password, timeout_s=timeout_s)
        client.command("list")  # health check
        return client
    except (OSError, PermissionError, ConnectionError):
        return None


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=1.0):
            return True
    except OSError:
        return False
