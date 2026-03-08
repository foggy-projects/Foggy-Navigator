"""CLI process management endpoints — list and kill orphan Claude Code node processes."""

from __future__ import annotations

import logging
import os
import re
import signal

import psutil
from fastapi import APIRouter, Depends, HTTPException, Path, status

from ..auth import verify_token
from ..models import (
    CliProcessInfo,
    CliProcessListResponse,
    KillProcessRequest,
    KillProcessResponse,
)
from ..claude.process_detection import get_detector, _tracked_pids
from ..claude.sdk_wrapper import _find_sdk_cli_pids, task_registry

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["processes"], dependencies=[Depends(verify_token)])


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_resume_session_id(command: str) -> str | None:
    """Extract Claude session ID from ``--resume <id>`` in the command line."""
    m = re.search(r'--resume\s+(\S+)', command)
    return m.group(1) if m else None


def _read_foggy_env(pid: int) -> dict[str, str | None]:
    """Read FOGGY_TASK_ID / FOGGY_SESSION_ID directly from the process environment.

    Uses psutil for cross-platform support (Linux /proc, Windows NtQueryInformation).
    This works even after Worker restart when task_registry is empty, because the
    env vars were injected into the CLI subprocess at spawn time and survive as long
    as the process is alive.

    Returns an empty dict on any access error (process gone, permission denied, etc.).
    """
    try:
        env = psutil.Process(pid).environ()
        return {
            "foggy_task_id": env.get("FOGGY_TASK_ID") or None,
            "foggy_session_id": env.get("FOGGY_SESSION_ID") or None,
        }
    except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess, OSError):
        return {"foggy_task_id": None, "foggy_session_id": None}


def _build_registry_session_lookup() -> dict[str, dict[str, str | None]]:
    """Build claude_session_id → foggy IDs mapping from the live task_registry.

    This is a secondary enrichment source used to correlate the ``--resume``
    session ID in the command line with Foggy platform IDs when the process
    environment is not accessible.  It is empty after a Worker restart.
    """
    lookup: dict[str, dict[str, str | None]] = {}
    for entry in task_registry.values():
        sid = entry.get("session_id")
        if sid:
            lookup[sid] = {
                "foggy_task_id": entry.get("foggy_task_id"),
                "foggy_session_id": entry.get("foggy_session_id"),
            }
    return lookup


def _enrich_processes(processes: list[CliProcessInfo]) -> None:
    """Enrich process list with Claude session and Foggy platform IDs.

    Enrichment strategy (two layers):

    1. **Primary — process env vars via psutil**: Read FOGGY_TASK_ID and
       FOGGY_SESSION_ID directly from the process environment.  This is
       reliable even after a Worker restart because the vars are baked into
       the spawned subprocess and persist as long as the process is alive.

    2. **Secondary — task_registry session lookup**: Parse ``--resume <id>``
       from the command line and cross-reference against the live
       task_registry.  This layer provides claude_session_id correlation and
       serves as a fallback if psutil env access fails.

    A process is considered non-orphan when its foggy_task_id can be resolved
    from either layer.
    """
    registry_lookup = _build_registry_session_lookup()

    for proc in processes:
        # Layer 0: tracked PID registry → task_registry lookup (most reliable,
        # works on macOS where process.title destroys env/cmdline visibility)
        tracked_task_id = _tracked_pids.get(proc.pid)
        if tracked_task_id:
            entry = task_registry.get(tracked_task_id)
            if entry:
                proc.foggy_task_id = entry.get("foggy_task_id")
                proc.foggy_session_id = entry.get("foggy_session_id")
                proc.claude_session_id = entry.get("session_id")
                proc.is_orphan = False

        # Layer 1: read env vars directly from the process (survives Worker restart)
        if not proc.foggy_task_id:
            foggy_env = _read_foggy_env(proc.pid)
            if foggy_env.get("foggy_task_id"):
                proc.foggy_task_id = foggy_env["foggy_task_id"]
                proc.foggy_session_id = foggy_env["foggy_session_id"]
                proc.is_orphan = False

        # Layer 2: command-line --resume → task_registry lookup
        if not proc.claude_session_id:
            claude_sid = _parse_resume_session_id(proc.command or "")
            if claude_sid:
                proc.claude_session_id = claude_sid
        if not proc.foggy_task_id and proc.claude_session_id:
            match = registry_lookup.get(proc.claude_session_id)
            if match and match.get("foggy_task_id"):
                proc.foggy_task_id = match["foggy_task_id"]
                proc.foggy_session_id = match.get("foggy_session_id")
                proc.is_orphan = False


def _get_process_details(pids: set[int]) -> list[CliProcessInfo]:
    """Get detailed info for the given PIDs via the platform detector.

    Converts ``ProcessInfo`` (plain dataclass) into ``CliProcessInfo``
    (Pydantic model with business fields like *is_orphan*).
    """
    raw = get_detector().get_details(pids)
    active_task_count = len(task_registry)
    return [
        CliProcessInfo(
            pid=p.pid,
            command=p.command,
            memory_mb=p.memory_mb,
            started_at=p.started_at,
            is_orphan=active_task_count == 0,
        )
        for p in raw
    ]


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@router.get("/processes", response_model=CliProcessListResponse)
async def list_processes() -> CliProcessListResponse:
    """List all Claude CLI node processes on this machine.

    Processes are detected by the ``--print`` flag in the command line,
    which is unique to SDK-spawned CLI processes.
    """
    pids = _find_sdk_cli_pids()
    processes = _get_process_details(pids)
    _enrich_processes(processes)
    return CliProcessListResponse(
        processes=processes,
        active_task_count=len(task_registry),
        total=len(processes),
    )


@router.post("/processes/{pid}/kill", response_model=KillProcessResponse)
async def kill_process(
    pid: int = Path(..., description="PID of the Claude CLI process to kill"),
    body: KillProcessRequest | None = None,
) -> KillProcessResponse:
    """Kill a specific Claude CLI node process by PID.

    Only processes matching the Claude CLI signature (``--print`` flag)
    can be killed via this endpoint — arbitrary PIDs are rejected.
    """
    force = body.force if body else False

    # Verify the PID is actually a Claude CLI process
    cli_pids = _find_sdk_cli_pids()
    if pid not in cli_pids:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PID {pid} is not a Claude CLI process or no longer exists",
        )

    try:
        if force and os.name != "nt":
            os.kill(pid, signal.SIGKILL)
        else:
            os.kill(pid, signal.SIGTERM)
        logger.info("Killed CLI process pid=%d (force=%s) via API", pid, force)
        return KillProcessResponse(
            pid=pid,
            status="killed",
            message=f"Process {pid} terminated {'forcefully' if force else 'gracefully'}",
        )
    except ProcessLookupError:
        return KillProcessResponse(
            pid=pid,
            status="not_found",
            message=f"Process {pid} no longer exists",
        )
    except OSError as exc:
        logger.warning("Failed to kill pid %d: %s", pid, exc)
        return KillProcessResponse(
            pid=pid,
            status="failed",
            message=f"Failed to kill process {pid}: {exc}",
        )
