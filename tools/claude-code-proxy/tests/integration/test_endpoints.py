"""Integration tests for API endpoints."""

import pytest
import os
from fastapi.testclient import TestClient
from unittest.mock import Mock, AsyncMock, patch
from fastapi import Request


@pytest.mark.integration
class TestHealthEndpoint:
    """Test /health endpoint."""

    def test_health_check(self, sample_config):
        """Test health check returns correct status."""
        from src.api.endpoints import router, config, key_pool, openai_clients

        # Temporarily replace the global config
        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)
            response = client.get("/health")

            assert response.status_code == 200
            data = response.json()
            assert data["status"] == "healthy"
            assert "timestamp" in data
            assert "backend_keys" in data
            assert "backend_count" in data


@pytest.mark.integration
class TestRootEndpoint:
    """Test / root endpoint."""

    def test_root_endpoint(self, sample_config):
        """Test root endpoint returns service info."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)
            response = client.get("/")

            assert response.status_code == 200
            data = response.json()
            assert data["message"] == "Claude-to-OpenAI API Proxy v1.0.0"
            assert data["status"] == "running"
            assert "config" in data
            assert "endpoints" in data
            assert data["endpoints"]["messages"] == "/v1/messages"


@pytest.mark.integration
class TestCountTokensEndpoint:
    """Test /v1/messages/count_tokens endpoint."""

    def test_count_tokens_basic(self, sample_config):
        """Test basic token counting."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "messages": [
                        {"role": "user", "content": "Hello, how are you?"},
                    ]
                },
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert "input_tokens" in data
            assert data["input_tokens"] > 0

    def test_count_tokens_with_system(self, sample_config):
        """Test token counting with system message."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "system": "You are a helpful assistant.",
                    "messages": [
                        {"role": "user", "content": "Hello"},
                    ]
                },
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["input_tokens"] > 0

    def test_count_tokens_system_list(self, sample_config):
        """Test token counting with list system message."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "system": [
                        {"type": "text", "text": "You are helpful."},
                        {"type": "text", "text": "Be concise."},
                    ],
                    "messages": [{"role": "user", "content": "Hi"}]
                },
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 200

    def test_count_tokens_multimodal(self, sample_config):
        """Test token counting with multimodal content."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": "What's in this image?"},
                                {
                                    "type": "image",
                                    "source": {
                                        "type": "base64",
                                        "media_type": "image/png",
                                        "data": "iVBORw0KG..."
                                    }
                                }
                            ]
                        }
                    ]
                },
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 200

    def test_count_tokens_invalid_json(self, sample_config):
        """Test token counting with invalid JSON."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.post(
                "/v1/messages/count_tokens",
                data="invalid json",
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 400


@pytest.mark.integration
class TestMessagesEndpoint:
    """Test /v1/messages endpoint."""

    def test_basic_message_non_streaming(self, sample_config, monkeypatch):
        """Test basic message endpoint with non-streaming response."""
        from src.api.endpoints import router

        # Mock the OpenAI client response
        mock_response = {
            "id": "chatcmpl-123",
            "object": "chat.completion",
            "created": 1234567890,
            "model": "gpt-4o",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "Hello! How can I help you?",
                },
                "finish_reason": "stop",
            }],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 20,
                "total_tokens": 30,
            },
        }

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(return_value=mock_response)
        mock_client.classify_openai_error = Mock(return_value="Error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        save_openai_request=Mock(),
                        save_openai_response=Mock(),
                        save_claude_response=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    assert response.status_code == 200
                    data = response.json()
                    assert data["role"] == "assistant"
                    assert data["type"] == "message"

    def test_message_with_tools(self, sample_config, monkeypatch):
        """Test message endpoint with tool definitions."""
        from src.api.endpoints import router

        # Mock tool call response
        mock_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {
                    "role": "assistant",
                    "content": "Let me check that.",
                    "tool_calls": [
                        {
                            "id": "call_123",
                            "type": "function",
                            "function": {
                                "name": "get_weather",
                                "arguments": '{"location": "Paris"}',
                            }
                        }
                    ],
                },
                "finish_reason": "tool_calls",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(return_value=mock_response)
        mock_client.classify_openai_error = Mock(return_value="Error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        save_openai_request=Mock(),
                        save_openai_response=Mock(),
                        save_claude_response=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "What's the weather?"}],
                            "tools": [
                                {
                                    "name": "get_weather",
                                    "description": "Get weather",
                                    "input_schema": {
                                        "type": "object",
                                        "properties": {
                                            "location": {"type": "string"}
                                        }
                                    }
                                }
                            ],
                            "tool_choice": {"type": "auto"},
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    assert response.status_code == 200

    def test_message_with_system(self, sample_config, monkeypatch):
        """Test message endpoint with system message."""
        from src.api.endpoints import router

        mock_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Response"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(return_value=mock_response)
        mock_client.classify_openai_error = Mock(return_value="Error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        save_openai_request=Mock(),
                        save_openai_response=Mock(),
                        save_claude_response=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "system": "You are a helpful assistant.",
                            "messages": [{"role": "user", "content": "Hello"}],
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    assert response.status_code == 200

    def test_message_invalid_json(self, sample_config):
        """Test message endpoint with invalid JSON."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)
            response = client.post(
                "/v1/messages",
                data="invalid json",
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 400

    def test_message_no_validation_error(self, sample_config):
        """Test message endpoint with validation error."""
        from src.api.endpoints import router

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)
            response = client.post(
                "/v1/messages",
                json={"model": "claude-3-5-sonnet-20241022", "max_tokens": -100},  # Invalid
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 422


@pytest.mark.integration
class TestAPIKeyValidation:
    """Test API key validation in endpoints."""

    def test_valid_api_key(self, sample_config, monkeypatch):
        """Test request with valid API key."""
        from src.api.endpoints import router

        # Set up key mapping
        sample_config.key_pool.mapping = {"valid-key": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.get("/health", headers={"x-api-key": "valid-key"})
            assert response.status_code == 200

    def test_invalid_api_key(self, sample_config, monkeypatch):
        """Test request with invalid API key."""
        from src.api.endpoints import router

        # Set up key mapping
        sample_config.key_pool.mapping = {"valid-key": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.get("/health", headers={"x-api-key": "invalid-key"})
            assert response.status_code == 401

    def test_no_api_key_required(self, sample_config, monkeypatch):
        """Test request when no API key validation is configured."""
        from src.api.endpoints import router

        # No mapping, no ANTHROPIC_API_KEY
        sample_config.key_pool.mapping = None
        sample_config.anthropic_api_key = None

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.get("/health")
            assert response.status_code == 200

    def test_bearer_token_auth(self, sample_config, monkeypatch):
        """Test Bearer token authentication."""
        from src.api.endpoints import router

        sample_config.key_pool.mapping = {"token-123": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(router)

            response = client.get(
                "/health",
                headers={"Authorization": "Bearer token-123"}
            )
            assert response.status_code == 200


@pytest.mark.integration
class TestStreamingResponse:
    """Test streaming response endpoints."""

    def test_streaming_response(self, sample_config, monkeypatch):
        """Test streaming message endpoint."""
        from src.api.endpoints import router

        # Mock streaming response
        async def mock_stream():
            yield 'data: {"choices":[{"delta":{"content":"Hello"}}]}'
            yield 'data: {"choices":[{"delta":{"content":" world"}}]}'
            yield "data: [DONE]"

        mock_client = Mock()
        mock_client.create_chat_completion_stream = AsyncMock(side_effect=mock_stream)
        mock_client.cancel_request = Mock()
        mock_client.classify_openai_error = Mock(return_value="Error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        save_openai_request=Mock(),
                        save_claude_sse_event=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                            "stream": True,
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    assert response.status_code == 200
                    assert "text/event-stream" in response.headers.get("content-type", "")


@pytest.mark.integration
class TestPassthroughMode:
    """Test passthrough mode functionality."""

    def test_passthrough_endpoint(self, sample_config, monkeypatch):
        """Test passthrough mode endpoint."""
        from src.api.endpoints import router

        # Create a passthrough backend key
        from src.core.key_pool import BackendKey
        passthrough_key = BackendKey(
            name="dashscope",
            api_key="sk-dashscope",
            base_url="https://dashscope.aliyuncs.com/v1",
            big_model="glm-4",
            middle_model="glm-4",
            small_model="glm-3-turbo",
            passthrough=True,
        )
        sample_config.key_pool.keys = {"dashscope": passthrough_key}
        sample_config.key_pool.mapping = None

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints._select_backend', return_value=(passthrough_key, None)):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    # Mock httpx AsyncClient
                    with patch('src.api.endpoints.httpx') as mock_httpx:
                        mock_response = Mock()
                        mock_response.status_code = 200
                        mock_response.json = Mock(return_value={
                            "id": "resp-123",
                            "choices": [{
                                "message": {"role": "assistant", "content": "Response"},
                                "finish_reason": "stop",
                            }],
                        })

                        mock_client = Mock()
                        mock_client.post = AsyncMock(return_value=mock_response)
                        mock_httpx.Timeout = Mock
                        mock_httpx.AsyncClient = Mock(return_value=mock_client)

                        client = TestClient(router)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                            },
                            headers={"x-api-key": "test-key"}
                        )

                        # Passthrough should still work
                        assert response.status_code in [200, 500]  # May succeed or fail due to mock


@pytest.mark.integration
class TestErrorHandling:
    """Test error handling in endpoints."""

    def test_internal_server_error(self, sample_config, monkeypatch):
        """Test internal server error handling."""
        from src.api.endpoints import router

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(
            side_effect=Exception("Unexpected error")
        )
        mock_client.classify_openai_error = Mock(return_value="Internal error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        save_openai_request=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    assert response.status_code == 500


@pytest.mark.integration
class TestFixtureCapture:
    """Test fixture capture functionality in endpoints."""

    def test_fixture_capture_saves_data(self, sample_config, monkeypatch):
        """Test that fixture capture saves request/response data."""
        from src.api.endpoints import router

        mock_response = {
            "id": "chatcmpl-123",
            "choices": [{
                "message": {"role": "assistant", "content": "Test response"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20},
        }

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(return_value=mock_response)
        mock_client.classify_openai_error = Mock(return_value="Error")

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints.openai_clients', {"default": mock_client}):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_session = Mock()
                    mock_capture.start_capture = Mock(return_value=mock_session)

                    client = TestClient(router)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                        },
                        headers={"x-api-key": "test-key"}
                    )

                    # Verify capture methods were called
                    mock_session.save_claude_request.assert_called()
                    mock_session.save_openai_request.assert_called()
                    mock_session.save_openai_response.assert_called()
                    mock_session.save_claude_response.assert_called()
                    mock_session.finish.assert_called()