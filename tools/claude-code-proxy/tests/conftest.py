"""Common pytest fixtures for claude-code-proxy tests."""

import os
import sys
import pytest
import tempfile
import shutil
from unittest.mock import Mock, MagicMock, AsyncMock
from typing import Dict, Optional

# Add src to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))

from src.core.key_pool import BackendKey, KeyPool
from src.core.config import Config
from src.core.model_manager import ModelManager
from src.models.claude import ClaudeMessagesRequest, ClaudeMessage
from tests.test_env_config import TestEnvConfig, EnvFileWriter


# ============================================================================
# Environment Fixtures
# ============================================================================

@pytest.fixture
def temp_env_vars():
    """Fixture to temporarily set environment variables.

    Usage:
        def test_with_custom_env(temp_env_vars):
            os.environ["OPENAI_API_KEY"] = "sk-test-key"
            config = Config()
    """
    original_env = os.environ.copy()
    yield
    os.environ.clear()
    os.environ.update(original_env)


@pytest.fixture
def clean_env():
    """Fixture that provides a completely clean environment.

    All existing environment variables are cleared during the test.
    """
    original_env = os.environ.copy()
    os.environ.clear()
    yield
    os.environ.clear()
    os.environ.update(original_env)


@pytest.fixture
def env_config():
    """Fixture to provide test environment configuration manager."""
    return TestEnvConfig


@pytest.fixture(params=["default", "multi_key", "passthrough", "with_mapping"])
def test_env(request):
    """Parameterized fixture for different test environments.

    Usage:
        def test_with_different_envs(test_env):
            config = TestEnvConfig.get_env_config(test_env)
            TestEnvConfig.apply_env_config(config)
    """
    return request.param


@pytest.fixture
def env_default(clean_env):
    """Environment fixture with default configuration."""
    config = TestEnvConfig.get_env_config("default")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


@pytest.fixture
def env_multi_key(clean_env):
    """Environment fixture with multi-key configuration."""
    config = TestEnvConfig.get_env_config("multi_key")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


@pytest.fixture
def env_passthrough(clean_env):
    """Environment fixture with passthrough configuration."""
    config = TestEnvConfig.get_env_config("passthrough")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


@pytest.fixture
def env_with_mapping(clean_env):
    """Environment fixture with key mapping configuration."""
    config = TestEnvConfig.get_env_config("with_mapping")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


@pytest.fixture
def env_custom_headers(clean_env):
    """Environment fixture with custom headers."""
    config = TestEnvConfig.get_env_config("custom_headers")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


@pytest.fixture
def env_truncation(clean_env):
    """Environment fixture with custom truncation settings."""
    config = TestEnvConfig.get_env_config("truncation")
    TestEnvConfig.apply_env_config(config)
    yield config
    TestEnvConfig.clear_env(config)


def apply_custom_env(env_name: str, custom_config: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """Helper function to apply a specific environment with optional custom overrides.

    This can be used directly in tests:
        def test_my_scenario():
            config = apply_custom_env("default", {"OPENAI_API_KEY": "my-key"})
            # ... test code
            TestEnvConfig.clear_env(config)
    """
    base_config = TestEnvConfig.get_env_config(env_name)
    if custom_config:
        base_config = {**base_config, **custom_config}
    TestEnvConfig.apply_env_config(base_config)
    return base_config


@pytest.fixture
def temp_dir():
    """Create a temporary directory and clean it up after the test."""
    temp_path = tempfile.mkdtemp()
    yield temp_path
    shutil.rmtree(temp_path, ignore_errors=True)


# ============================================================================
# Data Fixtures
# ============================================================================

@pytest.fixture
def sample_backend_key():
    """Create a sample BackendKey for testing."""
    return BackendKey(
        name="test-key",
        api_key="sk-test-key-123",
        base_url="https://api.openai.com/v1",
        big_model="gpt-4o",
        middle_model="gpt-4o",
        small_model="gpt-4o-mini",
        passthrough=False
    )


@pytest.fixture
def sample_backend_keys():
    """Create multiple sample BackendKeys for testing."""
    return {
        "key1": BackendKey(
            name="key1",
            api_key="sk-key1",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
            passthrough=False
        ),
        "key2": BackendKey(
            name="key2",
            api_key="sk-key2",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
            passthrough=False
        ),
    }


@pytest.fixture
def sample_key_pool(sample_backend_keys):
    """Create a sample KeyPool for testing."""
    return KeyPool(keys=sample_backend_keys, mapping=None)


@pytest.fixture
def sample_key_pool_with_mapping():
    """Create a KeyPool with client-to-backend mapping."""
    keys = {
        "backend1": BackendKey(
            name="backend1",
            api_key="sk-backend1",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
            passthrough=False
        ),
        "backend2": BackendKey(
            name="backend2",
            api_key="sk-backend2",
            base_url="https://api.openai.com/v1",
            big_model="gpt-4o",
            middle_model="gpt-4o",
            small_model="gpt-4o-mini",
            passthrough=False
        ),
    }
    mapping = {
        "client-key-1": ["backend1"],
        "client-key-2": ["backend1", "backend2"],
    }
    return KeyPool(keys=keys, mapping=mapping)


@pytest.fixture
def sample_config():
    """Create a minimal Config with mocked environment."""
    # We'll mock the environment setup to avoid sys.exit(1)
    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a minimal config object
        config = Config.__new__(Config)
        config.openai_base_url = "https://api.openai.com/v1"
        config.azure_api_version = None
        config.host = "0.0.0.0"
        config.port = 8082
        config.log_level = "INFO"
        config.max_tokens_limit = 4096
        config.min_tokens_limit = 100
        config.request_timeout = 90
        config.max_retries = 2
        config.truncation_tool_result_limit = 30000
        config.truncation_keep_head_messages = 4
        config.truncation_keep_tail_messages = 40
        config.big_model = "gpt-4o"
        config.middle_model = "gpt-4o"
        config.small_model = "gpt-4o-mini"
        config.anthropic_api_key = None

        # Create a simple key pool
        config.key_pool = KeyPool(
            keys={
                "default": BackendKey(
                    name="default",
                    api_key="sk-test-key",
                    base_url="https://api.openai.com/v1",
                    big_model="gpt-4o",
                    middle_model="gpt-4o",
                    small_model="gpt-4o-mini",
                )
            },
            mapping=None
        )
        config.openai_api_key = "sk-test-key"

        yield config


@pytest.fixture
def sample_model_manager(sample_config):
    """Create a ModelManager for testing."""
    return ModelManager(sample_config)


# ============================================================================
# Model Fixtures (Claude requests/messages)
# ============================================================================

@pytest.fixture
def sample_claude_message():
    """Create a simple Claude message."""
    return ClaudeMessage(
        role="user",
        content="Hello, how are you?"
    )


@pytest.fixture
def sample_claude_messages_request():
    """Create a sample Claude Messages API request."""
    return ClaudeMessagesRequest(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1000,
        messages=[
            ClaudeMessage(role="user", content="What is the capital of France?")
        ],
        temperature=0.7,
        top_p=0.9,
        stream=False,
    )


@pytest.fixture
def sample_claude_request_with_tools():
    """Create a Claude request with tool definitions."""
    from src.models.claude import ClaudeTool
    return ClaudeMessagesRequest(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1000,
        messages=[
            ClaudeMessage(role="user", content="What's the weather in Paris?")
        ],
        tools=[
            ClaudeTool(
                name="get_weather",
                description="Get current weather for a location",
                input_schema={
                    "type": "object",
                    "properties": {
                        "location": {
                            "type": "string",
                            "description": "The city name"
                        }
                    },
                    "required": ["location"]
                }
            )
        ],
        tool_choice={"type": "auto"},
        stream=False,
    )


@pytest.fixture
def sample_claude_request_with_tool_use():
    """Create a Claude request with previous tool use and tool results."""
    return ClaudeMessagesRequest(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1000,
        messages=[
            ClaudeMessage(
                role="user",
                content="What's the weather in Paris?"
            ),
            ClaudeMessage(
                role="assistant",
                content=[
                    {"type": "text", "text": "Let me check the weather for you."},
                    {"type": "tool_use", "id": "toolu_01", "name": "get_weather", "input": {"location": "Paris"}}
                ]
            ),
            ClaudeMessage(
                role="user",
                content=[
                    {"type": "tool_result", "tool_use_id": "toolu_01", "content": "The weather in Paris is 22°C and sunny."}
                ]
            ),
        ],
        stream=False,
    )


@pytest.fixture
def sample_claude_request_multimodal():
    """Create a Claude request with image content."""
    return ClaudeMessagesRequest(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1000,
        messages=[
            ClaudeMessage(
                role="user",
                content=[
                    {"type": "text", "text": "What do you see in this image?"},
                    {
                        "type": "image",
                        "source": {
                            "type": "base64",
                            "media_type": "image/png",
                            "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                        }
                    }
                ]
            ),
        ],
        stream=False,
    )


# ============================================================================
# Mock Fixtures
# ============================================================================

@pytest.fixture
def mock_openai_client():
    """Create a mock OpenAI client."""
    mock_client = Mock()
    mock_client.chat = Mock()
    mock_client.chat.completions = Mock()

    # Mock non-streaming response
    mock_completion = Mock()
    mock_completion.id = "chatcmpl-123"
    mock_completion.model_dump = Mock(return_value={
        "id": "chatcmpl-123",
        "object": "chat.completion",
        "created": 1234567890,
        "model": "gpt-4o",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": "Hello! I'm doing well, thank you for asking.",
            },
            "finish_reason": "stop",
        }],
        "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 20,
            "total_tokens": 30,
        },
    })
    mock_client.chat.completions.create = AsyncMock(return_value=mock_completion)

    # Mock streaming response
    async def mock_stream():
        chunks = [
            {"id": "chatcmpl-123", "choices": [{"delta": {"content": "Hello"}, "finish_reason": None}]},
            {"id": "chatcmpl-123", "choices": [{"delta": {"content": " world"}, "finish_reason": None}]},
            {"id": "chatcmpl-123", "choices": [{"delta": {"content": "!"}, "finish_reason": "stop"}]},
        ]
        for chunk in chunks:
            yield f"data: {chunk}"
        yield "data: [DONE]"

    mock_client.chat.completions.create = AsyncMock()
    mock_client.chat.completions.create.side_effect = [
        mock_completion,  # First call is non-streaming
        mock_stream(),    # Second call is streaming
    ]

    return mock_client


@pytest.fixture
def mock_http_request():
    """Create a mock FastAPI Request."""
    request = Mock(spec=FastAPIRequestStub)
    request.is_disconnected = AsyncMock(return_value=False)
    request.headers = {
        "x-api-key": "test-client-key",
        "anthropic-version": "2023-06-01",
    }
    return request


# ============================================================================
# Helper Classes
# ============================================================================

class FastAPIRequestStub:
    """Stub class for type hinting FastAPI Request."""
    pass


# ============================================================================
# Pytest Async Support
# ============================================================================

# Configure pytest-asyncio
@pytest.fixture(scope="session")
def event_loop_policy():
    """Use the default event loop policy for asyncio."""
    import asyncio
    return asyncio.DefaultEventLoopPolicy()