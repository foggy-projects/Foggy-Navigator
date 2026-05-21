"""Build the initial ChatModel message array for a BizWorker LLM turn."""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from ..models import SkillManifest
from .llm_agent_prompts import _build_system_prompt, _build_user_prompt
from .runtime_message_event_log import restore_runtime_protocol_messages


def build_initial_llm_messages(
    *,
    manifest: SkillManifest,
    prompt: str,
    skill_input: dict[str, Any],
    account_context_prompt: str = "",
    runtime_context: dict[str, Any] | None = None,
) -> list[Any]:
    """Return the canonical initial messages submitted to the chat model.

    The order is part of the runtime contract:
    system governance/context, bounded semantic history, then current user input.
    Tool calls produced during the loop are appended by ``LlmSkillAgent``.
    """
    messages: list[Any] = [
        SystemMessage(content=_build_system_prompt(
            manifest,
            account_context_prompt,
            skill_input=skill_input,
            skill_id=manifest.id,
            runtime_context=runtime_context,
        )),
    ]
    recovered_messages = runtime_protocol_recovery_messages(runtime_context)
    if recovered_messages:
        messages.extend(recovered_messages)
    else:
        messages.extend(runtime_visible_conversation_messages(runtime_context))
    messages.append(HumanMessage(content=_build_user_prompt(
        prompt,
        skill_input,
        manifest.id,
        runtime_context,
    )))
    return messages


def runtime_protocol_recovery_messages(runtime_context: dict[str, Any] | None) -> list[Any]:
    """Return restored provider protocol messages for unfinished frame recovery."""
    if not runtime_context:
        return []
    recovery = runtime_context.get("_runtime_protocol_recovery")
    if not isinstance(recovery, dict) or not recovery.get("enabled"):
        return []
    frame_id = recovery.get("frame_id") or runtime_context.get("_runtime_protocol_recovery_frame_id")
    if not isinstance(frame_id, str) or not frame_id:
        return []
    checkpoint = recovery.get("checkpoint")
    return restore_runtime_protocol_messages(
        runtime_context,
        frame_id=frame_id,
        checkpoint=checkpoint if isinstance(checkpoint, str) and checkpoint else None,
    )


def runtime_visible_conversation_messages(runtime_context: dict[str, Any] | None) -> list[Any]:
    """Convert BizWorker-owned semantic conversation memory to role messages."""
    if not runtime_context:
        return []
    history = runtime_context.get("_runtime_visible_conversation")
    if not isinstance(history, list):
        history = runtime_context.get("_visible_recent_conversation")
    if not isinstance(history, list):
        return []

    messages: list[Any] = []
    for item in history:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip().lower()
        content = item.get("content")
        if not isinstance(content, str) or not content.strip():
            continue
        if role == "user":
            messages.append(HumanMessage(content=content.strip()))
        elif role == "assistant":
            messages.append(AIMessage(content=content.strip()))
    return messages
