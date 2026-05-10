"""Query endpoint — accepts a business prompt and streams SSE events."""

from __future__ import annotations

import json
import logging
import asyncio
import uuid
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Depends, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..config import settings
from ..graphs.root_graph import RootState, root_graph
from ..models import QueryEvent, QueryRequest
from ..runtime.fsscript_bridge import extract_fsscript_script, get_fsscript_bridge
from .health import active_tasks

import time

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["query"], dependencies=[Depends(verify_token)])


def _resolve_session_id(request: QueryRequest) -> str | None:
    """Prefer Navigator's platform session id over provider-specific session ids."""
    return request.foggy_session_id or request.session_id


async def _event_generator(
    task_id: str,
    request: QueryRequest,
) -> AsyncGenerator[dict, None]:
    """Run the root graph and yield SSE events."""
    active_tasks.add(task_id)
    try:
        session_id = _resolve_session_id(request)
        fsscript = extract_fsscript_script(request.context)
        if fsscript is not None:
            async for event in get_fsscript_bridge().stream_events(
                task_id=task_id,
                session_id=session_id,
                script=fsscript,
                context=request.context,
                user_id=request.user_id,
                tenant_id=request.tenant_id,
            ):
                yield {
                    "event": "message",
                    "data": event.model_dump_json(),
                }
            return

        initial_state: RootState = {
            "task_id": task_id,
            "session_id": session_id,
            "prompt": request.prompt,
            "model": request.model,
            "context": request.context,
            "runtime_context": request.runtime_context,
            "user_id": request.user_id,
            "tenant_id": request.tenant_id,
            "events": [],
            "started_at": time.time(),
            "active_frame_id": None,
            "skill_results": [],
        }

        result = await asyncio.to_thread(root_graph.invoke, initial_state)

        for event in result.get("events", []):
            yield {
                "event": "message",
                "data": event.model_dump_json(),
            }

    except Exception as exc:
        logger.exception("Error processing query %s", task_id)
        error_event = QueryEvent(
            type="error",
            task_id=task_id,
            error=str(exc),
        )
        yield {
            "event": "message",
            "data": error_event.model_dump_json(),
        }
    finally:
        active_tasks.discard(task_id)


@router.post("/query")
async def query(request: QueryRequest) -> EventSourceResponse:
    """Accept a business query and stream results as SSE events."""
    if len(active_tasks) >= settings.max_concurrent_tasks:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"Max concurrent tasks ({settings.max_concurrent_tasks}) reached",
        )

    task_id = request.task_id or str(uuid.uuid4())

    logger.info("Starting query task_id=%s prompt=%s", task_id, request.prompt[:100])

    return EventSourceResponse(
        _event_generator(task_id, request),
        media_type="text/event-stream",
        ping=30,
    )
