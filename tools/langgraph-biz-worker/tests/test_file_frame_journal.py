"""Tests for FileFrameJournal — file-based Frame persistence."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.file_layout import session_data_dir

CTX_1 = "bctx_20260520_ab_conv_1"
CTX_2 = "bctx_20260520_cd_wrong_context"
CTX_JAN1 = "bctx_20260101_ab_cleanup_done"
CTX_JAN2 = "bctx_20260102_cd_cleanup_active"


def _make_frame(
    frame_id: str = "frm_001",
    task_id: str = "task_aaa",
    skill_id: str = "test_skill",
    status: FrameStatus = FrameStatus.RUNNING,
    conversation_id: str | None = CTX_1,
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
        session_dir = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1)
        assert path == session_dir / "frames" / "frm_001.json"
        assert frame.conversation_id == CTX_1
        assert not (tmp_path / "frames" / "task_aaa").exists()
        assert not (tmp_path / "runtime" / "frames").exists()
        assert not (tmp_path / "reports" / "frame-execution").exists()
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
        first = _make_frame(frame_id="frm_001", conversation_id=CTX_1)
        second = _make_frame(frame_id="frm_002", conversation_id=CTX_1)

        journal.save(first)
        journal.save(second)

        assert first.journal_seq is not None
        assert second.journal_seq is not None
        assert second.journal_seq > first.journal_seq
        assert first.journal_updated_at
        frames = journal.load_by_conversation(CTX_1)
        assert [frame.frame_id for frame in frames] == ["frm_001", "frm_002"]
        assert [frame.frame_id for frame in journal.load_by_task("task_aaa", conversation_id=CTX_1)] == [
            "frm_001",
            "frm_002",
        ]
        assert journal.load_by_task("task_aaa", conversation_id=CTX_2) == []
        session_dir = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1)
        assert (session_dir / "frames" / "frm_001.json").exists()
        assert (session_dir / "frames" / "frm_002.json").exists()
        assert not (tmp_path / "runtime" / "frames").exists()

    def test_same_conversation_tasks_share_session_directory(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        first_path = journal.save(_make_frame(frame_id="frm_001", task_id="task_1", conversation_id=CTX_1))
        second_path = journal.save(_make_frame(frame_id="frm_002", task_id="task_2", conversation_id=CTX_1))

        assert first_path.parent == second_path.parent
        assert first_path.parent == session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1) / "frames"
        assert [frame.frame_id for frame in journal.load_by_task("task_1")] == ["frm_001"]
        assert [frame.frame_id for frame in journal.load_by_conversation(CTX_1)] == ["frm_001", "frm_002"]

    def test_context_id_embedded_hash_selects_session_shard(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        context_id = "bctx_20260520_ab_1234567890abcdef"
        path = journal.save(_make_frame(conversation_id=context_id))

        assert path == (
            tmp_path / "runtime" / "sessions" / "by-date" / "2026" / "05" / "20"
            / "ab" / context_id / "frames" / "frm_001.json"
        )
        assert [frame.frame_id for frame in journal.load_by_conversation(context_id)] == ["frm_001"]

    def test_system_root_save_writes_session_index(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        frame = _make_frame(skill_id="system.root", conversation_id=CTX_1)
        frame.private_working_state["runtime_context_memory"] = {"revision": 3}

        journal.save(frame)

        index_path = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1) / "session.json"
        payload = json.loads(index_path.read_text(encoding="utf-8"))
        assert payload["contextId"] == CTX_1
        assert payload["rootFrameId"] == "frm_001"
        assert payload["rootSkillId"] == "system.root"
        assert payload["runtimeRevision"] == 3

    def test_load_root_by_conversation_uses_session_index_without_scan(self, tmp_path, monkeypatch):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(skill_id="system.root", conversation_id=CTX_1))

        def fail_scan(*args, **kwargs):
            raise AssertionError("session root lookup should not scan frames")

        monkeypatch.setattr(journal, "_scan_conversation_frame_paths", fail_scan)

        root = journal.load_root_by_conversation(CTX_1)

        assert root is not None
        assert root.frame_id == "frm_001"

    def test_load_root_by_conversation_fallback_rebuilds_session_index(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        child = _make_frame(frame_id="frm_child", skill_id="child", conversation_id=CTX_1)
        root = _make_frame(frame_id="frm_root", skill_id="system.root", conversation_id=CTX_1)
        journal.save(child)
        journal.save(root)
        index_path = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1) / "session.json"
        index_path.unlink()

        loaded = journal.load_root_by_conversation(CTX_1)

        assert loaded is not None
        assert loaded.frame_id == "frm_root"
        rebuilt = json.loads(index_path.read_text(encoding="utf-8"))
        assert rebuilt["rootFrameId"] == "frm_root"

    def test_load_root_history_by_conversation_uses_index_without_scan(self, tmp_path, monkeypatch):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_old", skill_id="system.root", conversation_id=CTX_1))
        journal.save(_make_frame(frame_id="frm_new", skill_id="system.root", conversation_id=CTX_1))
        index_path = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1) / "session.json"
        payload = json.loads(index_path.read_text(encoding="utf-8"))
        assert payload["rootFrameHistory"] == ["frm_old", "frm_new"]

        def fail_scan(*args, **kwargs):
            raise AssertionError("root history lookup should not scan frames")

        monkeypatch.setattr(journal, "_scan_conversation_frame_paths", fail_scan)

        roots = journal.load_root_history_by_conversation(CTX_1)

        assert [frame.frame_id for frame in roots] == ["frm_old", "frm_new"]

    def test_generates_standard_context_id_when_missing(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        frame = _make_frame(conversation_id=None)

        path = journal.save(frame)

        assert frame.conversation_id is not None
        assert frame.conversation_id.startswith("bctx_")
        assert path.parent.parent.name == frame.conversation_id
        assert not (session_data_dir(tmp_path, ("2026", "01", "01"), "task_aaa") / "frames").exists()

    def test_rejects_non_standard_conversation_id(self, tmp_path):
        journal = FileFrameJournal(tmp_path)

        with pytest.raises(ValueError, match="bctx_yyyyMMdd_<hash>_<id>"):
            journal.save(_make_frame(conversation_id="20260520-5fa4"))
        with pytest.raises(ValueError, match="bctx_yyyyMMdd_<hash>_<id>"):
            journal.save(_make_frame(conversation_id="bctx_20261340_ab_ctx_bad_date"))


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

        task_dir = session_data_dir(tmp_path, ("2026", "01", "01"), CTX_1) / "frames"
        assert task_dir.is_dir()

        journal.cleanup_task("task_aaa")
        assert not task_dir.exists()
        assert journal.load_by_task("task_aaa") == []

    def test_dry_run_cleanup_skips_recoverable_date_shards(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        completed = _make_frame(
            frame_id="frm_done",
            status=FrameStatus.COMPLETED,
            conversation_id=CTX_JAN1,
        )
        active = _make_frame(
            frame_id="frm_active",
            task_id="task_active",
            status=FrameStatus.AWAITING_USER,
            conversation_id=CTX_JAN2,
        )
        active.started_at = "2026-01-02T00:00:00Z"

        journal.save(completed)
        journal.save(active)

        plans = journal.dry_run_cleanup_before("2026-01-03")

        by_date = {plan["date"]: plan for plan in plans}
        assert by_date["2026-01-01"]["action"] == "delete"
        assert by_date["2026-01-02"]["action"] == "skip"
        assert by_date["2026-01-02"]["reason"] == "active_or_recoverable_frames"


class TestCorruptFileHandling:
    def test_corrupt_json_returns_none(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        # Write a valid frame first to create the directory
        file_path = journal.save(_make_frame())
        # Corrupt the file
        file_path.write_text("not valid json {{{", encoding="utf-8")

        loaded = journal.load("task_aaa", "frm_001")
        assert loaded is None

    def test_corrupt_file_skipped_in_load_by_task(self, tmp_path):
        journal = FileFrameJournal(tmp_path)
        journal.save(_make_frame(frame_id="frm_001"))
        journal.save(_make_frame(frame_id="frm_002"))

        # Corrupt one file
        file_path = journal.path_for_frame("task_aaa", "frm_001")
        assert file_path is not None
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

        frame_id = runtime.invoke_skill("task_t1", "some_skill", conversation_id=CTX_1)

        # File should exist
        loaded = journal.load("task_t1", frame_id)
        assert loaded is not None
        assert loaded.status == FrameStatus.RUNNING

    def test_submit_result_writes_journal(self, tmp_path):
        from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

        journal = FileFrameJournal(tmp_path)
        runtime = SkillRuntime(journal=journal)

        frame_id = runtime.invoke_skill("task_t2", "unregistered_skill", conversation_id=CTX_1)
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
