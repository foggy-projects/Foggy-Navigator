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
RECOVERABLE_FOCUS_KEYS = (
    "recoverable_focus_frame_id",
    "recoverable_focus_kind",
    "recoverable_focus_status",
    "recoverable_focus_interrupted_at",
    "recoverable_focus_summary",
    "recoverable_focus_stack",
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
        max_nesting_depth: int = DEFAULT_MAX_NESTING_DEPTH,
    ) -> None:
        self.store = frame_store or FrameStore()
        self.registry = skill_registry or SkillRegistry()
        self._journal = journal
        self._max_nesting_depth = max_nesting_depth

    # -- Frame creation ------------------------------------------------------

    def invoke_skill(
        self,
        task_id: str,
        skill_id: str,
        skill_input: dict[str, Any] | None = None,
        parent_frame_id: str | None = None,
        conversation_id: str | None = None,
        session_id: str | None = None,
        current_task_id: str | None = None,
        origin_task_id: str | None = None,
        last_task_ids: list[str] | None = None,
    ) -> str:
        """Create a new Frame and transition to RUNNING.

        Returns the ``frame_id``.
        """
        frame_id = f"frm_{uuid.uuid4().hex[:12]}"
        now = datetime.now(timezone.utc).isoformat()

        frame = SkillFrameState(
            frame_id=frame_id,
            task_id=task_id,
            skill_id=skill_id,
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

        manifest = self.registry.get_manifest(skill_id)
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
            "Invoked skill=%s frame=%s task=%s parent=%s",
            skill_id, frame_id, task_id, parent_frame_id,
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
        logger.info(
            "Frame %s awaiting approval from child frame %s",
            parent_frame_id,
            child_frame_id,
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
        logger.warning("Frame %s failed: %s", frame_id, reason)

    def cancel_frame(self, frame_id: str) -> None:
        """Transition to CANCELLED from any non-terminal state."""
        frame = self._get_frame(frame_id)
        self._transition(frame, FrameStatus.CANCELLED)
        frame.ended_at = datetime.now(timezone.utc).isoformat()
        self._save(frame)
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

    def submit_persistent_turn_result(
        self,
        frame_id: str,
        summary: str,
        structured_output: dict[str, Any],
        artifact_refs: list[str] | None = None,
        evidence_refs: list[str] | None = None,
    ) -> ValidationResult:
        """Record a turn result for a persistent Frame without closing it.

        System frames such as ``system.root`` do not have ordinary Skill exit
        semantics.  ``submit_skill_result`` ends the current user turn, but the
        Frame remains RUNNING so future resume logic can continue from the same
        root working context.
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
        keep_recoverable_focus = _continuation_resolution(structured_output) == "ASK_CLARIFICATION"

        if result.ok:
            if not keep_recoverable_focus:
                frame.private_working_state.pop("continuation_state", None)
                frame.private_working_state.pop("interrupt_reason", None)
                frame.private_working_state.pop("last_error", None)
                frame.private_working_state.pop("last_task_id", None)
                frame.private_working_state.pop("recoverable", None)
                frame.private_working_state.pop("interrupted_at", None)
                _clear_recoverable_focus_fields(frame.private_working_state)
            turn_results = frame.private_working_state.setdefault("turn_results", [])
            turn_entry = {
                "summary": summary,
                "structured_output": structured_output,
                "artifact_refs": artifact_refs or [],
                "evidence_refs": evidence_refs or [],
                "submitted_at": datetime.now(timezone.utc).isoformat(),
            }
            turn_results.append(turn_entry)
            self._compact_persistent_frame_context(frame, turn_entry)
            if interruption_entry and not keep_recoverable_focus:
                _append_interruption_history(frame, interruption_entry)
        else:
            frame.output = None
            frame.result_summary = None

        self._save(frame)
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

        # Build promoted result
        promoted: dict[str, Any] = {
            "frame_id": frame.frame_id,
            "skill_id": frame.skill_id,
        }

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
        self._save(parent)

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

        if frame.parent_frame_id:
            parent = self._get_frame(frame.parent_frame_id)
            function_results = parent.private_working_state.setdefault("function_results", {})
            function_results[function_frame_id] = {
                "function_id": frame.input.get("function_id"),
                "version": frame.input.get("version"),
                "result": result,
                "completed_at": frame.ended_at,
            }
            self._save(parent)

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

        if frame.parent_frame_id:
            parent = self._get_frame(frame.parent_frame_id)
            parent.private_working_state["pending_function_call_frame_id"] = function_frame_id
            parent.private_working_state["pending_function_call"] = {
                "function_frame_id": function_frame_id,
                "function_id": frame.input.get("function_id"),
                "version": frame.input.get("version"),
                "argument_hash": frame.input.get("argument_hash"),
                "suspend_id": approval_request.get("suspend_id"),
            }
            self._save(parent)

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

    def record_recoverable_child_interruption(
        self,
        parent_frame_id: str,
        reason: str,
        error: str = "",
        task_id: str | None = None,
    ) -> str | None:
        """Mark the active child of a waiting parent as recoverable.

        The parent root is moved back to RUNNING so the next user turn can be
        interpreted by the root LLM. The interrupted child remains available
        as a recoverable candidate until root either resumes or shelves it.
        """
        parent = self._get_frame(parent_frame_id)
        child = self._find_active_child_frame(parent)
        if child is None:
            if parent.status == FrameStatus.WAITING_CHILD:
                self.resume_from_child(parent.frame_id)
            return None

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

        parent.private_working_state["pending_recoverable_child_frame_id"] = child.frame_id
        parent.private_working_state["pending_recoverable_child"] = {
            "frame_id": child.frame_id,
            "skill_id": child.skill_id,
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
        if child is None or child.status in TERMINAL_STATES:
            self._clear_recoverable_child_reference(parent.frame_id, child_frame_id)
            return None
        if child.parent_frame_id != parent.frame_id:
            raise IllegalStateTransition("recoverable child frame does not belong to parent")
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

    def context_summary_for_frame(self, frame_id: str) -> dict[str, Any] | None:
        """Return the nearest root context summary visible from a frame stack."""
        for frame in self.get_call_stack(frame_id):
            summary = frame.private_working_state.get("root_context_summary")
            if isinstance(summary, dict):
                return _safe_json_copy(summary)
        return None

    # -- Internal helpers ----------------------------------------------------

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
        return self.registry.get_manifest(frame.skill_id)

    def _save(self, frame: SkillFrameState) -> None:
        """Save frame to in-memory store and optionally persist to file journal."""
        self.store.save(frame)
        if self._journal:
            self._journal.save(frame)

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
            child = self._journal.load(task_id, child_frame_id)
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


def _frame_stack_snapshot(stack: list[SkillFrameState]) -> list[dict[str, Any]]:
    return [
        {
            "frame_id": frame.frame_id,
            "parent_frame_id": frame.parent_frame_id,
            "skill_id": frame.skill_id,
            "frame_kind": frame.frame_kind.value,
            "status": frame.status.value,
            "input": _safe_json_copy(frame.input),
        }
        for frame in stack
    ]


def _clear_recoverable_focus_fields(working_state: dict[str, Any]) -> None:
    for key in RECOVERABLE_FOCUS_KEYS:
        working_state.pop(key, None)


def _recoverable_focus_frame_ids(
    working_state: dict[str, Any],
    owner_frame_id: str,
) -> list[str]:
    stack = working_state.get("recoverable_focus_stack")
    frame_ids: list[str] = []
    if isinstance(stack, list):
        for entry in stack:
            if not isinstance(entry, dict):
                continue
            frame_id = entry.get("frame_id")
            if isinstance(frame_id, str) and frame_id and frame_id != owner_frame_id:
                frame_ids.append(frame_id)
    focus_frame_id = working_state.get("recoverable_focus_frame_id")
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


def _safe_json_copy(value: dict[str, Any]) -> dict[str, Any]:
    try:
        return json.loads(json.dumps(value, ensure_ascii=False, default=str))
    except Exception:
        return {"summary": str(value)}
