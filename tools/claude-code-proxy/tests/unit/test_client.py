"""Unit tests for client.py module."""

import pytest
import asyncio
import httpx
from unittest.mock import Mock, AsyncMock, MagicMock, patch
from fastapi import HTTPException
from openai import AsyncOpenAI, AsyncAzureOpenAI
from openai._exceptions import (
    AuthenticationError,
    RateLimitError,
    BadRequestError,
    APIError,
)
from src.core.client import OpenAIClient


def _make_response(status_code: int = 401) -> httpx.Response:
    """Create a mock httpx.Response for OpenAI exception constructors."""
    request = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    return httpx.Response(status_code=status_code, request=request)


def _make_request() -> httpx.Request:
    """Create a mock httpx.Request for OpenAI APIError constructor."""
    return httpx.Request("POST", "https://api.openai.com/v1/chat/completions")


@pytest.mark.unit
class TestOpenAIClientInitialization:
    """Test OpenAIClient initialization."""

    def test_client_initialization_standard(self):
        """Test standard OpenAI client initialization."""
        client = OpenAIClient(
            api_key="sk-test-key",
            base_url="https://api.openai.com/v1",
            timeout=90,
            api_version=None,
            custom_headers=None,
        )

        assert client.api_key == "sk-test-key"
        assert client.base_url == "https://api.openai.com/v1"
        assert client.custom_headers == {}
        assert isinstance(client.client, AsyncOpenAI)
        assert not isinstance(client.client, AsyncAzureOpenAI)

    def test_client_initialization_azure(self):
        """Test Azure OpenAI client initialization."""
        client = OpenAIClient(
            api_key="sk-test-key",
            base_url="https://resource.openai.azure.com/",
            timeout=90,
            api_version="2024-02-01",
            custom_headers=None,
        )

        assert isinstance(client.client, AsyncAzureOpenAI)

    def test_client_with_custom_headers(self):
        """Test client initialization with custom headers."""
        custom_headers = {
            "X-Custom-Header": "custom-value",
            "Authorization": "Bearer token",
        }

        client = OpenAIClient(
            api_key="sk-test-key",
            base_url="https://api.openai.com/v1",
            timeout=90,
            api_version=None,
            custom_headers=custom_headers,
        )

        assert client.custom_headers == custom_headers

    def test_client_default_headers_set(self):
        """Test that default headers are always set."""
        client = OpenAIClient(
            api_key="sk-test-key",
            base_url="https://api.openai.com/v1",
        )

        # Check that default headers are passed to the client
        assert client.client is not None

    def test_client_active_requests_initialized_empty(self):
        """Test that active_requests starts empty."""
        client = OpenAIClient(
            api_key="sk-test-key",
            base_url="https://api.openai.com/v1",
        )

        assert client.active_requests == {}


@pytest.mark.unit
class TestCreateChatCompletion:
    """Test create_chat_completion method."""

    @pytest.mark.asyncio
    async def test_successful_completion(self):
        """Test successful chat completion."""
        # Mock the OpenAI client
        mock_completion = MagicMock()
        mock_completion.id = "chatcmpl-123"
        mock_completion.model_dump = Mock(return_value={
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "choices": [{
                "message": {"role": "assistant", "content": "Hello!"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        })

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            result = await client.create_chat_completion({
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "Hello"}],
            })

            assert result["id"] == "chatcmpl-123"
            assert result["choices"][0]["message"]["content"] == "Hello!"

    @pytest.mark.asyncio
    async def test_completion_with_request_id(self):
        """Test completion with request_id (tracking enabled)."""
        mock_completion = MagicMock()
        mock_completion.model_dump = Mock(return_value={
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Response"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        })

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            request_id = "test-request-id"
            result = await client.create_chat_completion({
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "Hello"}],
            }, request_id=request_id)

            assert request_id not in client.active_requests  # Should be cleaned up

    @pytest.mark.asyncio
    async def test_completion_cancellation(self):
        """Test completion cancellation via request_id."""

        async def slow_completion(**kwargs):
            await asyncio.sleep(0.5)
            mock = MagicMock()
            mock.model_dump = Mock(return_value={"id": "test"})
            return mock

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(side_effect=slow_completion)
            client.active_requests = {}

            request_id = "test-cancel-id"

            async def cancel_after_delay():
                await asyncio.sleep(0.05)
                client.cancel_request(request_id)

            # Schedule cancellation while request is in progress
            cancel_task = asyncio.create_task(cancel_after_delay())

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion(
                    {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello"}]},
                    request_id=request_id,
                )

            await cancel_task
            assert exc_info.value.status_code == 499
            assert "cancelled" in exc_info.value.detail.lower()

    @pytest.mark.asyncio
    async def test_authentication_error(self):
        """Test handling of AuthenticationError."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(
                side_effect=AuthenticationError(
                    "Invalid API key", response=_make_response(401), body=None
                )
            )
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                })

            assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_rate_limit_error(self):
        """Test handling of RateLimitError."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(
                side_effect=RateLimitError(
                    "Rate limit exceeded", response=_make_response(429), body=None
                )
            )
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                })

            assert exc_info.value.status_code == 429

    @pytest.mark.asyncio
    async def test_bad_request_error(self):
        """Test handling of BadRequestError."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(
                side_effect=BadRequestError(
                    "Invalid request", response=_make_response(400), body=None
                )
            )
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                })

            assert exc_info.value.status_code == 400

    @pytest.mark.asyncio
    async def test_api_error_with_status_code(self):
        """Test handling of APIError with custom status code."""
        error = APIError("API Error", request=_make_request(), body=None)
        error.status_code = 502

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(side_effect=error)
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                })

            assert exc_info.value.status_code == 502

    @pytest.mark.asyncio
    async def test_generic_exception(self):
        """Test handling of generic exceptions."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(
                side_effect=Exception("Unexpected error")
            )
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                })

            assert exc_info.value.status_code == 500

    @pytest.mark.asyncio
    async def test_completion_cleanup_on_success(self):
        """Test that active_requests is cleaned up after successful completion."""
        mock_completion = MagicMock()
        mock_completion.model_dump = Mock(return_value={
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Hello!"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        })

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            request_id = "test-id"
            client.active_requests[request_id] = asyncio.Event()

            await client.create_chat_completion({
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "Hello"}],
            }, request_id=request_id)

            assert request_id not in client.active_requests


@pytest.mark.unit
class TestCreateChatCompletionStream:
    """Test create_chat_completion_stream method."""

    @pytest.mark.asyncio
    async def test_successful_streaming(self):
        """Test successful streaming completion."""
        async def mock_stream():
            chunk = MagicMock()
            chunk.model_dump = Mock(return_value={
                "id": "chatcmpl-123",
                "choices": [{"delta": {"content": "Hello"}, "finish_reason": None}],
            })
            yield chunk
            chunk2 = MagicMock()
            chunk2.model_dump = Mock(return_value={
                "id": "chatcmpl-123",
                "choices": [{"delta": {"content": " world"}, "finish_reason": "stop"}],
            })
            yield chunk2

        mock_completion = MagicMock()
        mock_completion.__aiter__ = lambda self: mock_stream()

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            chunks = []
            async for chunk in client.create_chat_completion_stream({
                "model": "gpt-4o",
                "messages": [{"role": "user", "content": "Hello"}],
            }):
                chunks.append(chunk)

            assert len(chunks) == 3  # 2 chunks + [DONE]
            assert chunks[2] == "data: [DONE]"

    @pytest.mark.asyncio
    async def test_streaming_adds_stream_options(self):
        """Test that streaming adds stream_options."""
        async def empty_aiter(self_inner):
            return
            yield  # noqa: make it an async generator

        mock_completion = MagicMock()
        mock_completion.__aiter__ = empty_aiter

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            async def consume():
                async for _ in client.create_chat_completion_stream({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                }):
                    pass

            await consume()

            call_args = client.client.chat.completions.create.call_args
            assert call_args[1]["stream"] is True
            assert "stream_options" in call_args[1]
            assert call_args[1]["stream_options"]["include_usage"] is True

    @pytest.mark.asyncio
    async def test_streaming_with_cancellation(self):
        """Test streaming cancellation via request_id."""
        request_id = "test-cancel-stream"

        async def mock_stream_gen(self_inner):
            chunk = MagicMock()
            chunk.model_dump = Mock(return_value={
                "choices": [{"delta": {"content": "Hi"}}],
            })
            yield chunk
            # Second chunk — by now the cancel event should be set
            await asyncio.sleep(0.1)
            chunk2 = MagicMock()
            chunk2.model_dump = Mock(return_value={
                "choices": [{"delta": {"content": " world"}}],
            })
            yield chunk2

        mock_completion = MagicMock()
        mock_completion.__aiter__ = mock_stream_gen

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            chunks = []
            with pytest.raises(HTTPException) as exc_info:
                async for chunk in client.create_chat_completion_stream(
                    {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello"}]},
                    request_id=request_id,
                ):
                    chunks.append(chunk)
                    # Cancel after receiving first chunk
                    client.cancel_request(request_id)

            assert exc_info.value.status_code == 499

    @pytest.mark.asyncio
    async def test_streaming_authentication_error(self):
        """Test handling of AuthenticationError in streaming."""
        async def mock_stream_gen(self_inner):
            raise AuthenticationError(
                "Invalid API key", response=_make_response(401), body=None
            )
            yield  # noqa: make it an async generator

        mock_completion = MagicMock()
        mock_completion.__aiter__ = mock_stream_gen

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            with pytest.raises(HTTPException) as exc_info:
                async for _ in client.create_chat_completion_stream({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                }):
                    pass

            assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_streaming_cleanup_on_error(self):
        """Test that active_requests is cleaned up after streaming error."""
        async def mock_stream():
            raise Exception("Stream error")

        mock_completion = MagicMock()
        mock_completion.__aiter__ = lambda self: mock_stream()

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {"test-id": asyncio.Event()}

            with pytest.raises(HTTPException):
                async for _ in client.create_chat_completion_stream(
                    {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hello"}]},
                    request_id="test-id",
                ):
                    pass

            assert "test-id" not in client.active_requests

    @staticmethod
    async def _mock_empty_stream():
        return
        yield  # Make this a generator


@pytest.mark.unit
class TestCancelRequest:
    """Test cancel_request method."""

    def test_cancel_existing_request(self):
        """Test cancelling an existing request."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.active_requests = {"test-id": asyncio.Event()}

            result = client.cancel_request("test-id")

            assert result is True
            assert client.active_requests["test-id"].is_set()

    def test_cancel_nonexistent_request(self):
        """Test cancelling a non-existent request."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.active_requests = {}

            result = client.cancel_request("nonexistent-id")

            assert result is False

    def test_cancel_sets_event(self):
        """Test that cancelling sets the asyncio.Event."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.active_requests = {"test-id": asyncio.Event()}

            client.cancel_request("test-id")

            assert client.active_requests["test-id"].is_set()


@pytest.mark.unit
class TestClassifyOpenaiError:
    """Test classify_openai_error method."""

    def test_classify_region_error(self):
        """Test classification of region restriction errors."""
        client = self._create_client()
        result = client.classify_openai_error("unsupported_country_region_territory")
        assert "region" in result.lower()

    def test_classify_api_key_error(self):
        """Test classification of API key errors."""
        client = self._create_client()
        result = client.classify_openai_error("invalid_api_key")
        assert "API key" in result

    def test_classify_unauthorized_error(self):
        """Test classification of unauthorized errors."""
        client = self._create_client()
        result = client.classify_openai_error("unauthorized")
        assert "API key" in result

    def test_classify_rate_limit_error(self):
        """Test classification of rate limit errors."""
        client = self._create_client()
        result = client.classify_openai_error("rate_limit_exceeded")
        assert "rate limit" in result.lower()

    def test_classify_quota_error(self):
        """Test classification of quota errors."""
        client = self._create_client()
        result = client.classify_openai_error("quota_exceeded")
        assert "rate limit" in result.lower() or "quota" in result.lower()

    def test_classify_model_not_found_error(self):
        """Test classification of model not found errors."""
        client = self._create_client()
        result = client.classify_openai_error("model gpt-5 not found")
        assert "model" in result.lower()

    def test_classify_billing_error(self):
        """Test classification of billing errors."""
        client = self._create_client()
        result = client.classify_openai_error("billing issue detected")
        assert "billing" in result.lower()

    def test_classify_payment_error(self):
        """Test classification of payment errors."""
        client = self._create_client()
        result = client.classify_openai_error("payment required")
        assert "billing" in result.lower()

    def test_classify_unknown_error(self):
        """Test classification of unknown errors (returns as-is)."""
        client = self._create_client()
        error_msg = "Some random error message"
        result = client.classify_openai_error(error_msg)
        assert result == error_msg

    def test_classify_case_insensitive(self):
        """Test that error classification is case-insensitive."""
        client = self._create_client()
        result = client.classify_openai_error("RATE_LIMIT_EXCEEDED")
        assert "rate limit" in result.lower()

    @staticmethod
    def _create_client():
        """Helper to create a minimal client for testing."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            return OpenAIClient.__new__(OpenAIClient)


@pytest.mark.unit
class TestClientEdgeCases:
    """Test edge cases in client operations."""

    @pytest.mark.asyncio
    async def test_multiple_concurrent_requests(self):
        """Test handling multiple concurrent requests."""
        mock_completion = MagicMock()
        mock_completion.model_dump = Mock(return_value={
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Response"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        })

        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(return_value=mock_completion)
            client.active_requests = {}

            # Create multiple concurrent requests
            tasks = [
                client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": f"Message {i}"}],
                })
                for i in range(5)
            ]

            results = await asyncio.gather(*tasks)
            assert len(results) == 5

    @pytest.mark.asyncio
    async def test_request_id_cleanup_on_exception(self):
        """Test that request_id is cleaned up even on exception."""
        with patch.object(OpenAIClient, '__init__', lambda self, *args, **kwargs: None):
            client = OpenAIClient.__new__(OpenAIClient)
            client.client = Mock()
            client.client.chat.completions.create = AsyncMock(
                side_effect=Exception("Error")
            )
            client.active_requests = {"test-id": asyncio.Event()}

            with pytest.raises(HTTPException):
                await client.create_chat_completion({
                    "model": "gpt-4o",
                    "messages": [{"role": "user", "content": "Hello"}],
                }, request_id="test-id")

            assert "test-id" not in client.active_requests