"""Unit tests for marketplace/git_access.py — repo access check and credential helper."""

from __future__ import annotations

import asyncio
import subprocess
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from agent_worker.marketplace.git_access import (
    check_repo_access,
    configure_credential_helper,
)


# ---------------------------------------------------------------------------
# check_repo_access
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestCheckRepoAccess:
    """Test git ls-remote based repository access check."""

    async def test_accessible_repo(self):
        mock_proc = AsyncMock()
        mock_proc.communicate.return_value = (b"abc123\trefs/heads/main\n", b"")
        mock_proc.returncode = 0

        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    return_value=mock_proc):
            ok, err = await check_repo_access("https://github.com/user/repo.git")

        assert ok is True
        assert err == ""

    async def test_auth_failure(self):
        mock_proc = AsyncMock()
        mock_proc.communicate.return_value = (b"", b"Authentication failed for 'https://gitlab.com/repo.git'")
        mock_proc.returncode = 128

        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    return_value=mock_proc):
            ok, err = await check_repo_access("https://gitlab.com/repo.git")

        assert ok is False
        assert "Authentication required" in err

    async def test_repo_not_found(self):
        mock_proc = AsyncMock()
        mock_proc.communicate.return_value = (b"", b"Repository not found")
        mock_proc.returncode = 128

        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    return_value=mock_proc):
            ok, err = await check_repo_access("https://github.com/user/nonexistent.git")

        assert ok is False
        assert "not found" in err.lower()

    async def test_timeout(self):
        mock_proc = AsyncMock()
        mock_proc.communicate.side_effect = asyncio.TimeoutError()
        mock_proc.kill = MagicMock()

        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    return_value=mock_proc):
            ok, err = await check_repo_access("https://slow-server.com/repo.git", timeout=5)

        assert ok is False
        assert "timed out" in err.lower()

    async def test_git_not_found(self):
        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    side_effect=FileNotFoundError()):
            ok, err = await check_repo_access("https://example.com/repo.git")

        assert ok is False
        assert "not found" in err.lower()

    async def test_generic_error(self):
        with patch("agent_worker.marketplace.git_access.asyncio.create_subprocess_exec",
                    side_effect=RuntimeError("unexpected")):
            ok, err = await check_repo_access("https://example.com/repo.git")

        assert ok is False
        assert "unexpected" in err


# ---------------------------------------------------------------------------
# configure_credential_helper
# ---------------------------------------------------------------------------

class TestConfigureCredentialHelper:
    """Store credentials via git credential approve."""

    def test_successful_credential_store(self):
        mock_result = MagicMock()
        mock_result.returncode = 0

        with patch("agent_worker.marketplace.git_access.subprocess.run",
                    return_value=mock_result) as mock_run:
            result = configure_credential_helper(
                "https://gitlab.example.com/repo.git", "user", "pass123"
            )

        assert result is True
        # Verify the subprocess was called with correct args
        call_args = mock_run.call_args
        assert call_args[0][0] == ["git", "credential", "approve"]
        # Verify credential input contains correct fields
        input_data = call_args[1]["input"].decode("utf-8")
        assert "protocol=https" in input_data
        assert "host=gitlab.example.com" in input_data
        assert "username=user" in input_data
        assert "password=pass123" in input_data

    def test_failed_credential_store(self):
        mock_result = MagicMock()
        mock_result.returncode = 1
        mock_result.stderr = b"error message"

        with patch("agent_worker.marketplace.git_access.subprocess.run",
                    return_value=mock_result):
            result = configure_credential_helper("https://gitlab.com/repo.git", "u", "p")

        assert result is False

    def test_git_not_found(self):
        with patch("agent_worker.marketplace.git_access.subprocess.run",
                    side_effect=FileNotFoundError()):
            result = configure_credential_helper("https://gitlab.com/repo.git", "u", "p")

        assert result is False

    def test_timeout(self):
        with patch("agent_worker.marketplace.git_access.subprocess.run",
                    side_effect=subprocess.TimeoutExpired(cmd="git", timeout=10)):
            result = configure_credential_helper("https://gitlab.com/repo.git", "u", "p")

        assert result is False

    def test_http_protocol_detected(self):
        mock_result = MagicMock()
        mock_result.returncode = 0

        with patch("agent_worker.marketplace.git_access.subprocess.run",
                    return_value=mock_result) as mock_run:
            configure_credential_helper("http://internal.server/repo.git", "u", "p")

        input_data = mock_run.call_args[1]["input"].decode("utf-8")
        assert "protocol=http" in input_data
