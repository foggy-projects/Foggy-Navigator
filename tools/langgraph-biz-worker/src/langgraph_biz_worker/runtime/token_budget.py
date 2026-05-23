"""Lightweight token budget estimation for runtime context governance.

This module intentionally avoids provider-specific tokenizer dependencies.
It gives BizWorker a stable token-aware budget interface today, while keeping
the estimator replaceable when exact model tokenizers are introduced.
"""

from __future__ import annotations

import json
from typing import Any

ESTIMATOR_NAME = "heuristic-v1"


def estimate_text_tokens(value: Any) -> int:
    """Estimate token count for a text-like value.

    The heuristic is deliberately conservative for mixed Chinese/English
    business prompts: CJK characters count near 1 token, ASCII runs average
    around 4 chars/token, and other non-ASCII chars sit between them.
    """
    if value is None:
        return 0
    text = value if isinstance(value, str) else str(value)
    if not text:
        return 0

    ascii_count = 0
    cjk_count = 0
    other_count = 0
    for char in text:
        code = ord(char)
        if code <= 0x7F:
            ascii_count += 1
        elif _is_cjk(code):
            cjk_count += 1
        else:
            other_count += 1
    return max(
        1,
        (ascii_count + 3) // 4
        + cjk_count
        + (other_count + 1) // 2,
    )


def estimate_message_tokens(message: dict[str, Any]) -> int:
    """Estimate tokens for one provider-visible runtime message."""
    if not isinstance(message, dict):
        return 0
    total = 4
    total += estimate_text_tokens(message.get("role"))
    total += estimate_text_tokens(message.get("content"))
    tool_call_id = message.get("toolCallId")
    if isinstance(tool_call_id, str) and tool_call_id:
        total += estimate_text_tokens(tool_call_id)
    tool_calls = message.get("toolCalls")
    if isinstance(tool_calls, list) and tool_calls:
        try:
            total += estimate_text_tokens(
                json.dumps(tool_calls, ensure_ascii=False, separators=(",", ":"), default=str)
            )
        except Exception:
            total += estimate_text_tokens(str(tool_calls))
    return total


def estimate_messages_tokens(messages: list[dict[str, Any]]) -> int:
    """Estimate tokens for a provider-visible message list."""
    return sum(estimate_message_tokens(item) for item in messages if isinstance(item, dict))


def _is_cjk(code: int) -> bool:
    return (
        0x4E00 <= code <= 0x9FFF
        or 0x3400 <= code <= 0x4DBF
        or 0x20000 <= code <= 0x2A6DF
        or 0x2A700 <= code <= 0x2B73F
        or 0x2B740 <= code <= 0x2B81F
        or 0x2B820 <= code <= 0x2CEAF
        or 0xF900 <= code <= 0xFAFF
    )
