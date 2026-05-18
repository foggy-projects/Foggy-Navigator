"""Tests for FileFrameJournal — file-based Frame persistence."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal


def _make_frame(
    frame_id: str = "frm_001",
    task_id: str = "task_aaa",
    skill_id: str = "test_skill",
    status: FrameStatus = FrameStatus.RUNNING,
    conversation_id: str | None = None,
) -> SkillFrameState:
    return SkillFrameState(
        frame_id=frame_id,
        task_id=task_id,
        skill_id=skill_id,
        status=status,
        conversation_id=conversation_id,
        started_at="2026-01-01T00:00:00Z",
    )


class TestSaveAndLoad:
    def test_save_creates_json_file(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        frame = _make_frame()
        path = journal.save(frame)

        assert path.exists()
        assert path.suffix == ".json"
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["frame_id"] == "frm_001"
        assert data["task_id"] == "task_aaa"
        assert data["status"] == "RUNNING"

    def test_load_returns_frame(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        original = _make_frame()
        journal.save(original)

        loaded = journal.load("task_aaa", "frm_001")
        assert loaded is not None
        assert loaded.frame_id == original.frame_id
        assert loaded.status == original.status
        assert loaded.skill_id == original.skill_id

    def test_load_nonexistent_returns_none(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        assert journal.load("no_task", "no_frame") is None

    def test_overwrite_on_status_change(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        frame = _make_frame()
        journal.save(frame)

        frame.status = FrameStatus.COMPLETED
        frame.result_summary = "done"
        journal.save(frame)

        loaded = journal.load("task_aaa", "frm_001")
        assert loaded is not None
        assert loaded.status == FrameStatus.COMPLETED
        assert loaded.result_summary == "done"
        assert loaded.journal_seq is not None

    def test_save_assigns_monotonic_journal_sequence(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        first = _make_frame(frame_id="frm_001", conversation_id="conv-1")
        second = _make_frame(frame_id="frm_002", conversation_id="conv-1")

        journal.save(first)
        journal.save(second)

        assert first.journal_seq == 1
        assert second.journal_seq == 2
        assert first.journal_updated_at
        frames = journal.load_by_conversation("conv-1")
        assert [frame.frame_id for frame in frames] == ["frm_001", "frm_002"]


class TestLoadByTask:
    def test_load_multiple_frames(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001"))
        journal.save(_make_frame(frame_id="frm_002"))

        frames = journal.load_by_task("task_aaa")
        assert len(frames) == 2
        ids = {f.frame_id for f in frames}
        assert ids == {"frm_001", "frm_002"}

    def test_load_by_task_empty(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        assert journal.load_by_task("nonexistent") == []

    def test_load_by_task_isolates_tasks(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001", task_id="task_aaa"))
        journal.save(_make_frame(frame_id="frm_002", task_id="task_bbb"))

        frames_a = journal.load_by_task("task_aaa")
        frames_b = journal.load_by_task("task_bbb")
        assert len(frames_a) == 1
        assert len(frames_b) == 1
        assert frames_a[0].frame_id == "frm_001"
        assert frames_b[0].frame_id == "frm_002"


class TestFindAwaitingApproval:
    def test_finds_awaiting_frame(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001", status=FrameStatus.COMPLETED))
        journal.save(_make_frame(frame_id="frm_002", status=FrameStatus.AWAITING_APPROVAL))

        found = journal.find_awaiting_approval("task_aaa")
        assert found is not None
        assert found.frame_id == "frm_002"

    def test_prefers_parent_skill_with_bubbled_child_approval(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        child = _make_frame(
            frame_id="frm_child",
            status=FrameStatus.AWAITING_APPROVAL,
        )
        parent = _make_frame(
            frame_id="frm_parent",
            status=FrameStatus.AWAITING_APPROVAL,
        )
        parent.private_working_state["pending_child_approval_frame_id"] = child.frame_id
        function = _make_frame(
            frame_id="frm_function",
            skill_id="tms.vehicle.create",
            status=FrameStatus.AWAITING_APPROVAL,
        )
        function.frame_kind = FrameKind.FUNCTION_CALL

        journal.save(child)
        journal.save(function)
        journal.save(parent)

        found = journal.find_awaiting_approval("task_aaa")
        assert found is not None
        assert found.frame_id == "frm_parent"

    def test_returns_none_when_no_awaiting(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(status=FrameStatus.COMPLETED))

        assert journal.find_awaiting_approval("task_aaa") is None

    def test_returns_none_for_empty_task(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        assert journal.find_awaiting_approval("nonexistent") is None


class TestDeleteAndCleanup:
    def test_delete_single_frame(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame())
        journal.delete("task_aaa", "frm_001")

        assert journal.load("task_aaa", "frm_001") is None

    def test_delete_nonexistent_safe(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.delete("no_task", "no_frame")  # should not raise

    def test_cleanup_task_removes_directory(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001"))
        journal.save(_make_frame(frame_id="frm_002"))

        task_dir = tmp_path / "frames" / "task_aaa"
        assert task_dir.is_dir()

        journal.cleanup_task("task_aaa")
        assert not task_dir.exists()


class TestCorruptFileHandling:
    def test_corrupt_json_returns_none(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        # Write a valid frame first to create the directory
        journal.save(_make_frame())
        # Corrupt the file
        file_path = tmp_path / "frames" / "task_aaa" / "frm_001.json"
        file_path.write_text("not valid json {{{", encoding="utf-8")

        loaded = journal.load("task_aaa", "frm_001")
        assert loaded is None

    def test_corrupt_file_skipped_in_load_by_task(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001"))
        journal.save(_make_frame(frame_id="frm_002"))

        # Corrupt one file
        file_path = tmp_path / "frames" / "task_aaa" / "frm_001.json"
        file_path.write_text("broken", encoding="utf-8")

        frames = journal.load_by_task("task_aaa")
        assert len(frames) == 1
        assert frames[0].frame_id == "frm_002"


class TestRuntimeIntegration:
    """Test that SkillRuntime writes to journal when configured."""

    def test_invoke_skill_writes_journal(self, tmp_path):
        from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
        from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)

        frame_id = runtime.invoke_skill("task_t1", "some_skill")

        # File should exist
        loaded = journal.load("task_t1", frame_id)
        assert loaded is not None
        assert loaded.status == FrameStatus.RUNNING

    def test_submit_result_writes_journal(self, tmp_path):
        from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)

        frame_id = runtime.invoke_skill("task_t2", "unregistered_skill")
        runtime.submit_result(
            frame_id,
            summary="done",
            structured_output={"key": "val"},
        )

        loaded = journal.load("task_t2", frame_id)
        assert loaded is not None
        assert loaded.status == FrameStatus.COMPLETED

    def test_no_journal_no_error(self, tmp_path):
        """Runtime without journal should work as before (backward compat)."""
        from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

        runtime = SkillRuntime()  # no journal
        frame_id = runtime.invoke_skill("task_t3", "some_skill")
        assert runtime.get_frame(frame_id) is not None
