from __future__ import annotations

import asyncio
import json
import logging
import os
import platform
import shutil
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncGenerator

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..claude.sdk_wrapper import (
    SdkWrapper,
    task_registry,
    permission_pending,
    _sdk_available,
    _use_agent_sdk,
    EventBroadcast,
    has_verified_terminal_evidence,
)
from ..claude import event_mapper
from ..config import settings
from ..models import AbortResponse, PermissionResponse, QueryEvent, QueryRequest, RewindRequest
from ..termination import (
    OPERATION_HEADER,
    SIGNATURE_HEADER,
    verify_termination_capability,
)
from .utils import is_path_within_allowed_root

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["query"], dependencies=[Depends(verify_token)])

_wrapper = SdkWrapper()

_PENDING_DECISION_ACTIONS = ["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"]
_TERMINAL_STATUSES = frozenset({"COMPLETED", "FAILED", "ABORTED"})
_VERIFIED_TERMINAL_SOURCES = frozenset({
    "PROVIDER_TERMINAL_EVENT",
    "VERIFIED_MANAGED_PROCESS_EXIT",
})


def _load_persisted_terminal_evidence(
    store: Any,
    task_id: str,
) -> dict[str, Any] | None:
    """Return the latest explicit, durable terminal event for a task.

    Event-stream closure and a replay sequence only establish transport state.
    They are deliberately not terminal evidence: a diagnostic ``error`` or a
    detached producer can close the stream while the managed CLI remains
    unresolved.  Persisted terminal state therefore requires all three
    additive fields and a known verified source.
    """

    load_events = getattr(store, "load_events", None)
    if not callable(load_events):
        logger.warning("Event store does not support durable terminal lookup: task=%s", task_id)
        return None

    try:
        events = load_events(task_id)
    except Exception as exc:
        logger.warning(
            "Failed to load durable events for terminal lookup: task=%s type=%s",
            task_id,
            type(exc).__name__,
        )
        return None

    for event in reversed(events):
        if not isinstance(event, dict) or event.get("terminal_observed") is not True:
            continue
        terminal_status = event.get("terminal_status")
        terminal_source = event.get("terminal_source")
        if (
            not isinstance(terminal_status, str)
            or terminal_status not in _TERMINAL_STATUSES
            or not isinstance(terminal_source, str)
            or terminal_source not in _VERIFIED_TERMINAL_SOURCES
        ):
            continue
        return {
            "terminal_status": terminal_status,
            "terminal_source": terminal_source,
            "event_seq": event.get("seq"),
        }

    return None


def _attention_payload(entry: dict[str, Any]) -> list[dict[str, Any]]:
    """Expose the current recoverable attention without changing old fields."""

    attention_state = entry.get("attention_state")
    if not isinstance(attention_state, str) or not attention_state:
        return []
    evidence = entry.get("lifecycle_evidence")
    payload: dict[str, Any] = {
        "code": attention_state,
        "recoverable": not has_verified_terminal_evidence(entry),
    }
    if isinstance(evidence, dict):
        source = evidence.get("source")
        if isinstance(source, str) and source:
            payload["source"] = source
        observed_at = evidence.get("observed_at")
        if isinstance(observed_at, str) and observed_at:
            payload["observed_at"] = observed_at
    return [payload]


def _available_actions(entry: dict[str, Any]) -> list[str]:
    """Return explicit next actions only while a task remains non-terminal."""

    if has_verified_terminal_evidence(entry):
        return []
    if entry.get("attention_state") or entry.get("cancel_requested"):
        return list(_PENDING_DECISION_ACTIONS)
    return []


def _lifecycle_state(entry: dict[str, Any]) -> str:
    """Translate internal observation bookkeeping to the stable v2 state."""

    if has_verified_terminal_evidence(entry):
        return str(entry.get("terminal_lifecycle_state") or "COMPLETED")
    if entry.get("execution_state") == "CANCEL_REQUESTED" or entry.get("cancel_requested"):
        return "CANCEL_REQUESTED"
    return "RUNNING"


async def _emit_lifecycle_event(
    *,
    task_id: str,
    entry: dict[str, Any],
    subtype: str,
    data: dict[str, Any] | None = None,
) -> None:
    """Publish an additive lifecycle event when the task stream is still open."""

    broadcast: EventBroadcast | None = entry.get("broadcast")
    if broadcast is None or broadcast.closed:
        return
    await broadcast.put(
        event_mapper.map_system(
            task_id=task_id,
            subtype=subtype,
            data=data,
            session_id=entry.get("session_id"),
            attention=_attention_payload(entry),
            attention_status=entry.get("attention_state"),
            available_actions=_available_actions(entry),
            lifecycle_state=_lifecycle_state(entry),
            termination_operation=entry.get("termination_operation"),
        )
    )


def _find_claude_cli() -> str:
    """Locate Claude Code CLI binary — same strategy as the SDK.

    1. Bundled CLI inside ``claude_agent_sdk/_bundled/``
    2. ``claude`` on system PATH (``shutil.which``)
    3. Well-known install locations
    """
    cli_name = "claude.exe" if platform.system() == "Windows" else "claude"

    # 1. Bundled CLI shipped with claude-agent-sdk
    try:
        import claude_agent_sdk as _pkg
        bundled = Path(_pkg.__file__).parent / "_bundled" / cli_name
        if bundled.is_file():
            return str(bundled)
    except Exception:
        pass

    # 2. System PATH
    which = shutil.which("claude")
    if which:
        return which

    # 3. Common locations
    for p in [
        Path.home() / ".npm-global/bin/claude",
        Path("/usr/local/bin/claude"),
        Path.home() / ".local/bin/claude",
        Path.home() / "node_modules/.bin/claude",
        Path.home() / ".yarn/bin/claude",
        Path.home() / ".claude/local/claude",
    ]:
        if p.is_file():
            return str(p)

    raise FileNotFoundError("Claude Code CLI not found. Install claude-agent-sdk or npm i -g @anthropic-ai/claude-code")


def _purge_stale_tasks() -> None:
    """Purge only tasks with observed terminal evidence.

    An ``asyncio.Task.done()`` observation alone is not permission to release a
    managed CLI.  The SDK task may have detached while a child CLI is still
    alive, so ambiguous cases remain queryable with ``PROCESS_UNVERIFIED``.
    """

    from ..claude.process_detection import get_pids_for_task, is_cli_process

    stale_ids: list[str] = []
    for tid, entry in list(task_registry.items()):
        atask: asyncio.Task | None = entry.get("asyncio_task")
        producer_task: asyncio.Task | None = entry.get("producer_task")
        observation_task = producer_task or atask
        if has_verified_terminal_evidence(entry):
            stale_ids.append(tid)
            continue
        if observation_task is None or not observation_task.done():
            continue

        broadcast: EventBroadcast | None = entry.get("broadcast")
        live_pids = [pid for pid in get_pids_for_task(tid) if is_cli_process(pid)]
        entry["execution_state"] = (
            "CANCEL_REQUESTED" if entry.get("cancel_requested") else "ACTIVE_TASK_EXECUTION"
        )
        entry["attention_state"] = "PROCESS_UNVERIFIED"
        entry["lifecycle_evidence"] = {
            "source": "ASYNCIO_TASK_OBSERVATION_UNVERIFIED",
            "reason": "TASK_DONE_WITHOUT_EXPLICIT_TERMINAL_EVENT",
            "stream_closed": bool(broadcast and broadcast.closed),
            "live_pid_count": len(live_pids),
        }
        logger.warning(
            "Retained task with unverified process state: task_id=%s foggy_task_id=%s",
            tid,
            entry.get("foggy_task_id"),
        )

    if not stale_ids:
        return

    for tid in stale_ids:
        entry = task_registry.pop(tid, None)
        if entry:
            # Also clean up any pending permissions for this task
            for pid in list(permission_pending):
                if permission_pending[pid].get("task_id") == tid:
                    permission_pending.pop(pid, None)
            logger.warning(
                "Purged terminal-observed task from registry: task_id=%s, foggy_task_id=%s",
                tid, entry.get("foggy_task_id"),
            )


def _validate_cwd(cwd: str | None) -> str:
    """Ensure *cwd* is inside one of the ``allowed_cwds``.

    If the allow-list is empty every directory is accepted (dev mode).
    Returns the resolved, normalised path that will be forwarded to the SDK.
    """

    if cwd is None:
        cwd = os.getcwd()

    resolved = os.path.realpath(os.path.expanduser(cwd))

    if not settings.allowed_cwds:
        return resolved

    for allowed in settings.allowed_cwds:
        allowed_resolved = os.path.realpath(allowed)
        if is_path_within_allowed_root(resolved, allowed_resolved):
            return resolved

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"Working directory '{cwd}' is not in the allowed list",
    )


async def _event_generator(
    task_id: str,
    prompt: str,
    cwd: str,
    session_id: str | None,
    max_turns: int | None,
    model: str | None = None,
    extra_args: dict | None = None,
    images: list[dict] | None = None,
    api_key: str | None = None,
    auth_token: str | None = None,
    base_url: str | None = None,
    permission_mode: str | None = None,
    navigator_api_key: str | None = None,
    navigator_api_base: str | None = None,
    disallowed_tools: list[str] | None = None,
    foggy_task_id: str | None = None,
    foggy_session_id: str | None = None,
    extra_env_vars: dict[str, str] | None = None,
) -> AsyncGenerator[dict, None]:
    """Yield SSE-compatible ``dict`` payloads from the SDK wrapper stream."""

    try:
        async for event in _wrapper.run_query(
            task_id=task_id,
            prompt=prompt,
            cwd=cwd,
            session_id=session_id,
            max_turns=max_turns,
            model=model,
            extra_args=extra_args,
            images=images,
            api_key=api_key,
            auth_token=auth_token,
            base_url=base_url,
            permission_mode=permission_mode,
            navigator_api_key=navigator_api_key,
            navigator_api_base=navigator_api_base,
            disallowed_tools=disallowed_tools,
            foggy_task_id=foggy_task_id,
            foggy_session_id=foggy_session_id,
            extra_env_vars=extra_env_vars,
        ):
            yield {"event": "message", "data": json.dumps(event)}
    except asyncio.CancelledError:
        cancel_event = QueryEvent(
            type="error",
            task_id=task_id,
            error="CLAUDE_CANCEL_REQUESTED",
        )
        yield {"event": "message", "data": cancel_event.model_dump_json()}
    except Exception as exc:
        logger.error("Unexpected error in task %s: type=%s", task_id, type(exc).__name__)
        error_event = QueryEvent(
            type="error",
            task_id=task_id,
            error="CLAUDE_QUERY_STREAM_UNCONFIRMED",
        )
        yield {"event": "message", "data": error_event.model_dump_json()}


@router.post("/query")
async def query(body: QueryRequest):
    """Start a Claude Code query and stream results as SSE events.

    Each event uses the event name ``message`` and carries a JSON-serialised
    :class:`QueryEvent` as its ``data`` field.
    """

    if len(task_registry) >= settings.max_concurrent_tasks:
        # Before rejecting, purge stale entries whose asyncio task is already
        # done (e.g. cleanup skipped due to has_external_subscriber leak) and
        # that have no live CLI process backing them.
        _purge_stale_tasks()
        if len(task_registry) >= settings.max_concurrent_tasks:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Maximum concurrent tasks ({settings.max_concurrent_tasks}) reached",
            )

    cwd = _validate_cwd(body.cwd)
    task_id = str(uuid.uuid4())

    # Convert Pydantic ImageAttachment list to plain dicts for the wrapper.
    images_raw = [img.model_dump() for img in body.images] if body.images else None

    return EventSourceResponse(
        _event_generator(
            task_id=task_id,
            prompt=body.prompt,
            cwd=cwd,
            session_id=body.session_id,
            max_turns=body.max_turns,
            model=body.model,
            extra_args=body.extra_args,
            images=images_raw,
            api_key=body.api_key,
            auth_token=body.auth_token,
            base_url=body.base_url,
            permission_mode=body.permission_mode,
            navigator_api_key=body.navigator_api_key,
            navigator_api_base=body.navigator_api_base,
            disallowed_tools=body.disallowed_tools,
            foggy_task_id=body.foggy_task_id,
            foggy_session_id=body.foggy_session_id,
            extra_env_vars=body.extra_env_vars,
        ),
        media_type="text/event-stream",
        ping=30,  # SSE keepalive every 30s — prevents proxies/WebClient idle timeout
    )


@router.post("/query/{task_id}/respond")
async def respond_to_permission(task_id: str, body: PermissionResponse):
    """Respond to a pending permission request for a running task.

    The ``permission_id`` must match an active permission request
    created by the ``can_use_tool`` callback.
    """

    entry = permission_pending.get(body.permission_id)

    if entry is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Permission request '{body.permission_id}' not found or already resolved",
        )

    if entry.get("task_id") != task_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Permission request does not belong to this task",
        )

    # Deliver the decision, scope, answers, plan_action and signal the waiting callback
    entry["result"] = body.decision
    entry["deny_message"] = body.deny_message
    entry["scope"] = body.scope
    if body.plan_action is not None:
        entry["plan_action"] = body.plan_action
    if body.answers is not None:
        entry["answers"] = body.answers
    entry["event"].set()

    answer_count = len(body.answers) if isinstance(body.answers, dict) else 0
    logger.info(
        "Permission responded: task_id=%s, permission_id=%s, decision=%s, answer_count=%d",
        task_id,
        body.permission_id,
        body.decision,
        answer_count,
    )

    return {"task_id": task_id, "permission_id": body.permission_id, "status": "responded"}


@router.post("/query/rewind")
async def rewind_files(body: RewindRequest):
    """Rewind file changes to a specific checkpoint (UserMessage UUID).

    Uses the Claude Agent SDK ``rewind_files`` method when available,
    falling back to the CLI ``--rewind-files`` flag otherwise.
    """

    if not _sdk_available:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="CLAUDE_SDK_UNAVAILABLE",
        )

    cwd = _validate_cwd(body.cwd)

    # Use CLI --rewind-files flag (works with both SDK versions)
    if _use_agent_sdk:
        try:
            cli_path = _find_claude_cli()
            logger.info("Using configured Claude CLI for rewind")
            env = _wrapper._build_env()
            result = subprocess.run(
                [cli_path, "--resume", body.claude_session_id,
                 "--rewind-files", body.checkpoint_id,
                 "--output-format", "json"],
                capture_output=True, text=True, timeout=30,
                cwd=cwd,
                env={**os.environ, **(env or {}),
                     "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING": "1"},
            )

            if result.returncode != 0:
                logger.error("Rewind CLI failed: returncode=%d", result.returncode)
                raise HTTPException(
                    status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                    detail="CLAUDE_REWIND_FAILED",
                )

            logger.info("Rewind successful: session=%s, checkpoint=%s",
                         body.claude_session_id, body.checkpoint_id)
            return {
                "status": "rewound",
                "checkpoint_id": body.checkpoint_id,
                "claude_session_id": body.claude_session_id,
            }

        except subprocess.TimeoutExpired:
            logger.warning("Rewind CLI timed out")
            raise HTTPException(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                detail="CLAUDE_REWIND_TIMEOUT",
            )
        except HTTPException:
            raise
        except Exception as exc:
            logger.error("Rewind failed: type=%s", type(exc).__name__)
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail="CLAUDE_REWIND_FAILED",
            )

    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail="CLAUDE_REWIND_UNSUPPORTED",
    )


@router.post("/query/{task_id}/abort", response_model=AbortResponse)
async def abort_query(
    task_id: str,
    operation: str | None = Header(None, alias=OPERATION_HEADER),
    signature: str | None = Header(None, alias=SIGNATURE_HEADER),
) -> AbortResponse:
    """Request an explicitly-authorized cancellation for a managed query.

    The acknowledgement is intentionally non-terminal: task ownership stays
    active until the SDK/CLI stream provides actual exit evidence.  This route
    never escalates an observation problem into a PID signal; emergency PID
    action is isolated in ``/processes/{pid}/kill`` and requires its own
    ``MANUAL_PID_KILL`` operation.
    """
    capability = verify_termination_capability(
        encoded_operation=operation,
        encoded_signature=signature,
        expected_kind="REMOTE_CANCEL",
        route_task_id=task_id,
    )

    # -- Resolve task (by worker task_id or foggy_task_id) --
    result = _resolve_task_entry(task_id)
    if result is None:
        operation_summary = capability.public_summary(
            operation_status="UNCONFIRMED",
            observed_exit=False,
            result="TASK_NOT_REGISTERED_PENDING_RECONCILIATION",
        )
        logger.warning(
            "Cancellation requested without live registry: task=%s operation_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s; "
            "leaving terminal outcome to reconciler evidence",
            task_id,
            capability.operation_id,
            capability.origin,
            capability.actor_id,
            capability.reason_code,
            capability.correlation_id,
        )
        return AbortResponse(
            task_id=task_id,
            status="CANCEL_REQUESTED",
            operation_id=capability.operation_id,
            attention_state="TASK_NOT_REGISTERED_PENDING_RECONCILIATION",
            attention=[{
                "code": "TASK_NOT_REGISTERED_PENDING_RECONCILIATION",
                "source": "EXPLICIT_TERMINATION_OPERATION",
                "recoverable": True,
            }],
            available_actions=list(_PENDING_DECISION_ACTIONS),
            termination_operation=operation_summary,
        )

    resolved_id, entry = result
    if resolved_id != task_id:
        logger.info(
            "Cancellation operation_id=%s resolved foggy task '%s' to worker task '%s'",
            capability.operation_id,
            task_id,
            resolved_id,
        )

    requested_at = datetime.now(timezone.utc).isoformat()
    operation_summary = capability.public_summary(
        operation_status="CANCEL_REQUESTED",
        observed_exit=False,
    )
    operation_summary["requested_at"] = requested_at
    entry["cancel_requested"] = True
    entry["cancel_requested_at"] = requested_at
    entry["cancel_operation_id"] = capability.operation_id
    entry["execution_state"] = "CANCEL_REQUESTED"
    entry["attention_state"] = "CANCELLATION_PENDING_CONFIRMATION"
    entry["termination_operation"] = operation_summary
    entry["lifecycle_evidence"] = {
        "source": "EXPLICIT_TERMINATION_OPERATION",
        "operation_id": capability.operation_id,
        "origin": capability.origin,
        "reason_code": capability.reason_code,
        "observed_at": requested_at,
    }

    execution_task: asyncio.Task | None = entry.get("producer_task") or entry.get("asyncio_task")
    if execution_task is not None and not execution_task.done():
        # This is the sole managed-task cancellation path: the verified,
        # one-time capability above is explicit authorization.  Do not pop the
        # registry or signal tracked PIDs here; actual process exit remains an
        # independently observed lifecycle transition.
        execution_task.cancel()
    else:
        entry["attention_state"] = "PROCESS_UNVERIFIED"
        operation_summary["status"] = "UNCONFIRMED"
        operation_summary["result"] = "ASYNCIO_TASK_ALREADY_DONE_WITHOUT_TERMINAL_EVIDENCE"
        entry["lifecycle_evidence"] = {
            "source": "EXPLICIT_TERMINATION_OPERATION",
            "operation_id": capability.operation_id,
            "reason": "ASYNCIO_TASK_ALREADY_DONE_WITHOUT_TERMINAL_EVIDENCE",
            "observed_at": requested_at,
        }

    await _emit_lifecycle_event(
        task_id=resolved_id,
        entry=entry,
        subtype="termination_requested",
        data={"operation_id": capability.operation_id, "kind": capability.kind},
    )

    logger.info(
        "Cancellation accepted: task=%s resolved_task=%s operation_id=%s origin=%s actor_id=%s reason_code=%s correlation_id=%s",
        task_id,
        resolved_id,
        capability.operation_id,
        capability.origin,
        capability.actor_id,
        capability.reason_code,
        capability.correlation_id,
    )
    return AbortResponse(
        task_id=task_id,
        status="CANCEL_REQUESTED",
        operation_id=capability.operation_id,
        attention_state=entry.get("attention_state"),
        observed_exit=has_verified_terminal_evidence(entry),
        attention=_attention_payload(entry),
        available_actions=_available_actions(entry),
        lifecycle_state=_lifecycle_state(entry),
        termination_operation=entry.get("termination_operation"),
    )


def _resolve_task_entry(task_id: str) -> tuple[str, dict] | None:
    """Resolve a task by worker task_id or foggy_task_id."""
    entry = task_registry.get(task_id)
    if entry is not None:
        return task_id, entry
    # Fallback: search by foggy_task_id
    for tid, e in list(task_registry.items()):
        if e.get("foggy_task_id") == task_id:
            return tid, e
    return None


@router.get("/tasks/{task_id}/subscribe")
async def subscribe_to_task(
    task_id: str,
    ack_seq: int = 0,
    replay_from: int | None = None,
):
    """Subscribe to an existing task's SSE event stream.

    This endpoint enables reconnection after SSE disconnect or Java restart.
    It creates a new subscriber on the task's ``EventBroadcast``, replaying
    events whose ``seq > ack_seq`` so the caller can catch up on missed events.

    Query parameters:
        ``ack_seq``: last acknowledged sequence number (0 = replay all).
            Java sends its last received seq; Worker replays everything after it.
        ``replay_from``: **deprecated** — old index-based replay parameter.
            Kept for backward compatibility with older Java backends.
    """
    # Backward compatibility: old Java sends replay_from (index), new Java sends ack_seq.
    effective_ack_seq = ack_seq
    if replay_from is not None and ack_seq == 0:
        effective_ack_seq = replay_from

    resolved = _resolve_task_entry(task_id)
    if resolved is None:
        # Match the Codex SDK Worker recovery contract: registry cleanup must
        # not make already-durable terminal evidence unreachable.  Load all
        # events first so an ACK at or beyond latest still produces a valid,
        # empty SSE 200 rather than being confused with a genuinely unknown
        # task.
        from ..persistence.factory import get_event_store

        store = get_event_store()
        resolved_persistence_id = (
            store.resolve_alias(task_id)
            if hasattr(store, "resolve_alias")
            else task_id
        )
        persisted_events = await asyncio.to_thread(
            store.load_events,
            resolved_persistence_id,
            0,
        )
        if not persisted_events:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task '{task_id}' has no replayable events",
            )

        async def _persisted_replay_generator() -> AsyncGenerator[dict, None]:
            for event in persisted_events:
                if event.get("seq", 0) > effective_ack_seq:
                    yield {"event": "message", "data": json.dumps(event)}

        logger.info(
            "Subscribe durable replay: task=%s resolved=%s ack_seq=%d total_events=%d",
            task_id,
            resolved_persistence_id,
            effective_ack_seq,
            len(persisted_events),
        )
        return EventSourceResponse(
            _persisted_replay_generator(),
            media_type="text/event-stream",
            ping=30,
        )

    real_task_id, entry = resolved
    broadcast: EventBroadcast | None = entry.get("broadcast")
    if broadcast is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Task '{task_id}' has no broadcast (non-interactive mode or already cleaned up)",
        )

    # Mark task as reconnected — exits grace period loop in run_query
    entry["connected"] = True
    entry["has_external_subscriber"] = True
    logger.info(
        "Subscribe: task=%s (resolved=%s), ack_seq=%d, total_events=%d, latest_seq=%d",
        task_id, real_task_id, effective_ack_seq, broadcast.event_count, broadcast.latest_seq,
    )

    sub_queue = broadcast.subscribe(ack_seq=effective_ack_seq)

    async def _subscribe_generator() -> AsyncGenerator[dict, None]:
        try:
            while True:
                evt = await sub_queue.get()
                if evt is None:
                    break  # Stream finished
                yield {"event": "message", "data": json.dumps(evt)}
        except asyncio.CancelledError:
            pass
        finally:
            broadcast.unsubscribe(sub_queue)
            if broadcast.closed and not broadcast._subscribers:
                # A closed transport stream and a completed producer do not
                # establish process exit.  Release registry/PID ownership only
                # after an explicit terminal event was recorded.
                from ..claude.process_detection import get_pids_for_task, is_cli_process, unregister_pids_for_task

                live_pids = [pid for pid in get_pids_for_task(real_task_id) if is_cli_process(pid)]
                reg_entry = task_registry.get(real_task_id)
                producer_task: asyncio.Task | None = (
                    reg_entry.get("producer_task") if reg_entry else None
                )
                producer_done = producer_task is None or producer_task.done()
                if not has_verified_terminal_evidence(reg_entry):
                    if reg_entry:
                        reg_entry["execution_state"] = (
                            "CANCEL_REQUESTED"
                            if reg_entry.get("cancel_requested")
                            else "ACTIVE_TASK_EXECUTION"
                        )
                        reg_entry["attention_state"] = "PROCESS_UNVERIFIED"
                        reg_entry["lifecycle_evidence"] = {
                            "source": "CLOSED_SSE_WITHOUT_VERIFIED_TERMINAL_EVENT",
                            "live_pid_count": len(live_pids),
                            "producer_done": producer_done,
                            "action": "RETAINED_NO_AUTOMATIC_RELEASE",
                        }
                    logger.warning(
                        "Subscribe cleanup retained task %s without verified terminal evidence "
                        "(producer_done=%s, tracked_cli_pids=%d)",
                        real_task_id,
                        producer_done,
                        len(live_pids),
                    )
                else:
                    cleaned = task_registry.get(real_task_id)
                    if cleaned:
                        unregister_pids_for_task(real_task_id)
                        task_registry.pop(real_task_id, None)
                        logger.info("Subscribe cleanup: released task %s after verified terminal event", real_task_id)
                    for permission_id in list(permission_pending):
                        if permission_pending[permission_id].get("task_id") == real_task_id:
                            permission_pending.pop(permission_id, None)
            elif not broadcast.closed and not broadcast._subscribers:
                # SSE disconnected again but producer still alive.
                # Mark as disconnected so Java side knows to reconnect again.
                reg_entry = task_registry.get(real_task_id)
                if reg_entry:
                    reg_entry["connected"] = False
                    logger.warning(
                        "Subscribe: task %s SSE disconnected again (no subscribers), "
                        "marked disconnected. Producer still alive — Reconciler will handle.",
                        real_task_id,
                    )

    return EventSourceResponse(
        _subscribe_generator(),
        media_type="text/event-stream",
        ping=30,
    )


@router.get("/tasks/{task_id}/status")
async def get_task_status(task_id: str):
    """Query the Worker's real-time task state.

    Java Reconciler calls this endpoint periodically to detect seq gaps
    (Worker has more events than Java received) and trigger auto-reconnect.

    Also useful after Java restart to determine whether to replay events.

    Returns:
        task_id: The resolved task ID
        latest_seq: Latest event sequence number
        event_count: Total events produced
        closed: Whether the event stream has ended
        cli_alive: Whether there are live CLI processes for this task
        has_subscribers: Whether any SSE subscribers are connected
        source: "registry" (live task) or "persistence" (durably replayable task)
    """
    resolved = _resolve_task_entry(task_id)

    if resolved is not None:
        # Task is still live in registry
        real_task_id, entry = resolved
        broadcast: EventBroadcast | None = entry.get("broadcast")

        # The original request task may have detached after an SSE disconnect
        # while a queue producer continues to own the CLI. Never report that
        # observation as a terminal transition by itself.
        asyncio_task = entry.get("asyncio_task")
        producer_task = entry.get("producer_task")
        cli_alive = bool(
            (asyncio_task is not None and not asyncio_task.done())
            or (producer_task is not None and not producer_task.done())
        )

        return {
            "task_id": real_task_id,
            "latest_seq": broadcast.latest_seq if broadcast else 0,
            "event_count": broadcast.event_count if broadcast else 0,
            "closed": broadcast.closed if broadcast else True,
            "cli_alive": cli_alive,
            "has_subscribers": len(broadcast._subscribers) > 0 if broadcast else False,
            "connected": entry.get("connected", False),
            "execution_state": entry.get("execution_state", "ACTIVE_TASK_EXECUTION"),
            "attention_state": entry.get("attention_state"),
            "attention": _attention_payload(entry),
            "attention_status": entry.get("attention_state"),
            "available_actions": _available_actions(entry),
            "lifecycle_state": _lifecycle_state(entry),
            "termination_operation": entry.get("termination_operation"),
            "cancel_requested": bool(entry.get("cancel_requested")),
            "terminal_observed": has_verified_terminal_evidence(entry),
            "terminal_status": (
                entry.get("terminal_status") if has_verified_terminal_evidence(entry) else None
            ),
            "terminal_source": (
                entry.get("terminal_source") if has_verified_terminal_evidence(entry) else None
            ),
            "lifecycle_evidence": entry.get("lifecycle_evidence"),
            "source": "registry",
        }

    # Task not in registry — inspect the durable replay store.  Its closed
    # marker only describes stream transport; terminal status comes solely
    # from an explicitly marked provider/verified-exit event.
    # Resolve alias (foggy_task_id → worker task_id) since JSONL is stored
    # under worker task_id, not foggy_task_id.
    from ..persistence.factory import get_event_store
    store = get_event_store()
    resolved_persistence_id = (
        store.resolve_alias(task_id)
        if hasattr(store, "resolve_alias")
        else task_id
    )
    latest_seq = store.get_latest_seq(resolved_persistence_id)
    is_closed = store.is_closed(resolved_persistence_id)

    if latest_seq > 0 or is_closed:
        terminal_evidence = _load_persisted_terminal_evidence(store, resolved_persistence_id)
        if terminal_evidence is not None:
            terminal_status = terminal_evidence["terminal_status"]
            terminal_source = terminal_evidence["terminal_source"]
            return {
                "task_id": task_id,
                "latest_seq": latest_seq,
                "event_count": latest_seq,  # approximate (seq is 1-based monotonic)
                "closed": is_closed,
                # The persistence store does not perform a live PID probe.
                "cli_alive": None,
                "has_subscribers": False,
                "connected": False,
                "execution_state": "TERMINAL_OBSERVED",
                "attention_state": None,
                "attention": [],
                "attention_status": None,
                "available_actions": [],
                "lifecycle_state": terminal_status,
                "termination_operation": None,
                "cancel_requested": False,
                "terminal_observed": True,
                "terminal_status": terminal_status,
                "terminal_source": terminal_source,
                "lifecycle_evidence": {
                    "source": terminal_source,
                    "durable": True,
                    "event_seq": terminal_evidence["event_seq"],
                },
                "source": "persistence",
            }

        return {
            "task_id": task_id,
            "latest_seq": latest_seq,
            "event_count": latest_seq,  # approximate (seq is 1-based monotonic)
            "closed": is_closed,
            # A replay store cannot establish that a managed CLI is no longer
            # alive.  Keep that observation unknown rather than infer it from
            # a missing registry entry or a closed SSE stream.
            "cli_alive": None,
            "has_subscribers": False,
            "connected": False,
            "execution_state": "ACTIVE_TASK_EXECUTION",
            "attention_state": "PROCESS_UNVERIFIED",
            "attention": [{
                "code": "PROCESS_UNVERIFIED",
                "source": "EVENT_STORE",
                "recoverable": True,
            }],
            "attention_status": "PROCESS_UNVERIFIED",
            "available_actions": list(_PENDING_DECISION_ACTIONS),
            "lifecycle_state": "RUNNING",
            "termination_operation": None,
            "cancel_requested": False,
            "terminal_observed": False,
            "terminal_status": None,
            "terminal_source": None,
            "lifecycle_evidence": {
                "source": "EVENT_STORE",
                "closed": is_closed,
                "reason": "NO_VERIFIED_TERMINAL_EVENT",
            },
            "source": "persistence",
        }

    raise HTTPException(
        status_code=status.HTTP_404_NOT_FOUND,
        detail=f"Task '{task_id}' not found in registry or persistence store",
    )
