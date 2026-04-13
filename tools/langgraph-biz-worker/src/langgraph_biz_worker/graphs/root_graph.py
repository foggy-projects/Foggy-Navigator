"""Root Graph for LangGraph Biz Worker.

Phase 1: minimal single-node graph that returns a fixed result.
Phase 2+: will add skill routing, frame management, and subgraph invocation.
"""

from __future__ import annotations

import time
from typing import Any, TypedDict

from langgraph.graph import END, StateGraph

from ..models import QueryEvent


# ---------------------------------------------------------------------------
# Graph state
# ---------------------------------------------------------------------------


class RootState(TypedDict):
    """Root task state flowing through the graph.

    Phase 1 keeps it minimal. Phase 2 will extend with:
    - active_frame_id
    - skill_results
    - artifact_index
    - decision_log
    """

    task_id: str
    session_id: str | None
    prompt: str
    model: str | None
    context: dict[str, Any] | None
    events: list[QueryEvent]
    started_at: float


# ---------------------------------------------------------------------------
# Graph nodes
# ---------------------------------------------------------------------------


def process_query(state: RootState) -> dict:
    """Process the user query and produce SSE events.

    Phase 1: generates a fixed result without calling any LLM.
    Phase 2+: will route to skill subgraphs and call LLM.
    """
    task_id = state["task_id"]
    prompt = state["prompt"]
    started_at = state["started_at"]

    events: list[QueryEvent] = [
        QueryEvent(
            type="system",
            content="LangGraph Biz Worker processing query",
            task_id=task_id,
        ),
        QueryEvent(
            type="assistant_text",
            content=f"Received business query: {prompt}. "
            "This is a Phase 1 fixed response from the LangGraph Root Graph. "
            "Skill Runtime and real LLM integration will be available in Phase 2.",
            task_id=task_id,
            model=state.get("model"),
        ),
        QueryEvent(
            type="result",
            content="Query processed successfully",
            task_id=task_id,
            model=state.get("model"),
            duration_ms=int((time.time() - started_at) * 1000),
        ),
    ]

    return {"events": events}


# ---------------------------------------------------------------------------
# Graph construction
# ---------------------------------------------------------------------------


def build_root_graph() -> StateGraph:
    """Build and compile the root graph.

    Returns the compiled graph ready for invocation.
    """
    graph = StateGraph(RootState)
    graph.add_node("process_query", process_query)
    graph.set_entry_point("process_query")
    graph.add_edge("process_query", END)
    return graph.compile()


# Pre-built compiled graph instance
root_graph = build_root_graph()
