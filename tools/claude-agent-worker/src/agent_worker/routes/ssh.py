"""SSH WebSocket bridge endpoints.

Provides HTTP endpoints to create/close SSH sessions and a WebSocket
endpoint that bridges browser ↔ SSH PTY in real time.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import json
import logging
import re
import uuid

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect, status
from fastapi.responses import JSONResponse

from ..auth import verify_token
from ..config import settings
from ..models import (
    SshConnectRequest,
    SshConnectResponse,
    SshImageUploadRequest,
    SshImageUploadResponse,
    SshResizeRequest,
    SshSessionInfo,
)
from ..ssh.session_manager import (
    close_ssh_session,
    create_ssh_session,
    ssh_sessions,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/ssh", tags=["ssh"])

_ALLOWED_IMAGE_TYPES = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/jpg": "jpg",
    "image/webp": "webp",
}


def _infer_mime_type(name: str, mime_type: str | None) -> str:
    normalized = (mime_type or "").strip().lower()
    if normalized and normalized != "application/octet-stream":
        return normalized
    lower_name = name.lower()
    if lower_name.endswith(".png"):
        return "image/png"
    if lower_name.endswith((".jpg", ".jpeg")):
        return "image/jpeg"
    if lower_name.endswith(".webp"):
        return "image/webp"
    return normalized or "application/octet-stream"


def _detect_image_ext(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def _strip_data_url_prefix(data: str) -> str:
    if data.startswith("data:") and "," in data:
        return data.split(",", 1)[1]
    return data


def _decode_ssh_image_upload(req: SshImageUploadRequest) -> tuple[bytes, str, str]:
    mime_type = _infer_mime_type(req.name, req.mime_type)
    expected_ext = _ALLOWED_IMAGE_TYPES.get(mime_type)
    if expected_ext is None:
        raise ValueError("Unsupported image type")

    try:
        data = base64.b64decode(_strip_data_url_prefix(req.data), validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("Invalid base64 image data") from exc

    if not data:
        raise ValueError("Image data is empty")
    if len(data) > settings.ssh_image_max_bytes:
        raise ValueError(f"Image exceeds {settings.ssh_image_max_bytes} bytes")

    actual_ext = _detect_image_ext(data)
    if actual_ext is None:
        raise ValueError("Image signature is not recognized")
    if actual_ext != expected_ext:
        raise ValueError("Image MIME type does not match file signature")

    normalized_mime = "image/jpeg" if actual_ext == "jpg" else f"image/{actual_ext}"
    return data, normalized_mime, actual_ext


def _is_windows_path(path: str) -> bool:
    return bool(re.match(r"^[A-Za-z]:[\\/]", path) or "\\" in path)


def _remote_join(base: str, *parts: str) -> str:
    sep = "\\" if _is_windows_path(base) else "/"
    prefix = base.rstrip("/\\")
    clean_parts = [part.strip("/\\") for part in parts if part]
    if not clean_parts:
        return prefix
    if prefix in ("", sep):
        return sep + sep.join(clean_parts)
    return prefix + sep + sep.join(clean_parts)


async def _write_image_to_ssh_target(session, data: bytes, ext: str) -> str:
    async with session.conn.start_sftp_client() as sftp:
        base_dir = session.cwd.strip() if session.cwd else ""
        if base_dir:
            upload_dir = _remote_join(
                base_dir,
                ".foggy-attachments",
                "codex-tui",
                session.session_id,
            )
        else:
            home_dir = str(await sftp.realpath("."))
            upload_dir = _remote_join(
                home_dir,
                ".foggy-navigator",
                "ssh-attachments",
                session.session_id,
            )
        await sftp.makedirs(upload_dir, exist_ok=True)

        target_path = _remote_join(upload_dir, f"{uuid.uuid4().hex}.{ext}")
        async with sftp.open(target_path, "wb") as remote_file:
            await remote_file.write(data)

        session.image_upload_dirs.add(upload_dir)
        return target_path


# ---------------------------------------------------------------------------
# POST /connect — create SSH session
# ---------------------------------------------------------------------------


@router.post("/connect", response_model=SshConnectResponse, dependencies=[Depends(verify_token)])
async def connect(req: SshConnectRequest):
    if not req.password and not req.private_key:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"detail": "Either password or private_key must be provided"},
        )

    if len(ssh_sessions) >= settings.max_ssh_sessions:
        return JSONResponse(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            content={"detail": f"Max SSH sessions ({settings.max_ssh_sessions}) reached"},
        )

    try:
        session = await create_ssh_session(
            host=req.host,
            port=req.port,
            username=req.username,
            password=req.password,
            private_key=req.private_key,
            cols=req.cols,
            rows=req.rows,
            cwd=req.cwd,
            directory_id=req.directory_id,
        )
    except Exception as exc:
        logger.warning("SSH connect failed: %s", exc)
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"detail": f"SSH connection failed: {exc}"},
        )

    return SshConnectResponse(session_id=session.session_id)


# ---------------------------------------------------------------------------
# WS /{session_id}/ws — terminal bridge
# ---------------------------------------------------------------------------


def _verify_ws_token(token: str | None) -> bool:
    """Check the query-param token for WebSocket auth (WS can't use Authorization header)."""

    if not settings.worker_token:
        return True  # dev mode
    return token == settings.worker_token


@router.websocket("/{session_id}/ws")
async def ws_terminal(websocket: WebSocket, session_id: str, token: str | None = None):
    if not _verify_ws_token(token):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Unauthorized")
        return

    session = ssh_sessions.get(session_id)
    if session is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Session not found")
        return

    await websocket.accept()
    session.touch()

    async def _ssh_to_ws():
        """Forward SSH stdout → WebSocket binary frames."""
        try:
            logger.debug("ssh→ws loop started for session %s", session_id)
            while True:
                data = await session.process.stdout.read(4096)
                if not data:
                    logger.debug("ssh→ws: stdout EOF for session %s", session_id)
                    break
                logger.debug("ssh→ws: %d bytes from stdout", len(data))
                session.touch()
                await websocket.send_bytes(data)
        except Exception as exc:
            logger.warning("ssh→ws ended for session %s: %s", session_id, exc)

    async def _ws_to_ssh():
        """Forward WebSocket frames → SSH stdin, handle resize control messages."""
        try:
            logger.debug("ws→ssh loop started for session %s", session_id)
            while True:
                msg = await websocket.receive()
                if msg.get("type") == "websocket.disconnect":
                    logger.info("ws→ssh: WS disconnected for session %s", session_id)
                    break

                session.touch()

                if "bytes" in msg and msg["bytes"]:
                    logger.debug("ws→ssh: %d bytes from WS", len(msg["bytes"]))
                    session.process.stdin.write(msg["bytes"])
                    await session.process.stdin.drain()
                elif "text" in msg and msg["text"]:
                    text = msg["text"]
                    handled = False
                    try:
                        ctrl = json.loads(text)
                        if isinstance(ctrl, dict) and ctrl.get("type") == "resize":
                            cols = max(1, min(500, int(ctrl["cols"])))
                            rows = max(1, min(500, int(ctrl["rows"])))
                            session.process.change_terminal_size(cols, rows)
                            session.cols = cols
                            session.rows = rows
                            logger.debug("ws→ssh: resize %dx%d", cols, rows)
                            handled = True
                    except (json.JSONDecodeError, KeyError, ValueError):
                        pass
                    if not handled:
                        # Treat as raw terminal input (plain text or non-resize JSON)
                        raw = text.encode("utf-8")
                        logger.debug("ws→ssh: text input %d bytes: %r", len(raw), raw[:50])
                        session.process.stdin.write(raw)
                        await session.process.stdin.drain()
        except WebSocketDisconnect:
            logger.info("ws→ssh: WebSocketDisconnect for session %s", session_id)
        except Exception as exc:
            logger.warning("ws→ssh ended for session %s: %s", session_id, exc)

    ssh_task = asyncio.create_task(_ssh_to_ws())
    ws_task = asyncio.create_task(_ws_to_ssh())

    try:
        done, pending = await asyncio.wait(
            [ssh_task, ws_task], return_when=asyncio.FIRST_COMPLETED
        )
    finally:
        for t in [ssh_task, ws_task]:
            if not t.done():
                t.cancel()
                try:
                    await t
                except (asyncio.CancelledError, Exception):
                    pass

        try:
            await websocket.close()
        except Exception:
            pass
        # SSH session intentionally kept alive after WS disconnect to allow
        # reconnection within the idle timeout window (ssh_idle_timeout_seconds).

    logger.info("WebSocket bridge closed for SSH session %s", session_id)


# ---------------------------------------------------------------------------
# POST /{session_id}/images — upload image to SSH target filesystem
# ---------------------------------------------------------------------------


@router.post(
    "/{session_id}/images",
    response_model=SshImageUploadResponse,
    dependencies=[Depends(verify_token)],
)
async def upload_image(session_id: str, req: SshImageUploadRequest):
    session = ssh_sessions.get(session_id)
    if session is None:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": "Session not found"},
        )

    if session.image_upload_count >= settings.ssh_image_max_files_per_session:
        return JSONResponse(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            content={"detail": "Too many images uploaded for this SSH session"},
        )

    try:
        data, mime_type, ext = _decode_ssh_image_upload(req)
    except ValueError as exc:
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={"detail": str(exc)},
        )

    try:
        target_path = await _write_image_to_ssh_target(session, data, ext)
    except Exception as exc:
        logger.warning("SSH image upload failed for session %s: %s", session_id, exc)
        return JSONResponse(
            status_code=status.HTTP_502_BAD_GATEWAY,
            content={"detail": f"Failed to write image to SSH target: {exc}"},
        )

    session.image_upload_count += 1
    session.touch()
    return SshImageUploadResponse(
        target_image_path=target_path,
        mime_type=mime_type,
        size=len(data),
    )


# ---------------------------------------------------------------------------
# POST /{session_id}/resize — terminal resize (backup REST endpoint)
# ---------------------------------------------------------------------------


@router.post("/{session_id}/resize", dependencies=[Depends(verify_token)])
async def resize(session_id: str, req: SshResizeRequest):
    session = ssh_sessions.get(session_id)
    if session is None:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": "Session not found"},
        )

    session.process.change_terminal_size(req.cols, req.rows)
    session.cols = req.cols
    session.rows = req.rows
    session.touch()
    return {"status": "resized", "cols": req.cols, "rows": req.rows}


# ---------------------------------------------------------------------------
# POST /{session_id}/close — close SSH session
# ---------------------------------------------------------------------------


@router.post("/{session_id}/close", dependencies=[Depends(verify_token)])
async def close(session_id: str):
    ok = await close_ssh_session(session_id)
    if not ok:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": "Session not found"},
        )
    return {"status": "closed", "session_id": session_id}


# ---------------------------------------------------------------------------
# GET /sessions — list active sessions
# ---------------------------------------------------------------------------


@router.get("/sessions", response_model=list[SshSessionInfo], dependencies=[Depends(verify_token)])
async def list_sessions():
    return [
        SshSessionInfo(
            session_id=s.session_id,
            host=s.host,
            port=s.port,
            username=s.username,
            connected_at=s.connected_at,
            last_activity=s.last_activity,
            cols=s.cols,
            rows=s.rows,
            directory_id=s.directory_id,
        )
        for s in ssh_sessions.values()
    ]
