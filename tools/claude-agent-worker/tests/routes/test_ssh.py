"""Unit tests for routes/ssh.py — token verification and session helpers."""

from __future__ import annotations

import base64
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pytest

from agent_worker.models import SshImageUploadRequest
from agent_worker.routes import ssh as ssh_route
from agent_worker.routes.ssh import (
    _decode_ssh_image_upload,
    _remote_join,
    _verify_ws_token,
)


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


# ---------------------------------------------------------------------------
# SSH image upload helpers
# ---------------------------------------------------------------------------


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def test_decode_ssh_image_upload_accepts_png():
    req = SshImageUploadRequest(
        name="screen.png",
        data=_b64(b"\x89PNG\r\n\x1a\nimage-bytes"),
        mime_type="image/png",
    )

    data, mime_type, ext = _decode_ssh_image_upload(req)

    assert data.startswith(b"\x89PNG")
    assert mime_type == "image/png"
    assert ext == "png"


def test_decode_ssh_image_upload_rejects_signature_mismatch():
    req = SshImageUploadRequest(
        name="screen.png",
        data=_b64(b"\xff\xd8\xffimage-bytes"),
        mime_type="image/png",
    )

    with pytest.raises(ValueError, match="does not match"):
        _decode_ssh_image_upload(req)


def test_decode_ssh_image_upload_rejects_invalid_base64():
    req = SshImageUploadRequest(
        name="screen.webp",
        data="not base64",
        mime_type="image/webp",
    )

    with pytest.raises(ValueError, match="Invalid base64"):
        _decode_ssh_image_upload(req)


def test_remote_join_handles_unix_and_windows_paths():
    assert _remote_join("/home/user/project", ".foggy", "x.png") == "/home/user/project/.foggy/x.png"
    assert _remote_join("C:\\work\\project", ".foggy", "x.png") == "C:\\work\\project\\.foggy\\x.png"


@pytest.mark.asyncio
async def test_upload_image_success(monkeypatch):
    session = SimpleNamespace(
        image_upload_count=0,
        session_id="ssh-1",
        touch=Mock(),
    )

    async def fake_write(session_arg, data, ext):
        assert session_arg is session
        assert data.startswith(b"\x89PNG")
        assert ext == "png"
        return "/home/user/project/.foggy-attachments/codex-tui/ssh-1/image.png"

    monkeypatch.setattr(ssh_route, "_write_image_to_ssh_target", fake_write)
    ssh_route.ssh_sessions["ssh-1"] = session

    try:
        result = await ssh_route.upload_image(
            "ssh-1",
            SshImageUploadRequest(
                name="screen.png",
                data=_b64(b"\x89PNG\r\n\x1a\nimage-bytes"),
                mime_type="image/png",
            ),
        )
    finally:
        ssh_route.ssh_sessions.pop("ssh-1", None)

    assert result.target_image_path.endswith("/image.png")
    assert result.mime_type == "image/png"
    assert result.size > 0
    assert session.image_upload_count == 1
    session.touch.assert_called_once_with()


@pytest.mark.asyncio
async def test_upload_image_missing_session_returns_404():
    result = await ssh_route.upload_image(
        "missing",
        SshImageUploadRequest(
            name="screen.png",
            data=_b64(b"\x89PNG\r\n\x1a\nimage-bytes"),
            mime_type="image/png",
        ),
    )

    assert result.status_code == 404
