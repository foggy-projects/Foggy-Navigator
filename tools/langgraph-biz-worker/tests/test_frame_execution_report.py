"""Tests for offline Frame execution report generation."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillFrameState, SkillManifest
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_execution_report import (
    FrameExecutionReportGenerator,
    read_frame_execution_report,
)
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_skill_agent import LlmSkillAgent
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


def _make_frame(
    *,
    frame_id: str = "frm_parent",
    task_id: str = "task_001",
    skill_id: str = "order_evidence_collect",
    status: FrameStatus = FrameStatus.COMPLETED,
) -> SkillFrameState:
    return SkillFrameState(
        frame_id=frame_id,
        task_id=task_id,
        skill_id=skill_id,
        status=status,
        conversation_id="conv_001",
        session_id="session_001",
        input={"order_id": 501},
        output={"order_id": 501, "status": "OK"},
        result_summary="Collected evidence for order 501.",
        artifact_refs=["artifact:order:501"],
        evidence_refs=["order:501"],
        started_at="2026-05-17T01:00:00+00:00",
        ended_at="2026-05-17T01:01:00+00:00",
    )


def _runtime_with_journal(data_root) -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="test_skill",
        name="Test Skill",
        output_schema={
            "type": "object",
            "required": ["result"],
            "properties": {"result": {"type": "string"}},
        },
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    return SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(data_root),
    )


class _NoopModel:
    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        raise AssertionError("model should not be invoked")


def test_generate_report_from_completed_skill_frame(tmp_path):
    journal = FileFrameJournal(tmp_path)
    journal.save(_make_frame())

    report = FrameExecutionReportGenerator(tmp_path).generate_for_frame("task_001", "frm_parent")

    assert report.markdown_path.exists()
    assert report.digest_path.exists()
    assert report.conversation_markdown_path is not None
    assert report.conversation_markdown_path.exists()
    assert "Collected evidence for order 501." in report.markdown
    assert "`order_evidence_collect`" in report.markdown
    assert "`order:501`" in report.markdown
    assert report.digest["report_ref"] == "frame-report://task_001/frm_parent"
    assert report.digest["status"] == "COMPLETED"
    assert report.digest["summary"] == "Collected evidence for order 501."


def test_generate_report_includes_child_frame_digest(tmp_path):
    journal = FileFrameJournal(tmp_path)
    child = _make_frame(
        frame_id="frm_child",
        skill_id="address_verify",
    )
    child.result_summary = "Address verified."
    parent = _make_frame()
    parent.child_frame_ids = [child.frame_id]
    journal.save(child)
    journal.save(parent)

    report = FrameExecutionReportGenerator(tmp_path).generate_for_frame("task_001", "frm_parent")

    child_report_path = tmp_path / "frame-reports" / "task_001" / "frm_child.md"
    assert child_report_path.exists()
    assert "| frm_child | address_verify | SKILL | COMPLETED | Address verified." in report.markdown
    assert report.digest["child_reports"][0]["report_ref"] == "frame-report://task_001/frm_child"
    assert report.digest["child_reports"][0]["summary"] == "Address verified."


def test_generate_report_redacts_sensitive_tool_args(tmp_path):
    journal = FileFrameJournal(tmp_path)
    frame = _make_frame()
    frame.private_messages.append({
        "role": "tool_call",
        "content": {
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.vehicle.create",
                "access_token": "token-should-not-leak",
                "password": "password-should-not-leak",
                "nested": {"Authorization": "Bearer should-not-leak"},
            },
        },
    })
    journal.save(frame)

    report = FrameExecutionReportGenerator(tmp_path).generate_for_frame("task_001", "frm_parent")

    assert "token-should-not-leak" not in report.markdown
    assert "password-should-not-leak" not in report.markdown
    assert "Bearer should-not-leak" not in report.markdown
    assert report.markdown.count("<redacted>") >= 3
    assert report.digest["tool_call_count"] == 1


def test_generate_report_truncates_large_payload(tmp_path):
    journal = FileFrameJournal(tmp_path)
    frame = _make_frame()
    frame.output = {"large_payload": "x" * 240}
    journal.save(frame)

    report = FrameExecutionReportGenerator(tmp_path, max_field_chars=80).generate_for_frame(
        "task_001",
        "frm_parent",
    )

    assert "x" * 120 not in report.markdown
    assert "<truncated" in report.markdown
    assert '"large_payload"' in report.markdown


def test_generate_report_from_frame_path(tmp_path):
    journal = FileFrameJournal(tmp_path)
    frame = _make_frame()
    path = journal.save(frame)

    report = FrameExecutionReportGenerator(tmp_path).generate_from_path(path)

    assert report.markdown_path.exists()
    assert report.digest["source_path"] == str(path)
    persisted = json.loads(report.digest_path.read_text(encoding="utf-8"))
    assert persisted["frame_id"] == "frm_parent"


def test_generate_report_for_awaiting_approval_includes_suspend_id(tmp_path):
    journal = FileFrameJournal(tmp_path)
    frame = _make_frame(
        frame_id="fn_vehicle_create",
        skill_id="__function__:tms.vehicle.create",
        status=FrameStatus.AWAITING_APPROVAL,
    )
    frame.frame_kind = FrameKind.FUNCTION_CALL
    frame.approval_request = {
        "suspend_id": "sus_001",
        "approval_type": "business_function",
        "reason": "Create vehicle requires approval.",
        "summary": {"function_id": "tms.vehicle.create", "token": "approval-token"},
    }
    journal.save(frame)

    report = FrameExecutionReportGenerator(tmp_path).generate_for_frame(
        "task_001",
        "fn_vehicle_create",
    )

    assert report.digest["approval_required"] is True
    assert report.digest["approval"]["suspend_id"] == "sus_001"
    assert "sus_001" in report.markdown
    assert "approval-token" not in report.markdown
    assert "<redacted>" in report.markdown


def test_generate_report_missing_frame_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        FrameExecutionReportGenerator(tmp_path).generate_for_frame("task_missing", "frm_missing")


def test_read_frame_execution_report_generates_missing_report(tmp_path):
    journal = FileFrameJournal(tmp_path)
    journal.save(_make_frame())

    result = read_frame_execution_report(
        tmp_path,
        report_ref="frame-report://task_001/frm_parent",
        mode="summary",
    )

    assert result["ok"] is True
    assert result["summary"]["status"] == "COMPLETED"
    assert result["summary"]["summary"] == "Collected evidence for order 501."
    assert (tmp_path / "frame-reports" / "task_001" / "frm_parent.md").exists()


def test_runtime_generates_report_and_promotes_ref_on_close(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    frame_id = runtime.invoke_skill("task_runtime_report", "test_skill")
    frame = runtime.get_frame(frame_id)
    frame.private_messages.append({
        "role": "tool_call",
        "content": {
            "name": "invoke_business_function",
            "args": {"function_id": "demo.create", "token": "must-redact"},
        },
    })
    runtime.store.save(frame)

    result = runtime.submit_result(frame_id, "runtime report done", {"result": "success"})
    frame = runtime.get_frame(frame_id)

    assert result.ok
    assert frame.private_working_state["execution_report_ref"] == (
        f"frame-report://task_runtime_report/{frame_id}"
    )
    assert (tmp_path / "frame-reports" / "task_runtime_report" / f"{frame_id}.md").exists()

    promoted = runtime.close_frame(frame_id)
    assert promoted["execution_report_ref"] == f"frame-report://task_runtime_report/{frame_id}"
    assert promoted["execution_report_digest"]["status"] == "COMPLETED"
    assert runtime.get_frame(frame_id).private_working_state == {}

    report_text = (tmp_path / "frame-reports" / "task_runtime_report" / f"{frame_id}.md").read_text(
        encoding="utf-8",
    )
    assert "invoke_business_function" in report_text
    assert "must-redact" not in report_text


def test_runtime_links_child_report_to_active_plan_step(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    parent_id = runtime.invoke_skill("task_plan_report", "test_skill")
    parent = runtime.get_frame(parent_id)
    parent.private_working_state["active_plan"] = {
        "goal": "finish child work",
        "status": "IN_PROGRESS",
        "steps": [{"step_id": "collect", "status": "IN_PROGRESS"}],
    }
    parent.private_working_state["root_context_summary"] = {}
    runtime.store.save(parent)
    child_id = runtime.invoke_skill("task_plan_report", "test_skill", parent_frame_id=parent_id)
    runtime.submit_result(child_id, "child report done", {"result": "success", "step_id": "collect"})

    promoted = runtime.close_frame(child_id)
    runtime.write_child_result_to_parent(parent_id, child_id, promoted)
    parent = runtime.get_frame(parent_id)
    step = parent.private_working_state["active_plan"]["steps"][0]

    assert step["execution_report_ref"] == f"frame-report://task_plan_report/{child_id}"
    assert step["execution_report_digest"]["summary"] == "child report done"
    assert parent.private_working_state["root_context_summary"]["execution_reports"][-1]["report_ref"] == (
        f"frame-report://task_plan_report/{child_id}"
    )


def test_function_call_approval_report_is_readable(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    parent_id = runtime.invoke_skill("task_function_report", "test_skill")
    function_frame_id = runtime.invoke_function_call(
        parent_id,
        "tms.vehicle.create",
        "v1",
        arguments={"vehicleType": 1},
    )
    runtime.suspend_function_call(
        function_frame_id,
        {
            "suspend_id": "sus_report",
            "approval_type": "business_function",
            "reason": "approval required",
        },
    )
    function_frame = runtime.get_frame(function_frame_id)

    assert function_frame.private_working_state["execution_report_ref"] == (
        f"frame-report://task_function_report/{function_frame_id}"
    )

    result = read_frame_execution_report(
        tmp_path,
        task_id="task_function_report",
        frame_id=function_frame_id,
        mode="summary",
    )
    assert result["ok"] is True
    assert result["summary"]["status"] == "AWAITING_APPROVAL"
    assert result["summary"]["approval"]["suspend_id"] == "sus_report"


def test_business_function_result_finalizes_child_and_root_reports(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    root_id = runtime.invoke_skill("task_business_result_report", "system.root")
    child_id = runtime.invoke_child_skill(root_id, "child_skill")
    function_frame_id = runtime.invoke_function_call(
        child_id,
        "tms.vehicle.create",
        "v1",
        arguments={"vehicleType": 1},
    )
    approval_request = {
        "approval_type": "business_function",
        "function_id": "tms.vehicle.create",
        "version": "v1",
        "suspend_id": "sus_business_result",
        "summary": {"title": "Approval required"},
        "payload": {"function_frame_id": function_frame_id},
    }
    runtime.suspend_function_call(function_frame_id, approval_request)
    runtime.mark_awaiting_approval(child_id, approval_request)
    runtime.mark_child_awaiting_approval(root_id, child_id, approval_request)
    runtime.resume_from_approval(root_id, "approved", "ok")

    update = runtime.finalize_business_function_result(
        "task_business_result_report",
        "sus_business_result",
        success=True,
        result={"status": "SUCCESS", "executionStatus": "COMPLETED"},
        summary="Business function execution completed.",
    )

    assert update["ok"] is True
    assert update["function_execution_report_digest"]["status"] == "COMPLETED"
    assert update["child_execution_report_digest"]["status"] == "COMPLETED"
    assert update["root_execution_report_digest"]["status"] == "COMPLETED"
    assert update["closed_skill_frames"][0]["frame_id"] == child_id
    assert update["closed_skill_frames"][0]["execution_report_ref"] == (
        f"frame-report://task_business_result_report/{child_id}"
    )
    assert runtime.get_frame(function_frame_id).status == FrameStatus.COMPLETED
    assert runtime.get_frame(child_id).status == FrameStatus.COMPLETED
    assert runtime.get_frame(root_id).status == FrameStatus.COMPLETED

    root_report = read_frame_execution_report(
        tmp_path,
        task_id="task_business_result_report",
        frame_id=root_id,
        mode="summary",
    )
    assert root_report["summary"]["status"] == "COMPLETED"
    assert root_report["summary"]["child_reports"][0]["status"] == "COMPLETED"


def test_business_function_result_failure_finalizes_root_report(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    root_id = runtime.invoke_skill("task_business_failure_report", "system.root")
    function_frame_id = runtime.invoke_function_call(
        root_id,
        "tms.vehicle.create",
        "v1",
        arguments={"vehicleType": 1},
    )
    approval_request = {
        "approval_type": "business_function",
        "function_id": "tms.vehicle.create",
        "version": "v1",
        "suspend_id": "sus_business_failure",
        "summary": {"title": "Approval required"},
        "payload": {"function_frame_id": function_frame_id},
    }
    runtime.suspend_function_call(function_frame_id, approval_request)
    runtime.mark_awaiting_approval(root_id, approval_request)
    runtime.resume_from_approval(root_id, "approved", "ok")

    update = runtime.finalize_business_function_result(
        "task_business_failure_report",
        "sus_business_failure",
        success=False,
        result={"status": "FAILED", "executionStatus": "FAILED"},
        summary="Business function execution failed.",
        error_message="adapter failed",
    )

    assert update["ok"] is True
    assert update["function_execution_report_digest"]["status"] == "FAILED"
    assert update["root_execution_report_digest"]["status"] == "FAILED"
    assert runtime.get_frame(function_frame_id).status == FrameStatus.FAILED
    assert runtime.get_frame(root_id).status == FrameStatus.FAILED

    root_report = read_frame_execution_report(
        tmp_path,
        task_id="task_business_failure_report",
        frame_id=root_id,
        mode="summary",
    )
    assert root_report["summary"]["status"] == "FAILED"
    assert root_report["summary"]["summary"] == "Business function execution failed."


def test_llm_tool_reads_frame_execution_report(tmp_path):
    runtime = _runtime_with_journal(tmp_path)
    frame_id = runtime.invoke_skill("task_tool_report", "test_skill")
    runtime.submit_result(frame_id, "tool readable", {"result": "success"})
    report_ref = runtime.get_frame(frame_id).private_working_state["execution_report_ref"]

    agent = LlmSkillAgent(_NoopModel(), runtime, data_root=tmp_path)
    result = agent._call_tool(
        frame_id,
        "read_frame_execution_report",
        {"report_ref": report_ref, "mode": "metadata"},
        task_id="task_tool_report",
    )

    assert result["ok"] is True
    assert result["digest"]["status"] == "COMPLETED"
    assert result["digest"]["summary"] == "tool readable"
