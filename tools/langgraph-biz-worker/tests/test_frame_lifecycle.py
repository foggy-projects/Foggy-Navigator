"""Tests for Frame lifecycle — state machine, completion protocol, close semantics."""

import pytest

from langgraph_biz_worker.models import FrameStatus, SkillManifest
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import (
    FrameNotFound,
    IllegalStateTransition,
    SkillRuntime,
)


@pytest.fixture
def runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="test_skill",
        name="Test Skill",
        output_schema={
            "type": "object",
            "required": ["result"],
            "properties": {"result": {"type": "string"}},
        },
        promote_to_parent=["result_summary", "structured_output"],
    ))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


class TestInvokeSkill:
    def test_creates_frame_in_running_state(self, runtime: SkillRuntime):
        frame_id = runtime.invoke_skill("task1", "test_skill", {"key": "val"})
        frame = runtime.get_frame(frame_id)
        assert frame is not None
        assert frame.status == FrameStatus.RUNNING
        assert frame.task_id == "task1"
        assert frame.skill_id == "test_skill"
        assert frame.input == {"key": "val"}
        assert frame.started_at != ""

    def test_assigns_unique_frame_ids(self, runtime: SkillRuntime):
        f1 = runtime.invoke_skill("task1", "test_skill")
        f2 = runtime.invoke_skill("task1", "test_skill")
        assert f1 != f2

    def test_registers_child_on_parent(self, runtime: SkillRuntime):
        parent_id = runtime.invoke_skill("task1", "test_skill")
        child_id = runtime.invoke_skill("task1", "test_skill", parent_frame_id=parent_id)
        parent = runtime.get_frame(parent_id)
        assert child_id in parent.child_frame_ids


class TestStateTransitions:
    def test_running_to_waiting_child(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.mark_waiting_child(fid)
        assert runtime.get_frame(fid).status == FrameStatus.WAITING_CHILD

    def test_waiting_child_to_running(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.mark_waiting_child(fid)
        runtime.resume_from_child(fid)
        assert runtime.get_frame(fid).status == FrameStatus.RUNNING

    def test_fail_from_running(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.fail_frame(fid, "test error")
        frame = runtime.get_frame(fid)
        assert frame.status == FrameStatus.FAILED
        assert frame.ended_at != ""
        assert frame.private_working_state["fail_reason"] == "test error"

    def test_cancel_from_running(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.cancel_frame(fid)
        assert runtime.get_frame(fid).status == FrameStatus.CANCELLED

    def test_illegal_transition_raises(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        # RUNNING → WAITING_CHILD is ok, but WAITING_CHILD → COMPLETED is illegal
        runtime.mark_waiting_child(fid)
        with pytest.raises(IllegalStateTransition):
            runtime.submit_result(fid, "s", {"result": "x"})

    def test_cannot_transition_from_terminal(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.fail_frame(fid)
        with pytest.raises(IllegalStateTransition):
            runtime.resume_from_child(fid)

    def test_frame_not_found(self, runtime: SkillRuntime):
        with pytest.raises(FrameNotFound):
            runtime.fail_frame("nonexistent")


class TestSubmitResult:
    def test_valid_submission_completes_frame(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        result = runtime.submit_result(fid, "done", {"result": "success"})
        assert result.ok
        frame = runtime.get_frame(fid)
        assert frame.status == FrameStatus.COMPLETED
        assert frame.output == {"result": "success"}
        assert frame.result_summary == "done"
        assert frame.ended_at != ""

    def test_invalid_submission_returns_errors(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        # Missing required 'result' field
        result = runtime.submit_result(fid, "done", {"wrong_field": "x"})
        assert not result.ok
        assert len(result.errors) > 0
        # Frame stays RUNNING
        assert runtime.get_frame(fid).status == FrameStatus.RUNNING

    def test_max_retries_leads_to_failed(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        for _ in range(3):
            result = runtime.submit_result(fid, "bad", {})
        assert not result.ok
        assert runtime.get_frame(fid).status == FrameStatus.FAILED


class TestPersistentTurnResult:
    def test_persistent_turn_result_updates_root_context_summary(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")

        result = runtime.submit_persistent_turn_result(
            fid,
            "turn done",
            {"result": "success"},
            artifact_refs=["art_1"],
            evidence_refs=["ev_1"],
        )

        frame = runtime.get_frame(fid)
        summary = frame.private_working_state["root_context_summary"]
        assert result.ok
        assert frame.status == FrameStatus.RUNNING
        assert summary["turn_count"] == 1
        assert summary["latest_summary"] == "turn done"
        assert summary["latest_structured_output"] == {"result": "success"}
        assert summary["artifact_refs"] == ["art_1"]
        assert summary["evidence_refs"] == ["ev_1"]

    def test_persistent_turn_result_compacts_growth(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        frame = runtime.get_frame(fid)
        frame.private_messages = [
            {"role": "tool", "content": {"index": index}}
            for index in range(45)
        ]
        runtime.store.save(frame)

        for index in range(25):
            runtime.submit_persistent_turn_result(
                fid,
                f"turn {index}",
                {"result": str(index)},
                artifact_refs=[f"art_{index}"],
            )

        frame = runtime.get_frame(fid)
        summary = frame.private_working_state["root_context_summary"]
        assert len(frame.private_working_state["turn_results"]) == 20
        assert len(summary["recent_turns"]) == 10
        assert len(frame.private_messages) == 40
        assert summary["compacted_turn_result_count"] == 5
        assert summary["compacted_private_message_count"] == 5
        assert summary["turn_count"] == 25

    def test_function_call_frame_captures_root_context_summary_snapshot(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.submit_persistent_turn_result(
            fid,
            "turn done",
            {"result": "success"},
            artifact_refs=["art_1"],
        )

        function_frame_id = runtime.invoke_function_call(
            fid,
            "tms.order.close",
            "v1",
            arguments={"orderNo": "ORD-001"},
        )

        function_frame = runtime.get_frame(function_frame_id)
        snapshot = function_frame.private_working_state["context_snapshot"]
        assert snapshot["visibility"] == "passthrough"
        assert snapshot["root_context_summary"]["latest_summary"] == "turn done"
        assert snapshot["root_context_summary"]["artifact_refs"] == ["art_1"]

    def test_persistent_turn_archives_interruption_when_user_switches_task(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.record_recoverable_interruption(
            fid,
            reason="user_cancelled",
            error="Cancelled by user",
            task_id="t",
        )

        result = runtime.submit_persistent_turn_result(
            fid,
            "Previous vehicle creation was shelved; started unrelated lookup.",
            {
                "continuation_decision": "START_UNRELATED_NEW_TASK",
                "abandoned_interruption": {
                    "summary": "Vehicle creation was interrupted before completion.",
                },
                "result": "success",
            },
        )

        frame = runtime.get_frame(fid)
        assert result.ok
        assert "continuation_state" not in frame.private_working_state
        assert "recoverable" not in frame.private_working_state
        history = frame.private_working_state["root_context_summary"]["interruption_history"]
        assert history[-1]["reason"] == "user_cancelled"
        assert history[-1]["last_error"] == "Cancelled by user"
        assert history[-1]["resolution"] == "START_UNRELATED_NEW_TASK"
        assert history[-1]["abandoned_interruption"] == {
            "summary": "Vehicle creation was interrupted before completion.",
        }


class TestCloseFrame:
    def test_close_returns_promoted_result(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        runtime.submit_result(fid, "summary", {"result": "data"})
        promoted = runtime.close_frame(fid)
        assert promoted["result_summary"] == "summary"
        assert promoted["structured_output"] == {"result": "data"}
        assert promoted["skill_id"] == "test_skill"

    def test_close_destroys_private_context(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        frame = runtime.get_frame(fid)
        frame.private_messages.append({"role": "system", "content": "secret"})
        frame.private_working_state["scratch"] = "data"
        runtime.store.save(frame)

        runtime.submit_result(fid, "done", {"result": "x"})
        runtime.close_frame(fid)

        frame = runtime.get_frame(fid)
        assert frame.private_messages == []
        assert frame.private_working_state == {}
        assert frame.tool_calls == []

    def test_close_requires_completed_status(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")
        with pytest.raises(IllegalStateTransition):
            runtime.close_frame(fid)


class TestWriteChildResult:
    def test_child_result_in_parent_private_state(self, runtime: SkillRuntime):
        parent_id = runtime.invoke_skill("t", "test_skill")
        child_id = runtime.invoke_skill("t", "test_skill", parent_frame_id=parent_id)

        promoted = {"skill_id": "child_skill", "result_summary": "child done"}
        runtime.write_child_result_to_parent(parent_id, child_id, promoted)

        parent = runtime.get_frame(parent_id)
        assert child_id in parent.private_working_state["child_results"]
        assert parent.private_working_state["child_results"][child_id] == promoted
