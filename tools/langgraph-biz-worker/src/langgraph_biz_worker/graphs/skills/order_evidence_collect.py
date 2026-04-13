"""Order Evidence Collect — child Skill that gathers order and vehicle data.

This is a simple leaf skill that collects evidence and submits results.
It does not invoke further child skills.
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...models import QueryEvent
from ...runtime.skill_runtime import SkillRuntime
from ...tools.mock_biz_tools import mock_get_order, mock_get_vehicle_status


class EvidenceCollectState(TypedDict):
    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    events: list[QueryEvent]
    runtime: SkillRuntime


def collect_and_submit(state: EvidenceCollectState) -> dict:
    """Collect evidence from mock tools and submit result."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    order_id = state["skill_input"].get("order_id", "unknown")

    order = mock_get_order(order_id)
    vehicle = mock_get_vehicle_status(order.get("vehicle_id", "V00"))

    # Record evidence on frame
    frame = runtime.get_frame(frame_id)
    if frame:
        frame.evidence_refs = [f"order:{order_id}", f"vehicle:{order['vehicle_id']}"]
        runtime.store.save(frame)

    structured_output = {
        "order_details": order,
        "vehicle_details": vehicle,
    }

    result = runtime.submit_result(
        frame_id=frame_id,
        summary=f"Collected evidence for order {order_id}",
        structured_output=structured_output,
    )

    events = [
        QueryEvent(
            type="assistant_text",
            content=f"[order_evidence_collect] Collected order {order_id} data",
            task_id=state["task_id"],
            skill_frame_id=frame_id,
            skill_id="order_evidence_collect",
        ),
    ]

    if not result.ok:
        events.append(QueryEvent(
            type="error",
            content=f"Evidence collect submit rejected: {result.errors}",
            task_id=state["task_id"],
            skill_frame_id=frame_id,
            skill_id="order_evidence_collect",
        ))

    return {"events": events}
