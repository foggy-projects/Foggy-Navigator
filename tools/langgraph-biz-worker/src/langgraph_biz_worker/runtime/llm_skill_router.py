"""Chat model factory helpers for BizWorker LLM execution."""

from __future__ import annotations

import logging
from typing import Any

from langchain_core.language_models import BaseChatModel

from ..config import Settings
from .model_runtime_budget import resolve_model_runtime_budget

logger = logging.getLogger(__name__)


def create_chat_model(settings: Settings) -> BaseChatModel | None:
    """Create a LangChain chat model from settings. Returns None if not configured."""
    provider = settings.llm_provider.lower().strip()
    if not provider:
        return None

    if provider == "anthropic":
        from langchain_anthropic import ChatAnthropic

        kwargs: dict[str, Any] = {
            "model": settings.llm_model or "claude-sonnet-4-20250514",
            "temperature": settings.llm_temperature,
            "max_tokens": settings.llm_max_tokens,
            "timeout": settings.llm_request_timeout_seconds,
            "max_retries": max(0, settings.llm_provider_max_retries),
        }
        if settings.llm_api_key:
            kwargs["api_key"] = settings.llm_api_key
        if settings.llm_base_url:
            kwargs["base_url"] = settings.llm_base_url
        return ChatAnthropic(**kwargs)

    elif provider == "openai":
        from langchain_openai import ChatOpenAI

        kwargs = {
            "model": settings.llm_model or "gpt-4o",
            "temperature": settings.llm_temperature,
            "max_tokens": settings.llm_max_tokens,
            "timeout": settings.llm_request_timeout_seconds,
            "max_retries": max(0, settings.llm_provider_max_retries),
        }
        if settings.llm_api_key:
            kwargs["api_key"] = settings.llm_api_key
        if settings.llm_base_url:
            kwargs["base_url"] = settings.llm_base_url
        return ChatOpenAI(**kwargs)

    else:
        logger.warning("Unknown llm_provider: %s. LLM routing disabled.", provider)
        return None


def create_chat_model_from_config(config: dict[str, Any] | None) -> BaseChatModel | None:
    """Create a per-request chat model from Navigator-resolved model config."""
    if not config:
        return None

    provider = _text(config.get("provider")).lower()
    if not provider:
        return None

    try:
        temperature = float(config.get("temperature", 0.0) or 0.0)
    except (TypeError, ValueError):
        temperature = 0.0

    settings_kwargs: dict[str, Any] = {
        "llm_provider": provider,
        "llm_api_key": _text(config.get("api_key")),
        "llm_base_url": _text(config.get("base_url")),
        "llm_model": _text(config.get("model")),
        "llm_temperature": temperature,
    }
    request_timeout = _optional_float(
        config,
        "request_timeout_seconds",
        "timeout_seconds",
        "timeout",
    )
    if request_timeout is not None:
        settings_kwargs["llm_request_timeout_seconds"] = request_timeout
    provider_max_retries = _optional_int(config, "provider_max_retries", "llm_provider_max_retries")
    if provider_max_retries is not None:
        settings_kwargs["llm_provider_max_retries"] = provider_max_retries

    max_tokens = _optional_int(config, "max_tokens", "llm_max_tokens", "max_tokens_limit")
    if max_tokens is None:
        budget = resolve_model_runtime_budget(config)
        max_tokens = _optional_int(budget, "max_output_tokens")
    if max_tokens is not None:
        settings_kwargs["llm_max_tokens"] = max_tokens

    request_settings = Settings(**settings_kwargs)
    return create_chat_model(request_settings)


def _text(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip()
    return text


def _optional_float(source: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        value = source.get(key)
        if value in (None, ""):
            continue
        try:
            parsed = float(value)
        except (TypeError, ValueError):
            return None
        return parsed if parsed > 0 else None
    return None


def _optional_int(source: dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        value = source.get(key)
        if value in (None, ""):
            continue
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return None
        return max(0, parsed)
    return None
