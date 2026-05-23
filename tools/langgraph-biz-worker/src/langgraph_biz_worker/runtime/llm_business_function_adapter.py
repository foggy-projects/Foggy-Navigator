"""Business function helper logic for the LLM skill agent."""

from __future__ import annotations

from typing import Any

from .llm_tool_call_codec import _safe_content


def _looks_like_business_function_id(name: str) -> bool:
    return "." in name and not name.startswith(".") and not name.endswith(".")


def _split_business_function_tool_name(name: str) -> tuple[str, str | None]:
    if "@" not in name:
        return name, None
    function_id, version = name.rsplit("@", 1)
    return function_id, version or None


def _business_function_result_is_suspended(result: dict[str, Any]) -> bool:
    status = str(result.get("status") or "").upper()
    return (
        status == "SUSPENDED"
        or result.get("approvalRequired") is True
        or result.get("approval_required") is True
        or result.get("approval_wait") is True
    )


def _business_function_suspend_id(result: dict[str, Any]) -> str | None:
    value = result.get("suspendId") or result.get("suspend_id")
    return str(value) if value is not None else None


def _business_function_timeout_at(result: dict[str, Any]) -> str | None:
    value = result.get("timeoutAt") or result.get("timeout_at")
    return str(value) if value is not None else None


def _business_function_approval_summary(result: dict[str, Any], function_id: str) -> dict[str, Any]:
    raw_summary = result.get("approvalSummary") or result.get("approval_summary") or result.get("summary")
    if isinstance(raw_summary, dict):
        summary = dict(raw_summary)
    else:
        summary = {}
    summary.setdefault("approval_type", "business_function")
    summary.setdefault("function_id", function_id)
    summary.setdefault(
        "title",
        result.get("message") or f"Business function {function_id} requires approval",
    )
    summary.setdefault("reason", "approval_required")
    return _safe_content(summary)
