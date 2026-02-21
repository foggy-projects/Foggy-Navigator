from __future__ import annotations

import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status

from ..auth import verify_token
from ..claude.sdk_wrapper import session_store
from ..claude.session_scanner import (
    count_session_messages,
    read_session_messages,
    scan_all_sessions,
    scan_session_checkpoints,
)
from ..config import settings
from ..models import SessionInfo

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["sessions"], dependencies=[Depends(verify_token)])


@router.get("/sessions", response_model=list[SessionInfo])
async def list_sessions() -> list[SessionInfo]:
    """Return metadata for all tracked Claude Code sessions on this worker.

    Sessions are recorded locally whenever a query produces a
    ``ResultMessage`` that carries a ``session_id``.
    """

    return [
        SessionInfo(
            session_id=sid,
            cwd=info["cwd"],
            created_at=info["created_at"],
            updated_at=info["updated_at"],
            slug=info.get("slug"),
            git_branch=info.get("git_branch"),
        )
        for sid, info in session_store.items()
    ]


@router.get("/sessions/{session_id}", response_model=SessionInfo)
async def get_session(session_id: str) -> SessionInfo:
    """Return details for a single tracked session."""

    info = session_store.get(session_id)
    if info is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Session '{session_id}' not found",
        )

    return SessionInfo(
        session_id=session_id,
        cwd=info["cwd"],
        created_at=info["created_at"],
        updated_at=info["updated_at"],
        slug=info.get("slug"),
        git_branch=info.get("git_branch"),
    )


@router.get("/sessions/{session_id}/checkpoints")
async def get_session_checkpoints(session_id: str) -> list[dict]:
    """Scan a Claude Code local JSONL file for UserMessage UUIDs as checkpoints."""
    return scan_session_checkpoints(session_id)


@router.get("/sessions/{session_id}/message-count")
async def get_session_message_count(session_id: str) -> dict:
    """Count user/assistant messages in a Claude Code local JSONL file."""
    return count_session_messages(session_id)


@router.get("/sessions/{session_id}/messages")
async def get_session_messages(session_id: str) -> list[dict]:
    """Read conversation messages from a Claude Code local JSONL file.

    Returns user prompts and assistant text responses (no tool/thinking blocks).
    """
    messages = read_session_messages(session_id)
    if not messages:
        # Still return 200 with empty list - the session may exist but have no displayable messages
        return []
    return messages


@router.post("/sessions/sync")
async def sync_sessions() -> dict:
    """Re-scan Claude Code local JSONL storage and merge into session_store.

    Only scans directories listed in ``allowed_cwds``.  Existing runtime
    sessions are preserved (scanner results do not overwrite them).
    """
    if not settings.allowed_cwds:
        return {"synced": 0, "total": len(session_store)}

    scanned = scan_all_sessions(settings.allowed_cwds)

    # Merge: only add sessions that are NOT already tracked at runtime.
    added = 0
    for sid, info in scanned.items():
        if sid not in session_store:
            session_store[sid] = info
            added += 1

    logger.info("Session sync: scanned=%d, new=%d, total=%d", len(scanned), added, len(session_store))
    return {"synced": added, "total": len(session_store)}
