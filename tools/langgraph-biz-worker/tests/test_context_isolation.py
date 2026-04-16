"""Tests for context isolation — verifying private state never leaks across frame boundaries.

Design reference: Doc 31 §4.1 (Skill is not long-term memory), §13.3 (closure cleanup).

Covers gaps identified in coverage audit:
1. Three-level nesting: grandchild private state must not leak to parent or root
2. close_frame clears child_results from private_working_state
3. Promoted result strictly matches manifest's promote_to_parent
4. Sibling child Skills have isolated private state
"""

from __future__ import annotations

import json

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app
from langgraph_biz_worker.models import FrameStatus, QueryEvent
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


# ---------------------------------------------------------------------------
# Unit-level context isolation tests
# ---------------------------------------------------------------------------


class TestCloseFrameDestroysChildResults:
    """After close_frame, child_results (inside private_working_state) must be gone."""

    def test_child_results_cleared_on_close(self):
        runtime = SkillRuntime()
        parent = runtime.invoke_skill("t1", "parent_skill")

        # Invoke and complete a child
        child = runtime.invoke_child_skill(parent, "child_skill", {"data": "secret"})
        runtime.submit_result(child, "child done", {"child_key": "child_val"})
        runtime.complete_child_and_resume_parent(child)

        # Parent should have child_results before close
        parent_frame = runtime.get_frame(parent)
        assert "child_results" in parent_frame.private_working_state
        assert child in parent_frame.private_working_state["child_results"]

        # Complete and close parent
        runtime.submit_result(parent, "parent done", {"parent_key": "parent_val"})
        promoted = runtime.close_frame(parent)

        # After close: private_working_state (including child_results) must be empty
        parent_frame = runtime.get_frame(parent)
        assert parent_frame.private_working_state == {}
        assert parent_frame.private_messages == []
        assert parent_frame.tool_calls == []

        # Promoted result must NOT contain child_results
        assert "child_results" not in promoted
        assert "private_working_state" not in promoted


class TestPromotedResultStrictFields:
    """Promoted result must only contain fields declared in promote_to_parent."""

    def test_promoted_only_declared_fields(self):
        from langgraph_biz_worker.models import SkillManifest
        from langgraph_biz_worker.runtime.skill_registry import SkillRegistry

        registry = SkillRegistry()
        registry.register(SkillManifest(
            id="strict_skill",
            name="Strict Skill",
            promote_to_parent=["result_summary", "structured_output"],
        ))

        runtime = SkillRuntime(skill_registry=registry)
        fid = runtime.invoke_skill("t1", "strict_skill")

        # Add extra data that should NOT be promoted
        runtime.set_evidence_refs(fid, ["evidence_1"])

        # Submit result (no manifest output_schema → validation auto-passes)
        result = runtime.submit_result(
            fid, "done", {"key": "val"},
            artifact_refs=["artifact_1"],
            evidence_refs=["evidence_1"],
        )
        assert result.ok, f"submit_result failed: {result.errors}"

        # Also set approval_request on the frame
        frame = runtime.get_frame(fid)
        frame.approval_request = {"type": "test"}
        runtime.store.save(frame)

        # Frame must be COMPLETED before close
        frame = runtime.get_frame(fid)
        assert frame.status == FrameStatus.COMPLETED

        promoted = runtime.close_frame(fid)

        # Only declared fields + frame_id + skill_id should be present
        assert "result_summary" in promoted
        assert "structured_output" in promoted
        assert "frame_id" in promoted
        assert "skill_id" in promoted

        # Non-declared fields must NOT be promoted
        assert "artifact_refs" not in promoted
        assert "evidence_refs" not in promoted
        assert "approval_request" not in promoted


class TestSiblingChildIsolation:
    """Two sibling child Skills must not share or contaminate each other's state."""

    def test_sibling_private_states_isolated(self):
        runtime = SkillRuntime()
        parent = runtime.invoke_skill("t1", "parent_skill")

        # Child 1: writes specific private data
        c1 = runtime.invoke_child_skill(parent, "child_1", {"c1_input": True})
        c1_frame = runtime.get_frame(c1)
        c1_frame.private_messages.append({"role": "user", "content": "c1 secret"})
        c1_frame.private_working_state["c1_scratch"] = "c1_data"
        runtime.store.save(c1_frame)
        runtime.submit_result(c1, "c1 done", {"c1": True})
        runtime.complete_child_and_resume_parent(c1)

        # After c1 closes, its private state must be empty
        c1_after = runtime.get_frame(c1)
        assert c1_after.private_messages == []
        assert c1_after.private_working_state == {}

        # Child 2: should start clean, no contamination from c1
        c2 = runtime.invoke_child_skill(parent, "child_2", {"c2_input": True})
        c2_frame = runtime.get_frame(c2)
        assert c2_frame.private_messages == []
        assert c2_frame.private_working_state == {}
        assert "c1_scratch" not in c2_frame.private_working_state
        assert c2_frame.input == {"c2_input": True}  # c1's input not leaked

        runtime.submit_result(c2, "c2 done", {"c2": True})
        runtime.complete_child_and_resume_parent(c2)

        # Parent should have both results separately
        parent_frame = runtime.get_frame(parent)
        child_results = parent_frame.private_working_state["child_results"]
        assert c1 in child_results
        assert c2 in child_results
        # Each child's promoted result is independent
        assert child_results[c1] != child_results[c2]


class TestThreeLevelContextIsolation:
    """Grandchild private state must not leak to parent or root."""

    def test_grandchild_context_not_in_parent(self):
        runtime = SkillRuntime()
        root = runtime.invoke_skill("t1", "root_skill")
        parent = runtime.invoke_child_skill(root, "parent_skill")

        # Grandchild with secret private data
        grandchild = runtime.invoke_child_skill(parent, "grandchild_skill", {"secret": "gc_data"})
        gc_frame = runtime.get_frame(grandchild)
        gc_frame.private_messages.append({"role": "system", "content": "grandchild secret"})
        gc_frame.private_working_state["gc_scratch"] = "should_not_leak"
        runtime.store.save(gc_frame)

        runtime.submit_result(grandchild, "gc done", {"gc_result": True})
        runtime.complete_child_and_resume_parent(grandchild)

        # Grandchild private state is cleared
        gc_after = runtime.get_frame(grandchild)
        assert gc_after.private_messages == []
        assert gc_after.private_working_state == {}

        # Parent should only have gc's promoted result, not its private data
        parent_frame = runtime.get_frame(parent)
        gc_promoted = parent_frame.private_working_state["child_results"][grandchild]
        assert "gc_scratch" not in gc_promoted
        assert "private_messages" not in gc_promoted
        assert "private_working_state" not in gc_promoted

        # Complete parent
        runtime.submit_result(parent, "parent done", {"parent_result": True})
        runtime.complete_child_and_resume_parent(parent)

        # Root should only have parent's promoted result
        root_frame = runtime.get_frame(root)
        parent_promoted = root_frame.private_working_state["child_results"][parent]
        assert "gc_scratch" not in parent_promoted
        assert "child_results" not in parent_promoted  # parent's child_results are private


# ---------------------------------------------------------------------------
# E2E context isolation through HTTP
# ---------------------------------------------------------------------------


def _parse_sse_events(raw_text: str) -> list[dict]:
    events = []
    for line in raw_text.split("\n"):
        line = line.strip()
        if line.startswith("data:"):
            data = line[5:].strip()
            if data:
                try:
                    events.append(json.loads(data))
                except Exception:
                    pass
    return events


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


class TestE2EContextIsolation:
    """Verify context isolation through the full HTTP → Graph → SSE pipeline."""

    async def test_three_level_no_private_data_in_result(self, client):
        """Final SSE result must not contain any Frame's private_messages or private_working_state."""
        resp = await client.post("/api/v1/query", json={
            "prompt": "context isolation check",
            "taskId": "ctx_iso_001",
            "context": {"order_id": "ORD-ISO-001"},
        })
        events = _parse_sse_events(resp.text)

        result = next((e for e in events if e["type"] == "result"), None)
        assert result is not None

        # No private data in any event
        for event in events:
            so = event.get("structured_output") or {}
            content = event.get("content", "")
            assert "private_messages" not in so, f"private_messages leaked in {event['type']}"
            assert "private_working_state" not in so, f"private_working_state leaked in {event['type']}"
            assert "gc_scratch" not in str(so), f"grandchild scratch leaked in {event['type']}"

    async def test_grandchild_frames_cleaned_after_execution(self, client):
        """All grandchild frames (address_verify) should have empty private state after execution."""
        from langgraph_biz_worker.graphs.root_graph import get_runtime

        resp = await client.post("/api/v1/query", json={
            "prompt": "check grandchild cleanup",
            "taskId": "ctx_iso_002",
            "context": {"order_id": "ORD-ISO-002"},
        })
        events = _parse_sse_events(resp.text)

        runtime = get_runtime()
        grandchild_opens = [
            e for e in events
            if e["type"] == "skill_frame_open" and e.get("skill_id") == "address_verify"
        ]
        assert len(grandchild_opens) >= 1

        for gc_event in grandchild_opens:
            gc_frame = runtime.get_frame(gc_event["skill_frame_id"])
            assert gc_frame is not None
            assert gc_frame.private_messages == [], f"address_verify frame {gc_frame.frame_id} has leaked private_messages"
            assert gc_frame.private_working_state == {}, f"address_verify frame {gc_frame.frame_id} has leaked private_working_state"
