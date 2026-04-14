"""Order Evidence Collect — child Skill that gathers order and vehicle data.

Phase 6+: invokes address_verify as a grandchild Skill (3-level nesting).
Call chain: exception_triage → order_evidence_collect → address_verify
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...models import QueryEvent
from ...runtime.child_skill_utils import run_child_skill
from ...runtime.skill_runtime import SkillRuntime
from ...tools.mock_biz_tools import mock_get_order, mock_get_vehicle_status
from .address_verify import verify_and_submit as address_verify


class EvidenceCollectState(TypedDict):
    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    events: list[QueryEvent]
    runtime: SkillRuntime


def collect_and_submit(state: EvidenceCollectState) -> dict:
    """Collect evidence from mock tools, invoke address_verify child, and submit result."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    task_id = state["task_id"]
    order_id = state["skill_input"].get("order_id", "unknown")

    order = mock_get_order(order_id)
    vehicle = mock_get_vehicle_status(order.get("vehicle_id", "V00"))

    # Invoke address_verify as grandchild (3-level nesting)
    addr_events, addr_promoted = run_child_skill(
        runtime=runtime,
        task_id=task_id,
        parent_frame_id=frame_id,
        child_skill_id="address_verify",
        child_input={"order_id": order_id},
        child_executor=address_verify,
    )

    # Record evidence on frame
    runtime.set_evidence_refs(frame_id, [f"order:{order_id}", f"vehicle:{order['vehicle_id']}"])

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
