"""Tests for offline Frame execution report generation."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_execution_report import FrameExecutionReportGenerator


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
