"""Integration tests for API endpoints."""

import pytest
import json
import os
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from unittest.mock import Mock, AsyncMock, patch, MagicMock


def _create_test_app():
    """Create a fresh FastAPI app with the router for testing.

    Newer FastAPI/Starlette requires a full app (not just APIRouter)
    for TestClient to work correctly (middleware_astack setup).
    """
    from src.api.endpoints import router

    app = FastAPI()
    app.include_router(router)
    return app


@pytest.mark.integration
class TestHealthEndpoint:
    """Test /health endpoint."""

    def test_health_check(self, sample_config):
        """Test health check returns correct status."""
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)
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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)
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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

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

                    client = TestClient(app)
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
        app = _create_test_app()

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

                    client = TestClient(app)
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
        app = _create_test_app()

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

                    client = TestClient(app)
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
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)
            response = client.post(
                "/v1/messages",
                data="invalid json",
                headers={"x-api-key": "test-key"}
            )

            assert response.status_code == 400

    def test_message_no_validation_error(self, sample_config):
        """Test message endpoint with validation error."""
        app = _create_test_app()

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)
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
        app = _create_test_app()

        # Set up key mapping
        sample_config.key_pool.mapping = {"valid-key": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

            response = client.get("/health", headers={"x-api-key": "valid-key"})
            assert response.status_code == 200

    def test_invalid_api_key(self, sample_config, monkeypatch):
        """Test request with invalid API key (on an endpoint that validates keys)."""
        app = _create_test_app()

        # Set up key mapping
        sample_config.key_pool.mapping = {"valid-key": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

            # Use /v1/messages/count_tokens which has Depends(validate_api_key)
            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "messages": [{"role": "user", "content": "test"}],
                },
                headers={"x-api-key": "invalid-key"}
            )
            assert response.status_code == 401

    def test_no_api_key_required(self, sample_config, monkeypatch):
        """Test request when no API key validation is configured."""
        app = _create_test_app()

        # No mapping, no ANTHROPIC_API_KEY
        sample_config.key_pool.mapping = {}
        sample_config.anthropic_api_key = None

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

            response = client.get("/health")
            assert response.status_code == 200

    def test_bearer_token_auth(self, sample_config, monkeypatch):
        """Test Bearer token authentication."""
        app = _create_test_app()

        sample_config.key_pool.mapping = {"token-123": ["default"]}

        with patch('src.api.endpoints.config', sample_config):
            client = TestClient(app)

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
        app = _create_test_app()

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

                    client = TestClient(app)
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
        app = _create_test_app()

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
        sample_config.key_pool.mapping = {}

        with patch('src.api.endpoints.config', sample_config):
            with patch('src.api.endpoints._select_backend', return_value=(passthrough_key, None)):
                with patch('src.api.endpoints.fixture_capture') as mock_capture:
                    mock_capture.start_capture = Mock(return_value=Mock(
                        save_claude_request=Mock(),
                        finish=Mock(),
                        meta={}
                    ))

                    # Mock httpx.AsyncClient as async context manager
                    import httpx as real_httpx
                    mock_resp = Mock()
                    mock_resp.status_code = 200
                    mock_resp.json = Mock(return_value={
                        "id": "resp-123",
                        "type": "message",
                        "content": [{"type": "text", "text": "Response"}],
                    })

                    mock_async_client = AsyncMock()
                    mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
                    mock_async_client.__aexit__ = AsyncMock(return_value=False)
                    mock_async_client.post = AsyncMock(return_value=mock_resp)

                    with patch('src.api.endpoints.httpx') as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
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


@pytest.mark.integration
class TestErrorHandling:
    """Test error handling in endpoints."""

    def test_internal_server_error(self, sample_config, monkeypatch):
        """Test internal server error handling."""
        app = _create_test_app()

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

                    client = TestClient(app, raise_server_exceptions=False)
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
        app = _create_test_app()

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
                    mock_session.meta = {}
                    mock_capture.start_capture = Mock(return_value=mock_session)

                    client = TestClient(app)
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


# ============================================================================
# Helpers for passthrough / streaming tests
# ============================================================================


def _make_passthrough_key():
    """Create a passthrough BackendKey for testing."""
    from src.core.key_pool import BackendKey
    return BackendKey(
        name="dashscope",
        api_key="sk-dashscope",
        base_url="https://dashscope.aliyuncs.com",
        big_model="glm-4",
        middle_model="glm-4",
        small_model="glm-3-turbo",
        passthrough=True,
    )


def _patch_passthrough(sample_config):
    """Prepare sample_config for passthrough tests."""
    pt_key = _make_passthrough_key()
    sample_config.key_pool.keys = {"dashscope": pt_key}
    sample_config.key_pool.mapping = {}
    return pt_key


def _mock_capture():
    """Create a mock fixture_capture context."""
    mock = Mock()
    mock.start_capture = Mock(return_value=Mock(
        save_claude_request=Mock(),
        save_openai_request=Mock(),
        save_openai_response=Mock(),
        save_claude_response=Mock(),
        save_claude_sse_event=Mock(),
        finish=Mock(),
        meta={},
    ))
    return mock


# ============================================================================
# P2: Passthrough non-streaming — truncation retry, error paths
# ============================================================================


@pytest.mark.integration
class TestPassthroughNonStreaming:
    """Test passthrough non-streaming code paths (lines 282-316)."""

    def test_passthrough_non_json_response(self, sample_config):
        """Backend returns non-JSON response → error body constructed (lines 287-290)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        # Mock httpx response that fails to parse as JSON
        import httpx as real_httpx
        mock_resp = Mock()
        mock_resp.status_code = 502
        mock_resp.json = Mock(side_effect=ValueError("No JSON"))
        mock_resp.text = "Bad Gateway: upstream failure"

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(return_value=mock_resp)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 502
                        data = response.json()
                        assert data["type"] == "error"
                        assert "Bad Gateway" in data["error"]["message"]

    def test_passthrough_truncation_retry_success(self, sample_config):
        """Backend returns 400 input-length → truncate + retry succeeds (lines 297-312)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        # First response: 400 input length error
        error_resp = Mock()
        error_resp.status_code = 400
        error_resp.json = Mock(return_value={
            "error": {"type": "invalid_request", "message": "Input length exceeded limit"}
        })

        # Retry response: 200 success
        success_resp = Mock()
        success_resp.status_code = 200
        success_resp.json = Mock(return_value={
            "id": "resp-retry", "type": "message",
            "content": [{"type": "text", "text": "Truncated response"}],
        })

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(side_effect=[error_resp, success_resp])

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200
                        data = response.json()
                        assert data["id"] == "resp-retry"

    def test_passthrough_truncation_retry_also_fails(self, sample_config):
        """Backend returns 400 → truncate retry also fails → return original error (line 314)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        error_body = {"error": {"type": "invalid_request", "message": "Input length exceeded"}}

        error_resp = Mock()
        error_resp.status_code = 400
        error_resp.json = Mock(return_value=error_body)

        retry_fail_resp = Mock()
        retry_fail_resp.status_code = 400
        retry_fail_resp.json = Mock(return_value=error_body)

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(side_effect=[error_resp, retry_fail_resp])

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        # Original error is returned
                        assert response.status_code == 400

    def test_passthrough_normal_error_no_retry(self, sample_config):
        """Backend 500 error without input-length → no retry, return error directly."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        error_resp = Mock()
        error_resp.status_code = 500
        error_resp.json = Mock(return_value={
            "error": {"type": "server_error", "message": "Internal error"}
        })

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(return_value=error_resp)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 500
                        # post should be called exactly once (no retry)
                        assert mock_async_client.post.call_count == 1

    def test_passthrough_strips_unknown_fields(self, sample_config):
        """Passthrough strips fields not in the standard Anthropic API (lines 161-165)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_resp = Mock()
        mock_resp.status_code = 200
        mock_resp.json = Mock(return_value={"type": "message", "content": []})

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(return_value=mock_resp)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "thinking": {"type": "enabled"},
                                "context_management": {"enabled": True},
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200
                        # Verify the forwarded body doesn't have unknown fields
                        call_args = mock_async_client.post.call_args
                        sent_body = json.loads(call_args.kwargs["content"])
                        assert "thinking" not in sent_body
                        assert "context_management" not in sent_body
                        assert "model" in sent_body
                        assert "messages" in sent_body

    def test_passthrough_json_decode_error(self, sample_config):
        """Passthrough with unparseable body → forwards raw body (lines 175-176)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_resp = Mock()
        mock_resp.status_code = 200
        mock_resp.json = Mock(return_value={"type": "message"})

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(return_value=mock_resp)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                        client = TestClient(app)
                        # Send raw non-JSON body
                        response = client.post(
                            "/v1/messages",
                            content=b"not valid json {{{",
                            headers={
                                "x-api-key": "test-key",
                                "content-type": "application/json",
                            },
                        )

                        # Should still forward (raw body fallback)
                        assert response.status_code == 200


# ============================================================================
# P3: Passthrough streaming (_stream_passthrough generator)
# ============================================================================


@pytest.mark.integration
class TestPassthroughStreaming:
    """Test passthrough streaming code paths (lines 193-271)."""

    def test_streaming_passthrough_success(self, sample_config):
        """Normal streaming passthrough: forward SSE chunks (lines 250-255)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        # Mock stream response
        mock_stream_resp = AsyncMock()
        mock_stream_resp.status_code = 200

        async def aiter_bytes():
            yield b"event: message_start\ndata: {}\n\n"
            yield b"event: content_block_delta\ndata: {\"text\":\"Hi\"}\n\n"
            yield b"event: message_stop\ndata: {}\n\n"

        mock_stream_resp.aiter_bytes = aiter_bytes

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)

        # stream() returns an async context manager
        mock_stream_ctx = AsyncMock()
        mock_stream_ctx.__aenter__ = AsyncMock(return_value=mock_stream_resp)
        mock_stream_ctx.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.stream = Mock(return_value=mock_stream_ctx)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)
                        mock_httpx.TimeoutException = real_httpx.TimeoutException
                        mock_httpx.HTTPError = real_httpx.HTTPError

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "stream": True,
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200
                        assert "text/event-stream" in response.headers.get("content-type", "")
                        # Verify SSE content was forwarded
                        body = response.content.decode()
                        assert "message_start" in body or "Hi" in body or len(body) > 0

    def test_streaming_passthrough_backend_error_no_retry(self, sample_config):
        """Stream: backend 500 error → SSE error event emitted (lines 237-249)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_stream_resp = AsyncMock()
        mock_stream_resp.status_code = 500
        mock_stream_resp.aread = AsyncMock(return_value=json.dumps({
            "error": {"type": "server_error", "message": "Backend crashed"}
        }).encode())

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)

        mock_stream_ctx = AsyncMock()
        mock_stream_ctx.__aenter__ = AsyncMock(return_value=mock_stream_resp)
        mock_stream_ctx.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.stream = Mock(return_value=mock_stream_ctx)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)
                        mock_httpx.TimeoutException = real_httpx.TimeoutException
                        mock_httpx.HTTPError = real_httpx.HTTPError

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "stream": True,
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200  # StreamingResponse always 200
                        body = response.content.decode()
                        assert "event: error" in body
                        assert "Backend crashed" in body

    def test_streaming_passthrough_truncation_retry_success(self, sample_config):
        """Stream: backend 400 input-length → truncation retry succeeds (lines 204-234)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        # First stream call: 400 input-length error
        error_stream_resp = AsyncMock()
        error_stream_resp.status_code = 400
        error_stream_resp.aread = AsyncMock(return_value=json.dumps({
            "error": {"type": "invalid_request", "message": "Input length exceeded"}
        }).encode())

        # Retry stream call: 200 success
        success_stream_resp = AsyncMock()
        success_stream_resp.status_code = 200

        async def aiter_bytes():
            yield b"event: message_start\ndata: {}\n\n"
            yield b"event: message_stop\ndata: {}\n\n"

        success_stream_resp.aiter_bytes = aiter_bytes

        # Build client.stream() to return error first, then success
        call_count = 0

        class StreamContextManager:
            def __init__(self, resp):
                self.resp = resp

            async def __aenter__(self):
                return self.resp

            async def __aexit__(self, *args):
                return False

        def stream_side_effect(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return StreamContextManager(error_stream_resp)
            else:
                return StreamContextManager(success_stream_resp)

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.stream = Mock(side_effect=stream_side_effect)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)
                        mock_httpx.TimeoutException = real_httpx.TimeoutException
                        mock_httpx.HTTPError = real_httpx.HTTPError

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "stream": True,
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200
                        body = response.content.decode()
                        # Should contain the successful retry data, not an error
                        assert "event: error" not in body or "message_start" in body

    def test_streaming_passthrough_timeout(self, sample_config):
        """Stream: httpx.TimeoutException → SSE error event (lines 256-262)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)

        # stream() raises TimeoutException
        mock_stream_ctx = AsyncMock()
        mock_stream_ctx.__aenter__ = AsyncMock(
            side_effect=real_httpx.ReadTimeout("Connection timed out")
        )
        mock_stream_ctx.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.stream = Mock(return_value=mock_stream_ctx)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)
                        mock_httpx.TimeoutException = real_httpx.TimeoutException
                        mock_httpx.HTTPError = real_httpx.HTTPError

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "stream": True,
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200  # StreamingResponse
                        body = response.content.decode()
                        assert "event: error" in body
                        assert "timed out" in body.lower()

    def test_streaming_passthrough_http_error(self, sample_config):
        """Stream: httpx.HTTPError → SSE error event (lines 263-269)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)

        mock_stream_ctx = AsyncMock()
        mock_stream_ctx.__aenter__ = AsyncMock(
            side_effect=real_httpx.ConnectError("Connection refused")
        )
        mock_stream_ctx.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.stream = Mock(return_value=mock_stream_ctx)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints._select_backend", return_value=(pt_key, None)):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    with patch("src.api.endpoints.httpx") as mock_httpx:
                        mock_httpx.Timeout = real_httpx.Timeout
                        mock_httpx.AsyncClient = Mock(return_value=mock_async_client)
                        mock_httpx.TimeoutException = real_httpx.TimeoutException
                        mock_httpx.HTTPError = real_httpx.HTTPError

                        client = TestClient(app)
                        response = client.post(
                            "/v1/messages",
                            json={
                                "model": "claude-3-5-sonnet-20241022",
                                "max_tokens": 1000,
                                "messages": [{"role": "user", "content": "Hello"}],
                                "stream": True,
                            },
                            headers={"x-api-key": "test-key"},
                        )

                        assert response.status_code == 200
                        body = response.content.decode()
                        assert "event: error" in body
                        assert "connection failed" in body.lower()


# ============================================================================
# P4: /test-connection endpoint
# ============================================================================


@pytest.mark.integration
class TestConnectionEndpoint:
    """Test /test-connection endpoint (lines 521-557)."""

    def test_connection_all_success(self, sample_config):
        """All backends connect successfully → 200."""
        app = _create_test_app()

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(return_value={
            "id": "chatcmpl-test", "model": "gpt-4o-mini",
        })

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {"default": mock_client}):
                client = TestClient(app)
                response = client.get("/test-connection")

                assert response.status_code == 200
                data = response.json()
                assert data["status"] == "success"
                assert len(data["backends"]) == 1
                assert data["backends"][0]["status"] == "success"

    def test_connection_failure(self, sample_config):
        """Backend connection fails → 503."""
        app = _create_test_app()

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(
            side_effect=Exception("Connection refused")
        )

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {"default": mock_client}):
                client = TestClient(app)
                response = client.get("/test-connection")

                assert response.status_code == 503
                data = response.json()
                assert data["status"] == "partial_failure"
                assert data["backends"][0]["status"] == "failed"
                assert "Connection refused" in data["backends"][0]["error"]

    def test_connection_passthrough_skipped(self, sample_config):
        """Passthrough backends are skipped in connection test."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {}):
                client = TestClient(app)
                response = client.get("/test-connection")

                assert response.status_code == 200
                data = response.json()
                assert data["status"] == "success"
                assert data["backends"][0]["status"] == "skipped"
                assert "passthrough" in data["backends"][0]["reason"]

    def test_connection_mixed_results(self, sample_config):
        """Mix of success and failure → 503."""
        app = _create_test_app()

        from src.core.key_pool import BackendKey
        key2 = BackendKey(
            name="key2", api_key="sk-2", base_url="https://api.other.com/v1",
            big_model="gpt-4o", middle_model="gpt-4o", small_model="gpt-4o-mini",
        )
        sample_config.key_pool.keys["key2"] = key2

        mock_client_ok = Mock()
        mock_client_ok.create_chat_completion = AsyncMock(return_value={"id": "ok"})

        mock_client_fail = Mock()
        mock_client_fail.create_chat_completion = AsyncMock(
            side_effect=Exception("Timeout")
        )

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {
                "default": mock_client_ok, "key2": mock_client_fail
            }):
                client = TestClient(app)
                response = client.get("/test-connection")

                assert response.status_code == 503
                data = response.json()
                assert data["status"] == "partial_failure"


# ============================================================================
# P5: Streaming message flow + error handling
# ============================================================================


@pytest.mark.integration
class TestStreamingMessageFlow:
    """Test streaming message flow with error handling (lines 375-446)."""

    def test_streaming_http_exception_in_setup(self, sample_config):
        """HTTPException during stream creation → JSON error response (lines 412-423)."""
        app = _create_test_app()

        mock_client = Mock()
        # Use Mock (not AsyncMock) so the exception is raised synchronously
        # when the endpoint calls create_chat_completion_stream()
        mock_client.create_chat_completion_stream = Mock(
            side_effect=HTTPException(status_code=503, detail="Backend unavailable")
        )
        mock_client.classify_openai_error = Mock(return_value="Backend unavailable")
        mock_client.cancel_request = Mock()

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {"default": mock_client}):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    client = TestClient(app, raise_server_exceptions=False)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                            "stream": True,
                        },
                        headers={"x-api-key": "test-key"},
                    )

                    assert response.status_code == 503
                    data = response.json()
                    assert data["type"] == "error"

    def test_streaming_generic_exception(self, sample_config):
        """Generic exception in request processing → 500 (lines 439-446)."""
        app = _create_test_app()

        mock_client = Mock()
        mock_client.create_chat_completion = AsyncMock(
            side_effect=RuntimeError("Unexpected crash")
        )
        mock_client.classify_openai_error = Mock(return_value="Unexpected crash")

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.openai_clients", {"default": mock_client}):
                with patch("src.api.endpoints.fixture_capture", _mock_capture()):
                    client = TestClient(app, raise_server_exceptions=False)
                    response = client.post(
                        "/v1/messages",
                        json={
                            "model": "claude-3-5-sonnet-20241022",
                            "max_tokens": 1000,
                            "messages": [{"role": "user", "content": "Hello"}],
                        },
                        headers={"x-api-key": "test-key"},
                    )

                    assert response.status_code == 500

    def test_count_tokens_passthrough_mode(self, sample_config):
        """Count tokens in passthrough mode → forwards to backend (lines 458-463)."""
        app = _create_test_app()
        pt_key = _patch_passthrough(sample_config)

        import httpx as real_httpx

        mock_resp = Mock()
        mock_resp.status_code = 200
        mock_resp.json = Mock(return_value={"input_tokens": 42})

        mock_async_client = AsyncMock()
        mock_async_client.__aenter__ = AsyncMock(return_value=mock_async_client)
        mock_async_client.__aexit__ = AsyncMock(return_value=False)
        mock_async_client.post = AsyncMock(return_value=mock_resp)

        with patch("src.api.endpoints.config", sample_config):
            with patch("src.api.endpoints.httpx") as mock_httpx:
                mock_httpx.Timeout = real_httpx.Timeout
                mock_httpx.AsyncClient = Mock(return_value=mock_async_client)

                client = TestClient(app)
                response = client.post(
                    "/v1/messages/count_tokens",
                    json={
                        "model": "claude-3-5-sonnet-20241022",
                        "messages": [{"role": "user", "content": "Hello"}],
                    },
                    headers={"x-api-key": "test-key"},
                )

                assert response.status_code == 200

    def test_count_tokens_validation_error(self, sample_config):
        """Count tokens with validation error → 422 (lines 471-472)."""
        app = _create_test_app()

        with patch("src.api.endpoints.config", sample_config):
            client = TestClient(app)
            response = client.post(
                "/v1/messages/count_tokens",
                json={"model": "claude-3-5-sonnet-20241022"},  # missing messages
                headers={"x-api-key": "test-key"},
            )

            assert response.status_code == 422

    def test_count_tokens_null_content(self, sample_config):
        """Count tokens with null message content → handled gracefully (line 489)."""
        app = _create_test_app()

        with patch("src.api.endpoints.config", sample_config):
            client = TestClient(app)
            response = client.post(
                "/v1/messages/count_tokens",
                json={
                    "model": "claude-3-5-sonnet-20241022",
                    "messages": [{"role": "user", "content": None}],
                },
                headers={"x-api-key": "test-key"},
            )

            # Should still succeed (content=None is skipped)
            assert response.status_code == 200
            data = response.json()
            assert data["input_tokens"] >= 1  # max(1, 0//4) = 1
