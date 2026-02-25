"""SSH WebSocket bridge endpoints.

Provides HTTP endpoints to create/close SSH sessions and a WebSocket
endpoint that bridges browser ↔ SSH PTY in real time.
"""

from __future__ import annotations

import asyncio
import json
import logging

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect, status
from fastapi.responses import JSONResponse

from ..auth import verify_token
from ..config import settings
from ..models import SshConnectRequest, SshConnectResponse, SshResizeRequest, SshSessionInfo
from ..ssh.session_manager import (
    close_ssh_session,
    create_ssh_session,
    ssh_sessions,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/ssh", tags=["ssh"])


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
            while True:
                data = await session.process.stdout.read(4096)
                if not data:
                    break
                session.touch()
                await websocket.send_bytes(data)
        except Exception as exc:
            logger.debug("ssh→ws ended: %s", exc)

    async def _ws_to_ssh():
        """Forward WebSocket frames → SSH stdin, handle resize control messages."""
        try:
            while True:
                msg = await websocket.receive()
                if msg.get("type") == "websocket.disconnect":
                    break

                session.touch()

                if "bytes" in msg and msg["bytes"]:
                    session.process.stdin.write(msg["bytes"])
                elif "text" in msg and msg["text"]:
                    try:
                        ctrl = json.loads(msg["text"])
                        if ctrl.get("type") == "resize":
                            cols = max(1, min(500, int(ctrl["cols"])))
                            rows = max(1, min(500, int(ctrl["rows"])))
                            session.process.change_terminal_size(cols, rows)
                            session.cols = cols
                            session.rows = rows
                    except (json.JSONDecodeError, KeyError, ValueError):
                        # Treat non-JSON text as raw terminal input
                        session.process.stdin.write(msg["text"].encode("utf-8"))
        except WebSocketDisconnect:
            pass
        except Exception as exc:
            logger.debug("ws→ssh ended: %s", exc)

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
        )
        for s in ssh_sessions.values()
    ]
