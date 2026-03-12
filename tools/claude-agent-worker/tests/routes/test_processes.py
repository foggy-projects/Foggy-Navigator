"""Unit tests for routes/processes.py — process enrichment and helpers."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from agent_worker.models import CliProcessInfo
from agent_worker.routes.processes import (
    _build_registry_session_lookup,
    _enrich_processes,
    _get_process_details,
    _parse_resume_session_id,
    _read_foggy_env,
)
from agent_worker.claude.process_detection import _tracked_pids
from agent_worker.claude.sdk_wrapper import task_registry


# ---------------------------------------------------------------------------
# _parse_resume_session_id
# ---------------------------------------------------------------------------

class TestParseResumeSessionId:
    """Extract Claude session ID from --resume flag."""

    def test_with_resume_flag(self):
        cmd = "node /usr/bin/claude --print --resume abc-123-def"
        assert _parse_resume_session_id(cmd) == "abc-123-def"

    def test_resume_with_uuid(self):
        cmd = "claude --resume 550e8400-e29b-41d4-a716-446655440000 --print"
        assert _parse_resume_session_id(cmd) == "550e8400-e29b-41d4-a716-446655440000"

    def test_no_resume_flag(self):
        cmd = "node /usr/bin/claude --print --model sonnet"
        assert _parse_resume_session_id(cmd) is None

    def test_empty_command(self):
        assert _parse_resume_session_id("") is None


# ---------------------------------------------------------------------------
# _read_foggy_env
# ---------------------------------------------------------------------------

class TestReadFoggyEnv:
    """Read FOGGY_TASK_ID/SESSION_ID from process environment."""

    def test_reads_env_vars(self):
        mock_proc = MagicMock()
        mock_proc.environ.return_value = {
            "FOGGY_TASK_ID": "task-001",
            "FOGGY_SESSION_ID": "sess-001",
            "PATH": "/usr/bin",
        }
        with patch("agent_worker.routes.processes.psutil.Process", return_value=mock_proc):
            result = _read_foggy_env(1234)
        assert result["foggy_task_id"] == "task-001"
        assert result["foggy_session_id"] == "sess-001"

    def test_missing_env_vars(self):
        mock_proc = MagicMock()
        mock_proc.environ.return_value = {"PATH": "/usr/bin"}
        with patch("agent_worker.routes.processes.psutil.Process", return_value=mock_proc):
            result = _read_foggy_env(1234)
        assert result["foggy_task_id"] is None
        assert result["foggy_session_id"] is None

    def test_process_not_found(self):
        import psutil
        with patch("agent_worker.routes.processes.psutil.Process", side_effect=psutil.NoSuchProcess(999)):
            result = _read_foggy_env(999)
        assert result["foggy_task_id"] is None

    def test_access_denied(self):
        import psutil
        with patch("agent_worker.routes.processes.psutil.Process", side_effect=psutil.AccessDenied(1)):
            result = _read_foggy_env(1)
        assert result["foggy_task_id"] is None


# ---------------------------------------------------------------------------
# _build_registry_session_lookup
# ---------------------------------------------------------------------------

class TestBuildRegistrySessionLookup:
    """Build claude_session_id → foggy IDs mapping from task_registry."""

    def setup_method(self):
        task_registry.clear()

    def teardown_method(self):
        task_registry.clear()

    def test_empty_registry(self):
        assert _build_registry_session_lookup() == {}

    def test_single_entry(self):
        task_registry["worker-1"] = {
            "session_id": "claude-sess-1",
            "foggy_task_id": "foggy-task-1",
            "foggy_session_id": "foggy-sess-1",
        }
        lookup = _build_registry_session_lookup()
        assert "claude-sess-1" in lookup
        assert lookup["claude-sess-1"]["foggy_task_id"] == "foggy-task-1"

    def test_entry_without_session_id_skipped(self):
        task_registry["worker-1"] = {
            "foggy_task_id": "foggy-task-1",
        }
        lookup = _build_registry_session_lookup()
        assert len(lookup) == 0

    def test_multiple_entries(self):
        task_registry["w1"] = {"session_id": "s1", "foggy_task_id": "ft1", "foggy_session_id": None}
        task_registry["w2"] = {"session_id": "s2", "foggy_task_id": "ft2", "foggy_session_id": "fs2"}
        lookup = _build_registry_session_lookup()
        assert len(lookup) == 2
        assert lookup["s2"]["foggy_session_id"] == "fs2"


# ---------------------------------------------------------------------------
# _enrich_processes
# ---------------------------------------------------------------------------

class TestEnrichProcesses:
    """Enrich process list with Foggy platform IDs (3-layer strategy)."""

    def setup_method(self):
        task_registry.clear()
        _tracked_pids.clear()

    def teardown_method(self):
        task_registry.clear()
        _tracked_pids.clear()

    def _make_proc(self, pid: int, command: str = "") -> CliProcessInfo:
        return CliProcessInfo(pid=pid, command=command, memory_mb=0.0, is_orphan=True)

    def test_layer0_tracked_pid(self):
        """Layer 0: tracked PID → task_registry lookup."""
        _tracked_pids[1000] = "worker-1"
        task_registry["worker-1"] = {
            "foggy_task_id": "ft-1",
            "foggy_session_id": "fs-1",
            "session_id": "cs-1",
            "model": "claude-sonnet",
        }
        procs = [self._make_proc(1000)]
        _enrich_processes(procs)
        assert procs[0].foggy_task_id == "ft-1"
        assert procs[0].foggy_session_id == "fs-1"
        assert procs[0].claude_session_id == "cs-1"
        assert procs[0].model == "claude-sonnet"
        assert procs[0].is_orphan is False

    def test_layer1_psutil_env(self):
        """Layer 1: psutil env vars when tracked_pids has no match."""
        procs = [self._make_proc(2000)]
        with patch("agent_worker.routes.processes._read_foggy_env", return_value={
            "foggy_task_id": "ft-env-1",
            "foggy_session_id": "fs-env-1",
        }):
            _enrich_processes(procs)
        assert procs[0].foggy_task_id == "ft-env-1"
        assert procs[0].is_orphan is False

    def test_layer2_command_line_resume(self):
        """Layer 2: --resume session_id → task_registry lookup."""
        task_registry["w1"] = {
            "session_id": "claude-sess-abc",
            "foggy_task_id": "ft-resume-1",
            "foggy_session_id": "fs-resume-1",
        }
        procs = [self._make_proc(3000, command="claude --resume claude-sess-abc --print")]
        with patch("agent_worker.routes.processes._read_foggy_env", return_value={
            "foggy_task_id": None,
            "foggy_session_id": None,
        }):
            _enrich_processes(procs)
        assert procs[0].claude_session_id == "claude-sess-abc"
        assert procs[0].foggy_task_id == "ft-resume-1"
        assert procs[0].is_orphan is False

    def test_orphan_when_no_enrichment(self):
        """Process stays orphan when no layer can resolve it."""
        procs = [self._make_proc(9999, command="claude --print")]
        with patch("agent_worker.routes.processes._read_foggy_env", return_value={
            "foggy_task_id": None,
            "foggy_session_id": None,
        }):
            _enrich_processes(procs)
        assert procs[0].is_orphan is True
        assert procs[0].foggy_task_id is None


# ---------------------------------------------------------------------------
# _get_process_details
# ---------------------------------------------------------------------------

class TestGetProcessDetails:
    """Convert raw ProcessInfo into CliProcessInfo."""

    def setup_method(self):
        task_registry.clear()

    def teardown_method(self):
        task_registry.clear()

    def test_converts_process_info(self):
        mock_info = MagicMock()
        mock_info.pid = 1234
        mock_info.command = "node claude --print"
        mock_info.memory_mb = 256.5
        mock_info.started_at = "2026-01-01T00:00:00"

        mock_detector = MagicMock()
        mock_detector.get_details.return_value = [mock_info]

        with patch("agent_worker.routes.processes.get_detector", return_value=mock_detector):
            result = _get_process_details({1234})

        assert len(result) == 1
        assert result[0].pid == 1234
        assert result[0].memory_mb == 256.5

    def test_orphan_status_when_no_active_tasks(self):
        """is_orphan should be True when task_registry is empty."""
        mock_info = MagicMock()
        mock_info.pid = 5678
        mock_info.command = "claude --print"
        mock_info.memory_mb = 100.0
        mock_info.started_at = "2026-01-01T00:00:00"

        mock_detector = MagicMock()
        mock_detector.get_details.return_value = [mock_info]

        with patch("agent_worker.routes.processes.get_detector", return_value=mock_detector):
            result = _get_process_details({5678})

        assert result[0].is_orphan is True

    def test_not_orphan_when_active_tasks_exist(self):
        """is_orphan should be False when there are active tasks."""
        task_registry["active-task"] = {"foggy_task_id": "ft-1"}

        mock_info = MagicMock()
        mock_info.pid = 7777
        mock_info.command = "claude --print"
        mock_info.memory_mb = 50.0
        mock_info.started_at = "2026-01-01T00:00:00"

        mock_detector = MagicMock()
        mock_detector.get_details.return_value = [mock_info]

        with patch("agent_worker.routes.processes.get_detector", return_value=mock_detector):
            result = _get_process_details({7777})

        assert result[0].is_orphan is False
