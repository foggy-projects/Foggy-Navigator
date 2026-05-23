"""Build the initial ChatModel message array for a BizWorker LLM turn."""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

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
    """Convert BizWorker-owned root-visible protocol memory to role messages."""
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
        content = content.strip() if isinstance(content, str) else ""
        if role == "user":
            if not content:
                continue
            messages.append(HumanMessage(content=content.strip()))
        elif role == "assistant":
            tool_calls = _normalize_tool_calls(item.get("toolCalls"))
            if tool_calls:
                messages.append(AIMessage(content=content, tool_calls=tool_calls))
            elif content:
                messages.append(AIMessage(content=content))
        elif role == "tool":
            tool_call_id = item.get("toolCallId")
            if isinstance(tool_call_id, str) and tool_call_id:
                messages.append(ToolMessage(content=content, tool_call_id=tool_call_id))
    if not _has_valid_tool_protocol(messages):
        return []
    return messages


def _normalize_tool_calls(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        call_id = item.get("id")
        args = item.get("args")
        if not isinstance(name, str) or not name:
            continue
        if not isinstance(call_id, str) or not call_id:
            continue
        normalized.append({
            "name": name,
            "args": args if isinstance(args, dict) else {},
            "id": call_id,
        })
    return normalized


def _has_valid_tool_protocol(messages: list[Any]) -> bool:
    pending_tool_call_ids: set[str] = set()
    for message in messages:
        if isinstance(message, AIMessage):
            if pending_tool_call_ids:
                return False
            for tool_call in getattr(message, "tool_calls", []) or []:
                if isinstance(tool_call, dict) and isinstance(tool_call.get("id"), str):
                    pending_tool_call_ids.add(tool_call["id"])
        elif isinstance(message, ToolMessage):
            tool_call_id = getattr(message, "tool_call_id", None)
            if not isinstance(tool_call_id, str) or tool_call_id not in pending_tool_call_ids:
                return False
            pending_tool_call_ids.remove(tool_call_id)
        elif pending_tool_call_ids:
            return False
    return not pending_tool_call_ids
