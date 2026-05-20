"""Resume endpoint - external trigger to resume a suspended Frame.

Java side calls ``POST /api/v1/resume`` with ``taskId`` and the upstream
``contextId``. Worker finds the AWAITING_APPROVAL Frame inside that persisted
session directory and resumes execution.

Design reference: Doc 31 §16.5
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from ..auth import verify_token
from ..runtime.file_layout import require_standard_context_id
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.fsscript_bridge import (
    FsscriptRunBridge,
    FsscriptRunNotFound,
    get_fsscript_bridge,
)
from ..runtime.skill_runtime import FrameNotFound, IllegalStateTransition, SkillRuntime

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["resume"], dependencies=[Depends(verify_token)])


class ResumeRequest(BaseModel):
    """Request body for ``POST /api/v1/resume``."""

    model_config = ConfigDict(populate_by_name=True)

    task_id: str = Field(..., validation_alias=AliasChoices("taskId", "task_id"))
    context_id: str | None = Field(None, validation_alias=AliasChoices("contextId", "context_id"))
    session_id: str | None = Field(None, validation_alias=AliasChoices("sessionId", "session_id"))
    approval_result: str = Field(..., validation_alias=AliasChoices("approvalResult", "approval_result"))
    comment: str = ""


class ResumeResponse(BaseModel):
    """Response for a successful resume."""

    task_id: str
    frame_id: str
    status: str
    message: str
    resume_message: dict[str, Any] | None = None


# ---------------------------------------------------------------------------
# Singleton runtime components — injected from main.py at startup
# ---------------------------------------------------------------------------

_runtime: SkillRuntime | None = None
_journal: FileFrameJournal | None = None
_fsscript_bridge: FsscriptRunBridge | None = None


def configure(
    runtime: SkillRuntime,
    journal: FileFrameJournal,
    fsscript_bridge: FsscriptRunBridge | None = None,
) -> None:
    """Wire up the shared runtime and journal.  Called once at app startup."""
    global _runtime, _journal, _fsscript_bridge
    _runtime = runtime
    _journal = journal
    _fsscript_bridge = fsscript_bridge or get_fsscript_bridge()


@router.post("/resume", response_model=ResumeResponse)
async def resume(request: ResumeRequest) -> ResumeResponse:
    """Resume a task whose Frame is in AWAITING_APPROVAL state.

    ``contextId`` is required. Missing context is an upstream contract bug.
    """
    if _runtime is None or _journal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Resume service not configured",
        )
    context_id = _required_context_id(request)

    # 1. Find the suspended frame from file journal
    frame = _journal.find_awaiting_approval(request.task_id, conversation_id=context_id)
    if frame is None:
        if _fsscript_bridge is not None:
            try:
                result = _fsscript_bridge.resume_task(
                    request.task_id,
                    approval_result=request.approval_result,
                    comment=request.comment,
                )
                return ResumeResponse(
                    task_id=request.task_id,
                    frame_id=result.get("script_run_id", ""),
                    status=result.get("status", "RUNNING"),
                    message=(
                        f"FSScript run {result.get('script_run_id', '')} "
                        f"resumed with result: {request.approval_result}"
                    ),
                    resume_message=_resolve_fsscript_resume_message(
                        result,
                        request.approval_result,
                        request.comment,
                    ),
                )
            except FsscriptRunNotFound:
                pass
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No AWAITING_APPROVAL frame found for task {request.task_id}",
        )

    # 2. Restore frame into in-memory store (if not already there)
    if _runtime.get_frame(frame.frame_id) is None:
        _runtime.restore_frame(frame)
        logger.info(
            "Restored frame=%s from file journal for task=%s context=%s",
            frame.frame_id, request.task_id, context_id,
        )

    # 3. Resume the frame
    resume_message = _resolve_resume_message(
        frame.approval_request,
        request.approval_result,
        request.comment,
    )
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
    resume_message = _enrich_resume_message_with_execution_report(resume_message)

    return ResumeResponse(
        task_id=request.task_id,
        frame_id=frame.frame_id,
        status="RUNNING",
        message=f"Frame {frame.frame_id} resumed with result: {request.approval_result}",
        resume_message=resume_message,
    )


def _required_context_id(request: ResumeRequest) -> str:
    if isinstance(request.context_id, str) and request.context_id.strip():
        try:
            return require_standard_context_id(request.context_id)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
    raise HTTPException(
        status_code=422,
        detail="contextId is required for resume",
    )


def _resolve_fsscript_resume_message(
    result: dict[str, Any],
    approval_result: str,
    comment: str,
) -> dict[str, Any]:
    approval_payload = result.get("approval_payload") if isinstance(result.get("approval_payload"), dict) else {}
    summary = result.get("summary") if isinstance(result.get("summary"), dict) else {}
    if not summary and isinstance(approval_payload.get("summary"), dict):
        summary = approval_payload["summary"]

    content: str | None = None
    source: str | None = None
    for source_name, source_payload in (
        ("fsscript_result", result),
        ("fsscript_payload", approval_payload),
        ("fsscript_summary", summary),
    ):
        messages = _find_message_templates(source_payload)
        content, source_key = _select_resume_message(messages, approval_result)
        if content:
            source = f"{source_name}.{source_key}"
            break
    if not content or not source:
        content, source = _fallback_resume_message(approval_result), "platform_default"
    return {
        "content": content,
        "source": source,
        "approval_result": approval_result,
        "comment": comment,
        "script_run_id": result.get("script_run_id"),
        "suspend_id": result.get("suspend_id"),
    }


def _resolve_resume_message(
    approval_request: dict[str, Any] | None,
    approval_result: str,
    comment: str,
) -> dict[str, Any]:
    request_payload = approval_request or {}
    payload = request_payload.get("payload") if isinstance(request_payload.get("payload"), dict) else {}
    gateway_result = payload.get("gateway_result") if isinstance(payload.get("gateway_result"), dict) else {}
    input_payload = payload.get("input") if isinstance(payload.get("input"), dict) else {}
    summary = request_payload.get("summary") if isinstance(request_payload.get("summary"), dict) else {}

    for source_name, source_payload in (
        ("call_payload", payload),
        ("function_input", input_payload),
        ("gateway_result", gateway_result),
        ("approval_summary", summary),
        ("approval_request", request_payload),
    ):
        messages = _find_message_templates(source_payload)
        content, source_key = _select_resume_message(messages, approval_result)
        if content:
            return _build_resume_message(
                content=content,
                source=f"{source_name}.{source_key}",
                approval_request=request_payload,
                approval_result=approval_result,
                comment=comment,
            )

    return _build_resume_message(
        content=_fallback_resume_message(approval_result),
        source="platform_default",
        approval_request=request_payload,
        approval_result=approval_result,
        comment=comment,
    )


def _find_message_templates(payload: Any) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    for key in (
        "post_approval_message",
        "postApprovalMessage",
        "approval_messages",
        "approvalMessages",
        "resume_message",
        "resumeMessage",
    ):
        value = payload.get(key)
        if isinstance(value, dict):
            return value
    return None


def _select_resume_message(
    messages: dict[str, Any] | None,
    approval_result: str,
) -> tuple[str | None, str | None]:
    if not messages:
        return None, None
    normalized = _normalize_approval_result(approval_result)
    keys = [normalized]
    if normalized == "approved":
        keys.extend(["approve", "accepted", "success"])
    elif normalized == "rejected":
        keys.extend(["reject", "denied", "cancelled", "canceled"])
    elif normalized == "expired":
        keys.append("timeout")
    keys.append("default")
    for key in keys:
        value = messages.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip(), key
    return None, None


def _build_resume_message(
    *,
    content: str,
    source: str,
    approval_request: dict[str, Any],
    approval_result: str,
    comment: str,
) -> dict[str, Any]:
    payload = approval_request.get("payload") if isinstance(approval_request.get("payload"), dict) else {}
    return {
        "content": content,
        "source": source,
        "approval_result": approval_result,
        "comment": comment,
        "approval_type": approval_request.get("approval_type"),
        "function_id": approval_request.get("function_id"),
        "version": approval_request.get("version"),
        "suspend_id": approval_request.get("suspend_id"),
        "function_frame_id": payload.get("function_frame_id"),
    }


def _enrich_resume_message_with_execution_report(message: dict[str, Any]) -> dict[str, Any]:
    if _runtime is None:
        return message
    function_frame_id = message.get("function_frame_id")
    if not isinstance(function_frame_id, str) or not function_frame_id:
        return message
    function_frame = _runtime.get_frame(function_frame_id)
    if function_frame is None:
        return message
    state = function_frame.private_working_state
    report_ref = state.get("execution_report_ref")
    if isinstance(report_ref, str) and report_ref:
        message["execution_report_ref"] = report_ref
        message["function_execution_report_ref"] = report_ref
    report_digest = state.get("execution_report_digest")
    if isinstance(report_digest, dict):
        message["execution_report_digest"] = report_digest
        message["function_execution_report_digest"] = report_digest
    return message


def _fallback_resume_message(approval_result: str) -> str:
    normalized = _normalize_approval_result(approval_result)
    if normalized == "approved":
        return "审批已通过，系统已继续处理该业务操作。"
    if normalized == "rejected":
        return "审批已拒绝，本次业务操作不会继续执行。"
    if normalized == "expired":
        return "审批已超时，本次业务操作未继续执行。"
    return f"审批结果已更新：{approval_result}。"


def _normalize_approval_result(approval_result: str) -> str:
    normalized = (approval_result or "").strip().lower()
    if normalized in {"approved", "approve", "ok", "accepted", "accept"}:
        return "approved"
    if normalized in {"rejected", "reject", "denied", "deny", "cancelled", "canceled"}:
        return "rejected"
    if normalized in {"expired", "timeout", "timed_out"}:
        return "expired"
    return normalized or "unknown"
