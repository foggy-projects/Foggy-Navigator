"""Tests for POST /api/v1/resume endpoint and approval flow."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.skill_runtime import (
    IllegalStateTransition,
    SkillRuntime,
)


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
        fid = self.runtime.invoke_skill("task_resume", "skill_a")
        self.runtime.mark_awaiting_approval(fid, {"approval_type": "test"})

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_resume",
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

    async def test_resume_not_found(self, client):
        resp = await client.post("/api/v1/resume", json={
            "taskId": "nonexistent_task",
            "approvalResult": "approved",
        })
        assert resp.status_code == 404

    async def test_resume_no_awaiting_frame(self, client):
        # Frame is RUNNING, not AWAITING_APPROVAL
        self.runtime.invoke_skill("task_running", "skill_a")

        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_running",
            "approvalResult": "approved",
        })
        assert resp.status_code == 404

    async def test_resume_restores_from_file_after_memory_clear(self, client):
        # Create and suspend a frame
        fid = self.runtime.invoke_skill("task_restore", "skill_a")
        self.runtime.mark_awaiting_approval(fid, {"approval_type": "test"})

        # Clear in-memory store (simulate restart)
        self.runtime.store.clear()
        assert self.runtime.get_frame(fid) is None

        # Resume should restore from file
        resp = await client.post("/api/v1/resume", json={
            "taskId": "task_restore",
            "approvalResult": "approved",
        })
        assert resp.status_code == 200
        assert resp.json()["frame_id"] == fid

        # Frame should be back in memory
        frame = self.runtime.get_frame(fid)
        assert frame is not None
        assert frame.status == FrameStatus.RUNNING
