"""Tests for error paths and exception handling (P0/P1)."""

import json
from unittest.mock import patch

import pytest


@pytest.mark.asyncio
async def test_graph_exception_emits_error_event(client):
    """When root_graph.invoke raises, an error SSE event should be emitted."""
    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = RuntimeError("graph crashed")
        resp = await client.post(
            "/api/v1/query",
            json={"prompt": "crash test"},
        )
        assert resp.status_code == 200
        events = _parse_sse_events(resp.text)
        error_events = [e for e in events if e["type"] == "error"]
        assert len(error_events) >= 1
        assert "graph crashed" in error_events[0]["error"]


@pytest.mark.asyncio
async def test_error_event_contains_task_id(client):
    """Error events should still carry task_id."""
    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = ValueError("bad value")
        resp = await client.post(
            "/api/v1/query",
            json={"prompt": "error id test", "taskId": "err-task-001"},
        )
        events = _parse_sse_events(resp.text)
        error_events = [e for e in events if e["type"] == "error"]
        assert error_events[0]["task_id"] == "err-task-001"


@pytest.mark.asyncio
async def test_malformed_json_returns_422(client):
    """Malformed JSON body should return 422 Unprocessable Entity."""
    resp = await client.post(
        "/api/v1/query",
        content=b"not json at all",
        headers={"Content-Type": "application/json"},
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_missing_prompt_returns_422(client):
    """Request body without required 'prompt' field should return 422."""
    resp = await client.post(
        "/api/v1/query",
        json={"context": {"order_id": "123"}},
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_empty_prompt_accepted(client):
    """Empty string prompt should be accepted (not rejected by schema)."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": ""},
    )
    # Empty prompt is a valid string, so should get 200
    assert resp.status_code == 200


def _parse_sse_events(body: str) -> list[dict]:
    events = []
    for line in body.split("\n"):
        line = line.strip()
        if line.startswith("data:"):
            data_str = line[len("data:"):].strip()
            if data_str:
                try:
                    events.append(json.loads(data_str))
                except json.JSONDecodeError:
                    pass
    return events
