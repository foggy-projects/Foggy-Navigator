"""Tests for claude/process_detection.py — platform process detection & PID registry.

Tests focus on:
  - PID tracking registry (register, unregister, prune dead)
  - Platform factory (get_detector)
  - WindowsDetector/UnixDetector output parsing (via subprocess mock)
  - ProcessInfo dataclass
"""

from __future__ import annotations

import os
from unittest.mock import MagicMock, patch

import pytest

from agent_worker.claude.process_detection import (
    ProcessInfo,
    UnixDetector,
    WindowsDetector,
    _tracked_pids,
    get_detector,
    get_tracked_pids,
    register_pid,
    unregister_pid,
    unregister_pids_for_task,
)


# ---------------------------------------------------------------------------
# ProcessInfo dataclass
# ---------------------------------------------------------------------------

class TestProcessInfo:
    """ProcessInfo holds OS-level process information."""

    def test_defaults(self):
        p = ProcessInfo(pid=1234)
        assert p.pid == 1234
        assert p.command == ""
        assert p.memory_mb == 0.0
        assert p.started_at == ""

    def test_full_construction(self):
        p = ProcessInfo(
            pid=5678,
            command="node claude cli.js --output-format stream-json",
            memory_mb=150.3,
            started_at="2026-01-15T10:00:00",
        )
        assert p.pid == 5678
        assert p.memory_mb == 150.3


# ---------------------------------------------------------------------------
# PID tracking registry
# ---------------------------------------------------------------------------

class TestPidRegistry:
    """Module-level PID tracking for active tasks."""

    def setup_method(self):
        _tracked_pids.clear()

    def teardown_method(self):
        _tracked_pids.clear()

    def test_register_and_retrieve(self):
        register_pid(1000, "task-a")
        register_pid(2000, "task-b")

        assert 1000 in _tracked_pids
        assert _tracked_pids[1000] == "task-a"
        assert 2000 in _tracked_pids

    def test_unregister_pid(self):
        register_pid(1000, "task-a")
        unregister_pid(1000)
        assert 1000 not in _tracked_pids

    def test_unregister_nonexistent_noop(self):
        unregister_pid(99999)  # should not raise

    def test_unregister_pids_for_task(self):
        register_pid(1000, "task-a")
        register_pid(1001, "task-a")
        register_pid(2000, "task-b")

        unregister_pids_for_task("task-a")
        assert 1000 not in _tracked_pids
        assert 1001 not in _tracked_pids
        assert 2000 in _tracked_pids  # different task

    def test_unregister_pids_for_unknown_task_noop(self):
        register_pid(1000, "task-a")
        unregister_pids_for_task("task-nonexistent")
        assert 1000 in _tracked_pids

    def test_get_tracked_pids_prunes_dead(self):
        register_pid(1000, "task-a")
        register_pid(2000, "task-b")

        # Mock os.kill: 1000 is alive, 2000 is dead
        def mock_kill(pid, sig):
            if pid == 2000:
                raise OSError("No such process")

        with patch("agent_worker.claude.process_detection.os.kill", side_effect=mock_kill):
            alive = get_tracked_pids()

        assert alive == {1000}
        assert 2000 not in _tracked_pids  # pruned


# ---------------------------------------------------------------------------
# Platform factory
# ---------------------------------------------------------------------------

class TestGetDetector:
    """get_detector() returns the correct platform detector."""

    def test_windows_detector(self):
        with patch("agent_worker.claude.process_detection._detector", None):
            with patch("agent_worker.claude.process_detection.os.name", "nt"):
                with patch("agent_worker.claude.process_detection.sys.platform", "win32"):
                    d = get_detector()
                    assert isinstance(d, WindowsDetector)

    def test_unix_detector(self):
        with patch("agent_worker.claude.process_detection._detector", None):
            with patch("agent_worker.claude.process_detection.os.name", "posix"):
                with patch("agent_worker.claude.process_detection.sys.platform", "linux"):
                    d = get_detector()
                    assert isinstance(d, UnixDetector)


# ---------------------------------------------------------------------------
# WindowsDetector
# ---------------------------------------------------------------------------

class TestWindowsDetector:
    """WindowsDetector uses PowerShell to find Claude CLI processes."""

    def test_find_pids_parses_output(self):
        detector = WindowsDetector()

        # Mock PowerShell returning PIDs
        with patch("subprocess.check_output", return_value="1234\n5678\n") as mock:
            pids = detector.find_pids()

        assert 1234 in pids
        assert 5678 in pids

    def test_find_pids_empty_output(self):
        detector = WindowsDetector()

        with patch("subprocess.check_output", return_value=""):
            pids = detector.find_pids()

        assert pids == set()

    def test_find_pids_subprocess_error(self):
        import subprocess

        detector = WindowsDetector()

        with patch("subprocess.check_output", side_effect=subprocess.SubprocessError("err")):
            pids = detector.find_pids()

        assert pids == set()

    def test_get_details_parses_tab_separated(self):
        detector = WindowsDetector()

        output = "1234\tnode.exe\t157286400\t01/15/2026 10:00:00\tnode.exe cli.js --stream-json\n"
        with patch("subprocess.check_output", return_value=output):
            details = detector.get_details({1234})

        assert len(details) == 1
        assert details[0].pid == 1234
        assert details[0].memory_mb == pytest.approx(150.0, abs=0.5)
        assert "cli.js" in details[0].command

    def test_get_details_empty_pids(self):
        detector = WindowsDetector()
        assert detector.get_details(set()) == []

    def test_get_details_fallback_on_error(self):
        import subprocess

        detector = WindowsDetector()

        with patch("subprocess.check_output", side_effect=subprocess.SubprocessError("err")):
            details = detector.get_details({1234, 5678})

        # Should return basic ProcessInfo for each PID
        assert len(details) == 2
        pids = {d.pid for d in details}
        assert pids == {1234, 5678}


# ---------------------------------------------------------------------------
# UnixDetector
# ---------------------------------------------------------------------------

class TestUnixDetector:
    """UnixDetector uses pgrep + ps for detection."""

    def test_find_pids_parses_pgrep_output(self):
        detector = UnixDetector()

        with patch("subprocess.check_output", return_value="1234\n5678\n"):
            pids = detector.find_pids()

        assert 1234 in pids
        assert 5678 in pids

    def test_find_pids_no_match(self):
        import subprocess

        detector = UnixDetector()

        with patch("subprocess.check_output", side_effect=subprocess.SubprocessError()):
            pids = detector.find_pids()

        assert pids == set()

    def test_get_details_parses_ps_output(self):
        detector = UnixDetector()

        output = "1234 51200 Mon Jan 15 10:00:00 2026 /usr/bin/claude --output-format stream-json\n"
        with patch("subprocess.check_output", return_value=output):
            details = detector.get_details({1234})

        assert len(details) == 1
        assert details[0].pid == 1234
        assert details[0].memory_mb == pytest.approx(50.0, abs=0.5)

    def test_get_details_empty_pids(self):
        detector = UnixDetector()
        assert detector.get_details(set()) == []
