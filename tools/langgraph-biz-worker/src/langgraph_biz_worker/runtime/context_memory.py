"""BizWorker-owned runtime conversation memory.

The first backend for this memory is the persistent root frame's
``private_working_state``.  Callers should use this module instead of reading
or writing that frame field directly so the storage backend can move later.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Lock
from typing import Any

from ..models import SkillFrameState

RUNTIME_CONTEXT_MEMORY_KEY = "runtime_context_memory"
RUNTIME_VISIBLE_CONVERSATION_KEY = "_runtime_visible_conversation"

DEFAULT_LIMITS = {
    "maxVisibleMessages": 24,
    "maxPromptMessages": 12,
    "maxMessageChars": 1200,
}

_CONTEXT_LOCKS_GUARD = Lock()
_CONTEXT_LOCKS: dict[str, Lock] = {}


class ContextRuntimeBusy(RuntimeError):
    """Raised when Phase 1 detects an already running turn for the context."""


@dataclass
class ContextRuntimeMemory:
    """Bounded semantic runtime memory for one BizWorker context."""

    context_id: str | None = None
    schema_version: int = 1
    revision: int = 0
    updated_at: str = ""
    compacted_summary: dict[str, Any] | None = None
    pinned_head_messages: list[dict[str, Any]] = field(default_factory=list)
    visible_messages: list[dict[str, Any]] = field(default_factory=list)
    pending_turn: dict[str, Any] | None = None
    pending_user_inputs: list[dict[str, Any]] = field(default_factory=list)
    limits: dict[str, Any] = field(default_factory=lambda: dict(DEFAULT_LIMITS))
    loop_status: str = "IDLE"
    running_task_id: str | None = None
    running_frame_id: str | None = None

    @classmethod
    def from_dict(cls, value: dict[str, Any] | None, *, context_id: str | None = None) -> "ContextRuntimeMemory":
        if not isinstance(value, dict):
            return cls(context_id=context_id)
        limits = dict(DEFAULT_LIMITS)
        if isinstance(value.get("limits"), dict):
            limits.update(value["limits"])
        memory = cls(
            context_id=_str_or_none(value.get("contextId")) or context_id,
            schema_version=_int_or_default(value.get("schemaVersion"), 1),
            revision=_int_or_default(value.get("revision"), 0),
            updated_at=_str_or_default(value.get("updatedAt")),
            compacted_summary=value.get("compactedSummary") if isinstance(value.get("compactedSummary"), dict) else None,
            pinned_head_messages=_sanitize_messages(value.get("pinnedHeadMessages")),
            visible_messages=_sanitize_messages(value.get("visibleMessages")),
            pending_turn=value.get("pendingTurn") if isinstance(value.get("pendingTurn"), dict) else None,
            pending_user_inputs=_sanitize_messages(value.get("pendingUserInputs")),
            limits=limits,
            loop_status=_str_or_default(value.get("loopStatus"), "IDLE") or "IDLE",
            running_task_id=_str_or_none(value.get("runningTaskId")),
            running_frame_id=_str_or_none(value.get("runningFrameId")),
        )
        if not memory.context_id:
            memory.context_id = context_id
        return memory

    @classmethod
    def load_from_root_frame(cls, frame: SkillFrameState) -> "ContextRuntimeMemory":
        state = frame.private_working_state if isinstance(frame.private_working_state, dict) else {}
        return cls.from_dict(
            state.get(RUNTIME_CONTEXT_MEMORY_KEY),
            context_id=frame.conversation_id or frame.session_id,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "contextId": self.context_id,
            "revision": self.revision,
            "updatedAt": self.updated_at,
            "compactedSummary": self.compacted_summary,
            "pinnedHeadMessages": list(self.pinned_head_messages),
            "visibleMessages": list(self.visible_messages),
            "pendingTurn": self.pending_turn,
            "pendingUserInputs": list(self.pending_user_inputs),
            "limits": dict(self.limits),
            "loopStatus": self.loop_status,
            "runningTaskId": self.running_task_id,
            "runningFrameId": self.running_frame_id,
        }

    def save_to_root_frame(self, frame: SkillFrameState) -> None:
        if not self.context_id:
            self.context_id = frame.conversation_id or frame.session_id
        frame.private_working_state[RUNTIME_CONTEXT_MEMORY_KEY] = self.to_dict()

    def bootstrap_from_external_recent_conversation(
        self,
        history: list[dict[str, Any]] | None,
        *,
        task_id: str,
        root_frame_id: str,
        now: str | None = None,
    ) -> bool:
        """Import external recent conversation once for migration compatibility."""
        if self.revision > 0 or self.visible_messages or self.pending_turn:
            return False
        messages = _external_recent_to_messages(
            history,
            task_id=task_id,
            root_frame_id=root_frame_id,
            created_at=now or _now(),
        )
        if not messages:
            return False
        self.visible_messages.extend(messages)
        self.revision = 1
        self.updated_at = now or _now()
        return True

    def build_prompt_view(self) -> list[dict[str, Any]]:
        max_prompt_messages = _int_or_default(
            self.limits.get("maxPromptMessages"),
            DEFAULT_LIMITS["maxPromptMessages"],
        )
        messages = self.pinned_head_messages + self.visible_messages
        return [
            {
                "role": item["role"],
                "content": item["content"],
                "messageId": item.get("messageId"),
                "taskId": item.get("taskId"),
                "metadata": item.get("metadata") if isinstance(item.get("metadata"), dict) else {},
            }
            for item in messages[-max_prompt_messages:]
            if item.get("role") in {"user", "assistant"} and item.get("content")
        ]

    def begin_turn(
        self,
        *,
        task_id: str,
        root_frame_id: str,
        user_message: str,
        now: str | None = None,
    ) -> None:
        """Mark a user turn as running before the root LLM loop starts."""
        timestamp = now or _now()
        running_task_id = self.running_task_id or _pending_task_id(self.pending_turn)
        if self.loop_status == "RUNNING" and running_task_id and running_task_id != task_id:
            raise ContextRuntimeBusy(
                f"context runtime is busy: runningTaskId={running_task_id}"
            )
        if self.pending_turn and _pending_task_id(self.pending_turn) != task_id:
            raise ContextRuntimeBusy(
                f"context runtime is busy: pendingTaskId={_pending_task_id(self.pending_turn)}"
            )
        if self.pending_turn and _pending_task_id(self.pending_turn) == task_id:
            self.loop_status = "RUNNING"
            self.running_task_id = task_id
            self.running_frame_id = root_frame_id
            self.updated_at = timestamp
            return
        self.pending_turn = {
            "taskId": task_id,
            "rootFrameId": root_frame_id,
            "startedAt": timestamp,
            "status": "RUNNING",
            "userMessage": _new_message(
                role="user",
                content=user_message,
                task_id=task_id,
                root_frame_id=root_frame_id,
                created_at=timestamp,
                source="user",
            ),
        }
        self.loop_status = "RUNNING"
        self.running_task_id = task_id
        self.running_frame_id = root_frame_id
        self.updated_at = timestamp

    def commit_turn(
        self,
        *,
        assistant_message: str,
        metadata: dict[str, Any] | None = None,
        now: str | None = None,
    ) -> bool:
        """Commit pending user message plus final assistant projection."""
        if not self.pending_turn:
            self._clear_running(now=now)
            return False
        user_message = self.pending_turn.get("userMessage")
        if not isinstance(user_message, dict):
            self._clear_running(now=now)
            return False
        content = _clean_content(assistant_message, self._max_message_chars())
        if not content:
            self._clear_running(now=now)
            return False
        timestamp = now or _now()
        task_id = _str_or_default(user_message.get("taskId"))
        root_frame_id = _str_or_default(user_message.get("rootFrameId"))
        assistant = _new_message(
            role="assistant",
            content=content,
            task_id=task_id,
            root_frame_id=root_frame_id,
            created_at=timestamp,
            source="root_result",
            metadata=metadata,
        )
        self.visible_messages.extend([user_message, assistant])
        self._compact_visible_messages()
        self.pending_turn = None
        self.revision += 1
        self._clear_running(now=timestamp)
        return True

    def abandon_turn(self, *, status: str = "ABANDONED", now: str | None = None) -> None:
        if isinstance(self.pending_turn, dict):
            self.pending_turn["status"] = status
            self.pending_turn["endedAt"] = now or _now()
        self.pending_turn = None
        self._clear_running(now=now)

    def _clear_running(self, *, now: str | None = None) -> None:
        self.loop_status = "IDLE"
        self.running_task_id = None
        self.running_frame_id = None
        self.updated_at = now or _now()

    def _compact_visible_messages(self) -> None:
        max_visible = _int_or_default(
            self.limits.get("maxVisibleMessages"),
            DEFAULT_LIMITS["maxVisibleMessages"],
        )
        if len(self.visible_messages) > max_visible:
            del self.visible_messages[:-max_visible]

    def _max_message_chars(self) -> int:
        return _int_or_default(
            self.limits.get("maxMessageChars"),
            DEFAULT_LIMITS["maxMessageChars"],
        )


def context_execution_lock(context_id: str) -> Lock:
    """Return the in-process execution lock for a context id."""
    with _CONTEXT_LOCKS_GUARD:
        lock = _CONTEXT_LOCKS.get(context_id)
        if lock is None:
            lock = Lock()
            _CONTEXT_LOCKS[context_id] = lock
        return lock


def load_from_root_frame(frame: SkillFrameState) -> ContextRuntimeMemory:
    return ContextRuntimeMemory.load_from_root_frame(frame)


def save_to_root_frame(frame: SkillFrameState, memory: ContextRuntimeMemory) -> None:
    memory.save_to_root_frame(frame)


def assistant_visible_content(
    structured_output: dict[str, Any] | None,
    summary: str | None,
) -> str:
    """Derive the assistant semantic message that should enter runtime memory."""
    if isinstance(structured_output, dict):
        for key in (
            "message",
            "userMessage",
            "user_message",
            "finalResponse",
            "final_response",
            "answer",
        ):
            value = structured_output.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return (summary or "").strip()


def _external_recent_to_messages(
    history: list[dict[str, Any]] | None,
    *,
    task_id: str,
    root_frame_id: str,
    created_at: str,
) -> list[dict[str, Any]]:
    if not isinstance(history, list):
        return []
    messages: list[dict[str, Any]] = []
    for index, item in enumerate(history[-DEFAULT_LIMITS["maxPromptMessages"]:]):
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip().lower()
        if role not in {"user", "assistant"}:
            continue
        content = _clean_content(item.get("content"), DEFAULT_LIMITS["maxMessageChars"])
        if not content:
            continue
        messages.append(_new_message(
            role=role,
            content=content,
            task_id=task_id,
            root_frame_id=root_frame_id,
            created_at=created_at,
            source="external_compat_bootstrap",
            suffix=f"bootstrap_{index}",
        ))
    return messages


def _new_message(
    *,
    role: str,
    content: str,
    task_id: str,
    root_frame_id: str,
    created_at: str,
    source: str,
    metadata: dict[str, Any] | None = None,
    suffix: str | None = None,
) -> dict[str, Any]:
    safe_task = _safe_id_part(task_id)
    safe_role = _safe_id_part(role)
    suffix_part = suffix or _safe_id_part(created_at)
    message_metadata = {"source": source}
    if metadata:
        message_metadata.update(metadata)
    return {
        "messageId": f"rtm_{safe_task}_{safe_role}_{suffix_part}",
        "role": role,
        "content": content,
        "taskId": task_id,
        "rootFrameId": root_frame_id,
        "createdAt": created_at,
        "metadata": message_metadata,
    }


def _sanitize_messages(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    messages: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip().lower()
        if role not in {"user", "assistant"}:
            continue
        content = _clean_content(item.get("content"), DEFAULT_LIMITS["maxMessageChars"])
        if not content:
            continue
        normalized = dict(item)
        normalized["role"] = role
        normalized["content"] = content
        messages.append(normalized)
    return messages


def _clean_content(value: Any, max_chars: int) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    if not text:
        return ""
    return text[:max_chars]


def _pending_task_id(pending_turn: dict[str, Any] | None) -> str | None:
    if not isinstance(pending_turn, dict):
        return None
    task_id = pending_turn.get("taskId")
    return task_id if isinstance(task_id, str) and task_id else None


def _str_or_none(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _str_or_default(value: Any, default: str = "") -> str:
    return value if isinstance(value, str) else default


def _int_or_default(value: Any, default: int) -> int:
    if isinstance(value, int):
        return value
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _safe_id_part(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", value)
    return safe[:64] or "msg"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
