"""Tests for routes/query.py — query endpoint logic, stale task purge, cwd validation.

Tests focus on:
  - _validate_cwd: path validation with allowed_cwds
  - _purge_stale_tasks: cleanup of finished asyncio tasks
  - _resolve_task_entry: task resolution by worker ID or foggy_task_id
  - abort_query: task cancellation (including foggy_task_id lookup)
  - respond_to_permission: permission response delivery

Route-level HTTP tests (SSE streaming) are left for L3 integration tests.
"""

from __future__ import annotations

import asyncio
import logging
from unittest.mock import MagicMock, patch

import pytest
from fastapi import HTTPException

from agent_worker.routes.query import (
    _purge_stale_tasks,
    _resolve_task_entry,
    _validate_cwd,
    respond_to_permission,
)
from agent_worker.models import PermissionResponse
from agent_worker.claude.sdk_wrapper import (
    permission_pending,
    task_registry,
)


# ---------------------------------------------------------------------------
# _validate_cwd
# ---------------------------------------------------------------------------

class TestValidateCwd:
    """Path validation for the cwd parameter."""

    def test_none_cwd_uses_getcwd(self):
        with patch("agent_worker.config.settings.allowed_cwds", []):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                with patch("agent_worker.routes.query.os.getcwd", return_value="D:\\default"):
                    result = _validate_cwd(None)
                    assert result == "D:\\default"

    def test_allowed_cwd_passes(self):
        with patch("agent_worker.config.settings.allowed_cwds", ["D:\\projects"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                result = _validate_cwd("D:\\projects\\myapp")
                assert result == "D:\\projects\\myapp"

    def test_disallowed_cwd_raises_403(self):
        with patch("agent_worker.config.settings.allowed_cwds", ["D:\\projects"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException) as exc_info:
                    _validate_cwd("E:\\other")
                assert exc_info.value.status_code == 403

    def test_empty_allowed_permits_all(self):
        with patch("agent_worker.config.settings.allowed_cwds", []):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                result = _validate_cwd("Z:\\anything\\at\\all")
                assert result == "Z:\\anything\\at\\all"

    def test_exact_match(self):
        with patch("agent_worker.config.settings.allowed_cwds", ["D:\\projects"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                result = _validate_cwd("D:\\projects")
                assert result == "D:\\projects"

    def test_sibling_rejected(self):
        with patch("agent_worker.config.settings.allowed_cwds", ["D:\\projects"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException):
                    _validate_cwd("D:\\projects-backup")


# ---------------------------------------------------------------------------
# _purge_stale_tasks
# ---------------------------------------------------------------------------

class TestPurgeStale:
    """Only terminal-observed tasks are released by stale-entry maintenance."""

    def setup_method(self):
        task_registry.clear()
        permission_pending.clear()

    def teardown_method(self):
        task_registry.clear()
        permission_pending.clear()

    def test_purge_done_task(self):
        mock_task = MagicMock()
        mock_task.done.return_value = True
        task_registry["stale-1"] = {
            "asyncio_task": mock_task,
            "foggy_task_id": "foggy-1",
            "terminal_observed": True,
            "terminal_status": "COMPLETED",
            "terminal_source": "PROVIDER_TERMINAL_EVENT",
        }

        _purge_stale_tasks()

        assert "stale-1" not in task_registry

    def test_purge_cleans_pending_permissions(self):
        mock_task = MagicMock()
        mock_task.done.return_value = True
        task_registry["stale-1"] = {
            "asyncio_task": mock_task,
            "foggy_task_id": "foggy-1",
            "terminal_observed": True,
            "terminal_status": "ABORTED",
            "terminal_source": "VERIFIED_MANAGED_PROCESS_EXIT",
        }
        permission_pending["perm-1"] = {"task_id": "stale-1"}
        permission_pending["perm-2"] = {"task_id": "other-task"}

        _purge_stale_tasks()

        assert "perm-1" not in permission_pending
        assert "perm-2" in permission_pending  # different task, preserved

    def test_running_task_not_purged(self):
        mock_task = MagicMock()
        mock_task.done.return_value = False
        task_registry["active-1"] = {
            "asyncio_task": mock_task,
            "foggy_task_id": "foggy-1",
        }

        _purge_stale_tasks()

        assert "active-1" in task_registry

    def test_done_task_without_terminal_evidence_is_retained(self):
        mock_task = MagicMock()
        mock_task.done.return_value = True
        task_registry["unverified-1"] = {
            "asyncio_task": mock_task,
            "foggy_task_id": "foggy-unverified",
        }

        _purge_stale_tasks()

        assert "unverified-1" in task_registry
        assert task_registry["unverified-1"]["attention_state"] == "PROCESS_UNVERIFIED"

    def test_closed_stream_with_active_producer_is_not_purged(self):
        outer_task = MagicMock()
        outer_task.done.return_value = True
        producer_task = MagicMock()
        producer_task.done.return_value = False
        broadcast = MagicMock()
        broadcast.closed = True
        task_registry["still-running"] = {
            "asyncio_task": outer_task,
            "producer_task": producer_task,
            "broadcast": broadcast,
            "foggy_task_id": "foggy-still-running",
        }

        _purge_stale_tasks()

        assert "still-running" in task_registry

    def test_producer_and_stream_completion_without_terminal_event_is_retained(self):
        producer_task = MagicMock()
        producer_task.done.return_value = True
        broadcast = MagicMock()
        broadcast.closed = True
        task_registry["completed-1"] = {
            "producer_task": producer_task,
            "broadcast": broadcast,
            "foggy_task_id": "foggy-completed",
        }

        with patch("agent_worker.claude.process_detection.get_pids_for_task", return_value=[]):
            _purge_stale_tasks()

        assert "completed-1" in task_registry
        assert task_registry["completed-1"]["attention_state"] == "PROCESS_UNVERIFIED"

    def test_empty_registry_noop(self):
        _purge_stale_tasks()  # should not raise


# ---------------------------------------------------------------------------
# _resolve_task_entry
# ---------------------------------------------------------------------------

class TestResolveTaskEntry:
    """_resolve_task_entry looks up by worker_task_id, then by foggy_task_id."""

    def setup_method(self):
        task_registry.clear()

    def teardown_method(self):
        task_registry.clear()

    def test_resolve_by_worker_task_id(self):
        task_registry["worker-uuid-1"] = {"foggy_task_id": "foggy-1"}

        result = _resolve_task_entry("worker-uuid-1")
        assert result is not None
        tid, entry = result
        assert tid == "worker-uuid-1"
        assert entry["foggy_task_id"] == "foggy-1"

    def test_resolve_by_foggy_task_id(self):
        task_registry["worker-uuid-1"] = {"foggy_task_id": "20260306-abc"}

        result = _resolve_task_entry("20260306-abc")
        assert result is not None
        tid, entry = result
        assert tid == "worker-uuid-1"

    def test_resolve_not_found(self):
        result = _resolve_task_entry("nonexistent")
        assert result is None

    def test_worker_id_takes_precedence(self):
        """If worker_task_id matches directly, don't fall through to foggy search."""
        task_registry["direct-match"] = {"foggy_task_id": "foggy-1"}
        task_registry["other-task"] = {"foggy_task_id": "direct-match"}

        result = _resolve_task_entry("direct-match")
        assert result is not None
        tid, _ = result
        assert tid == "direct-match"


# ---------------------------------------------------------------------------
# respond_to_permission
# ---------------------------------------------------------------------------

class TestRespondToPermission:
    """Permission response diagnostics retain metadata, never answer content."""

    def setup_method(self):
        permission_pending.clear()

    def teardown_method(self):
        permission_pending.clear()

    @pytest.mark.asyncio
    async def test_response_log_redacts_answer_values(self, caplog):
        secret_answer = "customer-answer-secret-9b8b"
        event = asyncio.Event()
        permission_pending["perm-log-redaction"] = {
            "task_id": "task-log-redaction",
            "event": event,
        }

        body = PermissionResponse(
            permission_id="perm-log-redaction",
            decision="allow",
            answers={"question-secret-9b8b": secret_answer},
        )
        with caplog.at_level(logging.INFO, logger="agent_worker.routes.query"):
            response = await respond_to_permission("task-log-redaction", body)

        assert response["status"] == "responded"
        assert event.is_set()
        assert secret_answer not in caplog.text
        assert "question-secret-9b8b" not in caplog.text
        assert "answer_count=1" in caplog.text
