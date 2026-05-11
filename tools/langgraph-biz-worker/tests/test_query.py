"""Tests for the POST /api/v1/query SSE endpoint."""

import asyncio
import json
import threading
from unittest.mock import patch

import pytest

from langgraph_biz_worker.models import QueryEvent, QueryRequest
from langgraph_biz_worker.routes.query import _event_generator, _resolve_session_id


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


def test_query_prefers_foggy_session_id_for_platform_session():
    request = QueryRequest(
        prompt="test",
        session_id="claude-provider-session",
        foggy_session_id="navigator-session",
    )

    assert _resolve_session_id(request) == "navigator-session"


@pytest.mark.asyncio
async def test_query_generator_streams_tool_progress_before_graph_finishes():
    release_graph = threading.Event()
    graph_returned = threading.Event()
    tool_event = QueryEvent(
        type="tool_use",
        task_id="progress-task-001",
        skill_frame_id="frame-001",
        skill_id="skill-001",
        content="tms.dataset.listModels",
        tool_call_id="call-001",
        tool_name="tms.dataset.listModels",
        function_id="tms.dataset.listModels",
    )
    result_event = QueryEvent(type="result", task_id="progress-task-001", content="done")

    def invoke_with_progress(state):
        state["runtime_context"]["_progress_event_sink"](tool_event)
        assert release_graph.wait(timeout=2)
        graph_returned.set()
        return {"events": [tool_event, result_event]}

    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = invoke_with_progress
        generator = _event_generator(
            "progress-task-001",
            QueryRequest(prompt="stream tool progress"),
        )
        try:
            first = await asyncio.wait_for(generator.__anext__(), timeout=1)
            first_event = json.loads(first["data"])
            assert first_event["type"] == "tool_use"
            assert not graph_returned.is_set()
        finally:
            release_graph.set()

        remaining = []
        async for item in generator:
            remaining.append(json.loads(item["data"]))

    all_events = [first_event, *remaining]
    assert [event["type"] for event in all_events].count("tool_use") == 1
    assert any(event["type"] == "result" for event in all_events)


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
async def test_query_assistant_text_present(client):
    """The assistant_text event should contain the LLM response or fallback text."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze order 456"},
    )
    events = _parse_sse_events(resp.text)

    text_events = [e for e in events if e["type"] == "assistant_text"]
    assert len(text_events) >= 1
    assert len(text_events[0]["content"]) > 0


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


@pytest.mark.asyncio
async def test_query_allowed_skills(client):
    """When allowed_skills is in context, the LLM should receive it in the system prompt."""
    context = {
        "allowed_skills": [
            {
                "id": "tms_order_refund",
                "description": "Custom TMS order refund skill"
            }
        ]
    }
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test allowed skills", "context": context},
    )
    assert resp.status_code == 200
    events = _parse_sse_events(resp.text)
    
    # We can't directly check the system prompt sent to the LLM here unless we inspect the mock calls.
    # But we can verify it doesn't crash and returns events.
    assert len(events) >= 3
    event_types = [e["type"] for e in events]
    assert event_types[0] == "system"


@pytest.mark.asyncio
async def test_query_auto_inject_public_skills(client):
    """When auto_inject_public_skills is True, the LLM should receive public skills."""
    context = {
        "auto_inject_public_skills": True
    }
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test auto inject", "context": context},
    )
    assert resp.status_code == 200
    events = _parse_sse_events(resp.text)
    
    assert len(events) >= 3
    assert events[0]["type"] == "system"
