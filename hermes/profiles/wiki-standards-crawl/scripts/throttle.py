#!/usr/bin/env python3
"""Shared throttle for hourly-scheduled evidence bots.

The cron jobs fire EVERY HOUR now. The evidence scripts decide, at run time,
whether a full iteration is due. This module is the decision-free half of
that: a cooldown backed by a state file, and a worktree lock so two bots
sharing one tree cannot both `git checkout` into it.

Contract (matches hyakka_evidence.py's refusal philosophy):

  - `gate(name, hours)` prints a THROTTLED banner and exits 0 when the last
    SUCCESS for `name` is younger than `hours`. Exit 0 because Hermes injects
    stdout into the agent prompt: the bot must RUN and be told "not due" —
    exactly the same reason a refusal exits 0 with a REFUSED banner. The
    string "[SILENT]" is on the first line so an agent reading it can stop
    without spending anything.
  - `mark(name)` records a success NOW. Call it only after the real work
    (the evidence collector) exited 0. A throttled or refused run marks
    nothing — a blind run must not reset the clock.
  - `lock(path)` is a context manager holding an fcntl lock on `path` for
    the duration of the iteration. Non-blocking: if another bot holds the
    lock, this run prints THROTTLED and exits 0 rather than queueing behind
    a 21-minute run.

State lives under ~/.hermes/cron-throttle/ (one JSON per job name, timestamp
in ISO-8601 UTC). Timestamps never enter stdout.

Usage at the top of an evidence script:

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from throttle import gate, lock, mark
    gate("hyakka-source-scout", hours=24)          # exits 0 if not due
    with lock("/tmp/hyakka-growth-bot.lock"):      # shared-worktree guard
        ... the real work ...
        mark("hyakka-source-scout")                # only on success
"""
import fcntl
import json
import os
import sys
import time

STATE_DIR = os.environ.get(
    "HERMES_CRON_THROTTLE_DIR",
    os.path.join(os.path.expanduser("~"), ".hermes", "cron-throttle"))


def _state_path(name: str) -> str:
    safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in name)
    return os.path.join(STATE_DIR, safe + ".json")


def _read_last(name: str):
    try:
        with open(_state_path(name)) as f:
            return float(json.load(f).get("last-success", 0))
    except (OSError, ValueError):
        return 0.0


def gate(name: str, hours: float) -> None:
    """Exit 0 with a THROTTLED/[SILENT] banner when the job is not due."""
    last = _read_last(name)
    age_h = (time.time() - last) / 3600.0 if last else None
    if age_h is not None and age_h < hours:
        print("[SILENT]")
        print(f"THROTTLED\t{name}\tlast success {age_h:.1f}h ago "
              f"(cooldown {hours}h); nothing to do this tick.")
        sys.exit(0)


def mark(name: str) -> None:
    """Record a success NOW. Only call after the real work exited 0."""
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(_state_path(name), "w") as f:
        json.dump({"last-success": time.time(), "name": name}, f)


class lock:
    """Hold an exclusive fcntl lock for the iteration, or THROTTLED-exit."""

    def __init__(self, path: str, name: str = "lock"):
        self.path = path
        self.name = name
        self.fd = None

    def __enter__(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.fd = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o644)
        try:
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            print("[SILENT]")
            print(f"THROTTLED\t{self.name}\tanother run holds {self.path}; "
                  "skipping this tick.")
            sys.exit(0)
        return self

    def __exit__(self, *exc):
        try:
            fcntl.flock(self.fd, fcntl.LOCK_UN)
        finally:
            os.close(self.fd)
        return False
