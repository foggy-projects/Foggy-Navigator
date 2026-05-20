"""Tests for POST /api/v1/resume endpoint and approval flow."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.skill_runtime import (
    IllegalStateTransition,
    SkillRuntime,
)
from langgraph_biz_worker.runtime.fsscript_bridge import FsscriptRunNotFound


def _make_frame(
    frame_id: str = "frm_001",
    task_id: str = "task_aaa",
    skill_id: str = "test_skill",
    status: FrameStatus = FrameStatus.RUNNING,
) -> SkillFrameState:
    return SkillFrameState(
        frame_id=frame_id,
        task_id=task_id,
        skill_id=skill_id,
        status=status,
        started_at="2026-01-01T00:00:00Z",
    )


# ---------------------------------------------------------------------------
# SkillRuntime: mark_awaiting_approval + resume_from_approval
# ---------------------------------------------------------------------------


class TestMarkAwaitingApproval:
    def test_running_to_awaiting(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)
        fid = runtime.invoke_skill("t1", "skill_a")

        runtime.mark_awaiting_approval(fid, {"approval_type": "manual_dispatch", "summary": "need confirmation"})

        frame = runtime.get_frame(fid)
        assert frame is not None
        assert frame.status == FrameStatus.AWAITING_APPROVAL
        assert frame.approval_request["approval_type"] == "manual_dispatch"

        # File journal should also have AWAITING_APPROVAL
        loaded = journal.find_awaiting_approval("t1")
        assert loaded is not None
        assert loaded.frame_id == fid

    def test_cannot_await_from_completed(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)
        fid = runtime.invoke_skill("t1", "unregistered_skill")
        runtime.submit_result(fid, "done", {"key": "val"})

        with pytest.raises(IllegalStateTransition):
            runtime.mark_awaiting_approval(fid, {"approval_type": "test"})


class TestResumeFromApproval:
    def test_awaiting_to_running(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)
        fid = runtime.invoke_skill("t1", "skill_a")
        runtime.mark_awaiting_approval(fid, {"approval_type": "manual"})

        runtime.resume_from_approval(fid, "approved", "looks good")

        frame = runtime.get_frame(fid)
        assert frame is not None
        assert frame.status == FrameStatus.RUNNING
        assert frame.approval_request is None
        assert frame.private_working_state["approval_result"] == "approved"
        assert frame.private_working_state["approval_comment"] == "looks good"

    def test_cannot_resume_from_running(self, tmp_path):
        runtime = SkillRuntime(journal=FileFrameJournal(tmp_path))
        fid = runtime.invoke_skill("t1", "skill_a")

        with pytest.raises(IllegalStateTransition):
            runtime.resume_from_approval(fid, "approved")


# ---------------------------------------------------------------------------
# Full approval flow: mark → file persist → restore → resume
# ---------------------------------------------------------------------------


class TestFullApprovalRecoveryFlow:
    """Simulate Worker restart: memory cleared, frame recovered from file."""

    def test_resume_after_memory_clear(self, tmp_path):
        journal = FileFrameJournal(tmp_path)

        # Phase 1: Worker runs, frame reaches AWAITING_APPROVAL
        runtime1 = SkillRuntime(journal=journal)
        fid = runtime1.invoke_skill("task_x", "skill_a")
        runtime1.mark_awaiting_approval(fid, {"approval_type": "dispatch"})

        # Phase 2: Simulate Worker restart — new runtime, empty memory
        runtime2 = SkillRuntime(journal=journal)
        assert runtime2.get_frame(fid) is None  # memory is empty

        # Phase 3: Resume via file journal (as resume endpoint would do)
        suspended = journal.find_awaiting_approval("task_x")
        assert suspended is not None
        assert suspended.frame_id == fid

        # Restore into memory
        runtime2.store.save(suspended)
        runtime2.resume_from_approval(fid, "approved", "ok to proceed")

        frame = runtime2.get_frame(fid)
        assert frame is not None
        assert frame.status == FrameStatus.RUNNING
        assert frame.private_working_state["approval_result"] == "approved"

    def test_no_awaiting_frame_returns_none(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)
        runtime.invoke_skill("task_y", "skill_b")
        # Frame is RUNNING, not AWAITING_APPROVAL

        assert journal.find_awaiting_approval("task_y") is None

    def test_resume_restores_pending_function_frame(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime1 = SkillRuntime(journal=journal)
        caller_frame_id = runtime1.invoke_skill("task_fn_restore", "system.root")
        function_frame_id = runtime1.invoke_function_call(
            parent_frame_id=caller_frame_id,
            function_id="tms.order.close",
            version="v1",
            arguments={"orderNo": "ORD-001"},
        )
        approval_request = {
            "approval_type": "business_function",
            "function_id": "tms.order.close",
            "version": "v1",
            "suspend_id": "sus_restore",
            "summary": {"title": "Approval required"},
            "payload": {"function_frame_id": function_frame_id},
            "resolved": False,
        }
        runtime1.suspend_function_call(function_frame_id, approval_request)
        runtime1.mark_awaiting_approval(caller_frame_id, approval_request)

        runtime2 = SkillRuntime(journal=journal)
        caller = journal.find_awaiting_approval("task_fn_restore")
        runtime2.restore_frame(caller)

        runtime2.resume_from_approval(caller_frame_id, "approved", "ok")

        restored_function = runtime2.get_frame(function_frame_id)
        assert restored_function is not None
        assert restored_function.status == FrameStatus.COMPLETED
        assert restored_function.output["status"] == "RESUME_DISPATCHED"

    def test_resume_restores_bubbled_child_approval_frame(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        runtime1 = SkillRuntime(journal=journal)
        root_frame_id = runtime1.invoke_skill("task_child_fn_restore", "system.root")
        child_frame_id = runtime1.invoke_child_skill(root_frame_id, "child_skill")
        function_frame_id = runtime1.invoke_function_call(
            parent_frame_id=child_frame_id,
            function_id="tms.vehicle.create",
            version="v1",
            arguments={"plateNo": "A-001"},
        )
        approval_request = {
            "approval_type": "business_function",
            "function_id": "tms.vehicle.create",
            "version": "v1",
            "suspend_id": "sus_child_restore",
            "summary": {"title": "Approval required"},
            "payload": {"function_frame_id": function_frame_id},
            "resolved": False,
        }
        runtime1.suspend_function_call(function_frame_id, approval_request)
        runtime1.mark_awaiting_approval(child_frame_id, approval_request)
        runtime1.mark_child_awaiting_approval(root_frame_id, child_frame_id, approval_request)

        runtime2 = SkillRuntime(journal=journal)
        root = journal.find_awaiting_approval("task_child_fn_restore")
        assert root is not None
        assert root.frame_id == root_frame_id
        runtime2.restore_frame(root)

        runtime2.resume_from_approval(root_frame_id, "approved", "ok")

        restored_root = runtime2.get_frame(root_frame_id)
        restored_child = runtime2.get_frame(child_frame_id)
        restored_function = runtime2.get_frame(function_frame_id)
        assert restored_root.status == FrameStatus.RUNNING
        assert "pending_child_approval_frame_id" not in restored_root.private_working_state
        assert restored_child.status == FrameStatus.RUNNING
        assert restored_function.status == FrameStatus.COMPLETED
        assert restored_function.output["status"] == "RESUME_DISPATCHED"


# ---------------------------------------------------------------------------
# HTTP endpoint: POST /api/v1/resume
# ---------------------------------------------------------------------------


class TestResumeEndpoint:
    """Test the REST endpoint via httpx + ASGI transport."""

    @pytest.fixture(autouse=True)
    def _setup_resume_service(self, tmp_path):
        """Configure the resume route with a test runtime and journal."""
        from langgraph_biz_worker.routes import resume as resume_module

        self.journal = FileFrameJournal(tmp_path)
        self.runtime = SkillRuntime(journal=self.journal)
        resume_module.configure(self.runtime, self.journal)

        yield

        # Cleanup: reset globals
        resume_module._runtime = None
        resume_module._journal = None

    @pytest.fixture
    async def client(self):
        from httpx import ASGITransport, AsyncClient
        from langgraph_biz_worker.main import app

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c

    async def test_resume_success(self, client):
        # Create a frame in AWAITING_APPROVAL
        fid = self.runtime.invoke_skill(
            "task_resume",
            "skill_a",
            conversation_id="ctx_resume",
            session_id="sess_resume",
        )
        self.runtime.mark_awaiting_approval(fid, {"approval_type": "test"})

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_resume",
            "contextId": "ctx_resume",
            "sessionId": "sess_resume",
            "approvalResult": "approved",
            "comment": "lgtm",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["task_id"] == "task_resume"
        assert data["frame_id"] == fid
        assert data["status"] == "RUNNING"

        # Verify frame state
        frame = self.runtime.get_frame(fid)
        assert frame.status == FrameStatus.RUNNING

    async def test_resume_returns_call_time_post_approval_message(self, client):
        caller_frame_id = self.runtime.invoke_skill(
            "task_resume_msg",
            "system.root",
            conversation_id="ctx_resume_msg",
            session_id="sess_resume_msg",
        )
        function_frame_id = self.runtime.invoke_function_call(
            parent_frame_id=caller_frame_id,
            function_id="tms.order.close",
            version="v1",
            arguments={"orderNo": "ORD-001"},
        )
        approval_request = {
            "approval_type": "business_function",
            "function_id": "tms.order.close",
            "version": "v1",
            "suspend_id": "sus_msg_1",
            "summary": {"title": "Close order approval"},
            "payload": {
                "function_frame_id": function_frame_id,
                "input": {
                    "orderNo": "ORD-001",
                    "post_approval_message": {
                        "approved": "审批已通过，已继续提交关单申请。",
                        "rejected": "审批已拒绝，关单申请未提交。",
                    },
                },
            },
            "resolved": False,
        }
        self.runtime.suspend_function_call(function_frame_id, approval_request)
        self.runtime.mark_awaiting_approval(caller_frame_id, approval_request)

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_resume_msg",
            "contextId": "ctx_resume_msg",
            "sessionId": "sess_resume_msg",
            "approvalResult": "approved",
            "comment": "同意",
        })

        assert resp.status_code == 200
        data = resp.json()
        assert data["frame_id"] == caller_frame_id
        assert data["resume_message"]["content"] == "审批已通过，已继续提交关单申请。"
        assert data["resume_message"]["source"] == "function_input.approved"
        assert data["resume_message"]["approval_result"] == "approved"
        assert data["resume_message"]["comment"] == "同意"
        assert data["resume_message"]["function_id"] == "tms.order.close"
        assert data["resume_message"]["suspend_id"] == "sus_msg_1"
        assert data["resume_message"]["execution_report_ref"] == (
            f"frame-report://task_resume_msg/{function_frame_id}"
        )
        assert data["resume_message"]["execution_report_digest"]["status"] == "COMPLETED"

    async def test_resume_restores_function_frame_before_report_enrichment(self, client):
        caller_frame_id = self.runtime.invoke_skill(
            "task_resume_report_restore",
            "system.root",
            conversation_id="ctx_resume_report_restore",
            session_id="sess_resume_report_restore",
        )
        function_frame_id = self.runtime.invoke_function_call(
            parent_frame_id=caller_frame_id,
            function_id="tms.vehicle.create",
            version="v1",
            arguments={"plateNo": "A-001"},
        )
        approval_request = {
            "approval_type": "business_function",
            "function_id": "tms.vehicle.create",
            "version": "v1",
            "suspend_id": "sus_resume_report_restore",
            "summary": {"title": "Create vehicle approval"},
            "payload": {
                "function_frame_id": function_frame_id,
                "input": {
                    "plateNo": "A-001",
                    "post_approval_message": {
                        "approved": "审批已通过，已继续提交车辆创建申请。",
                    },
                },
            },
            "resolved": False,
        }
        self.runtime.suspend_function_call(function_frame_id, approval_request)
        self.runtime.mark_awaiting_approval(caller_frame_id, approval_request)

        self.runtime.store.clear()
        assert self.runtime.get_frame(caller_frame_id) is None
        assert self.runtime.get_frame(function_frame_id) is None

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_resume_report_restore",
            "contextId": "ctx_resume_report_restore",
            "sessionId": "sess_resume_report_restore",
            "approvalResult": "approved",
            "comment": "同意",
        })

        assert resp.status_code == 200
        data = resp.json()
        assert data["frame_id"] == caller_frame_id
        assert data["resume_message"]["content"] == "审批已通过，已继续提交车辆创建申请。"
        assert data["resume_message"]["execution_report_ref"] == (
            f"frame-report://task_resume_report_restore/{function_frame_id}"
        )
        assert data["resume_message"]["function_execution_report_ref"] == (
            f"frame-report://task_resume_report_restore/{function_frame_id}"
        )
        assert data["resume_message"]["execution_report_digest"]["status"] == "COMPLETED"

    async def test_resume_not_found(self, client):
        resp = await client.post("/api/v1/resume", json={
            "taskId": "nonexistent_task",
            "contextId": "ctx_missing",
            "approvalResult": "approved",
        })
        assert resp.status_code == 404

    async def test_resume_requires_context_id(self, client):
        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_missing_context",
            "approvalResult": "approved",
        })

        assert resp.status_code == 422

    async def test_resume_returns_fsscript_summary_post_approval_message(self, client):
        class FakeFsscriptBridge:
            def resume_task(self, task_id: str, approval_result: str, comment: str = ""):
                if task_id != "task_fsscript_msg":
                    raise FsscriptRunNotFound(task_id)
                return {
                    "script_run_id": "sr_msg_1",
                    "suspend_id": "sp_msg_1",
                    "status": "RUNNING",
                    "summary": {
                        "post_approval_message": {
                            "approved": "审批已通过，FSScript 已继续执行。",
                            "rejected": "审批已拒绝，FSScript 已停止执行。",
                        },
                    },
                }

        from langgraph_biz_worker.routes import resume as resume_module

        resume_module.configure(self.runtime, self.journal, FakeFsscriptBridge())

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_fsscript_msg",
            "contextId": "ctx_fsscript_msg",
            "approvalResult": "approved",
            "comment": "同意",
        })

        assert resp.status_code == 200
        data = resp.json()
        assert data["frame_id"] == "sr_msg_1"
        assert data["resume_message"]["content"] == "审批已通过，FSScript 已继续执行。"
        assert data["resume_message"]["source"] == "fsscript_summary.approved"
        assert data["resume_message"]["script_run_id"] == "sr_msg_1"
        assert data["resume_message"]["suspend_id"] == "sp_msg_1"

    async def test_resume_no_awaiting_frame(self, client):
        # Frame is RUNNING, not AWAITING_APPROVAL
        self.runtime.invoke_skill(
            "task_running",
            "skill_a",
            conversation_id="ctx_running",
            session_id="sess_running",
        )

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_running",
            "contextId": "ctx_running",
            "sessionId": "sess_running",
            "approvalResult": "approved",
        })
        assert resp.status_code == 404

    async def test_resume_restores_from_file_after_memory_clear(self, client):
        # Create and suspend a frame
        fid = self.runtime.invoke_skill(
            "task_restore",
            "skill_a",
            conversation_id="ctx_restore",
            session_id="sess_restore",
        )
        self.runtime.mark_awaiting_approval(fid, {"approval_type": "test"})

        # Clear in-memory store (simulate restart)
        self.runtime.store.clear()
        assert self.runtime.get_frame(fid) is None

        # Resume should restore from file
        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_restore",
            "contextId": "ctx_restore",
            "sessionId": "sess_restore",
            "approvalResult": "approved",
        })
        assert resp.status_code == 200
        assert resp.json()["frame_id"] == fid

        # Frame should be back in memory
        frame = self.runtime.get_frame(fid)
        assert frame is not None
        assert frame.status == FrameStatus.RUNNING
