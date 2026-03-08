"""Unit tests for request_converter.py module."""

import json
import pytest
from src.conversion.request_converter import (
    convert_claude_to_openai,
    convert_claude_user_message,
    convert_claude_assistant_message,
    convert_claude_tool_results,
    parse_tool_result_content,
)
from src.models.claude import ClaudeMessagesRequest, ClaudeMessage, ClaudeTool
from src.core.constants import Constants


@pytest.mark.unit
class TestConvertClaudeToOpenai:
    """Test main Claude to OpenAI request conversion."""

    def test_basic_conversion(self, sample_claude_messages_request, sample_model_manager):
        """Test basic request conversion without special features."""
        result = convert_claude_to_openai(sample_claude_messages_request, sample_model_manager)

        assert result["model"] == "gpt-4o"
        assert len(result["messages"]) == 1
        assert result["messages"][0]["role"] == "user"
        assert result["messages"][0]["content"] == "What is the capital of France?"
        assert result["max_tokens"] == 1000
        assert result["temperature"] == 0.7
        assert result["top_p"] == 0.9
        assert result["stream"] is False

    def test_conversion_with_model_mapping(self, sample_claude_messages_request, sample_model_manager):
        """Test model name mapping (claude-3-5-sonnet -> gpt-4o)."""
        request = ClaudeMessagesRequest(
            model="claude-3-opus-20240229",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Hello")],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert result["model"] == "gpt-4o"  # big_model

    def test_conversion_with_system_string(self, sample_model_manager):
        """Test conversion with string system message."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            system="You are a helpful assistant.",
            messages=[ClaudeMessage(role="user", content="Hello")],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert len(result["messages"]) == 2
        assert result["messages"][0]["role"] == "system"
        assert result["messages"][0]["content"] == "You are a helpful assistant."
        assert result["messages"][1]["role"] == "user"

    def test_conversion_with_system_list(self, env_default, sample_model_manager):
        """Test conversion with list system message."""
        from src.models.claude import ClaudeSystemContent
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            system=[
                ClaudeSystemContent(type="text", text="You are helpful."),
                ClaudeSystemContent(type="text", text="Be concise."),
            ],
            messages=[ClaudeMessage(role="user", content="Hello")],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert result["messages"][0]["role"] == "system"
        assert "You are helpful." in result["messages"][0]["content"]
        assert "Be concise." in result["messages"][0]["content"]

    def test_conversion_with_stop_sequences(self, sample_claude_messages_request, sample_model_manager):
        """Test conversion with stop sequences."""
        sample_claude_messages_request.stop_sequences = ["\n\n", "---"]
        result = convert_claude_to_openai(sample_claude_messages_request, sample_model_manager)

        assert result["stop"] == ["\n\n", "---"]

    def test_conversion_with_tools(self, sample_claude_request_with_tools, sample_model_manager):
        """Test conversion with tool definitions."""
        result = convert_claude_to_openai(sample_claude_request_with_tools, sample_model_manager)

        assert "tools" in result
        assert len(result["tools"]) == 1
        assert result["tools"][0]["type"] == "function"
        assert result["tools"][0]["function"]["name"] == "get_weather"
        assert result["tools"][0]["function"]["description"] == "Get current weather for a location"
        assert "properties" in result["tools"][0]["function"]["parameters"]

    def test_conversion_tool_choice_auto(self, sample_claude_request_with_tools, sample_model_manager):
        """Test tool_choice='auto' conversion."""
        result = convert_claude_to_openai(sample_claude_request_with_tools, sample_model_manager)

        assert result["tool_choice"] == "auto"

    def test_conversion_tool_choice_any(self, sample_model_manager):
        """Test tool_choice='any' conversion."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Use a tool")],
            tools=[
                ClaudeTool(
                    name="test_tool",
                    description="Test",
                    input_schema={"type": "object", "properties": {}},
                )
            ],
            tool_choice={"type": "any"},
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert result["tool_choice"] == "auto"  # 'any' maps to 'auto' in OpenAI

    def test_conversion_tool_choice_specific(self, sample_model_manager):
        """Test tool_choice with specific tool name."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Use weather tool")],
            tools=[
                ClaudeTool(
                    name="get_weather",
                    description="Get weather",
                    input_schema={"type": "object", "properties": {}},
                )
            ],
            tool_choice={"type": "tool", "name": "get_weather"},
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert result["tool_choice"]["type"] == "function"
        assert result["tool_choice"]["function"]["name"] == "get_weather"

    def test_conversion_max_tokens_clamping(self, monkeypatch, sample_model_manager):
        """Test that max_tokens is clamped to min/max limits."""
        # Monkeypatch the global config's max_tokens_limit
        from src.core.config import config
        original_max = config.max_tokens_limit
        monkeypatch.setattr(config, 'max_tokens_limit', 4096)

        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=100000,  # Above limit
            messages=[ClaudeMessage(role="user", content="Hello")],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        # max_tokens should be clamped to max_tokens_limit
        assert result["max_tokens"] == 4096

        # Restore original value (monkeypatch handles this)

    def test_conversion_with_tool_results(self, sample_claude_request_with_tool_use, sample_model_manager):
        """Test conversion with tool use and tool results."""
        result = convert_claude_to_openai(sample_claude_request_with_tool_use, sample_model_manager)

        assert len(result["messages"]) == 3
        # User message
        assert result["messages"][0]["role"] == "user"
        # Assistant message with tool calls
        assert result["messages"][1]["role"] == "assistant"
        assert "tool_calls" in result["messages"][1]
        # Tool result message
        assert result["messages"][2]["role"] == "tool"

    def test_conversion_with_multimodal_image(self, sample_claude_request_multimodal, sample_model_manager):
        """Test conversion with image content."""
        result = convert_claude_to_openai(sample_claude_request_multimodal, sample_model_manager)

        assert len(result["messages"]) == 1
        content = result["messages"][0]["content"]
        assert isinstance(content, list)
        assert len(content) == 2
        assert content[0]["type"] == "text"
        assert content[1]["type"] == "image_url"
        assert content[1]["image_url"]["url"].startswith("data:image/png;base64,")

    def test_conversion_empty_tool_list(self, sample_model_manager):
        """Test conversion with empty tool list."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Hello")],
            tools=[],  # Empty list
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert "tools" not in result

    def test_conversion_tool_without_name(self, sample_model_manager):
        """Test that tools without name are skipped."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Hello")],
            tools=[
                ClaudeTool(name="", description="Invalid", input_schema={"type": "object", "properties": {}}),
                ClaudeTool(name="   ", description="Whitespace", input_schema={"type": "object", "properties": {}}),
            ],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        assert "tools" not in result


@pytest.mark.unit
class TestConvertClaudeUserMessage:
    """Test Claude user message conversion."""

    def test_simple_text_message(self):
        """Test simple text user message."""
        msg = ClaudeMessage(role="user", content="Hello, world!")
        result = convert_claude_user_message(msg)

        assert result["role"] == "user"
        assert result["content"] == "Hello, world!"

    def test_empty_content(self):
        """Test user message with None content."""
        msg = ClaudeMessage(role="user", content=None)
        result = convert_claude_user_message(msg)

        assert result["role"] == "user"
        assert result["content"] == ""

    def test_multimodal_content(self):
        """Test user message with multiple content blocks."""
        from src.models.claude import ClaudeContentBlockText, ClaudeContentBlockImage
        msg = ClaudeMessage(
            role="user",
            content=[
                ClaudeContentBlockText(type="text", text="What's in this image?"),
                ClaudeContentBlockImage(
                    type="image",
                    source={
                        "type": "base64",
                        "media_type": "image/jpeg",
                        "data": "base64datahere",
                    },
                ),
            ],
        )
        result = convert_claude_user_message(msg)

        assert isinstance(result["content"], list)
        assert len(result["content"]) == 2
        assert result["content"][0]["type"] == "text"
        assert result["content"][1]["type"] == "image_url"

    def test_single_text_block_returns_string(self):
        """Test that a single text block returns a string, not a list."""
        from src.models.claude import ClaudeContentBlockText
        msg = ClaudeMessage(
            role="user",
            content=[ClaudeContentBlockText(type="text", text="Single block")],
        )
        result = convert_claude_user_message(msg)

        assert isinstance(result["content"], str)
        assert result["content"] == "Single block"


@pytest.mark.unit
class TestConvertClaudeAssistantMessage:
    """Test Claude assistant message conversion."""

    def test_simple_text_response(self):
        """Test simple text assistant message."""
        msg = ClaudeMessage(role="assistant", content="Here's the answer.")
        result = convert_claude_assistant_message(msg)

        assert result["role"] == "assistant"
        assert result["content"] == "Here's the answer."
        assert "tool_calls" not in result

    def test_assistant_with_none_content(self):
        """Test assistant message with None content."""
        msg = ClaudeMessage(role="assistant", content=None)
        result = convert_claude_assistant_message(msg)

        assert result["role"] == "assistant"
        assert result["content"] is None

    def test_assistant_with_tool_calls(self):
        """Test assistant message with tool calls."""
        from src.models.claude import ClaudeContentBlockText, ClaudeContentBlockToolUse
        msg = ClaudeMessage(
            role="assistant",
            content=[
                ClaudeContentBlockText(type="text", text="Let me check the weather."),
                ClaudeContentBlockToolUse(
                    type="tool_use",
                    id="toolu_01",
                    name="get_weather",
                    input={"location": "Paris"},
                ),
            ],
        )
        result = convert_claude_assistant_message(msg)

        assert result["role"] == "assistant"
        assert result["content"] == "Let me check the weather."
        assert "tool_calls" in result
        assert len(result["tool_calls"]) == 1
        assert result["tool_calls"][0]["id"] == "toolu_01"
        assert result["tool_calls"][0]["type"] == "function"
        assert result["tool_calls"][0]["function"]["name"] == "get_weather"
        assert json.loads(result["tool_calls"][0]["function"]["arguments"]) == {"location": "Paris"}

    def test_assistant_multiple_text_blocks(self):
        """Test assistant message with multiple text blocks (concatenated)."""
        from src.models.claude import ClaudeContentBlockText
        msg = ClaudeMessage(
            role="assistant",
            content=[
                ClaudeContentBlockText(type="text", text="Part 1. "),
                ClaudeContentBlockText(type="text", text="Part 2."),
            ],
        )
        result = convert_claude_assistant_message(msg)

        assert result["content"] == "Part 1. Part 2."

    def test_assistant_only_tool_calls(self):
        """Test assistant message with only tool calls (no text)."""
        from src.models.claude import ClaudeContentBlockToolUse
        msg = ClaudeMessage(
            role="assistant",
            content=[
                ClaudeContentBlockToolUse(
                    type="tool_use",
                    id="toolu_01",
                    name="calculate",
                    input={"expression": "2+2"},
                ),
            ],
        )
        result = convert_claude_assistant_message(msg)

        assert result["content"] is None
        assert len(result["tool_calls"]) == 1


@pytest.mark.unit
class TestConvertClaudeToolResults:
    """Test Claude tool result conversion."""

    def test_single_tool_result_string(self):
        """Test single tool result with string content."""
        from src.models.claude import ClaudeContentBlockToolResult
        msg = ClaudeMessage(
            role="user",
            content=[
                ClaudeContentBlockToolResult(
                    type="tool_result",
                    tool_use_id="toolu_01",
                    content="The result is 42",
                )
            ],
        )
        result = convert_claude_tool_results(msg)

        assert len(result) == 1
        assert result[0]["role"] == "tool"
        assert result[0]["tool_call_id"] == "toolu_01"
        assert result[0]["content"] == "The result is 42"

    def test_multiple_tool_results(self):
        """Test multiple tool results in one message."""
        from src.models.claude import ClaudeContentBlockToolResult
        msg = ClaudeMessage(
            role="user",
            content=[
                ClaudeContentBlockToolResult(
                    type="tool_result",
                    tool_use_id="toolu_01",
                    content="Result 1",
                ),
                ClaudeContentBlockToolResult(
                    type="tool_result",
                    tool_use_id="toolu_02",
                    content="Result 2",
                ),
            ],
        )
        result = convert_claude_tool_results(msg)

        assert len(result) == 2
        assert result[0]["tool_call_id"] == "toolu_01"
        assert result[1]["tool_call_id"] == "toolu_02"

    def test_tool_result_with_list_content(self):
        """Test tool result with list content (as dict representation)."""
        from src.models.claude import ClaudeContentBlockToolResult
        msg = ClaudeMessage(
            role="user",
            content=[
                ClaudeContentBlockToolResult(
                    type="tool_result",
                    tool_use_id="toolu_01",
                    content={"lines": ["First line", "Second line"]},
                )
            ],
        )
        result = convert_claude_tool_results(msg)

        assert len(result) == 1
        assert "First line" in result[0]["content"]
        assert "Second line" in result[0]["content"]


@pytest.mark.unit
class TestParseToolResultContent:
    """Test tool result content parsing."""

    def test_string_content(self):
        """Test parsing string content."""
        result = parse_tool_result_content("Simple string")
        assert result == "Simple string"

    def test_none_content(self):
        """Test parsing None content."""
        result = parse_tool_result_content(None)
        assert result == "No content provided"

    def test_list_of_text_blocks(self):
        """Test parsing list of dict text blocks."""
        content = [
            {"type": "text", "text": "Line 1"},
            {"type": "text", "text": "Line 2"},
            "Plain string",
        ]
        result = parse_tool_result_content(content)

        assert "Line 1" in result
        assert "Line 2" in result
        assert "Plain string" in result

    def test_dict_content(self):
        """Test parsing dict content."""
        content = {"result": "success", "value": 42}
        result = parse_tool_result_content(content)

        assert "success" in result
        assert "42" in result

    def test_dict_with_text_type(self):
        """Test parsing dict with text type."""
        content = {"type": "text", "text": "Extracted text"}
        result = parse_tool_result_content(content)

        assert result == "Extracted text"

    def test_mixed_list_content(self):
        """Test parsing mixed list content."""
        content = [
            {"type": "text", "text": "Text block"},
            "String item",
            {"other": "dict"},
        ]
        result = parse_tool_result_content(content)

        assert "Text block" in result
        assert "String item" in result

    def test_unparseable_content(self):
        """Test parsing unparseable content (should return string representation)."""
        class CustomObject:
            def __str__(self):
                return "CustomObject()"

        result = parse_tool_result_content(CustomObject())
        assert result == "CustomObject()"

    def test_json_serialization_failure(self):
        """Test handling of JSON serialization failure."""
        # Create an object that can't be JSON serialized
        class NotSerializable:
            pass

        result = parse_tool_result_content({"obj": NotSerializable()})
        # Should return string representation
        assert "NotSerializable" in result or "obj" in result

    def test_dict_content_with_text_type(self):
        """Test dict content with type='text' returns text value (lines 253-255)."""
        content = {"type": "text", "text": "Direct text from dict"}
        result = parse_tool_result_content(content)
        assert result == "Direct text from dict"

    def test_dict_content_json_serializable(self):
        """Test dict content that is JSON-serializable (lines 256-258)."""
        content = {"key": "value", "count": 42}
        result = parse_tool_result_content(content)
        parsed = json.loads(result)
        assert parsed == {"key": "value", "count": 42}

    def test_list_with_dict_having_text_key(self):
        """Test list item dict with 'text' key but no 'type' field (lines 244-245)."""
        content = [{"text": "fallback text", "extra": "data"}]
        result = parse_tool_result_content(content)
        assert result == "fallback text"

    def test_list_with_non_serializable_dict(self):
        """Test list with dict that fails JSON serialization (lines 247-250)."""
        class BadValue:
            def __str__(self):
                return "bad_value_str"

        content = [{"key": BadValue()}]
        result = parse_tool_result_content(content)
        # Should fall back to str() representation
        assert "bad_value_str" in result or "BadValue" in result


# ============================================================================
# P7: Additional request converter edge cases
# ============================================================================


@pytest.mark.unit
class TestRequestConverterEdgeCases:
    """Test remaining edge cases in request_converter.py."""

    def test_system_as_dict_list(self, sample_model_manager):
        """System as list of dicts (lines 33-37)."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            system=[
                {"type": "text", "text": "You are a helpful assistant."},
                {"type": "text", "text": "Be concise."},
            ],
            messages=[ClaudeMessage(role="user", content="Hello")],
        )
        result = convert_claude_to_openai(request, sample_model_manager)

        # System message should be combined
        system_msgs = [m for m in result["messages"] if m["role"] == "system"]
        assert len(system_msgs) == 1
        assert "helpful assistant" in system_msgs[0]["content"]
        assert "concise" in system_msgs[0]["content"]

    def test_tool_choice_unknown_type(self, sample_model_manager):
        """Unknown tool_choice type falls back to 'auto' (line 127)."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Hello")],
            tools=[
                ClaudeTool(
                    name="test",
                    description="test tool",
                    input_schema={"type": "object", "properties": {}},
                )
            ],
            tool_choice={"type": "unknown_type"},
        )
        result = convert_claude_to_openai(request, sample_model_manager)
        assert result["tool_choice"] == "auto"

    def test_tool_choice_type_any(self, sample_model_manager):
        """tool_choice type 'any' maps to 'auto' (line 120)."""
        request = ClaudeMessagesRequest(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1000,
            messages=[ClaudeMessage(role="user", content="Hello")],
            tools=[
                ClaudeTool(
                    name="test",
                    description="test tool",
                    input_schema={"type": "object", "properties": {}},
                )
            ],
            tool_choice={"type": "any"},
        )
        result = convert_claude_to_openai(request, sample_model_manager)
        assert result["tool_choice"] == "auto"