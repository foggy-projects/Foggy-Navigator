"""Query endpoint — accepts a business prompt and streams SSE events."""

from __future__ import annotations

import logging
import asyncio
import uuid
from collections.abc import AsyncGenerator
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from sse_starlette.sse import EventSourceResponse

from ..auth import verify_token
from ..config import settings
from ..graphs.root_graph import RootState, enqueue_pending_user_input_for_context, root_graph
from ..models import QueryEvent, QueryRequest
from ..runtime.context_memory import context_execution_lock
from ..runtime.execution_policy import copy_execution_policy_from_context, strip_execution_policy_context
from ..runtime.file_layout import generate_standard_context_id, require_standard_context_id
from ..runtime.fsscript_bridge import extract_fsscript_script, get_fsscript_bridge
from .health import active_tasks

import time

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["query"], dependencies=[Depends(verify_token)])

_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"


def _resolve_session_id(request: QueryRequest) -> str | None:
    """Prefer Navigator's platform session id over provider-specific session ids."""
    return request.foggy_session_id or request.session_id


def _resolve_context_id(request: QueryRequest) -> str:
    """Return the BizWorker-owned conversation context id for this query."""
    context = request.context or {}
    candidate = (
        request.context_id
        or context.get("contextId")
        or context.get("context_id")
        or context.get("conversationId")
        or context.get("conversation_id")
    )
    if isinstance(candidate, str) and candidate.strip():
        return require_standard_context_id(candidate)
    return generate_standard_context_id()


def _context_with_context_id(context: dict[str, Any] | None, context_id: str) -> dict[str, Any]:
    updated = dict(context or {})
    updated["contextId"] = context_id
    updated["context_id"] = context_id
    return updated


def _event_with_context_id(event: QueryEvent, context_id: str) -> QueryEvent:
    if not event.session_id:
        event.session_id = context_id
    if event.type == "system":
        payload = dict(event.payload or {})
        payload.setdefault("contextId", context_id)
        payload.setdefault("context_id", context_id)
        event.payload = payload
    return event


async def _event_generator(
    task_id: str,
    request: QueryRequest,
) -> AsyncGenerator[dict, None]:
    """Run the root graph and yield SSE events."""
    active_tasks.add(task_id)
    context_id: str | None = None
    context_lock = None
    context_lock_acquired = False
    try:
        navigator_session_id = _resolve_session_id(request)
        context_id = _resolve_context_id(request)
        request_context = _context_with_context_id(request.context, context_id)
        fsscript = extract_fsscript_script(request_context)
        if fsscript is not None:
            async for event in get_fsscript_bridge().stream_events(
                task_id=task_id,
                session_id=context_id,
                script=fsscript,
                context=request_context,
                user_id=request.user_id,
                tenant_id=request.tenant_id,
            ):
                event = _event_with_context_id(event, context_id)
                yield {
                    "event": "message",
                    "data": event.model_dump_json(),
                }
            return

        context_lock = context_execution_lock(context_id)
        if not context_lock.acquire(blocking=False):
            busy_event = _event_with_context_id(
                enqueue_pending_user_input_for_context(
                    context_id,
                    task_id=task_id,
                    prompt=request.prompt,
                ),
                context_id,
            )
            yield {
                "event": "message",
                "data": busy_event.model_dump_json(),
            }
            return
        context_lock_acquired = True

        queue: asyncio.Queue[Any] = asyncio.Queue()
        emitted_keys: set[str] = set()
        loop = asyncio.get_running_loop()

        def enqueue_progress_event(event: QueryEvent) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, event)

        visible_context = strip_execution_policy_context(request_context)
        if navigator_session_id:
            visible_context.setdefault("navigatorSessionId", navigator_session_id)
            visible_context.setdefault("navigator_session_id", navigator_session_id)
        runtime_context = copy_execution_policy_from_context(
            request.runtime_context or {},
            request_context,
        )
        if request.task_deadline_at:
            runtime_context.setdefault("task_deadline_at", request.task_deadline_at)
        if request.task_timeout_ms is not None:
            runtime_context.setdefault("task_timeout_ms", request.task_timeout_ms)
        if request.max_turns is not None and request.max_turns > 0:
            runtime_context.setdefault("max_turns", request.max_turns)
        runtime_context[_PROGRESS_EVENT_SINK_KEY] = enqueue_progress_event

        initial_state: RootState = {
            "task_id": task_id,
            "session_id": context_id,
            "prompt": request.prompt,
            "model": request.model,
            "model_config_id": request.model_config_id,
            "llm_config": request.llm_config,
            "vision_llm_config": request.vision_llm_config,
            "context": visible_context,
            "skill_name": request.skill_name,
            "runtime_context": runtime_context,
            "attachments": request.attachments,
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
            item = _event_with_context_id(item, context_id)
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
        if context_id is not None:
            error_event = _event_with_context_id(error_event, context_id)
        yield {
            "event": "message",
            "data": error_event.model_dump_json(),
        }
    finally:
        if context_lock_acquired and context_lock is not None:
            context_lock.release()
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


@router.post("/contexts")
async def allocate_context() -> dict[str, str]:
    """Allocate a BizWorker-owned conversation context id."""
    return {"contextId": generate_standard_context_id()}
