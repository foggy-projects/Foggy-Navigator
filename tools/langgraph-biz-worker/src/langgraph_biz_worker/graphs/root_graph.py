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
from ..models import FrameStatus, QueryEvent, SkillManifest
from ..runtime.file_frame_journal import FileFrameJournal
from ..runtime.frame_store import FrameStore
from ..runtime.llm_skill_router import LlmSkillRouter, create_chat_model
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

# LLM-based Skill Router (None if llm_provider is empty → rule-based fallback)
_chat_model = create_chat_model(settings)
_llm_router: LlmSkillRouter | None = LlmSkillRouter(_chat_model) if _chat_model else None
_llm_skill_agent: LlmSkillAgent | None = (
    LlmSkillAgent(_chat_model, _runtime, settings.llm_skill_max_iterations, data_root=Path(_data_root))
    if _chat_model and settings.llm_execute_skills
    else None
)


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
    context: dict[str, Any] | None
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
    account_id = state.get("user_id") or context.get("account_id") or context.get("accountId")

    try:
        _skill_registry.load(account_id=account_id)
    except ValueError:
        _skill_registry.load()

    events = [
        QueryEvent(
            type="system",
            content="LangGraph Biz Worker processing query",
            task_id=task_id,
        ),
    ]

    # Three-level routing priority:
    # 0. Dynamic skill injection via markdown
    # 1. Explicit skill in context
    # 2. LLM routing (if enabled)
    # 3. Rule-based fallback (backward compat)
    skill_id = None

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

    # Priority 2: LLM routing
    if not skill_id and _llm_router:
        llm_choice = _llm_router.route(state["prompt"], context, _skill_registry.list_skills())
        if llm_choice and _skill_registry.get_manifest(llm_choice):
            skill_id = llm_choice

    # Priority 3: rule-based fallback
    if not skill_id:
        if context.get("order_id"):
            skill_id = "exception_triage"

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
        # No skill matched — return simple response (Phase 1 fallback)
        content_msg = f"Received business query: {state['prompt']}. No matching skill found."
        if "OPEN_TMS_PAGE" in state["prompt"]:
            content_msg += '\n\n{"type":"OPEN_TMS_PAGE","routeName":"OrderDetail","query":{"orderIdentifier":"1122"}}'
        
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
    account_id = state.get("user_id") or context.get("account_id") or context.get("accountId")

    if _llm_skill_agent:
        return {
            "events": _llm_skill_agent.run(
                task_id=task_id,
                frame_id=frame_id,
                prompt=state["prompt"],
                account_id=account_id,
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
        promoted = _runtime.close_frame(frame_id)
        skill_results.append(promoted)

        events.append(QueryEvent(
            type="skill_frame_close",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=frame.skill_id,
            content=f"Frame closed: {frame.skill_id}",
        ))
        events.append(QueryEvent(
            type="result",
            content=promoted.get("result_summary", "Skill completed"),
            task_id=task_id,
            model=state.get("model"),
            structured_output=promoted.get("structured_output"),
            duration_ms=int((time.time() - state["started_at"]) * 1000),
        ))
    elif frame:
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=frame_id,
            error=f"Frame ended in {frame.status.value}",
        ))

    return {"events": events, "skill_results": skill_results, "active_frame_id": None}


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
