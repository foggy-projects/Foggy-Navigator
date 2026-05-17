"""Frame report reconciliation endpoints used by Navigator Java runtime."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from ..auth import verify_token
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.frame_execution_report import read_frame_execution_report
from ..runtime.skill_runtime import SkillRuntime

router = APIRouter(prefix="/api/v1", tags=["frame-reports"], dependencies=[Depends(verify_token)])


class BusinessFunctionResultReportRequest(BaseModel):
    task_id: str = Field(..., alias="taskId")
    suspend_id: str = Field(..., alias="suspendId")
    success: bool
    status: str | None = None
    execution_status: str | None = Field(None, alias="executionStatus")
    content: str = ""
    error_message: str | None = Field(None, alias="errorMessage")
    function_id: str | None = Field(None, alias="functionId")
    version: str | None = None
    result: dict[str, Any] | None = None


_runtime: SkillRuntime | None = None
_journal: FileFrameJournal | None = None


def configure(runtime: SkillRuntime, journal: FileFrameJournal) -> None:
    global _runtime, _journal
    _runtime = runtime
    _journal = journal


@router.post("/frames/business-function-result")
async def finalize_business_function_result_report(
    request: BusinessFunctionResultReportRequest,
) -> dict[str, Any]:
    if _runtime is None or _journal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Frame report service not configured",
        )

    result_payload = dict(request.result or {})
    result_payload.setdefault("status", request.status)
    result_payload.setdefault("executionStatus", request.execution_status)
    result_payload.setdefault("content", request.content)
    result_payload.setdefault("errorMessage", request.error_message)
    result_payload.setdefault("functionId", request.function_id)
    result_payload.setdefault("version", request.version)

    return _runtime.finalize_business_function_result(
        request.task_id,
        request.suspend_id,
        success=request.success,
        result=result_payload,
        summary=request.content,
        error_message=request.error_message or "",
    )


@router.get("/frame-reports")
async def get_frame_execution_report(
    report_ref: str | None = Query(None, alias="report_ref"),
    report_ref_camel: str | None = Query(None, alias="reportRef"),
    task_id: str | None = Query(None, alias="task_id"),
    task_id_camel: str | None = Query(None, alias="taskId"),
    frame_id: str | None = Query(None, alias="frame_id"),
    frame_id_camel: str | None = Query(None, alias="frameId"),
    mode: str = Query("summary"),
    max_chars: int = Query(6000, alias="max_chars"),
    max_chars_camel: int | None = Query(None, alias="maxChars"),
) -> dict[str, Any]:
    if _journal is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Frame report service not configured",
        )

    return read_frame_execution_report(
        _journal.data_root,
        report_ref=report_ref or report_ref_camel,
        task_id=task_id or task_id_camel,
        frame_id=frame_id or frame_id_camel,
        mode=mode,
        max_chars=max_chars_camel if max_chars_camel is not None else max_chars,
    )
