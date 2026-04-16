"""Rule Check — child Skill that validates recommendations against business rules.

Simple leaf skill: checks whether the proposed action is valid given incident history.
"""

from __future__ import annotations

from typing import Any, TypedDict

from ...models import QueryEvent
from ...runtime.skill_runtime import SkillRuntime
from ...tools.mock_biz_tools import mock_search_incidents


class RuleCheckState(TypedDict):
    task_id: str
    frame_id: str
    skill_input: dict[str, Any]
    events: list[QueryEvent]
    runtime: SkillRuntime


def check_and_submit(state: RuleCheckState) -> dict:
    """Check business rules and submit result."""
    runtime: SkillRuntime = state["runtime"]
    frame_id = state["frame_id"]
    skill_input = state["skill_input"]

    order_id = skill_input.get("order_id", "unknown")
    classification = skill_input.get("classification", "")
    recommended_action = skill_input.get("recommended_action", "")

    # Search for related incidents
    incidents = mock_search_incidents(f"order:{order_id}")

    # Simple rule-based check
    checked_rules = [
        "max_manual_dispatch_per_day",
        "escalation_threshold",
        "auto_retry_cooldown",
    ]
    violations = []

    # Simulated rule: if vehicle breakdown and action is auto_retry, flag as violation
    if classification == "vehicle_delay" and recommended_action == "auto_retry":
        violations.append("Cannot auto_retry when vehicle is broken down")

    rule_passed = len(violations) == 0

    structured_output = {
        "rule_passed": rule_passed,
        "checked_rules": checked_rules,
        "violations": violations,
        "incident_count": len(incidents),
    }

    result = runtime.submit_result(
        frame_id=frame_id,
        summary=f"Rule check {'passed' if rule_passed else 'failed'} for order {order_id}",
        structured_output=structured_output,
    )

    events = [
        QueryEvent(
            type="assistant_text",
            content=f"[rule_check] Checked {len(checked_rules)} rules, "
            f"{'all passed' if rule_passed else f'{len(violations)} violation(s)'}",
            task_id=state["task_id"],
            skill_frame_id=frame_id,
            skill_id="rule_check",
        ),
    ]

    if not result.ok:
        events.append(QueryEvent(
            type="error",
            content=f"Rule check submit rejected: {result.errors}",
            task_id=state["task_id"],
            skill_frame_id=frame_id,
            skill_id="rule_check",
        ))

    return {"events": events}
