"""Tests for the POST /api/v1/query SSE endpoint."""

import json

import pytest


@pytest.mark.asyncio
async def test_query_returns_sse_events(client):
    """Query endpoint should return a sequence of SSE events: system -> assistant_text -> result."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test business query"},
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")

    # Parse SSE events from response body
    events = _parse_sse_events(resp.text)

    assert len(events) >= 3, f"Expected at least 3 events, got {len(events)}"

    # Verify event sequence
    event_types = [e["type"] for e in events]
    assert event_types[0] == "system"
    assert event_types[1] == "assistant_text"
    assert event_types[-1] == "result"


@pytest.mark.asyncio
async def test_query_result_contains_task_id(client):
    """All events should carry a non-empty task_id."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test"},
    )
    events = _parse_sse_events(resp.text)

    for event in events:
        assert event.get("task_id"), f"Event missing task_id: {event}"


@pytest.mark.asyncio
async def test_query_with_custom_task_id(client):
    """When taskId is provided in request, events should use it."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test", "taskId": "custom-task-123"},
    )
    events = _parse_sse_events(resp.text)

    for event in events:
        assert event["task_id"] == "custom-task-123"


@pytest.mark.asyncio
async def test_query_result_has_duration(client):
    """The result event should contain duration_ms."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test"},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1
    assert result_events[0]["duration_ms"] is not None
    assert result_events[0]["duration_ms"] >= 0


@pytest.mark.asyncio
async def test_query_assistant_text_echoes_prompt(client):
    """The assistant_text event should reference the original prompt."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze order 456"},
    )
    events = _parse_sse_events(resp.text)

    text_events = [e for e in events if e["type"] == "assistant_text"]
    assert len(text_events) >= 1
    assert "analyze order 456" in text_events[0]["content"]


def _parse_sse_events(body: str) -> list[dict]:
    """Parse SSE event stream body into a list of JSON event dicts."""
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
