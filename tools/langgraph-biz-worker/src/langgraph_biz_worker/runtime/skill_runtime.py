"""Skill Runtime — single-point entry for all Frame state changes.

All Frame lifecycle operations (create, transition, complete, close) MUST go
through this class.  Neither the model nor individual graph nodes are allowed
to write final status directly.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from ..models import (
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
                self.store.save(parent)

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
