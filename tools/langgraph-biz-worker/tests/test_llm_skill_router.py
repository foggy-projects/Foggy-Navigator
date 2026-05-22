"""Tests for chat model factory helpers."""

from __future__ import annotations

from unittest.mock import patch

from langgraph_biz_worker.runtime.llm_skill_router import (
    create_chat_model,
    create_chat_model_from_config,
)


class TestCreateChatModel:
    def test_empty_provider_returns_none(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="")
        assert create_chat_model(s) is None

    def test_unknown_provider_returns_none(self):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="unknown_vendor")
        assert create_chat_model(s) is None

    @patch("langchain_anthropic.ChatAnthropic")
    def test_anthropic_provider(self, mock_cls):
        from langgraph_biz_worker.config import Settings
        s = Settings(llm_provider="anthropic", llm_api_key="sk-test", llm_model="claude-sonnet-4-20250514")
        result = create_chat_model(s)
        assert result is not None
        mock_cls.assert_called_once()

    @patch("langchain_openai.ChatOpenAI")
    def test_openai_provider(self, mock_cls):
        from langgraph_biz_worker.config import Settings
        s = Settings(
            llm_provider="openai",
            llm_api_key="sk-test",
            llm_model="gpt-4o",
            llm_request_timeout_seconds=45,
            llm_provider_max_retries=0,
        )
        result = create_chat_model(s)
        assert result is not None
        mock_cls.assert_called_once()
        kwargs = mock_cls.call_args.kwargs
        assert kwargs["timeout"] == 45
        assert kwargs["max_retries"] == 0

    @patch("langchain_openai.ChatOpenAI")
    def test_request_config_openai_provider(self, mock_cls):
        result = create_chat_model_from_config({
            "provider": "openai",
            "api_key": "sk-test",
            "base_url": "http://mock-llm:8000",
            "model": "navigator-e2e-scripted",
            "request_timeout_seconds": 12,
            "provider_max_retries": 0,
        })

        assert result is not None
        mock_cls.assert_called_once()
        kwargs = mock_cls.call_args.kwargs
        assert kwargs["api_key"] == "sk-test"
        assert kwargs["base_url"] == "http://mock-llm:8000"
        assert kwargs["model"] == "navigator-e2e-scripted"
        assert kwargs["timeout"] == 12
        assert kwargs["max_retries"] == 0
