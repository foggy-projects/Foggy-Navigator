"""LLM-backed summarizer for compacting BizWorker runtime memory."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Callable

from langchain_core.messages import HumanMessage, SystemMessage

from ..config import settings
from .llm_call_guard import invoke_chat_model

logger = logging.getLogger(__name__)

SUMMARY_JSON_SCHEMA = {
    "durableUserIntent": "用户长期目标、当前业务诉求和仍需保持的背景。",
    "decisionsAndConstraints": ["已经确认的约束、口径、偏好或限制。"],
    "businessEntities": ["重要业务对象、编号、租户、工单、订单、附件等。"],
    "completedWork": ["已经完成的业务动作和可复用结果。"],
    "openQuestions": ["仍待用户或系统确认的问题。"],
    "pendingActions": ["后续应继续执行的动作。"],
    "errorsAndRecovery": ["错误、恢复状态和避免重复踩坑的信息。"],
    "summaryQuality": "llm",
}

_COMPACTION_SYSTEM_PROMPT = """你是 BizWorker 运行时上下文压缩器。

任务：
- 将较早的对话和工具协议消息压缩为下一轮 LLM 可继续使用的运行时摘要。
- 保留用户意图、关键业务实体、已完成动作、未决问题、待办动作、错误与恢复信息。
- 不要输出完整历史，不要复述无意义寒暄，不要编造不存在的事实。
- 如果 tool 消息包含报告引用、业务编号、错误码或关键结果，需要保留。
- 只输出一个 JSON object，不要使用 Markdown 代码块。

输出 JSON 字段固定为：
{
  "durableUserIntent": "string",
  "decisionsAndConstraints": ["string"],
  "businessEntities": ["string"],
  "completedWork": ["string"],
  "openQuestions": ["string"],
  "pendingActions": ["string"],
  "errorsAndRecovery": ["string"],
  "summaryQuality": "llm"
}
"""


def build_runtime_compaction_summarizer(
    *,
    model: Any,
    runtime_context: dict[str, Any] | None,
    task_id: str,
    frame_id: str,
    data_root: str | Path | None = None,
    session_id: str = "",
    date_parts: tuple[str, str, str] | None = None,
    require_standard_context: bool = False,
) -> Callable[[dict[str, Any]], dict[str, Any]] | None:
    """Return a callable summary adapter, or None when no chat model is available."""
    if model is None or not callable(getattr(model, "invoke", None)):
        return None

    base_context = dict(runtime_context or {})
    if data_root:
        base_context["_llm_submission_data_root"] = str(data_root)
    if session_id:
        base_context["_llm_submission_session_id"] = session_id
    if date_parts is not None:
        base_context["_llm_submission_date_parts"] = date_parts
    base_context["_llm_submission_require_standard_context"] = require_standard_context
    base_context["_llm_submission_skill_id"] = "runtime-memory.compaction"
    base_context.pop("_llm_submission_tools", None)
    base_context.pop("_llm_submission_tool_choice", None)

    def summarize(payload: dict[str, Any]) -> dict[str, Any]:
        call_context = _compaction_call_context(base_context)
        messages = [
            SystemMessage(content=_COMPACTION_SYSTEM_PROMPT),
            HumanMessage(content=_summary_input_content(payload)),
        ]
        response = invoke_chat_model(
            model,
            messages,
            runtime_context=call_context,
            operation="runtime_memory.compaction",
            task_id=task_id,
            frame_id=frame_id,
        )
        return _parse_summary_response(_message_text(response))

    return summarize


def _summary_input_content(payload: dict[str, Any]) -> str:
    normalized = {
        "previousSummary": payload.get("previousSummary"),
        "messages": payload.get("messages") if isinstance(payload.get("messages"), list) else [],
        "limits": payload.get("limits") if isinstance(payload.get("limits"), dict) else {},
        "outputSchema": SUMMARY_JSON_SCHEMA,
    }
    return json.dumps(normalized, ensure_ascii=False, default=str)


def _compaction_call_context(base_context: dict[str, Any]) -> dict[str, Any]:
    context = dict(base_context)
    configured_timeout = _positive_float(
        context.get("runtime_compaction_request_timeout_seconds"),
        settings.runtime_compaction_request_timeout_seconds,
    )
    if configured_timeout > 0:
        context["llm_request_timeout_seconds"] = configured_timeout
        context["llm_execution_deadline_seconds"] = max(
            configured_timeout,
            _positive_float(
                context.get("runtime_compaction_execution_deadline_seconds"),
                settings.runtime_compaction_execution_deadline_seconds,
            ),
        )
    return context


def _parse_summary_response(text: str) -> dict[str, Any]:
    payload_text = _strip_json_fence(text.strip())
    parsed = json.loads(payload_text)
    if not isinstance(parsed, dict):
        raise ValueError("runtime memory compaction summary must be a JSON object")
    parsed["summaryQuality"] = "llm"
    return parsed


def _strip_json_fence(text: str) -> str:
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    if len(lines) >= 3 and lines[0].startswith("```") and lines[-1].strip() == "```":
        return "\n".join(lines[1:-1]).strip()
    return text


def _message_text(response: Any) -> str:
    content = getattr(response, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text") or item.get("content")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(part.strip() for part in parts if part and part.strip())
    if content in (None, "", [], {}):
        return ""
    try:
        return json.dumps(content, ensure_ascii=False)
    except Exception:
        return str(content)


def _positive_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = float(default)
    return parsed if parsed > 0 else float(default)
