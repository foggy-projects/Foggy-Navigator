"""Order Evidence Collect — child Skill that gathers order and vehicle data.

Phase 6+: invokes address_verify as a grandchild Skill (3-level nesting).
Call chain: exception_triage → order_evidence_collect → address_verify
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...models import QueryEvent
from ...runtime.skill_runtime import SkillRuntime
from ...tools.mock_biz_tools import mock_get_order, mock_get_vehicle_status
from .address_verify import verify_and_submit as address_verify


class EvidenceCollectState(TypedDict):
    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    events: list[QueryEvent]
    runtime: SkillRuntime


def _run_child(
    runtime: SkillRuntime,
    task_id: str,
    parent_frame_id: str,
    child_skill_id: str,
    child_input: dict[str, Any],
    child_executor,
) -> tuple[list[QueryEvent], dict[str, Any]]:
    """Same pattern as exception_triage._run_child — uses Runtime composite API."""
    child_frame_id = runtime.invoke_child_skill(
        parent_frame_id, child_skill_id, child_input,
    )

    events = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=child_frame_id,
            skill_id=child_skill_id,
            content=f"Opening child frame: {child_skill_id}",
        ),
    ]

    child_state = {
        "task_id": task_id,
        "frame_id": child_frame_id,
        "skill_input": child_input,
        "events": [],
        "runtime": runtime,
    }
    child_result = child_executor(child_state)
    events.extend(child_result.get("events", []))

    child_frame = runtime.get_frame(child_frame_id)
    promoted = {}
    if child_frame and child_frame.status.value == "COMPLETED":
        promoted = runtime.complete_child_and_resume_parent(child_frame_id)
        events.append(QueryEvent(
            type="skill_frame_close",
            task_id=task_id,
            skill_frame_id=child_frame_id,
            skill_id=child_skill_id,
            content=f"Closed child frame: {child_skill_id}",
        ))

    return events, promoted


def collect_and_submit(state: EvidenceCollectState) -> dict:
    """Collect evidence from mock tools, invoke address_verify child, and submit result."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    task_id = state["task_id"]
    order_id = state["skill_input"].get("order_id", "unknown")

    order = mock_get_order(order_id)
    vehicle = mock_get_vehicle_status(order.get("vehicle_id", "V00"))

    # Invoke address_verify as grandchild (3-level nesting)
    addr_events, addr_promoted = _run_child(
        runtime=runtime,
        task_id=task_id,
        parent_frame_id=frame_id,
        child_skill_id="address_verify",
        child_input={"order_id": order_id},
        child_executor=address_verify,
    )

    # Record evidence on frame
    frame = runtime.get_frame(frame_id)
    if frame:
        frame.evidence_refs = [f"order:{order_id}", f"vehicle:{order['vehicle_id']}"]
        runtime.store.save(frame)

    structured_output = {
        "order_details": order,
        "vehicle_details": vehicle,
        "address_verified": addr_promoted.get("structured_output", {}).get("reachable", False),
    }

    result = runtime.submit_result(
        frame_id=frame_id,
        summary=f"Collected evidence for order {order_id}",
        structured_output=structured_output,
    )

    events = list(addr_events)
    events.append(QueryEvent(
        type="assistant_text",
        content=f"[order_evidence_collect] Collected order {order_id} data (address verified)",
        task_id=task_id,
        skill_frame_id=frame_id,
        skill_id="order_evidence_collect",
    ))

    if not result.ok:
        events.append(QueryEvent(
            type="error",
            content=f"Evidence collect submit rejected: {result.errors}",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id="order_evidence_collect",
        ))

    return {"events": events}
