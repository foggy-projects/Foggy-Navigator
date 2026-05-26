"""Skill Runtime — single-point entry for all Frame state changes.

All Frame lifecycle operations (create, transition, complete, close) MUST go
through this class.  Neither the model nor individual graph nodes are allowed
to write final status directly.
"""

from __future__ import annotations

import logging
import uuid
import hashlib
import json
import re
from datetime import datetime, timezone
from typing import Any

from ..models import (
    FrameKind,
    FrameStatus,
    SkillFrameState,
    SkillManifest,
    TERMINAL_STATES,
    VALID_TRANSITIONS,
    ValidationResult,
)
from .file_frame_journal import FileFrameJournal
from .frame_execution_report import FrameExecutionReport, FrameExecutionReportGenerator
from .frame_store import FrameStore
from .output_contract import validate_output_contract
from .skill_registry import SkillRegistry

logger = logging.getLogger(__name__)


class IllegalStateTransition(Exception):
    """Raised when an illegal Frame state transition is attempted."""


class FrameNotFound(Exception):
    """Raised when a Frame cannot be found."""


class SubmitResultRejected(Exception):
    """Raised when submit_result validation fails."""

    def __init__(self, errors: list[str]) -> None:
        self.errors = errors
        super().__init__(f"Submit rejected: {errors}")


class MaxNestingDepthExceeded(Exception):
    """Raised when child Skill invocation exceeds the maximum nesting depth."""

    def __init__(self, depth: int, max_depth: int) -> None:
        self.depth = depth
        self.max_depth = max_depth
        super().__init__(
            f"Nesting depth {depth} exceeds maximum {max_depth}"
        )


# Default maximum nesting depth for Skill calls.
DEFAULT_MAX_NESTING_DEPTH = 5
PERSISTENT_FRAME_MAX_TURN_RESULTS = 20
PERSISTENT_FRAME_MAX_RECENT_SUMMARIES = 10
PERSISTENT_FRAME_MAX_PRIVATE_MESSAGES = 40
PERSISTENT_FRAME_MAX_INTERRUPTION_HISTORY = 10
PERSISTENT_FRAME_MAX_PLAN_HISTORY = 10
PERSISTENT_FRAME_MAX_CHILD_RESULT_SUMMARIES = 10
PERSISTENT_FRAME_MAX_CONTINUATION_STRING_CHARS = 500
CONTINUATION_REDACTED = "<redacted>"
CONTINUATION_REDACTED_URL = "<redacted-url>"
CONTINUATION_SENSITIVE_KEY_FRAGMENTS = (
    "api_key",
    "apikey",
    "authorization",
    "cookie",
    "credential",
    "password",
    "private_message",
    "provider_response",
    "raw_prompt",
    "secret",
    "signed",
    "signature",
    "stack_trace",
    "system_prompt",
    "token",
    "traceback",
)
CONTINUATION_URL_KEY_FRAGMENTS = ("download", "href", "link", "uri", "url")
HTTP_URL_PATTERN = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)
OPENAI_KEY_PATTERN = re.compile(r"\bsk-[A-Za-z0-9_-]{6,}\b")
RECOVERABLE_FOCUS_KEYS = (
    "recoverable_focus_frame_id",
    "recoverable_focus_kind",
    "recoverable_focus_status",
    "recoverable_focus_interrupted_at",
    "recoverable_focus_summary",
    "recoverable_focus_stack",
)
ACTIVE_FOCUS_KEYS = (
    "active_focus_frame_id",
    "active_focus_kind",
    "active_focus_status",
    "active_focus_updated_at",
    "active_focus_summary",
    "active_focus_stack",
)
AWAITING_USER_KEYS = (
    "pending_awaiting_user_child_frame_id",
    "pending_awaiting_user_child",
)
INTENT_RESOLUTIONS = frozenset({
    "CONTINUE_PREVIOUS",
    "ABANDON_PREVIOUS",
    "START_UNRELATED_NEW_TASK",
    "ASK_CLARIFICATION",
})


class SkillRuntime:
    """Core runtime managing Frame lifecycle for all Skills.

    Every state mutation on a ``SkillFrameState`` must go through one of the
    public methods below.
    """

    def __init__(
        self,
        frame_store: FrameStore | None = None,
        skill_registry: SkillRegistry | None = None,
        journal: FileFrameJournal | None = None,
        report_generator: FrameExecutionReportGenerator | None = None,
        max_nesting_depth: int = DEFAULT_MAX_NESTING_DEPTH,
    ) -> None:
        self.store = frame_store or FrameStore()
        self.registry = skill_registry or SkillRegistry()
        self._journal = journal
        self._report_generator = report_generator or _report_generator_from_journal(journal)
        self._max_nesting_depth = max_nesting_depth

    # -- Frame creation ------------------------------------------------------

    def invoke_skill(
        self,
        task_id: str,
        skill_id: str | None = None,
        skill_input: dict[str, Any] | None = None,
        parent_frame_id: str | None = None,
        conversation_id: str | None = None,
        session_id: str | None = None,
        current_task_id: str | None = None,
        origin_task_id: str | None = None,
        last_task_ids: list[str] | None = None,
        frame_kind: FrameKind = FrameKind.SKILL,
        agent_id: str | None = None,
        frame_name: str | None = None,
    ) -> str:
        """Create a new Frame and transition to RUNNING.

        Returns the ``frame_id``.
        """
        frame_id = f"frm_{uuid.uuid4().hex[:12]}"
        now = datetime.now(timezone.utc).isoformat()

        frame = SkillFrameState(
            frame_id=frame_id,
            task_id=task_id,
            skill_id=skill_id or "",
            agent_id=agent_id,
            frame_name=frame_name,
            frame_kind=frame_kind,
            parent_frame_id=parent_frame_id,
            status=FrameStatus.CREATED,
            conversation_id=conversation_id,
            session_id=session_id,
            current_task_id=current_task_id or task_id,
            origin_task_id=origin_task_id or task_id,
            last_task_ids=list(last_task_ids or [task_id]),
            input=skill_input or {},
            started_at=now,
        )

        manifest = self.registry.get_manifest(skill_id) if skill_id else None
        if manifest:
            # Freeze the manifest for this frame so later account/public registry
            # reloads cannot change validation or promotion semantics mid-run.
            frame.private_working_state["_skill_manifest"] = manifest.model_dump()

        # CREATED → RUNNING
        self._transition(frame, FrameStatus.RUNNING)
        self._save(frame)

        # If there is a parent, register this child
        if parent_frame_id:
            parent = self.store.get(parent_frame_id)
            if parent:
                parent.child_frame_ids.append(frame_id)
                self._save(parent)

        logger.info(
            "Invoked frame kind=%s skill=%s agent=%s frame=%s task=%s parent=%s",
            frame_kind.value, skill_id or "", agent_id or frame_name or "", frame_id, task_id, parent_frame_id,
        )
        return frame_id

    # -- State transitions ---------------------------------------------------

    def mark_waiting_child(self, frame_id: str) -> None:
        """RUNNING → WAITING_CHILD."""
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.WAITING_CHILD)
        self._save(frame)

    def resume_from_child(self, frame_id: str) -> None:
        """WAITING_CHILD → RUNNING."""
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.RUNNING)
        self._save(frame)

    def resume_from_user_input(self, frame_id: str, task_id: str | None = None) -> SkillFrameState:
        """AWAITING_USER → RUNNING after the next user message arrives."""
        frame = self._get_frame(frame_id)
        if frame.status != FrameStatus.AWAITING_USER:
            raise IllegalStateTransition(
                f"resume_from_user_input requires AWAITING_USER, got {frame.status.value}"
            )
        self._transition(frame, FrameStatus.RUNNING)
        now = datetime.now(timezone.utc).isoformat()
        frame.private_working_state["awaiting_user_resumed_at"] = now
        if task_id:
            frame.private_working_state["awaiting_user_resumed_task_id"] = task_id
            frame.task_id = task_id
            frame.current_task_id = task_id
            if not frame.last_task_ids:
                frame.last_task_ids.append(frame.origin_task_id or task_id)
            if frame.last_task_ids[-1] != task_id:
                frame.last_task_ids.append(task_id)
        self._save(frame)
        self._generate_frame_report(frame)
        return frame

    def mark_awaiting_approval(
        self,
        frame_id: str,
        approval_request: dict[str, Any],
    ) -> None:
        """RUNNING → AWAITING_APPROVAL.

        The ``approval_request`` payload is persisted on the Frame and
        emitted as an SSE event so Java side can capture and store the
        audit record (Doc 31 §16.4).
        """
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.AWAITING_APPROVAL)
        frame.approval_request = approval_request
        self._save(frame)
        self._generate_frame_report(frame)
        logger.info("Frame %s awaiting approval: %s", frame_id, approval_request.get("approval_type", ""))

    def mark_child_awaiting_approval(
        self,
        parent_frame_id: str,
        child_frame_id: str,
        approval_request: dict[str, Any],
    ) -> None:
        """Bubble a child approval wait to its parent frame.

        A parent that invoked a child skill is normally in ``WAITING_CHILD``.
        If the child suspends for approval, the parent must also become a
        durable approval wait boundary so the root graph does not treat the
        waiting child as a failed turn.
        """
        parent = self._get_frame(parent_frame_id)
        child = self._get_frame(child_frame_id)
        if child.parent_frame_id != parent_frame_id:
            raise IllegalStateTransition("child frame does not belong to parent")
        if child.status != FrameStatus.AWAITING_APPROVAL:
            raise IllegalStateTransition(
                f"mark_child_awaiting_approval requires child AWAITING_APPROVAL, got {child.status.value}"
            )

        self._transition(parent, FrameStatus.AWAITING_APPROVAL)
        parent.approval_request = approval_request
        parent.private_working_state["pending_child_approval_frame_id"] = child_frame_id
        self._save(parent)
        self._generate_frame_report(parent)
        logger.info(
            "Frame %s awaiting approval from child frame %s",
            parent_frame_id,
            child_frame_id,
        )

    def mark_child_awaiting_user(
        self,
        parent_frame_id: str,
        child_frame_id: str,
        awaiting_user_input: dict[str, Any] | None = None,
    ) -> None:
        """Bubble a child user-input wait to its parent frame.

        The child frame stays open in ``AWAITING_USER``.  The parent remains
        ``WAITING_CHILD`` so the next user turn deterministically resumes the
        same child frame before the root LLM loop.
        """
        parent = self._get_frame(parent_frame_id)
        child = self._get_frame(child_frame_id)
        if child.parent_frame_id != parent_frame_id:
            raise IllegalStateTransition("child frame does not belong to parent")
        if child.status != FrameStatus.AWAITING_USER:
            raise IllegalStateTransition(
                f"mark_child_awaiting_user requires child AWAITING_USER, got {child.status.value}"
            )
        if parent.status == FrameStatus.RUNNING:
            self._transition(parent, FrameStatus.WAITING_CHILD)
        elif parent.status != FrameStatus.WAITING_CHILD:
            raise IllegalStateTransition(
                f"mark_child_awaiting_user requires parent RUNNING/WAITING_CHILD, got {parent.status.value}"
            )

        now = datetime.now(timezone.utc).isoformat()
        wait_payload = _awaiting_user_input_payload(
            summary=child.result_summary or "",
            structured_output=child.output or {},
            artifact_refs=child.artifact_refs,
            evidence_refs=child.evidence_refs,
            submitted_at=now,
        )
        if awaiting_user_input:
            wait_payload.update(_safe_json_copy(awaiting_user_input))
        focus_stack = self._active_descendant_stack(parent)
        if len(focus_stack) < 2 or focus_stack[1].frame_id != child.frame_id:
            focus_stack = [parent, child]
        focus = focus_stack[-1]
        parent.private_working_state["pending_awaiting_user_child_frame_id"] = child.frame_id
        parent.private_working_state["pending_awaiting_user_child"] = {
            "frame_id": child.frame_id,
            "skill_id": child.skill_id,
            "agent_id": child.agent_id,
            "frame_name": child.frame_name,
            "frame_kind": child.frame_kind.value,
            "status": child.status.value,
            "input": _safe_json_copy(child.input),
            "awaiting_user_input": _safe_json_copy(wait_payload),
            "updated_at": now,
        }
        parent.result_summary = wait_payload.get("user_message") or child.result_summary
        parent.output = _safe_json_copy(child.output or {})
        self._set_active_focus(
            owner=parent,
            focus=focus,
            kind=_recoverable_focus_kind(parent, focus, focus_stack),
            status="AWAITING_USER",
            reason="waiting_for_user_input",
            error="",
            last_task_id=parent.current_task_id or parent.task_id,
            updated_at=now,
            stack=focus_stack,
            extra_summary={"awaiting_user_input": _safe_json_copy(wait_payload)},
        )
        self._save(parent)
        self._generate_frame_report(parent)
        logger.info(
            "Frame %s awaiting user input from child frame %s",
            parent_frame_id,
            child_frame_id,
        )

    def mark_focus_awaiting_user(
        self,
        owner_frame_id: str,
        focus_frame_id: str,
        awaiting_user_input: dict[str, Any] | None = None,
    ) -> None:
        """Bubble a nested awaiting-user focus to a non-immediate owner frame."""
        owner = self._get_frame(owner_frame_id)
        focus = self._get_frame(focus_frame_id)
        if focus.status != FrameStatus.AWAITING_USER:
            raise IllegalStateTransition(
                f"mark_focus_awaiting_user requires focus AWAITING_USER, got {focus.status.value}"
            )
        if owner.status == FrameStatus.RUNNING:
            self._transition(owner, FrameStatus.WAITING_CHILD)
        elif owner.status != FrameStatus.WAITING_CHILD:
            raise IllegalStateTransition(
                f"mark_focus_awaiting_user requires owner RUNNING/WAITING_CHILD, got {owner.status.value}"
            )

        stack = self._focus_stack_for_resume(owner)
        if not stack or stack[-1].frame_id != focus.frame_id:
            ancestors = list(reversed(self._parent_stack(focus)))
            if not ancestors or ancestors[0].frame_id != owner.frame_id:
                raise IllegalStateTransition("focus frame does not belong to owner focus stack")
            stack = ancestors + [focus]
        if len(stack) < 2:
            raise IllegalStateTransition("focus frame does not belong to owner focus stack")

        now = datetime.now(timezone.utc).isoformat()
        wait_payload = _awaiting_user_input_payload(
            summary=focus.result_summary or "",
            structured_output=focus.output or {},
            artifact_refs=focus.artifact_refs,
            evidence_refs=focus.evidence_refs,
            submitted_at=now,
        )
        if awaiting_user_input:
            wait_payload.update(_safe_json_copy(awaiting_user_input))
        owner.private_working_state["pending_awaiting_user_focus_frame_id"] = focus.frame_id
        owner.private_working_state["pending_awaiting_user_focus"] = {
            "frame_id": focus.frame_id,
            "skill_id": focus.skill_id,
            "agent_id": focus.agent_id,
            "frame_name": focus.frame_name,
            "frame_kind": focus.frame_kind.value,
            "status": focus.status.value,
            "input": _safe_json_copy(focus.input),
            "awaiting_user_input": _safe_json_copy(wait_payload),
            "updated_at": now,
        }
        owner.result_summary = wait_payload.get("user_message") or focus.result_summary
        owner.output = _safe_json_copy(focus.output or {})
        self._set_active_focus(
            owner=owner,
            focus=focus,
            kind=_recoverable_focus_kind(owner, focus, stack),
            status="AWAITING_USER",
            reason="waiting_for_user_input",
            error="",
            last_task_id=owner.current_task_id or owner.task_id,
            updated_at=now,
            stack=stack,
            extra_summary={"awaiting_user_input": _safe_json_copy(wait_payload)},
        )
        self._mark_focus_ancestors_waiting(stack)
        self._save(owner)
        self._generate_frame_report(owner)
        logger.info(
            "Frame %s awaiting user input from nested focus frame %s",
            owner_frame_id,
            focus_frame_id,
        )

    def resume_from_approval(
        self,
        frame_id: str,
        approval_result: str,
        comment: str = "",
    ) -> None:
        """AWAITING_APPROVAL → RUNNING after external approval.

        Called when Java side sends ``POST /api/v1/resume``.
        """
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.RUNNING)
        frame.private_working_state["approval_result"] = approval_result
        frame.private_working_state["approval_comment"] = comment
        frame.approval_request = None  # clear the pending request
        self._save(frame)
        self._resume_pending_child_approval(frame, approval_result, comment)
        self._resume_pending_function_call(frame, approval_result, comment)
        logger.info("Frame %s resumed from approval: %s", frame_id, approval_result)

    def fail_frame(self, frame_id: str, reason: str = "") -> None:
        """Transition to FAILED from any non-terminal state."""
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.FAILED)
        frame.ended_at = datetime.now(timezone.utc).isoformat()
        frame.private_working_state["fail_reason"] = reason
        self._save(frame)
        self._generate_frame_report(frame)
        logger.warning("Frame %s failed: %s", frame_id, reason)

    def reopen_recoverable_frame(
        self,
        frame_id: str,
        task_id: str | None = None,
    ) -> SkillFrameState:
        """Reopen an interrupted terminal frame so a user "continue" can retry it."""
        frame = self._get_frame(frame_id)
        if frame.status not in {FrameStatus.FAILED, FrameStatus.CANCELLED}:
            return frame
        if (
            frame.private_working_state.get("continuation_state") != "INTERRUPTED"
            or not frame.private_working_state.get("recoverable")
        ):
            raise IllegalStateTransition(
                f"Frame {frame_id} is terminal and not marked recoverable"
            )

        now = datetime.now(timezone.utc).isoformat()
        previous_status = frame.status.value
        previous_fail_reason = frame.private_working_state.get("fail_reason")
        history = frame.private_working_state.setdefault("terminal_resume_history", [])
        if not isinstance(history, list):
            history = []
            frame.private_working_state["terminal_resume_history"] = history
        history.append({
            "status": previous_status,
            "fail_reason": previous_fail_reason or "",
            "resumed_at": now,
            "task_id": task_id or frame.current_task_id or frame.task_id,
        })
        del history[:-PERSISTENT_FRAME_MAX_INTERRUPTION_HISTORY]

        frame.status = FrameStatus.RUNNING
        frame.ended_at = ""
        if task_id:
            frame.task_id = task_id
            frame.current_task_id = task_id
            if not frame.last_task_ids:
                frame.last_task_ids.append(frame.origin_task_id or task_id)
            if frame.last_task_ids[-1] != task_id:
                frame.last_task_ids.append(task_id)
        frame.private_working_state["resumed_from_terminal_status"] = previous_status
        frame.private_working_state["resumed_at"] = now
        frame.private_working_state.pop("fail_reason", None)
        self._save(frame)
        self._generate_frame_report(frame)
        logger.info("Reopened recoverable frame %s from %s", frame_id, previous_status)
        return frame

    def cancel_frame(self, frame_id: str) -> None:
        """Transition to CANCELLED from any non-terminal state."""
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.CANCELLED)
        frame.ended_at = datetime.now(timezone.utc).isoformat()
        self._save(frame)
        self._generate_frame_report(frame)
        logger.info("Frame %s cancelled", frame_id)

    # -- Completion protocol -------------------------------------------------

    def submit_result(
        self,
        frame_id: str,
        summary: str,
        structured_output: dict[str, Any],
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """Attempt to complete a Frame with the given candidate output.

        If validation passes, the Frame transitions to COMPLETED.
        If validation fails, the Frame remains RUNNING and errors are returned.
        After ``max_submit_attempts`` failures, the Frame transitions to FAILED.
        """
        frame = self._get_frame(frame_id)

        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"submit_result requires RUNNING, got {frame.status.value}"
            )

        # Bind candidate data onto the frame for validation
        frame.result_summary = summary
        frame.output = structured_output
        if artifact_refs:
            frame.artifact_refs = artifact_refs
        if evidence_refs:
            frame.evidence_refs = evidence_refs

        frame.submit_attempts += 1

        # Validate output contract
        manifest = self._manifest_for_frame(frame)
        if manifest:
            result = validate_output_contract(frame, manifest, structured_output)
        else:
            # No manifest → skip validation (for testing or unregistered skills)
            result = ValidationResult(ok=True)

        if result.ok:
            self._transition(frame, FrameStatus.COMPLETED)
            frame.ended_at = datetime.now(timezone.utc).isoformat()
            self._save(frame)
            self._generate_frame_report(frame)
            logger.info("Frame %s completed", frame_id)
            return result
        else:
            # Check retry limit
            if frame.submit_attempts >= frame.max_submit_attempts:
                self._transition(frame, FrameStatus.FAILED)
                frame.ended_at = datetime.now(timezone.utc).isoformat()
                frame.private_working_state["fail_reason"] = (
                    f"Max submit attempts ({frame.max_submit_attempts}) exceeded"
                )
                self._save(frame)
                self._generate_frame_report(frame)
                logger.warning(
                    "Frame %s failed after %d submit attempts",
                    frame_id, frame.submit_attempts,
                )
            else:
                # Remain RUNNING, clear candidate output
                frame.output = None
                frame.result_summary = None
                self._save(frame)

            return result

    def submit_user_input_request(
        self,
        frame_id: str,
        summary: str,
        structured_output: dict[str, Any],
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """Pause a non-persistent skill frame until the next user message.

        This is the child-frame variant of ``submit_frame_result`` for
        ``turn_status=WAITING_FOR_USER_INPUT``.  It records the user-facing
        prompt but deliberately does not close the child frame.
        """
        frame = self._get_frame(frame_id)
        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"submit_user_input_request requires RUNNING, got {frame.status.value}"
            )

        now = datetime.now(timezone.utc).isoformat()
        frame.result_summary = summary
        frame.output = structured_output
        if artifact_refs:
            frame.artifact_refs = artifact_refs
        if evidence_refs:
            frame.evidence_refs = evidence_refs
        frame.submit_attempts += 1

        wait_payload = _awaiting_user_input_payload(
            summary=summary,
            structured_output=structured_output,
            artifact_refs=artifact_refs,
            evidence_refs=evidence_refs,
            submitted_at=now,
        )
        frame.private_working_state["turn_status"] = "WAITING_FOR_USER_INPUT"
        frame.private_working_state["awaiting_user_input"] = wait_payload
        frame.private_working_state["awaiting_user_input_at"] = now
        _append_synthetic_private_assistant_message(frame, wait_payload.get("user_message"))
        self._transition(frame, FrameStatus.AWAITING_USER)
        self._save(frame)
        self._generate_frame_report(frame)
        logger.info("Frame %s awaiting user input", frame_id)
        return ValidationResult(ok=True)

    def handoff_to_parent(
        self,
        frame_id: str,
        summary: str,
        reason: str,
        intent_resolution: str,
        parent_instruction: str | None = None,
        requires_parent_synthesis: bool | None = None,
        structured_output: dict[str, Any] | None = None,
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """Complete a child frame as a controlled handoff to its parent.

        This is an escape hatch for ``AWAITING_USER`` / recoverable child
        resumes.  It is a runtime control result, not the skill's ordinary
        business output, so it intentionally bypasses the child manifest's
        output schema while still using the normal COMPLETED -> close/promote
        path.
        """
        frame = self._get_frame(frame_id)
        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"handoff_to_parent requires RUNNING, got {frame.status.value}"
            )
        if not frame.parent_frame_id:
            return ValidationResult(
                ok=False,
                errors=["handoff_to_parent requires a parent frame"],
            )

        summary = str(summary or "").strip()
        if not summary:
            return ValidationResult(ok=False, errors=["summary is required"])

        normalized_reason = str(reason or "OTHER").strip().upper() or "OTHER"
        normalized_intent = (
            str(intent_resolution or "RETURN_TO_PARENT").strip().upper()
            or "RETURN_TO_PARENT"
        )
        if requires_parent_synthesis is None:
            requires_parent_synthesis = normalized_intent in {
                "START_UNRELATED_NEW_TASK",
                "ASK_PARENT_TO_DECIDE",
            }

        payload = dict(structured_output or {})
        payload["status"] = "HANDOFF_TO_PARENT"
        payload["handoff_to_parent"] = True
        payload["handoff_reason"] = normalized_reason
        payload["intent_resolution"] = normalized_intent
        payload["requires_parent_synthesis"] = bool(requires_parent_synthesis)
        payload["message"] = summary
        if parent_instruction:
            payload["parent_instruction"] = str(parent_instruction).strip()

        now = datetime.now(timezone.utc).isoformat()
        frame.result_summary = summary
        frame.output = payload
        if artifact_refs:
            frame.artifact_refs = artifact_refs
        if evidence_refs:
            frame.evidence_refs = evidence_refs
        frame.submit_attempts += 1
        frame.private_working_state["handoff_to_parent"] = {
            "reason": normalized_reason,
            "intent_resolution": normalized_intent,
            "requires_parent_synthesis": bool(requires_parent_synthesis),
            "parent_instruction": parent_instruction or "",
            "submitted_at": now,
        }
        self._transition(frame, FrameStatus.COMPLETED)
        frame.ended_at = now
        self._save(frame)
        self._generate_frame_report(frame)
        logger.info("Frame %s handed off to parent", frame_id)
        return ValidationResult(ok=True)

    def submit_persistent_turn_result(
        self,
        frame_id: str,
        summary: str,
        structured_output: dict[str, Any],
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """Record a turn result for a persistent Frame without closing it.

        Conversation root frames do not have ordinary Skill exit semantics.
        ``submit_frame_result`` ends the current user turn, but the Frame
        remains RUNNING so future resume logic can continue from the same root
        working context.
        """
        frame = self._get_frame(frame_id)

        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"submit_persistent_turn_result requires RUNNING, got {frame.status.value}"
            )

        frame.result_summary = summary
        frame.output = structured_output
        if artifact_refs:
            frame.artifact_refs = artifact_refs
        if evidence_refs:
            frame.evidence_refs = evidence_refs

        manifest = self._manifest_for_frame(frame)
        if manifest:
            result = validate_output_contract(frame, manifest, structured_output)
        else:
            result = ValidationResult(ok=True)

        interruption_entry = (
            _interruption_history_entry(frame, summary, structured_output)
            if frame.private_working_state.get("continuation_state") == "INTERRUPTED"
            else None
        )
        intent_resolution = _continuation_resolution(structured_output)
        keep_recoverable_focus = intent_resolution == "ASK_CLARIFICATION"

        if result.ok:
            if not keep_recoverable_focus:
                frame.private_working_state.pop("continuation_state", None)
                frame.private_working_state.pop("interrupt_reason", None)
                frame.private_working_state.pop("last_error", None)
                frame.private_working_state.pop("last_task_id", None)
                frame.private_working_state.pop("recoverable", None)
                frame.private_working_state.pop("interrupted_at", None)
                _clear_recoverable_focus_fields(frame.private_working_state)
            _sync_active_plan_after_persistent_turn(
                frame,
                structured_output,
                summary,
                intent_resolution,
            )
            submitted_at = datetime.now(timezone.utc).isoformat()
            turn_results = frame.private_working_state.setdefault("turn_results", [])
            turn_entry = {
                "summary": summary,
                "structured_output": structured_output,
                "artifact_refs": artifact_refs or [],
                "evidence_refs": evidence_refs or [],
                "status": _persistent_turn_report_status(structured_output),
                "submitted_at": submitted_at,
                "ended_at": submitted_at,
            }
            turn_results.append(turn_entry)
            frame.private_working_state["current_turn_report"] = {
                "task_id": frame.current_task_id or frame.task_id,
                "status": turn_entry["status"],
                "ended_at": submitted_at,
                "summary": summary,
            }
            self._compact_persistent_frame_context(frame, turn_entry)
            if interruption_entry and not keep_recoverable_focus:
                _append_interruption_history(frame, interruption_entry)
        else:
            frame.output = None
            frame.result_summary = None

        self._save(frame)
        if result.ok:
            self._generate_frame_report(frame)
        return result

    def shelve_recoverable_interruption(
        self,
        frame_id: str,
        summary: str,
        abandoned_interruption: dict[str, Any] | str | None,
        decision: str = "START_UNRELATED_NEW_TASK",
        intent_resolution: str | None = None,
        new_task: dict[str, Any] | None = None,
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """End the current persistent turn by shelving an interrupted task.

        This is a deterministic root-frame operation for the case where the
        user explicitly stops the previous work or asks for an unrelated new
        task.  It preserves a compact interruption summary for later "back to
        that task" requests while clearing the active interruption marker.
        """
        frame = self._get_frame(frame_id)
        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"shelve_recoverable_interruption requires RUNNING, got {frame.status.value}"
            )
        if (
            frame.private_working_state.get("continuation_state") != "INTERRUPTED"
            or not frame.private_working_state.get("recoverable")
        ):
            return ValidationResult(ok=False, errors=["No recoverable interruption to shelve"])

        normalized_decision = _normalize_shelve_decision(decision)
        normalized_intent = _normalize_intent_resolution(intent_resolution or normalized_decision)
        if normalized_intent not in {"ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"}:
            normalized_intent = normalized_decision
        structured_output: dict[str, Any] = {
            "continuation_decision": normalized_decision,
            "intent_resolution": normalized_intent,
            "abandoned_interruption": _normalize_abandoned_interruption(abandoned_interruption),
        }
        if new_task:
            structured_output["new_task"] = _safe_json_copy(new_task)
        focus_frame_ids = _recoverable_focus_frame_ids(frame.private_working_state, frame.frame_id)

        validation = self.submit_persistent_turn_result(
            frame_id=frame_id,
            summary=summary,
            structured_output=structured_output,
            artifact_refs=artifact_refs,
            evidence_refs=evidence_refs,
        )
        if validation.ok:
            self.clear_recoverable_child_focus(
                frame_id,
                normalized_decision,
                focus_frame_ids=focus_frame_ids,
            )
        return validation

    # -- Frame closure -------------------------------------------------------

    def close_frame(self, frame_id: str) -> dict[str, Any]:
        """Close a completed Frame and return the promoted result.

        Only COMPLETED frames can be closed.  The promoted result contains
        only the fields allowed by ``promote_to_parent`` in the manifest.
        Private messages and working state are destroyed.
        """
        frame = self._get_frame(frame_id)

        if frame.status != FrameStatus.COMPLETED:
            raise IllegalStateTransition(
                f"close_frame requires COMPLETED, got {frame.status.value}"
            )

        report = self._generate_frame_report(frame)

        # Build promoted result
        promoted: dict[str, Any] = {
            "frame_id": frame.frame_id,
            "frame_kind": frame.frame_kind.value,
        }
        if frame.skill_id:
            promoted["skill_id"] = frame.skill_id
        if frame.agent_id:
            promoted["agent_id"] = frame.agent_id
        if frame.frame_name:
            promoted["frame_name"] = frame.frame_name

        manifest = self._manifest_for_frame(frame)
        promote_fields = manifest.promote_to_parent if manifest else [
            "result_summary", "structured_output", "artifact_refs", "evidence_refs",
        ]

        field_map = {
            "result_summary": frame.result_summary,
            "structured_output": frame.output,
            "artifact_refs": frame.artifact_refs,
            "evidence_refs": frame.evidence_refs,
            "approval_request": frame.approval_request,
        }
        for field_key in promote_fields:
            if field_key in field_map:
                promoted[field_key] = field_map[field_key]

        report_ref = _execution_report_ref_from_frame(frame)
        if report_ref:
            promoted["execution_report_ref"] = report_ref
        report_digest = _execution_report_digest_from_frame(frame)
        if report_digest:
            promoted["execution_report_digest"] = report_digest
        elif report:
            promoted["execution_report_digest"] = _compact_execution_report_digest(report.digest)

        # Destroy private context (Doc 31 §13.3)
        frame.private_messages.clear()
        frame.private_working_state.clear()
        frame.tool_calls.clear()
        self._save(frame)

        logger.info("Frame %s closed, promoted keys: %s", frame_id, list(promoted.keys()))
        return promoted

    # -- Write child result to parent ----------------------------------------

    def write_child_result_to_parent(
        self,
        parent_frame_id: str,
        child_frame_id: str,
        child_promoted: dict[str, Any],
    ) -> None:
        """Write a child's promoted result into the parent's private working state."""
        parent = self._get_frame(parent_frame_id)
        child_results = parent.private_working_state.setdefault("child_results", {})
        child_results[child_frame_id] = child_promoted
        _record_child_continuation_summary_on_parent(parent, child_promoted)
        self._link_execution_report_to_parent_context(parent, child_promoted)
        self._save(parent)
        self._generate_frame_report(parent)

    # -- Business function call frames --------------------------------------

    def invoke_function_call(
        self,
        parent_frame_id: str,
        function_id: str,
        version: str | None,
        arguments: dict[str, Any] | None = None,
        idempotency_key: str | None = None,
        tool_call_id: str | None = None,
    ) -> str:
        """Create a runtime wrapper frame for one business function call.

        Function-call frames are not LLM skills and do not put the caller in
        ``WAITING_CHILD``. They provide a durable audit/suspend boundary for a
        tool invocation that happens inside the caller skill loop.
        """
        parent = self._get_frame(parent_frame_id)
        frame_id = f"fn_{uuid.uuid4().hex[:12]}"
        now = datetime.now(timezone.utc).isoformat()
        safe_args = arguments or {}
        argument_hash = hashlib.sha256(
            json.dumps(safe_args, ensure_ascii=False, sort_keys=True, default=str).encode("utf-8")
        ).hexdigest()

        frame = SkillFrameState(
            frame_id=frame_id,
            task_id=parent.task_id,
            skill_id=f"__function__:{function_id}",
            frame_kind=FrameKind.FUNCTION_CALL,
            parent_frame_id=parent_frame_id,
            status=FrameStatus.CREATED,
            conversation_id=parent.conversation_id,
            session_id=parent.session_id,
            current_task_id=parent.current_task_id or parent.task_id,
            origin_task_id=parent.origin_task_id or parent.task_id,
            last_task_ids=list(parent.last_task_ids or [parent.task_id]),
            input={
                "function_id": function_id,
                "version": version,
                "arguments": safe_args,
                "argument_hash": argument_hash,
                "idempotency_key": idempotency_key,
                "tool_call_id": tool_call_id,
            },
            private_working_state={
                "caller_skill_id": parent.skill_id,
                "approval_state": "NONE",
                "context_visibility": "passthrough",
                "context_snapshot": self._context_snapshot_for_function_call(parent_frame_id),
            },
            started_at=now,
        )

        self._transition(frame, FrameStatus.RUNNING)
        self._save(frame)

        parent.child_frame_ids.append(frame_id)
        self._save(parent)
        return frame_id

    def complete_function_call(
        self,
        function_frame_id: str,
        result: dict[str, Any],
        summary: str = "",
    ) -> None:
        """Complete a function-call frame and write its result to the caller."""
        frame = self._get_frame(function_frame_id)
        if frame.frame_kind != FrameKind.FUNCTION_CALL:
            raise IllegalStateTransition("complete_function_call requires FUNCTION_CALL frame")
        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"complete_function_call requires RUNNING, got {frame.status.value}"
            )

        frame.result_summary = summary or _function_result_summary(frame, result)
        frame.output = result
        self._transition(frame, FrameStatus.COMPLETED)
        frame.ended_at = datetime.now(timezone.utc).isoformat()
        self._save(frame)
        self._generate_frame_report(frame)

        if frame.parent_frame_id:
            parent = self._get_frame(frame.parent_frame_id)
            function_results = parent.private_working_state.setdefault("function_results", {})
            result_entry = {
                "function_id": frame.input.get("function_id"),
                "version": frame.input.get("version"),
                "result": result,
                "completed_at": frame.ended_at,
            }
            report_ref = _execution_report_ref_from_frame(frame)
            if report_ref:
                result_entry["execution_report_ref"] = report_ref
            report_digest = _execution_report_digest_from_frame(frame)
            if report_digest:
                result_entry["execution_report_digest"] = report_digest
            function_results[function_frame_id] = result_entry
            self._link_execution_report_to_parent_context(parent, result_entry)
            self._save(parent)
            self._generate_frame_report(parent)

    def finalize_business_function_result(
        self,
        task_id: str,
        suspend_id: str,
        *,
        success: bool,
        result: dict[str, Any] | None = None,
        summary: str = "",
        error_message: str = "",
    ) -> dict[str, Any]:
        """Finalize a Java-owned business function result into frame reports.

        Approval-required business functions resume the Python frame first, but
        Java owns the actual adapter execution.  When Java publishes the final
        adapter result, this method updates the durable FUNCTION_CALL frame and
        then closes the active skill stack for the task so report digests match
        the visible task terminal state.
        """
        self._restore_task_frames_from_journal(task_id)
        function_frame = self._find_function_frame_by_suspend_id(task_id, suspend_id)
        if function_frame is None:
            return {
                "ok": False,
                "retryable": False,
                "error": f"No function frame found for suspend_id={suspend_id}",
            }
        if function_frame.status == FrameStatus.AWAITING_APPROVAL:
            return {
                "ok": False,
                "retryable": True,
                "error": "Function frame is still awaiting approval resume",
                "function_frame_id": function_frame.frame_id,
            }

        final_status = FrameStatus.COMPLETED if success else FrameStatus.FAILED
        now = datetime.now(timezone.utc).isoformat()
        result_payload = _safe_json_copy(result or {})
        result_payload.setdefault("suspend_id", suspend_id)
        result_payload.setdefault("function_id", function_frame.input.get("function_id"))
        result_payload.setdefault("version", function_frame.input.get("version"))
        if error_message:
            result_payload.setdefault("error_message", error_message)

        final_summary = (
            summary
            or result_payload.get("content")
            or result_payload.get("message")
            or ("Business function execution completed." if success else "Business function execution failed.")
        )
        function_frame.result_summary = str(final_summary)
        function_frame.output = result_payload
        function_frame.ended_at = now
        function_frame.approval_request = None
        function_frame.private_working_state["approval_state"] = "COMPLETED" if success else "FAILED"
        if error_message:
            function_frame.private_working_state["fail_reason"] = error_message
        self._force_terminal_status(function_frame, final_status)
        self._save(function_frame)
        self._generate_frame_report(function_frame)

        closed_skill_frames: list[dict[str, Any]] = []
        root_report_payload: dict[str, Any] | None = None
        child_report_payload: dict[str, Any] | None = None
        previous_frame = function_frame
        for frame in self._parent_stack(function_frame):
            self._record_child_result_on_parent(frame, previous_frame)
            self._clear_business_function_wait_state(frame, function_frame.frame_id)
            frame.result_summary = str(final_summary)
            frame.output = result_payload
            frame.ended_at = now
            frame.approval_request = None
            if error_message:
                frame.private_working_state["fail_reason"] = error_message
            self._force_terminal_status(frame, final_status)
            self._save(frame)
            self._generate_frame_report(frame)

            report_payload = self._execution_report_payload_for_frame(frame)
            if frame.parent_frame_id:
                closed_skill_frames.append({
                    "frame_id": frame.frame_id,
                    "parent_frame_id": frame.parent_frame_id,
                    "skill_id": frame.skill_id,
                    "status": frame.status.value,
                    "summary": frame.result_summary,
                    **report_payload,
                })
                child_report_payload = report_payload
            else:
                root_report_payload = report_payload
            previous_frame = frame

        function_report_payload = self._execution_report_payload_for_frame(function_frame)
        response: dict[str, Any] = {
            "ok": True,
            "task_id": task_id,
            "suspend_id": suspend_id,
            "function_frame_id": function_frame.frame_id,
            "status": final_status.value,
            "closed_skill_frames": closed_skill_frames,
            "execution_report_ref": (
                root_report_payload or child_report_payload or function_report_payload
            ).get("execution_report_ref"),
            "execution_report_digest": (
                root_report_payload or child_report_payload or function_report_payload
            ).get("execution_report_digest"),
            "function_execution_report_ref": function_report_payload.get("execution_report_ref"),
            "function_execution_report_digest": function_report_payload.get("execution_report_digest"),
        }
        if root_report_payload:
            response["root_frame_id"] = previous_frame.frame_id
            response["root_execution_report_ref"] = root_report_payload.get("execution_report_ref")
            response["root_execution_report_digest"] = root_report_payload.get("execution_report_digest")
        if child_report_payload:
            response["child_execution_report_ref"] = child_report_payload.get("execution_report_ref")
            response["child_execution_report_digest"] = child_report_payload.get("execution_report_digest")
        return response

    def suspend_function_call(
        self,
        function_frame_id: str,
        approval_request: dict[str, Any],
    ) -> None:
        """Mark a function-call frame as waiting for Java-owned approval."""
        frame = self._get_frame(function_frame_id)
        if frame.frame_kind != FrameKind.FUNCTION_CALL:
            raise IllegalStateTransition("suspend_function_call requires FUNCTION_CALL frame")
        if frame.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"suspend_function_call requires RUNNING, got {frame.status.value}"
            )

        self._transition(frame, FrameStatus.AWAITING_APPROVAL)
        frame.approval_request = approval_request
        frame.private_working_state["approval_state"] = "PENDING"
        frame.private_working_state["suspend_id"] = approval_request.get("suspend_id")
        self._save(frame)
        self._generate_frame_report(frame)

        if frame.parent_frame_id:
            parent = self._get_frame(frame.parent_frame_id)
            parent.private_working_state["pending_function_call_frame_id"] = function_frame_id
            pending_call = {
                "function_frame_id": function_frame_id,
                "function_id": frame.input.get("function_id"),
                "version": frame.input.get("version"),
                "argument_hash": frame.input.get("argument_hash"),
                "suspend_id": approval_request.get("suspend_id"),
            }
            report_ref = _execution_report_ref_from_frame(frame)
            if report_ref:
                pending_call["execution_report_ref"] = report_ref
            report_digest = _execution_report_digest_from_frame(frame)
            if report_digest:
                pending_call["execution_report_digest"] = report_digest
            parent.private_working_state["pending_function_call"] = pending_call
            self._link_execution_report_to_parent_context(parent, pending_call)
            self._save(parent)
            self._generate_frame_report(parent)

    # -- Composite child Skill operations (Doc 31a §4.1) ---------------------

    def invoke_child_skill(
        self,
        parent_frame_id: str,
        child_skill_id: str,
        child_input: dict[str, Any] | None = None,
    ) -> str:
        """Standard child Skill invocation with depth check.

        1. Check nesting depth limit
        2. mark_waiting_child(parent)
        3. invoke_skill(child, parent_frame_id=parent)
        4. Return child_frame_id
        """
        depth = self.get_nesting_depth(parent_frame_id)
        if depth >= self._max_nesting_depth:
            raise MaxNestingDepthExceeded(depth, self._max_nesting_depth)

        parent = self._get_frame(parent_frame_id)
        self.mark_waiting_child(parent_frame_id)

        child_frame_id = self.invoke_skill(
            task_id=parent.task_id,
            skill_id=child_skill_id,
            skill_input=child_input,
            parent_frame_id=parent_frame_id,
            conversation_id=parent.conversation_id,
            session_id=parent.session_id,
            current_task_id=parent.current_task_id or parent.task_id,
            origin_task_id=parent.origin_task_id or parent.task_id,
            last_task_ids=parent.last_task_ids or [parent.task_id],
        )

        logger.info(
            "Child skill invoked: parent=%s child=%s skill=%s depth=%d",
            parent_frame_id, child_frame_id, child_skill_id, depth + 1,
        )
        return child_frame_id

    def invoke_agent(
        self,
        parent_frame_id: str,
        *,
        agent_id: str | None = None,
        frame_name: str | None = None,
        agent_input: dict[str, Any] | None = None,
        legacy_skill_id: str | None = None,
    ) -> str:
        """Standard delegated Agent frame invocation with depth check."""
        depth = self.get_nesting_depth(parent_frame_id)
        if depth >= self._max_nesting_depth:
            raise MaxNestingDepthExceeded(depth, self._max_nesting_depth)

        parent = self._get_frame(parent_frame_id)
        self.mark_waiting_child(parent_frame_id)

        child_frame_id = self.invoke_skill(
            task_id=parent.task_id,
            skill_id=legacy_skill_id,
            skill_input=agent_input,
            parent_frame_id=parent_frame_id,
            conversation_id=parent.conversation_id,
            session_id=parent.session_id,
            current_task_id=parent.current_task_id or parent.task_id,
            origin_task_id=parent.origin_task_id or parent.task_id,
            last_task_ids=parent.last_task_ids or [parent.task_id],
            frame_kind=FrameKind.AGENT,
            agent_id=agent_id,
            frame_name=frame_name,
        )

        logger.info(
            "Child agent invoked: parent=%s child=%s agent=%s depth=%d",
            parent_frame_id, child_frame_id, agent_id or frame_name or legacy_skill_id or "", depth + 1,
        )
        return child_frame_id

    def complete_child_and_resume_parent(
        self,
        child_frame_id: str,
    ) -> dict[str, Any]:
        """Standard child completion: close child, write to parent, resume parent.

        1. close_frame(child) → promoted result
        2. write_child_result_to_parent(parent, child, promoted)
        3. resume_from_child(parent)
        4. Return promoted result
        """
        child = self._get_frame(child_frame_id)
        parent_frame_id = child.parent_frame_id
        if not parent_frame_id:
            raise ValueError(
                f"Frame {child_frame_id} has no parent — "
                "use close_frame() directly for root-level frames"
            )

        promoted = self.close_frame(child_frame_id)
        self.write_child_result_to_parent(parent_frame_id, child_frame_id, promoted)
        self.resume_from_child(parent_frame_id)
        self._clear_recoverable_child_reference(parent_frame_id, child_frame_id)

        return promoted

    # -- Call stack & nesting depth ------------------------------------------

    def get_call_stack(self, frame_id: str) -> list[SkillFrameState]:
        """Walk parent_frame_id chain upward, return stack (current at index 0, root last)."""
        stack: list[SkillFrameState] = []
        current = self.store.get(frame_id)
        visited: set[str] = set()  # guard against cycles
        while current and current.frame_id not in visited:
            stack.append(current)
            visited.add(current.frame_id)
            if current.parent_frame_id:
                current = self.store.get(current.parent_frame_id)
            else:
                break
        return stack

    def get_nesting_depth(self, frame_id: str) -> int:
        """Return nesting depth of a frame (root frame = 0)."""
        return len(self.get_call_stack(frame_id)) - 1

    @property
    def max_nesting_depth(self) -> int:
        return self._max_nesting_depth

    # -- Queries -------------------------------------------------------------

    def get_frame(self, frame_id: str) -> SkillFrameState | None:
        return self.store.get(frame_id)

    def get_frames_by_task(self, task_id: str) -> list[SkillFrameState]:
        return self.store.get_by_task(task_id)

    def get_frames_by_conversation(self, conversation_id: str) -> list[SkillFrameState]:
        return self.store.get_by_conversation(conversation_id)

    def select_latest_recoverable_root(
        self,
        *,
        conversation_id: str | None,
        task_id: str | None = None,
        root_skill_id: str = "system.root",
    ) -> SkillFrameState | None:
        """Select the latest recoverable root frame for a conversation.

        The file journal is the recovery source of truth. In-memory frames are
        included for hot-path continuity, but journal ordering decides ties and
        supersedes older active recoverable roots.
        """
        candidates: list[SkillFrameState] = []
        if conversation_id:
            candidates.extend(self.store.get_by_conversation(conversation_id))
            if self._journal:
                candidates.extend(self._journal.load_root_history_by_conversation(
                    conversation_id,
                    root_skill_id=root_skill_id,
                ))
        if task_id:
            candidates.extend(self.store.get_by_task(task_id))
            if self._journal:
                candidates.extend(self._journal.load_by_task(task_id))

        recoverable_roots = [
            frame for frame in _dedupe_latest_frame_snapshots(candidates)
            if _is_active_recoverable_root(frame, root_skill_id)
        ]
        if not recoverable_roots:
            return None

        recoverable_roots.sort(key=_recoverable_root_sort_key)
        latest = recoverable_roots[-1]
        self.restore_frame(latest)
        latest = self._get_frame(latest.frame_id)
        self._supersede_recoverable_roots(
            recoverable_roots[:-1],
            latest=latest,
            root_skill_id=root_skill_id,
        )
        return latest

    def rebind_frame_to_task(
        self,
        frame_id: str,
        task_id: str,
        session_id: str | None = None,
        conversation_id: str | None = None,
    ) -> SkillFrameState:
        """Bind a persistent frame to the current task without changing identity."""
        frame = self._get_frame(frame_id)
        if not frame.origin_task_id:
            frame.origin_task_id = frame.task_id
        frame.task_id = task_id
        frame.current_task_id = task_id
        if session_id:
            frame.session_id = session_id
        if conversation_id:
            frame.conversation_id = conversation_id
        if not frame.last_task_ids:
            frame.last_task_ids.append(frame.origin_task_id or task_id)
        if frame.last_task_ids[-1] != task_id:
            frame.last_task_ids.append(task_id)
        self._save(frame)
        return frame

    def record_recoverable_interruption(
        self,
        frame_id: str,
        reason: str,
        error: str = "",
        task_id: str | None = None,
    ) -> None:
        """Mark a RUNNING persistent frame as interrupted but reusable."""
        frame = self._get_frame(frame_id)
        now = datetime.now(timezone.utc).isoformat()
        last_task_id = task_id or frame.current_task_id or frame.task_id
        frame.private_working_state["continuation_state"] = "INTERRUPTED"
        frame.private_working_state["interrupt_reason"] = reason
        frame.private_working_state["last_error"] = error
        frame.private_working_state["last_task_id"] = last_task_id
        frame.private_working_state["recoverable"] = True
        frame.private_working_state["interrupted_at"] = now
        if not frame.private_working_state.get("recoverable_focus_frame_id"):
            self._set_recoverable_focus(
                owner=frame,
                focus=frame,
                kind="ROOT",
                status="INTERRUPTED",
                reason=reason,
                error=error,
                last_task_id=last_task_id,
                interrupted_at=now,
                stack=[frame],
            )
        self._save(frame)
        self._generate_frame_report(frame)

    def record_recoverable_child_interruption(
        self,
        parent_frame_id: str,
        reason: str,
        error: str = "",
        task_id: str | None = None,
        child_frame_id: str | None = None,
        allow_terminal_child: bool = False,
    ) -> str | None:
        """Mark the active child of a waiting parent as recoverable.

        The parent root is moved back to RUNNING so the next user turn can be
        interpreted by the root LLM. The interrupted child remains available
        as a recoverable candidate until root either resumes or shelves it.
        """
        parent = self._get_frame(parent_frame_id)
        child = (
            self._load_related_child_frame(parent, child_frame_id)
            if child_frame_id
            else self._find_active_child_frame(parent)
        )
        if (
            child is None
            or (child.status in TERMINAL_STATES and not allow_terminal_child)
        ):
            if parent.status == FrameStatus.WAITING_CHILD:
                self.resume_from_child(parent.frame_id)
            return None
        if child.parent_frame_id != parent.frame_id:
            raise IllegalStateTransition("recoverable child frame does not belong to parent")

        now = datetime.now(timezone.utc).isoformat()
        last_task_id = task_id or parent.current_task_id or parent.task_id
        focus_stack = self._active_descendant_stack(parent)
        if len(focus_stack) < 2 or focus_stack[1].frame_id != child.frame_id:
            focus_stack = [parent, child]
        focus = focus_stack[-1]

        for interrupted_frame in focus_stack[1:]:
            interrupted_frame.private_working_state["continuation_state"] = "INTERRUPTED"
            interrupted_frame.private_working_state["interrupt_reason"] = reason
            interrupted_frame.private_working_state["last_error"] = error
            interrupted_frame.private_working_state["last_task_id"] = last_task_id
            interrupted_frame.private_working_state["recoverable"] = True
            interrupted_frame.private_working_state["interrupted_at"] = now
            self._save(interrupted_frame)
            self._generate_frame_report(interrupted_frame)

        parent.private_working_state["pending_recoverable_child_frame_id"] = child.frame_id
        parent.private_working_state["pending_recoverable_child"] = {
            "frame_id": child.frame_id,
            "skill_id": child.skill_id,
            "agent_id": child.agent_id,
            "frame_name": child.frame_name,
            "frame_kind": child.frame_kind.value,
            "status": child.status.value,
            "input": _safe_json_copy(child.input),
            "reason": reason,
            "last_error": error,
            "last_task_id": last_task_id,
            "interrupted_at": now,
            "recoverable_focus_frame_id": focus.frame_id,
            "recoverable_focus_kind": _recoverable_focus_kind(parent, focus, focus_stack),
        }
        self._set_recoverable_focus(
            owner=parent,
            focus=focus,
            kind=_recoverable_focus_kind(parent, focus, focus_stack),
            status="INTERRUPTED",
            reason=reason,
            error=error,
            last_task_id=last_task_id,
            interrupted_at=now,
            stack=focus_stack,
        )
        self._save(parent)
        self._generate_frame_report(parent)
        if parent.status == FrameStatus.WAITING_CHILD:
            self.resume_from_child(parent.frame_id)
        return child.frame_id

    def prepare_recoverable_child_resume(self, parent_frame_id: str) -> SkillFrameState | None:
        """Return the pending recoverable child and put parent back in WAITING_CHILD."""
        parent = self._get_frame(parent_frame_id)
        if parent.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"prepare_recoverable_child_resume requires parent RUNNING, got {parent.status.value}"
            )
        child_frame_id = parent.private_working_state.get("pending_recoverable_child_frame_id")
        if not isinstance(child_frame_id, str) or not child_frame_id:
            return None
        child = self._load_related_child_frame(parent, child_frame_id)
        if child is None:
            self._clear_recoverable_child_reference(parent.frame_id, child_frame_id)
            return None
        if child.parent_frame_id != parent.frame_id:
            raise IllegalStateTransition("recoverable child frame does not belong to parent")
        if child.status in TERMINAL_STATES:
            if (
                child.private_working_state.get("continuation_state") != "INTERRUPTED"
                or not child.private_working_state.get("recoverable")
            ):
                self._clear_recoverable_child_reference(parent.frame_id, child_frame_id)
                return None
            child = self.reopen_recoverable_frame(
                child.frame_id,
                task_id=parent.current_task_id or parent.task_id,
            )
        if child.status == FrameStatus.WAITING_CHILD:
            self.resume_from_child(child.frame_id)
            refreshed = self.get_frame(child.frame_id)
            if refreshed is not None:
                child = refreshed
        elif child.status == FrameStatus.AWAITING_APPROVAL:
            return child

        self.mark_waiting_child(parent.frame_id)
        refreshed_child = self.get_frame(child.frame_id)
        return refreshed_child or child

    def prepare_recoverable_focus_resume(
        self,
        parent_frame_id: str,
        task_id: str | None = None,
    ) -> SkillFrameState | None:
        """Return the recoverable focus child and put its parent back in WAITING_CHILD.

        This is the deterministic continuation path used before the root LLM
        loop runs.  If a focus stack exists, the deepest leaf is resumed by
        default so user follow-up continues where execution was interrupted.
        """
        parent = self._get_frame(parent_frame_id)
        if parent.status != FrameStatus.RUNNING:
            raise IllegalStateTransition(
                f"prepare_recoverable_focus_resume requires parent RUNNING, got {parent.status.value}"
            )

        state = parent.private_working_state
        if state.get("continuation_state") != "INTERRUPTED" and not state.get("recoverable"):
            return None

        return self._prepare_focus_stack_resume(
            parent,
            task_id=task_id,
            allow_awaiting_user=False,
        )

    def prepare_active_focus_resume(
        self,
        parent_frame_id: str,
        task_id: str | None = None,
    ) -> SkillFrameState | None:
        """Return the active child focus for deterministic pre-root resume.

        This covers both recoverable interruptions and direct child waits such
        as ``AWAITING_USER``.  The parent can be either RUNNING (interrupted
        focus) or WAITING_CHILD (open child awaiting user input).
        """
        parent = self._get_frame(parent_frame_id)
        if parent.status not in {FrameStatus.RUNNING, FrameStatus.WAITING_CHILD}:
            raise IllegalStateTransition(
                f"prepare_active_focus_resume requires parent RUNNING/WAITING_CHILD, got {parent.status.value}"
            )

        return self._prepare_focus_stack_resume(
            parent,
            task_id=task_id,
            allow_awaiting_user=True,
        )

    def clear_recoverable_child_focus(
        self,
        parent_frame_id: str,
        resolution: str = "SHELVED",
        focus_frame_ids: list[str] | None = None,
    ) -> None:
        """Shelve and detach the current pending recoverable child, if any."""
        parent = self._get_frame(parent_frame_id)
        child_frame_id = parent.private_working_state.get("pending_recoverable_child_frame_id")
        if not isinstance(child_frame_id, str) or not child_frame_id:
            self._clear_recoverable_child_reference(parent.frame_id)
            return
        focus_frame_ids = list(
            focus_frame_ids
            or _recoverable_focus_frame_ids(
                parent.private_working_state,
                parent.frame_id,
            )
        )
        if child_frame_id not in focus_frame_ids:
            focus_frame_ids.insert(0, child_frame_id)
        shelved_at = datetime.now(timezone.utc).isoformat()
        for focus_frame_id in reversed(focus_frame_ids):
            frame = self._load_related_child_frame(parent, focus_frame_id)
            if frame is None:
                continue
            frame.private_working_state["continuation_state"] = "SHELVED"
            frame.private_working_state["shelve_resolution"] = resolution
            frame.private_working_state["shelved_at"] = shelved_at
            if frame.status not in TERMINAL_STATES:
                self._transition(frame, FrameStatus.CANCELLED)
                frame.ended_at = shelved_at
            self._save(frame)
            self._generate_frame_report(frame)
        self._clear_recoverable_child_reference(parent.frame_id, child_frame_id)

    def set_evidence_refs(self, frame_id: str, evidence_refs: list[str]) -> None:
        """Set evidence references on a frame and persist."""
        frame = self._get_frame(frame_id)
        frame.evidence_refs = evidence_refs
        self._save(frame)

    def restore_frame(self, frame: SkillFrameState) -> None:
        """Restore a frame into in-memory store (e.g. after Worker restart).

        Only writes to memory — the file journal already has this frame.
        """
        self.store.save(frame)

    def save_frame(self, frame: SkillFrameState) -> None:
        """Persist an externally updated frame state.

        This is intentionally narrow: callers that own a frame-scoped runtime
        extension can update the frame object and ask the runtime to persist it
        without reaching into the private ``_save`` helper.
        """
        self._save(frame)

    def context_summary_for_frame(self, frame_id: str) -> dict[str, Any] | None:
        """Return the nearest root context summary visible from a frame stack."""
        for frame in self.get_call_stack(frame_id):
            summary = frame.private_working_state.get("root_context_summary")
            if isinstance(summary, dict):
                return _safe_json_copy(summary)
        return None

    # -- Internal helpers ----------------------------------------------------

    def _restore_task_frames_from_journal(self, task_id: str) -> None:
        if self._journal is None:
            return
        for frame in self._journal.load_by_task(task_id):
            self.restore_frame(frame)

    def _supersede_recoverable_roots(
        self,
        frames: list[SkillFrameState],
        *,
        latest: SkillFrameState,
        root_skill_id: str,
    ) -> None:
        if not frames:
            return
        superseded_at = datetime.now(timezone.utc).isoformat()
        for snapshot in frames:
            if snapshot.frame_id == latest.frame_id:
                continue
            self.restore_frame(snapshot)
            frame = self._get_frame(snapshot.frame_id)
            if not _is_active_recoverable_root(frame, root_skill_id):
                continue
            frame.private_working_state["continuation_state"] = "SUPERSEDED"
            frame.private_working_state["recoverable"] = False
            frame.private_working_state["superseded_by_frame_id"] = latest.frame_id
            frame.private_working_state["superseded_at"] = superseded_at
            frame.private_working_state["supersede_reason"] = "newer_recoverable_focus_selected"
            _clear_recoverable_focus_fields(frame.private_working_state)
            self._save(frame)

    def _find_function_frame_by_suspend_id(
        self,
        task_id: str,
        suspend_id: str,
    ) -> SkillFrameState | None:
        for frame in reversed(self.store.get_by_task(task_id)):
            if frame.frame_kind != FrameKind.FUNCTION_CALL:
                continue
            state_suspend_id = frame.private_working_state.get("suspend_id")
            request_suspend_id = (
                frame.approval_request.get("suspend_id")
                if isinstance(frame.approval_request, dict)
                else None
            )
            if suspend_id in {state_suspend_id, request_suspend_id}:
                return frame
        return None

    def _parent_stack(self, frame: SkillFrameState) -> list[SkillFrameState]:
        stack: list[SkillFrameState] = []
        parent_frame_id = frame.parent_frame_id
        visited = {frame.frame_id}
        while parent_frame_id and parent_frame_id not in visited:
            visited.add(parent_frame_id)
            parent = self.store.get(parent_frame_id)
            if parent is None and self._journal:
                parent = self._journal.load(frame.task_id, parent_frame_id)
                if parent is not None:
                    self.restore_frame(parent)
            if parent is None:
                break
            stack.append(parent)
            parent_frame_id = parent.parent_frame_id
        return stack

    def _force_terminal_status(
        self,
        frame: SkillFrameState,
        target: FrameStatus,
    ) -> None:
        if frame.status == target:
            return
        if frame.status in TERMINAL_STATES:
            frame.status = target
            return
        if target in VALID_TRANSITIONS.get(frame.status, frozenset()):
            self._transition(frame, target)
            return
        frame.status = target

    def _record_child_result_on_parent(
        self,
        parent: SkillFrameState,
        child: SkillFrameState,
    ) -> None:
        if child.frame_kind == FrameKind.FUNCTION_CALL:
            function_results = parent.private_working_state.setdefault("function_results", {})
            entry = {
                "function_id": child.input.get("function_id"),
                "version": child.input.get("version"),
                "result": _safe_json_copy(child.output or {}),
                "completed_at": child.ended_at,
            }
            entry.update(self._execution_report_payload_for_frame(child))
            function_results[child.frame_id] = entry
            self._link_execution_report_to_parent_context(parent, entry)
            return

        child_results = parent.private_working_state.setdefault("child_results", {})
        promoted = {
            "frame_id": child.frame_id,
            "skill_id": child.skill_id,
            "agent_id": child.agent_id,
            "frame_name": child.frame_name,
            "frame_kind": child.frame_kind.value,
            "status": child.status.value,
            "result_summary": child.result_summary,
            "structured_output": _safe_json_copy(child.output or {}),
            "artifact_refs": list(child.artifact_refs),
            "evidence_refs": list(child.evidence_refs),
        }
        promoted.update(self._execution_report_payload_for_frame(child))
        child_results[child.frame_id] = promoted
        _record_child_continuation_summary_on_parent(parent, promoted)
        self._link_execution_report_to_parent_context(parent, promoted)

    def _clear_business_function_wait_state(
        self,
        frame: SkillFrameState,
        function_frame_id: str,
    ) -> None:
        if frame.private_working_state.get("pending_function_call_frame_id") == function_frame_id:
            frame.private_working_state.pop("pending_function_call_frame_id", None)
            frame.private_working_state.pop("pending_function_call", None)
        frame.private_working_state.pop("pending_child_approval_frame_id", None)
        frame.private_working_state.pop("pending_recoverable_child_frame_id", None)
        frame.private_working_state.pop("pending_recoverable_child", None)
        _clear_recoverable_focus_fields(frame.private_working_state)

    def _execution_report_payload_for_frame(
        self,
        frame: SkillFrameState,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        report_ref = _execution_report_ref_from_frame(frame)
        if report_ref:
            payload["execution_report_ref"] = report_ref
        report_digest = _execution_report_digest_from_frame(frame)
        if report_digest:
            payload["execution_report_digest"] = report_digest
        return payload

    def _get_frame(self, frame_id: str) -> SkillFrameState:
        frame = self.store.get(frame_id)
        if frame is None:
            raise FrameNotFound(f"Frame not found: {frame_id}")
        return frame

    def _manifest_for_frame(self, frame: SkillFrameState) -> SkillManifest | None:
        """Return the frame-frozen manifest, falling back to the current registry."""
        snapshot = frame.private_working_state.get("_skill_manifest")
        if isinstance(snapshot, dict):
            try:
                return SkillManifest(**snapshot)
            except Exception:
                logger.warning("Invalid manifest snapshot in frame=%s", frame.frame_id, exc_info=True)
        if not frame.skill_id:
            return None
        return self.registry.get_manifest(frame.skill_id)

    def _save(self, frame: SkillFrameState) -> None:
        """Save frame to in-memory store and optionally persist to file journal."""
        self.store.save(frame)
        if self._journal:
            self._journal.save(frame)

    def _generate_frame_report(self, frame: SkillFrameState) -> FrameExecutionReport | None:
        """Generate and link an execution report without changing frame status."""
        if self._report_generator is None or self._journal is None:
            return None
        try:
            report = self._report_generator.generate_for_frame(frame.task_id, frame.frame_id)
        except FileNotFoundError:
            logger.debug(
                "Skipping frame execution report; frame snapshot missing: task=%s frame=%s",
                frame.task_id,
                frame.frame_id,
            )
            return None
        except Exception:
            logger.warning(
                "Failed to generate frame execution report: task=%s frame=%s",
                frame.task_id,
                frame.frame_id,
                exc_info=True,
            )
            return None

        self._attach_execution_report_metadata(frame, report)
        return report

    def _attach_execution_report_metadata(
        self,
        frame: SkillFrameState,
        report: FrameExecutionReport,
    ) -> None:
        digest = _compact_execution_report_digest(report.digest)
        frame.private_working_state["execution_report_ref"] = report.report_ref
        frame.private_working_state["execution_report_digest"] = digest
        if _is_conversation_root_frame(frame, "system.root"):
            summary = frame.private_working_state.setdefault("root_context_summary", {})
            _append_execution_report_to_summary(summary, report.report_ref, digest)
            summary["latest_execution_report_ref"] = report.report_ref
        self.store.save(frame)
        if self._journal:
            self._journal.save(frame)

    def _link_execution_report_to_parent_context(
        self,
        parent: SkillFrameState,
        payload: dict[str, Any],
    ) -> None:
        report_ref = _execution_report_ref_from_payload(payload)
        if not report_ref:
            return
        digest = _execution_report_digest_from_payload(payload)
        summary = parent.private_working_state.setdefault("root_context_summary", {})
        _append_execution_report_to_summary(summary, report_ref, digest)
        if digest:
            summary["latest_execution_report_ref"] = report_ref
        if _attach_execution_report_to_active_plan(parent.private_working_state, payload, report_ref, digest):
            _sync_active_plan_summary(parent)

    def _transition(self, frame: SkillFrameState, target: FrameStatus) -> None:
        """Validate and apply a state transition."""
        current = frame.status
        allowed = VALID_TRANSITIONS.get(current, frozenset())
        if target not in allowed:
            raise IllegalStateTransition(
                f"Cannot transition from {current.value} to {target.value}"
            )
        frame.status = target

    def _compact_persistent_frame_context(
        self,
        frame: SkillFrameState,
        turn_entry: dict[str, Any],
    ) -> None:
        summary = frame.private_working_state.setdefault("root_context_summary", {})
        summary["turn_count"] = int(summary.get("turn_count") or 0) + 1
        summary["latest_summary"] = turn_entry["summary"]
        summary["latest_structured_output"] = _compact_structured_output(turn_entry["structured_output"])

        recent_turns = summary.setdefault("recent_turns", [])
        recent_turns.append({
            "summary": turn_entry["summary"],
            "structured_output": _compact_structured_output(turn_entry["structured_output"]),
            "artifact_refs": turn_entry["artifact_refs"],
            "evidence_refs": turn_entry["evidence_refs"],
            "submitted_at": turn_entry["submitted_at"],
        })
        del recent_turns[:-PERSISTENT_FRAME_MAX_RECENT_SUMMARIES]

        summary["artifact_refs"] = _append_unique_capped(
            summary.get("artifact_refs"),
            turn_entry["artifact_refs"],
            limit=50,
        )
        summary["evidence_refs"] = _append_unique_capped(
            summary.get("evidence_refs"),
            turn_entry["evidence_refs"],
            limit=50,
        )

        turn_results = frame.private_working_state.get("turn_results")
        if isinstance(turn_results, list) and len(turn_results) > PERSISTENT_FRAME_MAX_TURN_RESULTS:
            dropped = len(turn_results) - PERSISTENT_FRAME_MAX_TURN_RESULTS
            del turn_results[:-PERSISTENT_FRAME_MAX_TURN_RESULTS]
            summary["compacted_turn_result_count"] = int(summary.get("compacted_turn_result_count") or 0) + dropped

        if len(frame.private_messages) > PERSISTENT_FRAME_MAX_PRIVATE_MESSAGES:
            dropped = len(frame.private_messages) - PERSISTENT_FRAME_MAX_PRIVATE_MESSAGES
            del frame.private_messages[:-PERSISTENT_FRAME_MAX_PRIVATE_MESSAGES]
            summary["compacted_private_message_count"] = (
                int(summary.get("compacted_private_message_count") or 0) + dropped
            )
        summary["private_messages_retained"] = len(frame.private_messages)

    def _context_snapshot_for_function_call(self, parent_frame_id: str) -> dict[str, Any]:
        return {
            "visibility": "passthrough",
            "root_context_summary": self.context_summary_for_frame(parent_frame_id),
        }

    def _find_active_child_frame(self, parent: SkillFrameState) -> SkillFrameState | None:
        for child_frame_id in reversed(parent.child_frame_ids):
            child = self._load_related_child_frame(parent, child_frame_id)
            if child is not None and child.status not in TERMINAL_STATES:
                return child
        return None

    def _active_descendant_stack(self, root: SkillFrameState) -> list[SkillFrameState]:
        """Return root-to-deepest-active stack for recoverable focus metadata."""
        stack = [root]
        current = root
        visited = {root.frame_id}
        while True:
            child = self._find_active_child_frame(current)
            if child is None or child.frame_id in visited:
                break
            stack.append(child)
            visited.add(child.frame_id)
            current = child
        return stack

    def _prepare_focus_stack_resume(
        self,
        owner: SkillFrameState,
        *,
        task_id: str | None,
        allow_awaiting_user: bool,
    ) -> SkillFrameState | None:
        """Prepare the deepest focus leaf for deterministic pre-root resume."""
        stack = self._focus_stack_for_resume(owner)
        if len(stack) < 2:
            self._clear_recoverable_child_reference(owner.frame_id)
            return None

        current_task_id = task_id or owner.current_task_id or owner.task_id
        if current_task_id:
            rebound_stack: list[SkillFrameState] = []
            for frame in stack:
                rebound_stack.append(self.rebind_frame_to_task(
                    frame.frame_id,
                    current_task_id,
                    session_id=owner.session_id,
                    conversation_id=owner.conversation_id,
                ))
            stack = rebound_stack

        focus = stack[-1]
        if focus.status == FrameStatus.AWAITING_USER:
            if not allow_awaiting_user:
                return focus
            focus = self.resume_from_user_input(focus.frame_id, task_id=current_task_id)
            stack[-1] = focus
        elif focus.status in TERMINAL_STATES:
            if (
                focus.private_working_state.get("continuation_state") != "INTERRUPTED"
                or not focus.private_working_state.get("recoverable")
            ):
                self._clear_recoverable_child_reference(owner.frame_id, focus.frame_id)
                return None
            focus = self.reopen_recoverable_frame(
                focus.frame_id,
                task_id=current_task_id,
            )
            if current_task_id:
                focus = self.rebind_frame_to_task(
                    focus.frame_id,
                    current_task_id,
                    session_id=owner.session_id,
                    conversation_id=owner.conversation_id,
                )
            stack[-1] = focus

        if focus.status == FrameStatus.WAITING_CHILD:
            self.resume_from_child(focus.frame_id)
            refreshed_focus = self.get_frame(focus.frame_id)
            if refreshed_focus is not None:
                focus = refreshed_focus
                stack[-1] = focus
        elif focus.status == FrameStatus.AWAITING_APPROVAL:
            self._mark_focus_ancestors_waiting(stack)
            return focus

        self._mark_focus_ancestors_waiting(stack)
        refreshed_focus = self.get_frame(focus.frame_id)
        return refreshed_focus or focus

    def _focus_stack_for_resume(self, owner: SkillFrameState) -> list[SkillFrameState]:
        """Load owner-to-leaf focus stack from persisted focus metadata."""
        state = owner.private_working_state
        snapshot = state.get("active_focus_stack") or state.get("recoverable_focus_stack")
        if isinstance(snapshot, list) and snapshot:
            stack = self._load_focus_stack_snapshot(owner, snapshot)
            if len(stack) >= 2:
                return stack

        active_stack = self._active_descendant_stack(owner)
        if len(active_stack) >= 2:
            return active_stack

        focus_frame_id = state.get("active_focus_frame_id") or state.get("recoverable_focus_frame_id")
        if not isinstance(focus_frame_id, str) or not focus_frame_id or focus_frame_id == owner.frame_id:
            return [owner]
        focus = self._load_related_child_frame(owner, focus_frame_id)
        if focus is None or focus.parent_frame_id != owner.frame_id:
            return [owner]
        return [owner, focus]

    def _load_focus_stack_snapshot(
        self,
        owner: SkillFrameState,
        snapshot: list[Any],
    ) -> list[SkillFrameState]:
        stack = [owner]
        current = owner
        visited = {owner.frame_id}
        entries = [entry for entry in snapshot if isinstance(entry, dict)]
        if not entries:
            return stack

        start_index = 0
        first_frame_id = entries[0].get("frame_id")
        if first_frame_id == owner.frame_id:
            start_index = 1

        for entry in entries[start_index:]:
            frame_id = entry.get("frame_id")
            if not isinstance(frame_id, str) or not frame_id or frame_id in visited:
                break
            child = self._load_related_child_frame(current, frame_id)
            if child is None or child.parent_frame_id != current.frame_id:
                break
            stack.append(child)
            visited.add(child.frame_id)
            current = child
        return stack

    def _mark_focus_ancestors_waiting(self, stack: list[SkillFrameState]) -> None:
        for ancestor in stack[:-1]:
            refreshed = self.get_frame(ancestor.frame_id) or ancestor
            if refreshed.status == FrameStatus.RUNNING:
                self.mark_waiting_child(refreshed.frame_id)

    def _set_active_focus(
        self,
        *,
        owner: SkillFrameState,
        focus: SkillFrameState,
        kind: str,
        status: str,
        reason: str,
        error: str,
        last_task_id: str,
        updated_at: str,
        stack: list[SkillFrameState],
        extra_summary: dict[str, Any] | None = None,
    ) -> None:
        summary = {
            "frame_id": focus.frame_id,
            "skill_id": focus.skill_id,
            "agent_id": focus.agent_id,
            "frame_name": focus.frame_name,
            "frame_kind": focus.frame_kind.value,
            "focus_kind": kind,
            "status": focus.status.value,
            "input": _safe_json_copy(focus.input),
            "reason": reason,
            "last_error": error,
            "last_task_id": last_task_id,
            "updated_at": updated_at,
        }
        if extra_summary:
            summary.update(_safe_json_copy(extra_summary))
        owner.private_working_state["active_focus_frame_id"] = focus.frame_id
        owner.private_working_state["active_focus_kind"] = kind
        owner.private_working_state["active_focus_status"] = status
        owner.private_working_state["active_focus_updated_at"] = updated_at
        owner.private_working_state["active_focus_summary"] = summary
        owner.private_working_state["active_focus_stack"] = _frame_stack_snapshot(stack)

    def _set_recoverable_focus(
        self,
        *,
        owner: SkillFrameState,
        focus: SkillFrameState,
        kind: str,
        status: str,
        reason: str,
        error: str,
        last_task_id: str,
        interrupted_at: str,
        stack: list[SkillFrameState],
    ) -> None:
        summary = {
            "frame_id": focus.frame_id,
            "skill_id": focus.skill_id,
            "agent_id": focus.agent_id,
            "frame_name": focus.frame_name,
            "frame_kind": focus.frame_kind.value,
            "focus_kind": kind,
            "status": focus.status.value,
            "input": _safe_json_copy(focus.input),
            "reason": reason,
            "last_error": error,
            "last_task_id": last_task_id,
            "interrupted_at": interrupted_at,
        }
        owner.private_working_state["recoverable_focus_frame_id"] = focus.frame_id
        owner.private_working_state["recoverable_focus_kind"] = kind
        owner.private_working_state["recoverable_focus_status"] = status
        owner.private_working_state["recoverable_focus_interrupted_at"] = interrupted_at
        owner.private_working_state["recoverable_focus_summary"] = summary
        owner.private_working_state["recoverable_focus_stack"] = _frame_stack_snapshot(stack)
        self._set_active_focus(
            owner=owner,
            focus=focus,
            kind=kind,
            status=status,
            reason=reason,
            error=error,
            last_task_id=last_task_id,
            updated_at=interrupted_at,
            stack=stack,
        )

    def _load_related_child_frame(
        self,
        parent: SkillFrameState,
        child_frame_id: str,
    ) -> SkillFrameState | None:
        child = self.store.get(child_frame_id)
        if child is not None:
            return child
        if not self._journal:
            return None
        task_ids = [
            parent.task_id,
            parent.current_task_id,
            parent.origin_task_id,
            *(parent.last_task_ids or []),
        ]
        seen_task_ids: set[str] = set()
        for task_id in task_ids:
            if not task_id or task_id in seen_task_ids:
                continue
            seen_task_ids.add(task_id)
            child = self._journal.load(
                task_id,
                child_frame_id,
                conversation_id=parent.conversation_id,
            )
            if child is not None:
                self.restore_frame(child)
                return child
        if parent.conversation_id:
            for candidate in self._journal.load_by_conversation(parent.conversation_id):
                if candidate.frame_id == child_frame_id:
                    self.restore_frame(candidate)
                    return candidate
        return None

    def _clear_recoverable_child_reference(
        self,
        parent_frame_id: str,
        child_frame_id: str | None = None,
    ) -> None:
        parent = self._get_frame(parent_frame_id)
        pending = parent.private_working_state.get("pending_recoverable_child_frame_id")
        if child_frame_id and pending not in {child_frame_id, None}:
            return
        parent.private_working_state.pop("pending_recoverable_child_frame_id", None)
        parent.private_working_state.pop("pending_recoverable_child", None)
        _clear_recoverable_focus_fields(parent.private_working_state)
        self._save(parent)

    def _resume_pending_child_approval(
        self,
        parent_frame: SkillFrameState,
        approval_result: str,
        comment: str,
    ) -> None:
        child_frame_id = parent_frame.private_working_state.get("pending_child_approval_frame_id")
        if not isinstance(child_frame_id, str) or not child_frame_id:
            return
        child_frame = self.store.get(child_frame_id)
        if child_frame is None and self._journal:
            child_frame = self._journal.load(parent_frame.task_id, child_frame_id)
            if child_frame is not None:
                self.restore_frame(child_frame)
        if child_frame is None:
            parent_frame.private_working_state.pop("pending_child_approval_frame_id", None)
            self._save(parent_frame)
            return
        if child_frame.status == FrameStatus.AWAITING_APPROVAL:
            self.resume_from_approval(child_frame_id, approval_result, comment)

        parent_frame.private_working_state.pop("pending_child_approval_frame_id", None)
        self._save(parent_frame)

    def _resume_pending_function_call(
        self,
        caller_frame: SkillFrameState,
        approval_result: str,
        comment: str,
    ) -> None:
        function_frame_id = caller_frame.private_working_state.get("pending_function_call_frame_id")
        if not isinstance(function_frame_id, str) or not function_frame_id:
            return
        function_frame = self.store.get(function_frame_id)
        if function_frame is None and self._journal:
            function_frame = self._journal.load(caller_frame.task_id, function_frame_id)
            if function_frame is not None:
                self.restore_frame(function_frame)
        if function_frame is None or function_frame.frame_kind != FrameKind.FUNCTION_CALL:
            return
        if function_frame.status != FrameStatus.AWAITING_APPROVAL:
            return

        normalized = (approval_result or "").strip().lower()
        function_frame.private_working_state["approval_result"] = approval_result
        function_frame.private_working_state["approval_comment"] = comment
        function_frame.approval_request = None
        if normalized in {"approved", "approve", "ok", "accepted"}:
            self._transition(function_frame, FrameStatus.RUNNING)
            self.complete_function_call(
                function_frame.frame_id,
                {
                    "status": "RESUME_DISPATCHED",
                    "approval_result": approval_result,
                    "comment": comment,
                    "message": (
                        "Approval accepted. Business function execution is "
                        "owned by Java suspension service."
                    ),
                },
                summary="Business function approval accepted.",
            )
        else:
            function_frame.private_working_state["approval_state"] = "REJECTED"
            self._transition(function_frame, FrameStatus.CANCELLED)
            function_frame.ended_at = datetime.now(timezone.utc).isoformat()
            self._save(function_frame)
            self._generate_frame_report(function_frame)
            caller_frame.private_working_state["continuation_state"] = "INTERRUPTED"
            caller_frame.private_working_state["interrupt_reason"] = "approval_rejected"
            caller_frame.private_working_state["last_error"] = comment
            caller_frame.private_working_state["last_task_id"] = (
                caller_frame.current_task_id or caller_frame.task_id
            )
            caller_frame.private_working_state["recoverable"] = True
            caller_frame.private_working_state["interrupted_at"] = datetime.now(timezone.utc).isoformat()

        caller_frame.private_working_state.pop("pending_function_call_frame_id", None)
        caller_frame.private_working_state.pop("pending_function_call", None)
        self._save(caller_frame)
        self._generate_frame_report(caller_frame)


def _report_generator_from_journal(
    journal: FileFrameJournal | None,
) -> FrameExecutionReportGenerator | None:
    if journal is None:
        return None
    try:
        return FrameExecutionReportGenerator(journal.data_root)
    except Exception:
        logger.warning("Failed to initialize frame execution report generator", exc_info=True)
        return None


def _execution_report_ref_from_frame(frame: SkillFrameState) -> str | None:
    value = frame.private_working_state.get("execution_report_ref")
    return value if isinstance(value, str) and value else None


def _execution_report_digest_from_frame(frame: SkillFrameState) -> dict[str, Any] | None:
    value = frame.private_working_state.get("execution_report_digest")
    return _safe_json_copy(value) if isinstance(value, dict) else None


def _execution_report_ref_from_payload(payload: dict[str, Any]) -> str | None:
    value = _extract_value(
        payload,
        "execution_report_ref",
        "executionReportRef",
        "report_ref",
        "reportRef",
    )
    return value if isinstance(value, str) and value else None


def _execution_report_digest_from_payload(payload: dict[str, Any]) -> dict[str, Any] | None:
    value = _extract_value(
        payload,
        "execution_report_digest",
        "executionReportDigest",
        "digest",
    )
    return _compact_execution_report_digest(value) if isinstance(value, dict) else None


def _persistent_turn_report_status(structured_output: dict[str, Any]) -> str:
    value = str(
        structured_output.get("turn_status")
        or structured_output.get("status")
        or ""
    ).strip().upper()
    if value in {"WAITING_FOR_USER_INPUT", "AWAITING_USER", "PENDING_INFO"}:
        return FrameStatus.AWAITING_USER.value
    if value in {"FAILED", "ERROR"}:
        return FrameStatus.FAILED.value
    return FrameStatus.COMPLETED.value


def _compact_execution_report_digest(digest: dict[str, Any]) -> dict[str, Any]:
    child_reports = digest.get("child_reports")
    compact_child_reports = child_reports[-10:] if isinstance(child_reports, list) else None
    compact = {
        "report_ref": digest.get("report_ref"),
        "frame_id": digest.get("frame_id"),
        "task_id": digest.get("task_id"),
        "skill_id": digest.get("skill_id"),
        "frame_kind": digest.get("frame_kind"),
        "status": digest.get("status"),
        "frame_status": digest.get("frame_status"),
        "summary": digest.get("summary"),
        "started_at": digest.get("started_at"),
        "ended_at": digest.get("ended_at"),
        "tool_call_count": digest.get("tool_call_count"),
        "child_frame_count": digest.get("child_frame_count"),
        "approval_required": digest.get("approval_required"),
        "error": digest.get("error"),
        "artifact_refs": digest.get("artifact_refs"),
        "evidence_refs": digest.get("evidence_refs"),
        "child_reports": compact_child_reports,
        "generated_at": digest.get("generated_at"),
        "generator_version": digest.get("generator_version"),
    }
    return {
        key: _safe_json_value_copy(value)
        for key, value in compact.items()
        if value not in (None, "", [], {})
    }


def _append_execution_report_to_summary(
    summary: dict[str, Any],
    report_ref: str,
    digest: dict[str, Any] | None,
) -> None:
    reports = summary.setdefault("execution_reports", [])
    if not isinstance(reports, list):
        reports = []
        summary["execution_reports"] = reports
    reports[:] = [
        item for item in reports
        if not isinstance(item, dict) or item.get("report_ref") != report_ref
    ]
    entry = {"report_ref": report_ref}
    if digest:
        entry.update(_compact_execution_report_digest(digest))
    reports.append(entry)
    del reports[:-PERSISTENT_FRAME_MAX_TURN_RESULTS]


def _attach_execution_report_to_active_plan(
    working_state: dict[str, Any],
    payload: dict[str, Any],
    report_ref: str,
    digest: dict[str, Any] | None,
) -> bool:
    active_plan = working_state.get("active_plan")
    if not isinstance(active_plan, (dict, list)):
        return False
    steps = list(_iter_active_plan_steps(active_plan))
    if not steps:
        return False
    step_id = _extract_plan_step_id(payload)
    if step_id:
        candidates = [step for step in steps if _plan_step_matches_id(step, step_id)]
    else:
        candidates = [
            step for step in steps
            if not _execution_report_ref_from_payload(step)
        ]
    if len(candidates) != 1:
        return False
    target = candidates[0]
    target["execution_report_ref"] = report_ref
    if digest:
        target["execution_report_digest"] = _compact_execution_report_digest(digest)
    return True


def _iter_active_plan_steps(active_plan: dict[str, Any] | list[Any]) -> list[dict[str, Any]]:
    if isinstance(active_plan, list):
        return [item for item in active_plan if isinstance(item, dict)]
    for key in ("steps", "items", "tasks", "subtasks"):
        value = active_plan.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
    return []


def _extract_plan_step_id(payload: dict[str, Any]) -> str | None:
    value = _extract_value(
        payload,
        "active_plan_step_id",
        "activePlanStepId",
        "plan_step_id",
        "planStepId",
        "step_id",
        "stepId",
    )
    if isinstance(value, (str, int, float)) and str(value).strip():
        return str(value).strip()
    return None


def _plan_step_matches_id(step: dict[str, Any], step_id: str) -> bool:
    for key in ("active_plan_step_id", "activePlanStepId", "plan_step_id", "planStepId", "step_id", "stepId", "id"):
        value = step.get(key)
        if isinstance(value, (str, int, float)) and str(value).strip() == step_id:
            return True
    return False


def _function_result_summary(frame: SkillFrameState, result: dict[str, Any]) -> str:
    function_id = frame.input.get("function_id") or frame.skill_id
    status = result.get("status") or result.get("approval_result") or "completed"
    return f"Business function {function_id} {status}"


def _compact_structured_output(output: dict[str, Any]) -> dict[str, Any]:
    try:
        encoded = json.dumps(output, ensure_ascii=False, sort_keys=True, default=str)
    except TypeError:
        return {"summary": str(output)}
    if len(encoded) <= 2000:
        return output
    return {
        "summary": "[structured_output omitted from root_context_summary]",
        "size": len(encoded),
        "keys": sorted(str(key) for key in output.keys()),
    }


def _record_child_continuation_summary_on_parent(
    parent: SkillFrameState,
    child_promoted: dict[str, Any],
) -> None:
    summary = _child_continuation_summary_from_promoted(child_promoted)
    if not summary:
        return

    parent.private_working_state["latest_child_result_summary"] = summary
    root_summary = parent.private_working_state.setdefault("root_context_summary", {})
    root_summary["latest_child_result_summary"] = summary

    summaries = root_summary.setdefault("child_result_summaries", [])
    if not isinstance(summaries, list):
        summaries = []
        root_summary["child_result_summaries"] = summaries
    frame_id = summary.get("frame_id")
    report_ref = summary.get("execution_report_ref")
    summaries[:] = [
        item for item in summaries
        if not (
            isinstance(item, dict)
            and (
                (frame_id and item.get("frame_id") == frame_id)
                or (report_ref and item.get("execution_report_ref") == report_ref)
            )
        )
    ]
    summaries.append(summary)
    del summaries[:-PERSISTENT_FRAME_MAX_CHILD_RESULT_SUMMARIES]


def _child_continuation_summary_from_promoted(
    child_promoted: dict[str, Any],
) -> dict[str, Any]:
    if not isinstance(child_promoted, dict):
        return {}
    structured = child_promoted.get("structured_output")
    structured_output = structured if isinstance(structured, dict) else {}

    summary: dict[str, Any] = {}
    for key in (
        "frame_id",
        "skill_id",
        "agent_id",
        "frame_name",
        "frame_kind",
        "result_summary",
        "execution_report_ref",
    ):
        value = child_promoted.get(key)
        if value not in (None, "", [], {}):
            summary[key] = _safe_continuation_value_copy(value, key)

    frame_status = child_promoted.get("status")
    if frame_status not in (None, "", [], {}):
        summary["frame_status"] = _safe_continuation_value_copy(frame_status, "frame_status")

    if structured_output:
        summary["structured_output"] = _compact_structured_output(
            _safe_continuation_value_copy(structured_output, "structured_output")
        )

    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "status",
        "business_status",
        "businessStatus",
        "status",
        "state",
    )
    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "intent_resolution",
        "intent_resolution",
        "intentResolution",
        "continuation_decision",
        "continuationDecision",
    )
    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "next_step",
        "next_step",
        "nextStep",
        "next_steps",
        "nextSteps",
        "recommended_action",
        "recommendedAction",
    )
    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "missing_fields",
        "missing_fields",
        "missingFields",
        "required_fields",
        "requiredFields",
    )
    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "awaiting_user_input",
        "awaiting_user_input",
        "awaitingUserInput",
        "needs_clarification",
        "needsClarification",
    )
    _copy_promoted_business_field(
        summary,
        child_promoted,
        structured_output,
        "active_plan",
        "active_plan",
        "activePlan",
        "plan",
    )

    for key in ("artifact_refs", "evidence_refs", "execution_report_digest"):
        value = child_promoted.get(key)
        if value not in (None, "", [], {}):
            summary[key] = _safe_continuation_value_copy(value, key)
    return summary


def _copy_promoted_business_field(
    target: dict[str, Any],
    promoted: dict[str, Any],
    structured_output: dict[str, Any],
    target_key: str,
    *source_keys: str,
) -> None:
    value = _extract_value(structured_output, *source_keys)
    if value is None and target_key != "status":
        value = _extract_value(promoted, *source_keys)
    if value not in (None, "", [], {}):
        target[target_key] = _safe_continuation_value_copy(value, target_key)


def _safe_continuation_value_copy(value: Any, key_hint: str | None = None) -> Any:
    return _sanitize_continuation_value(_safe_json_value_copy(value), key_hint)


def _sanitize_continuation_value(value: Any, key_hint: str | None = None) -> Any:
    if key_hint and _is_sensitive_continuation_key(key_hint):
        return CONTINUATION_REDACTED
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if _is_sensitive_continuation_key(key_text):
                sanitized[key_text] = CONTINUATION_REDACTED
            else:
                sanitized[key_text] = _sanitize_continuation_value(item, key_text)
        return sanitized
    if isinstance(value, list):
        return [_sanitize_continuation_value(item, key_hint) for item in value]
    if isinstance(value, str):
        return _sanitize_continuation_string(value, key_hint)
    return value


def _sanitize_continuation_string(value: str, key_hint: str | None = None) -> str:
    if key_hint and _is_url_like_continuation_key(key_hint) and value.lower().startswith(("http://", "https://")):
        return CONTINUATION_REDACTED_URL
    text = HTTP_URL_PATTERN.sub(CONTINUATION_REDACTED_URL, value)
    text = OPENAI_KEY_PATTERN.sub(CONTINUATION_REDACTED, text)
    if len(text) > PERSISTENT_FRAME_MAX_CONTINUATION_STRING_CHARS:
        text = text[:PERSISTENT_FRAME_MAX_CONTINUATION_STRING_CHARS] + "...[truncated]"
    return text


def _is_sensitive_continuation_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    return any(fragment in normalized for fragment in CONTINUATION_SENSITIVE_KEY_FRAGMENTS)


def _is_url_like_continuation_key(key: str) -> bool:
    normalized = key.replace("-", "_").lower()
    return any(fragment in normalized for fragment in CONTINUATION_URL_KEY_FRAGMENTS)


def _append_unique_capped(existing: Any, incoming: list[str], limit: int) -> list[str]:
    values: list[str] = []
    if isinstance(existing, list):
        values.extend(str(item) for item in existing if item is not None)
    values.extend(str(item) for item in incoming if item is not None)

    deduped: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        deduped.append(value)
    return deduped[-limit:]


def _recoverable_focus_kind(
    owner: SkillFrameState,
    focus: SkillFrameState,
    stack: list[SkillFrameState],
) -> str:
    if focus.frame_id == owner.frame_id:
        return "ROOT"
    if focus.frame_kind == FrameKind.FUNCTION_CALL:
        return "APPROVAL" if focus.status == FrameStatus.AWAITING_APPROVAL else "FUNCTION_CALL"
    if len(stack) > 2:
        return "NESTED_SKILL"
    return "CHILD_SKILL"


def _awaiting_user_input_payload(
    *,
    summary: str,
    structured_output: dict[str, Any],
    artifact_refs: list[str] | None,
    evidence_refs: list[str] | None,
    submitted_at: str,
) -> dict[str, Any]:
    output = structured_output if isinstance(structured_output, dict) else {}
    user_message = _first_non_empty_string(
        output.get("user_message"),
        output.get("message"),
        output.get("prompt"),
        output.get("next_step"),
        output.get("nextStep"),
        summary,
    )
    payload: dict[str, Any] = {
        "turn_status": "WAITING_FOR_USER_INPUT",
        "user_message": user_message,
        "summary": summary,
        "structured_output": _safe_json_copy(output),
        "artifact_refs": list(artifact_refs or []),
        "evidence_refs": list(evidence_refs or []),
        "submitted_at": submitted_at,
    }
    for key in (
        "required_fields",
        "missing_fields",
        "next_step",
        "status",
        "business_object",
        "current_step",
    ):
        if key in output:
            payload[key] = _safe_json_copy(output[key])
    return payload


def _append_synthetic_private_assistant_message(
    frame: SkillFrameState,
    content: Any,
) -> None:
    if not isinstance(content, str) or not content.strip():
        return
    text = content.strip()
    for message in reversed(frame.private_messages[-4:]):
        if not isinstance(message, dict):
            continue
        if message.get("role") != "assistant":
            continue
        existing = message.get("content")
        if isinstance(existing, str) and existing.strip() == text:
            return
    frame.private_messages.append({
        "role": "assistant",
        "content": text,
        "synthetic": True,
        "source": "submit_frame_result.structured_output",
    })


def _first_non_empty_string(*values: Any) -> str:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _frame_stack_snapshot(stack: list[SkillFrameState]) -> list[dict[str, Any]]:
    return [
        {
            "frame_id": frame.frame_id,
            "parent_frame_id": frame.parent_frame_id,
            "skill_id": frame.skill_id,
            "agent_id": frame.agent_id,
            "frame_name": frame.frame_name,
            "frame_kind": frame.frame_kind.value,
            "status": frame.status.value,
            "input": _safe_json_copy(frame.input),
        }
        for frame in stack
    ]


def _clear_recoverable_focus_fields(working_state: dict[str, Any]) -> None:
    for key in RECOVERABLE_FOCUS_KEYS:
        working_state.pop(key, None)
    _clear_active_focus_fields(working_state)


def _clear_active_focus_fields(working_state: dict[str, Any]) -> None:
    for key in ACTIVE_FOCUS_KEYS:
        working_state.pop(key, None)
    for key in AWAITING_USER_KEYS:
        working_state.pop(key, None)


def _recoverable_focus_frame_ids(
    working_state: dict[str, Any],
    owner_frame_id: str,
) -> list[str]:
    stack = working_state.get("active_focus_stack") or working_state.get("recoverable_focus_stack")
    frame_ids: list[str] = []
    if isinstance(stack, list):
        for entry in stack:
            if not isinstance(entry, dict):
                continue
            frame_id = entry.get("frame_id")
            if isinstance(frame_id, str) and frame_id and frame_id != owner_frame_id:
                frame_ids.append(frame_id)
    focus_frame_id = working_state.get("active_focus_frame_id") or working_state.get("recoverable_focus_frame_id")
    if isinstance(focus_frame_id, str) and focus_frame_id and focus_frame_id != owner_frame_id:
        frame_ids.append(focus_frame_id)

    deduped: list[str] = []
    seen: set[str] = set()
    for frame_id in frame_ids:
        if frame_id in seen:
            continue
        seen.add(frame_id)
        deduped.append(frame_id)
    return deduped


def _dedupe_latest_frame_snapshots(frames: list[SkillFrameState]) -> list[SkillFrameState]:
    latest_by_id: dict[str, SkillFrameState] = {}
    for frame in frames:
        previous = latest_by_id.get(frame.frame_id)
        if previous is None or _recoverable_root_sort_key(frame) >= _recoverable_root_sort_key(previous):
            latest_by_id[frame.frame_id] = frame
    return list(latest_by_id.values())


def _is_active_recoverable_root(frame: SkillFrameState, root_skill_id: str) -> bool:
    if not _is_conversation_root_frame(frame, root_skill_id):
        return False
    state = frame.private_working_state
    continuation_state = str(state.get("continuation_state") or "").upper()
    if continuation_state in {"SUPERSEDED", "SHELVED"}:
        return False
    recoverable = state.get("recoverable") is True and continuation_state == "INTERRUPTED"
    focus_frame_id = state.get("recoverable_focus_frame_id")
    active_focus_frame_id = state.get("active_focus_frame_id")
    pending_child_frame_id = state.get("pending_recoverable_child_frame_id")
    awaiting_user_child_frame_id = state.get("pending_awaiting_user_child_frame_id")
    has_focus = isinstance(focus_frame_id, str) and bool(focus_frame_id)
    has_active_focus = (
        isinstance(active_focus_frame_id, str)
        and bool(active_focus_frame_id)
        and active_focus_frame_id != frame.frame_id
    )
    has_pending_child = isinstance(pending_child_frame_id, str) and bool(pending_child_frame_id)
    has_awaiting_user_child = (
        isinstance(awaiting_user_child_frame_id, str)
        and bool(awaiting_user_child_frame_id)
    )
    if frame.status == FrameStatus.COMPLETED and not (
        recoverable
        or has_focus
        or has_active_focus
        or has_pending_child
        or has_awaiting_user_child
    ):
        return False
    return recoverable or has_focus or has_active_focus or has_pending_child or has_awaiting_user_child


def _is_conversation_root_frame(frame: SkillFrameState, root_skill_id: str) -> bool:
    if frame.parent_frame_id:
        return False
    if frame.frame_kind == FrameKind.ROOT:
        return True
    return frame.skill_id == root_skill_id


def _recoverable_root_sort_key(frame: SkillFrameState) -> tuple[int, str, str, str]:
    sequence = frame.journal_seq if isinstance(frame.journal_seq, int) else -1
    state = frame.private_working_state
    timestamp = (
        str(state.get("interrupted_at") or "")
        or str(state.get("active_focus_updated_at") or "")
        or str(state.get("awaiting_user_input_at") or "")
        or str(state.get("recoverable_focus_interrupted_at") or "")
        or frame.journal_updated_at
        or frame.ended_at
        or frame.started_at
        or ""
    )
    task_id = frame.current_task_id or frame.task_id or ""
    return sequence, timestamp, task_id, frame.frame_id


def _interruption_history_entry(
    frame: SkillFrameState,
    summary: str,
    structured_output: dict[str, Any],
) -> dict[str, Any]:
    now = datetime.now(timezone.utc).isoformat()
    entry = {
        "reason": frame.private_working_state.get("interrupt_reason") or "unknown",
        "last_error": frame.private_working_state.get("last_error") or "",
        "last_task_id": frame.private_working_state.get("last_task_id") or "",
        "interrupted_at": frame.private_working_state.get("interrupted_at") or "",
        "resolution": _continuation_resolution(structured_output),
        "resolution_summary": summary,
        "abandoned_interruption": _extract_dict_value(
            structured_output,
            "abandoned_interruption",
            "abandonedInterruption",
            "previous_task_summary",
            "previousTaskSummary",
        ),
        "resolved_at": now,
    }
    focus_summary = frame.private_working_state.get("recoverable_focus_summary")
    if isinstance(focus_summary, dict):
        entry["recoverable_focus_summary"] = _safe_json_copy(focus_summary)
    focus_stack = frame.private_working_state.get("recoverable_focus_stack")
    if isinstance(focus_stack, list):
        entry["recoverable_focus_stack"] = _safe_json_copy({"stack": focus_stack}).get("stack", [])
    return entry


def _normalize_shelve_decision(decision: str | None) -> str:
    normalized = (decision or "").strip().upper()
    if normalized in {"ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"}:
        return normalized
    return "START_UNRELATED_NEW_TASK"


def _normalize_intent_resolution(value: str | None) -> str:
    normalized = (value or "").strip().upper()
    if normalized in INTENT_RESOLUTIONS:
        return normalized
    return ""


def _normalize_abandoned_interruption(value: dict[str, Any] | str | None) -> dict[str, Any]:
    if isinstance(value, dict):
        return _safe_json_copy(value)
    text = value.strip() if isinstance(value, str) else ""
    if text:
        return {"summary": text}
    return {"summary": "Previous interrupted work was shelved."}


def _append_interruption_history(
    frame: SkillFrameState,
    entry: dict[str, Any],
) -> None:
    summary = frame.private_working_state.setdefault("root_context_summary", {})
    history = summary.setdefault("interruption_history", [])
    if not isinstance(history, list):
        history = []
        summary["interruption_history"] = history
    history.append(entry)
    del history[:-PERSISTENT_FRAME_MAX_INTERRUPTION_HISTORY]
    focus_summary = entry.get("recoverable_focus_summary")
    if isinstance(focus_summary, dict):
        focus_history = summary.setdefault("focus_history", [])
        if not isinstance(focus_history, list):
            focus_history = []
            summary["focus_history"] = focus_history
        focus_history.append({
            "focus": _safe_json_copy(focus_summary),
            "resolution": entry.get("resolution"),
            "resolved_at": entry.get("resolved_at"),
        })
        del focus_history[:-PERSISTENT_FRAME_MAX_INTERRUPTION_HISTORY]


def _sync_active_plan_after_persistent_turn(
    frame: SkillFrameState,
    structured_output: dict[str, Any],
    summary: str,
    intent_resolution: str,
) -> None:
    """Persist or shelve root active_plan from a persistent turn result."""
    if intent_resolution in {"ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"}:
        _archive_active_plan(
            frame,
            resolution=intent_resolution,
            summary=summary,
        )
        frame.private_working_state.pop("active_plan", None)
        _sync_active_plan_summary(frame)

    has_plan, plan_value = _extract_present_value(
        structured_output,
        "active_plan",
        "activePlan",
    )
    if not has_plan:
        return

    active_plan = _normalize_active_plan(plan_value)
    if active_plan is None:
        _archive_active_plan(
            frame,
            resolution="CLEARED",
            summary=summary,
        )
        frame.private_working_state.pop("active_plan", None)
        _sync_active_plan_summary(frame)
        return

    terminal_status = _active_plan_terminal_status(active_plan)
    if terminal_status:
        frame.private_working_state["active_plan"] = active_plan
        _archive_active_plan(
            frame,
            resolution=terminal_status,
            summary=summary,
        )
        frame.private_working_state.pop("active_plan", None)
        _sync_active_plan_summary(frame)
        return

    frame.private_working_state["active_plan"] = active_plan
    _sync_active_plan_summary(frame)


def _archive_active_plan(
    frame: SkillFrameState,
    *,
    resolution: str,
    summary: str,
) -> None:
    active_plan = frame.private_working_state.get("active_plan")
    if not isinstance(active_plan, (dict, list)) or not active_plan:
        return
    root_summary = frame.private_working_state.setdefault("root_context_summary", {})
    history = root_summary.setdefault("plan_history", [])
    if not isinstance(history, list):
        history = []
        root_summary["plan_history"] = history
    history.append({
        "plan": _safe_json_value_copy(active_plan),
        "resolution": resolution,
        "summary": summary,
        "resolved_at": datetime.now(timezone.utc).isoformat(),
    })
    del history[:-PERSISTENT_FRAME_MAX_PLAN_HISTORY]


def _sync_active_plan_summary(frame: SkillFrameState) -> None:
    root_summary = frame.private_working_state.get("root_context_summary")
    if not isinstance(root_summary, dict):
        if "active_plan" not in frame.private_working_state:
            return
        root_summary = frame.private_working_state.setdefault("root_context_summary", {})
    active_plan = frame.private_working_state.get("active_plan")
    if isinstance(active_plan, (dict, list)) and active_plan:
        root_summary["active_plan"] = _safe_json_value_copy(active_plan)
    else:
        root_summary.pop("active_plan", None)


def _normalize_active_plan(value: Any) -> dict[str, Any] | list[Any] | None:
    if isinstance(value, dict) and value:
        return _safe_json_value_copy(value)
    if isinstance(value, list) and value:
        return _safe_json_value_copy(value)
    if isinstance(value, str) and value.strip():
        return {"summary": value.strip()}
    return None


def _active_plan_terminal_status(value: dict[str, Any] | list[Any]) -> str:
    if not isinstance(value, dict):
        return ""
    status = _extract_value(value, "status", "state", "plan_status", "planStatus")
    normalized = str(status or "").strip().upper()
    if normalized in {
        "COMPLETED",
        "COMPLETE",
        "DONE",
        "CANCELLED",
        "CANCELED",
        "ABANDONED",
        "SHELVED",
    }:
        return normalized
    return ""


def _continuation_resolution(structured_output: dict[str, Any]) -> str:
    value = _extract_value(
        structured_output,
        "intent_resolution",
        "intentResolution",
        "continuation_decision",
        "continuationDecision",
        "previous_frame_action",
        "previousFrameAction",
    )
    normalized = _normalize_intent_resolution(value if isinstance(value, str) else None)
    if normalized:
        return normalized
    if isinstance(value, str) and value.strip():
        return value.strip().upper()
    return "TURN_COMPLETED"


def _extract_dict_value(value: Any, *keys: str) -> dict[str, Any] | None:
    extracted = _extract_value(value, *keys)
    if isinstance(extracted, dict):
        return _safe_json_copy(extracted)
    if isinstance(extracted, str) and extracted.strip():
        return {"summary": extracted.strip()}
    return None


def _extract_value(value: Any, *keys: str) -> Any:
    if isinstance(value, dict):
        for key in keys:
            if key in value:
                return value[key]
        for nested_key in ("result", "output", "data", "structured_output", "structuredOutput"):
            nested = _extract_value(value.get(nested_key), *keys)
            if nested is not None:
                return nested
    return None


def _extract_present_value(value: Any, *keys: str) -> tuple[bool, Any]:
    if isinstance(value, dict):
        for key in keys:
            if key in value:
                return True, value[key]
        for nested_key in ("result", "output", "data", "structured_output", "structuredOutput"):
            present, nested = _extract_present_value(value.get(nested_key), *keys)
            if present:
                return True, nested
    return False, None


def _safe_json_value_copy(value: Any) -> Any:
    try:
        return json.loads(json.dumps(value, ensure_ascii=False, default=str))
    except Exception:
        return {"summary": str(value)}


def _safe_json_copy(value: dict[str, Any]) -> dict[str, Any]:
    try:
        return json.loads(json.dumps(value, ensure_ascii=False, default=str))
    except Exception:
        return {"summary": str(value)}
