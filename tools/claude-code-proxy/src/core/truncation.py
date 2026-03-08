"""Context truncation utilities for passthrough mode error recovery.

When a backend returns a 400 error due to input length exceeding its limit,
these utilities truncate the request body and allow a retry.

Strategy (layered):
  1. Truncate oversized tool_result content blocks (> TOOL_RESULT_CHAR_LIMIT chars)
  2. Sliding-window: keep first N + last M messages, remove the middle
"""

import copy
import logging

logger = logging.getLogger("proxy")

# ── Defaults (overridden via Config) ──────────────────────────────────────

TOOL_RESULT_CHAR_LIMIT = 30_000
KEEP_HEAD_MESSAGES = 4      # ~2 user+assistant turns
KEEP_TAIL_MESSAGES = 40     # ~20 recent turns

_TRUNCATION_SUFFIX = "\n\n... [truncated by proxy for context limit] ..."


def truncate_for_retry(
    body_json: dict,
    tool_result_limit: int = TOOL_RESULT_CHAR_LIMIT,
    keep_head: int = KEEP_HEAD_MESSAGES,
    keep_tail: int = KEEP_TAIL_MESSAGES,
) -> dict:
    """Apply layered truncation to a deep-copied request body.

    Returns the mutated ``body_json`` (same reference) for convenience.
    """
    t1 = _truncate_tool_results(body_json, tool_result_limit)
    t2 = _sliding_window_messages(body_json, keep_head, keep_tail)
    if t1 or t2:
        logger.info(
            "Truncation applied: tool_results_truncated=%d, messages_removed=%d",
            t1, t2,
        )
    return body_json


# ── Internal helpers ──────────────────────────────────────────────────────

def _truncate_tool_results(body_json: dict, limit: int) -> int:
    """Truncate oversized tool_result content blocks.

    Returns the number of blocks that were truncated.
    """
    truncated_count = 0
    for msg in body_json.get("messages", []):
        content = msg.get("content")
        if not isinstance(content, list):
            continue
        for block in content:
            if block.get("type") != "tool_result":
                continue
            c = block.get("content")
            if isinstance(c, str) and len(c) > limit:
                block["content"] = c[:limit] + _TRUNCATION_SUFFIX
                truncated_count += 1
            elif isinstance(c, list):
                for sub in c:
                    if isinstance(sub, dict) and sub.get("type") == "text":
                        t = sub.get("text", "")
                        if len(t) > limit:
                            sub["text"] = t[:limit] + _TRUNCATION_SUFFIX
                            truncated_count += 1
    return truncated_count


def _sliding_window_messages(body_json: dict, keep_head: int, keep_tail: int) -> int:
    """Keep head + tail messages, remove the middle.

    Returns the number of messages removed (0 if no pruning needed).
    """
    messages = body_json.get("messages", [])
    total = len(messages)
    # Only prune if there is a meaningful gap to remove
    if total <= keep_head + keep_tail + 2:
        return 0

    head = messages[:keep_head]
    tail = messages[-keep_tail:]
    removed = total - keep_head - keep_tail

    marker = {
        "role": "user",
        "content": f"[{removed} earlier messages truncated by proxy to fit context limit]",
    }
    body_json["messages"] = head + [marker] + tail
    return removed
