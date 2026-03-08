"""Unit tests for model_manager.py module."""

import pytest
from src.core.model_manager import ModelManager
from src.core.config import Config
from src.core.key_pool import BackendKey


@pytest.mark.unit
class TestModelManager:
    """Test ModelManager class functionality."""

    def test_model_manager_initialization(self, sample_config):
        """Test ModelManager initialization with config."""
        manager = ModelManager(sample_config)

        assert manager.config is sample_config
        assert manager.config.big_model == "gpt-4o"
        assert manager.config.small_model == "gpt-4o-mini"

    def test_map_claude_haiku_to_small(self, sample_model_manager):
        """Test mapping Claude Haiku models to small model."""
        haiku_models = [
            "claude-3-haiku-20240307",
            "claude-3-5-haiku-20241022",
            "claude-3-haiku",
            "Claude-3-5-Haiku",  # Case insensitive
        ]

        for model in haiku_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == "gpt-4o-mini"

    def test_map_claude_sonnet_to_middle(self, sample_model_manager):
        """Test mapping Claude Sonnet models to middle model."""
        sonnet_models = [
            "claude-3-sonnet-20240229",
            "claude-3-5-sonnet-20241022",
            "claude-3-sonnet",
            "Claude-3-5-Sonnet",  # Case insensitive
        ]

        for model in sonnet_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == "gpt-4o"  # In sample config, middle = big

    def test_map_claude_opus_to_big(self, sample_model_manager):
        """Test mapping Claude Opus models to big model."""
        opus_models = [
            "claude-3-opus-20240229",
            "claude-opus-4-6",  # New Opus 4.6
            "claude-opus",
            "Claude-Opus-4-6",  # Case insensitive
        ]

        for model in opus_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == "gpt-4o"

    def test_map_openai_models_passthrough(self, sample_model_manager):
        """Test that OpenAI models are passed through unchanged."""
        gpt_models = [
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-3.5-turbo",
            "gpt-4",
        ]

        for model in gpt_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == model  # Unchanged

    def test_map_o1_models_passthrough(self, sample_model_manager):
        """Test that O1 models are passed through unchanged."""
        o1_models = [
            "o1-preview",
            "o1-mini",
        ]

        for model in o1_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == model

    def test_map_other_models_passthrough(self, sample_model_manager):
        """Test that other supported models are passed through unchanged."""
        other_models = [
            "ep-20250416175800-l709p",  # ARK
            "doubao-pro-32k",  # Doubao
            "doubao-lite-32k",
            "deepseek-chat",  # DeepSeek
            "deepseek-coder",
        ]

        for model in other_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == model

    def test_map_unknown_claude_model_to_big(self, sample_model_manager):
        """Test that unknown Claude models default to big model."""
        unknown_models = [
            "claude-unknown",
            "claude-4-future",
            "some-unknown-model",
        ]

        for model in unknown_models:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == "gpt-4o"  # Default to big

    def test_map_with_backend_key_big(self, sample_model_manager):
        """Test mapping with backend_key using its big_model."""
        backend_key = BackendKey(
            name="custom",
            api_key="sk-custom",
            base_url="https://api.com",
            big_model="gpt-4-turbo",
            middle_model="gpt-4",
            small_model="gpt-3.5-turbo",
        )

        result = sample_model_manager.map_claude_model_to_openai("claude-3-opus-20240229", backend_key)
        assert result == "gpt-4-turbo"  # Uses backend_key.big_model

    def test_map_with_backend_key_middle(self, sample_model_manager):
        """Test mapping with backend_key using its middle_model."""
        backend_key = BackendKey(
            name="custom",
            api_key="sk-custom",
            base_url="https://api.com",
            big_model="gpt-4-turbo",
            middle_model="gpt-4",
            small_model="gpt-3.5-turbo",
        )

        result = sample_model_manager.map_claude_model_to_openai("claude-3-5-sonnet-20241022", backend_key)
        assert result == "gpt-4"  # Uses backend_key.middle_model

    def test_map_with_backend_key_small(self, sample_model_manager):
        """Test mapping with backend_key using its small_model."""
        backend_key = BackendKey(
            name="custom",
            api_key="sk-custom",
            base_url="https://api.com",
            big_model="gpt-4-turbo",
            middle_model="gpt-4",
            small_model="gpt-3.5-turbo",
        )

        result = sample_model_manager.map_claude_model_to_openai("claude-3-haiku-20240307", backend_key)
        assert result == "gpt-3.5-turbo"  # Uses backend_key.small_model

    def test_map_with_backend_key_openai_passthrough(self, sample_model_manager):
        """Test that OpenAI models pass through even with backend_key."""
        backend_key = BackendKey(
            name="custom",
            api_key="sk-custom",
            base_url="https://api.com",
            big_model="gpt-4-turbo",
            middle_model="gpt-4",
            small_model="gpt-3.5-turbo",
        )

        result = sample_model_manager.map_claude_model_to_openai("gpt-4o", backend_key)
        assert result == "gpt-4o"  # Unchanged

    def test_map_without_backend_key_uses_global(self, sample_model_manager):
        """Test mapping without backend_key uses global config."""
        result = sample_model_manager.map_claude_model_to_openai("claude-3-opus-20240229")
        assert result == sample_model_manager.config.big_model

    def test_claude_model_variations(self, sample_model_manager):
        """Test various Claude model name variations."""
        test_cases = [
            # (input, expected_output)
            ("claude-3-opus-20240229", "gpt-4o"),
            ("claude-3-sonnet-20240229", "gpt-4o"),
            ("claude-3-haiku-20240307", "gpt-4o-mini"),
            ("claude-3-5-sonnet-20241022", "gpt-4o"),
            ("claude-opus-4-6", "gpt-4o"),
            ("claude-3-5-opus-20241022", "gpt-4o"),
        ]

        for input_model, expected in test_cases:
            result = sample_model_manager.map_claude_model_to_openai(input_model)
            assert result == expected

    def test_model_names_case_insensitive(self, sample_model_manager):
        """Test that model names are case-insensitive."""
        test_cases = [
            "CLAUDE-3-OPUS",
            "Claude-3-Opus",
            "cLaUdE-3-oPuS",
            "CLAUDE-3-5-SONNET",
            "Claude-3-5-Sonnet",
            "CLAUDE-3-HAIKU",
        ]

        for model in test_cases:
            result = sample_model_manager.map_claude_model_to_openai(model)
            # Should not raise error
            assert result in ["gpt-4o", "gpt-4o-mini"]

    def test_model_manager_with_different_config(self):
        """Test ModelManager with custom config values."""
        config = Config.__new__(Config)
        config.big_model = "gpt-4-turbo"
        config.middle_model = "gpt-4"
        config.small_model = "gpt-3.5-turbo"

        manager = ModelManager(config)

        assert manager.map_claude_model_to_openai("claude-3-opus") == "gpt-4-turbo"
        assert manager.map_claude_model_to_openai("claude-3-sonnet") == "gpt-4"
        assert manager.map_claude_model_to_openai("claude-3-haiku") == "gpt-3.5-turbo"

    def test_global_model_manager_instance(self, sample_config):
        """Test that global model_manager instance exists."""
        from src.core.model_manager import model_manager

        assert model_manager is not None
        assert isinstance(model_manager, ModelManager)

    def test_map_partial_model_name(self, sample_model_manager):
        """Test mapping with partial model names."""
        # These should still work based on substring matching
        partial_cases = [
            ("my-claude-3-opus-model", "gpt-4o"),  # Contains "opus"
            ("claude-3-sonnet-custom", "gpt-4o"),  # Contains "sonnet"
            ("custom-haiku-3", "gpt-4o-mini"),  # Contains "haiku"
        ]

        for model, expected in partial_cases:
            result = sample_model_manager.map_claude_model_to_openai(model)
            assert result == expected

    def test_backend_key_none_uses_default(self, sample_model_manager):
        """Test that None backend_key uses default behavior."""
        result = sample_model_manager.map_claude_model_to_openai("claude-3-opus", None)
        assert result == "gpt-4o"  # Uses global config

    def test_model_mapping_is_deterministic(self, sample_model_manager):
        """Test that model mapping is deterministic."""
        models = [
            "claude-3-opus-20240229",
            "claude-3-sonnet-20240229",
            "claude-3-haiku-20240307",
        ]

        for model in models:
            result1 = sample_model_manager.map_claude_model_to_openai(model)
            result2 = sample_model_manager.map_claude_model_to_openai(model)
            assert result1 == result2