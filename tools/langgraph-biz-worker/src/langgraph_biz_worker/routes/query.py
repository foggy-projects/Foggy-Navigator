"""Query endpoint — accepts a business prompt and streams SSE events."""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Depends, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..config import settings
from ..graphs.root_graph import RootState, root_graph
from ..models import QueryEvent, QueryRequest
from .health import active_tasks

import time

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["query"], dependencies=[Depends(verify_token)])


async def _event_generator(
    task_id: str,
    request: QueryRequest,
) -> AsyncGenerator[dict, None]:
    """Run the root graph and yield SSE events."""
    active_tasks.add(task_id)
    try:
        initial_state: RootState = {
            "task_id": task_id,
            "session_id": request.session_id,
            "prompt": request.prompt,
            "model": request.model,
            "context": request.context,
            "events": [],
            "started_at": time.time(),
        }

        result = root_graph.invoke(initial_state)

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
