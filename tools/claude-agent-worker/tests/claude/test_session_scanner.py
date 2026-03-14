"""Tests for claude/session_scanner.py — JSONL session history scanning.

Tests use tmp_path to create synthetic JSONL files and mock the
``_claude_projects_dir`` path.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import pytest

from agent_worker.claude.session_scanner import (
    _parse_jsonl_head,
    count_session_messages,
    encode_path_to_project_dir,
    read_session_messages,
    rewind_session_conversation,
    scan_session_checkpoints,
    scan_sessions_for_cwd,
)


# ---------------------------------------------------------------------------
# encode_path_to_project_dir
# ---------------------------------------------------------------------------

class TestEncodePathToProjectDir:
    """Path encoding: each : \\ / becomes a single -."""

    def test_windows_drive_path(self):
        assert encode_path_to_project_dir("D:\\foo\\bar") == "D--foo-bar"

    def test_windows_deep_path(self):
        result = encode_path_to_project_dir("D:\\a\\b\\c\\d")
        assert result == "D--a-b-c-d"

    def test_unix_path(self):
        assert encode_path_to_project_dir("/home/user/project") == "-home-user-project"

    def test_forward_slash_path(self):
        assert encode_path_to_project_dir("D:/foo/bar") == "D--foo-bar"

    def test_mixed_separators(self):
        assert encode_path_to_project_dir("D:\\foo/bar\\baz") == "D--foo-bar-baz"

    def test_drive_root(self):
        assert encode_path_to_project_dir("D:\\") == "D--"


# ---------------------------------------------------------------------------
# _parse_jsonl_head
# ---------------------------------------------------------------------------

class TestParseJsonlHead:
    """Extract session metadata from the first lines of a JSONL file."""

    def test_valid_session(self, tmp_path: Path):
        f = tmp_path / "session.jsonl"
        f.write_text(
            json.dumps({
                "sessionId": "abc-123",
                "cwd": "D:\\projects",
                "slug": "my-project",
                "gitBranch": "main",
                "timestamp": "2026-01-15T10:00:00Z",
            }) + "\n",
            encoding="utf-8",
        )

        meta = _parse_jsonl_head(f)
        assert meta is not None
        assert meta["session_id"] == "abc-123"
        assert meta["cwd"] == "D:\\projects"
        assert meta["slug"] == "my-project"
        assert meta["git_branch"] == "main"
        assert meta["timestamp"] == "2026-01-15T10:00:00Z"

    def test_missing_cwd_returns_none(self, tmp_path: Path):
        f = tmp_path / "session.jsonl"
        f.write_text(json.dumps({"sessionId": "abc-123"}) + "\n", encoding="utf-8")

        meta = _parse_jsonl_head(f)
        assert meta is None

    def test_missing_session_id_returns_none(self, tmp_path: Path):
        f = tmp_path / "session.jsonl"
        f.write_text(json.dumps({"cwd": "/foo"}) + "\n", encoding="utf-8")

        meta = _parse_jsonl_head(f)
        assert meta is None

    def test_file_history_snapshot_skipped(self, tmp_path: Path):
        f = tmp_path / "session.jsonl"
        f.write_text(
            json.dumps({"type": "file-history-snapshot", "data": {}}) + "\n"
            + json.dumps({
                "sessionId": "abc-123",
                "cwd": "/foo",
                "timestamp": "2026-01-01T00:00:00Z",
            }) + "\n",
            encoding="utf-8",
        )

        meta = _parse_jsonl_head(f)
        assert meta is not None
        assert meta["session_id"] == "abc-123"

    def test_corrupt_json_graceful(self, tmp_path: Path):
        f = tmp_path / "session.jsonl"
        f.write_text(
            "not valid json\n"
            + json.dumps({
                "sessionId": "abc",
                "cwd": "/bar",
                "timestamp": "2026-01-01T00:00:00Z",
            }) + "\n",
            encoding="utf-8",
        )

        meta = _parse_jsonl_head(f)
        assert meta is not None
        assert meta["session_id"] == "abc"

    def test_nonexistent_file_returns_none(self, tmp_path: Path):
        meta = _parse_jsonl_head(tmp_path / "nonexistent.jsonl")
        assert meta is None

    def test_early_stop_with_all_fields(self, tmp_path: Path):
        """Stops reading after all fields are found."""
        lines = []
        lines.append(json.dumps({
            "sessionId": "s1",
            "cwd": "/foo",
            "slug": "myslug",
            "gitBranch": "develop",
            "timestamp": "2026-01-01T00:00:00Z",
        }))
        # These lines should never be read
        for i in range(100):
            lines.append(json.dumps({"extra": i}))

        f = tmp_path / "session.jsonl"
        f.write_text("\n".join(lines) + "\n", encoding="utf-8")

        meta = _parse_jsonl_head(f, max_lines=5)
        assert meta is not None
        assert meta["session_id"] == "s1"


# ---------------------------------------------------------------------------
# scan_sessions_for_cwd
# ---------------------------------------------------------------------------

class TestScanSessionsForCwd:
    """Scan Claude Code project directories for sessions matching a cwd."""

    def test_valid_session_found(self, tmp_path: Path):
        # Create directory structure: projects/{encoded}/session.jsonl
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True)

        session_file = project_dir / "abcdef01-2345-6789-abcd-ef0123456789.jsonl"
        session_file.write_text(
            json.dumps({
                "sessionId": "abcdef01-2345-6789-abcd-ef0123456789",
                "cwd": "D:\\projects",
                "slug": "test",
                "gitBranch": "main",
                "timestamp": "2026-01-15T10:00:00Z",
            }) + "\n",
            encoding="utf-8",
        )

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            sessions = scan_sessions_for_cwd("D:\\projects")

        assert len(sessions) == 1
        assert sessions[0]["session_id"] == "abcdef01-2345-6789-abcd-ef0123456789"
        assert sessions[0]["cwd"] == "D:\\projects"

    def test_no_project_dir(self, tmp_path: Path):
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            sessions = scan_sessions_for_cwd("D:\\nonexistent")

        assert sessions == []

    def test_non_uuid_file_skipped(self, tmp_path: Path):
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True)

        # Non-UUID filename should be skipped
        (project_dir / "not-a-uuid.jsonl").write_text("{}\n", encoding="utf-8")
        # Also create a valid session
        session_file = project_dir / "12345678-1234-1234-1234-123456789abc.jsonl"
        session_file.write_text(
            json.dumps({
                "sessionId": "12345678-1234-1234-1234-123456789abc",
                "cwd": "D:\\projects",
                "timestamp": "2026-01-01T00:00:00Z",
            }) + "\n",
            encoding="utf-8",
        )

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            sessions = scan_sessions_for_cwd("D:\\projects")

        assert len(sessions) == 1


# ---------------------------------------------------------------------------
# read_session_messages
# ---------------------------------------------------------------------------

class TestReadSessionMessages:
    """Read simplified conversation messages from a session JSONL."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_user_and_assistant_messages(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "Hello"}, "timestamp": "t1"},
            {"type": "assistant", "message": {"content": [
                {"type": "text", "text": "Hi there!"},
            ]}, "timestamp": "t2"},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)

        assert len(messages) == 2
        assert messages[0]["role"] == "user"
        assert messages[0]["content"] == "Hello"
        assert messages[1]["role"] == "assistant"
        assert messages[1]["content"] == "Hi there!"

    def test_sidechain_messages_excluded(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "Hello"}, "timestamp": "t1"},
            {"type": "assistant", "message": {"content": [
                {"type": "text", "text": "Original"},
            ]}, "timestamp": "t2"},
            {"type": "user", "message": {"content": "Sidechain"}, "isSidechain": True, "timestamp": "t3"},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)

        assert len(messages) == 2

    def test_nonexistent_session_returns_empty(self, tmp_path: Path):
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages("nonexistent-session-id")

        assert messages == []

    def test_tool_blocks_skipped(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Read", "input": {}},
                {"type": "text", "text": "After tool"},
            ]}, "timestamp": "t1"},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)

        assert len(messages) == 1
        assert messages[0]["content"] == "After tool"


# ---------------------------------------------------------------------------
# scan_session_checkpoints
# ---------------------------------------------------------------------------

class TestScanSessionCheckpoints:
    """Extract UserMessage UUIDs as checkpoints from a session JSONL."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_extracts_user_uuids(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "uuid": "u1", "timestamp": "t1"},
            {"type": "assistant", "message": {"content": []}},
            {"type": "user", "uuid": "u2", "timestamp": "t2"},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        assert len(checkpoints) == 2
        assert checkpoints[0] == {"id": "u1", "turnIndex": 1, "timestamp": "t1"}
        assert checkpoints[1] == {"id": "u2", "turnIndex": 2, "timestamp": "t2"}

    def test_sidechain_excluded(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "uuid": "u1", "timestamp": "t1"},
            {"type": "user", "uuid": "u2", "timestamp": "t2", "isSidechain": True},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        assert len(checkpoints) == 1

    def test_no_uuid_skipped(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "timestamp": "t1"},  # no uuid
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        assert checkpoints == []


# ---------------------------------------------------------------------------
# count_session_messages
# ---------------------------------------------------------------------------

class TestCountSessionMessages:
    """Count user/assistant messages in a session JSONL."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_counts_correctly(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user"},
            {"type": "assistant"},
            {"type": "user"},
            {"type": "assistant"},
            {"type": "system"},  # not counted
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = count_session_messages(sid)

        assert result == {"user_count": 2, "assistant_count": 2, "total": 4}

    def test_sidechain_excluded(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user"},
            {"type": "user", "isSidechain": True},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = count_session_messages(sid)

        assert result == {"user_count": 1, "assistant_count": 0, "total": 1}

    def test_nonexistent_session(self, tmp_path: Path):
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = count_session_messages("nonexistent")

        assert result == {"user_count": 0, "assistant_count": 0, "total": 0}


# ---------------------------------------------------------------------------
# rewind_session_conversation
# ---------------------------------------------------------------------------

class TestRewindSessionConversation:
    """Mark target turn and subsequent lines as sidechain."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_rewind_to_turn_2(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "Turn 1"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Reply 1"}]}},
            {"type": "user", "message": {"content": "Turn 2"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Reply 2"}]}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation(sid, turn_index=2)

        assert result["status"] == "rewound"
        assert result["user_prompt"] == "Turn 2"
        assert result["turn_index"] == 2

        # Verify the file was rewritten
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)
        # Only turn 1 should remain (turn 2+ marked as sidechain)
        assert len(messages) == 2  # user1 + assistant1

    def test_rewind_turn_not_found(self, tmp_path: Path):
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "Only turn"}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation(sid, turn_index=5)

        assert result["status"] == "error"
        assert "not found" in result["message"]

    def test_rewind_nonexistent_session(self, tmp_path: Path):
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation("nonexistent-id", turn_index=1)

        assert result["status"] == "error"
        assert "not found" in result["message"]


# ---------------------------------------------------------------------------
# Checkpoint / Rewind integration — tool_result filtering, multi-turn, abort
# ---------------------------------------------------------------------------

class TestScanCheckpointsMultiTurn:
    """Verify checkpoint scan correctly excludes tool_result messages."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_multi_turn_with_tool_results(self, tmp_path: Path):
        """3 user prompts + interleaved tool_results → only 3 checkpoints."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            # Turn 1: real user prompt
            {"type": "user", "uuid": "u1", "message": {"role": "user", "content": "Hello"}, "timestamp": "t1"},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Hi"}]}},
            # tool_result — should NOT count as a turn
            {"type": "user", "uuid": "u-tr-1", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "ok"}
            ]}, "timestamp": "t2"},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done"}]}},
            # Turn 2: real user prompt
            {"type": "user", "uuid": "u2", "message": {"role": "user", "content": "Generate file"}, "timestamp": "t3"},
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "t2", "name": "Write", "input": {}}]}},
            # tool_result — NOT a turn
            {"type": "user", "uuid": "u-tr-2", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t2", "content": "file written"}
            ]}, "timestamp": "t4"},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "File created"}]}},
            # Turn 3: real user prompt
            {"type": "user", "uuid": "u3", "message": {"role": "user", "content": "Delete it"}, "timestamp": "t5"},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Deleted"}]}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        # Only 3 real user prompts, not 5 (which includes tool_results)
        assert len(checkpoints) == 3
        assert checkpoints[0] == {"id": "u1", "turnIndex": 1, "timestamp": "t1"}
        assert checkpoints[1] == {"id": "u2", "turnIndex": 2, "timestamp": "t3"}
        assert checkpoints[2] == {"id": "u3", "turnIndex": 3, "timestamp": "t5"}

    def test_tool_result_only_session(self, tmp_path: Path):
        """Session with only tool_result messages → 0 checkpoints."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "uuid": "u-tr", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "ok"}
            ]}, "timestamp": "t1"},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        assert checkpoints == []


class TestRewindSkipsToolResults:
    """Rewind turn counting must also skip tool_result messages."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_rewind_turn2_with_interleaved_tool_results(self, tmp_path: Path):
        """Rewind to turn 2 should skip tool_result lines in turn counting."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            # Turn 1
            {"type": "user", "message": {"content": "Hello"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Hi"}]}},
            # tool_result (not a turn)
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "ok"}
            ]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done"}]}},
            # Turn 2
            {"type": "user", "message": {"content": "Generate file"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Generated"}]}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation(sid, turn_index=2)

        assert result["status"] == "rewound"
        assert result["user_prompt"] == "Generate file"
        assert result["turn_index"] == 2

        # After rewind to turn 2: turn 1 user + assistant + tool_result's
        # assistant remain (all before the cutoff line at turn 2).
        # read_session_messages skips tool_result "user" messages but keeps
        # the assistant reply that follows a tool_result.
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)
        # user1="Hello", assistant1="Hi", assistant-after-tool="Done"
        assert len(messages) == 3
        assert messages[0]["content"] == "Hello"
        assert messages[1]["content"] == "Hi"
        assert messages[2]["content"] == "Done"

    def test_rewind_to_turn1(self, tmp_path: Path):
        """Rewind to turn 1 marks everything from turn 1 onwards as sidechain."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "First prompt"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "First reply"}]}},
            {"type": "user", "message": {"content": "Second prompt"}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation(sid, turn_index=1)

        assert result["status"] == "rewound"
        assert result["user_prompt"] == "First prompt"

        # Everything should be sidechain — no visible messages
        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            messages = read_session_messages(sid)
        assert len(messages) == 0


class TestScanAbortedSession:
    """Aborted sessions (incomplete) should still have checkpoints scanned."""

    def _create_session(self, tmp_path: Path, session_id: str, lines: list[dict]) -> Path:
        encoded = encode_path_to_project_dir("D:\\projects")
        project_dir = tmp_path / encoded
        project_dir.mkdir(parents=True, exist_ok=True)
        f = project_dir / f"{session_id}.jsonl"
        f.write_text(
            "\n".join(json.dumps(l) for l in lines) + "\n",
            encoding="utf-8",
        )
        return f

    def test_aborted_session_has_checkpoints(self, tmp_path: Path):
        """An aborted session (no result event) still has scannable checkpoints."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "uuid": "u1", "message": {"role": "user", "content": "Start long task"}, "timestamp": "t1"},
            {"type": "assistant", "message": {"content": [
                {"type": "text", "text": "Working on it..."},
                {"type": "tool_use", "id": "t1", "name": "Write", "input": {}},
            ]}},
            {"type": "user", "uuid": "u-tr-1", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "written"}
            ]}, "timestamp": "t2"},
            # Task was aborted here — no result/complete message
            {"type": "assistant", "message": {"content": [
                {"type": "text", "text": "Continuing..."},
            ]}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            checkpoints = scan_session_checkpoints(sid)

        # Only 1 real user prompt (the tool_result is excluded)
        assert len(checkpoints) == 1
        assert checkpoints[0]["id"] == "u1"
        assert checkpoints[0]["turnIndex"] == 1

    def test_aborted_session_rewind(self, tmp_path: Path):
        """Can rewind an aborted session to its only turn."""
        sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        lines = [
            {"type": "user", "message": {"content": "Start task"}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Working"}]}},
        ]
        self._create_session(tmp_path, sid, lines)

        with patch(
            "agent_worker.claude.session_scanner._claude_projects_dir",
            return_value=tmp_path,
        ):
            result = rewind_session_conversation(sid, turn_index=1)

        assert result["status"] == "rewound"
        assert result["user_prompt"] == "Start task"
