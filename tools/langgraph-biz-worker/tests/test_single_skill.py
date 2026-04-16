"""Tests for Phase 3 — single Skill end-to-end via HTTP endpoint."""

import json

import pytest


@pytest.mark.asyncio
async def test_skill_triggered_with_order_id(client):
    """Query with context.order_id should trigger exception_triage skill."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze delay", "context": {"order_id": "123"}},
    )
    assert resp.status_code == 200
    events = _parse_sse_events(resp.text)
    event_types = [e["type"] for e in events]

    assert "system" in event_types
    assert "skill_frame_open" in event_types
    assert "assistant_text" in event_types
    assert "skill_frame_close" in event_types
    assert "result" in event_types

    # Result should contain structured output
    result_event = next(e for e in events if e["type"] == "result")
    assert result_event.get("structured_output") is not None
    assert result_event["structured_output"]["classification"] in [
        "vehicle_delay", "weather", "system_error", "other",
    ]


@pytest.mark.asyncio
async def test_skill_frame_open_close_events(client):
    """skill_frame_open and skill_frame_close events carry frame/skill IDs."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze", "context": {"order_id": "456"}},
    )
    events = _parse_sse_events(resp.text)

    open_events = [e for e in events if e["type"] == "skill_frame_open"]
    close_events = [e for e in events if e["type"] == "skill_frame_close"]

    # Parent frame (exception_triage) + child frames
    parent_opens = [e for e in open_events if e.get("skill_id") == "exception_triage"]
    parent_closes = [e for e in close_events if e.get("skill_id") == "exception_triage"]
    assert len(parent_opens) == 1
    assert len(parent_closes) == 1
    assert parent_opens[0]["skill_frame_id"] is not None
    assert parent_closes[0]["skill_frame_id"] == parent_opens[0]["skill_frame_id"]


@pytest.mark.asyncio
async def test_no_skill_without_context(client):
    """Query without order_id should fall back to no-skill response."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "general question"},
    )
    events = _parse_sse_events(resp.text)
    event_types = [e["type"] for e in events]

    # Should NOT have skill frame events
    assert "skill_frame_open" not in event_types
    assert "skill_frame_close" not in event_types
    # Should still have a result
    assert "result" in event_types


@pytest.mark.asyncio
async def test_result_has_evidence_in_summary(client):
    """The assistant_text should mention gathered evidence."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "check", "context": {"order_id": "789"}},
    )
    events = _parse_sse_events(resp.text)
    text_events = [e for e in events if e["type"] == "assistant_text"]
    assert any("evidence" in (e.get("content") or "").lower() for e in text_events)


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
