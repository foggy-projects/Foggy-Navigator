"""Business function helper logic for the LLM skill agent."""

from __future__ import annotations

import json
import re
from typing import Any

from .llm_tool_call_codec import _safe_content


_BUSINESS_IDENTIFIER_FIELDS = frozenset({
    "orderNo",
    "order_no",
    "orderIdentifier",
    "order_identifier",
    "waybillNo",
    "waybill_no",
    "businessOrderNo",
    "business_order_no",
})
_NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS = frozenset({
    "skillId",
    "skill_id",
    "functionId",
    "function_id",
    "objectId",
    "object_id",
    "routeName",
    "route_name",
    "frameId",
    "frame_id",
    "skillFrameId",
    "skill_frame_id",
    "functionFrameId",
    "function_frame_id",
    "taskId",
    "task_id",
    "sessionId",
    "session_id",
    "messageId",
    "message_id",
})
_NAVIGATOR_RUNTIME_IDENTIFIER_PATTERN = re.compile(r"\b(?:frm|lgt|msg|sess)_[A-Za-z0-9_-]+\b")
_BUSINESS_ID_CLAIM_PATTERN = re.compile(
    r"(订单号|运单号|业务单号|单号|order\s*(?:no|number|id)|waybill\s*(?:no|number|id))",
    re.IGNORECASE,
)
_FORMAL_ORDER_SUCCESS_PATTERN = re.compile(
    r"(订单创建成功|创建订单成功|已创建订单|已下单|下单成功|order\s+created)",
    re.IGNORECASE,
)


def _looks_like_business_function_id(name: str) -> bool:
    return "." in name and not name.startswith(".") and not name.endswith(".")


def _split_business_function_tool_name(name: str) -> tuple[str, str | None]:
    if "@" not in name:
        return name, None
    function_id, version = name.rsplit("@", 1)
    return function_id, version or None


def _guard_final_summary(
    summary: Any,
    structured_output: Any,
    frame: Any | None,
) -> str:
    text = summary if isinstance(summary, str) else str(summary or "")
    text = text.strip()
    if not text:
        return _safe_summary_from_context(frame, structured_output)

    claims_business_id = _BUSINESS_ID_CLAIM_PATTERN.search(text) is not None
    claims_formal_order = _FORMAL_ORDER_SUCCESS_PATTERN.search(text) is not None
    if not (claims_business_id or claims_formal_order):
        return text

    allowed_ids = _collect_business_identifier_values(structured_output)
    if frame is not None:
        for message in frame.private_messages:
            allowed_ids.update(_collect_business_identifier_values(message.get("content")))

    if claims_business_id and any(value and value in text for value in allowed_ids):
        return text

    if _is_page_action_without_business_identifier(structured_output) or _contains_runtime_identifier_claim(text):
        return _safe_summary_from_context(frame, structured_output)

    if claims_business_id and not allowed_ids:
        return _safe_summary_from_context(frame, structured_output)

    return text


def _contains_runtime_identifier_claim(text: str) -> bool:
    if _NAVIGATOR_RUNTIME_IDENTIFIER_PATTERN.search(text):
        return True
    return any(field in text for field in _NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS)


def _is_page_action_without_business_identifier(value: Any) -> bool:
    actions = _collect_action_like_outputs(value)
    if not actions:
        return False
    return not _collect_business_identifier_values(value)


def _collect_action_like_outputs(value: Any) -> list[dict[str, Any]]:
    parsed = _parse_maybe_json(value)
    if isinstance(parsed, list):
        actions: list[dict[str, Any]] = []
        for item in parsed:
            actions.extend(_collect_action_like_outputs(item))
        return actions
    if not isinstance(parsed, dict):
        return []
    actions = []
    action_type = parsed.get("type") or parsed.get("actionType") or parsed.get("action")
    if isinstance(action_type, str) and re.search(r"OPEN_.*PAGE|OPEN_TMS_PAGE", action_type, re.IGNORECASE):
        actions.append(parsed)
    for key in ("actions", "structured_output", "structuredOutput", "result", "output", "payload", "data"):
        actions.extend(_collect_action_like_outputs(parsed.get(key)))
    return actions


def _collect_business_identifier_values(value: Any) -> set[str]:
    parsed = _parse_maybe_json(value)
    found: set[str] = set()
    if isinstance(parsed, list):
        for item in parsed:
            found.update(_collect_business_identifier_values(item))
        return found
    if not isinstance(parsed, dict):
        return found
    for key, nested in parsed.items():
        if key in _BUSINESS_IDENTIFIER_FIELDS and isinstance(nested, (str, int, float)):
            text = str(nested).strip()
            if text:
                found.add(text)
        elif key not in _NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS:
            found.update(_collect_business_identifier_values(nested))
    return found


def _safe_summary_from_context(frame: Any | None, structured_output: Any) -> str:
    if frame is not None:
        for message in reversed(frame.private_messages):
            candidate = _extract_safe_summary(message.get("content"))
            if candidate:
                return candidate
    candidate = _extract_safe_summary(structured_output)
    if candidate:
        return candidate
    if _is_page_action_without_business_identifier(structured_output):
        return "已完成操作，可继续处理。"
    return "已完成操作。"


def _extract_safe_summary(value: Any) -> str | None:
    parsed = _parse_maybe_json(value)
    if isinstance(parsed, list):
        for item in reversed(parsed):
            candidate = _extract_safe_summary(item)
            if candidate:
                return candidate
        return None
    if not isinstance(parsed, dict):
        return None
    for key in ("summary", "message", "label"):
        candidate = parsed.get(key)
        if isinstance(candidate, str) and candidate.strip():
            text = candidate.strip()
            if (
                not _contains_runtime_identifier_claim(text)
                and not _BUSINESS_ID_CLAIM_PATTERN.search(text)
                and not _FORMAL_ORDER_SUCCESS_PATTERN.search(text)
            ):
                return text
    for key in ("result", "output", "data", "structured_output", "structuredOutput", "payload"):
        candidate = _extract_safe_summary(parsed.get(key))
        if candidate:
            return candidate
    return None


def _parse_maybe_json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


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
