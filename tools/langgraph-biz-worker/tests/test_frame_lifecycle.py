"""Tests for Frame lifecycle — state machine, completion protocol, close semantics."""

import json

import pytest

from langgraph_biz_worker.models import FrameStatus, SkillManifest
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import (
    FrameNotFound,
    IllegalStateTransition,
    SkillRuntime,
)

CTX_FOCUS = "bctx_20260520_ab_conv-focus"
CTX_RECOVERABLE = "bctx_20260520_cd_conv-1"


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

    def test_persistent_turn_result_persists_active_plan(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")

        result = runtime.submit_persistent_turn_result(
            fid,
            "plan started",
            {
                "result": "success",
                "active_plan": {
                    "goal": "complete several steps",
                    "status": "IN_PROGRESS",
                    "steps": [{"id": "1", "status": "DONE"}],
                },
            },
        )

        frame = runtime.get_frame(fid)
        summary = frame.private_working_state["root_context_summary"]
        assert result.ok
        assert frame.private_working_state["active_plan"]["goal"] == "complete several steps"
        assert summary["active_plan"]["status"] == "IN_PROGRESS"

    def test_persistent_turn_result_archives_terminal_active_plan(self, runtime: SkillRuntime):
        fid = runtime.invoke_skill("t", "test_skill")

        result = runtime.submit_persistent_turn_result(
            fid,
            "plan completed",
            {
                "result": "success",
                "active_plan": {
                    "goal": "complete several steps",
                    "status": "COMPLETED",
                },
            },
        )

        frame = runtime.get_frame(fid)
        summary = frame.private_working_state["root_context_summary"]
        assert result.ok
        assert "active_plan" not in frame.private_working_state
        assert "active_plan" not in summary
        assert summary["plan_history"][-1]["resolution"] == "COMPLETED"
        assert summary["plan_history"][-1]["plan"]["goal"] == "complete several steps"

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

    def test_prepare_recoverable_focus_resume_rebinds_immediate_child(self, runtime: SkillRuntime):
        parent_id = runtime.invoke_skill(
            "task-focus-001",
            "test_skill",
            conversation_id=CTX_FOCUS,
            session_id="sess-focus",
            current_task_id="task-focus-001",
            origin_task_id="task-focus-001",
        )
        child_id = runtime.invoke_child_skill(
            parent_id,
            "test_skill",
            {"order_id": "ORD-1"},
        )
        runtime.record_recoverable_child_interruption(
            parent_id,
            reason="user_cancelled",
            error="cancelled during child",
            task_id="task-focus-001",
        )
        runtime.record_recoverable_interruption(
            parent_id,
            reason="user_cancelled",
            error="cancelled during child",
            task_id="task-focus-001",
        )

        focus = runtime.prepare_recoverable_focus_resume(
            parent_id,
            task_id="task-focus-002",
        )

        parent = runtime.get_frame(parent_id)
        child = runtime.get_frame(child_id)
        assert focus is not None
        assert focus.frame_id == child_id
        assert parent.status == FrameStatus.WAITING_CHILD
        assert child.status == FrameStatus.RUNNING
        assert child.task_id == "task-focus-002"
        assert child.current_task_id == "task-focus-002"
        assert child.origin_task_id == "task-focus-001"
        assert child.conversation_id == CTX_FOCUS
        assert child.session_id == "sess-focus"

    def test_prepare_active_focus_resume_rebinds_nested_recoverable_leaf(self, runtime: SkillRuntime):
        root_id = runtime.invoke_skill(
            "task-focus-nested-001",
            "test_skill",
            conversation_id=CTX_FOCUS,
            session_id="sess-focus-nested",
            current_task_id="task-focus-nested-001",
            origin_task_id="task-focus-nested-001",
        )
        child_id = runtime.invoke_child_skill(
            root_id,
            "test_skill",
            {"order_id": "ORD-NESTED"},
        )
        grandchild_id = runtime.invoke_child_skill(
            child_id,
            "test_skill",
            {"order_id": "ORD-NESTED", "step": "deep"},
        )
        runtime.record_recoverable_child_interruption(
            root_id,
            reason="user_cancelled",
            error="cancelled during nested child",
            task_id="task-focus-nested-001",
        )
        runtime.record_recoverable_interruption(
            root_id,
            reason="user_cancelled",
            error="cancelled during nested child",
            task_id="task-focus-nested-001",
        )

        focus = runtime.prepare_active_focus_resume(
            root_id,
            task_id="task-focus-nested-002",
        )

        root = runtime.get_frame(root_id)
        child = runtime.get_frame(child_id)
        grandchild = runtime.get_frame(grandchild_id)
        assert focus is not None
        assert focus.frame_id == grandchild_id
        assert root.status == FrameStatus.WAITING_CHILD
        assert child.status == FrameStatus.WAITING_CHILD
        assert grandchild.status == FrameStatus.RUNNING
        assert root.current_task_id == "task-focus-nested-002"
        assert child.current_task_id == "task-focus-nested-002"
        assert grandchild.current_task_id == "task-focus-nested-002"
        assert grandchild.origin_task_id == "task-focus-nested-001"
        assert grandchild.conversation_id == CTX_FOCUS
        assert grandchild.session_id == "sess-focus-nested"
        assert [entry["frame_id"] for entry in root.private_working_state["recoverable_focus_stack"]] == [
            root_id,
            child_id,
            grandchild_id,
        ]

    def test_latest_recoverable_root_is_selected_and_older_focus_superseded(self, tmp_path):
        from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal

        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)
        old_frame_id = runtime.invoke_skill(
            "task-old",
            "system.root",
            conversation_id=CTX_RECOVERABLE,
            current_task_id="task-old",
        )
        runtime.record_recoverable_interruption(
            old_frame_id,
            reason="user_cancelled",
            error="cancelled",
            task_id="task-old",
        )
        new_frame_id = runtime.invoke_skill(
            "task-new",
            "system.root",
            conversation_id=CTX_RECOVERABLE,
            current_task_id="task-new",
        )
        runtime.record_recoverable_interruption(
            new_frame_id,
            reason="llm_retry_exhausted",
            error="timeout",
            task_id="task-new",
        )

        restored = SkillRuntime(journal=journal)
        selected = restored.select_latest_recoverable_root(
            conversation_id=CTX_RECOVERABLE,
            task_id="task-next",
            root_skill_id="system.root",
        )

        assert selected is not None
        assert selected.frame_id == new_frame_id
        old_frame = restored.get_frame(old_frame_id)
        assert old_frame is not None
        assert old_frame.private_working_state["continuation_state"] == "SUPERSEDED"
        assert old_frame.private_working_state["recoverable"] is False
        assert old_frame.private_working_state["superseded_by_frame_id"] == new_frame_id


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

    def test_child_result_summary_preserves_business_wait_state(self, runtime: SkillRuntime):
        parent_id = runtime.invoke_skill("t", "test_skill")
        child_id = runtime.invoke_skill("t", "test_skill", parent_frame_id=parent_id)

        promoted = {
            "frame_id": child_id,
            "skill_id": "ticket_skill",
            "status": "COMPLETED",
            "result_summary": "Need more ticket fields.",
            "structured_output": {
                "status": "WAITING_USER",
                "next_step": "请回复工单类型、标题及详细描述。",
                "missing_fields": ["ticket_type", "title", "description"],
            },
            "execution_report_ref": f"frame-report://t/{child_id}",
        }
        runtime.write_child_result_to_parent(parent_id, child_id, promoted)

        parent = runtime.get_frame(parent_id)
        summary = parent.private_working_state["latest_child_result_summary"]
        assert summary["frame_id"] == child_id
        assert summary["skill_id"] == "ticket_skill"
        assert summary["frame_status"] == "COMPLETED"
        assert summary["status"] == "WAITING_USER"
        assert summary["next_step"] == "请回复工单类型、标题及详细描述。"
        assert summary["missing_fields"] == ["ticket_type", "title", "description"]

        root_summary = parent.private_working_state["root_context_summary"]
        assert root_summary["latest_child_result_summary"] == summary
        assert root_summary["child_result_summaries"][-1] == summary

    def test_child_result_summary_redacts_sensitive_recovery_payload(self, runtime: SkillRuntime):
        parent_id = runtime.invoke_skill("t", "test_skill")
        child_id = runtime.invoke_skill("t", "test_skill", parent_frame_id=parent_id)

        promoted = {
            "frame_id": child_id,
            "skill_id": "ticket_skill",
            "status": "COMPLETED",
            "result_summary": "Need more ticket fields. See https://signed.example.test/file?token=leak",
            "structured_output": {
                "status": "WAITING_USER",
                "next_step": "请回复工单类型、标题及详细描述。",
                "missing_fields": ["ticket_type", "title", "description"],
                "signed_url": "https://signed.example.test/file?X-Amz-Signature=leak",
                "api_key": "sk-secret-token",
                "raw_prompt": "SYSTEM PROMPT SECRET",
                "nested": {
                    "Authorization": "Bearer secret-token",
                    "callbackUrl": "https://signed.example.test/callback?signature=leak",
                    "public_note": "safe note",
                },
            },
            "artifact_refs": [
                {
                    "artifact_id": "artifact_1",
                    "url": "https://signed.example.test/artifact?signature=leak",
                }
            ],
            "evidence_refs": [
                "evidence://safe/ref",
                "https://signed.example.test/evidence?signature=leak",
            ],
            "execution_report_digest": {
                "report_ref": f"frame-report://t/{child_id}",
                "error": "provider returned sk-secret-token",
                "artifact_refs": ["https://signed.example.test/report-artifact?token=leak"],
            },
        }
        runtime.write_child_result_to_parent(parent_id, child_id, promoted)

        parent = runtime.get_frame(parent_id)
        summary = parent.private_working_state["latest_child_result_summary"]
        encoded = json.dumps(summary, ensure_ascii=False)

        assert summary["status"] == "WAITING_USER"
        assert summary["next_step"] == "请回复工单类型、标题及详细描述。"
        assert summary["missing_fields"] == ["ticket_type", "title", "description"]
        assert "evidence://safe/ref" in encoded
        assert "<redacted" in encoded
        assert "https://signed.example.test" not in encoded
        assert "X-Amz-Signature" not in encoded
        assert "sk-secret-token" not in encoded
        assert "SYSTEM PROMPT SECRET" not in encoded
        assert "secret-token" not in encoded
