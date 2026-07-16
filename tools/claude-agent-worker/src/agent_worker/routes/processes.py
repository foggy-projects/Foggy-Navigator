"""CLI process management endpoints — list and kill orphan Claude Code node processes."""

from __future__ import annotations

from collections import defaultdict
import logging
import os
import re
import signal
from datetime import datetime, timezone

import psutil
from fastapi import APIRouter, Depends, Header, HTTPException, Path, status

from ..auth import verify_token
from ..models import (
    CliProcessInfo,
    CliProcessListResponse,
    KillProcessRequest,
    KillProcessResponse,
)
from ..claude import event_mapper
from ..claude.process_detection import get_detector, _tracked_pids
from ..claude.sdk_wrapper import _find_sdk_cli_pids, task_registry
from ..termination import (
    OPERATION_HEADER,
    SIGNATURE_HEADER,
    TerminationCapability,
    verify_termination_capability,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["processes"], dependencies=[Depends(verify_token)])


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _parse_resume_session_id(command: str) -> str | None:
    """Extract Claude session ID from ``--resume <id>`` in the command line."""
    m = re.search(r'--resume\s+(\S+)', command)
    return m.group(1) if m else None


def _canonical_process_identity(pid: int, started_at: str | None) -> str | None:
    """Return the opaque identity used to bind a manual kill to one process.

    A PID is recyclable by the operating system.  The Worker therefore binds a
    capability to the exact start-time observation that was returned to
    Navigator, and repeats that observation immediately before signal
    dispatch.  Missing start-time evidence fails closed rather than treating a
    PID as a durable process identity.
    """

    if not isinstance(started_at, str) or not started_at.strip():
        return None
    return f"claude-cli:{pid}:{started_at.strip()}"


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


def _process_brief(proc: CliProcessInfo) -> dict[str, object]:
    """Return a compact, log-friendly snapshot of a process."""
    return {
        "pid": proc.pid,
        "claude_session_id": proc.claude_session_id,
        "foggy_task_id": proc.foggy_task_id,
        "foggy_session_id": proc.foggy_session_id,
        "model": proc.model,
        "is_orphan": proc.is_orphan,
        "started_at": proc.started_at,
        "process_identity": proc.process_identity,
        # Commands may contain prompt text, paths, or credentials injected by
        # callers.  Diagnostics need only indicate whether one was observed.
        "command_present": bool(proc.command),
    }


def _build_duplicate_groups(
    processes: list[CliProcessInfo],
) -> list[tuple[str, str, list[CliProcessInfo]]]:
    """Group processes that appear to belong to the same task/session.

    Priority:
    1. ``foggy_task_id`` — best business-level identity
    2. ``claude_session_id`` — fallback when only CLI resume session is known
    """
    groups: dict[tuple[str, str], list[CliProcessInfo]] = defaultdict(list)

    for proc in processes:
        if proc.foggy_task_id:
            groups[("foggy_task_id", proc.foggy_task_id)].append(proc)
        elif proc.claude_session_id:
            groups[("claude_session_id", proc.claude_session_id)].append(proc)

    duplicates: list[tuple[str, str, list[CliProcessInfo]]] = []
    for (identity_type, identity_value), grouped in groups.items():
        if len(grouped) > 1:
            duplicates.append((identity_type, identity_value, sorted(grouped, key=lambda p: p.pid)))
    return duplicates


def _find_related_processes(
    processes: list[CliProcessInfo],
    target: CliProcessInfo,
) -> list[CliProcessInfo]:
    """Return sibling processes likely tied to the same task/session."""
    related: list[CliProcessInfo] = []
    for proc in processes:
        if proc.pid == target.pid:
            continue
        same_foggy_task = bool(target.foggy_task_id and proc.foggy_task_id == target.foggy_task_id)
        same_claude_session = bool(
            target.claude_session_id
            and proc.claude_session_id == target.claude_session_id
        )
        if same_foggy_task or same_claude_session:
            related.append(proc)
    return sorted(related, key=lambda p: p.pid)


def _log_process_snapshot(processes: list[CliProcessInfo], active_task_count: int) -> None:
    """Emit diagnostics for the current process list."""
    if not processes:
        logger.debug(
            "CLI process snapshot: total=0 active_task_count=%d tracked_pids=%d task_registry=%d",
            active_task_count, len(_tracked_pids), len(task_registry),
        )
        return

    briefs = [_process_brief(proc) for proc in sorted(processes, key=lambda p: p.pid)]
    logger.info(
        "CLI process snapshot: total=%d active_task_count=%d tracked_pids=%d task_registry=%d processes=%s",
        len(processes), active_task_count, len(_tracked_pids), len(task_registry), briefs,
    )

    for identity_type, identity_value, grouped in _build_duplicate_groups(processes):
        logger.warning(
            "CLI process duplicate identity detected: %s=%s count=%d pids=%s details=%s",
            identity_type,
            identity_value,
            len(grouped),
            [proc.pid for proc in grouped],
            [_process_brief(proc) for proc in grouped],
        )


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
        # Layer 0: the process environment is the authoritative identity.  It
        # survives Worker restarts and cannot be confused with a stale in-memory
        # PID registration after PID reuse.
        if not proc.foggy_task_id:
            foggy_env = _read_foggy_env(proc.pid)
            if foggy_env.get("foggy_task_id"):
                proc.foggy_task_id = foggy_env["foggy_task_id"]
                proc.foggy_session_id = foggy_env["foggy_session_id"]
                proc.is_orphan = False

        # Layer 1: tracked PID registry → task_registry lookup.  This is a
        # fallback for platforms where process environment/cmdline visibility
        # is unavailable, not an override for a process-owned Foggy identity.
        tracked_task_id = _tracked_pids.get(proc.pid)
        if tracked_task_id and not proc.foggy_task_id:
            entry = task_registry.get(tracked_task_id)
            if entry:
                proc.foggy_task_id = entry.get("foggy_task_id")
                proc.foggy_session_id = entry.get("foggy_session_id")
                proc.claude_session_id = entry.get("session_id")
                proc.model = entry.get("model")
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
            process_identity=_canonical_process_identity(p.pid, p.started_at),
            command=p.command,
            memory_mb=p.memory_mb,
            started_at=p.started_at,
            is_orphan=active_task_count == 0,
        )
        for p in raw
    ]


def _resolve_pid_task_binding(
    *,
    pid: int,
    target: CliProcessInfo | None,
    capability_task_id: str,
) -> dict | None:
    """Resolve and verify the task identity bound to a manual PID operation.

    A valid PID alone is not enough: it can be reused, and an administrator
    must not turn a capability for one task into a kill for another.  We accept
    only a direct process ``FOGGY_TASK_ID`` identity or a live registry binding
    (including its worker task ID).  An orphan CLI is intentionally not a
    manual-kill target through this task-scoped API.
    """

    binding_groups: list[tuple[set[str], dict]] = []

    def _add_entry(worker_task_id: str, entry: dict) -> None:
        entry_task_ids = {worker_task_id}
        foggy_task_id = entry.get("foggy_task_id")
        if isinstance(foggy_task_id, str) and foggy_task_id:
            entry_task_ids.add(foggy_task_id)
        binding_groups.append((entry_task_ids, entry))

    # Always re-read the process environment for the destructive route.  The
    # process-owned identity takes precedence over a potentially stale tracker
    # entry, including when list-process enrichment previously fell back to it.
    direct_foggy_task_id = _read_foggy_env(pid).get("foggy_task_id")
    target_foggy_task_id = getattr(target, "foggy_task_id", None)
    observed_task_id = direct_foggy_task_id or (
        target_foggy_task_id
        if isinstance(target_foggy_task_id, str) and target_foggy_task_id
        else None
    )

    tracked_task_id = _tracked_pids.get(pid)
    if tracked_task_id:
        tracked_entry = task_registry.get(tracked_task_id)
        if tracked_entry is not None:
            _add_entry(tracked_task_id, tracked_entry)

    if observed_task_id:
        for worker_task_id, entry in task_registry.items():
            if entry.get("foggy_task_id") == observed_task_id:
                _add_entry(worker_task_id, entry)

    target_session_id = getattr(target, "claude_session_id", None)
    if isinstance(target_session_id, str) and target_session_id:
        for worker_task_id, entry in task_registry.items():
            if entry.get("session_id") == target_session_id:
                _add_entry(worker_task_id, entry)

    if not observed_task_id and not binding_groups:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"PID {pid} has no active task or Foggy task binding",
        )

    # A direct process identity is authoritative.  If a tracked/session
    # binding disagrees, do not choose one arbitrarily: PID reuse or stale
    # registry state must never turn a capability for task A into a kill of
    # task B.
    if observed_task_id:
        incompatible = [aliases for aliases, _entry in binding_groups if observed_task_id not in aliases]
        if incompatible:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"PID {pid} has conflicting process and active-task bindings",
            )
        allowed_task_ids = {observed_task_id}
        for aliases, _entry in binding_groups:
            allowed_task_ids.update(aliases)
    else:
        # Without a process-owned Foggy task ID, exactly one live registry
        # binding may authorize the PID.  Multiple disjoint bindings are an
        # ambiguity, not permission to use either capability.
        allowed_task_ids = set(binding_groups[0][0])
        for aliases, _entry in binding_groups[1:]:
            if not allowed_task_ids.intersection(aliases):
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"PID {pid} has conflicting active-task bindings",
                )
            allowed_task_ids.update(aliases)

    if capability_task_id not in allowed_task_ids:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Termination operation task does not match the target process binding",
        )
    return next(
        (entry for aliases, entry in binding_groups if capability_task_id in aliases),
        None,
    )


def _registered_task_id(entry: dict, fallback_task_id: str) -> str:
    """Return the live Worker task key for an entry, if it is still known."""

    return next(
        (task_id for task_id, candidate in task_registry.items() if candidate is entry),
        fallback_task_id,
    )


async def _record_manual_kill_observation(
    *,
    entry: dict | None,
    capability: TerminationCapability,
    pid: int,
    operation_summary: dict,
    result: str,
) -> None:
    """Persist and publish an unconfirmed manual PID operation observation.

    A delivered signal is never exit evidence.  This helper keeps the task
    non-terminal and makes the same safe operation summary visible through
    both ``GET /tasks/{id}/status`` and an already-subscribed SSE stream.
    """

    if entry is None:
        return

    observed_at = operation_summary["observed_at"]
    entry["attention_state"] = "TERMINATION_UNCONFIRMED"
    entry["lifecycle_evidence"] = {
        "source": "EXPLICIT_MANUAL_PID_KILL_OPERATION",
        "operation_id": capability.operation_id,
        "origin": capability.origin,
        "reason_code": capability.reason_code,
        "pid": pid,
        "result": result,
        "observed_at": observed_at,
    }
    entry["termination_operation"] = operation_summary

    broadcast = entry.get("broadcast")
    if broadcast is None or broadcast.closed:
        return
    try:
        await broadcast.put(
            event_mapper.map_system(
                task_id=_registered_task_id(entry, capability.task_id),
                subtype="manual_pid_kill_requested",
                data={
                    "operation_id": capability.operation_id,
                    "kind": capability.kind,
                    "pid": pid,
                    "result": result,
                },
                session_id=entry.get("session_id"),
                attention=[{
                    "code": "TERMINATION_UNCONFIRMED",
                    "source": "EXPLICIT_MANUAL_PID_KILL_OPERATION",
                    "observed_at": observed_at,
                    "recoverable": not bool(entry.get("terminal_observed")),
                }],
                attention_status="TERMINATION_UNCONFIRMED",
                available_actions=(
                    [] if entry.get("terminal_observed")
                    else ["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"]
                ),
                lifecycle_state=(
                    "COMPLETED" if entry.get("terminal_observed")
                    else "CANCEL_REQUESTED" if entry.get("cancel_requested")
                    else "RUNNING"
                ),
                termination_operation=operation_summary,
            )
        )
    except Exception as exc:
        # The operation may already have signalled the CLI.  A subsequent
        # observability failure must not turn that acknowledgement into a
        # false dispatch failure or obscure the safe status summary.
        logger.warning(
            "Manual PID kill lifecycle event emit failed: operation_id=%s task_id=%s pid=%d "
            "error_code=MANUAL_PID_KILL_EVENT_EMIT_FAILED error_type=%s",
            capability.operation_id,
            capability.task_id,
            pid,
            type(exc).__name__,
        )


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
    _log_process_snapshot(processes, len(task_registry))
    return CliProcessListResponse(
        processes=processes,
        active_task_count=len(task_registry),
        total=len(processes),
    )


@router.post("/processes/{pid}/kill", response_model=KillProcessResponse)
async def kill_process(
    pid: int = Path(..., description="PID of the Claude CLI process to kill"),
    body: KillProcessRequest | None = None,
    operation: str | None = Header(None, alias=OPERATION_HEADER),
    signature: str | None = Header(None, alias=SIGNATURE_HEADER),
) -> KillProcessResponse:
    """Kill a specific Claude CLI node process by PID.

    Only processes matching the Claude CLI signature (``--print`` flag)
    can be killed via this endpoint — arbitrary PIDs are rejected. This is an
    isolated administrative PID operation; it never dispatches a task abort.
    """
    capability = verify_termination_capability(
        encoded_operation=operation,
        encoded_signature=signature,
        expected_kind="MANUAL_PID_KILL",
        route_pid=pid,
    )
    force = body.force if body else False

    # Verify the PID is actually a Claude CLI process
    cli_pids = _find_sdk_cli_pids()
    if pid not in cli_pids:
        logger.warning(
            "Manual CLI kill target absent: operation_id=%s task_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s pid=%d",
            capability.operation_id,
            capability.task_id,
            capability.origin,
            capability.actor_id,
            capability.reason_code,
            capability.correlation_id,
            pid,
        )
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PID {pid} is not a Claude CLI process or no longer exists",
        )

    processes = _get_process_details(cli_pids)
    _enrich_processes(processes)
    target = next((proc for proc in processes if proc.pid == pid), None)
    if target is None:
        logger.warning(
            "Manual CLI kill process identity unavailable: operation_id=%s task_id=%s pid=%d reason=TARGET_DETAILS_UNAVAILABLE",
            capability.operation_id,
            capability.task_id,
            pid,
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="CLI process identity is unavailable; termination was not dispatched",
        )

    observed_process_identity = _canonical_process_identity(target.pid, target.started_at)
    if observed_process_identity is None:
        logger.warning(
            "Manual CLI kill process identity unavailable: operation_id=%s task_id=%s pid=%d reason=START_TIME_UNAVAILABLE",
            capability.operation_id,
            capability.task_id,
            pid,
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="CLI process identity is unavailable; termination was not dispatched",
        )
    if capability.expected_process_identity != observed_process_identity:
        logger.warning(
            "Manual CLI kill process identity mismatch: operation_id=%s task_id=%s pid=%d",
            capability.operation_id,
            capability.task_id,
            pid,
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="CLI process identity changed; termination was not dispatched",
        )

    bound_entry = _resolve_pid_task_binding(
        pid=pid,
        target=target,
        capability_task_id=capability.task_id,
    )
    requested_at = datetime.now(timezone.utc).isoformat()
    operation_summary = capability.public_summary(
        operation_status="UNCONFIRMED",
        observed_exit=False,
    )
    operation_summary["requested_at"] = requested_at
    if target is not None:
        related = _find_related_processes(processes, target)
        logger.warning(
            "CLI kill requested: operation_id=%s task_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s pid=%d force=%s target=%s related_count=%d related=%s",
            capability.operation_id,
            capability.task_id,
            capability.origin,
            capability.actor_id,
            capability.reason_code,
            capability.correlation_id,
            pid,
            force,
            _process_brief(target),
            len(related),
            [_process_brief(proc) for proc in related],
        )
    try:
        if force and os.name != "nt":
            os.kill(pid, signal.SIGKILL)
        else:
            os.kill(pid, signal.SIGTERM)
        operation_summary["result"] = "SIGNAL_DISPATCHED_EXIT_UNCONFIRMED"
        operation_summary["observed_at"] = datetime.now(timezone.utc).isoformat()
        await _record_manual_kill_observation(
            entry=bound_entry,
            capability=capability,
            pid=pid,
            operation_summary=operation_summary,
            result="SIGNAL_DISPATCHED_EXIT_UNCONFIRMED",
        )
        logger.info(
            "Manual CLI kill signal dispatched operation_id=%s task_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s pid=%d force=%s; exit remains unverified",
            capability.operation_id,
            capability.task_id,
            capability.origin,
            capability.actor_id,
            capability.reason_code,
            capability.correlation_id,
            pid,
            force,
        )
        return KillProcessResponse(
            pid=pid,
            status="KILL_REQUESTED",
            message=f"Termination signal dispatched for process {pid}; exit remains unverified",
            operation_id=capability.operation_id,
            lifecycle_status="KILL_REQUESTED_UNVERIFIED",
            observed_exit=False,
            attention_state="TERMINATION_UNCONFIRMED",
            task_id=capability.task_id,
            attention=[{
                "code": "TERMINATION_UNCONFIRMED",
                "source": "EXPLICIT_MANUAL_PID_KILL_OPERATION",
                "observed_at": operation_summary["observed_at"],
                "recoverable": True,
            }],
            available_actions=["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"],
            lifecycle_state="RUNNING",
            termination_operation=operation_summary,
        )
    except ProcessLookupError:
        operation_summary["result"] = "PID_NOT_FOUND_EXIT_UNCONFIRMED"
        operation_summary["observed_at"] = datetime.now(timezone.utc).isoformat()
        await _record_manual_kill_observation(
            entry=bound_entry,
            capability=capability,
            pid=pid,
            operation_summary=operation_summary,
            result="PID_NOT_FOUND_EXIT_UNCONFIRMED",
        )
        return KillProcessResponse(
            pid=pid,
            status="not_found",
            message=f"Process {pid} was not present when the termination signal was dispatched; exit remains unverified",
            operation_id=capability.operation_id,
            lifecycle_status="PID_NOT_FOUND_EXIT_UNCONFIRMED",
            observed_exit=False,
            attention_state="TERMINATION_UNCONFIRMED",
            task_id=capability.task_id,
            attention=[{
                "code": "TERMINATION_UNCONFIRMED",
                "source": "EXPLICIT_MANUAL_PID_KILL_OPERATION",
                "observed_at": operation_summary["observed_at"],
                "recoverable": True,
            }],
            available_actions=["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"],
            lifecycle_state="RUNNING",
            termination_operation=operation_summary,
        )
    except OSError as exc:
        operation_summary["result"] = "SIGNAL_DISPATCH_FAILED"
        operation_summary["observed_at"] = datetime.now(timezone.utc).isoformat()
        logger.warning(
            "Manual CLI kill signal dispatch failed: operation_id=%s task_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s pid=%d error_type=%s",
            capability.operation_id,
            capability.task_id,
            capability.origin,
            capability.actor_id,
            capability.reason_code,
            capability.correlation_id,
            pid,
            exc.__class__.__name__,
        )
        await _record_manual_kill_observation(
            entry=bound_entry,
            capability=capability,
            pid=pid,
            operation_summary=operation_summary,
            result="SIGNAL_DISPATCH_FAILED",
        )
        return KillProcessResponse(
            pid=pid,
            status="failed",
            message=f"Termination signal dispatch failed for process {pid}; exit remains unverified",
            operation_id=capability.operation_id,
            lifecycle_status="KILL_DISPATCH_FAILED",
            observed_exit=False,
            attention_state="TERMINATION_UNCONFIRMED",
            task_id=capability.task_id,
            attention=[{
                "code": "TERMINATION_UNCONFIRMED",
                "source": "EXPLICIT_MANUAL_PID_KILL_OPERATION",
                "observed_at": operation_summary["observed_at"],
                "recoverable": True,
            }],
            available_actions=["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"],
            lifecycle_state="RUNNING",
            termination_operation=operation_summary,
        )
