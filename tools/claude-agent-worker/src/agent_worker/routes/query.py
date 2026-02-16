from __future__ import annotations

import asyncio
import json
import logging
import os
import uuid
from typing import AsyncGenerator

from fastapi import APIRouter, Depends, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..claude.sdk_wrapper import SdkWrapper, task_registry
from ..config import settings
from ..models import AbortResponse, QueryEvent, QueryRequest

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

    resolved = os.path.realpath(cwd)

    if not settings.allowed_cwds:
        return resolved

    for allowed in settings.allowed_cwds:
        allowed_resolved = os.path.realpath(allowed)
        # Accept *resolved* itself or any sub-directory of an allowed root.
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved + os.sep):
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

    return EventSourceResponse(
        _event_generator(
            task_id=task_id,
            prompt=body.prompt,
            cwd=cwd,
            session_id=body.session_id,
            max_turns=body.max_turns,
            model=body.model,
        ),
        media_type="text/event-stream",
    )


@router.post("/query/{task_id}/abort", response_model=AbortResponse)
async def abort_query(task_id: str) -> AbortResponse:
    """Cancel a running query by its ``task_id``.

    If the task is still active its underlying asyncio task will be cancelled
    and cleaned up.
    """

    entry = task_registry.get(task_id)

    if entry is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Task '{task_id}' not found or already finished",
        )

    asyncio_task: asyncio.Task | None = entry.get("asyncio_task")
    if asyncio_task is not None and not asyncio_task.done():
        asyncio_task.cancel()

    task_registry.pop(task_id, None)

    return AbortResponse(task_id=task_id, status="cancelled")
