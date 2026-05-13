"""Tests for models.py — Pydantic model validation, defaults, and serialization.

Ensures request/response models enforce constraints and produce correct JSON.
"""

from __future__ import annotations

from datetime import datetime

import pytest
from pydantic import ValidationError

from agent_worker.models import (
    AbortResponse,
    CliProcessInfo,
    ContentMatch,
    CreateWorktreeRequest,
    FileEntry,
    GitInfoResponse,
    GitLogEntry,
    HealthResponse,
    ImageAttachment,
    KillProcessRequest,
    PermissionResponse,
    QueryEvent,
    QueryRequest,
    RewindRequest,
    SessionInfo,
    SkillInfo,
    SshConnectRequest,
    SshResizeRequest,
    WorktreeInfo,
)


# ---------------------------------------------------------------------------
# QueryRequest
# ---------------------------------------------------------------------------

class TestQueryRequest:
    """POST /api/v1/query request body validation."""

    def test_minimal_valid(self):
        req = QueryRequest(prompt="Hello")
        assert req.prompt == "Hello"
        assert req.cwd is None
        assert req.session_id is None
        assert req.max_turns is None
        assert req.model is None
        assert req.images is None
        assert req.api_key is None
        assert req.permission_mode is None

    def test_full_request(self):
        req = QueryRequest(
            prompt="Fix the bug",
            cwd="D:\\projects",
            session_id="abc-123",
            max_turns=10,
            model="claude-sonnet-4-20250514",
            api_key="sk-test",
            auth_token="token-test",
            base_url="https://custom.api",
            permission_mode="bypassPermissions",
            navigator_api_key="nav-key",
            disallowed_tools=["Bash", "Task"],
            foggy_task_id="20260306-001",
            foggy_session_id="sess-001",
            extra_env_vars={"FOO": "bar"},
            attachments=[{"name": "pod-photo.png", "url": "https://tms.example.com/files/pod-photo.png"}],
        )
        assert req.prompt == "Fix the bug"
        assert req.max_turns == 10
        assert req.disallowed_tools == ["Bash", "Task"]
        assert req.extra_env_vars == {"FOO": "bar"}
        assert req.attachments == [{"name": "pod-photo.png", "url": "https://tms.example.com/files/pod-photo.png"}]

    def test_missing_prompt_raises(self):
        with pytest.raises(ValidationError):
            QueryRequest()  # type: ignore[call-arg]

    def test_images_list(self):
        req = QueryRequest(
            prompt="Look at this",
            images=[
                ImageAttachment(name="screen.png", data="base64data", mime_type="image/png"),
            ],
        )
        assert len(req.images) == 1
        assert req.images[0].name == "screen.png"


# ---------------------------------------------------------------------------
# QueryEvent
# ---------------------------------------------------------------------------

class TestQueryEvent:
    """SSE event payload model."""

    def test_assistant_text_event(self):
        evt = QueryEvent(type="assistant_text", task_id="t1", content="Hello")
        assert evt.type == "assistant_text"
        assert evt.content == "Hello"
        assert evt.tool is None

    def test_tool_use_event(self):
        evt = QueryEvent(
            type="tool_use",
            task_id="t1",
            tool="Read",
            input={"file_path": "/foo"},
        )
        assert evt.tool == "Read"
        assert evt.input == {"file_path": "/foo"}

    def test_result_event(self):
        evt = QueryEvent(
            type="result",
            task_id="t1",
            content="Done",
            cost_usd=0.05,
            duration_ms=12000,
            input_tokens=1000,
            output_tokens=500,
            num_turns=3,
            model="claude-sonnet-4-20250514",
        )
        assert evt.cost_usd == 0.05
        assert evt.num_turns == 3

    def test_error_event(self):
        evt = QueryEvent(type="error", task_id="t1", error="Timeout")
        assert evt.error == "Timeout"

    def test_serialization_roundtrip(self):
        evt = QueryEvent(type="assistant_text", task_id="t1", content="Hi")
        json_str = evt.model_dump_json()
        restored = QueryEvent.model_validate_json(json_str)
        assert restored.type == "assistant_text"
        assert restored.content == "Hi"


# ---------------------------------------------------------------------------
# PermissionResponse
# ---------------------------------------------------------------------------

class TestPermissionResponse:
    """Permission response payload validation."""

    def test_minimal(self):
        resp = PermissionResponse(permission_id="perm-1", decision="allow")
        assert resp.scope == "once"  # default
        assert resp.deny_message is None

    def test_deny_with_message(self):
        resp = PermissionResponse(
            permission_id="perm-1",
            decision="deny",
            deny_message="Not safe",
            scope="session",
        )
        assert resp.decision == "deny"
        assert resp.deny_message == "Not safe"

    def test_with_answers(self):
        resp = PermissionResponse(
            permission_id="perm-1",
            decision="allow",
            answers={"Which approach?": "Option A"},
        )
        assert resp.answers == {"Which approach?": "Option A"}

    def test_with_plan_action(self):
        resp = PermissionResponse(
            permission_id="perm-1",
            decision="allow",
            plan_action="acceptEdits",
        )
        assert resp.plan_action == "acceptEdits"


# ---------------------------------------------------------------------------
# HealthResponse
# ---------------------------------------------------------------------------

class TestHealthResponse:
    """Health endpoint response."""

    def test_all_fields(self):
        h = HealthResponse(
            hostname="worker-1",
            version="0.1.0",
            active_tasks=2,
            claude_cli_available=True,
            worker_name="my-worker",
        )
        assert h.hostname == "worker-1"
        assert h.active_tasks == 2
        assert h.claude_cli_available is True


# ---------------------------------------------------------------------------
# SessionInfo
# ---------------------------------------------------------------------------

class TestSessionInfo:
    """Session metadata model."""

    def test_basic(self):
        now = datetime.now()
        s = SessionInfo(
            session_id="s1",
            cwd="/projects",
            created_at=now,
            updated_at=now,
        )
        assert s.session_id == "s1"
        assert s.slug is None
        assert s.git_branch is None

    def test_with_optional_fields(self):
        now = datetime.now()
        s = SessionInfo(
            session_id="s1",
            cwd="/projects",
            created_at=now,
            updated_at=now,
            slug="my-project",
            git_branch="feature/x",
        )
        assert s.slug == "my-project"
        assert s.git_branch == "feature/x"


# ---------------------------------------------------------------------------
# AbortResponse
# ---------------------------------------------------------------------------

class TestAbortResponse:
    """Abort endpoint response."""

    def test_basic(self):
        r = AbortResponse(task_id="t1", status="cancelled")
        assert r.task_id == "t1"
        assert r.status == "cancelled"


# ---------------------------------------------------------------------------
# GitInfoResponse
# ---------------------------------------------------------------------------

class TestGitInfoResponse:
    """Git info response with defaults."""

    def test_defaults(self):
        g = GitInfoResponse(path="/project", is_git_repo=True)
        assert g.status == "unknown"
        assert g.provider == "OTHER"
        assert g.branch is None

    def test_full(self):
        g = GitInfoResponse(
            path="/project",
            is_git_repo=True,
            branch="main",
            remote_url="https://github.com/user/repo",
            status="clean",
            provider="GITHUB",
        )
        assert g.provider == "GITHUB"
        assert g.status == "clean"


# ---------------------------------------------------------------------------
# SSH models
# ---------------------------------------------------------------------------

class TestSshModels:
    """SSH request/response models."""

    def test_connect_request_defaults(self):
        req = SshConnectRequest(host="192.168.1.1", username="root")
        assert req.port == 22
        assert req.cols == 80
        assert req.rows == 24
        assert req.password is None
        assert req.private_key is None

    def test_resize_request(self):
        req = SshResizeRequest(cols=120, rows=40)
        assert req.cols == 120
        assert req.rows == 40

    def test_resize_request_validation(self):
        with pytest.raises(ValidationError):
            SshResizeRequest(cols=0, rows=24)  # min is 1

    def test_connect_port_validation(self):
        with pytest.raises(ValidationError):
            SshConnectRequest(host="x", username="u", port=0)  # min is 1
        with pytest.raises(ValidationError):
            SshConnectRequest(host="x", username="u", port=70000)  # max is 65535


# ---------------------------------------------------------------------------
# CliProcessInfo
# ---------------------------------------------------------------------------

class TestCliProcessInfo:
    """CLI process info model."""

    def test_defaults(self):
        p = CliProcessInfo(pid=1234)
        assert p.command == ""
        assert p.memory_mb == 0.0
        assert p.is_orphan is True
        assert p.claude_session_id is None
        assert p.foggy_task_id is None

    def test_full(self):
        p = CliProcessInfo(
            pid=5678,
            command="node cli.js --stream-json",
            memory_mb=150.3,
            started_at="2026-01-15T10:00:00",
            is_orphan=False,
            claude_session_id="cs-123",
            foggy_task_id="ft-456",
            foggy_session_id="fs-789",
        )
        assert p.is_orphan is False
        assert p.foggy_session_id == "fs-789"


# ---------------------------------------------------------------------------
# KillProcessRequest
# ---------------------------------------------------------------------------

class TestKillProcessRequest:
    """Kill process request defaults."""

    def test_default_not_force(self):
        req = KillProcessRequest()
        assert req.force is False

    def test_force_kill(self):
        req = KillProcessRequest(force=True)
        assert req.force is True


# ---------------------------------------------------------------------------
# Worktree models
# ---------------------------------------------------------------------------

class TestWorktreeModels:
    """Git worktree request/response models."""

    def test_create_request(self):
        req = CreateWorktreeRequest(repo_path="/repo", branch="feature/x")
        assert req.worktree_path is None

    def test_worktree_info(self):
        info = WorktreeInfo(path="/repo/.worktrees/x", branch="feature/x", is_main=False)
        assert info.is_main is False


# ---------------------------------------------------------------------------
# File models
# ---------------------------------------------------------------------------

class TestFileModels:
    """File browser models."""

    def test_file_entry_defaults(self):
        f = FileEntry(name="test.py", path="/project/test.py", is_dir=False)
        assert f.size == 0
        assert f.modified == ""

    def test_content_match(self):
        m = ContentMatch(file="main.py", line_number=42, line_content="def foo():")
        assert m.context_before == []
        assert m.context_after == []


# ---------------------------------------------------------------------------
# Skill models
# ---------------------------------------------------------------------------

class TestSkillInfo:
    """Skill info model."""

    def test_defaults(self):
        s = SkillInfo(name="my-skill")
        assert s.description == ""
        assert s.scope == "project"


# ---------------------------------------------------------------------------
# RewindRequest
# ---------------------------------------------------------------------------

class TestRewindRequest:
    """Rewind request validation."""

    def test_valid(self):
        req = RewindRequest(claude_session_id="cs-1", checkpoint_id="cp-1")
        assert req.cwd is None

    def test_missing_required_raises(self):
        with pytest.raises(ValidationError):
            RewindRequest(claude_session_id="cs-1")  # type: ignore[call-arg]


# ---------------------------------------------------------------------------
# ImageAttachment
# ---------------------------------------------------------------------------

class TestImageAttachment:
    """Image attachment model."""

    def test_defaults(self):
        img = ImageAttachment(name="screenshot.png", data="base64data")
        assert img.mime_type == "image/png"

    def test_custom_mime(self):
        img = ImageAttachment(name="photo.webp", data="base64data", mime_type="image/webp")
        assert img.mime_type == "image/webp"
