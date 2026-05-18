"""Tool-call normalization and safe payload helpers for the LLM skill agent."""

from __future__ import annotations

import json
from typing import Any


_SCRUB_TEMPLATE = "[externalized: {artifact_id}, size={size}, summary={summary}]"


def _scrub_create_artifact_content(
    messages: list[Any],
    tool_call_id: str,
    placeholder: str,
) -> None:
    """Walk the messages list and replace create_artifact's raw content arg
    with a lightweight placeholder.

    This ensures the original long content is NOT retained in the active
    conversation context that is sent back to the LLM on subsequent turns.
    """
    for msg in messages:
        # LangChain AIMessage with tool_calls
        raw_calls = getattr(msg, "tool_calls", None)
        if raw_calls:
            for tc in raw_calls:
                tc_dict = tc if isinstance(tc, dict) else {}
                if not tc_dict:
                    continue
                if tc_dict.get("id") == tool_call_id and tc_dict.get("name") == "create_artifact":
                    args = tc_dict.get("args")
                    if isinstance(args, dict) and "content" in args:
                        args["content"] = placeholder

        # Also check additional_kwargs for OpenAI-style
        additional = getattr(msg, "additional_kwargs", None)
        if isinstance(additional, dict):
            for tc in additional.get("tool_calls", []):
                if tc.get("id") == tool_call_id:
                    func = tc.get("function", {})
                    if func.get("name") == "create_artifact":
                        try:
                            parsed = json.loads(func.get("arguments", "{}"))
                            if "content" in parsed:
                                parsed["content"] = placeholder
                                func["arguments"] = json.dumps(parsed, ensure_ascii=False)
                        except (json.JSONDecodeError, TypeError):
                            pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _extract_tool_calls(response: Any) -> list[dict[str, Any]]:
    raw_calls = getattr(response, "tool_calls", None)
    if raw_calls:
        return [_normalize_tool_call(c) for c in raw_calls]

    additional_kwargs = getattr(response, "additional_kwargs", {}) or {}
    raw_calls = additional_kwargs.get("tool_calls") or []
    return [_normalize_openai_tool_call(c) for c in raw_calls]


def _normalize_tool_call(call: Any) -> dict[str, Any]:
    if isinstance(call, dict):
        return {
            "id": call.get("id") or "call_unknown",
            "name": call.get("name"),
            "args": call.get("args") or {},
        }
    return {
        "id": getattr(call, "id", "call_unknown"),
        "name": getattr(call, "name", None),
        "args": getattr(call, "args", {}) or {},
    }


def _normalize_openai_tool_call(call: dict[str, Any]) -> dict[str, Any]:
    function = call.get("function", {})
    arguments = function.get("arguments") or "{}"
    if isinstance(arguments, str):
        args = json.loads(arguments)
    else:
        args = arguments
    return {
        "id": call.get("id") or "call_unknown",
        "name": function.get("name"),
        "args": args,
    }


def _safe_content(content: Any) -> Any:
    if isinstance(content, str):
        return content
    try:
        return json.loads(json.dumps(content))
    except Exception:
        return str(content)


def _execution_report_payload_from_frame(frame: Any | None) -> dict[str, Any]:
    if frame is None:
        return {}
    state = getattr(frame, "private_working_state", {}) or {}
    if not isinstance(state, dict):
        return {}
    payload: dict[str, Any] = {}
    report_ref = state.get("execution_report_ref")
    if isinstance(report_ref, str) and report_ref:
        payload["execution_report_ref"] = report_ref
    report_digest = state.get("execution_report_digest")
    if isinstance(report_digest, dict) and report_digest:
        payload["execution_report_digest"] = _safe_content(report_digest)
    return payload


def _child_approval_report_payload(child_frame: Any | None) -> dict[str, Any]:
    payload = _execution_report_payload_from_frame(child_frame)
    report_ref = payload.get("execution_report_ref")
    if report_ref:
        payload["child_execution_report_ref"] = report_ref
    report_digest = payload.get("execution_report_digest")
    if report_digest:
        payload["child_execution_report_digest"] = report_digest
    return payload


def _execution_report_payload_from_result(result: Any) -> dict[str, Any]:
    if not isinstance(result, dict):
        return {}
    payload: dict[str, Any] = {}
    report_ref = (
        result.get("execution_report_ref")
        or result.get("executionReportRef")
        or result.get("report_ref")
        or result.get("reportRef")
    )
    if isinstance(report_ref, str) and report_ref:
        payload["execution_report_ref"] = report_ref
    report_digest = result.get("execution_report_digest") or result.get("executionReportDigest")
    if isinstance(report_digest, dict) and report_digest:
        payload["execution_report_digest"] = _safe_content(report_digest)
    nested_result = result.get("result")
    if isinstance(nested_result, dict):
        nested_payload = _execution_report_payload_from_result(nested_result)
        payload.setdefault("execution_report_ref", nested_payload.get("execution_report_ref"))
        payload.setdefault("execution_report_digest", nested_payload.get("execution_report_digest"))
    return {key: value for key, value in payload.items() if value is not None}


def _safe_tool_call_args(args: dict[str, Any]) -> dict[str, Any]:
    safe_args = dict(args)
    for key in list(safe_args):
        lowered = key.lower()
        if "token" in lowered or "secret" in lowered or "password" in lowered:
            safe_args[key] = "<redacted>"
    return _safe_content(safe_args)
