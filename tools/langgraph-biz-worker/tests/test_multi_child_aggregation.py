"""Tests for Phase 5 — multi-child Skill aggregation."""

import json

import pytest

from langgraph_biz_worker.models import FrameStatus


@pytest.mark.asyncio
async def test_two_child_skills_invoked(client):
    """Both order_evidence_collect and rule_check child skills should fire."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "full analysis", "context": {"skill": "exception_triage", "order_id": "500"}},
    )
    events = _parse_sse_events(resp.text)

    child_opens = [e for e in events if e["type"] == "skill_frame_open"]
    child_skill_ids = [e.get("skill_id") for e in child_opens]

    assert "exception_triage" in child_skill_ids
    assert "order_evidence_collect" in child_skill_ids
    assert "rule_check" in child_skill_ids


@pytest.mark.asyncio
async def test_frame_sequence_correct(client):
    """Frame open/close sequence: parent open → child1 open/close → child2 open/close → parent close."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "sequence test", "context": {"skill": "exception_triage", "order_id": "501"}},
    )
    events = _parse_sse_events(resp.text)

    frame_events = [e for e in events if e["type"] in ("skill_frame_open", "skill_frame_close")]
    sequence = [(e["type"], e.get("skill_id")) for e in frame_events]

    assert sequence == [
        ("skill_frame_open", "exception_triage"),
        ("skill_frame_open", "order_evidence_collect"),
        ("skill_frame_open", "address_verify"),
        ("skill_frame_close", "address_verify"),
        ("skill_frame_close", "order_evidence_collect"),
        ("skill_frame_open", "rule_check"),
        ("skill_frame_close", "rule_check"),
        ("skill_frame_close", "exception_triage"),
    ]


@pytest.mark.asyncio
async def test_root_only_sees_parent_aggregated_result(client):
    """The final result event should contain parent's aggregated output, not child details."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "aggregate", "context": {"skill": "exception_triage", "order_id": "502"}},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1

    result = result_events[0]
    so = result.get("structured_output", {})

    # Should have parent's analysis fields
    assert "classification" in so
    assert "recommended_action" in so
    assert "confidence" in so

    # Should NOT have child-level details
    assert "order_details" not in so
    assert "vehicle_details" not in so
    assert "checked_rules" not in so


@pytest.mark.asyncio
async def test_all_child_frames_closed(client):
    """After query completes, all child frames should be COMPLETED with clean private state."""
    from langgraph_biz_worker.graphs.root_graph import get_runtime

    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "cleanup", "context": {"skill": "exception_triage", "order_id": "503"}},
    )
    events = _parse_sse_events(resp.text)

    runtime = get_runtime()

    child_frame_ids = [
        e["skill_frame_id"]
        for e in events
        if e["type"] == "skill_frame_open"
        and e.get("skill_id") in ("order_evidence_collect", "rule_check")
    ]

    assert len(child_frame_ids) == 2

    for fid in child_frame_ids:
        frame = runtime.get_frame(fid)
        assert frame.status == FrameStatus.COMPLETED
        assert frame.private_messages == []
        assert frame.private_working_state == {}


@pytest.mark.asyncio
async def test_child_scratchpad_not_in_root_public_state(client):
    """Root's structured_output should not contain any child's private data."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "isolation", "context": {"skill": "exception_triage", "order_id": "504"}},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1

    so = result_events[0].get("structured_output", {})
    # None of the child scratchpad keys should be present
    assert "private_messages" not in so
    assert "private_working_state" not in so
    assert "rule_passed" not in so  # This belongs to rule_check child, not parent


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
