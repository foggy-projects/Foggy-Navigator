"""Map Claude Code SDK message objects to flat SSE-friendly dicts.

Each helper returns a plain ``dict`` that corresponds to the fields of
:class:`~agent_worker.models.QueryEvent`.
"""

from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger(__name__)


def map_assistant_text(task_id: str, text: str, session_id: str | None = None) -> dict[str, Any]:
    """Map a ``TextBlock`` from an ``AssistantMessage`` to an SSE dict."""

    return {
        "type": "assistant_text",
        "content": text,
        "task_id": task_id,
        "session_id": session_id,
    }


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
) -> dict[str, Any]:
    """Map a ``ResultMessage`` to an SSE dict."""

    return {
        "type": "result",
        "content": result_text,
        "cost_usd": cost_usd,
        "duration_ms": duration_ms,
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
