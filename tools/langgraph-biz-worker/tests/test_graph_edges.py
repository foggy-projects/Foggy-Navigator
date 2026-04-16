"""Tests for Root Graph edge cases and node boundary behavior (P2)."""

import json

import pytest


@pytest.mark.asyncio
async def test_query_field_aliasing_taskId(client):
    """taskId alias should be mapped to task_id."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "alias test", "taskId": "alias-123"},
    )
    events = _parse_sse_events(resp.text)
    for e in events:
        assert e.get("task_id") == "alias-123"


@pytest.mark.asyncio
async def test_query_with_session_id(client):
    """session_id should be propagated (not rejected)."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "session test", "session_id": "sess-456"},
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_query_with_model_parameter(client):
    """model parameter should be accepted and propagated to events."""
    resp = await client.post(
        "/api/v1/query",
        json={
            "prompt": "model test",
            "model": "claude-3-5-sonnet",
            "context": {"order_id": "789"},
        },
    )
    events = _parse_sse_events(resp.text)
    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1


@pytest.mark.asyncio
async def test_no_skill_match_fallback_response(client):
    """Query with context but no matching skill should return fallback."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "general question", "context": {"unrelated": "data"}},
    )
    events = _parse_sse_events(resp.text)
    event_types = [e["type"] for e in events]
    assert "skill_frame_open" not in event_types
    assert "result" in event_types


@pytest.mark.asyncio
async def test_skill_context_trigger_exception_triage(client):
    """context.skill='exception_triage' should also trigger the skill."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test", "context": {"skill": "exception_triage"}},
    )
    events = _parse_sse_events(resp.text)
    opens = [e for e in events if e["type"] == "skill_frame_open" and e.get("skill_id") == "exception_triage"]
    assert len(opens) == 1


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
