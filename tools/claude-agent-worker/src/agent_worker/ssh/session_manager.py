"""SSH session lifecycle management.

Maintains a global registry of active SSH sessions backed by asyncssh,
with idle-timeout cleanup running as a background asyncio task.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

import asyncssh

from ..config import settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Session data
# ---------------------------------------------------------------------------


@dataclass
class SshSession:
    """A single active SSH pseudo-terminal session."""

    session_id: str
    host: str
    port: int
    username: str
    conn: asyncssh.SSHClientConnection
    process: asyncssh.SSHClientProcess
    connected_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    last_activity: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    cols: int = 80
    rows: int = 24
    directory_id: str | None = None

    def touch(self) -> None:
        self.last_activity = datetime.now(timezone.utc)

    async def close(self) -> None:
        for label, action in [
            ("write_eof", lambda: self.process.stdin.write_eof()),
            ("process.close", lambda: self.process.close()),
            ("conn.close", lambda: self.conn.close()),
        ]:
            try:
                action()
            except Exception as exc:
                logger.debug("SSH session %s close '%s' failed: %s", self.session_id, label, exc)
        try:
            await self.conn.wait_closed()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Global registry
# ---------------------------------------------------------------------------

ssh_sessions: dict[str, SshSession] = {}

_cleanup_task: asyncio.Task | None = None


# ---------------------------------------------------------------------------
# Core operations
# ---------------------------------------------------------------------------


async def create_ssh_session(
    host: str,
    port: int,
    username: str,
    password: str | None = None,
    private_key: str | None = None,
    cols: int = 80,
    rows: int = 24,
    cwd: str | None = None,
    directory_id: str | None = None,
) -> SshSession:
    """Open an SSH connection + PTY process and register it."""

    if len(ssh_sessions) >= settings.max_ssh_sessions:
        raise RuntimeError(f"Max SSH sessions ({settings.max_ssh_sessions}) reached")

    connect_kwargs: dict = dict(
        host=host,
        port=port,
        username=username,
        known_hosts=None,  # internal/VPN — skip host-key verification
    )

    if private_key:
        connect_kwargs["client_keys"] = [asyncssh.import_private_key(private_key)]
    elif password:
        connect_kwargs["password"] = password
    else:
        raise ValueError("Either password or private_key must be provided")

    conn = await asyncssh.connect(**connect_kwargs)
    try:
        process = await conn.create_process(
            term_type="xterm-256color",
            term_size=(cols, rows),
            encoding=None,  # raw bytes I/O
        )
    except Exception:
        conn.close()
        raise

    session_id = uuid.uuid4().hex[:12]
    session = SshSession(
        session_id=session_id,
        host=host,
        port=port,
        username=username,
        conn=conn,
        process=process,
        cols=cols,
        rows=rows,
        directory_id=directory_id,
    )
    ssh_sessions[session_id] = session

    # Auto cd into working directory if provided
    # Fire-and-forget: don't block the HTTP response; let the shell fully
    # initialize before sending commands (PowerShell can take >1s to start)
    if cwd:
        async def _send_cwd(proc: asyncssh.SSHClientProcess, path: str) -> None:
            await asyncio.sleep(1.5)
            try:
                # Use \r (carriage return) — that's what terminal Enter key sends
                proc.stdin.write(f"cd '{path}'; clear\r".encode("utf-8"))
            except Exception as exc:
                logger.debug("Failed to send cwd command: %s", exc)

        asyncio.create_task(_send_cwd(process, cwd))

    logger.info("SSH session %s created → %s@%s:%d (cwd=%s)", session_id, username, host, port, cwd)
    return session


async def close_ssh_session(session_id: str) -> bool:
    """Close and unregister a session.  Returns False if not found."""

    session = ssh_sessions.pop(session_id, None)
    if session is None:
        return False
    await session.close()
    logger.info("SSH session %s closed", session_id)
    return True


# ---------------------------------------------------------------------------
# Idle cleanup
# ---------------------------------------------------------------------------


async def _cleanup_idle_sessions() -> None:
    """Periodically scan and close sessions that exceeded the idle timeout."""

    while True:
        await asyncio.sleep(60)
        now = datetime.now(timezone.utc)
        timeout = settings.ssh_idle_timeout_seconds
        to_close: list[str] = []
        for sid, sess in list(ssh_sessions.items()):
            idle = (now - sess.last_activity).total_seconds()
            if idle > timeout:
                to_close.append(sid)
        for sid in to_close:
            logger.info("SSH session %s idle for >%ds — closing", sid, timeout)
            await close_ssh_session(sid)


def start_cleanup_task() -> None:
    """Start the background cleanup coroutine (called from lifespan startup)."""

    global _cleanup_task
    _cleanup_task = asyncio.create_task(_cleanup_idle_sessions())
    logger.info("SSH idle-cleanup task started (timeout=%ds)", settings.ssh_idle_timeout_seconds)


async def stop_cleanup_and_close_all() -> None:
    """Cancel cleanup and close every open session (called from lifespan shutdown)."""

    global _cleanup_task
    if _cleanup_task is not None:
        _cleanup_task.cancel()
        try:
            await _cleanup_task
        except asyncio.CancelledError:
            pass
        _cleanup_task = None

    for sid in list(ssh_sessions):
        await close_ssh_session(sid)
    logger.info("All SSH sessions closed")
