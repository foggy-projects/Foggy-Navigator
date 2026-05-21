"""Prompt construction helpers for the LLM skill agent."""

from __future__ import annotations

import datetime
import json
from typing import Any

from ..models import SkillManifest
from .attachment_context import build_attachment_context_prompt as _build_attachment_context_prompt
from .llm_tool_call_codec import _safe_content


_SYSTEM_ROOT_SKILL_ID = "system.root"
_PRIVATE_CONTEXT_KEYS = {
    "clientappid",
    "contextid",
    "conversationid",
    "credentialid",
    "llmconfig",
    "messageid",
    "navigatorsessionid",
    "rootagentid",
    "sessionid",
    "taskscopedtoken",
    "upstreamref",
    "upstreamuserid",
    "visionllmconfig",
    "workertoken",
}
_PRIVATE_CONTEXT_MARKERS = (
    "apikey",
    "accesskey",
    "accesstoken",
    "authorization",
    "credential",
    "password",
    "secret",
    "token",
)
_MODEL_VISIBLE_SKILL_KEYS = {"id", "name", "description"}
_SYSTEM_ROOT_IDENTITY_KEYS = {
    "businessskillid",
    "businessskillname",
    "childskillid",
    "rootskillid",
    "rootskillname",
    "skillid",
    "skillname",
}


def _build_system_prompt(manifest: SkillManifest, account_context_prompt: str = "") -> str:
    prompt = _build_system_identity_prompt(manifest)
    if account_context_prompt:
        prompt += f"\n---\n{account_context_prompt}\n---\n\n"
    if manifest.markdown_body:
        prompt += f"\n---\nSkill Instructions:\n{manifest.markdown_body}\n---\n\n"

    prompt += (
        "Business functions may be shown as `function_id@version`; when using "
        "invoke_business_function, pass them as `function_id` without the @version "
        "suffix and `version` separately. "
        "Navigator runtime identifiers such as skillId, functionId, frameId, "
        "skillFrameId, function_frame_id, taskId, sessionId, messageId, and "
        "values prefixed with frm_, lgt_, msg_, or sess_ are internal tracing "
        "ids. Use them only when reasoning about execution history or when the "
        "user explicitly asks for trace/debug identifiers; do not expose them "
        "in normal user-facing summaries, and never present them as an order "
        "number, waybill number, business document number, or proof that a "
        "formal order was created. Only call a value an order/waybill/business "
        "id when the tool output schema or result explicitly provides a public "
        "business identifier field such as orderNo, orderIdentifier, or waybillNo. "
        "For page-opening structured outputs, prefer the tool summary and action "
        "label; do not infer business success or business ids from page actions, "
        "buttons, Navigator frame ids, skill ids, or action metadata. "
        "If the skill references files under its bundle, use list_skill_resources "
        "or read_skill_resource; those tools only expose the current ClientApp's "
        "public skill resources. "
        "Use only the provided tools. When the skill is complete, call "
        "submit_skill_result. Natural-language completion is not accepted."
    )
    return prompt


def _build_system_identity_prompt(manifest: SkillManifest) -> str:
    if manifest.id == _SYSTEM_ROOT_SKILL_ID:
        description = (
            "Root orchestration agent for the current business conversation. "
            "Coordinate the current user turn, call business tools when needed, "
            "and return a concise result for the user."
        )
        return (
            "You are the root orchestration agent for this business conversation.\n"
            f"Description: {description}\n"
            f"Output schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
        )
    return (
        f"You are executing business skill {manifest.id}.\n"
        f"Description: {manifest.description}\n"
        f"Output schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
    )


def _build_user_prompt(
    prompt: str,
    skill_input: dict[str, Any],
    skill_id: str,
    runtime_context: dict[str, Any] | None = None,
) -> str:
    parts = [
        _build_turn_header(skill_id, runtime_context),
        _build_runtime_time_context_prompt(runtime_context),
        _build_recoverable_interruption_prompt(runtime_context, prompt),
        _build_awaiting_user_input_prompt(runtime_context, prompt),
        _build_active_plan_prompt(runtime_context),
        _build_root_planning_policy_prompt(runtime_context, skill_id),
        _build_frame_result_contract_prompt(runtime_context),
        _build_runtime_visible_conversation_prompt(runtime_context),
        f"User request: {prompt}",
        _build_model_visible_skill_input_prompt(skill_input),
        _build_attachment_context_prompt(_runtime_attachments(runtime_context)),
        _build_visible_context_prompt(runtime_context),
    ]
    return "\n".join(part for part in parts if part)


def _build_turn_header(skill_id: str, runtime_context: dict[str, Any] | None = None) -> str:
    if (runtime_context or {}).get("_persistent_frame") is True or skill_id == _SYSTEM_ROOT_SKILL_ID:
        return "Current root turn context:"
    return "Current business skill turn context:"


def _build_model_visible_skill_input_prompt(skill_input: dict[str, Any]) -> str:
    visible = model_visible_context(skill_input)
    if not visible:
        return ""
    return f"Business context: {json.dumps(visible, ensure_ascii=False, sort_keys=True)}"


def model_visible_context(value: Any) -> Any:
    """Return the model-visible projection of runtime context-like data."""
    if isinstance(value, dict):
        projected: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if _is_private_context_key(key_text):
                continue
            if _is_system_root_identity(key_text, item):
                continue
            if key in {"recentConversation", "recent_conversation", "attachments"}:
                continue
            if key == "allowed_skills" and isinstance(item, list):
                allowed_skills = _visible_allowed_skills(item)
                if allowed_skills:
                    projected[key] = allowed_skills
                continue
            projected_item = model_visible_context(item)
            if projected_item not in (None, {}, []):
                projected[str(key)] = projected_item
        return projected
    if isinstance(value, list):
        projected_list = []
        for item in value:
            projected_item = model_visible_context(item)
            if projected_item not in (None, {}, []):
                projected_list.append(projected_item)
        return projected_list
    return value


def _visible_allowed_skills(skills: list[Any]) -> list[dict[str, Any]]:
    visible: list[dict[str, Any]] = []
    for item in skills:
        if not isinstance(item, dict):
            continue
        if item.get("id") == _SYSTEM_ROOT_SKILL_ID:
            continue
        skill = {
            key: item[key]
            for key in _MODEL_VISIBLE_SKILL_KEYS
            if isinstance(item.get(key), str) and item.get(key).strip()
        }
        if skill.get("id"):
            visible.append(skill)
    return visible


def _is_private_context_key(key: str) -> bool:
    if key.startswith("_"):
        return True
    normalized = "".join(char for char in key.lower() if char.isalnum())
    if normalized in _PRIVATE_CONTEXT_KEYS:
        return True
    return any(marker in normalized for marker in _PRIVATE_CONTEXT_MARKERS)


def _is_system_root_identity(key: str, value: Any) -> bool:
    normalized = "".join(char for char in key.lower() if char.isalnum())
    if normalized not in _SYSTEM_ROOT_IDENTITY_KEYS:
        return False
    if isinstance(value, str):
        return value.strip().lower() == _SYSTEM_ROOT_SKILL_ID
    return False


def _runtime_attachments(runtime_context: dict[str, Any] | None) -> list[dict[str, Any]] | None:
    if not runtime_context:
        return None
    value = runtime_context.get("attachments")
    return value if isinstance(value, list) else None


def _build_runtime_visible_conversation_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    history = runtime_context.get("_runtime_visible_conversation")
    if isinstance(history, list):
        lines = _conversation_lines(history)
        if lines:
            return "Runtime-visible conversation before this turn:\n" + "\n".join(lines)
    return _build_visible_recent_conversation_prompt(runtime_context)


def _build_visible_recent_conversation_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    history = runtime_context.get("_visible_recent_conversation")
    if not isinstance(history, list):
        return ""
    lines = _conversation_lines(history)
    if not lines:
        return ""
    return "Recent conversation before this turn:\n" + "\n".join(lines)


def _conversation_lines(history: list[Any]) -> list[str]:
    lines: list[str] = []
    for item in history[-12:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "message").strip().lower()
        if role not in {"user", "assistant"}:
            continue
        content = item.get("content")
        if isinstance(content, str) and content.strip():
            lines.append(f"{role}: {content.strip()[:1200]}")
    return lines


def _recoverable_interruption_context(working_state: dict[str, Any]) -> dict[str, Any] | None:
    if working_state.get("continuation_state") != "INTERRUPTED":
        return None
    if not working_state.get("recoverable"):
        return None
    context = {
        "reason": working_state.get("interrupt_reason") or "unknown",
        "last_error": working_state.get("last_error") or "",
        "interrupted_at": working_state.get("interrupted_at") or "",
    }
    pending_child = working_state.get("pending_recoverable_child")
    if isinstance(pending_child, dict):
        context["pending_child_skill"] = model_visible_context(_safe_content(pending_child))
    recoverable_focus = working_state.get("recoverable_focus_summary")
    if isinstance(recoverable_focus, dict):
        context["recoverable_focus"] = model_visible_context(_safe_content(recoverable_focus))
    recoverable_focus_stack = working_state.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        context["recoverable_focus_stack"] = model_visible_context(_safe_content(recoverable_focus_stack))
    continuation_summary = _continuation_summary_context(working_state)
    if isinstance(continuation_summary, dict):
        context["continuation_summary"] = model_visible_context(_safe_content(continuation_summary))
    return context


def _continuation_summary_context(working_state: dict[str, Any]) -> dict[str, Any] | None:
    summary = working_state.get("latest_child_result_summary")
    if isinstance(summary, dict):
        return summary
    root_summary = working_state.get("root_context_summary")
    if not isinstance(root_summary, dict):
        return None
    summary = root_summary.get("latest_child_result_summary")
    if isinstance(summary, dict):
        return summary
    summaries = root_summary.get("child_result_summaries")
    if isinstance(summaries, list):
        for item in reversed(summaries):
            if isinstance(item, dict):
                return item
    return None


def _active_plan_context(working_state: dict[str, Any]) -> Any | None:
    active_plan = working_state.get("active_plan")
    if isinstance(active_plan, (dict, list)) and active_plan:
        return _safe_content(active_plan)
    return None


def _build_recoverable_interruption_prompt(
    runtime_context: dict[str, Any] | None,
    prompt: str,
) -> str:
    if not runtime_context:
        return ""
    interruption = runtime_context.get("_recoverable_interruption")
    if not isinstance(interruption, dict):
        return ""
    parts = [
        "Previous execution was interrupted.",
        f"Reason: {interruption.get('reason') or 'unknown'}",
    ]
    last_error = interruption.get("last_error")
    if last_error:
        parts.append(f"Last error: {last_error}")
    pending_child = interruption.get("pending_child_skill")
    if isinstance(pending_child, dict):
        parts.append(
            "Pending child skill: "
            f"{json.dumps(pending_child, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus = interruption.get("recoverable_focus")
    if isinstance(recoverable_focus, dict):
        parts.append(
            "Recoverable focus: "
            f"{json.dumps(recoverable_focus, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus_stack = interruption.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        parts.append(
            "Recoverable focus stack: "
            f"{json.dumps(recoverable_focus_stack, ensure_ascii=False, sort_keys=True)}"
        )
    continuation_summary = interruption.get("continuation_summary")
    if isinstance(continuation_summary, dict):
        parts.append(
            "Continuation summary from promoted child result: "
            f"{json.dumps(continuation_summary, ensure_ascii=False, sort_keys=True)}"
        )
    parts.append(f"User's new instruction: {prompt}")
    parts.append(
        "The interrupted work is a recoverable candidate, not a mandatory "
        "continuation. First resolve intent_resolution as one of "
        "CONTINUE_PREVIOUS, ABANDON_PREVIOUS, START_UNRELATED_NEW_TASK, or "
        "ASK_CLARIFICATION. If the new instruction explicitly continues, "
        "corrects, or supplements the interrupted work, use CONTINUE_PREVIOUS "
        "and continue from the existing frame context. If there is a pending "
        "child skill, use resume_recoverable_child_skill so the same child "
        "frame continues. If the user explicitly stops/cancels it, use "
        "ABANDON_PREVIOUS. If the user asks for an unrelated new task, use "
        "START_UNRELATED_NEW_TASK. For either shelving case, summarize what "
        "is being abandoned, then use shelve_interrupted_frame with decision "
        "set to ABANDON_PREVIOUS or START_UNRELATED_NEW_TASK, include "
        "intent_resolution, and include an abandoned_interruption summary. "
        "If the intent is ambiguous and the interrupted work involves approval "
        "or business side effects, use ASK_CLARIFICATION and ask for "
        "clarification via submit_skill_result."
    )
    return "\n".join(parts)


def _build_awaiting_user_input_prompt(
    runtime_context: dict[str, Any] | None,
    prompt: str,
) -> str:
    if not runtime_context:
        return ""
    awaiting = runtime_context.get("_awaiting_user_input")
    if not isinstance(awaiting, dict):
        return ""
    parts = [
        "Previous child skill turn is waiting for user input.",
        "Awaiting-user context:",
        json.dumps(model_visible_context(awaiting), ensure_ascii=False, sort_keys=True),
        f"Current user reply: {prompt}",
        (
            "Rule: Continue this same unfinished frame. Treat the current user "
            "reply as the answer to the previous user-facing prompt unless the "
            "reply explicitly cancels or changes the request."
        ),
    ]
    return "\n".join(parts)


def _build_frame_result_contract_prompt(runtime_context: dict[str, Any] | None) -> str:
    return (
        "Frame result contract: Treat the result returned by invoke_business_skill "
        "or resume_recoverable_child_skill as the primary business-decision "
        "context from that child frame, including status, next_step, "
        "missing_fields, structured_output, artifact_refs, and evidence_refs. "
        "A continuation summary injected after interruption is derived from the "
        "same promoted child result and should be interpreted consistently with "
        "the normal tool result. Do not call read_frame_execution_report after "
        "a normal child completion just to recover those fields. Use "
        "read_frame_execution_report only when the user asks how the frame ran, "
        "when debugging/auditing execution, or as a fallback if the promoted "
        "result/continuation summary lacks a field required for the next "
        "business decision."
    )


def _build_active_plan_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    active_plan = runtime_context.get("_active_plan")
    if not isinstance(active_plan, (dict, list)):
        return ""
    return "\n".join([
        "Active task plan:",
        json.dumps(active_plan, ensure_ascii=False, sort_keys=True),
        (
            "Rule: Treat active_plan as the current persistent root working plan. "
            "Before finalizing this turn, compare the intended result against the "
            "plan. If the plan is still useful, preserve or update it in "
            "submit_skill_result.structured_output.active_plan. If the user "
            "explicitly abandons it or starts an unrelated task, set "
            "intent_resolution to ABANDON_PREVIOUS or START_UNRELATED_NEW_TASK "
            "and summarize the abandoned plan."
        ),
    ])


def _build_root_planning_policy_prompt(
    runtime_context: dict[str, Any] | None,
    skill_id: str,
) -> str:
    if not runtime_context or runtime_context.get("_persistent_frame") is not True:
        return ""
    return (
        "Persistent root planning policy: For complex, multi-intent, multi-skill, "
        "or externally coordinated work, maintain an active_plan in "
        "submit_skill_result.structured_output.active_plan. The plan should be "
        "compact, structured, and updated as work progresses; it is working "
        "state for future turns, not user-facing narration."
    )


def _build_visible_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    summary = runtime_context.get("_visible_root_context_summary")
    if not isinstance(summary, dict):
        return ""
    return (
        "Visible parent/root context summary:\n"
        f"{json.dumps(model_visible_context(summary), ensure_ascii=False, sort_keys=True)}"
    )


def _build_runtime_time_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    time_context = _runtime_time_context(runtime_context)
    return (
        "Runtime context:\n"
        f"- current_time: {time_context['current_time']}\n"
        f"- timezone: {time_context['timezone']}\n"
        f"- business_date: {time_context['business_date']}\n"
        f"- current_month_range: [{time_context['current_month_start']}, {time_context['next_month_start']})\n"
        "Rule: Resolve relative dates such as 本月, 今天, 昨日, 近7天 using this runtime context."
    )


def _runtime_time_context(runtime_context: dict[str, Any] | None) -> dict[str, str]:
    context = runtime_context or {}
    current_time = _runtime_context_str(context, "current_time", "currentTime")
    timezone_name = _runtime_context_str(context, "timezone", "timeZone", "tz") or _local_timezone_name()

    if current_time:
        now = _parse_runtime_datetime(current_time) or datetime.datetime.now().astimezone()
    else:
        now = datetime.datetime.now().astimezone()
        current_time = now.isoformat()

    business_date = _runtime_context_str(context, "business_date", "businessDate")
    if not business_date:
        business_date = now.date().isoformat()

    business_day = _parse_runtime_date(business_date) or now.date()
    current_month_start, next_month_start = _month_range_for(business_day)

    return {
        "current_time": current_time,
        "timezone": timezone_name,
        "business_date": business_day.isoformat(),
        "current_month_start": current_month_start.isoformat(),
        "next_month_start": next_month_start.isoformat(),
    }


def _runtime_context_str(context: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _local_timezone_name() -> str:
    tzinfo = datetime.datetime.now().astimezone().tzinfo
    if tzinfo is None:
        return "local"
    return getattr(tzinfo, "key", None) or tzinfo.tzname(None) or str(tzinfo)


def _parse_runtime_datetime(value: str) -> datetime.datetime | None:
    try:
        normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
        return datetime.datetime.fromisoformat(normalized)
    except ValueError:
        return None


def _parse_runtime_date(value: str) -> datetime.date | None:
    try:
        return datetime.date.fromisoformat(value[:10])
    except ValueError:
        return None


def _month_range_for(day: datetime.date) -> tuple[datetime.date, datetime.date]:
    current_month_start = day.replace(day=1)
    if current_month_start.month == 12:
        next_month_start = current_month_start.replace(
            year=current_month_start.year + 1,
            month=1,
        )
    else:
        next_month_start = current_month_start.replace(month=current_month_start.month + 1)
    return current_month_start, next_month_start
