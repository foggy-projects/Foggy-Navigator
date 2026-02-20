"""Map Claude Code SDK message objects to flat SSE-friendly dicts.

Each helper returns a plain ``dict`` that corresponds to the fields of
:class:`~agent_worker.models.QueryEvent`.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)


def map_assistant_text(task_id: str, text: str, session_id: str | None = None,
                       model: str | None = None) -> dict[str, Any]:
    """Map a ``TextBlock`` from an ``AssistantMessage`` to an SSE dict."""

    d: dict[str, Any] = {
        "type": "assistant_text",
        "content": text,
        "task_id": task_id,
        "session_id": session_id,
    }
    if model:
        d["model"] = model
    return d


def map_tool_use(
    task_id: str,
    tool_name: str,
    tool_input: dict[str, Any],
    session_id: str | None = None,
) -> dict[str, Any]:
    """Map a ``ToolUseBlock`` to an SSE dict."""

    return {
        "type": "tool_use",
        "tool": tool_name,
        "input": tool_input,
        "task_id": task_id,
        "session_id": session_id,
    }


def map_tool_result(
    task_id: str,
    tool_use_id: str,
    content: str | None,
    is_error: bool = False,
    session_id: str | None = None,
) -> dict[str, Any]:
    """Map a ``ToolResultBlock`` to an SSE dict."""

    return {
        "type": "tool_result",
        "tool": tool_use_id,
        "output": content,
        "error": content if is_error else None,
        "task_id": task_id,
        "session_id": session_id,
    }


def map_result(
    task_id: str,
    result_text: str | None,
    cost_usd: float | None,
    duration_ms: int | None,
    session_id: str | None = None,
    input_tokens: int | None = None,
    output_tokens: int | None = None,
    num_turns: int | None = None,
    model: str | None = None,
) -> dict[str, Any]:
    """Map a ``ResultMessage`` to an SSE dict."""

    return {
        "type": "result",
        "content": result_text,
        "cost_usd": cost_usd,
        "duration_ms": duration_ms,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "num_turns": num_turns,
        "model": model,
        "task_id": task_id,
        "session_id": session_id,
    }


def map_system(
    task_id: str,
    subtype: str,
    data: dict[str, Any] | None = None,
    session_id: str | None = None,
) -> dict[str, Any]:
    """Map a ``SystemMessage`` to an SSE dict."""

    return {
        "type": "system",
        "subtype": subtype,
        "data": data,
        "task_id": task_id,
        "session_id": session_id,
    }


def map_permission_request(
    task_id: str,
    permission_id: str,
    tool_name: str,
    tool_input: dict[str, Any] | None = None,
    session_id: str | None = None,
    has_suggestions: bool = False,
) -> dict[str, Any]:
    """Map a permission request (can_use_tool callback) to an SSE dict."""

    return {
        "type": "permission_request",
        "permission_id": permission_id,
        "tool": tool_name,
        "input": tool_input,
        "task_id": task_id,
        "session_id": session_id,
        "has_suggestions": has_suggestions,
    }


def map_plan_review(
    task_id: str,
    permission_id: str,
    allowed_prompts: list[dict[str, Any]] | None = None,
    session_id: str | None = None,
) -> dict[str, Any]:
    """Map an ExitPlanMode tool call to an SSE dict for plan review."""

    return {
        "type": "plan_review",
        "permission_id": permission_id,
        "allowed_prompts": allowed_prompts or [],
        "task_id": task_id,
        "session_id": session_id,
    }


def map_user_question(
    task_id: str,
    permission_id: str,
    questions: list[dict[str, Any]],
    session_id: str | None = None,
) -> dict[str, Any]:
    """Map an AskUserQuestion tool call to an SSE dict."""

    return {
        "type": "user_question",
        "permission_id": permission_id,
        "questions": questions,
        "task_id": task_id,
        "session_id": session_id,
    }


def map_error(task_id: str, error: str, session_id: str | None = None) -> dict[str, Any]:
    """Map an error condition to an SSE dict."""

    return {
        "type": "error",
        "error": error,
        "task_id": task_id,
        "session_id": session_id,
    }
