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
from urllib.parse import urlsplit, urlunsplit

from langgraph.graph import END, StateGraph

from ..config import settings
from ..models import FrameStatus, QueryEvent, SkillManifest
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.frame_store import FrameStore
from ..runtime.account_context_files import build_account_context_prompt
from ..runtime.llm_skill_router import LlmSkillRouter, create_chat_model, create_chat_model_from_config
from ..runtime.llm_skill_agent import LlmSkillAgent
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
    session_stem = _safe_log_stem(session_id or "_no-session")
    task_stem = _safe_log_stem(task_id)
    log_dir = Path(data_root) / "logs" / "llm-conversations" / session_stem

    for existing in sorted(log_dir.glob(f"*_{task_stem}.jsonl")):
        return existing

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
            "When the current user turn is ready to answer, call submit_skill_result. "
            "This root skill is persistent; submit_skill_result ends the current turn, "
            "not the root skill frame."
        ),
        allowed_tools=["invoke_business_function", "invoke_business_skill", "submit_skill_result"],
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


def _get_or_create_system_root_frame(task_id: str, context: dict[str, Any]) -> tuple[str, bool]:
    """Return the persistent root frame for this task, restoring it if needed."""
    for frame in _runtime.get_frames_by_task(task_id):
        if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
            return frame.frame_id, False

    for frame in _journal.load_by_task(task_id):
        if frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING:
            _runtime.restore_frame(frame)
            return frame.frame_id, False

    frame_id = _runtime.invoke_skill(
        task_id=task_id,
        skill_id=ROOT_SKILL_ID,
        skill_input=context,
    )
    return frame_id, True


def _build_attachment_context_prompt(attachments: list[dict[str, Any]] | None) -> str:
    if not attachments:
        return ""
    safe_attachments = [_safe_attachment_summary(item) for item in attachments if isinstance(item, dict)]
    if not safe_attachments:
        return ""
    return "Attachments provided by upstream system:\n" + json.dumps(
        safe_attachments,
        ensure_ascii=False,
        indent=2,
    )


def _safe_attachment_summary(item: dict[str, Any]) -> dict[str, Any]:
    aliases = {
        "id": ("id", "attachmentId", "attachment_id"),
        "name": ("name", "fileName", "filename"),
        "mimeType": ("mimeType", "mime_type", "contentType", "content_type"),
        "size": ("size", "sizeBytes", "size_bytes"),
        "kind": ("kind", "type"),
        "provider": ("provider",),
        "url": ("url", "href"),
    }
    summary: dict[str, Any] = {}
    for output_key, keys in aliases.items():
        for key in keys:
            value = item.get(key)
            if value is not None and value != "":
                summary[output_key] = _safe_attachment_value(output_key, value)
                break

    metadata = item.get("metadata")
    if isinstance(metadata, dict):
        safe_metadata = {
            str(key): _truncate_text(str(value), 240)
            for key, value in metadata.items()
            if (
                isinstance(value, (str, int, float, bool))
                and value is not None
                and not _is_sensitive_key(str(key))
            )
        }
        if safe_metadata:
            summary["metadata"] = safe_metadata
    return summary


def _is_sensitive_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    return any(part in normalized for part in ("token", "secret", "password", "credential", "api_key", "apikey"))


def _safe_attachment_value(key: str, value: Any) -> Any:
    if key == "size" and isinstance(value, (int, float)):
        return value
    text = str(value)
    if key == "url":
        try:
            parts = urlsplit(text)
            text = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
        except ValueError:
            pass
    return _truncate_text(text, 500)


def _truncate_text(value: str, max_len: int) -> str:
    return value if len(value) <= max_len else value[:max_len] + "...[truncated]"


def _chat_model_for_state(state: RootState):
    llm_config = state.get("llm_config")
    if llm_config:
        return create_chat_model_from_config(llm_config)
    return _chat_model


def _llm_skill_agent_for_state(state: RootState) -> LlmSkillAgent | None:
    llm_config = state.get("llm_config")
    if not llm_config:
        return _llm_skill_agent
    if not settings.llm_execute_skills:
        return None
    chat_model = create_chat_model_from_config(llm_config)
    if not chat_model:
        return None
    return LlmSkillAgent(chat_model, _runtime, settings.llm_skill_max_iterations, data_root=Path(_data_root))


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
    context: dict[str, Any] | None
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
    context = state.get("context") or {}
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

    if _should_use_system_root_skill(state):
        frame_id, created = _get_or_create_system_root_frame(task_id, context)
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
    if markdown_body and context.get("skillId"):
        # Synthesize a temporary manifest to execute
        manifest = SkillManifest(
            id=context.get("skillId"),
            name=context.get("skillId"),
            markdown_body=markdown_body,
            allowed_tools=[] # Tools can be registered if needed
        )
        _skill_registry.register(manifest)
        skill_id = manifest.id

    # Priority 1: explicit skill in context
    explicit = context.get("skill") or context.get("skillId")
    if not skill_id and explicit and _skill_registry.get_manifest(explicit):
        skill_id = explicit

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
                    "description": "Delegate the user's request to a specialized business skill. Use this when the user asks to perform an action that matches one of the available skills.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "skill_id": {
                                "type": "string",
                                "description": "The exact ID of the skill to invoke."
                            },
                            "instruction": {
                                "type": "string",
                                "description": "Natural language instructions for the skill, summarizing what needs to be done and any extracted entities (e.g. order numbers) from the user's request."
                            }
                        },
                        "required": ["skill_id", "instruction"]
                    }
                }
            }
            tools = [invoke_tool]
            
            if routable_skills:
                skills_summary = "\n".join([f"- {s.id}: {s.description}" for s in routable_skills])
                skills_section = f"Available Skills:\n{skills_summary}\n\nIf the user's request matches a skill, use the `invoke_business_skill` tool to delegate the work.\nProvide the `skill_id` and a detailed `instruction` based on the user's input."
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
            human_parts = [prompt_with_attachments]
            if context:
                human_parts.append(f"\nContext: {json.dumps(context, ensure_ascii=False)}")
            human_msg = HumanMessage(content="\n".join(human_parts))

            llm_with_tools = chat_model.bind_tools(tools)
            
            # --- Conversation logging setup ---
            import datetime
            session_id = state.get("session_id")
            _log_file = _conversation_log_file(_data_root, task_id, session_id)
            _log_file.parent.mkdir(parents=True, exist_ok=True)
            
            def _append_log(entry: dict) -> None:
                try:
                    entry.setdefault("task_id", task_id)
                    entry.setdefault("session_id", session_id)
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
                        if "skill_id" in args:
                            skill_id = args["skill_id"]
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
    context = state.get("context") or {}
    account_id = _account_id_from_state(state)
    runtime_context = dict(state.get("runtime_context") or {})
    client_app_id = context.get("client_app_id") or context.get("clientAppId")
    if client_app_id:
        runtime_context.setdefault("client_app_id", client_app_id)

    llm_skill_agent = _llm_skill_agent_for_state(state)
    if llm_skill_agent:
        skill_prompt = _skill_agent_prompt(state["prompt"], context)
        return {
            "events": llm_skill_agent.run(
                task_id=task_id,
                frame_id=frame_id,
                prompt=_prompt_with_attachment_context(skill_prompt, state.get("attachments")),
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
        ))
        events.append(QueryEvent(
            type="result",
            content=result_summary,
            task_id=task_id,
            model=state.get("model"),
            structured_output=structured_output,
            duration_ms=int((time.time() - state["started_at"]) * 1000),
        ))
    elif frame and frame.skill_id == ROOT_SKILL_ID and frame.status == FrameStatus.RUNNING and frame.result_summary:
        events.append(QueryEvent(
            type="result",
            content=frame.result_summary,
            task_id=task_id,
            model=state.get("model"),
            structured_output=frame.output,
            duration_ms=int((time.time() - state["started_at"]) * 1000),
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
