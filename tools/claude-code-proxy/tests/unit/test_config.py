"""Unit tests for config.py module."""

import os
import pytest
from src.core.config import Config
from src.core.key_pool import BackendKey


@pytest.mark.unit
class TestConfig:
    """Test Config class initialization and functionality."""

    def test_config_initialization_defaults(self, clean_env):
        """Test Config initialization with default values."""
        # Set required environment variable
        os.environ["OPENAI_API_KEY"] = "sk-test-key"

        config = Config()

        assert config.openai_base_url == "https://api.openai.com/v1"
        assert config.azure_api_version is None
        assert config.host == "0.0.0.0"
        assert config.port == 8082
        assert config.log_level == "INFO"
        assert config.max_tokens_limit == 4096
        assert config.min_tokens_limit == 100
        assert config.request_timeout == 90
        assert config.max_retries == 2

    def test_config_custom_values(self, clean_env):
        """Test Config initialization with custom environment variables."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"
        os.environ["OPENAI_BASE_URL"] = "https://custom.api.com/v1"
        os.environ["HOST"] = "127.0.0.1"
        os.environ["PORT"] = "9000"
        os.environ["LOG_LEVEL"] = "DEBUG"
        os.environ["MAX_TOKENS_LIMIT"] = "8192"
        os.environ["MIN_TOKENS_LIMIT"] = "50"
        os.environ["REQUEST_TIMEOUT"] = "120"
        os.environ["MAX_RETRIES"] = "3"
        os.environ["AZURE_API_VERSION"] = "2024-02-01"

        config = Config()

        assert config.openai_base_url == "https://custom.api.com/v1"
        assert config.host == "127.0.0.1"
        assert config.port == 9000
        assert config.log_level == "DEBUG"
        assert config.max_tokens_limit == 8192
        assert config.min_tokens_limit == 50
        assert config.request_timeout == 120
        assert config.max_retries == 3
        assert config.azure_api_version == "2024-02-01"

    def test_config_truncation_settings(self, clean_env):
        """Test truncation-related configuration."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"
        os.environ["TRUNCATION_TOOL_RESULT_LIMIT"] = "50000"
        os.environ["TRUNCATION_KEEP_HEAD_MESSAGES"] = "6"
        os.environ["TRUNCATION_KEEP_TAIL_MESSAGES"] = "30"

        config = Config()

        assert config.truncation_tool_result_limit == 50000
        assert config.truncation_keep_head_messages == 6
        assert config.truncation_keep_tail_messages == 30

    def test_config_model_settings(self, clean_env):
        """Test model-related configuration."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"
        os.environ["BIG_MODEL"] = "gpt-4o"
        os.environ["MIDDLE_MODEL"] = "gpt-4o-mini"
        os.environ["SMALL_MODEL"] = "gpt-3.5-turbo"

        config = Config()

        assert config.big_model == "gpt-4o"
        assert config.middle_model == "gpt-4o-mini"
        assert config.small_model == "gpt-3.5-turbo"

    def test_config_single_key_mode(self, clean_env):
        """Test single key mode (legacy behavior)."""
        os.environ["OPENAI_API_KEY"] = "sk-single-key"

        config = Config()

        assert config.key_pool.has_mapping is False
        assert len(config.key_pool.keys) == 1
        assert "DEFAULT" in config.key_pool.keys
        assert config.openai_api_key == "sk-single-key"

    def test_config_multiple_keys_mode(self, clean_env):
        """Test multiple keys mode with OPENAI_API_KEY_* pattern."""
        os.environ["OPENAI_API_KEY_KEY1"] = "sk-key1"
        os.environ["OPENAI_API_KEY_KEY2"] = "sk-key2"
        os.environ["OPENAI_BASE_URL_KEY1"] = "https://api1.com"
        os.environ["BIG_MODEL_KEY1"] = "gpt-4"
        os.environ["SMALL_MODEL_KEY2"] = "gpt-3.5-turbo"

        config = Config()

        assert len(config.key_pool.keys) == 2
        assert "KEY1" in config.key_pool.keys
        assert "KEY2" in config.key_pool.keys
        assert config.key_pool.keys["KEY1"].base_url == "https://api1.com"
        assert config.key_pool.keys["KEY1"].big_model == "gpt-4"
        assert config.key_pool.keys["KEY2"].small_model == "gpt-3.5-turbo"
        assert config.openai_api_key == "sk-key1"  # First key in pool

    def test_config_passthrough_keys(self, clean_env):
        """Test passthrough key configuration."""
        os.environ["OPENAI_API_KEY_DASHSCOPE"] = "sk-dashscope"
        os.environ["PASSTHROUGH_DASHSCOPE"] = "true"

        config = Config()

        assert "DASHSCOPE" in config.key_pool.keys
        assert config.key_pool.keys["DASHSCOPE"].passthrough is True

    def test_config_key_mapping(self, clean_env):
        """Test KEY_MAPPING configuration."""
        os.environ["OPENAI_API_KEY_B1"] = "sk-b1"
        os.environ["OPENAI_API_KEY_B2"] = "sk-b2"
        os.environ["KEY_MAPPING"] = "client1:B1,B2;client2:B1"

        config = Config()

        assert config.key_pool.has_mapping is True
        assert "client1" in config.key_pool.mapping
        assert "client2" in config.key_pool.mapping
        assert config.key_pool.mapping["client1"] == ["B1", "B2"]
        assert config.key_pool.mapping["client2"] == ["B1"]

    def test_config_invalid_backend_in_mapping(self, clean_env, capsys):
        """Test that invalid backend names in KEY_MAPPING are skipped."""
        os.environ["OPENAI_API_KEY_B1"] = "sk-b1"
        os.environ["KEY_MAPPING"] = "client1:B1,nonexistent;client2:B2"

        config = Config()

        assert config.key_pool.has_mapping is True
        assert "client1" in config.key_pool.mapping
        assert config.key_pool.mapping["client1"] == ["B1"]
        assert "client2" not in config.key_pool.mapping

        captured = capsys.readouterr()
        assert "Warning: KEY_MAPPING client 'client2' has no valid backend keys" in captured.out

    def test_config_anthropic_api_key_validation(self, clean_env):
        """Test ANTHROPIC_API_KEY for client validation (legacy mode)."""
        os.environ["OPENAI_API_KEY"] = "sk-openai-key"
        os.environ["ANTHROPIC_API_KEY"] = 'sk-ant-test-key'

        config = Config()

        assert config.anthropic_api_key == "sk-ant-test-key"

    def test_config_anthropic_key_stripped_quotes(self, clean_env):
        """Test that quotes are stripped from ANTHROPIC_API_KEY."""
        os.environ["OPENAI_API_KEY"] = "sk-openai-key"
        os.environ['ANTHROPIC_API_KEY'] = '"sk-ant-test-key"'
        os.environ["QUOTED_KEY"] = "'sk-ant-quoted'"

        config = Config()

        assert config.anthropic_api_key == "sk-ant-test-key"

    def test_config_no_api_key_raises_error(self, clean_env):
        """Test that Config raises error when no API key is found."""
        with pytest.raises(ValueError, match="No OPENAI_API_KEY or OPENAI_API_KEY_.* found"):
            Config()

    def test_validate_api_key_success(self, clean_env):
        """Test validate_api_key with valid configuration."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"

        config = Config()
        assert config.validate_api_key() is True

    def test_validate_client_api_key_with_mapping(self, clean_env):
        """Test validate_client_api_key with KEY_MAPPING configured."""
        os.environ["OPENAI_API_KEY_B1"] = "sk-b1"
        os.environ["KEY_MAPPING"] = "client1:B1"

        config = Config()

        assert config.validate_client_api_key("client1") is True
        assert config.validate_client_api_key("unknown") is False

    def test_validate_client_api_key_legacy_mode(self, clean_env):
        """Test validate_client_api_key in legacy single-key mode."""
        os.environ["OPENAI_API_KEY"] = "sk-openai-key"
        os.environ["ANTHROPIC_API_KEY"] = "sk-ant-valid"

        config = Config()

        assert config.validate_client_api_key("sk-ant-valid") is True
        assert config.validate_client_api_key("sk-invalid") is False

    def test_validate_client_api_key_no_validation(self, clean_env, capsys):
        """Test that validation is skipped when neither mapping nor ANTHROPIC_API_KEY is set."""
        os.environ["OPENAI_API_KEY"] = "sk-openai-key"

        config = Config()

        # Any key should pass when no validation is configured
        assert config.validate_client_api_key("any-key") is True

    def test_get_custom_headers(self, clean_env):
        """Test get_custom_headers extracts CUSTOM_HEADER_* variables."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"
        os.environ["CUSTOM_HEADER_X_CUSTOM_HEADER"] = "custom-value"
        os.environ["CUSTOM_HEADER_AUTHORIZATION"] = "Bearer token"
        os.environ["NOT_A_HEADER"] = "should-be-ignored"

        config = Config()
        headers = config.get_custom_headers()

        # On Windows, env var names are uppercased by dict(os.environ)
        # The implementation converts underscores to hyphens
        assert headers == {
            "X-CUSTOM-HEADER": "custom-value",
            "AUTHORIZATION": "Bearer token",
        }

    def test_get_custom_headers_empty(self, clean_env):
        """Test get_custom_headers returns empty dict when no custom headers."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"

        config = Config()
        headers = config.get_custom_headers()

        assert headers == {}

    def test_underscore_to_hyphen_conversion_in_headers(self, clean_env):
        """Test that underscores in CUSTOM_HEADER names are converted to hyphens."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"
        os.environ["CUSTOM_HEADER_X_API_VERSION"] = "v1"
        os.environ["CUSTOM_HEADER_X_FEATURE_FLAG"] = "enabled"

        config = Config()
        headers = config.get_custom_headers()

        # Environment variable underscores are converted to hyphens
        assert headers["X-API-VERSION"] == "v1"
        assert headers["X-FEATURE-FLAG"] == "enabled"

    def test_openai_api_key_ignored_when_named_keys_exist(self, clean_env, capsys):
        """Test that OPENAI_API_KEY is ignored when OPENAI_API_KEY_* keys are found."""
        os.environ["OPENAI_API_KEY"] = "sk-should-be-ignored"
        os.environ["OPENAI_API_KEY_NAMED"] = "sk-named-key"

        config = Config()

        assert "DEFAULT" not in config.key_pool.keys
        assert "NAMED" in config.key_pool.keys

        captured = capsys.readouterr()
        assert "Note: OPENAI_API_KEY ignored because OPENAI_API_KEY_* keys were found" in captured.out

    def test_config_warning_message_no_validation(self, clean_env, capsys):
        """Test warning message when neither ANTHROPIC_API_KEY nor KEY_MAPPING is set."""
        os.environ["OPENAI_API_KEY"] = "sk-test-key"

        Config()

        captured = capsys.readouterr()
        assert "Warning: Neither ANTHROPIC_API_KEY nor KEY_MAPPING set" in captured.out