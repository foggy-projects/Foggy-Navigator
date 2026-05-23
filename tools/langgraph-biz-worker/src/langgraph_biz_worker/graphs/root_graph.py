"""Root Graph for LangGraph Biz Worker.

Phase 1: minimal single-node graph returning fixed results.
Phase 3+: routes to Skill subgraphs via SkillRuntime Frame lifecycle.
"""

from __future__ import annotations

import operator
import time
from pathlib import Path
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, StateGraph

from ..config import settings
from ..models import FrameKind, FrameStatus, QueryEvent, SkillManifest
from ..runtime.context_memory import (
    PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY,
    RUNTIME_VISIBLE_CONVERSATION_KEY,
    ContextRuntimeBusy,
    assistant_visible_content,
    context_state_lock,
    load_from_root_frame,
    save_to_root_frame,
)
from ..runtime.context_compaction_summarizer import build_runtime_compaction_summarizer
from ..runtime.account_workspace import resolve_account_workspace
from ..runtime.execution_policy import ExecutionPolicy, copy_execution_policy_from_context, strip_execution_policy_context
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.file_layout import (
    date_parts_for_frame,
    generate_standard_context_id,
    optional_standard_context_id,
    require_standard_context_id,
)
from ..runtime.frame_store import FrameStore
from ..runtime.llm_skill_router import create_chat_model, create_chat_model_from_config
from ..runtime.llm_skill_agent import LlmSkillAgent
from ..runtime.llm_agent_prompts import model_visible_context
from ..runtime.model_runtime_budget import resolve_model_runtime_budget
from ..runtime.llm_child_recovery import (
    _context_skill_manifest,
    _direct_child_result_for_user,
    _record_parent_child_recoverable_interruption,
    _runtime_context_for_child_agent,
    _runtime_context_for_child_skill,
)
from ..runtime.skill_identity import (
    SKILL_NAME_ALIASES,
    SkillNameValidationError,
    normalize_skill_name,
    validate_skill_name,
)
from ..runtime.skill_registry import SkillRegistry
from ..runtime.skill_runtime import SkillRuntime
from .skills.exception_triage import (
    TriageState,
    analyze,
    invoke_evidence_child,
    invoke_rule_check_child,
    submit_result,
)


# ---------------------------------------------------------------------------
# Shared runtime instances (singleton per process)
# ---------------------------------------------------------------------------

# File journal for Frame persistence (Doc 31 §16.3)
# Uses data_root from config, falls back to <project>/data
_data_root = settings.data_root or str(Path(__file__).resolve().parent.parent.parent.parent / "data")

_frame_store = FrameStore()
_skill_registry = SkillRegistry(data_root=Path(_data_root))
_skill_registry.load()
_journal = FileFrameJournal(_data_root)

_runtime = SkillRuntime(frame_store=_frame_store, skill_registry=_skill_registry, journal=_journal)

ROOT_SKILL_ID = "system.root"
ROOT_FRAME_OPEN_CONTENT = "Opening conversation root frame"
ROOT_FRAME_REUSE_CONTENT = "Reusing conversation root frame"

# LLM-based Skill Router (None if llm_provider is empty → rule-based fallback)
_chat_model = create_chat_model(settings)
_llm_skill_agent: LlmSkillAgent | None = (
    LlmSkillAgent(_chat_model, _runtime, settings.llm_skill_max_iterations, data_root=Path(_data_root))
    if _chat_model and settings.llm_execute_skills
    else None
)


def _account_id_from_state(state: RootState) -> str | None:
    context = state.get("context") or {}
    return (
        context.get("account_id")
        or context.get("accountId")
        or context.get("upstream_user_id")
        or context.get("upstreamUserId")
        or state.get("user_id")
    )


def _explicit_skill_name_from_state(state: RootState) -> str | None:
    context = state.get("context") or {}
    values: dict[str, Any] = {}
    if state.get("skill_name") is not None:
        values["skill_name"] = state.get("skill_name")
    for alias in SKILL_NAME_ALIASES:
        if alias in context:
            values[alias] = context[alias]
    if values:
        return normalize_skill_name(values, required=False)

    legacy = context.get("skill")
    if legacy is None:
        return None
    return validate_skill_name(legacy, "skill")


def _skill_agent_prompt(prompt: str, context: dict[str, Any]) -> str:
    instruction = context.get("skill_instruction")
    if isinstance(instruction, str) and instruction.strip():
        return instruction.strip()
    return prompt


def _context_with_visible_skill_descriptions(context: dict[str, Any]) -> dict[str, Any]:
    enriched = dict(context)
    allowed_skills = context.get("allowed_skills")
    if not isinstance(allowed_skills, list):
        return enriched
    enriched_skills: list[Any] = []
    for item in allowed_skills:
        if not isinstance(item, dict):
            enriched_skills.append(item)
            continue
        skill = dict(item)
        skill_id = skill.get("id")
        manifest = _skill_registry.get_manifest(skill_id) if isinstance(skill_id, str) else None
        if manifest:
            skill.setdefault("name", manifest.name or manifest.id)
            if getattr(manifest, "description", ""):
                skill.setdefault("description", manifest.description)
        enriched_skills.append(skill)
    enriched["allowed_skills"] = enriched_skills
    return enriched


def _sync_memory_limits_from_runtime_context(memory: Any, runtime_context: dict[str, Any] | None) -> None:
    if not isinstance(runtime_context, dict):
        return
    llm_config = runtime_context.get("llm_config")
    if not isinstance(llm_config, dict):
        return
    budget = resolve_model_runtime_budget(llm_config)
    runtime_context["_runtime_budget"] = budget
    max_input_tokens = _positive_int(budget.get("max_input_tokens"))
    compact_threshold_tokens = _positive_int(budget.get("auto_compact_input_token_threshold"))
    context_window_tokens = _positive_int(budget.get("context_window_tokens"))
    max_output_tokens = _positive_int(budget.get("max_output_tokens"))
    prompt_reserve_output_tokens = _positive_int(budget.get("prompt_reserve_output_tokens"))
    prompt_reserve_system_tokens = _positive_int(budget.get("prompt_reserve_system_tokens"))
    max_tool_result_tokens = _positive_int(budget.get("max_single_tool_result_tokens"))
    max_tool_result_chars = _positive_int(budget.get("max_single_tool_result_chars"))
    project_historical_tool_results = _bool_or_none(budget.get("project_historical_tool_results"))
    raw_tool_result_tail_turn_count = _positive_int(budget.get("raw_tool_result_tail_turn_count"))
    compaction_head_turn_count = _positive_int(budget.get("compaction_head_turn_count"))
    compaction_tail_turn_count = _positive_int(budget.get("compaction_tail_turn_count"))
    max_compaction_summary_chars = _positive_int(budget.get("max_compaction_summary_chars"))
    max_prompt_messages = _positive_int(budget.get("max_prompt_messages"))
    max_visible_messages = _positive_int(budget.get("max_visible_messages"))
    if max_input_tokens is not None:
        memory.limits["maxPromptTokens"] = max_input_tokens
        memory.limits["maxPromptChars"] = max_input_tokens * 4
    if compact_threshold_tokens is not None:
        memory.limits["maxVisibleTokens"] = compact_threshold_tokens
        memory.limits["maxVisibleChars"] = compact_threshold_tokens * 4
    if context_window_tokens is not None:
        memory.limits["contextWindowTokens"] = context_window_tokens
    if max_output_tokens is not None:
        memory.limits["maxOutputTokens"] = max_output_tokens
    if prompt_reserve_output_tokens is not None:
        memory.limits["promptReserveOutputTokens"] = prompt_reserve_output_tokens
    if prompt_reserve_system_tokens is not None:
        memory.limits["promptReserveSystemTokens"] = prompt_reserve_system_tokens
    if max_tool_result_tokens is not None:
        memory.limits["maxToolResultTokens"] = max_tool_result_tokens
    if max_tool_result_chars is not None:
        memory.limits["maxToolResultChars"] = max_tool_result_chars
    if project_historical_tool_results is not None:
        memory.limits["projectHistoricalToolResults"] = project_historical_tool_results
    if raw_tool_result_tail_turn_count is not None:
        memory.limits["rawToolResultTailTurnCount"] = raw_tool_result_tail_turn_count
    if compaction_head_turn_count is not None:
        memory.limits["headTurnCount"] = compaction_head_turn_count
    if compaction_tail_turn_count is not None:
        memory.limits["tailTurnCount"] = compaction_tail_turn_count
    if max_compaction_summary_chars is not None:
        memory.limits["maxSummaryChars"] = max_compaction_summary_chars
    if max_prompt_messages is not None:
        memory.limits["maxPromptMessages"] = max_prompt_messages
    if max_visible_messages is not None:
        memory.limits["maxVisibleMessages"] = max_visible_messages
    preset_key = budget.get("preset_key")
    if isinstance(preset_key, str) and preset_key:
        memory.limits["runtimeBudgetPresetKey"] = preset_key
    source = budget.get("source")
    if isinstance(source, str) and source:
        memory.limits["runtimeBudgetSource"] = source
    token_estimator = budget.get("token_estimator")
    if isinstance(token_estimator, str) and token_estimator:
        memory.limits["tokenEstimator"] = token_estimator


def _compact_for_prompt_budget_with_warning(
    memory: Any,
    *,
    frame: Any,
    task_id: str,
    runtime_context: dict[str, Any],
    summarizer: Any,
) -> None:
    before = memory.prompt_budget_status()
    compacted = memory.compact_for_prompt_budget(summarizer=summarizer)
    after = memory.prompt_budget_status()
    if not before.get("wouldClip") and not compacted and not after.get("wouldClip"):
        return
    if after.get("wouldClip"):
        code = "PROMPT_BUDGET_HARD_CAP_REMAINS"
        severity = "warning"
        message = "Prompt budget still requires final prompt assembly to apply hard-cap clipping."
    else:
        code = "PROMPT_BUDGET_PRE_COMPACTION"
        severity = "info"
        message = "Runtime memory was compacted before prompt assembly would hard-cut visible messages."
    _append_runtime_context_warning(
        runtime_context,
        {
            "schemaVersion": 1,
            "code": code,
            "severity": severity,
            "message": message,
            "taskId": task_id,
            "frameId": getattr(frame, "frame_id", ""),
            "runtimeRevision": memory.revision,
            "compacted": compacted,
            "before": before,
            "after": after,
        },
    )


def _append_runtime_context_warning(runtime_context: dict[str, Any], warning: dict[str, Any]) -> None:
    warnings = runtime_context.setdefault("_runtime_context_warnings", [])
    if not isinstance(warnings, list):
        warnings = []
        runtime_context["_runtime_context_warnings"] = warnings
    code = str(warning.get("code") or "")
    task_id = str(warning.get("taskId") or "")
    frame_id = str(warning.get("frameId") or "")
    revision = str(warning.get("runtimeRevision") or "")
    key = f"{code}:{task_id}:{frame_id}:{revision}"
    for existing in warnings:
        if not isinstance(existing, dict):
            continue
        existing_key = (
            f"{existing.get('code') or ''}:"
            f"{existing.get('taskId') or ''}:"
            f"{existing.get('frameId') or ''}:"
            f"{existing.get('runtimeRevision') or ''}"
        )
        if existing_key == key:
            return
    warnings.append(warning)


def _runtime_memory_compaction_summarizer(
    *,
    frame: Any,
    state: RootState,
    runtime_context: dict[str, Any],
):
    if not settings.runtime_compaction_llm_enabled:
        return None
    model = _chat_model_for_state(state)
    session_id = optional_standard_context_id(frame.conversation_id or frame.session_id)
    if session_id is None:
        return None
    return build_runtime_compaction_summarizer(
        model=model,
        runtime_context=runtime_context,
        task_id=state["task_id"],
        frame_id=frame.frame_id,
        data_root=_data_root,
        session_id=session_id,
        date_parts=date_parts_for_frame(frame),
        require_standard_context=True,
    )


def _recent_conversation_for_runtime(context: dict[str, Any]) -> list[dict[str, Any]]:
    history = context.get("recentConversation") or context.get("recent_conversation")
    if not isinstance(history, list):
        return []
    visible: list[dict[str, Any]] = []
    for item in history[-12:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "message").strip().lower()
        if role not in {"user", "assistant", "tool", "system"}:
            role = "message"
        content = item.get("content")
        if isinstance(content, str) and content.strip():
            visible.append({"role": role, "content": content.strip()[:1200]})
    return visible


def _system_root_manifest() -> SkillManifest:
    return SkillManifest(
        id=ROOT_SKILL_ID,
        name=ROOT_SKILL_ID,
        description="当前业务会话的根编排 Agent。",
        markdown_body=(
            "你是当前任务的根业务编排 Agent。"
            "可以直接处理用户请求时，直接用自然语言回复用户并结束当前回合。"
            "普通寒暄、简单问答、无需保留结构化状态的答复，不要调用 submit_skill_result。"
            "只有需要保存 active_plan、artifact_refs、evidence_refs、structured_output "
            "或其他跨回合结构化状态时，才主动调用 submit_skill_result。"
            "当可用上下文或技能材料中描述了所需的已授权业务函数时，使用 "
            "invoke_business_function 调用业务函数。"
            "处理某个业务技能可直接支持的请求时，默认使用 invoke_business_skill "
            "读取业务技能说明，并在当前 Root 上下文中继续推理和调用业务函数；"
            "不要仅因为技能或目录名称包含 agent 就打开子 Agent frame。"
            "只有用户明确要求使用子 Agent/独立代理，或任务确实需要与 Root 隔离的"
            "独立生命周期、独立报告、长任务等待或多层委派时，才使用 invoke_business_agent。"
            "附件默认只是元数据或 URL；只有在用户要求检查图片/文件内容，"
            "或必须从附件中提取字段时，才使用 analyze_attachment。"
            "Excel 或 CSV 表格内容应使用 analyze_spreadsheet，不要当作图片分析。"
            "如果用户只是要求把文件作为业务操作附件提交，应保留附件，不要分析内容。"
            "上游系统提供的附件已经上传并带有 URL/ref；创建工单或追加沟通时，"
            "直接按业务函数 schema 映射为 attachmentRefs，不要再调用 attachment.upload。"
            "根 Agent 是持久的；自然语言最终消息或 submit_skill_result 都只完成当前用户回合，"
            "不会关闭整个会话。"
        ),
        allowed_tools=[
            "invoke_business_function",
            "invoke_business_skill",
            "invoke_business_agent",
            "analyze_attachment",
            "analyze_spreadsheet",
            "submit_skill_result",
        ],
        promote_to_parent=["result_summary", "structured_output", "artifact_refs", "evidence_refs"],
        visibility="builtin",
        context_visibility="passthrough",
    )


def _ensure_system_root_skill() -> None:
    _skill_registry.register(_system_root_manifest())


def _should_use_system_root_skill(state: RootState) -> bool:
    if not settings.llm_execute_skills:
        return False
    return _chat_model_for_state(state) is not None


def _conversation_id_for_root_frame(
    task_id: str,
    session_id: str | None,
    context: dict[str, Any],
) -> str:
    for key in (
        "contextId",
        "context_id",
        "conversationId",
        "conversation_id",
    ):
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            conversation_id = require_standard_context_id(value)
            context["contextId"] = conversation_id
            context["context_id"] = conversation_id
            return conversation_id
    conversation_id = optional_standard_context_id(session_id) or generate_standard_context_id()
    context["contextId"] = conversation_id
    context["context_id"] = conversation_id
    return conversation_id


def _is_conversation_root_frame(frame: Any) -> bool:
    if frame is None or getattr(frame, "parent_frame_id", None):
        return False
    if getattr(frame, "frame_kind", None) == FrameKind.ROOT:
        return True
    return getattr(frame, "skill_id", None) == ROOT_SKILL_ID


def _get_or_create_system_root_frame(
    task_id: str,
    session_id: str | None,
    context: dict[str, Any],
) -> tuple[str, bool]:
    """Return the persistent root frame for this conversation, restoring it if needed."""
    conversation_id = _conversation_id_for_root_frame(task_id, session_id, context)

    recoverable = _runtime.select_latest_recoverable_root(
        conversation_id=conversation_id,
        task_id=task_id,
        root_skill_id=ROOT_SKILL_ID,
    )
    if recoverable is not None:
        rebound = _runtime.rebind_frame_to_task(
            recoverable.frame_id,
            task_id,
            session_id=session_id,
            conversation_id=conversation_id,
        )
        return rebound.frame_id, False

    if conversation_id:
        for frame in _runtime.get_frames_by_conversation(conversation_id):
            if _is_conversation_root_frame(frame) and frame.status == FrameStatus.RUNNING:
                rebound = _runtime.rebind_frame_to_task(
                    frame.frame_id,
                    task_id,
                    session_id=session_id,
                    conversation_id=conversation_id,
                )
                return rebound.frame_id, False

        root_frame = _journal.load_root_by_conversation(
            conversation_id,
            root_skill_id=ROOT_SKILL_ID,
        )
        if root_frame and root_frame.status == FrameStatus.RUNNING:
            _runtime.restore_frame(root_frame)
            rebound = _runtime.rebind_frame_to_task(
                root_frame.frame_id,
                task_id,
                session_id=session_id,
                conversation_id=conversation_id,
            )
            return rebound.frame_id, False

    for frame in _runtime.get_frames_by_task(task_id):
        if _is_conversation_root_frame(frame) and frame.status == FrameStatus.RUNNING:
            rebound = _runtime.rebind_frame_to_task(
                frame.frame_id,
                task_id,
                session_id=session_id,
                conversation_id=conversation_id,
            )
            return rebound.frame_id, False

    for frame in _journal.load_by_task(task_id):
        if _is_conversation_root_frame(frame) and frame.status == FrameStatus.RUNNING:
            _runtime.restore_frame(frame)
            rebound = _runtime.rebind_frame_to_task(
                frame.frame_id,
                task_id,
                session_id=session_id,
                conversation_id=conversation_id,
            )
            return rebound.frame_id, False

    frame_id = _runtime.invoke_skill(
        task_id=task_id,
        skill_id=ROOT_SKILL_ID,
        skill_input=context,
        conversation_id=conversation_id,
        session_id=session_id,
        current_task_id=task_id,
        origin_task_id=task_id,
        frame_kind=FrameKind.ROOT,
    )
    return frame_id, True


def _chat_model_for_state(state: RootState):
    llm_config = state.get("llm_config")
    if llm_config:
        return create_chat_model_from_config(llm_config)
    return _chat_model


def _llm_skill_agent_for_state(state: RootState) -> LlmSkillAgent | None:
    if not settings.llm_execute_skills:
        return None
    llm_config = state.get("llm_config")
    max_iterations = _llm_skill_max_iterations_for_state(state)
    if not llm_config:
        if max_iterations == settings.llm_skill_max_iterations:
            return _llm_skill_agent
        if not _chat_model:
            return None
        return LlmSkillAgent(_chat_model, _runtime, max_iterations, data_root=Path(_data_root))
    chat_model = create_chat_model_from_config(llm_config)
    if not chat_model:
        return None
    return LlmSkillAgent(chat_model, _runtime, max_iterations, data_root=Path(_data_root))


def _llm_skill_max_iterations_for_state(state: RootState) -> int:
    runtime_context = state.get("runtime_context") or {}
    for key in ("llm_skill_max_iterations", "llmSkillMaxIterations", "max_turns", "maxTurns"):
        value = runtime_context.get(key)
        parsed = _positive_int(value)
        if parsed is not None:
            return parsed
    return settings.llm_skill_max_iterations


def _positive_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    if isinstance(value, float):
        parsed = int(value)
        return parsed if parsed > 0 else None
    if isinstance(value, str) and value.strip():
        try:
            parsed = int(value.strip())
        except ValueError:
            return None
        return parsed if parsed > 0 else None
    return None


def _bool_or_none(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True
        if normalized in {"false", "0", "no", "n", "off"}:
            return False
    if isinstance(value, int) and value in {0, 1}:
        return bool(value)
    return None


def get_runtime() -> SkillRuntime:
    """Expose the shared runtime for testing and external access."""
    return _runtime


def get_journal() -> FileFrameJournal:
    """Expose the shared journal for resume route configuration."""
    return _journal


# ---------------------------------------------------------------------------
# Graph state
# ---------------------------------------------------------------------------


class RootState(TypedDict):
    """Root task state flowing through the graph."""

    task_id: str
    session_id: str | None
    prompt: str
    model: str | None
    model_config_id: str | None
    llm_config: dict[str, Any] | None
    vision_llm_config: dict[str, Any] | None
    context: dict[str, Any] | None
    skill_name: str | None
    runtime_context: dict[str, Any] | None
    attachments: list[dict[str, Any]] | None
    user_id: str | None
    tenant_id: str | None
    # Annotated with operator.add so each node's events are *appended*
    events: Annotated[list[QueryEvent], operator.add]
    started_at: float

    # Phase 3+ fields
    active_frame_id: str | None
    skill_results: Annotated[list[dict[str, Any]], operator.add]


# ---------------------------------------------------------------------------
# Graph nodes
# ---------------------------------------------------------------------------


def route_skill(state: RootState) -> dict:
    """Determine which skill to invoke based on the query context."""
    task_id = state["task_id"]
    context = strip_execution_policy_context(state.get("context") or {})
    account_id = _account_id_from_state(state)
    client_app_id = context.get("client_app_id") or context.get("clientAppId")
    account_workspace = None
    try:
        execution_policy = ExecutionPolicy.from_context(
            copy_execution_policy_from_context(
                state.get("runtime_context") or {},
                state.get("context") or {},
            )
        )
        account_workspace = resolve_account_workspace(
            _skill_registry.data_root,
            account_id,
            execution_policy=execution_policy,
        )
    except ValueError:
        account_workspace = None

    try:
        _skill_registry.load(
            account_id=account_id,
            client_app_id=client_app_id,
            account_workspace=account_workspace,
        )
    except ValueError:
        _skill_registry.load()
    _ensure_system_root_skill()
    context = _context_with_visible_skill_descriptions(context)

    events = [
        QueryEvent(
            type="system",
            content="LangGraph Biz Worker processing query",
            task_id=task_id,
        ),
    ]

    if _should_use_system_root_skill(state):
        frame_id, created = _get_or_create_system_root_frame(task_id, state.get("session_id"), context)
        events.append(QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=frame_id,
            content=ROOT_FRAME_OPEN_CONTENT if created else ROOT_FRAME_REUSE_CONTENT,
            presentation_hint="root_frame",
        ))
        return {"events": events, "active_frame_id": frame_id, "context": context}

    try:
        explicit_skill_name = _explicit_skill_name_from_state(state)
    except SkillNameValidationError as exc:
        events.append(QueryEvent(type="error", task_id=task_id, error=str(exc)))
        return {"events": events, "active_frame_id": None, "context": context}

    # Legacy non-LLM fallback priority:
    # 0. Dynamic skill injection via markdown
    # 1. Explicit skill in context
    # 2. Rule-based fallback (backward compat)
    skill_id = None

    # Priority 0: dynamic markdown injection
    markdown_body = context.get("skill_markdown")
    if markdown_body and explicit_skill_name:
        # Synthesize a temporary manifest to execute
        manifest = SkillManifest(
            id=explicit_skill_name,
            name=explicit_skill_name,
            markdown_body=markdown_body,
            allowed_tools=[],  # Tools can be registered if needed
        )
        _skill_registry.register(manifest)
        skill_id = manifest.id

    # Priority 1: explicit skill in context
    if not skill_id and explicit_skill_name and _skill_registry.get_manifest(explicit_skill_name):
        skill_id = explicit_skill_name

    # Priority 2: rule-based fallback (takes precedence over LLM routing to preserve
    # deterministic test behaviour and backward-compat triggers like order_id → exception_triage)
    if not skill_id:
        if context.get("order_id"):
            skill_id = "exception_triage"

    if skill_id:
        # Create an Agent Frame via Runtime. Older tool names may still pass a
        # skill-like id; it is kept as compatibility metadata while the frame
        # lifecycle semantics are Agent-owned.
        conversation_id = _conversation_id_for_root_frame(task_id, state.get("session_id"), context)
        frame_id = _runtime.invoke_skill(
            task_id=task_id,
            skill_id=skill_id,
            skill_input=context,
            conversation_id=conversation_id,
            session_id=state.get("session_id"),
            current_task_id=task_id,
            origin_task_id=task_id,
            frame_kind=FrameKind.AGENT,
            agent_id=skill_id,
            frame_name=skill_id,
        )
        events.append(QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=skill_id,
            content=f"Opening frame for agent: {skill_id}",
            presentation_hint="agent_frame",
        ))
        return {"events": events, "active_frame_id": frame_id, "context": context}
    else:
        # Static fallback if model routing fails or model is missing
        content_msg = "抱歉，我未能识别出与您请求匹配的业务技能。请尝试提供更多业务上下文或具体的操作指令。"

        events.extend([
            QueryEvent(
                type="assistant_text",
                content=content_msg,
                task_id=task_id,
                model=state.get("model"),
            ),
            QueryEvent(
                type="result",
                content=content_msg,
                task_id=task_id,
                model=state.get("model"),
                duration_ms=int((time.time() - state["started_at"]) * 1000),
            ),
        ])
        return {"events": events, "active_frame_id": None, "context": context}


def run_skill(state: RootState) -> dict:
    """Execute the matched skill's subgraph logic."""
    frame_id = state.get("active_frame_id")
    if not frame_id:
        return {"events": []}

    frame = _runtime.get_frame(frame_id)
    if not frame:
        return {"events": []}

    task_id = state["task_id"]
    raw_context = state.get("context") or {}
    context = strip_execution_policy_context(raw_context)
    account_id = _account_id_from_state(state)
    runtime_context = copy_execution_policy_from_context(state.get("runtime_context") or {}, raw_context)
    runtime_context["task_id"] = task_id
    runtime_context["frame_id"] = frame_id
    if frame.conversation_id:
        runtime_context.setdefault("contextId", frame.conversation_id)
    elif frame.session_id:
        runtime_context.setdefault("sessionId", frame.session_id)
    is_root_frame = _is_conversation_root_frame(frame)
    if not is_root_frame:
        recent_conversation = _recent_conversation_for_runtime(context)
        if recent_conversation:
            runtime_context["_visible_recent_conversation"] = recent_conversation
    client_app_id = context.get("client_app_id") or context.get("clientAppId")
    if client_app_id:
        runtime_context.setdefault("client_app_id", client_app_id)
    if state.get("llm_config"):
        runtime_context["llm_config"] = state["llm_config"]
    if state.get("vision_llm_config"):
        runtime_context["vision_llm_config"] = state["vision_llm_config"]
    if state.get("attachments"):
        runtime_context["attachments"] = state["attachments"]
    if _is_conversation_root_frame(frame):
        runtime_context["_model_visible_business_context"] = _context_with_visible_skill_descriptions(context)

    llm_skill_agent = _llm_skill_agent_for_state(state)
    if llm_skill_agent:
        skill_prompt = _skill_agent_prompt(state["prompt"], context)
        if is_root_frame:
            recovered_focus_events = _run_active_focus_before_root(
                state,
                root_frame_id=frame_id,
                prompt=skill_prompt,
                account_id=account_id,
                runtime_context=runtime_context,
                llm_skill_agent=llm_skill_agent,
            )
            if recovered_focus_events is not None:
                return {"events": recovered_focus_events}
            busy_event = _prepare_root_runtime_memory_for_turn(
                frame,
                state=state,
                task_id=task_id,
                context=context,
                prompt=skill_prompt,
                runtime_context=runtime_context,
            )
            if busy_event is not None:
                return {"events": [busy_event]}
        return {
            "events": llm_skill_agent.run(
                task_id=task_id,
                frame_id=frame_id,
                prompt=skill_prompt,
                account_id=account_id,
                runtime_context=runtime_context,
                persistent_frame=is_root_frame,
            )
        }

    # Build skill subgraph state and run steps sequentially
    triage_state: TriageState = {
        "task_id": task_id,
        "frame_id": frame_id,
        "skill_input": frame.input,
        "evidence": {},
        "analysis": {},
        "events": [],
        "runtime": _runtime,
        "child_results": {},
    }

    # Step 1: invoke child skill for evidence collection
    result1 = invoke_evidence_child(triage_state)
    triage_state["evidence"] = result1.get("evidence", {})
    all_events = list(result1.get("events", []))

    # Step 2: analyze evidence
    result2 = analyze(triage_state)
    triage_state["analysis"] = result2["analysis"]

    # Step 3: invoke rule check child skill
    result3 = invoke_rule_check_child(triage_state)
    all_events.extend(result3.get("events", []))

    # Step 4: submit final aggregated result
    result4 = submit_result(triage_state)
    all_events.extend(result4.get("events", []))

    return {"events": all_events}


def _prepare_root_runtime_memory_for_turn(
    frame: Any,
    *,
    state: RootState | None = None,
    task_id: str,
    context: dict[str, Any],
    prompt: str,
    runtime_context: dict[str, Any],
    inject_prompt_view: bool = True,
) -> QueryEvent | None:
    context_id = _context_id_for_frame(frame)
    with context_state_lock(context_id):
        memory = load_from_root_frame(frame)
        _sync_memory_limits_from_runtime_context(memory, runtime_context)
        memory.bootstrap_from_external_recent_conversation(
            _recent_conversation_for_runtime(context),
            task_id=task_id,
            root_frame_id=frame.frame_id,
        )
        if inject_prompt_view:
            _compact_for_prompt_budget_with_warning(
                memory,
                frame=frame,
                task_id=task_id,
                runtime_context=runtime_context,
                summarizer=(
                    _runtime_memory_compaction_summarizer(
                        frame=frame,
                        state=state,
                        runtime_context=runtime_context,
                    ) if state is not None else None
                ),
            )
            prompt_view = memory.build_prompt_view()
            if prompt_view:
                runtime_context[RUNTIME_VISIBLE_CONVERSATION_KEY] = prompt_view
            else:
                runtime_context.pop(RUNTIME_VISIBLE_CONVERSATION_KEY, None)
        else:
            runtime_context.pop(RUNTIME_VISIBLE_CONVERSATION_KEY, None)
        runtime_context["_runtime_context_revision"] = memory.revision

        try:
            memory.begin_turn(
                task_id=task_id,
                root_frame_id=frame.frame_id,
                user_message=prompt,
            )
        except ContextRuntimeBusy as exc:
            return QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=frame.frame_id,
                error=str(exc),
                presentation_hint="root_frame",
                payload={
                    "code": "CONTEXT_RUNTIME_BUSY",
                    "retryable": True,
                },
            )

        save_to_root_frame(frame, memory)
        _runtime.save_frame(frame)
    _install_runtime_memory_checkpoint(
        runtime_context,
        root_frame_id=frame.frame_id,
    )
    _install_runtime_memory_markers(
        runtime_context,
        root_frame_id=frame.frame_id,
    )
    return None


def _refresh_root_runtime_memory_for_running_turn(
    frame: Any,
    *,
    state: RootState | None = None,
    runtime_context: dict[str, Any],
    inject_prompt_view: bool = True,
) -> None:
    """Expose prompt memory for a root turn that was already opened."""
    context_id = _context_id_for_frame(frame)
    with context_state_lock(context_id):
        refreshed = _runtime.get_frame(frame.frame_id) or frame
        memory = load_from_root_frame(refreshed)
        _sync_memory_limits_from_runtime_context(memory, runtime_context)
        if inject_prompt_view:
            _compact_for_prompt_budget_with_warning(
                memory,
                frame=refreshed,
                task_id=(
                    state["task_id"] if state is not None else
                    getattr(refreshed, "current_task_id", None) or getattr(refreshed, "origin_task_id", "")
                ),
                runtime_context=runtime_context,
                summarizer=(
                    _runtime_memory_compaction_summarizer(
                        frame=refreshed,
                        state=state,
                        runtime_context=runtime_context,
                    ) if state is not None else None
                ),
            )
            prompt_view = memory.build_prompt_view()
            if prompt_view:
                runtime_context[RUNTIME_VISIBLE_CONVERSATION_KEY] = prompt_view
            else:
                runtime_context.pop(RUNTIME_VISIBLE_CONVERSATION_KEY, None)
        else:
            runtime_context.pop(RUNTIME_VISIBLE_CONVERSATION_KEY, None)
        runtime_context["_runtime_context_revision"] = memory.revision
        save_to_root_frame(refreshed, memory)
        _runtime.save_frame(refreshed)
    _install_runtime_memory_checkpoint(
        runtime_context,
        root_frame_id=frame.frame_id,
    )
    _install_runtime_memory_markers(
        runtime_context,
        root_frame_id=frame.frame_id,
    )


def _commit_root_runtime_memory_turn(
    frame: Any,
    *,
    state: RootState,
    assistant_message: str,
    structured_output: dict[str, Any] | None,
) -> None:
    context_id = _context_id_for_frame(frame)
    with context_state_lock(context_id):
        memory = load_from_root_frame(frame)
        budget_context = dict(state.get("runtime_context") or {})
        if state.get("llm_config"):
            budget_context["llm_config"] = state["llm_config"]
        _sync_memory_limits_from_runtime_context(memory, budget_context)
        if memory.pending_turn is None:
            try:
                memory.begin_turn(
                    task_id=state["task_id"],
                    root_frame_id=frame.frame_id,
                    user_message=_skill_agent_prompt(
                        state["prompt"],
                        strip_execution_policy_context(state.get("context") or {}),
                    ),
                )
            except ContextRuntimeBusy:
                return
        metadata = _runtime_memory_projection_metadata(frame, structured_output)
        protocol_messages = frame.private_working_state.get(PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY)
        summarizer = _runtime_memory_compaction_summarizer(
            frame=frame,
            state=state,
            runtime_context=budget_context,
        )
        committed = memory.commit_turn(
            assistant_message=assistant_message,
            metadata=metadata,
            protocol_messages=protocol_messages if isinstance(protocol_messages, list) else None,
            summarizer=summarizer,
        )
        if committed:
            frame.private_working_state.pop(PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY, None)
        save_to_root_frame(frame, memory)
        _runtime.save_frame(frame)


def _abandon_root_runtime_memory_turn(frame: Any, *, status: str) -> None:
    context_id = _context_id_for_frame(frame)
    with context_state_lock(context_id):
        memory = load_from_root_frame(frame)
        if memory.pending_turn is None and memory.loop_status != "RUNNING":
            return
        memory.abandon_turn(status=status)
        save_to_root_frame(frame, memory)
        _runtime.save_frame(frame)


def enqueue_pending_user_input_for_context(
    context_id: str,
    *,
    task_id: str,
    prompt: str,
) -> QueryEvent:
    """Queue a user message for an already running root loop."""
    with context_state_lock(context_id):
        frame = _select_active_root_frame_for_context(context_id)
        if frame is None:
            return QueryEvent(
                type="error",
                task_id=task_id,
                session_id=context_id,
                error="context runtime is busy but no active root frame was found",
                payload={
                    "code": "CONTEXT_RUNTIME_BUSY",
                    "retryable": True,
                },
            )
        memory = load_from_root_frame(frame)
        if memory.loop_status != "RUNNING" or memory.pending_turn is None:
            return QueryEvent(
                type="error",
                task_id=task_id,
                session_id=context_id,
                skill_frame_id=frame.frame_id,
                error="context execution stream is closing; retry as a new turn",
                presentation_hint="root_frame",
                payload={
                    "code": "CONTEXT_RUNTIME_BUSY",
                    "retryable": True,
                },
            )
        queued = memory.enqueue_user_input(
            task_id=task_id,
            root_frame_id=frame.frame_id,
            user_message=prompt,
        )
        if queued is None:
            return QueryEvent(
                type="error",
                task_id=task_id,
                session_id=context_id,
                skill_frame_id=frame.frame_id,
                error="queued user input is empty",
                presentation_hint="root_frame",
                payload={
                    "code": "CONTEXT_RUNTIME_QUEUE_REJECTED",
                    "retryable": False,
                },
            )
        save_to_root_frame(frame, memory)
        _runtime.save_frame(frame)
        return QueryEvent(
            type="system",
            task_id=task_id,
            session_id=context_id,
            skill_frame_id=frame.frame_id,
            content="User input queued for the running context loop.",
            presentation_hint="root_frame",
            payload={
                "code": "CONTEXT_RUNTIME_QUEUED",
                "contextId": context_id,
                "queuedMessageId": queued.get("messageId"),
                "runningTaskId": memory.running_task_id,
                "runningFrameId": memory.running_frame_id or frame.frame_id,
            },
        )


def _install_runtime_memory_checkpoint(
    runtime_context: dict[str, Any],
    *,
    root_frame_id: str,
) -> None:
    def checkpoint() -> list[dict[str, Any]]:
        frame = _runtime.get_frame(root_frame_id)
        if frame is None:
            return []
        context_id = _context_id_for_frame(frame)
        with context_state_lock(context_id):
            refreshed = _runtime.get_frame(root_frame_id)
            if refreshed is None:
                return []
            memory = load_from_root_frame(refreshed)
            if memory.loop_status != "RUNNING" or memory.pending_turn is None:
                return []
            drained = memory.drain_pending_user_inputs()
            if not drained:
                return []
            save_to_root_frame(refreshed, memory)
            _runtime.save_frame(refreshed)
            return drained

    runtime_context["_runtime_memory_checkpoint"] = checkpoint


def _install_runtime_memory_markers(
    runtime_context: dict[str, Any],
    *,
    root_frame_id: str,
) -> None:
    def mark_finalizing() -> None:
        _update_runtime_memory_loop_state(root_frame_id, "FINALIZING")

    def mark_running() -> None:
        _update_runtime_memory_loop_state(root_frame_id, "RUNNING")

    runtime_context["_runtime_memory_mark_finalizing"] = mark_finalizing
    runtime_context["_runtime_memory_mark_running"] = mark_running


def _update_runtime_memory_loop_state(root_frame_id: str, status: str) -> None:
    frame = _runtime.get_frame(root_frame_id)
    if frame is None:
        return
    context_id = _context_id_for_frame(frame)
    with context_state_lock(context_id):
        refreshed = _runtime.get_frame(root_frame_id)
        if refreshed is None:
            return
        memory = load_from_root_frame(refreshed)
        updated = (
            memory.mark_finalizing()
            if status == "FINALIZING"
            else memory.mark_running()
        )
        if not updated:
            return
        save_to_root_frame(refreshed, memory)
        _runtime.save_frame(refreshed)


def _select_active_root_frame_for_context(context_id: str) -> Any | None:
    candidates = list(_runtime.get_frames_by_conversation(context_id))
    root_frame = _journal.load_root_by_conversation(
        context_id,
        root_skill_id=ROOT_SKILL_ID,
    )
    if root_frame is not None:
        candidates.append(root_frame)
    active_statuses = {
        FrameStatus.RUNNING,
        FrameStatus.WAITING_CHILD,
        FrameStatus.AWAITING_APPROVAL,
    }
    roots = [
        frame for frame in candidates
        if _is_conversation_root_frame(frame) and frame.status in active_statuses
    ]
    if not roots:
        return None
    roots.sort(key=lambda item: (
        item.journal_seq if isinstance(item.journal_seq, int) else -1,
        item.journal_updated_at or item.started_at or "",
        item.current_task_id or item.task_id or "",
    ))
    latest = roots[-1]
    if _runtime.get_frame(latest.frame_id) is None:
        _runtime.restore_frame(latest)
    return _runtime.get_frame(latest.frame_id) or latest


def _context_id_for_frame(frame: Any) -> str:
    context_id = optional_standard_context_id(
        getattr(frame, "conversation_id", None) or getattr(frame, "session_id", None)
    )
    if context_id is None:
        raise ValueError("Root runtime context requires a standard contextId")
    return context_id


def _runtime_memory_projection_metadata(
    frame: Any,
    structured_output: dict[str, Any] | None,
) -> dict[str, Any]:
    output = structured_output if isinstance(structured_output, dict) else {}
    child_summary = _matching_latest_child_summary(frame, output)
    report_ref = _execution_report_ref_from_frame(frame)
    report_digest = _execution_report_digest_from_frame(frame)
    metadata: dict[str, Any] = {
        "source": "root_result",
        "frameKind": getattr(getattr(frame, "frame_kind", None), "value", "ROOT"),
        "skillFrameId": getattr(frame, "frame_id", ""),
        "rootFrameKind": "ROOT",
        "rootFrameId": getattr(frame, "frame_id", ""),
        "structuredOutputKeys": sorted(output.keys()),
        "reportRef": report_ref,
    }
    if report_digest:
        metadata["reportDigest"] = report_digest
    if child_summary:
        child_report_ref = child_summary.get("execution_report_ref")
        child_report_digest = child_summary.get("execution_report_digest")
        metadata.update({
            "source": "skill_result",
            "skillId": child_summary.get("skill_id"),
            "skillFrameId": child_summary.get("frame_id"),
            "childSkillId": child_summary.get("skill_id"),
            "childSkillFrameId": child_summary.get("frame_id"),
            "reportRef": child_report_ref or report_ref,
            "promotedResultDigest": child_report_digest,
        })
    if _is_non_recoverable_projection(output):
        metadata.update({
            "source": "error",
            "errorCategory": output.get("error_category") or "NON_RECOVERABLE_TOOL_ERROR",
            "recoverable": False,
        })
    return {key: value for key, value in metadata.items() if value not in (None, "", [], {})}


def _matching_latest_child_summary(frame: Any, output: dict[str, Any]) -> dict[str, Any] | None:
    state = getattr(frame, "private_working_state", {}) or {}
    summary = state.get("latest_child_result_summary")
    if not isinstance(summary, dict):
        root_summary = state.get("root_context_summary")
        if isinstance(root_summary, dict):
            candidate = root_summary.get("latest_child_result_summary")
            summary = candidate if isinstance(candidate, dict) else None
    if not isinstance(summary, dict):
        return None
    if output and output == summary.get("structured_output"):
        return summary
    frame_summary = getattr(frame, "result_summary", None)
    if frame_summary and frame_summary == summary.get("result_summary"):
        return summary
    return None


def _is_non_recoverable_projection(output: dict[str, Any]) -> bool:
    if not output:
        return False
    status = str(output.get("status") or "").strip().upper()
    return (
        status == "ERROR"
        and (
            output.get("recoverable") is False
            or output.get("llm_retry_allowed") is False
        )
    )


def _visible_promoted_child_result(promoted: dict[str, Any]) -> dict[str, Any]:
    visible: dict[str, Any] = {}
    skill_id = promoted.get("skill_id")
    if isinstance(skill_id, str) and skill_id:
        visible["source_skill"] = skill_id
    for key in (
        "result_summary",
        "structured_output",
        "artifact_refs",
        "evidence_refs",
        "execution_report_ref",
        "execution_report_digest",
        "requires_parent_synthesis",
        "remaining_work",
    ):
        value = promoted.get(key)
        if value not in (None, "", [], {}):
            visible[key] = value
    return model_visible_context(visible)


def _run_root_synthesis_after_focus_completion(
    state: RootState,
    *,
    root_frame_id: str,
    prompt: str,
    account_id: str | None,
    runtime_context: dict[str, Any],
    llm_skill_agent: LlmSkillAgent,
    events: list[QueryEvent],
) -> list[QueryEvent]:
    root = _runtime.get_frame(root_frame_id)
    if root is None:
        events.append(QueryEvent(
            type="error",
            task_id=state["task_id"],
            skill_frame_id=root_frame_id,
            error="Root frame not found while synthesizing completed focus result",
            presentation_hint="root_frame",
        ))
        return events

    root_runtime_context = dict(runtime_context)
    root_context_summary = _runtime.context_summary_for_frame(root_frame_id)
    if root_context_summary:
        root_runtime_context["_visible_root_context_summary"] = root_context_summary
    _refresh_root_runtime_memory_for_running_turn(
        root,
        state=state,
        runtime_context=root_runtime_context,
    )
    events.extend(llm_skill_agent.run(
        task_id=state["task_id"],
        frame_id=root_frame_id,
        prompt=prompt,
        account_id=account_id,
        runtime_context=root_runtime_context,
        persistent_frame=True,
    ))
    return events


def _bubble_focus_approval_to_root(
    *,
    root_frame_id: str,
    focus: Any,
    events: list[QueryEvent],
    task_id: str,
) -> list[QueryEvent]:
    approval_request = getattr(focus, "approval_request", None)
    if not isinstance(approval_request, dict):
        _resume_root_if_waiting_for_focus(root_frame_id)
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=getattr(focus, "frame_id", ""),
            parent_frame_id=getattr(focus, "parent_frame_id", None),
            skill_id=getattr(focus, "skill_id", None),
            error="Child skill is awaiting approval without approval_request",
        ))
        return events

    current = focus
    while getattr(current, "parent_frame_id", None):
        parent_frame_id = current.parent_frame_id
        _runtime.mark_child_awaiting_approval(
            parent_frame_id,
            current.frame_id,
            approval_request,
        )
        if parent_frame_id == root_frame_id:
            break
        refreshed_parent = _runtime.get_frame(parent_frame_id)
        if refreshed_parent is None:
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=parent_frame_id,
                error="Parent frame not found while bubbling approval wait",
            ))
            break
        current = refreshed_parent
    return events


def _run_parent_after_nested_focus_completion(
    *,
    state: RootState,
    parent: Any,
    child_promoted: dict[str, Any],
    root_frame_id: str,
    prompt: str,
    account_id: str | None,
    runtime_context: dict[str, Any],
    llm_skill_agent: LlmSkillAgent,
    events: list[QueryEvent],
) -> Any | None:
    task_id = state["task_id"]
    manifest = None
    if parent.skill_id:
        manifest = _context_skill_manifest(
            _runtime,
            parent.skill_id,
            account_id=account_id,
            runtime_context=runtime_context,
        )
    if manifest is None and parent.frame_kind != FrameKind.AGENT:
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=parent.frame_id,
            parent_frame_id=parent.parent_frame_id,
            skill_id=parent.skill_id,
            error=f"Skill manifest not found: {parent.skill_id}",
        ))
        return None

    events.append(QueryEvent(
        type="skill_frame_open",
        task_id=task_id,
        skill_frame_id=parent.frame_id,
        parent_frame_id=parent.parent_frame_id or root_frame_id,
        skill_id=parent.agent_id or parent.skill_id,
        content=f"Resuming parent frame after child completion: {parent.agent_id or parent.skill_id or parent.frame_id}",
        presentation_hint="agent_frame" if parent.frame_kind == FrameKind.AGENT else None,
    ))
    if parent.frame_kind == FrameKind.AGENT:
        parent_runtime_context = _runtime_context_for_child_agent(
            runtime_context=runtime_context,
            root_context_summary=_runtime.context_summary_for_frame(parent.frame_id),
            agent_manifest=manifest,
            agent_id=parent.agent_id or parent.skill_id or parent.frame_id,
        )
    else:
        parent_runtime_context = _runtime_context_for_child_skill(
            runtime_context,
            _runtime.context_summary_for_frame(parent.frame_id),
            manifest,
        )
    parent_runtime_context = dict(parent_runtime_context or {})
    parent_runtime_context["_nested_child_completed"] = _visible_promoted_child_result(child_promoted)
    parent_runtime_context["_runtime_protocol_recovery"] = {
        "enabled": True,
        "frame_id": parent.frame_id,
        "mode": "CHILD_COMPLETED",
    }
    events.extend(llm_skill_agent.run(
        task_id=task_id,
        frame_id=parent.frame_id,
        prompt=prompt,
        account_id=account_id,
        runtime_context=parent_runtime_context,
    ))
    return _runtime.get_frame(parent.frame_id)


def _unwind_completed_focus_to_root(
    state: RootState,
    *,
    root_frame_id: str,
    completed_focus: Any,
    prompt: str,
    account_id: str | None,
    runtime_context: dict[str, Any],
    llm_skill_agent: LlmSkillAgent,
    events: list[QueryEvent],
) -> list[QueryEvent]:
    """Close a completed focus and resume each parent frame up to root."""
    task_id = state["task_id"]
    current = completed_focus

    while current is not None and getattr(current, "parent_frame_id", None):
        parent_frame_id = current.parent_frame_id
        promoted = _runtime.complete_child_and_resume_parent(current.frame_id)
        events.append(QueryEvent(
            type="skill_frame_close",
            task_id=task_id,
            skill_frame_id=current.frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=current.agent_id or current.skill_id,
            content=f"Frame closed: {current.agent_id or current.skill_id or current.frame_id}",
            presentation_hint="agent_frame" if current.frame_kind == FrameKind.AGENT else None,
            execution_report_ref=promoted.get("execution_report_ref"),
            execution_report_digest=promoted.get("execution_report_digest"),
        ))

        if parent_frame_id == root_frame_id:
            direct_result = _direct_child_result_for_user(
                promoted,
                allow_legacy_completed=True,
            )
            if direct_result is not None:
                validation = _runtime.submit_persistent_turn_result(
                    frame_id=root_frame_id,
                    summary=direct_result["summary"],
                    structured_output=direct_result["structured_output"],
                    artifact_refs=direct_result.get("artifact_refs"),
                    evidence_refs=direct_result.get("evidence_refs"),
                )
                if not validation.ok:
                    events.append(QueryEvent(
                        type="error",
                        task_id=task_id,
                        skill_frame_id=root_frame_id,
                        error=(
                            "Failed to submit direct child result: "
                            + "; ".join(validation.errors)
                        ),
                        presentation_hint="root_frame",
                    ))
                return events
            return _run_root_synthesis_after_focus_completion(
                state,
                root_frame_id=root_frame_id,
                prompt=prompt,
                account_id=account_id,
                runtime_context=runtime_context,
                llm_skill_agent=llm_skill_agent,
                events=events,
            )

        parent = _runtime.get_frame(parent_frame_id)
        if parent is None:
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=parent_frame_id,
                error="Parent frame not found while unwinding completed focus",
            ))
            return events

        refreshed_parent = _run_parent_after_nested_focus_completion(
            state=state,
            parent=parent,
            child_promoted=promoted,
            root_frame_id=root_frame_id,
            prompt=prompt,
            account_id=account_id,
            runtime_context=runtime_context,
            llm_skill_agent=llm_skill_agent,
            events=events,
        )
        if refreshed_parent is None:
            return events

        if refreshed_parent.status == FrameStatus.AWAITING_APPROVAL:
            return _bubble_focus_approval_to_root(
                root_frame_id=root_frame_id,
                focus=refreshed_parent,
                events=events,
                task_id=task_id,
            )

        if refreshed_parent.status == FrameStatus.AWAITING_USER:
            awaiting_user_context = _awaiting_user_context_for_focus(refreshed_parent)
            grandparent_frame_id = refreshed_parent.parent_frame_id or root_frame_id
            _runtime.mark_child_awaiting_user(
                grandparent_frame_id,
                refreshed_parent.frame_id,
                awaiting_user_context,
            )
            if grandparent_frame_id != root_frame_id:
                _runtime.mark_focus_awaiting_user(
                    root_frame_id,
                    refreshed_parent.frame_id,
                    awaiting_user_context,
                )
            return events

        if refreshed_parent.status != FrameStatus.COMPLETED:
            error = f"Parent skill ended in {refreshed_parent.status.value}"
            _record_parent_child_recoverable_interruption(
                _runtime,
                parent_frame_id=refreshed_parent.parent_frame_id or root_frame_id,
                child_frame_id=refreshed_parent.frame_id,
                reason="child_skill_failed",
                error=error,
                task_id=task_id,
            )
            return events

        current = refreshed_parent

    return events


def _run_active_focus_before_root(
    state: RootState,
    *,
    root_frame_id: str,
    prompt: str,
    account_id: str | None,
    runtime_context: dict[str, Any],
    llm_skill_agent: LlmSkillAgent,
) -> list[QueryEvent] | None:
    """Resume the active child focus before giving the turn to root."""
    task_id = state["task_id"]
    root = _runtime.get_frame(root_frame_id)
    if root is None or not _is_conversation_root_frame(root):
        return None

    focus_frame_id = (
        root.private_working_state.get("active_focus_frame_id")
        or root.private_working_state.get("recoverable_focus_frame_id")
    )
    if not isinstance(focus_frame_id, str) or not focus_frame_id or focus_frame_id == root_frame_id:
        return None

    focus = _runtime.prepare_active_focus_resume(root_frame_id, task_id=task_id)
    if focus is None:
        return None
    root = _runtime.get_frame(root_frame_id) or root
    focus_parent_frame_id = focus.parent_frame_id or root_frame_id

    focus_manifest = None
    if focus.skill_id:
        focus_manifest = _context_skill_manifest(
            _runtime,
            focus.skill_id,
            account_id=account_id,
            runtime_context=runtime_context,
        )
    if focus_manifest is None and focus.frame_kind != FrameKind.AGENT:
        _resume_root_if_waiting_for_focus(root_frame_id)
        return [QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=focus.frame_id,
            parent_frame_id=focus_parent_frame_id,
            skill_id=focus.skill_id,
            error=f"Skill manifest not found: {focus.skill_id}",
        )]

    events: list[QueryEvent] = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=focus.frame_id,
            parent_frame_id=focus_parent_frame_id,
            skill_id=focus.agent_id or focus.skill_id,
            content=f"Resuming frame for agent: {focus.agent_id or focus.skill_id or focus.frame_id}",
            presentation_hint="agent_frame" if focus.frame_kind == FrameKind.AGENT else None,
        )
    ]

    if focus.status == FrameStatus.AWAITING_APPROVAL:
        approval_request = focus.approval_request
        if isinstance(approval_request, dict):
            _runtime.mark_child_awaiting_approval(
                focus_parent_frame_id,
                focus.frame_id,
                approval_request,
            )
        else:
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=focus.frame_id,
                parent_frame_id=focus_parent_frame_id,
                skill_id=focus.agent_id or focus.skill_id,
                error="Child agent is awaiting approval without approval_request",
            ))
        return events

    if focus.frame_kind == FrameKind.AGENT:
        child_runtime_context = _runtime_context_for_child_agent(
            runtime_context=runtime_context,
            root_context_summary=_runtime.context_summary_for_frame(focus_parent_frame_id),
            agent_manifest=focus_manifest,
            agent_id=focus.agent_id or focus.skill_id or focus.frame_id,
        )
    else:
        child_runtime_context = _runtime_context_for_child_skill(
            runtime_context,
            _runtime.context_summary_for_frame(focus_parent_frame_id),
            focus_manifest,
        )
    child_runtime_context = dict(child_runtime_context or {})
    busy_event = _prepare_root_runtime_memory_for_turn(
        root,
        task_id=task_id,
        context=strip_execution_policy_context(state.get("context") or {}),
        prompt=prompt,
        runtime_context=child_runtime_context,
        inject_prompt_view=False,
    )
    if busy_event is not None:
        events.append(busy_event)
        return events
    awaiting_user_context = _awaiting_user_context_for_focus(focus)
    if awaiting_user_context:
        child_runtime_context["_awaiting_user_input"] = awaiting_user_context
        child_runtime_context["_runtime_protocol_recovery"] = {
            "enabled": True,
            "frame_id": focus.frame_id,
            "mode": "AWAITING_USER",
        }
    elif _is_recoverable_focus_frame(focus):
        child_runtime_context["_runtime_protocol_recovery"] = {
            "enabled": True,
            "frame_id": focus.frame_id,
            "mode": "RECOVERABLE_INTERRUPTION",
        }
    events.extend(llm_skill_agent.run(
        task_id=task_id,
        frame_id=focus.frame_id,
        prompt=prompt,
        account_id=account_id,
        runtime_context=child_runtime_context,
    ))

    refreshed_focus = _runtime.get_frame(focus.frame_id)
    if refreshed_focus and refreshed_focus.status == FrameStatus.AWAITING_APPROVAL:
        approval_request = refreshed_focus.approval_request
        if isinstance(approval_request, dict):
            _runtime.mark_child_awaiting_approval(
                focus_parent_frame_id,
                refreshed_focus.frame_id,
                approval_request,
            )
        else:
            _resume_root_if_waiting_for_focus(root_frame_id)
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=refreshed_focus.frame_id,
                parent_frame_id=focus_parent_frame_id,
                skill_id=refreshed_focus.agent_id or refreshed_focus.skill_id,
                error="Child agent is awaiting approval without approval_request",
            ))
        return events

    if refreshed_focus and refreshed_focus.status == FrameStatus.AWAITING_USER:
        awaiting_user_context = _awaiting_user_context_for_focus(refreshed_focus)
        _runtime.mark_child_awaiting_user(
            focus_parent_frame_id,
            refreshed_focus.frame_id,
            awaiting_user_context,
        )
        if focus_parent_frame_id != root_frame_id:
            _runtime.mark_focus_awaiting_user(
                root_frame_id,
                refreshed_focus.frame_id,
                awaiting_user_context,
            )
        return events

    if not refreshed_focus or refreshed_focus.status != FrameStatus.COMPLETED:
        error = f"Child agent ended in {refreshed_focus.status.value if refreshed_focus else 'MISSING'}"
        _record_parent_child_recoverable_interruption(
            _runtime,
            parent_frame_id=focus_parent_frame_id,
            child_frame_id=refreshed_focus.frame_id if refreshed_focus else focus.frame_id,
            reason="child_agent_failed",
            error=error,
            task_id=task_id,
        )
        return events

    return _unwind_completed_focus_to_root(
        state,
        root_frame_id=root_frame_id,
        completed_focus=refreshed_focus,
        prompt=prompt,
        account_id=account_id,
        runtime_context=runtime_context,
        llm_skill_agent=llm_skill_agent,
        events=events,
    )


def _awaiting_user_context_for_focus(frame: Any) -> dict[str, Any] | None:
    state = getattr(frame, "private_working_state", {}) or {}
    payload = state.get("awaiting_user_input")
    if not isinstance(payload, dict):
        return None
    return {
        "frame_id": getattr(frame, "frame_id", ""),
        "skill_id": getattr(frame, "skill_id", ""),
        "status": getattr(getattr(frame, "status", None), "value", ""),
        "awaiting_user_input": payload,
    }


def _is_recoverable_focus_frame(frame: Any) -> bool:
    state = getattr(frame, "private_working_state", {}) or {}
    return (
        state.get("continuation_state") == "INTERRUPTED"
        and state.get("recoverable") is True
    )


def _resume_root_if_waiting_for_focus(root_frame_id: str) -> None:
    root = _runtime.get_frame(root_frame_id)
    if root and root.status == FrameStatus.WAITING_CHILD:
        _runtime.resume_from_child(root_frame_id)


def _is_current_turn_interrupted(frame: Any, task_id: str) -> bool:
    state = frame.private_working_state
    return (
        state.get("continuation_state") == "INTERRUPTED"
        and state.get("last_task_id") == task_id
    )


def close_skill_frame(state: RootState) -> dict:
    """Close the active frame and promote results to root context."""
    frame_id = state.get("active_frame_id")
    if not frame_id:
        return {"events": [], "skill_results": []}

    task_id = state["task_id"]
    frame = _runtime.get_frame(frame_id)
    events: list[QueryEvent] = []
    skill_results: list[dict[str, Any]] = []

    if frame and frame.status == FrameStatus.COMPLETED:
        frame_summary = frame.result_summary
        frame_output = frame.output
        promoted = _runtime.close_frame(frame_id)
        skill_results.append(promoted)
        result_summary = (
            promoted.get("result_summary")
            or frame_summary
            or _summary_from_structured_output(frame_output)
            or "Skill completed"
        )
        structured_output = promoted.get("structured_output")
        if structured_output is None:
            structured_output = frame_output

        events.append(QueryEvent(
            type="skill_frame_close",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=frame.skill_id,
            content=f"Frame closed: {frame.skill_id}",
            execution_report_ref=promoted.get("execution_report_ref"),
            execution_report_digest=promoted.get("execution_report_digest"),
        ))
        events.append(QueryEvent(
            type="result",
            content=result_summary,
            task_id=task_id,
            model=state.get("model"),
            structured_output=structured_output,
            duration_ms=int((time.time() - state["started_at"]) * 1000),
            execution_report_ref=promoted.get("execution_report_ref"),
            execution_report_digest=promoted.get("execution_report_digest"),
        ))
    elif (
        frame
        and _is_conversation_root_frame(frame)
        and frame.status == FrameStatus.RUNNING
        and _is_current_turn_interrupted(frame, task_id)
    ):
        _abandon_root_runtime_memory_turn(frame, status="INTERRUPTED")
    elif frame and _is_conversation_root_frame(frame) and frame.status == FrameStatus.RUNNING and frame.result_summary:
        _commit_root_runtime_memory_turn(
            frame,
            state=state,
            assistant_message=assistant_visible_content(frame.output, frame.result_summary),
            structured_output=frame.output,
        )
        events.append(QueryEvent(
            type="result",
            content=frame.result_summary,
            task_id=task_id,
            model=state.get("model"),
            structured_output=frame.output,
            duration_ms=int((time.time() - state["started_at"]) * 1000),
            execution_report_ref=_execution_report_ref_from_frame(frame),
            execution_report_digest=_execution_report_digest_from_frame(frame),
        ))
    elif (
        frame
        and _is_conversation_root_frame(frame)
        and frame.status == FrameStatus.WAITING_CHILD
        and frame.private_working_state.get("active_focus_status") == "AWAITING_USER"
    ):
        _commit_root_runtime_memory_turn(
            frame,
            state=state,
            assistant_message=assistant_visible_content(frame.output, frame.result_summary)
            or "等待用户补充信息。",
            structured_output=frame.output,
        )
        events.append(QueryEvent(
            type="result",
            content=frame.result_summary or "等待用户补充信息。",
            task_id=task_id,
            model=state.get("model"),
            structured_output=frame.output,
            duration_ms=int((time.time() - state["started_at"]) * 1000),
            execution_report_ref=_execution_report_ref_from_frame(frame),
            execution_report_digest=_execution_report_digest_from_frame(frame),
        ))
    elif frame and frame.status == FrameStatus.AWAITING_APPROVAL:
        # The approval_required event has already been emitted by the runtime
        # tool handler. Do not turn a deliberate suspension into an error.
        if _is_conversation_root_frame(frame):
            _abandon_root_runtime_memory_turn(frame, status="AWAITING_APPROVAL")
        pass
    elif frame:
        if _is_conversation_root_frame(frame):
            _abandon_root_runtime_memory_turn(frame, status=frame.status.value)
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=frame_id,
            error=f"Frame ended in {frame.status.value}",
        ))

    return {"events": events, "skill_results": skill_results, "active_frame_id": None}


def _summary_from_structured_output(output: Any) -> str | None:
    if not isinstance(output, dict):
        return None
    message = output.get("message")
    if isinstance(message, str) and message.strip():
        return message
    error = output.get("error")
    if isinstance(error, str) and error.strip():
        return error
    return None


def _execution_report_ref_from_frame(frame: Any) -> str | None:
    state = getattr(frame, "private_working_state", {}) or {}
    if not isinstance(state, dict):
        return None
    value = state.get("execution_report_ref")
    return value if isinstance(value, str) and value else None


def _execution_report_digest_from_frame(frame: Any) -> dict[str, Any] | None:
    state = getattr(frame, "private_working_state", {}) or {}
    if not isinstance(state, dict):
        return None
    value = state.get("execution_report_digest")
    return value if isinstance(value, dict) and value else None


def should_run_skill(state: RootState) -> str:
    """Conditional edge: skip skill execution if no frame is active."""
    if state.get("active_frame_id"):
        return "run_skill"
    return END


# ---------------------------------------------------------------------------
# Graph construction
# ---------------------------------------------------------------------------


def build_root_graph() -> StateGraph:
    """Build and compile the root graph."""
    graph = StateGraph(RootState)

    graph.add_node("route_skill", route_skill)
    graph.add_node("run_skill", run_skill)
    graph.add_node("close_skill_frame", close_skill_frame)

    graph.set_entry_point("route_skill")
    graph.add_conditional_edges("route_skill", should_run_skill, {
        "run_skill": "run_skill",
        END: END,
    })
    graph.add_edge("run_skill", "close_skill_frame")
    graph.add_edge("close_skill_frame", END)

    return graph.compile()


# Pre-built compiled graph instance
root_graph = build_root_graph()
