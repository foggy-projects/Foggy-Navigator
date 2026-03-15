"""Integration tests for the core query API:

  POST /api/v1/query          — start query, stream SSE
  POST /api/v1/query/{id}/abort — cancel running task
"""

from __future__ import annotations

import asyncio
import os
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient

from agent_worker.claude.process_detection import _tracked_pids, register_pid
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
# Regression: Abort concurrent tasks — only target PIDs are killed
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestAbortConcurrentTaskIsolation:
    """Regression tests: aborting task-A must NOT kill task-B's CLI processes.

    Root cause: _capture_child_pids() scanned ALL children and blindly
    re-registered them, so all PIDs ended up under the last-scanned task.
    Aborting any task would then kill all CLIs.

    The ownership guard in register_pid() fixes this by preventing
    cross-task PID re-registration (first-come-first-served).
    """

    def setup_method(self):
        _tracked_pids.clear()

    def teardown_method(self):
        _tracked_pids.clear()

    async def test_abort_task_a_does_not_kill_task_b_pids(self, client: AsyncClient):
        """Two concurrent tasks: aborting task-A kills only task-A's PIDs."""
        # Setup: two running tasks with distinct PIDs
        running_a = asyncio.ensure_future(asyncio.sleep(999))
        running_b = asyncio.ensure_future(asyncio.sleep(999))

        task_registry["worker-A"] = {
            "asyncio_task": running_a,
            "foggy_task_id": "foggy-A",
        }
        task_registry["worker-B"] = {
            "asyncio_task": running_b,
            "foggy_task_id": "foggy-B",
        }

        # Register PIDs with correct ownership
        register_pid(1000, "worker-A")
        register_pid(2000, "worker-B")

        # Simulate _capture_child_pids cross-registration attempt (the bug)
        register_pid(2000, "worker-A")  # task-A tries to steal task-B's PID
        register_pid(1000, "worker-B")  # task-B tries to steal task-A's PID

        # Verify ownership guard worked
        assert _tracked_pids[1000] == "worker-A"
        assert _tracked_pids[2000] == "worker-B"

        # Abort task-A — mock is_cli_process and os.kill
        killed_pids: list[int] = []

        def mock_kill(pid, sig):
            killed_pids.append(pid)

        with (
            patch("agent_worker.claude.process_detection.is_cli_process", return_value=True),
            patch("os.kill", side_effect=mock_kill),
        ):
            resp = await client.post("/api/v1/query/worker-A/abort")

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "cancelled"

        # Only task-A's PID (1000) should be killed
        assert 1000 in killed_pids
        assert 2000 not in killed_pids

        # Task-B should still be in the registry and running
        assert "worker-B" in task_registry
        assert not running_b.cancelled()

        # Cleanup
        running_b.cancel()

    async def test_abort_by_foggy_id_isolates_pids(self, client: AsyncClient):
        """Aborting by foggy_task_id also correctly isolates PIDs."""
        running_x = asyncio.ensure_future(asyncio.sleep(999))
        running_y = asyncio.ensure_future(asyncio.sleep(999))

        task_registry["worker-X"] = {
            "asyncio_task": running_x,
            "foggy_task_id": "foggy-task-X",
        }
        task_registry["worker-Y"] = {
            "asyncio_task": running_y,
            "foggy_task_id": "foggy-task-Y",
        }

        register_pid(5000, "worker-X")
        register_pid(5001, "worker-X")  # task-X has two CLIs
        register_pid(6000, "worker-Y")

        killed_pids: list[int] = []

        def mock_kill(pid, sig):
            killed_pids.append(pid)

        with (
            patch("agent_worker.claude.process_detection.is_cli_process", return_value=True),
            patch("os.kill", side_effect=mock_kill),
        ):
            resp = await client.post("/api/v1/query/foggy-task-X/abort")

        assert resp.status_code == 200

        # Both of task-X's PIDs killed
        assert 5000 in killed_pids
        assert 5001 in killed_pids
        # task-Y's PID untouched
        assert 6000 not in killed_pids

        # task-Y still alive
        assert "worker-Y" in task_registry

        # Cleanup
        running_y.cancel()

    async def test_abort_skips_non_cli_pids(self, client: AsyncClient):
        """Abort skips PIDs that are no longer CLI processes (PID reuse)."""
        running = asyncio.ensure_future(asyncio.sleep(999))
        task_registry["worker-Z"] = {
            "asyncio_task": running,
            "foggy_task_id": "foggy-Z",
        }
        register_pid(9000, "worker-Z")

        killed_pids: list[int] = []

        def mock_kill(pid, sig):
            killed_pids.append(pid)

        # PID 9000 is no longer a CLI process (PID reused by python.exe)
        with (
            patch("agent_worker.claude.process_detection.is_cli_process", return_value=False),
            patch("os.kill", side_effect=mock_kill),
        ):
            resp = await client.post("/api/v1/query/worker-Z/abort")

        assert resp.status_code == 200
        assert 9000 not in killed_pids  # skipped because is_cli_process=False


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
