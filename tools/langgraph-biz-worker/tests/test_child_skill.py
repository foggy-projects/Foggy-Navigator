"""Tests for Phase 4 — child Skill invocation and frame lifecycle."""

import json

import pytest

from langgraph_biz_worker.models import FrameStatus
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


@pytest.mark.asyncio
async def test_child_skill_frame_appears_in_events(client):
    """Child skill frame open/close events should appear in SSE stream."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze", "context": {"order_id": "100"}},
    )
    events = _parse_sse_events(resp.text)
    event_types = [e["type"] for e in events]

    # Parent frame
    parent_opens = [e for e in events if e["type"] == "skill_frame_open" and e.get("skill_id") == "exception_triage"]
    parent_closes = [e for e in events if e["type"] == "skill_frame_close" and e.get("skill_id") == "exception_triage"]

    # Child frame
    child_opens = [e for e in events if e["type"] == "skill_frame_open" and e.get("skill_id") == "order_evidence_collect"]
    child_closes = [e for e in events if e["type"] == "skill_frame_close" and e.get("skill_id") == "order_evidence_collect"]

    assert len(parent_opens) == 1
    assert len(parent_closes) == 1
    assert len(child_opens) == 1
    assert len(child_closes) == 1
    assert parent_opens[0].get("parent_frame_id") is None
    assert parent_closes[0].get("parent_frame_id") is None
    assert child_opens[0].get("parent_frame_id") == parent_opens[0]["skill_frame_id"]
    assert child_closes[0].get("parent_frame_id") == parent_opens[0]["skill_frame_id"]

    # Child evidence_collect should open after parent and close before parent closes
    all_frame_events = [e for e in events if e["type"] in ("skill_frame_open", "skill_frame_close")]
    frame_sequence = [(e["type"], e.get("skill_id")) for e in all_frame_events]
    # Verify evidence_collect appears nested within exception_triage
    assert frame_sequence[0] == ("skill_frame_open", "exception_triage")
    assert frame_sequence[-1] == ("skill_frame_close", "exception_triage")
    # evidence_collect should be present
    assert ("skill_frame_open", "order_evidence_collect") in frame_sequence
    assert ("skill_frame_close", "order_evidence_collect") in frame_sequence


@pytest.mark.asyncio
async def test_child_frame_completed_and_closed(client):
    """After query, child frames should be COMPLETED and private context cleaned."""
    from langgraph_biz_worker.graphs.root_graph import get_runtime

    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test", "context": {"order_id": "200"}},
    )
    events = _parse_sse_events(resp.text)

    # Find the child frame ID from events
    child_opens = [e for e in events if e["type"] == "skill_frame_open" and e.get("skill_id") == "order_evidence_collect"]
    assert len(child_opens) == 1
    child_frame_id = child_opens[0]["skill_frame_id"]

    runtime = get_runtime()
    child_frame = runtime.get_frame(child_frame_id)

    # Child frame should be completed
    assert child_frame.status == FrameStatus.COMPLETED
    # Private context should be destroyed after close_frame
    assert child_frame.private_messages == []
    assert child_frame.private_working_state == {}


@pytest.mark.asyncio
async def test_parent_receives_child_result(client):
    """The final result should contain data from child skill's evidence."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "check", "context": {"order_id": "300"}},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1

    result = result_events[0]
    assert result.get("structured_output") is not None
    assert result["structured_output"]["classification"] == "vehicle_delay"


@pytest.mark.asyncio
async def test_child_scratchpad_not_in_root_result(client):
    """Root result should NOT contain child's private messages or working state."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "verify", "context": {"order_id": "400"}},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1

    result = result_events[0]
    # structured_output should only have classification/action/confidence
    so = result.get("structured_output", {})
    assert "private_messages" not in so
    assert "private_working_state" not in so


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
