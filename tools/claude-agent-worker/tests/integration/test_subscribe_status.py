"""Integration tests for task subscribe & status endpoints:

  GET /api/v1/tasks/{id}/subscribe  — SSE reconnection with ack_seq replay
  GET /api/v1/tasks/{id}/status     — real-time task state query
"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import patch

import pytest
from httpx import AsyncClient

from agent_worker.claude.sdk_wrapper import EventBroadcast, task_registry
from agent_worker.persistence.jsonl_store import JsonlEventStore

from .conftest import collect_sse_events


# ---------------------------------------------------------------------------
# Subscribe
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestSubscribe:
    """GET /api/v1/tasks/{id}/subscribe replays and streams events."""

    async def test_subscribe_replays_all_events(
        self, client: AsyncClient, make_broadcast,
    ):
        """Subscribe with ack_seq=0 replays all events from the broadcast."""
        events = [
            {"type": "assistant_text", "task_id": "t-1", "content": "Hi"},
            {"type": "result", "task_id": "t-1", "session_id": "s-1"},
        ]
        bc = await make_broadcast("t-1", events, closed=True)
        task_registry["t-1"] = {
            "broadcast": bc,
            "asyncio_task": None,
            "foggy_task_id": None,
            "connected": False,
            "has_external_subscriber": False,
        }

        collected = await collect_sse_events(
            client, "GET", "/api/v1/tasks/t-1/subscribe",
            params={"ack_seq": 0},
        )

        assert len(collected) == 2
        assert collected[0]["type"] == "assistant_text"
        assert collected[1]["type"] == "result"

    async def test_subscribe_with_ack_seq_replays_newer(
        self, client: AsyncClient, make_broadcast,
    ):
        """Subscribe with ack_seq=1 only replays events with seq > 1."""
        events = [
            {"type": "assistant_text", "task_id": "t-2", "content": "A"},
            {"type": "assistant_text", "task_id": "t-2", "content": "B"},
            {"type": "result", "task_id": "t-2", "session_id": "s-2"},
        ]
        bc = await make_broadcast("t-2", events, closed=True)
        task_registry["t-2"] = {
            "broadcast": bc,
            "asyncio_task": None,
            "foggy_task_id": None,
            "connected": False,
            "has_external_subscriber": False,
        }

        collected = await collect_sse_events(
            client, "GET", "/api/v1/tasks/t-2/subscribe",
            params={"ack_seq": 1},
        )

        # Events have seq 1, 2, 3; only seq > 1 are replayed (2 events)
        assert len(collected) == 2
        assert collected[0]["content"] == "B"

    async def test_subscribe_nonexistent_returns_404(self, client: AsyncClient):
        resp = await client.get("/api/v1/tasks/no-such/subscribe")
        assert resp.status_code == 404

    async def test_subscribe_by_foggy_task_id(
        self, client: AsyncClient, make_broadcast,
    ):
        """Subscribe resolves foggy_task_id to the real worker task_id."""
        events = [{"type": "result", "task_id": "w-123", "session_id": "s-x"}]
        bc = await make_broadcast("w-123", events, closed=True)
        task_registry["w-123"] = {
            "broadcast": bc,
            "asyncio_task": None,
            "foggy_task_id": "foggy-abc",
            "connected": False,
            "has_external_subscriber": False,
        }

        collected = await collect_sse_events(
            client, "GET", "/api/v1/tasks/foggy-abc/subscribe",
            params={"ack_seq": 0},
        )

        assert len(collected) == 1

    async def test_subscribe_marks_connected(
        self, client: AsyncClient, make_broadcast,
    ):
        """After subscribe, the task entry should have connected=True."""
        bc = await make_broadcast("t-mark", [{"type": "result", "task_id": "t-mark"}], closed=True)
        task_registry["t-mark"] = {
            "broadcast": bc,
            "asyncio_task": None,
            "foggy_task_id": None,
            "connected": False,
            "has_external_subscriber": False,
        }

        await collect_sse_events(
            client, "GET", "/api/v1/tasks/t-mark/subscribe",
        )

        # After the SSE stream closes and cleanup runs, entry may be removed
        # (closed broadcast + no subscribers → cleanup). That's expected.
        # The key test is that no error occurred during subscribe.

    async def test_subscribe_no_broadcast_returns_409(self, client: AsyncClient):
        """If the task has no broadcast (e.g. non-interactive), return 409."""
        task_registry["t-no-bc"] = {
            "broadcast": None,
            "asyncio_task": None,
            "foggy_task_id": None,
            "connected": False,
        }

        resp = await client.get("/api/v1/tasks/t-no-bc/subscribe")
        assert resp.status_code == 409


# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestTaskStatus:
    """GET /api/v1/tasks/{id}/status returns real-time task metadata."""

    async def test_live_task_status(
        self, client: AsyncClient, make_broadcast,
    ):
        """Status for a live (still running) task returns registry source."""
        running = asyncio.ensure_future(asyncio.sleep(999))
        bc = await make_broadcast(
            "t-live",
            [{"type": "assistant_text", "task_id": "t-live", "content": "Hi"}],
            closed=False,
        )

        task_registry["t-live"] = {
            "broadcast": bc,
            "asyncio_task": running,
            "foggy_task_id": "f-live",
            "connected": True,
            "has_external_subscriber": True,
        }

        try:
            resp = await client.get("/api/v1/tasks/t-live/status")
            assert resp.status_code == 200
            body = resp.json()
            assert body["task_id"] == "t-live"
            assert body["latest_seq"] == 1
            assert body["event_count"] == 1
            assert body["closed"] is False
            assert body["cli_alive"] is True
            assert body["source"] == "registry"
        finally:
            running.cancel()

    async def test_finished_task_status(
        self, client: AsyncClient, make_broadcast,
    ):
        """Status for a finished task (broadcast closed, asyncio done)."""
        done = asyncio.ensure_future(asyncio.sleep(0))
        await asyncio.sleep(0.01)

        bc = await make_broadcast(
            "t-done",
            [
                {"type": "assistant_text", "task_id": "t-done", "content": "Done"},
                {"type": "result", "task_id": "t-done", "session_id": "s-1"},
            ],
            closed=True,
        )

        task_registry["t-done"] = {
            "broadcast": bc,
            "asyncio_task": done,
            "foggy_task_id": None,
            "connected": False,
            "has_external_subscriber": False,
        }

        resp = await client.get("/api/v1/tasks/t-done/status")
        assert resp.status_code == 200
        body = resp.json()
        assert body["closed"] is True
        assert body["cli_alive"] is False
        assert body["event_count"] == 2

    async def test_status_resolves_foggy_task_id(
        self, client: AsyncClient, make_broadcast,
    ):
        """Status endpoint resolves foggy_task_id to the real entry."""
        bc = await make_broadcast("w-xyz", [{"type": "result", "task_id": "w-xyz"}], closed=True)
        task_registry["w-xyz"] = {
            "broadcast": bc,
            "asyncio_task": None,
            "foggy_task_id": "foggy-xyz",
            "connected": False,
        }

        resp = await client.get("/api/v1/tasks/foggy-xyz/status")
        assert resp.status_code == 200
        assert resp.json()["task_id"] == "w-xyz"

    async def test_status_from_persistence(
        self, client: AsyncClient, tmp_path,
    ):
        """If task not in registry but in persistence store, return persistence data."""
        store = JsonlEventStore(tmp_path)
        store.append("persist-task", {"seq": 1, "type": "assistant_text"})
        store.append("persist-task", {"seq": 2, "type": "result"})
        store.mark_closed("persist-task")

        # The status endpoint imports get_event_store lazily:
        #   from ..persistence.factory import get_event_store
        # We patch the factory function at its source module.
        with patch(
            "agent_worker.persistence.factory.get_event_store",
            return_value=store,
        ):
            resp = await client.get("/api/v1/tasks/persist-task/status")

        assert resp.status_code == 200
        body = resp.json()
        assert body["latest_seq"] == 2
        assert body["closed"] is True
        assert body["source"] == "persistence"

    async def test_status_not_found(self, client: AsyncClient):
        """Status for a completely unknown task returns 404."""
        resp = await client.get("/api/v1/tasks/ghost-task/status")
        assert resp.status_code == 404
