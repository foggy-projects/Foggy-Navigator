"""Tests for persistence/jsonl_store.py — JSONL file-based event persistence.

Uses pytest's ``tmp_path`` fixture to operate on a temporary directory,
ensuring no I/O side effects between tests.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from agent_worker.persistence.jsonl_store import JsonlEventStore


@pytest.fixture
def store(tmp_path: Path) -> JsonlEventStore:
    """Create a JsonlEventStore backed by a temporary directory."""
    return JsonlEventStore(tmp_path)


# ---------------------------------------------------------------------------
# Append & Load
# ---------------------------------------------------------------------------

class TestAppendAndLoad:
    """append() persists events; load_events() reads them back."""

    def test_append_single_event(self, store: JsonlEventStore, tmp_path: Path):
        store.append("task-1", {"seq": 1, "type": "assistant_text", "content": "hi"})

        events_file = tmp_path / "events" / "task-1" / "events.jsonl"
        assert events_file.exists()

        loaded = store.load_events("task-1")
        assert len(loaded) == 1
        assert loaded[0]["content"] == "hi"

    def test_append_multiple_events(self, store: JsonlEventStore):
        for i in range(1, 6):
            store.append("task-1", {"seq": i, "type": "event", "n": i})

        loaded = store.load_events("task-1")
        assert len(loaded) == 5
        assert [e["n"] for e in loaded] == [1, 2, 3, 4, 5]

    def test_load_empty_task(self, store: JsonlEventStore):
        loaded = store.load_events("nonexistent-task")
        assert loaded == []

    def test_load_events_after_seq(self, store: JsonlEventStore):
        for i in range(1, 11):
            store.append("task-1", {"seq": i, "type": "event"})

        loaded = store.load_events("task-1", after_seq=7)
        assert len(loaded) == 3
        assert [e["seq"] for e in loaded] == [8, 9, 10]

    def test_load_events_after_seq_zero_returns_all(self, store: JsonlEventStore):
        for i in range(1, 4):
            store.append("task-1", {"seq": i, "type": "event"})

        loaded = store.load_events("task-1", after_seq=0)
        assert len(loaded) == 3

    def test_load_events_after_seq_beyond_max(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "type": "event"})
        store.append("task-1", {"seq": 2, "type": "event"})

        loaded = store.load_events("task-1", after_seq=100)
        assert loaded == []

    def test_separate_tasks_isolated(self, store: JsonlEventStore):
        store.append("task-a", {"seq": 1, "type": "a"})
        store.append("task-b", {"seq": 1, "type": "b"})

        loaded_a = store.load_events("task-a")
        loaded_b = store.load_events("task-b")
        assert len(loaded_a) == 1
        assert loaded_a[0]["type"] == "a"
        assert len(loaded_b) == 1
        assert loaded_b[0]["type"] == "b"


# ---------------------------------------------------------------------------
# Corrupt JSONL handling
# ---------------------------------------------------------------------------

class TestCorruptJsonl:
    """load_events() gracefully skips corrupt lines."""

    def test_corrupt_line_skipped(self, store: JsonlEventStore, tmp_path: Path):
        # Write valid + corrupt + valid lines
        task_dir = tmp_path / "events" / "task-1"
        task_dir.mkdir(parents=True)
        events_file = task_dir / "events.jsonl"
        events_file.write_text(
            '{"seq": 1, "type": "event"}\n'
            'this is not valid json\n'
            '{"seq": 2, "type": "event"}\n',
            encoding="utf-8",
        )

        loaded = store.load_events("task-1")
        assert len(loaded) == 2
        assert [e["seq"] for e in loaded] == [1, 2]

    def test_empty_lines_skipped(self, store: JsonlEventStore, tmp_path: Path):
        task_dir = tmp_path / "events" / "task-1"
        task_dir.mkdir(parents=True)
        events_file = task_dir / "events.jsonl"
        events_file.write_text(
            '{"seq": 1, "type": "event"}\n'
            '\n'
            '  \n'
            '{"seq": 2, "type": "event"}\n',
            encoding="utf-8",
        )

        loaded = store.load_events("task-1")
        assert len(loaded) == 2

    def test_event_without_seq_treated_as_zero(self, store: JsonlEventStore, tmp_path: Path):
        task_dir = tmp_path / "events" / "task-1"
        task_dir.mkdir(parents=True)
        events_file = task_dir / "events.jsonl"
        events_file.write_text(
            '{"type": "no_seq_event"}\n',
            encoding="utf-8",
        )

        # after_seq=0 should NOT include events with seq=0 (default)
        loaded = store.load_events("task-1", after_seq=0)
        assert len(loaded) == 0

        # after_seq=-1 should include them
        loaded_all = store.load_events("task-1", after_seq=-1)
        assert len(loaded_all) == 1


# ---------------------------------------------------------------------------
# Sequence numbers
# ---------------------------------------------------------------------------

class TestSequenceNumbers:
    """get_latest_seq() scans JSONL for the highest seq."""

    def test_empty_store_returns_zero(self, store: JsonlEventStore):
        assert store.get_latest_seq("nonexistent") == 0

    def test_after_appends(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "type": "e"})
        store.append("task-1", {"seq": 2, "type": "e"})
        store.append("task-1", {"seq": 3, "type": "e"})

        assert store.get_latest_seq("task-1") == 3

    def test_non_sequential_seq(self, store: JsonlEventStore):
        """get_latest_seq returns the max, even if they're not in order."""
        store.append("task-1", {"seq": 5, "type": "e"})
        store.append("task-1", {"seq": 2, "type": "e"})
        store.append("task-1", {"seq": 10, "type": "e"})

        assert store.get_latest_seq("task-1") == 10

    def test_corrupt_lines_ignored(self, store: JsonlEventStore, tmp_path: Path):
        task_dir = tmp_path / "events" / "task-1"
        task_dir.mkdir(parents=True)
        events_file = task_dir / "events.jsonl"
        events_file.write_text(
            '{"seq": 5, "type": "e"}\n'
            'not json\n'
            '{"seq": 3, "type": "e"}\n',
            encoding="utf-8",
        )

        assert store.get_latest_seq("task-1") == 5


# ---------------------------------------------------------------------------
# Closed marker
# ---------------------------------------------------------------------------

class TestClosedMarker:
    """mark_closed() / is_closed() manage the .closed sentinel file."""

    def test_not_closed_by_default(self, store: JsonlEventStore):
        assert store.is_closed("task-1") is False

    def test_mark_closed(self, store: JsonlEventStore, tmp_path: Path):
        store.mark_closed("task-1")
        assert store.is_closed("task-1") is True

        marker = tmp_path / "events" / "task-1" / ".closed"
        assert marker.exists()

    def test_mark_closed_idempotent(self, store: JsonlEventStore):
        store.mark_closed("task-1")
        store.mark_closed("task-1")
        assert store.is_closed("task-1") is True

    def test_mark_closed_without_events(self, store: JsonlEventStore):
        """Can mark closed even if no events were appended."""
        store.mark_closed("task-new")
        assert store.is_closed("task-new") is True


# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------

class TestCleanup:
    """cleanup() removes the entire task directory."""

    def test_cleanup_removes_directory(self, store: JsonlEventStore, tmp_path: Path):
        store.append("task-1", {"seq": 1, "type": "e"})
        store.mark_closed("task-1")

        task_dir = tmp_path / "events" / "task-1"
        assert task_dir.exists()

        store.cleanup("task-1")
        assert not task_dir.exists()

    def test_cleanup_nonexistent_task_noop(self, store: JsonlEventStore):
        """cleanup() does not raise on missing task."""
        store.cleanup("nonexistent-task")  # should not raise

    def test_cleanup_then_load_returns_empty(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "type": "e"})
        store.cleanup("task-1")

        assert store.load_events("task-1") == []
        assert store.get_latest_seq("task-1") == 0
        assert store.is_closed("task-1") is False


# ---------------------------------------------------------------------------
# Unicode & special characters
# ---------------------------------------------------------------------------

class TestUnicodeSupport:
    """Events can contain non-ASCII text (Chinese, emoji, etc.)."""

    def test_chinese_content(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "type": "text", "content": "你好世界"})
        loaded = store.load_events("task-1")
        assert loaded[0]["content"] == "你好世界"

    def test_emoji_content(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "type": "text", "content": "🎉 Done!"})
        loaded = store.load_events("task-1")
        assert loaded[0]["content"] == "🎉 Done!"

    def test_special_json_chars(self, store: JsonlEventStore):
        store.append("task-1", {"seq": 1, "content": 'path: "D:\\foo\\bar"'})
        loaded = store.load_events("task-1")
        assert loaded[0]["content"] == 'path: "D:\\foo\\bar"'


# ---------------------------------------------------------------------------
# Fail-safe behavior
# ---------------------------------------------------------------------------

class TestFailSafe:
    """I/O errors are logged, not raised."""

    def test_append_to_readonly_dir_does_not_raise(self, tmp_path: Path):
        """Verify append is fail-safe (logs warning, does not crash)."""
        import os
        import stat

        # Create a read-only directory so that mkdir inside it fails
        readonly_dir = tmp_path / "readonly_base"
        readonly_dir.mkdir()
        # Create a nested target that we'll try to write into
        target_base = readonly_dir / "nested"
        # On Windows: use a device-path prefix that is guaranteed invalid
        # On Unix: make parent read-only so mkdir fails
        if os.name == "nt":
            # NUL device path — cannot be created as a directory
            bad_store = JsonlEventStore(Path(r"\\.\NUL\impossible\path"))
        else:
            readonly_dir.chmod(stat.S_IRUSR | stat.S_IXUSR)
            bad_store = JsonlEventStore(readonly_dir / "nested")

        # Should not raise — just log a warning
        bad_store.append("task-1", {"seq": 1, "type": "e"})

        # Restore permissions for cleanup
        if os.name != "nt":
            readonly_dir.chmod(stat.S_IRWXU)

    def test_load_from_nonexistent_returns_empty(self, tmp_path: Path):
        # Use a path inside tmp_path that definitely does not exist
        store = JsonlEventStore(tmp_path / "does_not_exist")
        assert store.load_events("no-such-task") == []


# ---------------------------------------------------------------------------
# Alias mapping (foggy_task_id → worker task_id)
# ---------------------------------------------------------------------------

class TestAliasMapping:
    """register_alias() / resolve_alias() provide ID translation."""

    def test_register_and_resolve(self, store: JsonlEventStore, tmp_path: Path):
        store.register_alias("20260308-6b78", "6eef49a6-fa82-477b-9a71-cd2331e9b71a")

        resolved = store.resolve_alias("20260308-6b78")
        assert resolved == "6eef49a6-fa82-477b-9a71-cd2331e9b71a"

        # Alias file should exist
        alias_file = tmp_path / "aliases" / "20260308-6b78.alias"
        assert alias_file.exists()

    def test_resolve_unknown_returns_input(self, store: JsonlEventStore):
        """resolve_alias() returns the input unchanged if no alias exists."""
        resolved = store.resolve_alias("no-such-alias")
        assert resolved == "no-such-alias"

    def test_resolve_after_register_enables_persistence_lookup(
        self, store: JsonlEventStore
    ):
        """End-to-end: register alias, append events under worker ID,
        then query by foggy_task_id via alias resolution."""
        worker_id = "6eef49a6-fa82-477b-9a71-cd2331e9b71a"
        foggy_id = "20260308-6b78"

        # Register alias and store events under worker ID
        store.register_alias(foggy_id, worker_id)
        store.append(worker_id, {"seq": 1, "type": "text", "content": "hello"})
        store.append(worker_id, {"seq": 2, "type": "text", "content": "world"})
        store.mark_closed(worker_id)

        # Query by foggy_task_id — resolve alias first
        resolved = store.resolve_alias(foggy_id)
        assert resolved == worker_id
        assert store.get_latest_seq(resolved) == 2
        assert store.is_closed(resolved) is True
        events = store.load_events(resolved, after_seq=0)
        assert len(events) == 2

    def test_register_alias_idempotent(self, store: JsonlEventStore):
        """Re-registering the same alias overwrites cleanly."""
        store.register_alias("my-alias", "task-v1")
        store.register_alias("my-alias", "task-v2")

        assert store.resolve_alias("my-alias") == "task-v2"

    def test_multiple_aliases_independent(self, store: JsonlEventStore):
        """Different aliases can point to different tasks."""
        store.register_alias("alias-a", "task-1")
        store.register_alias("alias-b", "task-2")

        assert store.resolve_alias("alias-a") == "task-1"
        assert store.resolve_alias("alias-b") == "task-2"
