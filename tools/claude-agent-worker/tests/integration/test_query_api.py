"""Integration tests for the core query API:

  POST /api/v1/query          — start query, stream SSE
  POST /api/v1/query/{id}/abort — cancel running task
"""

from __future__ import annotations

import asyncio
from unittest.mock import patch

import pytest
from httpx import AsyncClient

from agent_worker.claude.sdk_wrapper import task_registry
from agent_worker.config import settings

from .conftest import collect_sse_events


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _query_body(**overrides) -> dict:
    """Build a minimal valid QueryRequest payload."""
    base = {"prompt": "Say hello", "cwd": "."}
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# Tests — SSE delivery
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestQuerySSEDelivery:
    """POST /api/v1/query streams SSE events from the SDK wrapper."""

    async def test_basic_query_returns_sse_events(self, client: AsyncClient):
        """A mocked run_query yielding two events should produce two SSE messages."""
        from agent_worker.routes.query import _wrapper

        async def _patched_run_query(**kwargs):
            tid = kwargs["task_id"]
            yield {"type": "assistant_text", "task_id": tid, "content": "Hello world"}
            yield {"type": "result", "task_id": tid, "session_id": "sess-1",
                   "duration_ms": 100, "input_tokens": 10, "output_tokens": 5}

        with patch.object(_wrapper, "run_query", side_effect=_patched_run_query):
            events = await collect_sse_events(
                client, "POST", "/api/v1/query",
                json_body=_query_body(),
            )

        assert len(events) == 2
        assert events[0]["type"] == "assistant_text"
        assert events[0]["content"] == "Hello world"
        assert events[1]["type"] == "result"

    async def test_error_in_generator_yields_error_event(self, client: AsyncClient):
        """If the SDK wrapper raises, _event_generator catches it and yields an error event."""
        from agent_worker.routes.query import _wrapper

        async def _failing_run_query(**kwargs):
            raise RuntimeError("SDK exploded")
            yield  # noqa: unreachable — makes this an async generator

        with patch.object(_wrapper, "run_query", side_effect=_failing_run_query):
            events = await collect_sse_events(
                client, "POST", "/api/v1/query",
                json_body=_query_body(),
            )

        assert len(events) >= 1
        error_evt = events[-1]
        assert error_evt["type"] == "error"
        assert "SDK exploded" in error_evt["error"]

    async def test_empty_prompt_rejected(self, client: AsyncClient):
        """Missing prompt field should return 422 validation error."""
        resp = await client.post("/api/v1/query", json={"cwd": "."})
        assert resp.status_code == 422


# ---------------------------------------------------------------------------
# Tests — Max concurrent tasks
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestMaxConcurrentTasks:
    """The query endpoint rejects new tasks when the limit is reached."""

    async def test_429_when_at_capacity(self, client: AsyncClient):
        """Fill task_registry to max, verify new query returns 429."""
        tasks: list[asyncio.Task] = []
        with patch.object(settings, "max_concurrent_tasks", 2):
            for i in range(2):
                t = asyncio.ensure_future(asyncio.sleep(999))
                tasks.append(t)
                task_registry[f"task-{i}"] = {
                    "asyncio_task": t,
                    "foggy_task_id": None,
                }

            try:
                resp = await client.post("/api/v1/query", json=_query_body())
                assert resp.status_code == 429
                assert "Maximum concurrent tasks" in resp.json()["detail"]
            finally:
                for t in tasks:
                    t.cancel()

    async def test_stale_tasks_purged_before_reject(self, client: AsyncClient):
        """When max is reached but a task's asyncio.Task is done, it gets purged."""
        with patch.object(settings, "max_concurrent_tasks", 1):
            # Create a finished (done) task
            done_task = asyncio.ensure_future(asyncio.sleep(0))
            await asyncio.sleep(0.05)  # let it complete
            task_registry["stale-task"] = {
                "asyncio_task": done_task,
                "foggy_task_id": None,
            }

            from agent_worker.routes.query import _wrapper

            async def _quick_run(**kwargs):
                yield {"type": "result", "task_id": kwargs["task_id"]}

            with (
                patch.object(_wrapper, "run_query", side_effect=_quick_run),
                patch("agent_worker.routes.query._find_sdk_cli_pids", return_value=set()),
            ):
                events = await collect_sse_events(
                    client, "POST", "/api/v1/query",
                    json_body=_query_body(),
                )

            assert len(events) >= 1
            assert "stale-task" not in task_registry


# ---------------------------------------------------------------------------
# Tests — Abort
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestAbortQuery:
    """POST /api/v1/query/{task_id}/abort cancels running tasks."""

    async def test_abort_running_task(self, client: AsyncClient):
        """Abort a task that is still running (asyncio task not done)."""
        running = asyncio.ensure_future(asyncio.sleep(999))
        task_registry["t-abc"] = {
            "asyncio_task": running,
            "foggy_task_id": "foggy-001",
        }

        resp = await client.post("/api/v1/query/t-abc/abort")
        assert resp.status_code == 200
        body = resp.json()
        assert body["task_id"] == "t-abc"
        assert body["status"] == "cancelled"
        # Give event loop time to process the cancellation
        await asyncio.sleep(0.05)
        assert running.cancelled()
        assert "t-abc" not in task_registry

    async def test_abort_by_foggy_task_id(self, client: AsyncClient):
        """Abort using the foggy_task_id (Java backend's ID)."""
        running = asyncio.ensure_future(asyncio.sleep(999))
        task_registry["w-id-123"] = {
            "asyncio_task": running,
            "foggy_task_id": "20250306-xyz",
        }

        resp = await client.post("/api/v1/query/20250306-xyz/abort")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "cancelled"
        await asyncio.sleep(0.05)
        assert running.cancelled()

    async def test_abort_nonexistent_is_idempotent(self, client: AsyncClient):
        """Aborting a task that doesn't exist returns success (idempotent)."""
        resp = await client.post("/api/v1/query/no-such-task/abort")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "cancelled"

    async def test_abort_already_done_task(self, client: AsyncClient):
        """Abort a task whose asyncio.Task is already done."""
        done = asyncio.ensure_future(asyncio.sleep(0))
        await asyncio.sleep(0.05)
        task_registry["t-done"] = {
            "asyncio_task": done,
            "foggy_task_id": None,
        }

        resp = await client.post("/api/v1/query/t-done/abort")
        assert resp.status_code == 200
        assert "t-done" not in task_registry


# ---------------------------------------------------------------------------
# Tests — CWD validation
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestCwdValidation:
    """POST /api/v1/query validates cwd against allowed_cwds."""

    async def test_disallowed_cwd_returns_403(self, client: AsyncClient):
        """When allowed_cwds is set, a path outside it is rejected."""
        with patch.object(settings, "allowed_cwds", ["C:/allowed-project"]):
            resp = await client.post(
                "/api/v1/query",
                json=_query_body(cwd="C:/forbidden-project"),
            )
            assert resp.status_code == 403
            assert "not in the allowed list" in resp.json()["detail"]
