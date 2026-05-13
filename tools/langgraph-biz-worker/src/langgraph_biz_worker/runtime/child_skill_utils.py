"""Shared utilities for child Skill invocation within Skill subgraphs.

Extracted from exception_triage._run_child and order_evidence_collect._run_child
to eliminate duplication.
"""

from __future__ import annotations

from typing import Any, Callable

from ..models import FrameStatus, QueryEvent
from .skill_runtime import SkillRuntime


def run_child_skill(
    runtime: SkillRuntime,
    task_id: str,
    parent_frame_id: str,
    child_skill_id: str,
    child_input: dict[str, Any],
    child_executor: Callable[[dict], dict],
) -> tuple[list[QueryEvent], dict[str, Any]]:
    """Invoke, execute, complete a child skill using SkillRuntime standard API.

    Returns (events, promoted_result).
    """
    child_frame_id = runtime.invoke_child_skill(
        parent_frame_id, child_skill_id, child_input,
    )

    events: list[QueryEvent] = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=child_frame_id,
            parent_frame_id=parent_frame_id,
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
    promoted: dict[str, Any] = {}
    if child_frame and child_frame.status == FrameStatus.COMPLETED:
        promoted = runtime.complete_child_and_resume_parent(child_frame_id)
        events.append(QueryEvent(
            type="skill_frame_close",
            task_id=task_id,
            skill_frame_id=child_frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=child_skill_id,
            content=f"Closed child frame: {child_skill_id}",
        ))

    return events, promoted
