"""Unit tests for endpoints.py helper functions.

Covers:
  - _extract_client_key (lines 43-49)
  - _strip_thinking_blocks (lines 91-114)
  - _is_input_length_error (lines 117-122)
  - validate_api_key legacy path (lines 67, 71-73)
"""

import pytest
from unittest.mock import patch, Mock, AsyncMock

from src.api.endpoints import (
    _extract_client_key,
    _strip_thinking_blocks,
    _is_input_length_error,
    validate_api_key,
)


# ============================================================================
# _extract_client_key
# ============================================================================


class TestExtractClientKey:
    """Test _extract_client_key helper."""

    def test_x_api_key_takes_priority(self):
        """x-api-key header is preferred over Authorization."""
        result = _extract_client_key("key-from-header", "Bearer key-from-auth")
        assert result == "key-from-header"

    def test_bearer_token_extracted(self):
        """Authorization: Bearer token is extracted when x-api-key is absent."""
        result = _extract_client_key(None, "Bearer my-secret-token")
        assert result == "my-secret-token"

    def test_no_headers_returns_none(self):
        """Returns None when neither header is set."""
        result = _extract_client_key(None, None)
        assert result is None

    def test_non_bearer_auth_returns_none(self):
        """Non-Bearer Authorization header is ignored."""
        result = _extract_client_key(None, "Basic dXNlcjpwYXNz")
        assert result is None

    def test_empty_x_api_key_falls_through(self):
        """Empty string x-api-key falls through to Authorization."""
        result = _extract_client_key("", "Bearer fallback-key")
        assert result == "fallback-key"

    def test_empty_bearer_value(self):
        """Bearer prefix with value returns the value."""
        result = _extract_client_key(None, "Bearer ")
        assert result == ""


# ============================================================================
# _strip_thinking_blocks
# ============================================================================


class TestStripThinkingBlocks:
    """Test _strip_thinking_blocks helper."""

    def test_strips_thinking_blocks(self):
        """Removes thinking-type content blocks from messages."""
        body = {
            "messages": [
                {
                    "role": "assistant",
                    "content": [
                        {"type": "thinking", "thinking": "Let me think..."},
                        {"type": "text", "text": "Hello!"},
                    ],
                }
            ]
        }
        _strip_thinking_blocks(body)
        assert len(body["messages"][0]["content"]) == 1
        assert body["messages"][0]["content"][0]["type"] == "text"

    def test_replaces_empty_content_with_placeholder(self):
        """When all blocks are thinking, replaces with empty text block."""
        body = {
            "messages": [
                {
                    "role": "assistant",
                    "content": [
                        {"type": "thinking", "thinking": "Internal monologue"},
                    ],
                }
            ]
        }
        _strip_thinking_blocks(body)
        assert len(body["messages"][0]["content"]) == 1
        assert body["messages"][0]["content"][0] == {"type": "text", "text": ""}

    def test_no_thinking_blocks_unchanged(self):
        """Messages without thinking blocks remain unchanged."""
        body = {
            "messages": [
                {
                    "role": "user",
                    "content": [{"type": "text", "text": "Hello"}],
                }
            ]
        }
        _strip_thinking_blocks(body)
        assert len(body["messages"][0]["content"]) == 1
        assert body["messages"][0]["content"][0]["text"] == "Hello"

    def test_string_content_skipped(self):
        """Messages with string content are not affected."""
        body = {
            "messages": [
                {"role": "user", "content": "Just a string"},
            ]
        }
        _strip_thinking_blocks(body)
        assert body["messages"][0]["content"] == "Just a string"

    def test_no_messages_key(self):
        """Body without 'messages' key does nothing."""
        body = {"model": "claude-3"}
        _strip_thinking_blocks(body)
        assert body == {"model": "claude-3"}

    def test_messages_not_a_list(self):
        """Non-list messages value does nothing."""
        body = {"messages": "not a list"}
        _strip_thinking_blocks(body)
        assert body == {"messages": "not a list"}

    def test_multiple_messages_multiple_thinking(self):
        """Strips thinking from multiple messages, counting total stripped."""
        body = {
            "messages": [
                {
                    "role": "assistant",
                    "content": [
                        {"type": "thinking", "thinking": "..."},
                        {"type": "text", "text": "A"},
                    ],
                },
                {
                    "role": "assistant",
                    "content": [
                        {"type": "thinking", "thinking": "..."},
                        {"type": "thinking", "thinking": "more..."},
                        {"type": "text", "text": "B"},
                    ],
                },
            ]
        }
        _strip_thinking_blocks(body)
        assert len(body["messages"][0]["content"]) == 1  # 1 thinking stripped
        assert len(body["messages"][1]["content"]) == 1  # 2 thinking stripped


# ============================================================================
# _is_input_length_error
# ============================================================================


class TestIsInputLengthError:
    """Test _is_input_length_error helper."""

    def test_input_length_400(self):
        """Matches 'input length' with status 400."""
        assert _is_input_length_error(400, "Input length exceeded the limit") is True

    def test_context_length_400(self):
        """Matches 'context length' with status 400."""
        assert _is_input_length_error(400, "Context length too long for this model") is True

    def test_too_many_tokens_400(self):
        """Matches 'too many tokens' with status 400."""
        assert _is_input_length_error(400, "Too many tokens in the request") is True

    def test_non_400_status(self):
        """Non-400 status code returns False."""
        assert _is_input_length_error(500, "Input length exceeded") is False
        assert _is_input_length_error(429, "Too many tokens") is False

    def test_400_unrelated_error(self):
        """400 with unrelated error message returns False."""
        assert _is_input_length_error(400, "Invalid model parameter") is False

    def test_case_insensitive(self):
        """Matching is case-insensitive."""
        assert _is_input_length_error(400, "INPUT LENGTH exceeded") is True
        assert _is_input_length_error(400, "CONTEXT LENGTH limit") is True
        assert _is_input_length_error(400, "TOO MANY TOKENS") is True


# ============================================================================
# validate_api_key — legacy ANTHROPIC_API_KEY path
# ============================================================================


class TestValidateApiKeyLegacy:
    """Test validate_api_key with legacy ANTHROPIC_API_KEY config (lines 70-76)."""

    @pytest.mark.asyncio
    async def test_legacy_valid_key(self):
        """Legacy mode: valid key passes validation."""
        mock_config = Mock()
        mock_config.key_pool.has_mapping = False
        mock_config.anthropic_api_key = "sk-ant-secret"
        mock_config.validate_client_api_key = Mock(return_value=True)

        with patch("src.api.endpoints.config", mock_config):
            result = await validate_api_key(x_api_key="sk-ant-secret", authorization=None)
            assert result == "sk-ant-secret"
            mock_config.validate_client_api_key.assert_called_once_with("sk-ant-secret")

    @pytest.mark.asyncio
    async def test_legacy_invalid_key_raises(self):
        """Legacy mode: invalid key raises 401."""
        from fastapi import HTTPException

        mock_config = Mock()
        mock_config.key_pool.has_mapping = False
        mock_config.anthropic_api_key = "sk-ant-secret"
        mock_config.validate_client_api_key = Mock(return_value=False)

        with patch("src.api.endpoints.config", mock_config):
            with pytest.raises(HTTPException) as exc_info:
                await validate_api_key(x_api_key="wrong-key", authorization=None)
            assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_legacy_no_key_provided_raises(self):
        """Legacy mode: missing key raises 401."""
        from fastapi import HTTPException

        mock_config = Mock()
        mock_config.key_pool.has_mapping = False
        mock_config.anthropic_api_key = "sk-ant-secret"
        mock_config.validate_client_api_key = Mock(return_value=False)

        with patch("src.api.endpoints.config", mock_config):
            with pytest.raises(HTTPException) as exc_info:
                await validate_api_key(x_api_key=None, authorization=None)
            assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_mapping_valid_key_returns_key(self):
        """Mapping mode: valid mapped key returns the client key."""
        mock_config = Mock()
        mock_config.key_pool.has_mapping = True
        mock_config.key_pool.validate_client_key = Mock(return_value=True)

        with patch("src.api.endpoints.config", mock_config):
            result = await validate_api_key(x_api_key="mapped-key", authorization=None)
            assert result == "mapped-key"

    @pytest.mark.asyncio
    async def test_no_auth_configured_passes(self):
        """No ANTHROPIC_API_KEY and no mapping: any request passes."""
        mock_config = Mock()
        mock_config.key_pool.has_mapping = False
        mock_config.anthropic_api_key = None

        with patch("src.api.endpoints.config", mock_config):
            result = await validate_api_key(x_api_key=None, authorization=None)
            assert result is None
