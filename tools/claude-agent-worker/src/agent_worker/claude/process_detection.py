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
from datetime import datetime
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
# Factory — module-level singleton
# ---------------------------------------------------------------------------

_detector: ProcessDetector | None = None


def get_detector() -> ProcessDetector:
    """Return the platform-appropriate ``ProcessDetector`` singleton."""
    global _detector
    if _detector is None:
        if os.name == "nt":
            _detector = WindowsDetector()
        # Future: elif sys.platform == "darwin": _detector = MacOSDetector()
        else:
            _detector = UnixDetector()
    return _detector
