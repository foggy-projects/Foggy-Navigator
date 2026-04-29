"""Tests for artifact context governance — long content scrubbing."""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock

from langgraph_biz_worker.runtime.llm_skill_agent import _scrub_create_artifact_content


def _make_ai_message_with_tool_call(
    tool_call_id: str,
    name: str,
    args: dict[str, Any],
) -> MagicMock:
    """Create a mock AIMessage with tool_calls attribute."""
    msg = MagicMock()
    msg.tool_calls = [
        {"id": tool_call_id, "name": name, "args": args},
    ]
    msg.additional_kwargs = {}
    return msg


class TestScrubCreateArtifactContent:
    def test_scrub_removes_content_from_tool_calls(self):
        long_content = "x" * 10000
        args = {"name": "big", "content": long_content, "scope": "task"}
        msg = _make_ai_message_with_tool_call("call_123", "create_artifact", args)

        messages = [msg]
        _scrub_create_artifact_content(
            messages,
            "call_123",
            "[externalized: art_abc, size=10000, summary=test]",
        )

        # Content should be replaced
        actual_args = msg.tool_calls[0]["args"]
        assert actual_args["content"] == "[externalized: art_abc, size=10000, summary=test]"
        assert "x" * 100 not in actual_args["content"]

    def test_scrub_preserves_other_tool_calls(self):
        args1 = {"name": "big", "content": "long data"}
        args2 = {"query": "search term"}
        msg1 = _make_ai_message_with_tool_call("call_1", "create_artifact", args1)
        msg2 = _make_ai_message_with_tool_call("call_2", "mock_search_incidents", args2)

        messages = [msg1, msg2]
        _scrub_create_artifact_content(messages, "call_1", "[placeholder]")

        # Only call_1's content should be scrubbed
        assert msg1.tool_calls[0]["args"]["content"] == "[placeholder]"
        assert msg2.tool_calls[0]["args"]["query"] == "search term"

    def test_scrub_openai_style(self):
        """Test scrubbing for OpenAI-style additional_kwargs format."""
        msg = MagicMock()
        msg.tool_calls = []
        msg.additional_kwargs = {
            "tool_calls": [{
                "id": "call_abc",
                "function": {
                    "name": "create_artifact",
                    "arguments": json.dumps({"name": "x", "content": "long text here"}),
                },
            }],
        }

        messages = [msg]
        _scrub_create_artifact_content(messages, "call_abc", "[scrubbed]")

        # Verify the arguments were scrubbed
        func = msg.additional_kwargs["tool_calls"][0]["function"]
        parsed = json.loads(func["arguments"])
        assert parsed["content"] == "[scrubbed]"

    def test_scrub_no_match_is_safe(self):
        """Scrubbing with a non-matching call_id should be a no-op."""
        args = {"name": "x", "content": "keep this"}
        msg = _make_ai_message_with_tool_call("call_999", "create_artifact", args)

        messages = [msg]
        _scrub_create_artifact_content(messages, "call_other", "[placeholder]")

        assert msg.tool_calls[0]["args"]["content"] == "keep this"
