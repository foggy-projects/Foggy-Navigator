"""Frame interruption endpoint.

Java calls this endpoint when a visible task is aborted or its stream is lost.
The Worker keeps the persistent root frame reusable for the next user turn
without trying to resume the old task loop.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from ..auth import verify_token
from ..models import FrameStatus, SkillFrameState
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.skill_runtime import FrameNotFound, IllegalStateTransition, SkillRuntime

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["frames"], dependencies=[Depends(verify_token)])

ROOT_SKILL_ID = "system.root"


class FrameInterruptionRequest(BaseModel):
    """Request body for ``POST /api/v1/frames/interruption``."""

    model_config = ConfigDict(populate_by_name=True)

    task_id: str | None = Field(
        None,
        validation_alias=AliasChoices("taskId", "task_id"),
    )
    session_id: str | None = Field(
        None,
        validation_alias=AliasChoices("sessionId", "session_id"),
    )
    context_id: str | None = Field(
        None,
        validation_alias=AliasChoices("contextId", "context_id"),
    )
    reason: str
    error: str = ""
    context: dict[str, Any] | None = None


class FrameInterruptionResponse(BaseModel):
    """Response for an interruption record request."""

    task_id: str | None = None
    frame_id: str | None = None
    status: str
    message: str


_runtime: SkillRuntime | None = None
_journal: FileFrameJournal | None = None


def configure(runtime: SkillRuntime, journal: FileFrameJournal) -> None:
    """Wire up shared runtime and journal. Called once at app startup."""
    global _runtime, _journal
    _runtime = runtime
    _journal = journal


@router.post("/frames/interruption", response_model=FrameInterruptionResponse)
async def record_frame_interruption(
    request: FrameInterruptionRequest,
) -> FrameInterruptionResponse:
    """Record a recoverable interruption on the persistent root frame.

    The endpoint is intentionally idempotent-ish: if the frame is not found,
    it returns ``not_found`` with HTTP 200 so Java-side cancellation/failure
    can still complete deterministically.
    """
    if _runtime is None or _journal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Frame interruption service not configured",
        )

    frame = _find_root_frame(request)
    if frame is None:
        logger.info(
            "No root frame found for interruption: task=%s session=%s context=%s reason=%s",
            request.task_id,
            request.session_id,
            request.context_id,
            request.reason,
        )
        return FrameInterruptionResponse(
            task_id=request.task_id,
            status="not_found",
            message="No reusable root frame found for interruption request",
        )

    if _runtime.get_frame(frame.frame_id) is None:
        _runtime.restore_frame(frame)

    conversation_id = _conversation_id(request)
    if request.task_id:
        frame = _runtime.rebind_frame_to_task(
            frame.frame_id,
            request.task_id,
            session_id=request.session_id,
            conversation_id=conversation_id,
        )

    _normalize_awaiting_frame_for_interruption(frame, request)
    _normalize_waiting_child_frame_for_interruption(frame, request)

    try:
        _runtime.record_recoverable_interruption(
            frame.frame_id,
            reason=request.reason,
            error=request.error,
            task_id=request.task_id,
        )
    except FrameNotFound as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    logger.info(
        "Recorded recoverable root frame interruption: frame=%s task=%s reason=%s",
        frame.frame_id,
        request.task_id,
        request.reason,
    )
    return FrameInterruptionResponse(
        task_id=request.task_id,
        frame_id=frame.frame_id,
        status="recorded",
        message="Recoverable interruption recorded",
    )


def _find_root_frame(request: FrameInterruptionRequest) -> SkillFrameState | None:
    candidates: list[SkillFrameState] = []
    if request.task_id:
        candidates.extend(_runtime.get_frames_by_task(request.task_id))  # type: ignore[union-attr]
        candidates.extend(_journal.load_by_task(request.task_id))  # type: ignore[union-attr]

    conversation_id = _conversation_id(request)
    if conversation_id:
        candidates.extend(_runtime.get_frames_by_conversation(conversation_id))  # type: ignore[union-attr]
        candidates.extend(_journal.load_by_conversation(conversation_id))  # type: ignore[union-attr]

    seen: set[str] = set()
    deduped: list[SkillFrameState] = []
    for frame in candidates:
        if frame.frame_id in seen:
            continue
        seen.add(frame.frame_id)
        deduped.append(frame)

    for status_value in (FrameStatus.RUNNING, FrameStatus.WAITING_CHILD, FrameStatus.AWAITING_APPROVAL):
        for frame in deduped:
            if frame.skill_id == ROOT_SKILL_ID and frame.status == status_value:
                return frame
    return None


def _normalize_awaiting_frame_for_interruption(
    frame: SkillFrameState,
    request: FrameInterruptionRequest,
) -> None:
    if frame.status != FrameStatus.AWAITING_APPROVAL:
        return
    if _normalize_reason(request.reason) not in {"user_cancelled", "approval_rejected"}:
        return
    try:
        _runtime.resume_from_approval(  # type: ignore[union-attr]
            frame.frame_id,
            approval_result="rejected",
            comment=request.error or request.reason,
        )
    except IllegalStateTransition:
        logger.debug(
            "Awaiting frame could not be normalized before interruption: frame=%s",
            frame.frame_id,
            exc_info=True,
        )


def _normalize_waiting_child_frame_for_interruption(
    frame: SkillFrameState,
    request: FrameInterruptionRequest,
) -> None:
    if frame.status != FrameStatus.WAITING_CHILD:
        return
    try:
        _runtime.record_recoverable_child_interruption(  # type: ignore[union-attr]
            frame.frame_id,
            reason=request.reason,
            error=request.error,
            task_id=request.task_id,
        )
    except (FrameNotFound, IllegalStateTransition):
        logger.debug(
            "Waiting-child root could not record child interruption: frame=%s",
            frame.frame_id,
            exc_info=True,
        )


def _conversation_id(request: FrameInterruptionRequest) -> str | None:
    context = request.context or {}
    for value in (
        request.context_id,
        context.get("contextId"),
        context.get("context_id"),
        context.get("conversationId"),
        context.get("conversation_id"),
        context.get("foggy_session_id"),
        context.get("session_id"),
        request.session_id,
    ):
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _normalize_reason(reason: str) -> str:
    normalized = (reason or "").strip().lower()
    if normalized in {"user_abort", "user_aborted", "cancelled", "canceled"}:
        return "user_cancelled"
    if normalized in {"rejected", "reject", "denied", "deny"}:
        return "approval_rejected"
    return normalized
