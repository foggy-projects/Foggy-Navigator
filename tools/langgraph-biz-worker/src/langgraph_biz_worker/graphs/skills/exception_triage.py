"""Exception Triage Skill — programmatic subgraph simulating LLM behavior.

Phase 5: invokes two child skills (order_evidence_collect, rule_check),
aggregates their results, and submits a final diagnosis.
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...models import QueryEvent
from ...runtime.child_skill_utils import run_child_skill
from ...runtime.skill_runtime import SkillRuntime
from .order_evidence_collect import collect_and_submit as evidence_collect
from .rule_check import check_and_submit as rule_check


class TriageState(TypedDict):
    """Internal state for the exception triage skill subgraph."""

    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    evidence: dict[str, Any]
    analysis: dict[str, Any]
    events: list[QueryEvent]
    runtime: SkillRuntime
    child_results: dict[str, Any]


# ---------------------------------------------------------------------------
# Triage steps
# ---------------------------------------------------------------------------


def invoke_evidence_child(state: TriageState) -> dict:
    """Invoke order_evidence_collect as a child Skill."""
    events, promoted = run_child_skill(
        runtime=state["runtime"],
        task_id=state["task_id"],
        parent_frame_id=state["frame_id"],
        child_skill_id="order_evidence_collect",
        child_input=state["skill_input"],
        child_executor=evidence_collect,
    )

    # Extract evidence from child result
    evidence = {}
    so = promoted.get("structured_output", {})
    if so:
        evidence["order"] = so.get("order_details", {})
        evidence["vehicle"] = so.get("vehicle_details", {})

    return {"events": events, "evidence": evidence}


def analyze(state: TriageState) -> dict:
    """Analyze evidence and produce classification (simulated LLM reasoning)."""
    vehicle = state["evidence"].get("vehicle", {})
    order = state["evidence"].get("order", {})

    if vehicle.get("status") == "breakdown":
        classification = "vehicle_delay"
        action = "manual_dispatch"
        confidence = 0.92
    elif order.get("delay_minutes", 0) > 60:
        classification = "system_error"
        action = "escalate"
        confidence = 0.75
    else:
        classification = "other"
        action = "auto_retry"
        confidence = 0.60

    analysis = {
        "classification": classification,
        "recommended_action": action,
        "confidence": confidence,
    }

    return {"analysis": analysis}


def invoke_rule_check_child(state: TriageState) -> dict:
    """Invoke rule_check as a second child Skill."""
    analysis = state["analysis"]
    child_input = {
        **state["skill_input"],
        "classification": analysis["classification"],
        "recommended_action": analysis["recommended_action"],
    }

    events, promoted = run_child_skill(
        runtime=state["runtime"],
        task_id=state["task_id"],
        parent_frame_id=state["frame_id"],
        child_skill_id="rule_check",
        child_input=child_input,
        child_executor=rule_check,
    )

    return {"events": events, "rule_check_result": promoted}


def submit_result(state: TriageState) -> dict:
    """Submit aggregated analysis with rule check status."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    analysis = state["analysis"]

    # Collect evidence refs from all child results
    parent_frame = runtime.get_frame(frame_id)
    if parent_frame:
        child_results = parent_frame.private_working_state.get("child_results", {})
        all_evidence = []
        for cr in child_results.values():
            all_evidence.extend(cr.get("evidence_refs", []))
        if all_evidence:
            runtime.set_evidence_refs(frame_id, all_evidence)

    summary = (
        f"Order diagnosed as {analysis['classification']}, "
        f"recommend {analysis['recommended_action']} "
        f"(confidence: {analysis['confidence']:.0%})"
    )

    result = runtime.submit_result(
        frame_id=frame_id,
        summary=summary,
        structured_output=analysis,
    )

    events: list[QueryEvent] = []
    if not result.ok:
        events.append(QueryEvent(
            type="error",
            content=f"Submit rejected: {result.errors}",
            task_id=state["task_id"],
            skill_frame_id=frame_id,
            skill_id="exception_triage",
        ))

    return {"events": events}
