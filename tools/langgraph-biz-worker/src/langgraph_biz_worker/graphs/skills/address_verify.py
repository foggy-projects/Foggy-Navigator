"""Address Verify Skill — mock child of order_evidence_collect for 3-level nesting test.

Call chain: exception_triage → order_evidence_collect → address_verify
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...runtime.skill_runtime import SkillRuntime


class AddressVerifyState(TypedDict):
    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    events: list
    runtime: SkillRuntime


def verify_and_submit(state: AddressVerifyState) -> dict:
    """Mock address verification: always returns reachable."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    order_id = state["skill_input"].get("order_id", "UNKNOWN")

    runtime.submit_result(
        frame_id=frame_id,
        summary=f"Address for order {order_id} is reachable",
        structured_output={
            "reachable": True,
            "address": f"Mock address for {order_id}",
        },
    )

    return {"events": []}
