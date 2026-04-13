"""Tests for concurrent task rate limiting (P0)."""

from unittest.mock import patch

import pytest

from langgraph_biz_worker.routes.health import active_tasks


@pytest.mark.asyncio
async def test_429_when_max_concurrent_reached(client):
    """Should return 429 when active_tasks reaches max_concurrent_tasks."""
    with patch("langgraph_biz_worker.routes.query.settings") as mock_settings:
        mock_settings.max_concurrent_tasks = 2
        # Simulate 2 tasks already active
        active_tasks.add("fake-task-1")
        active_tasks.add("fake-task-2")
        try:
            resp = await client.post(
                "/api/v1/query",
                json={"prompt": "should be rejected"},
            )
            assert resp.status_code == 429
            assert "Max concurrent tasks" in resp.json()["detail"]
        finally:
            active_tasks.discard("fake-task-1")
            active_tasks.discard("fake-task-2")


@pytest.mark.asyncio
async def test_active_tasks_decremented_after_completion(client):
    """active_tasks should be cleaned up after a query completes."""
    initial_count = len(active_tasks)
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "quick test"},
    )
    assert resp.status_code == 200
    # After response is fully consumed, task should be removed
    assert len(active_tasks) == initial_count


@pytest.mark.asyncio
async def test_active_tasks_decremented_on_error(client):
    """active_tasks should be cleaned up even when graph throws an error."""
    initial_count = len(active_tasks)
    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = RuntimeError("boom")
        resp = await client.post(
            "/api/v1/query",
            json={"prompt": "will fail"},
        )
        assert resp.status_code == 200  # SSE still returns 200
    # After error, task should still be removed
    assert len(active_tasks) == initial_count
