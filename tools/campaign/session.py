"""MinecraftServerSession (goal §8): one normal implementation owning the
server JVM lifecycle — launch, Done detection, commands, graceful stop with
forced-kill fallback, context-manager cleanup so exceptions never leave
Java processes running."""
from __future__ import annotations

import subprocess
import time
from pathlib import Path

from .waits import wait_for_log


class MinecraftServerSession:
    """Owns one server JVM. Use as a context manager."""

    def __init__(self, argv: list[str], working_dir: Path, log_path: Path,
                 boot_timeout_s: float = 900, stop_timeout_s: float = 300,
                 done_pattern: str = r"Done \([0-9.]+s\)"):
        self.argv = list(argv)
        self.working_dir = Path(working_dir)
        self.log_path = Path(log_path)
        self.boot_timeout_s = boot_timeout_s
        self.stop_timeout_s = stop_timeout_s
        self.done_pattern = done_pattern
        self.process: subprocess.Popen | None = None
        self._log_handle = None
        self.boot_time_s: float | None = None

    # -- lifecycle ------------------------------------------------------

    def start(self) -> bool:
        self._log_handle = self.log_path.open("wb")
        self.process = subprocess.Popen(
            self.argv, cwd=str(self.working_dir), stdout=self._log_handle,
            stderr=subprocess.STDOUT, stdin=subprocess.PIPE)
        t0 = time.monotonic()
        if wait_for_log(self.log_path, self.done_pattern, self.boot_timeout_s,
                        process=self.process):
            self.boot_time_s = time.monotonic() - t0
            return True
        return False

    def send_command(self, command: str) -> None:
        if self.process and self.process.stdin:
            try:
                self.process.stdin.write((command + "\n").encode())
                self.process.stdin.flush()
            except (OSError, ValueError):
                pass

    def save_all_flush(self) -> None:
        self.send_command("save-all flush")

    def stop(self, timeout_s: float | None = None) -> bool:
        """Graceful 'stop' then forced-kill fallback. Idempotent."""
        timeout_s = timeout_s if timeout_s is not None else self.stop_timeout_s
        if self.process is None or self.process.poll() is not None:
            self._close_log()
            return True
        self.send_command("stop")
        try:
            self.process.wait(timeout=timeout_s)
            exited = True
        except subprocess.TimeoutExpired:
            self.process.kill()
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                pass
            exited = False
        self._close_log()
        return exited

    def kill(self) -> None:
        if self.process and self.process.poll() is None:
            self.process.kill()
        self._close_log()

    def _close_log(self):
        if self._log_handle:
            try:
                self._log_handle.close()
            except Exception:
                pass
            self._log_handle = None

    # -- context manager -------------------------------------------------

    def __enter__(self) -> "MinecraftServerSession":
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        try:
            self.stop()
        except Exception:
            self.kill()
        return False  # do not swallow exceptions

    # -- introspection ----------------------------------------------------

    @property
    def log_text(self) -> str:
        try:
            return self.log_path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""

    @property
    def returncode(self) -> int | None:
        return self.process.poll() if self.process else None
