"""Unit tests for marketplace/setup.py — interactive checks and credential prompting."""

from __future__ import annotations

import sys
from unittest.mock import MagicMock, patch

import pytest

from agent_worker.marketplace.setup import is_interactive, prompt_for_credentials


# ---------------------------------------------------------------------------
# is_interactive
# ---------------------------------------------------------------------------

class TestIsInteractive:
    """Detect whether the process runs in an interactive terminal."""

    def test_non_tty_stdin(self):
        with patch.object(sys, "stdin") as mock_stdin:
            mock_stdin.isatty.return_value = False
            assert is_interactive() is False

    def test_all_tty_non_windows(self):
        with (
            patch.object(sys, "stdin") as mock_stdin,
            patch.object(sys, "stdout") as mock_stdout,
            patch.object(sys, "stderr") as mock_stderr,
            patch.object(sys, "platform", "linux"),
        ):
            mock_stdin.isatty.return_value = True
            mock_stdout.isatty.return_value = True
            mock_stderr.isatty.return_value = True
            assert is_interactive() is True

    def test_stdout_redirected(self):
        with (
            patch.object(sys, "stdin") as mock_stdin,
            patch.object(sys, "stdout") as mock_stdout,
            patch.object(sys, "stderr") as mock_stderr,
            patch.object(sys, "platform", "linux"),
        ):
            mock_stdin.isatty.return_value = True
            mock_stdout.isatty.return_value = False
            mock_stderr.isatty.return_value = True
            assert is_interactive() is False

    def test_stderr_redirected(self):
        with (
            patch.object(sys, "stdin") as mock_stdin,
            patch.object(sys, "stdout") as mock_stdout,
            patch.object(sys, "stderr") as mock_stderr,
            patch.object(sys, "platform", "linux"),
        ):
            mock_stdin.isatty.return_value = True
            mock_stdout.isatty.return_value = True
            mock_stderr.isatty.return_value = False
            assert is_interactive() is False


# ---------------------------------------------------------------------------
# prompt_for_credentials
# ---------------------------------------------------------------------------

class TestPromptForCredentials:
    """Interactive credential prompting."""

    def test_returns_none_when_not_interactive(self):
        with patch("agent_worker.marketplace.setup.is_interactive", return_value=False):
            result = prompt_for_credentials()
        assert result is None

    def test_returns_credentials(self):
        with (
            patch("agent_worker.marketplace.setup.is_interactive", return_value=True),
            patch("builtins.input", return_value="my-user"),
            patch("agent_worker.marketplace.setup.getpass.getpass", return_value="my-token"),
            patch("builtins.print"),
        ):
            result = prompt_for_credentials()
        assert result == ("my-user", "my-token")

    def test_empty_username_cancels(self):
        with (
            patch("agent_worker.marketplace.setup.is_interactive", return_value=True),
            patch("builtins.input", return_value=""),
            patch("builtins.print"),
        ):
            result = prompt_for_credentials()
        assert result is None

    def test_empty_password_cancels(self):
        with (
            patch("agent_worker.marketplace.setup.is_interactive", return_value=True),
            patch("builtins.input", return_value="user"),
            patch("agent_worker.marketplace.setup.getpass.getpass", return_value=""),
            patch("builtins.print"),
        ):
            result = prompt_for_credentials()
        assert result is None

    def test_keyboard_interrupt_cancels(self):
        with (
            patch("agent_worker.marketplace.setup.is_interactive", return_value=True),
            patch("builtins.input", side_effect=KeyboardInterrupt()),
            patch("builtins.print"),
        ):
            result = prompt_for_credentials()
        assert result is None

    def test_eof_error_cancels(self):
        with (
            patch("agent_worker.marketplace.setup.is_interactive", return_value=True),
            patch("builtins.input", side_effect=EOFError()),
            patch("builtins.print"),
        ):
            result = prompt_for_credentials()
        assert result is None
