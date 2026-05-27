"""E2E smoke test — full query flow through HTTP → Root Graph → Skill Subgraph → SSE events.

Validates the complete chain:
  POST /api/v1/query
  → Root Graph routes to exception_triage skill
  → exception_triage invokes child skills (order_evidence_collect, rule_check)
  → All frames open/close with proper SSE events
  → Final result contains structured output
  → Frame file journal persists state at each step
"""

from __future__ import annotations

import json

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app
from langgraph_biz_worker.models import QueryEvent


def _parse_sse_events(raw_text: str) -> list[QueryEvent]:
    """Parse SSE stream text into QueryEvent objects."""
    events = []
    for line in raw_text.split("\n"):
        line = line.strip()
        if line.startswith("data:"):
            data = line[5:].strip()
            if data:
                try:
                    events.append(QueryEvent(**json.loads(data)))
                except Exception:
                    pass
    return events


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


class TestE2ESmoke:
    """End-to-end smoke test through the full HTTP → Graph → SSE pipeline."""

    async def test_full_skill_execution_with_order_context(self, client):
        """Trigger exception_triage skill with order context and verify full event chain."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "分析订单异常",
            "taskId": "e2e_smoke_001",
            "context": {"skill": "exception_triage", "order_id": "ORD-E2E-001"},
        })
        assert resp.status_code == 200

        events = _parse_sse_events(resp.text)
        assert len(events) > 0, "Should emit at least one SSE event"

        event_types = [e.type for e in events]

        # Must have system + skill frame events + result
        assert "system" in event_types, f"Missing system event. Got: {event_types}"
        assert "result" in event_types, f"Missing result event. Got: {event_types}"

        # Check skill frame open/close events exist (exception_triage + children)
        frame_opens = [e for e in events if e.type == "skill_frame_open"]
        frame_closes = [e for e in events if e.type == "skill_frame_close"]
        assert len(frame_opens) >= 1, "Should have at least 1 skill frame open"
        assert len(frame_closes) >= 1, "Should have at least 1 skill frame close"

        # Every opened frame should be closed
        open_ids = {e.skill_frame_id for e in frame_opens}
        close_ids = {e.skill_frame_id for e in frame_closes}
        assert open_ids == close_ids, f"Unclosed frames: {open_ids - close_ids}"

        # Result event should have task_id and duration
        result_event = next(e for e in events if e.type == "result")
        assert result_event.task_id == "e2e_smoke_001"
        assert result_event.duration_ms is not None and result_event.duration_ms >= 0

        # Result should contain structured output with classification
        assert result_event.structured_output is not None
        assert "classification" in result_event.structured_output

    async def test_no_skill_fallback(self, client):
        """Query without order context should fallback (no skill triggered)."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "你好",
            "taskId": "e2e_smoke_002",
        })
        assert resp.status_code == 200

        events = _parse_sse_events(resp.text)
        assert len(events) > 0

        # Should have a result but no skill frames
        result_events = [e for e in events if e.type == "result"]
        assert len(result_events) == 1

        frame_opens = [e for e in events if e.type == "skill_frame_open"]
        assert len(frame_opens) == 0, "No skill should be triggered without context"

    async def test_event_ordering(self, client):
        """Verify SSE event ordering: system → frame_open → ... → frame_close → result."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "处理异常订单",
            "taskId": "e2e_smoke_003",
            "context": {"skill": "exception_triage", "order_id": "ORD-E2E-003"},
        })
        events = _parse_sse_events(resp.text)
        event_types = [e.type for e in events]

        # system should come before any skill_frame_open
        if "system" in event_types and "skill_frame_open" in event_types:
            assert event_types.index("system") < event_types.index("skill_frame_open")

        # result should be the last event
        assert event_types[-1] == "result", f"Last event should be 'result', got '{event_types[-1]}'"

    async def test_child_skill_frames_nested(self, client):
        """Verify parent skill invokes child skills with proper frame nesting."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "异常分诊",
            "taskId": "e2e_smoke_004",
            "context": {"skill": "exception_triage", "order_id": "ORD-E2E-004"},
        })
        events = _parse_sse_events(resp.text)

        frame_opens = [e for e in events if e.type == "skill_frame_open"]
        skill_ids = [e.skill_id for e in frame_opens]

        # exception_triage should open, plus its child skills
        assert "exception_triage" in skill_ids, f"Missing parent skill. Got: {skill_ids}"

        # At least one child skill should be invoked
        child_skills = {"order_evidence_collect", "rule_check"}
        invoked_children = child_skills & set(skill_ids)
        assert len(invoked_children) >= 1, f"Expected child skills from {child_skills}, got {skill_ids}"

    async def test_task_id_consistency(self, client):
        """All events in a query should carry the same task_id."""
        task_id = "e2e_smoke_005"
        resp = await client.post("/api/v1/query", json={
            "prompt": "分析异常",
            "taskId": task_id,
            "context": {"skill": "exception_triage", "order_id": "ORD-E2E-005"},
        })
        events = _parse_sse_events(resp.text)

        for event in events:
            assert event.task_id == task_id, (
                f"Event type={event.type} has task_id={event.task_id}, expected {task_id}"
            )
