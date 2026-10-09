"""Event-driven wait — replaces `sleep N; grep` polling loops.

Policy alignment (AGENTS.md test execution policy): time is not coverage;
a timeout is a CEILING, not a required sleep. `waitfor` returns the moment
the evidence condition is met, or fails honestly at the deadline with a
summary of what was observed.

Modes (combinable; ALL given conditions must hold):

- --pattern REGEX [--count N]   fire when REGEX has matched at least N
                                lines (default 1). Event-count evidence.
- --settle KEY [--stable-for S] fire when the key=value counter KEY has
                                not changed for S seconds (default 10).
                                Matches the change-driven metrics dumper:
                                if the dumper only prints on change, an
                                unchanged value plus silence is exactly
                                the settled condition.

Other flags:

- --from-start    scan existing file content instead of only lines
                  appended after waitfor starts (default: tail-new).
- --deadline S    ceiling in seconds (default 300). Not a hold duration.
- --interval S    poll interval (default 0.5).

Exit codes: 0 condition met; 1 deadline expired; 2 deadline expired and
the file never appeared.
"""

import os
import re
import sys
import time

_KV_RE = re.compile(r"(\w+)=(-?[0-9.]+)")


class _Watcher:
    def __init__(self, path, from_start):
        self.path = path
        self._fh = None
        self._from_start = from_start
        # tail-new applies only to files that already existed: a file that
        # appears AFTER waitfor started is read from its beginning
        self._existed_at_start = os.path.isfile(path)
        self._buffer = b""
        self.match_count = 0
        self.counters = {}
        self.last_line = ""

    def _ensure_open(self):
        if self._fh is not None:
            return True
        if not os.path.isfile(self.path):
            return False
        self._fh = open(self.path, "rb")
        if self._from_start or not self._existed_at_start:
            self._fh.seek(0)
        else:
            self._fh.seek(0, 2)  # tail-new baseline
        return True

    def poll(self, pattern, want_count):
        """Returns True when the pattern condition is satisfied."""
        if not self._ensure_open():
            return False
        new = self._fh.read()
        if new:
            self._buffer += new
            *lines, self._buffer = self._buffer.split(b"\n")
            rx = re.compile(pattern) if pattern else None
            for raw in lines:
                line = raw.decode("utf-8", errors="replace")
                for k, v in _KV_RE.findall(line):
                    try:
                        val = float(v) if "." in v else int(v)
                    except ValueError:
                        continue
                    self.counters[k] = val
                if rx is not None and rx.search(line):
                    self.match_count += 1
                    self.last_line = line.strip()[:200]
        return self.match_count >= want_count

    def close(self):
        if self._fh is not None:
            self._fh.close()
            self._fh = None


def wait_for(path, pattern=None, count=1, settle_key=None, stable_for=10.0,
             from_start=False, deadline_s=300.0, interval_s=0.5):
    """Blocking wait. Returns (ok, summary_dict)."""
    started = time.monotonic()
    watcher = _Watcher(path, from_start)
    settle_seen = settle_key is None
    settle_value = None
    settle_changed_at = None
    ok = False
    try:
        while True:
            now = time.monotonic()
            if now - started >= deadline_s:
                break
            matched = watcher.poll(pattern, count)
            if settle_key is not None:
                cur = watcher.counters.get(settle_key)
                if cur is not None and cur != settle_value:
                    settle_value = cur
                    settle_changed_at = now
                    settle_seen = True
            pattern_ok = pattern is None or matched
            settle_ok = settle_key is None or (
                settle_seen and settle_changed_at is not None
                and now - settle_changed_at >= stable_for)
            if pattern_ok and settle_ok:
                ok = True
                break
            time.sleep(interval_s)
    finally:
        watcher.close()
    summary = {
        "path": path, "ok": ok,
        "file_appeared": os.path.isfile(path),
        "elapsed_s": round(time.monotonic() - started, 1),
        "pattern": pattern, "match_count": watcher.match_count,
        "count_needed": count if pattern else None,
        "settle_key": settle_key,
        "settle_value": settle_value,
        "last_matching_line": watcher.last_line,
        "counters": watcher.counters,
    }
    return ok, summary


def render_summary(s):
    out = []
    if s["ok"]:
        out.append("WAITFOR-MET   %s after %ss" % (s["path"],
                                                   s["elapsed_s"]))
    else:
        out.append("WAITFOR-TIMEOUT %s after %ss (deadline is a ceiling "
                   "— evidence not reached)" % (s["path"], s["elapsed_s"]))
    if s["pattern"]:
        out.append("  pattern %r matched %d/%s" % (
            s["pattern"], s["match_count"],
            s["count_needed"] if s["count_needed"] else "any"))
    if s["settle_key"]:
        out.append("  settle %s=%s" % (s["settle_key"], s["settle_value"]))
    if s["last_matching_line"]:
        out.append("  last match: %s" % s["last_matching_line"])
    return "\n".join(out)
