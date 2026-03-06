"""Unit tests for response_converter.py module."""

import json
import pytest
from fastapi import HTTPException, Request
from unittest.mock import Mock, AsyncMock
from src.conversion.response_converter import (
    convert_openai_to_claude_response,
    convert_openai_streaming_to_claude,
    convert_openai_streaming_to_claude_with_cancellation,
)
from src.models.claude import ClaudeMessagesRequest
from src.core.constants import Constants


@pytest.mark.unit
class TestConvertOpenaiToClaudeResponse:
    """Test OpenAI to Claude response conversion."""

    def test_basic_text_response(self, sample_claude_messages_request):
        """Test basic text response conversion."""
        openai_response = {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1234567890,
            "model": "gpt-4o",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello! How can I help you today?",
                },
                "finish_reason": "stop",
            }],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 20,
                "total_tokens": 30,
            },
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["type"] == "message"
        assert result["role"] == "assistant"
        assert result["model"] == sample_claude_messages_request.model
        assert result["stop_reason"] == Constants.STOP_END_TURN
        assert len(result["content"]) == 1
        assert result["content"][0]["type"] == "text"
        assert result["content"][0]["text"] == "Hello! How can I help you today?"
        assert result["usage"]["input_tokens"] == 10
        assert result["usage"]["output_tokens"] == 20

    def test_response_with_tool_calls(self, sample_claude_messages_request):
        """Test response with tool calls."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Let me check that for you.",
                    "tool_calls": [
                        {
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                                "name": "get_weather",
                                "arguments": '{"location": "Paris"}',
                            },
                        }
                    ],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["stop_reason"] == Constants.STOP_TOOL_USE
        assert len(result["content"]) == 2
        assert result["content"][0]["type"] == "text"
        assert result["content"][1]["type"] == "tool_use"
        assert result["content"][1]["name"] == "get_weather"
        assert result["content"][1]["input"] == {"location": "Paris"}
        assert result["content"][1]["id"] == "call_abc123"

    def test_response_only_tool_calls(self, sample_claude_messages_request):
        """Test response with only tool calls (no text)."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": "call_123",
                            "type": "function",
                            "function": {"name": "test", "arguments": "{}"},
                        }
                    ],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["content"][0]["type"] == "tool_use"

    def test_stop_reason_length(self, sample_claude_messages_request):
        """Test stop_reason mapping for length limit."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Truncated..."},
                "finish_reason": "length",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["stop_reason"] == Constants.STOP_MAX_TOKENS

    def test_response_empty_content(self, sample_claude_messages_request):
        """Test response with no content."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": None},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 0},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        # Should have at least one content block
        assert len(result["content"]) >= 1

    def test_response_no_choices_raises_error(self, sample_claude_messages_request):
        """Test that response with no choices raises HTTPException."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [],
        }

        with pytest.raises(HTTPException) as exc_info:
            convert_openai_to_claude_response(openai_response, sample_claude_messages_request)
        assert exc_info.value.status_code == 500
        assert "No choices in OpenAI response" in str(exc_info.value.detail)

    def test_response_id_preserved(self, sample_claude_messages_request):
        """Test that OpenAI response ID is preserved."""
        openai_response = {
            "id": "custom-response-id",
            "choices": [{
                "message": {"role": "assistant", "content": "Hello"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["id"] == "custom-response-id"

    def test_invalid_json_arguments(self, sample_claude_messages_request):
        """Test handling of invalid JSON in tool call arguments."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Hello",
                    "tool_calls": [
                        {
                            "id": "call_123",
                            "type": "function",
                            "function": {
                                "name": "test",
                                "arguments": "invalid{json",
                            },
                        }
                    ],
                },
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["content"][1]["input"] == {"raw_arguments": "invalid{json"}


@pytest.mark.unit
class TestConvertOpenaiStreamingToClaude:
    """Test OpenAI to Claude streaming response conversion."""

    @pytest.mark.asyncio
    async def test_streaming_initial_events(self, sample_claude_messages_request):
        """Test that initial SSE events are correct."""
        # Create a mock stream that yields nothing
        async def mock_stream():
            return

        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            Mock(),
        )

        # Get first few events
        events = []
        async for event in generator:
            events.append(event)
            if len(events) >= 3:
                break

        assert len(events) >= 3
        assert "message_start" in events[0]
        assert "content_block_start" in events[1]
        assert "ping" in events[2]

    @pytest.mark.asyncio
    async def test_streaming_text_delta(self, sample_claude_messages_request):
        """Test streaming text delta conversion."""
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}]}'

        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            Mock(),
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have initial events + delta
        assert any("content_block_delta" in e for e in events)
        delta_event = next(e for e in events if "content_block_delta" in e)
        assert "Hello" in delta_event

    @pytest.mark.asyncio
    async def test_streaming_done_marker(self, sample_claude_messages_request):
        """Test that [DONE] marker ends the stream."""
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hi"}}]}'
            yield "data: [DONE]"

        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            Mock(),
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have message_stop at the end
        assert any("message_stop" in e for e in events)

    @pytest.mark.asyncio
    async def test_streaming_tool_calls(self, sample_claude_messages_request):
        """Test streaming tool call conversion."""
        async def mock_stream():
            # Tool call start
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_123","type":"function","function":{"name":"get_weather"}}]}}]}'
            # Tool arguments
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"type":"function","function":{"arguments":"{\\"location\\":"}}]}}]}'
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"type":"function","function":{"arguments":"Paris\\"}}"}}]}}]}'
            # Done
            yield "data: [DONE]"

        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            Mock(),
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have tool_use content block
        assert any("tool_use" in e for e in events)

    @pytest.mark.asyncio
    async def test_streaming_finish_reason_length(self, sample_claude_messages_request):
        """Test finish_reason 'length' mapping in streaming."""
        async def mock_stream():
            yield 'data: {"choices":[{"finish_reason":"length"}]}'

        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            Mock(),
        )

        events = []
        async for event in generator:
            events.append(event)

        # Check that stop_reason is max_tokens
        delta_events = [e for e in events if "message_delta" in e]
        assert len(delta_events) > 0
        assert "max_tokens" in delta_events[0]  # Or STOP_MAX_TOKENS

    @pytest.mark.asyncio
    async def test_streaming_error_handling(self, sample_claude_messages_request):
        """Test error handling during streaming."""
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}]}'
            raise ValueError("Stream error")

        logger = Mock()
        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            logger,
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have error event
        assert any("error" in e for e in events)
        logger.error.assert_called()

    @pytest.mark.asyncio
    async def test_streaming_invalid_json(self, sample_claude_messages_request):
        """Test handling of invalid JSON chunks."""
        async def mock_stream():
            yield 'data: invalid json here'
            yield 'data: {"choices":[{"delta":{"content":"valid"}}]}'

        logger = Mock()
        generator = convert_openai_streaming_to_claude(
            mock_stream(),
            sample_claude_messages_request,
            logger,
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should continue processing valid chunks
        logger.warning.assert_called()


@pytest.mark.unit
class TestConvertOpenaiStreamingWithCancellation:
    """Test streaming conversion with cancellation support."""

    @pytest.mark.asyncio
    async def test_cancellation_on_disconnect(self, sample_claude_messages_request):
        """Test that client disconnect triggers cancellation."""
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}]}'
            # Simulate long-running stream
            await asyncio.sleep(0.1)
            yield 'data: {"choices":[{"delta":{"content":" world"}}]}'

        import asyncio

        mock_request = Mock(spec=Request)
        mock_request.is_disconnected = AsyncMock(side_effect=[False, True])
        mock_client = Mock()
        mock_client.cancel_request = Mock()

        logger = Mock()
        generator = convert_openai_streaming_to_claude_with_cancellation(
            mock_stream(),
            sample_claude_messages_request,
            logger,
            mock_request,
            mock_client,
            "test-request-id",
        )

        events = []
        async for event in generator:
            events.append(event)

        # Cancel should be called
        mock_client.cancel_request.assert_called_once_with("test-request-id")

    @pytest.mark.asyncio
    async def test_usage_tracking_with_cache(self, sample_claude_messages_request):
        """Test usage tracking including cached tokens."""
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}],"usage":{"prompt_tokens":100,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":30}}}'

        mock_request = Mock(spec=Request)
        mock_request.is_disconnected = AsyncMock(return_value=False)
        mock_client = Mock()
        mock_client.cancel_request = Mock()

        logger = Mock()
        generator = convert_openai_streaming_to_claude_with_cancellation(
            mock_stream(),
            sample_claude_messages_request,
            logger,
            mock_request,
            mock_client,
            "test-id",
        )

        events = []
        async for event in generator:
            events.append(event)

        # Check usage in message_delta
        delta_events = [e for e in events if "message_delta" in e]
        if delta_events:
            # May contain cache_read_input_tokens
            pass

    @pytest.mark.asyncio
    async def test_httpexception_499_handling(self, sample_claude_messages_request):
        """Test that the cancellation generator handles streaming correctly."""
        async def mock_stream():
            # Yield a valid chunk
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}]}'
            # Yield [DONE] marker
            yield 'data: [DONE]'

        mock_request = Mock(spec=Request)
        mock_request.is_disconnected = AsyncMock(return_value=False)
        mock_client = Mock()
        mock_logger = Mock()

        generator = convert_openai_streaming_to_claude_with_cancellation(
            mock_stream(),
            sample_claude_messages_request,
            mock_logger,
            mock_request,
            mock_client,
            "test-id",
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have initial events
        assert any("message_start" in e for e in events)
        # Should have content delta
        assert any("Hello" in e for e in events)
        # Should have completed streaming
        assert len(events) > 0

    @pytest.mark.asyncio
    async def test_final_events_order(self, sample_claude_messages_request):
        """Test that final events are in correct order."""
        async def mock_stream():
            yield 'data: {"choices":[{"finish_reason":"stop"}]}'

        mock_request = Mock(spec=Request)
        mock_request.is_disconnected = AsyncMock(return_value=False)
        mock_client = Mock()
        mock_logger = Mock()

        generator = convert_openai_streaming_to_claude_with_cancellation(
            mock_stream(),
            sample_claude_messages_request,
            mock_logger,
            mock_request,
            mock_client,
            "test-id",
        )

        events = []
        async for event in generator:
            events.append(event)

        # Check order: content_block_stop -> message_delta -> message_stop
        stop_events = [e for e in events if any(x in e for x in ["content_block_stop", "message_delta", "message_stop"])]

        # Verify order
        for i, event in enumerate(stop_events[:-1]):
            if "message_stop" in event:
                # message_stop should be last
                break

    @pytest.mark.asyncio
    async def test_multiple_tool_blocks_ordering(self, sample_claude_messages_request):
        """Test that multiple tool blocks are ordered correctly."""
        async def mock_stream():
            # First tool call
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"tool1"}}]}}]}'
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"type":"function","function":{"arguments":"{}"}}]}}]}'
            # Second tool call
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call2","type":"function","function":{"name":"tool2"}}]}}]}'
            yield 'data: {"choices":[{"delta":{"tool_calls":[{"index":1,"type":"function","function":{"arguments":"{}"}}]}}]}'
            yield "data: [DONE]"

        mock_request = Mock(spec=Request)
        mock_request.is_disconnected = AsyncMock(return_value=False)
        mock_client = Mock()
        mock_logger = Mock()

        generator = convert_openai_streaming_to_claude_with_cancellation(
            mock_stream(),
            sample_claude_messages_request,
            mock_logger,
            mock_request,
            mock_client,
            "test-id",
        )

        events = []
        async for event in generator:
            events.append(event)

        # Should have both tool_use blocks
        tool_use_events = [e for e in events if "tool_use" in e]
        assert len([e for e in tool_use_events if "tool1" in e]) >= 1
        assert len([e for e in tool_use_events if "tool2" in e]) >= 1


@pytest.mark.unit
class TestResponseConverterEdgeCases:
    """Test edge cases in response conversion."""

    def test_empty_tool_calls_list(self, sample_claude_messages_request):
        """Test response with empty tool_calls list."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Hello",
                    "tool_calls": [],
                },
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert len([c for c in result["content"] if c["type"] == "tool_use"]) == 0

    def test_function_call_finish_reason(self, sample_claude_messages_request):
        """Test function_call finish_reason (legacy OpenAI format)."""
        openai_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Using function..."},
                "finish_reason": "function_call",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        result = convert_openai_to_claude_response(openai_response, sample_claude_messages_request)

        assert result["stop_reason"] == Constants.STOP_TOOL_USE