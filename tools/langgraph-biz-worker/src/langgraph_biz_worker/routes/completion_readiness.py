"""Authenticated, content-free completion-readiness inspection."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends

from ..auth import verify_token
from ..config import settings
from ..runtime.completion_receipt import CompletionReceiptStore, RECEIPT_SCHEMA, utc_now
from ..runtime.skill_registry import _DEFAULT_SKILLS_ROOT
from .health import active_task_metadata, active_tasks

router = APIRouter(
    prefix="/api/v1",
    tags=["completion-readiness"],
    dependencies=[Depends(verify_token)],
)


def receipt_store() -> CompletionReceiptStore:
    skills_root = Path(_DEFAULT_SKILLS_ROOT)
    data_root = Path(settings.data_root) if settings.data_root else skills_root.parent / "data"
    return CompletionReceiptStore(data_root)


def _base_response() -> dict[str, Any]:
    return {
        "worker_reachable": True,
        "worker_observed_at": utc_now(),
        "worker_task_known": False,
        "worker_task_state": "UNKNOWN",
        "provider_process_present": None,
        "provider_process_state": None,
        "provider_active_task_present": False,
        "provider_task_terminal": False,
        "provider_terminal_status": None,
        "final_output_present": False,
        "final_output_digest": None,
        "final_output_recorded_at": None,
        "structured_output_present": False,
        "structured_output_digest": None,
        "terminal_signal_present": False,
        "terminal_signal_source": None,
        "terminal_signal_recorded_at": None,
        "completion_signal_present": False,
        "completion_signal_source": None,
        "completion_signal_recorded_at": None,
        "result_recoverable": False,
        "evidence_schema": RECEIPT_SCHEMA,
        "provider_task_id": None,
        "receipt_dispatch_count": None,
        "receipt_worker_id": None,
        "receipt_task_id": None,
        "terminal_error_code": None,
        "evidence_conflict": False,
        "sanitized_error_code": "WORKER_TASK_NOT_FOUND",
    }


@router.get("/tasks/{task_id}/completion-readiness")
async def completion_readiness(task_id: str) -> dict[str, Any]:
    response = _base_response()
    if task_id in active_tasks:
        metadata = active_task_metadata.get(task_id, {})
        response.update({
            "worker_task_known": True,
            "worker_task_state": "RUNNING",
            "provider_active_task_present": True,
            "provider_task_id": task_id,
            "receipt_dispatch_count": metadata.get("dispatch_count"),
            "receipt_worker_id": metadata.get("worker_id"),
            "receipt_task_id": task_id,
            "sanitized_error_code": None,
        })
        return response

    receipt, error_code = receipt_store().inspect(task_id)
    if error_code:
        response["sanitized_error_code"] = error_code
        return response
    if receipt is None:
        return response

    response.update({
        "worker_task_known": True,
        "worker_task_state": receipt["terminal_status"],
        "provider_task_terminal": True,
        "provider_terminal_status": receipt["terminal_status"],
        "final_output_present": receipt["final_output_present"],
        "final_output_digest": receipt["final_output_digest"],
        "final_output_recorded_at": (
            receipt["recorded_at"] if receipt["final_output_present"] else None
        ),
        "structured_output_present": receipt["structured_output_present"],
        "structured_output_digest": receipt["structured_output_digest"],
        "terminal_signal_present": receipt["terminal_signal_present"],
        "terminal_signal_source": receipt["terminal_signal_source"],
        "terminal_signal_recorded_at": receipt["terminal_signal_recorded_at"],
        "completion_signal_present": receipt["completion_signal_present"],
        "completion_signal_source": receipt["completion_signal_source"],
        "completion_signal_recorded_at": receipt["completion_signal_recorded_at"],
        "provider_task_id": receipt["provider_task_id"],
        "receipt_dispatch_count": receipt["dispatch_count"],
        "receipt_worker_id": receipt["worker_id"],
        "receipt_task_id": receipt["task_id"],
        "terminal_error_code": receipt["terminal_error_code"],
        "evidence_conflict": receipt["evidence_conflict"],
        "sanitized_error_code": (
            "LANGGRAPH_COMPLETION_EVIDENCE_CONFLICT"
            if receipt["evidence_conflict"] else None
        ),
    })
    return response
