"""LLM-based Skill Router — selects the best Skill for a given prompt.

Supports Anthropic and OpenAI providers, configured via Settings.
When llm_provider is empty, this module is not used (rule-based fallback).
"""

from __future__ import annotations

import json
import logging
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from ..config import Settings
from ..models import SkillManifest

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT_TEMPLATE = """\
You are a skill router for a business automation system.
Given a user's request, decide which skill best handles it.

Available skills:
{skills_json}

Rules:
- Respond with ONLY the skill id (e.g. "exception_triage"), nothing else.
- If no skill matches the request, respond with exactly "NONE".
- Choose based on the skill description and the user's intent.
- If user provides context with a specific domain keyword (e.g. order_id), prefer skills in that domain."""


def _build_skills_json(skills: list[SkillManifest]) -> str:
    """Build a compact JSON summary of available skills for the system prompt."""
    entries = []
    for s in skills:
        entry: dict[str, Any] = {"id": s.id, "description": s.description}
        if s.input_schema:
            entry["input_hint"] = list(s.input_schema.get("properties", {}).keys())
        entries.append(entry)
    return json.dumps(entries, ensure_ascii=False, indent=2)


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
            "max_tokens": 64,
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
            "max_tokens": 64,
        }
        if settings.llm_api_key:
            kwargs["api_key"] = settings.llm_api_key
        if settings.llm_base_url:
            kwargs["base_url"] = settings.llm_base_url
        return ChatOpenAI(**kwargs)

    else:
        logger.warning("Unknown llm_provider: %s. LLM routing disabled.", provider)
        return None


class LlmSkillRouter:
    """Routes user prompts to the best matching Skill using an LLM."""

    def __init__(self, chat_model: BaseChatModel) -> None:
        self._model = chat_model

    def route(
        self,
        prompt: str,
        context: dict[str, Any] | None,
        skills: list[SkillManifest],
    ) -> str | None:
        """Ask the LLM which skill to invoke.

        Returns a skill_id string, or None if no skill matches.
        """
        if not skills:
            return None

        skills_json = _build_skills_json(skills)
        system = _SYSTEM_PROMPT_TEMPLATE.format(skills_json=skills_json)

        # Build the human message: prompt + context summary
        human_parts = [prompt]
        if context:
            human_parts.append(f"\nContext: {json.dumps(context, ensure_ascii=False)}")
        human = "\n".join(human_parts)

        try:
            response = self._model.invoke([
                SystemMessage(content=system),
                HumanMessage(content=human),
            ])
            raw = response.content.strip().strip('"').strip("'")
            logger.info("LLM skill routing: prompt=%s → %s", prompt[:80], raw)

            if raw.upper() == "NONE" or not raw:
                return None
            return raw

        except Exception:
            logger.warning("LLM skill routing failed, returning None", exc_info=True)
            return None
