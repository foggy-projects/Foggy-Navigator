"""Tests for claude/event_mapper.py — pure function SSE dict mapping.

Every mapper function converts structured data into flat SSE-friendly dicts.
These are pure functions with zero external dependencies, ideal for unit tests.
"""

from __future__ import annotations

import pytest

from agent_worker.claude.event_mapper import (
    map_assistant_text,
    map_checkpoint,
    map_error,
    map_permission_request,
    map_plan_review,
    map_result,
    map_sync_checkpoint,
    map_system,
    map_tool_result,
    map_tool_use,
    map_user_question,
)


class TestMapAssistantText:
    """map_assistant_text: TextBlock from AssistantMessage → SSE dict."""

    def test_basic_text(self):
        d = map_assistant_text(task_id="t1", text="Hello world")
        assert d["type"] == "assistant_text"
        assert d["content"] == "Hello world"
        assert d["task_id"] == "t1"
        assert d["session_id"] is None

    def test_with_session_id(self):
        d = map_assistant_text(task_id="t1", text="Hi", session_id="s1")
        assert d["session_id"] == "s1"

    def test_with_model(self):
        d = map_assistant_text(task_id="t1", text="Hi", model="claude-sonnet-4-20250514")
        assert d["model"] == "claude-sonnet-4-20250514"

    def test_without_model_no_key(self):
        d = map_assistant_text(task_id="t1", text="Hi")
        assert "model" not in d

    def test_empty_text(self):
        d = map_assistant_text(task_id="t1", text="")
        assert d["content"] == ""

    def test_multiline_text(self):
        d = map_assistant_text(task_id="t1", text="line1\nline2\nline3")
        assert d["content"] == "line1\nline2\nline3"


class TestMapToolUse:
    """map_tool_use: ToolUseBlock → SSE dict."""

    def test_basic_tool_use(self):
        d = map_tool_use(
            task_id="t1",
            tool_name="Read",
            tool_input={"file_path": "/foo/bar.py"},
        )
        assert d["type"] == "tool_use"
        assert d["tool"] == "Read"
        assert d["input"] == {"file_path": "/foo/bar.py"}
        assert d["task_id"] == "t1"
        assert d["tool_use_id"] is None
        assert d["session_id"] is None

    def test_with_tool_use_id(self):
        d = map_tool_use(
            task_id="t1",
            tool_name="Bash",
            tool_input={"command": "ls"},
            tool_use_id="toolu_123",
        )
        assert d["tool_use_id"] == "toolu_123"

    def test_with_session_id(self):
        d = map_tool_use(
            task_id="t1",
            tool_name="Write",
            tool_input={"file_path": "/tmp/x", "content": "hello"},
            session_id="s1",
        )
        assert d["session_id"] == "s1"

    def test_empty_input(self):
        d = map_tool_use(task_id="t1", tool_name="Glob", tool_input={})
        assert d["input"] == {}


class TestMapToolResult:
    """map_tool_result: ToolResultBlock → SSE dict."""

    def test_success_result(self):
        d = map_tool_result(
            task_id="t1",
            tool_use_id="toolu_123",
            content="file contents here",
        )
        assert d["type"] == "tool_result"
        assert d["tool"] == "unknown"  # default when tool_name is None
        assert d["tool_use_id"] == "toolu_123"
        assert d["output"] == "file contents here"
        assert d["is_error"] is False
        assert d["error"] is None

    def test_error_result(self):
        d = map_tool_result(
            task_id="t1",
            tool_use_id="toolu_456",
            content="File not found",
            is_error=True,
        )
        assert d["is_error"] is True
        assert d["error"] == "File not found"
        assert d["output"] == "File not found"

    def test_with_tool_name(self):
        d = map_tool_result(
            task_id="t1",
            tool_use_id="toolu_789",
            content="OK",
            tool_name="Bash",
        )
        assert d["tool"] == "Bash"

    def test_none_content(self):
        d = map_tool_result(
            task_id="t1",
            tool_use_id="toolu_000",
            content=None,
        )
        assert d["output"] is None
        assert d["error"] is None


class TestMapResult:
    """map_result: ResultMessage → SSE dict."""

    def test_full_result(self):
        d = map_result(
            task_id="t1",
            result_text="Task completed successfully",
            cost_usd=0.05,
            duration_ms=12000,
            session_id="s1",
            input_tokens=1000,
            output_tokens=500,
            num_turns=3,
            model="claude-sonnet-4-20250514",
        )
        assert d["type"] == "result"
        assert d["content"] == "Task completed successfully"
        assert d["cost_usd"] == 0.05
        assert d["duration_ms"] == 12000
        assert d["input_tokens"] == 1000
        assert d["output_tokens"] == 500
        assert d["num_turns"] == 3
        assert d["model"] == "claude-sonnet-4-20250514"
        assert d["task_id"] == "t1"
        assert d["session_id"] == "s1"

    def test_minimal_result(self):
        d = map_result(task_id="t1", result_text=None, cost_usd=None, duration_ms=None)
        assert d["content"] is None
        assert d["cost_usd"] is None
        assert d["duration_ms"] is None
        assert d["input_tokens"] is None
        assert d["output_tokens"] is None
        assert d["num_turns"] is None
        assert d["model"] is None

    def test_zero_cost(self):
        d = map_result(task_id="t1", result_text="OK", cost_usd=0.0, duration_ms=0)
        assert d["cost_usd"] == 0.0
        assert d["duration_ms"] == 0


class TestMapSystem:
    """map_system: SystemMessage → SSE dict."""

    def test_init_event(self):
        d = map_system(
            task_id="t1",
            subtype="init",
            data={"session_id": "abc-123"},
        )
        assert d["type"] == "system"
        assert d["subtype"] == "init"
        assert d["data"] == {"session_id": "abc-123"}

    def test_no_data(self):
        d = map_system(task_id="t1", subtype="heartbeat")
        assert d["data"] is None

    def test_with_session_id(self):
        d = map_system(task_id="t1", subtype="status", session_id="s1")
        assert d["session_id"] == "s1"


class TestMapPermissionRequest:
    """map_permission_request: can_use_tool callback → SSE dict."""

    def test_basic_permission(self):
        d = map_permission_request(
            task_id="t1",
            permission_id="perm-001",
            tool_name="Bash",
            tool_input={"command": "rm -rf /tmp/test"},
        )
        assert d["type"] == "permission_request"
        assert d["permission_id"] == "perm-001"
        assert d["tool"] == "Bash"
        assert d["input"] == {"command": "rm -rf /tmp/test"}
        assert d["has_suggestions"] is False

    def test_with_suggestions(self):
        d = map_permission_request(
            task_id="t1",
            permission_id="perm-002",
            tool_name="Write",
            has_suggestions=True,
        )
        assert d["has_suggestions"] is True

    def test_none_input(self):
        d = map_permission_request(
            task_id="t1",
            permission_id="perm-003",
            tool_name="Read",
        )
        assert d["input"] is None


class TestMapPlanReview:
    """map_plan_review: ExitPlanMode tool call → SSE dict."""

    def test_full_plan_review(self):
        prompts = [{"tool": "Bash", "prompt": "run tests"}]
        d = map_plan_review(
            task_id="t1",
            permission_id="perm-010",
            allowed_prompts=prompts,
            plan="Step 1: Foo\nStep 2: Bar",
            session_id="s1",
        )
        assert d["type"] == "plan_review"
        assert d["permission_id"] == "perm-010"
        assert d["allowed_prompts"] == prompts
        assert d["plan"] == "Step 1: Foo\nStep 2: Bar"

    def test_defaults(self):
        d = map_plan_review(task_id="t1", permission_id="perm-011")
        assert d["allowed_prompts"] == []
        assert d["plan"] == ""


class TestMapUserQuestion:
    """map_user_question: AskUserQuestion tool call → SSE dict."""

    def test_with_questions(self):
        questions = [
            {"question": "Which approach?", "options": [{"label": "A"}, {"label": "B"}]}
        ]
        d = map_user_question(
            task_id="t1",
            permission_id="perm-020",
            questions=questions,
            session_id="s1",
        )
        assert d["type"] == "user_question"
        assert d["permission_id"] == "perm-020"
        assert d["questions"] == questions
        assert d["session_id"] == "s1"

    def test_empty_questions(self):
        d = map_user_question(task_id="t1", permission_id="perm-021", questions=[])
        assert d["questions"] == []


class TestMapCheckpoint:
    """map_checkpoint: UserMessage UUID → SSE dict."""

    def test_basic_checkpoint(self):
        d = map_checkpoint(
            task_id="t1",
            checkpoint_id="uuid-abc-123",
            session_id="s1",
        )
        assert d["type"] == "checkpoint"
        assert d["checkpoint_id"] == "uuid-abc-123"
        assert d["task_id"] == "t1"
        assert d["session_id"] == "s1"

    def test_no_session(self):
        d = map_checkpoint(task_id="t1", checkpoint_id="uuid-xyz")
        assert d["session_id"] is None


class TestMapError:
    """map_error: error condition → SSE dict."""

    def test_basic_error(self):
        d = map_error(task_id="t1", error="Something went wrong")
        assert d["type"] == "error"
        assert d["error"] == "Something went wrong"
        assert d["task_id"] == "t1"

    def test_with_session(self):
        d = map_error(task_id="t1", error="Timeout", session_id="s1")
        assert d["session_id"] == "s1"

    def test_empty_error(self):
        d = map_error(task_id="t1", error="")
        assert d["error"] == ""


class TestMapSyncCheckpoint:
    """map_sync_checkpoint: final stream integrity marker → SSE dict."""

    def test_basic_sync_checkpoint(self):
        d = map_sync_checkpoint(
            task_id="t1",
            latest_seq=42,
            event_count=42,
            session_id="s1",
        )
        assert d["type"] == "sync_checkpoint"
        assert d["latest_seq"] == 42
        assert d["event_count"] == 42
        assert d["task_id"] == "t1"
        assert d["session_id"] == "s1"

    def test_zero_values(self):
        d = map_sync_checkpoint(task_id="t1", latest_seq=0, event_count=0)
        assert d["latest_seq"] == 0
        assert d["event_count"] == 0
