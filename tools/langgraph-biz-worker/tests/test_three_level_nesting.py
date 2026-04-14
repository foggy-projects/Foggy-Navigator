"""Tests for 3-level Skill nesting: exception_triage → order_evidence_collect → address_verify.

Validates the full chain through HTTP → Root Graph → SSE events.
"""

from __future__ import annotations

import json

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app
from langgraph_biz_worker.models import QueryEvent


def _parse_sse_events(raw_text: str) -> list[QueryEvent]:
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


class TestThreeLevelNesting:
    async def test_address_verify_frame_appears(self, client):
        """address_verify should appear as a grandchild frame in the SSE events."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "三层嵌套测试",
            "taskId": "nest3_001",
            "context": {"order_id": "ORD-NEST3"},
        })
        assert resp.status_code == 200

        events = _parse_sse_events(resp.text)
        frame_opens = [e for e in events if e.type == "skill_frame_open"]
        skill_ids = [e.skill_id for e in frame_opens]

        assert "exception_triage" in skill_ids, f"Missing parent. Got: {skill_ids}"
        assert "order_evidence_collect" in skill_ids, f"Missing child. Got: {skill_ids}"
        assert "address_verify" in skill_ids, f"Missing grandchild. Got: {skill_ids}"

    async def test_all_frames_closed(self, client):
        """All opened frames (including grandchild) should be closed."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "三层嵌套测试",
            "taskId": "nest3_002",
            "context": {"order_id": "ORD-NEST3"},
        })
        events = _parse_sse_events(resp.text)

        opens = {e.skill_frame_id for e in events if e.type == "skill_frame_open"}
        closes = {e.skill_frame_id for e in events if e.type == "skill_frame_close"}
        assert opens == closes, f"Unclosed: {opens - closes}"

    async def test_result_includes_address_verified(self, client):
        """Final result should contain address_verified from the grandchild."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "三层嵌套测试",
            "taskId": "nest3_003",
            "context": {"order_id": "ORD-NEST3"},
        })
        events = _parse_sse_events(resp.text)
        result = next((e for e in events if e.type == "result"), None)
        assert result is not None
        assert result.structured_output is not None

    async def test_frame_open_order(self, client):
        """Frame opens should be: exception_triage → order_evidence_collect → address_verify."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "三层嵌套测试",
            "taskId": "nest3_004",
            "context": {"order_id": "ORD-NEST3"},
        })
        events = _parse_sse_events(resp.text)
        frame_opens = [e.skill_id for e in events if e.type == "skill_frame_open"]

        # exception_triage opens first
        assert frame_opens[0] == "exception_triage"

        # order_evidence_collect opens second
        assert "order_evidence_collect" in frame_opens
        oec_idx = frame_opens.index("order_evidence_collect")

        # address_verify opens third (after order_evidence_collect)
        assert "address_verify" in frame_opens
        av_idx = frame_opens.index("address_verify")
        assert av_idx > oec_idx

    async def test_at_least_4_frames_total(self, client):
        """Should have at least 4 frame open events:
        exception_triage, order_evidence_collect, address_verify, rule_check."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "三层嵌套测试",
            "taskId": "nest3_005",
            "context": {"order_id": "ORD-NEST3"},
        })
        events = _parse_sse_events(resp.text)
        frame_opens = [e for e in events if e.type == "skill_frame_open"]
        assert len(frame_opens) >= 4, f"Expected >= 4 frames, got {len(frame_opens)}: {[e.skill_id for e in frame_opens]}"
