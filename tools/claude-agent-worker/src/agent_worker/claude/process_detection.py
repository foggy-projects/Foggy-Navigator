"""Strategy-pattern process detection for Claude CLI processes.

Provides platform-specific detectors (Windows / Unix) behind a common
``ProcessDetector`` protocol.  Use the module-level ``get_detector()``
factory to obtain the singleton for the current platform.
"""

from __future__ import annotations

import logging
import os
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Protocol

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------

@dataclass
class ProcessInfo:
    """OS-level raw process information (no business fields like *is_orphan*)."""

    pid: int
    command: str = ""
    memory_mb: float = 0.0
    started_at: str = ""


@dataclass
class _DetectionPattern:
    """A single detection pattern used by a detector."""

    description: str  # for logging
    query: str  # PowerShell where-clause or pgrep regex


# ---------------------------------------------------------------------------
# Protocol
# ---------------------------------------------------------------------------

class ProcessDetector(Protocol):
    """Platform-specific strategy for detecting Claude CLI processes."""

    def find_pids(self) -> set[int]:
        """Return PIDs of Claude CLI processes spawned by the SDK."""
        ...

    def get_details(self, pids: set[int]) -> list[ProcessInfo]:
        """Return detailed info for the given PIDs."""
        ...


# ---------------------------------------------------------------------------
# Windows detector
# ---------------------------------------------------------------------------

class WindowsDetector:
    """Detect Claude CLI processes on Windows via PowerShell Get-CimInstance."""

    patterns: list[_DetectionPattern] = field(default_factory=list)

    def __init__(self) -> None:
        self.patterns = [
            _DetectionPattern(
                description="node.exe running claude cli.js with stream-json",
                query=(
                    "($_.Name -eq 'node.exe') -and "
                    "($_.CommandLine -like '*claude*cli.js*--output-format*stream-json*')"
                ),
            ),
            _DetectionPattern(
                description="claude.exe native binary with stream-json",
                query=(
                    "($_.Name -eq 'claude.exe') -and "
                    "($_.CommandLine -like '*--output-format*stream-json*')"
                ),
            ),
        ]

    def find_pids(self) -> set[int]:
        pids: set[int] = set()
        for pat in self.patterns:
            self._powershell_query(pat.query, pids)
        return pids

    def get_details(self, pids: set[int]) -> list[ProcessInfo]:
        if not pids:
            return []

        results: list[ProcessInfo] = []
        pid_filter = " -or ".join(f"$_.ProcessId -eq {pid}" for pid in pids)
        ps_cmd = (
            f"Get-CimInstance Win32_Process | Where-Object {{ {pid_filter} }} "
            "| ForEach-Object { "
            "\"$($_.ProcessId)\t$($_.Name)\t$($_.WorkingSetSize)\t$($_.CreationDate)\t$($_.CommandLine)\" }"
        )
        try:
            out = subprocess.check_output(
                ["powershell.exe", "-NoProfile", "-Command", ps_cmd],
                text=True, timeout=10, stderr=subprocess.DEVNULL,
            )
            for line in out.strip().splitlines():
                line = line.strip()
                if not line:
                    continue
                parts = line.split("\t", 4)
                if len(parts) < 5:
                    continue
                try:
                    pid = int(parts[0])
                except (ValueError, IndexError):
                    continue
                cmd = parts[4] if len(parts) > 4 else ""
                try:
                    mem_bytes = int(parts[2])
                except (ValueError, IndexError):
                    mem_bytes = 0

                started = ""
                if parts[3]:
                    try:
                        dt = datetime.strptime(parts[3].strip(), "%m/%d/%Y %H:%M:%S")
                        started = dt.isoformat()
                    except (ValueError, IndexError):
                        started = parts[3].strip()

                results.append(ProcessInfo(
                    pid=pid,
                    command=cmd[:200] if cmd else "",
                    memory_mb=round(mem_bytes / (1024 * 1024), 1),
                    started_at=started,
                ))
        except (subprocess.SubprocessError, FileNotFoundError, OSError) as exc:
            logger.warning("Failed to get process details via PowerShell: %s", exc)
            for pid in pids:
                results.append(ProcessInfo(pid=pid))

        return results

    # -- internal helpers ---------------------------------------------------

    @staticmethod
    def _powershell_query(where_clause: str, out_pids: set[int]) -> None:
        ps_cmd = (
            f"Get-CimInstance Win32_Process | Where-Object {{ {where_clause} }} "
            f"| ForEach-Object {{ $_.ProcessId }}"
        )
        try:
            out = subprocess.check_output(
                ["powershell.exe", "-NoProfile", "-Command", ps_cmd],
                text=True, timeout=10, stderr=subprocess.DEVNULL,
            )
            for line in out.strip().splitlines():
                line = line.strip()
                if line.isdigit():
                    out_pids.add(int(line))
        except (subprocess.SubprocessError, FileNotFoundError, OSError) as exc:
            logger.debug("PowerShell process query failed: %s", exc)


# ---------------------------------------------------------------------------
# Unix detector
# ---------------------------------------------------------------------------

class UnixDetector:
    """Detect Claude CLI processes on Linux/macOS via pgrep + ps."""

    patterns: list[_DetectionPattern] = field(default_factory=list)

    def __init__(self) -> None:
        self.patterns = [
            _DetectionPattern(
                description="claude-agent-sdk (stream-json)",
                query=r"claude.*--output-format.*stream-json",
            ),
            _DetectionPattern(
                description="claude-code-sdk legacy (--print)",
                query=r"claude-code.*cli\.js.*--print",
            ),
        ]

    def find_pids(self) -> set[int]:
        pids: set[int] = set()
        for pat in self.patterns:
            self._pgrep(pat.query, pids)
        return pids

    def get_details(self, pids: set[int]) -> list[ProcessInfo]:
        if not pids:
            return []

        results: list[ProcessInfo] = []
        pid_args = ",".join(str(p) for p in pids)
        try:
            out = subprocess.check_output(
                ["ps", "-o", "pid=,rss=,lstart=,args=", "-p", pid_args],
                text=True, timeout=10, stderr=subprocess.DEVNULL,
            )
            for line in out.strip().splitlines():
                line = line.strip()
                if not line:
                    continue
                tokens = line.split(None, 2)
                if len(tokens) < 3:
                    continue
                try:
                    pid = int(tokens[0])
                except ValueError:
                    continue
                try:
                    rss_kb = int(tokens[1])
                except ValueError:
                    rss_kb = 0
                rest = tokens[2]
                rest_tokens = rest.split(None, 5)
                if len(rest_tokens) >= 6:
                    lstart_str = " ".join(rest_tokens[:5])
                    cmd = rest_tokens[5]
                else:
                    lstart_str = ""
                    cmd = rest

                started = ""
                if lstart_str:
                    try:
                        dt = datetime.strptime(lstart_str, "%a %b %d %H:%M:%S %Y")
                        started = dt.isoformat()
                    except ValueError:
                        started = lstart_str

                results.append(ProcessInfo(
                    pid=pid,
                    command=cmd[:200] if cmd else "",
                    memory_mb=round(rss_kb / 1024, 1),
                    started_at=started,
                ))
        except (subprocess.SubprocessError, FileNotFoundError, OSError) as exc:
            logger.warning("Failed to get process details via ps: %s", exc)
            for pid in pids:
                results.append(ProcessInfo(pid=pid))

        return results

    # -- internal helpers ---------------------------------------------------

    @staticmethod
    def _pgrep(pattern: str, out_pids: set[int]) -> None:
        try:
            out = subprocess.check_output(
                ["pgrep", "-f", pattern],
                text=True, timeout=5, stderr=subprocess.DEVNULL,
            )
            for line in out.strip().splitlines():
                line = line.strip()
                if line.isdigit():
                    out_pids.add(int(line))
        except (subprocess.SubprocessError, FileNotFoundError, OSError):
            pass


# ---------------------------------------------------------------------------
# macOS detector — psutil-based (pgrep -f fails after Node sets process.title)
# ---------------------------------------------------------------------------

class MacOSDetector:
    """Detect Claude CLI processes on macOS via process-tree traversal.

    On macOS, Node.js sets ``process.title = 'claude'`` within ~0.3-0.5 s of
    startup, which overwrites **both** the process args and the environment
    data in ``KERN_PROCARGS2``.  This breaks ``pgrep -f``, ``ps -o args=``,
    and ``psutil.Process.environ()``.

    The reliable detection strategy is **process-tree traversal**: the Worker
    process (this Python process) is the parent of all SDK-spawned CLI
    subprocesses.  We find children named ``node`` or ``claude`` via
    ``psutil.Process.children(recursive=True)``.
    """

    _TARGET_NAMES: frozenset[str] = frozenset({"node", "claude"})

    def find_pids(self) -> set[int]:
        try:
            import psutil
        except ImportError:
            logger.warning("psutil not available on macOS, falling back to pgrep")
            pids: set[int] = set()
            UnixDetector._pgrep(r"claude.*--output-format.*stream-json", pids)
            return pids

        found: set[int] = set()
        try:
            me = psutil.Process(os.getpid())
            children = me.children(recursive=True)
            for child in children:
                try:
                    name = child.name()
                    if name in self._TARGET_NAMES:
                        found.add(child.pid)
                        logger.info("macOS: found child CLI process pid=%d name=%r", child.pid, name)
                except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                    pass
            logger.info("macOS: %d total children, %d CLI pids", len(children), len(found))
        except (psutil.NoSuchProcess, psutil.AccessDenied) as exc:
            logger.warning("macOS: failed to enumerate children: %s", exc)

        return found

    def get_details(self, pids: set[int]) -> list[ProcessInfo]:
        if not pids:
            return []

        try:
            import psutil
        except ImportError:
            return UnixDetector().get_details(pids)

        results: list[ProcessInfo] = []
        for pid in pids:
            try:
                p = psutil.Process(pid)
                mem_mb = round(p.memory_info().rss / (1024 * 1024), 1)
                ct = p.create_time()
                started = datetime.fromtimestamp(ct, tz=timezone.utc).isoformat()
                results.append(ProcessInfo(
                    pid=pid,
                    command=f"claude (pid={pid})",
                    memory_mb=mem_mb,
                    started_at=started,
                ))
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess, OSError):
                results.append(ProcessInfo(pid=pid))
        return results


# ---------------------------------------------------------------------------
# Factory — module-level singleton
# ---------------------------------------------------------------------------

_detector: ProcessDetector | None = None

# ---------------------------------------------------------------------------
# PID tracking registry — Layer 1 (primary, O(1) lookup)
# ---------------------------------------------------------------------------
# Populated at SDK launch time when the PID is already known.
# Platform detectors (Layer 2) serve as fallback.

_tracked_pids: dict[int, str] = {}  # pid → task_id


def register_pid(pid: int, task_id: str) -> None:
    """Register a CLI process PID for active tracking."""
    _tracked_pids[pid] = task_id
    logger.info("Registered tracked PID %d for task %s", pid, task_id)


def unregister_pid(pid: int) -> None:
    """Remove a single PID from the tracking registry."""
    removed = _tracked_pids.pop(pid, None)
    if removed is not None:
        logger.info("Unregistered tracked PID %d (task %s)", pid, removed)


def get_tracked_pids() -> set[int]:
    """Return all tracked PIDs that are still alive (prune dead ones)."""
    alive = set()
    dead = []
    for pid in _tracked_pids:
        try:
            os.kill(pid, 0)  # signal 0: existence check only
            alive.add(pid)
        except OSError:
            dead.append(pid)
    for pid in dead:
        _tracked_pids.pop(pid, None)
    return alive


def unregister_pids_for_task(task_id: str) -> None:
    """Remove all PIDs associated with a given task."""
    to_remove = [pid for pid, tid in _tracked_pids.items() if tid == task_id]
    for pid in to_remove:
        _tracked_pids.pop(pid, None)
    if to_remove:
        logger.info("Unregistered %d tracked PID(s) for task %s: %s", len(to_remove), task_id, to_remove)


def get_pids_for_task(task_id: str) -> list[int]:
    """Return all tracked PIDs associated with the given task (read-only snapshot).

    Unlike ``unregister_pids_for_task`` this does **not** mutate ``_tracked_pids``.
    Used by ``abort_query()`` to snapshot PIDs before cancellation so they can
    be killed even after the asyncio finally block unregisters them.
    """
    return [pid for pid, tid in _tracked_pids.items() if tid == task_id]


# Process names considered valid CLI targets (lowercase, without .exe suffix).
_CLI_PROCESS_NAMES: frozenset[str] = frozenset(("node", "claude"))


def is_cli_process(pid: int) -> bool:
    """Check whether *pid* still belongs to a recognised CLI process.

    Used by ``abort_query()`` before sending SIGTERM to avoid killing an
    unrelated process that reused the same PID after the original CLI exited.

    Returns ``True`` only when the process exists **and** its name matches
    one of the known CLI executable names (``node``, ``claude``).
    Returns ``False`` if the process is gone, inaccessible, or has a
    different name (i.e. PID was reused by the OS for something else).
    """
    try:
        import psutil
        proc = psutil.Process(pid)
        name = proc.name()
        basename = name.rsplit(".", 1)[0].lower() if name.endswith(".exe") else name.lower()
        return basename in _CLI_PROCESS_NAMES
    except ImportError:
        # psutil not available — fall back to existence-only check (best effort)
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    except Exception:
        # NoSuchProcess / AccessDenied / ZombieProcess — not a valid target
        return False


def get_detector() -> ProcessDetector:
    """Return the platform-appropriate ``ProcessDetector`` singleton."""
    global _detector
    if _detector is None:
        if os.name == "nt":
            _detector = WindowsDetector()
        elif sys.platform == "darwin":
            _detector = MacOSDetector()
        else:
            _detector = UnixDetector()
    return _detector
