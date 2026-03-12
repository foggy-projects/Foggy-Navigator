"""Unit tests for routes/ssh.py — token verification and session helpers."""

from __future__ import annotations

from unittest.mock import patch

from agent_worker.routes.ssh import _verify_ws_token


# ---------------------------------------------------------------------------
# _verify_ws_token
# ---------------------------------------------------------------------------

class TestVerifyWsToken:
    """WebSocket token verification for SSH bridge."""

    def test_dev_mode_no_token_required(self):
        """Empty worker_token means dev mode — all tokens accepted."""
        with patch("agent_worker.routes.ssh.settings") as mock_settings:
            mock_settings.worker_token = ""
            assert _verify_ws_token(None) is True
            assert _verify_ws_token("anything") is True

    def test_valid_token(self):
        with patch("agent_worker.routes.ssh.settings") as mock_settings:
            mock_settings.worker_token = "secret-token"
            assert _verify_ws_token("secret-token") is True

    def test_invalid_token(self):
        with patch("agent_worker.routes.ssh.settings") as mock_settings:
            mock_settings.worker_token = "secret-token"
            assert _verify_ws_token("wrong-token") is False

    def test_none_token_with_auth_enabled(self):
        with patch("agent_worker.routes.ssh.settings") as mock_settings:
            mock_settings.worker_token = "secret-token"
            assert _verify_ws_token(None) is False

    def test_empty_string_token_with_auth_enabled(self):
        with patch("agent_worker.routes.ssh.settings") as mock_settings:
            mock_settings.worker_token = "secret-token"
            assert _verify_ws_token("") is False
