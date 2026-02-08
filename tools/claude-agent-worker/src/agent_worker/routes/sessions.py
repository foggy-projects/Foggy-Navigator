from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status

from ..auth import verify_token
from ..claude.sdk_wrapper import session_store
from ..models import SessionInfo

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
    )
