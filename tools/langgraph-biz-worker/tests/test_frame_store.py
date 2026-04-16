"""Tests for FrameStore direct operations (P1)."""

import pytest

from langgraph_biz_worker.models import FrameStatus, SkillFrameState
from langgraph_biz_worker.runtime.frame_store import FrameStore


@pytest.fixture
def store() -> FrameStore:
    return FrameStore()


def _make_frame(frame_id: str, task_id: str = "t1", skill_id: str = "s1") -> SkillFrameState:
    return SkillFrameState(frame_id=frame_id, task_id=task_id, skill_id=skill_id)


class TestFrameStoreCRUD:
    def test_save_and_get(self, store):
        frame = _make_frame("f1")
        store.save(frame)
        assert store.get("f1") is not None
        assert store.get("f1").frame_id == "f1"

    def test_get_nonexistent(self, store):
        assert store.get("nonexistent") is None

    def test_overwrite_existing(self, store):
        frame = _make_frame("f1")
        store.save(frame)
        frame.status = FrameStatus.RUNNING
        store.save(frame)
        assert store.get("f1").status == FrameStatus.RUNNING

    def test_delete_existing(self, store):
        store.save(_make_frame("f1"))
        store.delete("f1")
        assert store.get("f1") is None

    def test_delete_nonexistent_safe(self, store):
        """Deleting a non-existent frame should not raise."""
        store.delete("nonexistent")  # Should not raise

    def test_get_by_task(self, store):
        store.save(_make_frame("f1", task_id="t1"))
        store.save(_make_frame("f2", task_id="t1"))
        store.save(_make_frame("f3", task_id="t2"))
        assert len(store.get_by_task("t1")) == 2
        assert len(store.get_by_task("t2")) == 1

    def test_get_by_task_empty(self, store):
        assert store.get_by_task("nonexistent") == []

    def test_list_all(self, store):
        store.save(_make_frame("f1"))
        store.save(_make_frame("f2"))
        assert len(store.list_all()) == 2

    def test_clear(self, store):
        store.save(_make_frame("f1"))
        store.save(_make_frame("f2"))
        store.clear()
        assert store.list_all() == []
        assert store.get("f1") is None

    def test_delete_cleans_task_index(self, store):
        store.save(_make_frame("f1", task_id="t1"))
        store.save(_make_frame("f2", task_id="t1"))
        store.delete("f1")
        frames = store.get_by_task("t1")
        assert len(frames) == 1
        assert frames[0].frame_id == "f2"

    def test_no_duplicate_in_task_index(self, store):
        frame = _make_frame("f1", task_id="t1")
        store.save(frame)
        store.save(frame)  # Save again
        assert len(store.get_by_task("t1")) == 1
