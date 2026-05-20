"""Child skill invocation and recoverable-child recovery helpers."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from ..models import FrameStatus, QueryEvent, SkillManifest
from .llm_tool_call_codec import _child_approval_report_payload
from .skill_runtime import SkillRuntime


RunChildFrame = Callable[..., list[QueryEvent]]


def _invoke_business_skill_tool(
    runtime: SkillRuntime,
    *,
    frame_id: str,
    args: dict[str, Any],
    task_id: str,
    account_id: str | None,
    runtime_context: dict[str, Any] | None,
    persistent_frame: bool,
    run_child_frame: RunChildFrame,
) -> dict[str, Any]:
    child_skill_id = (
        args.get("skill_name")
        or args.get("skillName")
        or args.get("skill_id")
        or args.get("skillId")
    )
    if not child_skill_id:
        return {"ok": False, "error": "skill_name is required"}
    if child_skill_id == runtime.get_frame(frame_id).skill_id:
        return {"ok": False, "error": "A skill cannot invoke itself"}
    child_manifest = runtime.registry.get_manifest(child_skill_id)
    if not child_manifest:
        return {"ok": False, "error": f"Skill manifest not found: {child_skill_id}"}

    instruction = args.get("instruction") or args.get("prompt") or ""
    child_input = args.get("input") if isinstance(args.get("input"), dict) else {}
    child_runtime_context = _runtime_context_for_child_skill(
        runtime_context,
        runtime.context_summary_for_frame(frame_id),
        child_manifest,
    )
    child_frame_id = runtime.invoke_child_skill(
        parent_frame_id=frame_id,
        child_skill_id=child_skill_id,
        child_input=child_input,
    )
    child_events = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=child_frame_id,
            parent_frame_id=frame_id,
            skill_id=child_skill_id,
            content=f"Opening frame for skill: {child_skill_id}",
        )
    ]
    child_events.extend(run_child_frame(
        task_id=task_id,
        frame_id=child_frame_id,
        prompt=instruction,
        account_id=account_id,
        runtime_context=child_runtime_context,
    ))

    child = runtime.get_frame(child_frame_id)
    if child and child.status == FrameStatus.AWAITING_APPROVAL:
        approval_request = child.approval_request
        if not isinstance(approval_request, dict):
            return {
                "ok": False,
                "error": "Child skill is awaiting approval without approval_request",
                "_events": child_events,
            }
        runtime.mark_child_awaiting_approval(
            frame_id,
            child_frame_id,
            approval_request,
        )
        report_payload = _child_approval_report_payload(child)
        return {
            "ok": True,
            "approval_wait": True,
            "child_frame_id": child_frame_id,
            **report_payload,
            "_events": child_events,
            "_suspended": True,
        }
    if child and child.status == FrameStatus.AWAITING_USER:
        runtime.mark_child_awaiting_user(
            frame_id,
            child_frame_id,
            _awaiting_user_input_from_child(child),
        )
        return {
            "ok": True,
            "awaiting_user": True,
            "turn_status": "WAITING_FOR_USER_INPUT",
            "child_frame_id": child_frame_id,
            "result": {
                "frame_id": child.frame_id,
                "skill_id": child.skill_id,
                "result_summary": child.result_summary,
                "structured_output": child.output or {},
                "artifact_refs": list(child.artifact_refs),
                "evidence_refs": list(child.evidence_refs),
            },
            "_events": child_events,
            "_suspended": True,
        }
    if not child or child.status != FrameStatus.COMPLETED:
        error = f"Child skill ended in {child.status.value if child else 'MISSING'}"
        if persistent_frame:
            _record_parent_child_recoverable_interruption(
                runtime,
                parent_frame_id=frame_id,
                child_frame_id=child.frame_id if child else child_frame_id,
                reason="child_skill_failed",
                error=error,
                task_id=task_id,
            )
            return {
                "ok": False,
                "error": error,
                "_events": child_events,
                "_suspended": True,
            }
        _resume_parent_if_waiting(runtime, frame_id)
        runtime.fail_frame(frame_id, error)
        return {
            "ok": False,
            "error": error,
            "_events": child_events,
        }

    promoted = runtime.complete_child_and_resume_parent(child_frame_id)
    child_events.append(QueryEvent(
        type="skill_frame_close",
        task_id=task_id,
        skill_frame_id=child_frame_id,
        parent_frame_id=frame_id,
        skill_id=child_skill_id,
        content=f"Frame closed: {child_skill_id}",
        execution_report_ref=promoted.get("execution_report_ref"),
        execution_report_digest=promoted.get("execution_report_digest"),
    ))
    direct_result = _direct_child_result_for_user(promoted)
    if persistent_frame and direct_result is not None:
        validation = runtime.submit_persistent_turn_result(
            frame_id=frame_id,
            summary=direct_result["summary"],
            structured_output=direct_result["structured_output"],
            artifact_refs=direct_result.get("artifact_refs"),
            evidence_refs=direct_result.get("evidence_refs"),
        )
        response = {
            "ok": validation.ok,
            "direct_result": True,
            "errors": validation.errors,
            "result": promoted,
            "_events": child_events,
        }
        if validation.ok:
            response["_suspended"] = True
        return response
    return {"ok": True, "result": promoted, "_events": child_events}


def _resume_recoverable_child_skill_tool(
    runtime: SkillRuntime,
    *,
    frame_id: str,
    args: dict[str, Any],
    task_id: str,
    account_id: str | None,
    runtime_context: dict[str, Any] | None,
    persistent_frame: bool,
    run_child_frame: RunChildFrame,
) -> dict[str, Any]:
    if not persistent_frame:
        return {"ok": False, "error": "resume_recoverable_child_skill is only available on persistent frames"}
    parent = runtime.get_frame(frame_id)
    if not parent:
        return {"ok": False, "error": f"Frame not found: {frame_id}"}
    child = runtime.prepare_recoverable_child_resume(frame_id)
    if child is None:
        return {"ok": False, "error": "No recoverable child skill is pending"}
    child_manifest = runtime.registry.get_manifest(child.skill_id)
    if not child_manifest:
        return {"ok": False, "error": f"Skill manifest not found: {child.skill_id}"}

    instruction = args.get("instruction") or args.get("prompt") or ""
    child_runtime_context = _runtime_context_for_child_skill(
        runtime_context,
        runtime.context_summary_for_frame(frame_id),
        child_manifest,
    )
    child_events = [
        QueryEvent(
            type="skill_frame_open",
            task_id=task_id,
            skill_frame_id=child.frame_id,
            parent_frame_id=frame_id,
            skill_id=child.skill_id,
            content=f"Resuming frame for skill: {child.skill_id}",
        )
    ]
    child_events.extend(run_child_frame(
        task_id=task_id,
        frame_id=child.frame_id,
        prompt=instruction,
        account_id=account_id,
        runtime_context=child_runtime_context,
    ))

    refreshed_child = runtime.get_frame(child.frame_id)
    if refreshed_child and refreshed_child.status == FrameStatus.AWAITING_APPROVAL:
        approval_request = refreshed_child.approval_request
        if not isinstance(approval_request, dict):
            _resume_parent_if_waiting(runtime, frame_id)
            return {
                "ok": False,
                "error": "Child skill is awaiting approval without approval_request",
                "_events": child_events,
            }
        runtime.mark_child_awaiting_approval(
            frame_id,
            child.frame_id,
            approval_request,
        )
        report_payload = _child_approval_report_payload(refreshed_child)
        return {
            "ok": True,
            "approval_wait": True,
            "child_frame_id": child.frame_id,
            **report_payload,
            "_events": child_events,
            "_suspended": True,
        }
    if refreshed_child and refreshed_child.status == FrameStatus.AWAITING_USER:
        runtime.mark_child_awaiting_user(
            frame_id,
            refreshed_child.frame_id,
            _awaiting_user_input_from_child(refreshed_child),
        )
        return {
            "ok": True,
            "awaiting_user": True,
            "turn_status": "WAITING_FOR_USER_INPUT",
            "child_frame_id": refreshed_child.frame_id,
            "result": {
                "frame_id": refreshed_child.frame_id,
                "skill_id": refreshed_child.skill_id,
                "result_summary": refreshed_child.result_summary,
                "structured_output": refreshed_child.output or {},
                "artifact_refs": list(refreshed_child.artifact_refs),
                "evidence_refs": list(refreshed_child.evidence_refs),
            },
            "_events": child_events,
            "_suspended": True,
        }
    if not refreshed_child or refreshed_child.status != FrameStatus.COMPLETED:
        error = f"Child skill ended in {refreshed_child.status.value if refreshed_child else 'MISSING'}"
        _record_parent_child_recoverable_interruption(
            runtime,
            parent_frame_id=frame_id,
            child_frame_id=refreshed_child.frame_id if refreshed_child else child.frame_id,
            reason="child_skill_failed",
            error=error,
            task_id=task_id,
        )
        return {
            "ok": False,
            "error": error,
            "child_frame_id": child.frame_id,
            "_events": child_events,
            "_suspended": True,
        }

    promoted = runtime.complete_child_and_resume_parent(child.frame_id)
    child_events.append(QueryEvent(
        type="skill_frame_close",
        task_id=task_id,
        skill_frame_id=child.frame_id,
        parent_frame_id=frame_id,
        skill_id=child.skill_id,
        content=f"Frame closed: {child.skill_id}",
        execution_report_ref=promoted.get("execution_report_ref"),
        execution_report_digest=promoted.get("execution_report_digest"),
    ))
    direct_result = _direct_child_result_for_user(promoted)
    if direct_result is not None:
        validation = runtime.submit_persistent_turn_result(
            frame_id=frame_id,
            summary=direct_result["summary"],
            structured_output=direct_result["structured_output"],
            artifact_refs=direct_result.get("artifact_refs"),
            evidence_refs=direct_result.get("evidence_refs"),
        )
        response = {
            "ok": validation.ok,
            "intent_resolution": "CONTINUE_PREVIOUS",
            "direct_result": True,
            "errors": validation.errors,
            "result": promoted,
            "child_frame_id": child.frame_id,
            "_events": child_events,
        }
        if validation.ok:
            response["_suspended"] = True
        return response
    return {
        "ok": True,
        "intent_resolution": "CONTINUE_PREVIOUS",
        "result": promoted,
        "child_frame_id": child.frame_id,
        "_events": child_events,
    }


def _direct_child_result_for_user(
    promoted: dict[str, Any],
    *,
    allow_legacy_completed: bool = False,
) -> dict[str, Any] | None:
    """Return normalized direct-turn payload when a child result is user-final."""
    if not isinstance(promoted, dict):
        return None

    structured_output = promoted.get("structured_output")
    if not isinstance(structured_output, dict):
        structured_output = {}

    requires_parent_synthesis = _optional_bool(
        promoted.get("requires_parent_synthesis"),
        promoted.get("requiresParentSynthesis"),
        structured_output.get("requires_parent_synthesis"),
        structured_output.get("requiresParentSynthesis"),
    )
    if requires_parent_synthesis is True:
        return None

    remaining_work = (
        promoted.get("remaining_work")
        if "remaining_work" in promoted
        else promoted.get("remainingWork")
    )
    if remaining_work is None:
        remaining_work = (
            structured_output.get("remaining_work")
            if "remaining_work" in structured_output
            else structured_output.get("remainingWork")
        )
    if _has_remaining_work(remaining_work):
        return None

    statuses = _normalized_statuses(promoted, structured_output)
    terminal_statuses = {
        "FINAL_FOR_USER",
        "COMPLETED",
        "COMPLETE",
        "DONE",
        "SUCCESS",
        "SUCCEEDED",
        "SUBMITTED",
    }
    direct = False
    if "FINAL_FOR_USER" in statuses:
        direct = True
    elif requires_parent_synthesis is False:
        direct = True
    elif allow_legacy_completed and statuses & terminal_statuses:
        direct = True
    if not direct:
        return None

    summary = _first_non_empty_string(
        structured_output.get("user_message"),
        structured_output.get("userMessage"),
        structured_output.get("message"),
        promoted.get("user_message"),
        promoted.get("userMessage"),
        promoted.get("result_summary"),
        promoted.get("summary"),
    )
    if not summary:
        return None

    return {
        "summary": summary,
        "structured_output": structured_output,
        "artifact_refs": _list_or_none(promoted.get("artifact_refs")),
        "evidence_refs": _list_or_none(promoted.get("evidence_refs")),
    }


def _normalized_statuses(
    promoted: dict[str, Any],
    structured_output: dict[str, Any],
) -> set[str]:
    candidates = [
        promoted.get("turn_status"),
        promoted.get("turnStatus"),
        promoted.get("next_step"),
        promoted.get("nextStep"),
        promoted.get("status"),
        structured_output.get("turn_status"),
        structured_output.get("turnStatus"),
        structured_output.get("next_step"),
        structured_output.get("nextStep"),
        structured_output.get("status"),
    ]
    return {
        str(value).strip().upper()
        for value in candidates
        if value is not None and str(value).strip()
    }


def _optional_bool(*values: Any) -> bool | None:
    for value in values:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"true", "1", "yes", "y"}:
                return True
            if normalized in {"false", "0", "no", "n"}:
                return False
    return None


def _has_remaining_work(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, (list, tuple, set, dict)):
        return bool(value)
    if isinstance(value, str):
        normalized = value.strip().lower()
        return normalized not in {"", "none", "null", "false", "no", "[]"}
    return bool(value)


def _first_non_empty_string(*values: Any) -> str:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _list_or_none(value: Any) -> list[Any] | None:
    return value if isinstance(value, list) else None


def _runtime_context_for_child_skill(
    runtime_context: dict[str, Any] | None,
    root_context_summary: dict[str, Any] | None,
    child_manifest: SkillManifest,
) -> dict[str, Any] | None:
    child_context = dict(runtime_context or {})
    visibility = _context_visibility_for_child_manifest(child_manifest)
    child_context["_context_visibility"] = visibility
    child_context.pop("_visible_root_context_summary", None)
    if visibility == "summary" and root_context_summary:
        child_context["_visible_root_context_summary"] = root_context_summary
    return child_context


def _resume_parent_if_waiting(runtime: SkillRuntime, parent_frame_id: str) -> None:
    parent = runtime.get_frame(parent_frame_id)
    if parent and parent.status == FrameStatus.WAITING_CHILD:
        runtime.resume_from_child(parent_frame_id)


def _record_parent_child_recoverable_interruption(
    runtime: SkillRuntime,
    *,
    parent_frame_id: str,
    child_frame_id: str | None,
    reason: str,
    error: str,
    task_id: str,
) -> None:
    if child_frame_id:
        runtime.record_recoverable_child_interruption(
            parent_frame_id,
            reason=reason,
            error=error,
            task_id=task_id,
            child_frame_id=child_frame_id,
            allow_terminal_child=True,
        )
    _resume_parent_if_waiting(runtime, parent_frame_id)
    runtime.record_recoverable_interruption(
        parent_frame_id,
        reason=reason,
        error=error,
        task_id=task_id,
    )


def _awaiting_user_input_from_child(child: Any) -> dict[str, Any]:
    state = getattr(child, "private_working_state", {}) or {}
    payload = state.get("awaiting_user_input")
    if isinstance(payload, dict):
        return payload
    return {
        "turn_status": "WAITING_FOR_USER_INPUT",
        "user_message": child.result_summary or "",
        "summary": child.result_summary or "",
        "structured_output": child.output or {},
        "artifact_refs": list(child.artifact_refs),
        "evidence_refs": list(child.evidence_refs),
    }


def _context_visibility_for_child_manifest(manifest: SkillManifest) -> str:
    visibility = _normalize_context_visibility(manifest.context_visibility)
    if visibility != "passthrough":
        return visibility
    if manifest.visibility == "builtin" or manifest.id.startswith("system."):
        return "passthrough"
    return "isolated"


def _normalize_context_visibility(value: str | None) -> str:
    normalized = (value or "isolated").strip().lower()
    if normalized in {"isolated", "summary", "passthrough"}:
        return normalized
    return "isolated"
