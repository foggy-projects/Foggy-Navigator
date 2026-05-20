"""Root Graph for LangGraph Biz Worker.

Phase 1: minimal single-node graph returning fixed results.
Phase 3+: routes to Skill subgraphs via SkillRuntime Frame lifecycle.
"""

from __future__ import annotations

import operator
import time
import json
import re
from pathlib import Path
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, StateGraph

from ..config import settings
from ..models import FrameStatus, QueryEvent, SkillManifest
from ..runtime.attachment_context import build_attachment_context_prompt as _build_attachment_context_prompt
from ..runtime.execution_policy import copy_execution_policy_from_context, strip_execution_policy_context
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.file_layout import date_parts_for_now, require_standard_context_id, session_data_dir
from ..runtime.frame_store import FrameStore
from ..runtime.account_context_files import build_account_context_prompt
from ..runtime.llm_skill_router import LlmSkillRouter, create_chat_model, create_chat_model_from_config
from ..runtime.llm_skill_agent import LlmSkillAgent
from ..runtime.llm_child_recovery import (
    _direct_child_result_for_user,
    _record_parent_child_recoverable_interruption,
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

# LLM-based Skill Router (None if llm_provider is empty → rule-based fallback)
_chat_model = create_chat_model(settings)
_llm_router: LlmSkillRouter | None = LlmSkillRouter(_chat_model) if _chat_model else None
_llm_skill_agent: LlmSkillAgent | None = (
    LlmSkillAgent(_chat_model, _runtime, settings.llm_skill_max_iterations, data_root=Path(_data_root))
    if _chat_model and settings.llm_execute_skills
    else None
)


def _safe_log_stem(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", value)


def _conversation_log_file(data_root: str | Path, task_id: str, session_id: str | None) -> Path:
    """Return the JSONL path for a single LLM routing request.

    Logs are grouped by session directory, with one numbered file per task. This
    keeps a multi-turn session ordered without making each task look like it
    reused earlier system prompts.
    """
    task_stem = _safe_log_stem(task_id)

    log_dir = (
        session_data_dir(Path(data_root), date_parts_for_now(), session_id or "_no-session")
        / "logs" / "llm-conversations"
    )
    max_index = 0
    if log_dir.exists():
        for existing in log_dir.glob("*.jsonl"):
            prefix = existing.name.split("_", 1)[0]
            if prefix.isdigit():
                max_index = max(max_index, int(prefix))
    return log_dir / f"{max_index + 1:04d}_{task_stem}.jsonl"


def _runtime_time_context_for_log(runtime_context: dict[str, Any] | None) -> dict[str, str]:
    """Whitelist dynamic time context for conversation logs without leaking tokens."""
    context = runtime_context or {}
    aliases = {
        "current_time": ("current_time", "currentTime"),
        "timezone": ("timezone", "timeZone", "tz"),
        "business_date": ("business_date", "businessDate"),
    }
    safe_context: dict[str, str] = {}
    for canonical_key, keys in aliases.items():
        for key in keys:
            value = context.get(key)
            if isinstance(value, str) and value:
                safe_context[canonical_key] = value
                break
    return safe_context


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


def _prompt_with_attachment_context(prompt: str, attachments: list[dict[str, Any]] | None) -> str:
    attachment_context = _build_attachment_context_prompt(attachments)
    if not attachment_context:
        return prompt
    return f"{prompt}\n\n{attachment_context}"


def _skill_agent_prompt(prompt: str, context: dict[str, Any]) -> str:
    instruction = context.get("skill_instruction")
    if isinstance(instruction, str) and instruction.strip():
        return instruction.strip()
    return prompt


def _recent_conversation_prompt(context: dict[str, Any]) -> str | None:
    history = context.get("recentConversation") or context.get("recent_conversation")
    if not isinstance(history, list):
        return None
    lines: list[str] = []
    for item in history[-12:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "message").strip().lower()
        content = item.get("content")
        if role not in {"user", "assistant", "tool", "system"}:
            role = "message"
        if isinstance(content, str) and content.strip():
            lines.append(f"{role}: {content.strip()[:1200]}")
    if not lines:
        return None
    return "Recent conversation before the current user message:\n" + "\n".join(lines)


def _context_for_prompt(context: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in context.items()
        if key not in {"recentConversation", "recent_conversation"}
    }


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
        description="System root business skill for task-level orchestration.",
        markdown_body=(
            "You are the system root skill for this business task. "
            "Handle the user's request directly when possible. "
            "Use invoke_business_function for authorized business functions when the "
            "needed function is described in the available context or skill material. "
            "Use invoke_business_skill to delegate bounded work to a specialized child skill. "
            "Attachments are metadata or URLs. Use analyze_attachment only when the user asks "
            "to inspect image/file contents or when required fields must be extracted from an attachment; "
            "for Excel or CSV spreadsheet content use analyze_spreadsheet instead of image analysis; "
            "if the user only asks to attach a file to a business operation, preserve the attachment without analysis. "
            "When the current user turn is ready to answer, call submit_skill_result. "
            "This root skill is persistent; submit_skill_result ends the current turn, "
            "not the root skill frame."
        ),
        allowed_tools=[
            "invoke_business_function",
            "invoke_business_skill",
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
) -> str | None:
    for key in (
        "contextId",
        "context_id",
        "conversationId",
        "conversation_id",
    ):
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return require_standard_context_id(value)
    return None


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
            if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
                rebound = _runtime.rebind_frame_to_task(
                    frame.frame_id,
                    task_id,
                    session_id=session_id,
                    conversation_id=conversation_id,
                )
                return rebound.frame_id, False

        for frame in _journal.load_by_conversation(conversation_id):
            if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
                _runtime.restore_frame(frame)
                rebound = _runtime.rebind_frame_to_task(
                    frame.frame_id,
                    task_id,
                    session_id=session_id,
                    conversation_id=conversation_id,
                )
                return rebound.frame_id, False

    for frame in _runtime.get_frames_by_task(task_id):
        if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
            rebound = _runtime.rebind_frame_to_task(
                frame.frame_id,
                task_id,
                session_id=session_id,
                conversation_id=conversation_id,
            )
            return rebound.frame_id, False

    for frame in _journal.load_by_task(task_id):
        if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
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
    )
    return frame_id, True


def _chat_model_for_state(state: RootState):
    llm_config = state.get("llm_config")
    if llm_config:
        return create_chat_model_from_config(llm_config)
    return _chat_model


def _llm_skill_agent_for_state(state: RootState) -> LlmSkillAgent | None:
    llm_config = state.get("llm_config")
    max_iterations = _llm_skill_max_iterations_for_state(state)
    if not llm_config:
        if max_iterations == settings.llm_skill_max_iterations:
            return _llm_skill_agent
        if not settings.llm_execute_skills or not _chat_model:
            return None
        return LlmSkillAgent(_chat_model, _runtime, max_iterations, data_root=Path(_data_root))
    if not settings.llm_execute_skills:
        return None
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

    try:
        _skill_registry.load(account_id=account_id, client_app_id=client_app_id)
    except ValueError:
        _skill_registry.load()
    _ensure_system_root_skill()

    events = [
        QueryEvent(
            type="system",
            content="LangGraph Biz Worker processing query",
            task_id=task_id,
        ),
    ]

    try:
        explicit_skill_name = _explicit_skill_name_from_state(state)
    except SkillNameValidationError as exc:
        events.append(QueryEvent(type="error", task_id=task_id, error=str(exc)))
        return {"events": events, "active_frame_id": None}

    if not explicit_skill_name and _should_use_system_root_skill(state):
        frame_id, created = _get_or_create_system_root_frame(task_id, state.get("session_id"), context)
        events.append(QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=ROOT_SKILL_ID,
            content=(
                f"Opening frame for skill: {ROOT_SKILL_ID}"
                if created
                else f"Reusing frame for skill: {ROOT_SKILL_ID}"
            ),
        ))
        return {"events": events, "active_frame_id": frame_id}

    # Three-level routing priority:
    # 0. Dynamic skill injection via markdown
    # 1. Explicit skill in context
    # 2. LLM routing (if enabled)
    # 3. Rule-based fallback (backward compat)
    skill_id = None
    chat_model = _chat_model_for_state(state)
    prompt_with_attachments = _prompt_with_attachment_context(
        state["prompt"],
        state.get("attachments"),
    )

    # Priority 0: dynamic markdown injection
    markdown_body = context.get("skill_markdown")
    if markdown_body and explicit_skill_name:
        # Synthesize a temporary manifest to execute
        manifest = SkillManifest(
            id=explicit_skill_name,
            name=explicit_skill_name,
            markdown_body=markdown_body,
            allowed_tools=[] # Tools can be registered if needed
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

    # Priority 3: Agentic LLM routing (only if no prior priority matched)
    if not skill_id and chat_model:
        if getattr(settings, "llm_agentic_routing", False):
            from langchain_core.messages import HumanMessage, SystemMessage
            
            allowed_skills_param = context.get("allowed_skills")
            routable_skills = []
            
            # 1. Process explicit allowed_skills from context
            if allowed_skills_param and isinstance(allowed_skills_param, list):
                for sk in allowed_skills_param:
                    sk_id = sk.get("id")
                    if not sk_id:
                        continue
                    if "markdown_body" in sk:
                        manifest = SkillManifest(
                            id=sk_id,
                            name=sk.get("name", sk_id),
                            description=sk.get("description", ""),
                            markdown_body=sk["markdown_body"],
                            allowed_tools=sk.get("allowed_tools", [])
                        )
                        _skill_registry.register(manifest)
                        routable_skills.append(manifest)
                    else:
                        manifest = _skill_registry.get_manifest(sk_id)
                        if manifest:
                            routable_skills.append(manifest)
                        else:
                            stub = SkillManifest(
                                id=sk_id,
                                name=sk.get("name", sk_id),
                                description=sk.get("description", ""),
                                allowed_tools=[]
                            )
                            routable_skills.append(stub)
            
            # 2. Process system public skills and user private skills
            auto_inject = context.get("auto_inject_public_skills", False)
            auto_inject_app_public = context.get("auto_inject_app_public_skills", bool(client_app_id))
            all_skills = _skill_registry.list_skills()
            for s in all_skills:
                # Deduplicate if already added via allowed_skills
                if any(r.id == s.id for r in routable_skills):
                    continue
                    
                vis = getattr(s, "visibility", "public")
                if vis == "builtin":
                    continue
                    
                # Private account skills are ALWAYS injected (not controlled by TMS allowed_skills)
                if vis == "private":
                    routable_skills.append(s)
                    continue
                    
                # App-scoped public skills are safe to inject after Java resolves clientAppId.
                app_scoped = bool(client_app_id) and getattr(s, "client_app_id", None) == client_app_id
                if vis == "public" and (auto_inject or (auto_inject_app_public and app_scoped)):
                    routable_skills.append(s)
            
            invoke_tool = {
                "type": "function",
                "function": {
                    "name": "invoke_business_skill",
                    "description": (
                        "Delegate the user's request to a specialized business skill. "
                        "Use this when the user asks to perform an action that matches "
                        "one of the available skills. The returned promoted result is "
                        "the primary business-decision context from that child skill; "
                        "do not inspect execution reports after normal completion just "
                        "to recover status, next_step, or structured_output fields. "
                        "If the child asks for user input, the runtime keeps that child "
                        "frame open and resumes it on the next user message."
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "skill_name": {
                                "type": "string",
                                "description": "The exact skill folder name to invoke."
                            },
                            "instruction": {
                                "type": "string",
                                "description": (
                                    "Natural language instructions for the skill, "
                                    "summarizing what needs to be done and any "
                                    "extracted entities (e.g. order numbers) from "
                                    "the user's request so the child can return a "
                                    "complete promoted result."
                                )
                            }
                        },
                        "required": ["skill_name", "instruction"]
                    }
                }
            }
            tools = [invoke_tool]
            
            if routable_skills:
                skills_summary = "\n".join([f"- {s.id}: {s.description}" for s in routable_skills])
                skills_section = f"Available Skills:\n{skills_summary}\n\nIf the user's request matches a skill, use the `invoke_business_skill` tool to delegate the work.\nProvide the `skill_name` and a detailed `instruction` based on the user's input. Treat the returned promoted result as the primary child-skill business context."
            else:
                skills_section = "No business skills are currently registered. Respond naturally to the user."

            account_context_section = build_account_context_prompt(Path(_data_root), account_id)
            
            sys_msg_content = f"""You are an intelligent business assistant embedded in a business system.
Your job is to chat with the user and automatically delegate tasks to specialized skills when appropriate.

{account_context_section}

{skills_section}

If no skill matches, respond naturally to the user — you can answer questions, provide guidance, or have a general conversation."""
            sys_msg_content_for_log = (
                sys_msg_content.replace(
                    account_context_section,
                    "[account_context_files: omitted from conversation log]",
                )
                if account_context_section
                else sys_msg_content
            )
            
            sys_msg = SystemMessage(content=sys_msg_content)
            human_parts = []
            recent_conversation = _recent_conversation_prompt(context)
            if recent_conversation:
                human_parts.append(recent_conversation)
            human_parts.append(f"Current user message:\n{prompt_with_attachments}")
            context_for_prompt = _context_for_prompt(context)
            if context_for_prompt:
                human_parts.append(f"\nContext: {json.dumps(context_for_prompt, ensure_ascii=False)}")
            human_msg = HumanMessage(content="\n".join(human_parts))

            llm_with_tools = chat_model.bind_tools(tools)
            
            # --- Conversation logging setup ---
            import datetime
            session_id = state.get("session_id")
            conversation_id = _conversation_id_for_root_frame(task_id, session_id, context)
            log_session_key = conversation_id or session_id
            _log_file = _conversation_log_file(_data_root, task_id, log_session_key)
            _log_file.parent.mkdir(parents=True, exist_ok=True)
            
            def _append_log(entry: dict) -> None:
                try:
                    entry.setdefault("task_id", task_id)
                    entry.setdefault("session_id", session_id)
                    entry.setdefault("conversation_id", conversation_id)
                    with open(_log_file, "a", encoding="utf-8") as f:
                        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
                except Exception:
                    pass
            
            _append_log({
                "ts": datetime.datetime.now().isoformat(),
                "event": "llm_request",
                "messages": [
                    {"role": "system", "content": sys_msg_content_for_log},
                    {"role": "user", "content": human_msg.content},
                ],
                "runtime_time_context": _runtime_time_context_for_log(state.get("runtime_context")),
                "routable_skills": [s.id for s in routable_skills],
            })
            
            try:
                final_msg = None
                for chunk in llm_with_tools.stream([sys_msg, human_msg]):
                    if chunk.content:
                        events.append(QueryEvent(
                            type="assistant_text",
                            content=chunk.content,
                            task_id=task_id,
                            model=state.get("model"),
                        ))
                    if final_msg is None:
                        final_msg = chunk
                    else:
                        final_msg += chunk
                
                # Log assistant response
                _append_log({
                    "ts": datetime.datetime.now().isoformat(),
                    "event": "llm_response",
                    "content": final_msg.content if final_msg else "",
                    "tool_calls": getattr(final_msg, "tool_calls", None),
                })
                
                # Check if there are tool calls
                if final_msg and getattr(final_msg, "tool_calls", None):
                    tc = final_msg.tool_calls[0]
                    if tc.get("name") == "invoke_business_skill":
                        args = tc.get("args", {})
                        candidate_skill_name = (
                            args.get("skill_name")
                            or args.get("skillName")
                            or args.get("skill_id")
                            or args.get("skillId")
                        )
                        if candidate_skill_name:
                            try:
                                skill_id = validate_skill_name(candidate_skill_name)
                            except SkillNameValidationError:
                                skill_id = None
                        if "instruction" in args:
                            context["skill_instruction"] = args["instruction"]
                        _append_log({
                            "ts": datetime.datetime.now().isoformat(),
                            "event": "skill_delegated",
                            "skill_id": skill_id,
                            "instruction": args.get("instruction"),
                        })
                else:
                    # No tool call made, pure conversational chat completed
                    events.append(QueryEvent(
                        type="result",
                        content=final_msg.content if final_msg else "",
                        task_id=task_id,
                        model=state.get("model"),
                        duration_ms=int((time.time() - state["started_at"]) * 1000),
                    ))
                    return {"events": events, "active_frame_id": None}
                    
            except Exception as e:
                import logging
                logging.getLogger(__name__).warning("Agentic routing failed: %s", e)
                _append_log({
                    "ts": datetime.datetime.now().isoformat(),
                    "event": "routing_error",
                    "error": str(e),
                })
        else:
            # Standalone routing (Legacy)
            llm_router = LlmSkillRouter(chat_model)
            if llm_router:
                llm_choice = llm_router.route(prompt_with_attachments, context, _skill_registry.list_skills())
                if llm_choice and _skill_registry.get_manifest(llm_choice):
                    skill_id = llm_choice

    if skill_id:
        # Create Frame via Runtime
        frame_id = _runtime.invoke_skill(
            task_id=task_id,
            skill_id=skill_id,
            skill_input=context,
        )
        events.append(QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=skill_id,
            content=f"Opening frame for skill: {skill_id}",
        ))
        return {"events": events, "active_frame_id": frame_id}
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
        return {"events": events, "active_frame_id": None}


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

    llm_skill_agent = _llm_skill_agent_for_state(state)
    if llm_skill_agent:
        skill_prompt = _skill_agent_prompt(state["prompt"], context)
        if frame.skill_id == ROOT_SKILL_ID:
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
        return {
            "events": llm_skill_agent.run(
                task_id=task_id,
                frame_id=frame_id,
                prompt=skill_prompt,
                account_id=account_id,
                runtime_context=runtime_context,
                persistent_frame=frame.skill_id == ROOT_SKILL_ID,
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
    if root is None or root.skill_id != ROOT_SKILL_ID:
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

    focus_manifest = _skill_registry.get_manifest(focus.skill_id)
    if focus_manifest is None:
        _resume_root_if_waiting_for_focus(root_frame_id)
        return [QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=focus.frame_id,
            parent_frame_id=root_frame_id,
            skill_id=focus.skill_id,
            error=f"Skill manifest not found: {focus.skill_id}",
        )]

    events: list[QueryEvent] = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=focus.frame_id,
            parent_frame_id=root_frame_id,
            skill_id=focus.skill_id,
            content=f"Resuming frame for skill: {focus.skill_id}",
        )
    ]

    if focus.status == FrameStatus.AWAITING_APPROVAL:
        approval_request = focus.approval_request
        if isinstance(approval_request, dict):
            _runtime.mark_child_awaiting_approval(
                root_frame_id,
                focus.frame_id,
                approval_request,
            )
        else:
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=focus.frame_id,
                parent_frame_id=root_frame_id,
                skill_id=focus.skill_id,
                error="Child skill is awaiting approval without approval_request",
            ))
        return events

    child_runtime_context = _runtime_context_for_child_skill(
        runtime_context,
        _runtime.context_summary_for_frame(root_frame_id),
        focus_manifest,
    )
    awaiting_user_context = _awaiting_user_context_for_focus(focus)
    if awaiting_user_context:
        child_runtime_context = dict(child_runtime_context or {})
        child_runtime_context["_awaiting_user_input"] = awaiting_user_context
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
                root_frame_id,
                refreshed_focus.frame_id,
                approval_request,
            )
        else:
            _resume_root_if_waiting_for_focus(root_frame_id)
            events.append(QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=refreshed_focus.frame_id,
                parent_frame_id=root_frame_id,
                skill_id=refreshed_focus.skill_id,
                error="Child skill is awaiting approval without approval_request",
            ))
        return events

    if refreshed_focus and refreshed_focus.status == FrameStatus.AWAITING_USER:
        _runtime.mark_child_awaiting_user(
            root_frame_id,
            refreshed_focus.frame_id,
            _awaiting_user_context_for_focus(refreshed_focus),
        )
        return events

    if not refreshed_focus or refreshed_focus.status != FrameStatus.COMPLETED:
        error = f"Child skill ended in {refreshed_focus.status.value if refreshed_focus else 'MISSING'}"
        _record_parent_child_recoverable_interruption(
            _runtime,
            parent_frame_id=root_frame_id,
            child_frame_id=refreshed_focus.frame_id if refreshed_focus else focus.frame_id,
            reason="child_skill_failed",
            error=error,
            task_id=task_id,
        )
        return events

    promoted = _runtime.complete_child_and_resume_parent(refreshed_focus.frame_id)
    events.append(QueryEvent(
        type="skill_frame_close",
        task_id=task_id,
        skill_frame_id=refreshed_focus.frame_id,
        parent_frame_id=root_frame_id,
        skill_id=refreshed_focus.skill_id,
        content=f"Frame closed: {refreshed_focus.skill_id}",
        execution_report_ref=promoted.get("execution_report_ref"),
        execution_report_digest=promoted.get("execution_report_digest"),
    ))

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
                skill_id=ROOT_SKILL_ID,
                error="Failed to submit direct child result: " + "; ".join(validation.errors),
            ))
        return events

    root_runtime_context = dict(runtime_context)
    root_context_summary = _runtime.context_summary_for_frame(root_frame_id)
    if root_context_summary:
        root_runtime_context["_visible_root_context_summary"] = root_context_summary

    events.extend(llm_skill_agent.run(
        task_id=task_id,
        frame_id=root_frame_id,
        prompt=prompt,
        account_id=account_id,
        runtime_context=root_runtime_context,
        persistent_frame=True,
    ))
    return events


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
        and frame.skill_id == ROOT_SKILL_ID
        and frame.status == FrameStatus.RUNNING
        and _is_current_turn_interrupted(frame, task_id)
    ):
        pass
    elif frame and frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING and frame.result_summary:
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
        and frame.skill_id == ROOT_SKILL_ID
        and frame.status == FrameStatus.WAITING_CHILD
        and frame.private_working_state.get("active_focus_status") == "AWAITING_USER"
    ):
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
        pass
    elif frame:
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
