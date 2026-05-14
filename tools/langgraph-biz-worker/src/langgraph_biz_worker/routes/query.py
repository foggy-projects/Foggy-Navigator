"""Query endpoint — accepts a business prompt and streams SSE events."""

from __future__ import annotations

import json
import logging
import asyncio
import uuid
from collections.abc import AsyncGenerator
from typing import Any

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

_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"


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

        queue: asyncio.Queue[Any] = asyncio.Queue()
        emitted_keys: set[str] = set()
        loop = asyncio.get_running_loop()

        def enqueue_progress_event(event: QueryEvent) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, event)

        runtime_context = dict(request.runtime_context or {})
        runtime_context[_PROGRESS_EVENT_SINK_KEY] = enqueue_progress_event

        initial_state: RootState = {
            "task_id": task_id,
            "session_id": session_id,
            "prompt": request.prompt,
            "model": request.model,
            "llm_config": request.llm_config,
            "context": request.context,
            "runtime_context": runtime_context,
            "user_id": request.user_id,
            "tenant_id": request.tenant_id,
            "events": [],
            "started_at": time.time(),
            "active_frame_id": None,
            "skill_results": [],
        }

        def run_graph() -> None:
            try:
                result = root_graph.invoke(initial_state)
                for event in result.get("events", []):
                    enqueue_progress_event(event)
            except Exception as exc:
                loop.call_soon_threadsafe(queue.put_nowait, exc)
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, None)

        graph_task = asyncio.create_task(asyncio.to_thread(run_graph))

        while True:
            item = await queue.get()
            if item is None:
                break
            if isinstance(item, BaseException):
                raise item
            event_key = item.model_dump_json()
            if event_key in emitted_keys:
                continue
            emitted_keys.add(event_key)
            yield {
                "event": "message",
                "data": item.model_dump_json(),
            }
        await graph_task

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
