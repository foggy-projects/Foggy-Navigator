from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import uuid
from typing import AsyncGenerator

from fastapi import APIRouter, Depends, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..claude.sdk_wrapper import SdkWrapper, task_registry, permission_pending, _sdk_available, _use_agent_sdk, EventBroadcast
from ..config import settings
from ..models import AbortResponse, PermissionResponse, QueryEvent, QueryRequest, RewindRequest

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["query"], dependencies=[Depends(verify_token)])

_wrapper = SdkWrapper()


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
        # Accept *resolved* itself or any sub-directory of an allowed root.
        # rstrip(os.sep) avoids double-sep when allowed is a drive root like "D:\"
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved.rstrip(os.sep) + os.sep):
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
    disallowed_tools: list[str] | None = None,
    foggy_task_id: str | None = None,
    foggy_session_id: str | None = None,
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
            disallowed_tools=disallowed_tools,
            foggy_task_id=foggy_task_id,
            foggy_session_id=foggy_session_id,
        ):
            yield {"event": "message", "data": json.dumps(event)}
    except asyncio.CancelledError:
        cancel_event = QueryEvent(
            type="error",
            task_id=task_id,
            error="Task was cancelled",
        )
        yield {"event": "message", "data": cancel_event.model_dump_json()}
    except Exception as exc:
        logger.exception("Unexpected error in task %s", task_id)
        error_event = QueryEvent(
            type="error",
            task_id=task_id,
            error=str(exc),
        )
        yield {"event": "message", "data": error_event.model_dump_json()}


@router.post("/query")
async def query(body: QueryRequest):
    """Start a Claude Code query and stream results as SSE events.

    Each event uses the event name ``message`` and carries a JSON-serialised
    :class:`QueryEvent` as its ``data`` field.
    """

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
            disallowed_tools=body.disallowed_tools,
            foggy_task_id=body.foggy_task_id,
            foggy_session_id=body.foggy_session_id,
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

    logger.info(
        "Permission responded: task_id=%s, permission_id=%s, decision=%s",
        task_id, body.permission_id, body.decision,
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
            detail="Claude Code SDK is not installed",
        )

    cwd = _validate_cwd(body.cwd)

    # Use CLI --rewind-files flag (works with both SDK versions)
    if _use_agent_sdk:
        try:
            env = _wrapper._build_env()
            result = subprocess.run(
                ["claude", "--resume", body.claude_session_id,
                 "--rewind-files", body.checkpoint_id,
                 "--output-format", "json"],
                capture_output=True, text=True, timeout=30,
                cwd=cwd,
                env={**os.environ, **(env or {}),
                     "CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING": "1"},
            )

            if result.returncode != 0:
                logger.error("Rewind CLI failed: returncode=%d, stderr=%s",
                             result.returncode, result.stderr[:500] if result.stderr else None)
                raise HTTPException(
                    status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                    detail=f"Rewind failed: {result.stderr or 'unknown error'}",
                )

            logger.info("Rewind successful: session=%s, checkpoint=%s",
                         body.claude_session_id, body.checkpoint_id)
            return {
                "status": "rewound",
                "checkpoint_id": body.checkpoint_id,
                "claude_session_id": body.claude_session_id,
            }

        except subprocess.TimeoutExpired:
            raise HTTPException(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                detail="Rewind timed out (30s)",
            )
        except HTTPException:
            raise
        except Exception as exc:
            logger.exception("Rewind error: %s", exc)
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=f"Rewind failed: {exc}",
            )

    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail="Rewind requires claude-agent-sdk",
    )


@router.post("/query/{task_id}/abort", response_model=AbortResponse)
async def abort_query(task_id: str) -> AbortResponse:
    """Cancel a running query by its ``task_id``.

    Accepts either the Worker's internal UUID *or* the Foggy platform
    ``foggy_task_id`` (injected at task creation).  The latter allows the
    Java backend to abort tasks using its own task ID without maintaining
    a separate ID-mapping table.

    If the task is still active its underlying asyncio task will be cancelled
    and cleaned up.
    """

    entry = task_registry.pop(task_id, None)

    if entry is None:
        # Fallback: search by foggy_task_id so the Java backend can abort
        # using its own task ID (YYYYMMDD-xxxx format) without needing the
        # Worker's internal UUID.
        resolved_id = None
        for tid, e in list(task_registry.items()):
            if e.get("foggy_task_id") == task_id:
                resolved_id = tid
                break
        if resolved_id is not None:
            entry = task_registry.pop(resolved_id, None)
            logger.info("Abort: resolved foggy_task_id '%s' → worker task_id '%s'", task_id, resolved_id)
        else:
            # Task already finished or was never registered — treat as idempotent success
            logger.info("Abort called for task '%s' but already gone from registry", task_id)
            return AbortResponse(task_id=task_id, status="cancelled")

    asyncio_task: asyncio.Task | None = entry.get("asyncio_task")
    if asyncio_task is not None and not asyncio_task.done():
        asyncio_task.cancel()

    return AbortResponse(task_id=task_id, status="cancelled")


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
async def subscribe_to_task(task_id: str, replay_from: int = 0):
    """Subscribe to an existing task's SSE event stream.

    This endpoint enables reconnection after SSE disconnect or Java restart.
    It creates a new subscriber on the task's ``EventBroadcast``, optionally
    replaying events from a given index so the caller can catch up on missed
    events.

    Query parameters:
        ``replay_from``: event index to replay from (0 = all events)
    """
    resolved = _resolve_task_entry(task_id)
    if resolved is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Task '{task_id}' not found in registry (CLI may have exited)",
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
    logger.info(
        "Subscribe: task=%s (resolved=%s), replay_from=%d, total_events=%d",
        task_id, real_task_id, replay_from, broadcast.event_count,
    )

    sub_queue = broadcast.subscribe(replay_from=replay_from)

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
                # Producer finished and no subscribers left — clean up registry.
                cleaned = task_registry.pop(real_task_id, None)
                if cleaned:
                    logger.info("Subscribe cleanup: removed task %s from registry (last subscriber)", real_task_id)
                for pid in list(permission_pending):
                    if permission_pending[pid].get("task_id") == real_task_id:
                        permission_pending.pop(pid, None)
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
