"""Resume endpoint — external trigger to resume a suspended Frame.

Java side calls ``POST /api/v1/resume`` with only ``taskId`` (no frameId).
Worker finds the AWAITING_APPROVAL Frame internally and resumes execution.

Design reference: Doc 31 §16.5
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field

from ..auth import verify_token
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.skill_runtime import FrameNotFound, IllegalStateTransition, SkillRuntime

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["resume"], dependencies=[Depends(verify_token)])


class ResumeRequest(BaseModel):
    """Request body for ``POST /api/v1/resume``."""

    task_id: str = Field(..., alias="taskId")
    approval_result: str = Field(..., alias="approvalResult")
    comment: str = ""


class ResumeResponse(BaseModel):
    """Response for a successful resume."""

    task_id: str
    frame_id: str
    status: str
    message: str


# ---------------------------------------------------------------------------
# Singleton runtime components — injected from main.py at startup
# ---------------------------------------------------------------------------

_runtime: SkillRuntime | None = None
_journal: FileFrameJournal | None = None


def configure(runtime: SkillRuntime, journal: FileFrameJournal) -> None:
    """Wire up the shared runtime and journal.  Called once at app startup."""
    global _runtime, _journal
    _runtime = runtime
    _journal = journal


@router.post("/resume", response_model=ResumeResponse)
async def resume(request: ResumeRequest) -> ResumeResponse:
    """Resume a task whose Frame is in AWAITING_APPROVAL state.

    Java side passes only ``taskId``.  Worker locates the suspended Frame
    internally via the file journal.
    """
    if _runtime is None or _journal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Resume service not configured",
        )

    # 1. Find the suspended frame from file journal
    frame = _journal.find_awaiting_approval(request.task_id)
    if frame is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No AWAITING_APPROVAL frame found for task {request.task_id}",
        )

    # 2. Restore frame into in-memory store (if not already there)
    if _runtime.get_frame(frame.frame_id) is None:
        _runtime.restore_frame(frame)
        logger.info(
            "Restored frame=%s from file journal for task=%s",
            frame.frame_id, request.task_id,
        )

    # 3. Resume the frame
    try:
        _runtime.resume_from_approval(
            frame.frame_id,
            approval_result=request.approval_result,
            comment=request.comment,
        )
    except (FrameNotFound, IllegalStateTransition) as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(exc),
        )

    return ResumeResponse(
        task_id=request.task_id,
        frame_id=frame.frame_id,
        status="RUNNING",
        message=f"Frame {frame.frame_id} resumed with result: {request.approval_result}",
    )
