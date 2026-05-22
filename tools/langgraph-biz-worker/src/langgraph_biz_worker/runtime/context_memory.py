"""BizWorker-owned runtime conversation memory.

The first backend for this memory is the persistent root frame's
``private_working_state``.  Callers should use this module instead of reading
or writing that frame field directly so the storage backend can move later.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import Lock
from typing import Any, Callable

from ..models import SkillFrameState

RUNTIME_CONTEXT_MEMORY_KEY = "runtime_context_memory"
RUNTIME_VISIBLE_CONVERSATION_KEY = "_runtime_visible_conversation"
PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY = "_pending_root_turn_protocol_messages"

DEFAULT_LIMITS = {
    "maxVisibleMessages": 24,
    "maxPromptMessages": 12,
    "maxPromptChars": 48000,
    "maxMessageChars": 1200,
    "maxToolResultChars": 48000,
    "maxToolCallArgsChars": 12000,
    "maxVisibleChars": 12000,
    "headTurnCount": 2,
    "tailTurnCount": 6,
    "maxSummaryChars": 2400,
}

_CONTEXT_LOCKS_GUARD = Lock()
_CONTEXT_LOCKS: dict[str, Lock] = {}
_CONTEXT_STATE_LOCKS_GUARD = Lock()
_CONTEXT_STATE_LOCKS: dict[str, Lock] = {}


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
            pinned_head_messages=_sanitize_messages(value.get("pinnedHeadMessages"), limits=limits),
            visible_messages=_sanitize_messages(value.get("visibleMessages"), limits=limits),
            pending_turn=value.get("pendingTurn") if isinstance(value.get("pendingTurn"), dict) else None,
            pending_user_inputs=_sanitize_messages(value.get("pendingUserInputs"), limits=limits),
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
        max_prompt_chars = _int_or_default(
            self.limits.get("maxPromptChars"),
            DEFAULT_LIMITS["maxPromptChars"],
        )
        head_messages = [
            *self.pinned_head_messages,
            *self._compacted_summary_prompt_messages(),
        ]
        head_prompt = [
            _prompt_message_copy(item)
            for item in head_messages
            if _message_visible_in_prompt(item)
        ]
        visible_prompt = [
            _prompt_message_copy(item)
            for item in self.visible_messages
            if _message_visible_in_prompt(item)
        ]
        tail_prompt = _turn_aware_prompt_tail(
            visible_prompt,
            max_messages=max(1, max_prompt_messages - len(head_prompt)),
            max_chars=max(1, max_prompt_chars - _messages_total_chars(head_prompt)),
        )
        prompt_source = [*head_prompt, *tail_prompt]
        return _ensure_valid_tool_protocol_window([
            item
            for item in prompt_source
            if _message_visible_in_prompt(item)
        ])

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
            "queuedUserMessages": [],
        }
        self.loop_status = "RUNNING"
        self.running_task_id = task_id
        self.running_frame_id = root_frame_id
        self.updated_at = timestamp

    def enqueue_user_input(
        self,
        *,
        task_id: str,
        root_frame_id: str,
        user_message: str,
        now: str | None = None,
    ) -> dict[str, Any] | None:
        """Queue a user message for the currently running loop."""
        content = _clean_content(user_message, self._max_message_chars())
        if not content:
            return None
        timestamp = now or _now()
        queued = _new_message(
            role="user",
            content=content,
            task_id=task_id,
            root_frame_id=root_frame_id,
            created_at=timestamp,
            source="queued_user_input",
            metadata={
                "queuedTaskId": task_id,
                "queueStatus": "QUEUED",
            },
            suffix=f"queued_{len(self.pending_user_inputs) + 1}_{_safe_id_part(timestamp)}",
        )
        self.pending_user_inputs.append(queued)
        self.updated_at = timestamp
        return queued

    def drain_pending_user_inputs(
        self,
        *,
        limit: int = 8,
        now: str | None = None,
    ) -> list[dict[str, Any]]:
        """Move queued user messages into the in-flight turn in FIFO order."""
        if not self.pending_user_inputs:
            return []
        count = max(0, limit)
        if count == 0:
            return []
        drained = self.pending_user_inputs[:count]
        del self.pending_user_inputs[:count]
        timestamp = now or _now()
        if isinstance(self.pending_turn, dict):
            queued_messages = self.pending_turn.setdefault("queuedUserMessages", [])
            if not isinstance(queued_messages, list):
                queued_messages = []
                self.pending_turn["queuedUserMessages"] = queued_messages
            for item in drained:
                metadata = item.setdefault("metadata", {})
                if isinstance(metadata, dict):
                    metadata["queueStatus"] = "IN_FLIGHT"
                    metadata["inFlightAt"] = timestamp
                queued_messages.append(item)
        self.updated_at = timestamp
        return drained

    def commit_turn(
        self,
        *,
        assistant_message: str,
        metadata: dict[str, Any] | None = None,
        protocol_messages: list[dict[str, Any]] | None = None,
        now: str | None = None,
        summarizer: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
    ) -> bool:
        """Commit the completed root turn.

        ``protocol_messages`` is the exact root-visible provider protocol for
        this turn: user messages, assistant tool calls, tool results, and the
        final assistant projection when the model produced one.  If the
        protocol is missing or invalid, commit falls back to the semantic
        ``user -> assistant`` projection so runtime memory never stores an
        invalid provider sequence.
        """
        if not self.pending_turn:
            self._clear_running(now=now)
            return False
        user_message = self.pending_turn.get("userMessage")
        if not isinstance(user_message, dict):
            self.abandon_turn(status="COMMIT_REJECTED", now=now)
            return False
        queued_user_messages = [
            item for item in self.pending_turn.get("queuedUserMessages", [])
            if isinstance(item, dict) and item.get("role") == "user" and item.get("content")
        ]
        content = _clean_content(assistant_message, self._max_message_chars())
        if not content:
            self.abandon_turn(status="COMMIT_REJECTED", now=now)
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
        turn_messages = self._commit_protocol_messages(
            protocol_messages,
            pending_user_message=user_message,
            fallback_user_messages=[user_message, *queued_user_messages],
            final_assistant=assistant,
            metadata=metadata,
        )
        self.visible_messages.extend(turn_messages)
        self._compact_visible_messages(summarizer=summarizer)
        self.pending_turn = None
        self.revision += 1
        self._clear_running(now=timestamp)
        return True

    def _commit_protocol_messages(
        self,
        protocol_messages: list[dict[str, Any]] | None,
        *,
        pending_user_message: dict[str, Any],
        fallback_user_messages: list[dict[str, Any]],
        final_assistant: dict[str, Any],
        metadata: dict[str, Any] | None,
    ) -> list[dict[str, Any]]:
        protocol_turn = _sanitize_messages(protocol_messages, limits=self.limits)
        if not (
            protocol_turn
            and _has_valid_tool_protocol_messages(protocol_turn)
            and _protocol_contains_user_message(protocol_turn, pending_user_message)
        ):
            protocol_turn = list(fallback_user_messages)

        if protocol_turn and _is_final_assistant_message(protocol_turn[-1]):
            merged = dict(protocol_turn[-1])
            merged_metadata = merged.get("metadata") if isinstance(merged.get("metadata"), dict) else {}
            merged["metadata"] = {
                **merged_metadata,
                "projection": metadata or {},
            }
            protocol_turn[-1] = merged
            return protocol_turn
        return [*protocol_turn, final_assistant]

    def abandon_turn(self, *, status: str = "ABANDONED", now: str | None = None) -> None:
        if isinstance(self.pending_turn, dict):
            self.pending_turn["status"] = status
            self.pending_turn["endedAt"] = now or _now()
        self.pending_turn = None
        self._clear_running(now=now)

    def mark_finalizing(self, *, now: str | None = None) -> bool:
        if not isinstance(self.pending_turn, dict):
            return False
        timestamp = now or _now()
        self.pending_turn["status"] = "FINALIZING"
        self.loop_status = "FINALIZING"
        self.updated_at = timestamp
        return True

    def mark_running(self, *, now: str | None = None) -> bool:
        if not isinstance(self.pending_turn, dict):
            return False
        timestamp = now or _now()
        self.pending_turn["status"] = "RUNNING"
        self.loop_status = "RUNNING"
        self.running_task_id = _pending_task_id(self.pending_turn)
        self.running_frame_id = _str_or_none(self.pending_turn.get("rootFrameId"))
        self.updated_at = timestamp
        return True

    def _clear_running(self, *, now: str | None = None) -> None:
        self.loop_status = "IDLE"
        self.running_task_id = None
        self.running_frame_id = None
        self.updated_at = now or _now()

    def _compact_visible_messages(
        self,
        *,
        summarizer: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
    ) -> None:
        max_visible = _int_or_default(
            self.limits.get("maxVisibleMessages"),
            DEFAULT_LIMITS["maxVisibleMessages"],
        )
        max_visible_chars = _int_or_default(
            self.limits.get("maxVisibleChars"),
            DEFAULT_LIMITS["maxVisibleChars"],
        )
        if (
            len(self.visible_messages) <= max_visible
            and _messages_total_chars(self.visible_messages) <= max_visible_chars
        ):
            return

        head_count = max(0, _int_or_default(
            self.limits.get("headTurnCount"),
            DEFAULT_LIMITS["headTurnCount"],
        )) * 2
        tail_count = max(1, _int_or_default(
            self.limits.get("tailTurnCount"),
            DEFAULT_LIMITS["tailTurnCount"],
        )) * 2
        max_summary_chars = _int_or_default(
            self.limits.get("maxSummaryChars"),
            DEFAULT_LIMITS["maxSummaryChars"],
        )

        messages = list(self.visible_messages)
        if self.pinned_head_messages:
            head: list[dict[str, Any]] = []
            middle = messages[:-tail_count] if len(messages) > tail_count else []
        else:
            head = _ensure_valid_tool_protocol_window(messages[:head_count])
            middle_end = max(head_count, len(messages) - tail_count)
            middle = messages[head_count:middle_end]
        tail = _ensure_valid_tool_protocol_window(
            messages[-tail_count:] if len(messages) > tail_count else list(messages)
        )

        if not middle:
            if len(messages) <= max_visible:
                return
            middle = messages[:-tail_count]
            head = []
            tail = _ensure_valid_tool_protocol_window(messages[-tail_count:])
        if not middle:
            return

        previous_summary = self.compacted_summary if isinstance(self.compacted_summary, dict) else None
        summarizer_messages = _summary_input_messages(
            middle,
            max_chars=self._max_message_chars(),
        )
        summary_input = {
            "previousSummary": previous_summary,
            "messages": summarizer_messages,
            "limits": dict(self.limits),
        }
        summary = None
        if summarizer is not None:
            try:
                candidate = summarizer(summary_input)
                if isinstance(candidate, dict):
                    summary = _normalize_compacted_summary(
                        candidate,
                        covered_messages=summarizer_messages,
                        previous_summary=previous_summary,
                        max_chars=max_summary_chars,
                        quality="llm",
                    )
            except Exception:
                summary = None
        if summary is None:
            summary = _deterministic_compacted_summary(
                previous_summary,
                middle,
                max_chars=max_summary_chars,
            )

        if head:
            self.pinned_head_messages.extend(head)
        self.compacted_summary = summary
        self.visible_messages = tail

    def _max_message_chars(self) -> int:
        return _int_or_default(
            self.limits.get("maxMessageChars"),
            DEFAULT_LIMITS["maxMessageChars"],
        )

    def _compacted_summary_prompt_messages(self) -> list[dict[str, Any]]:
        if not isinstance(self.compacted_summary, dict):
            return []
        content = _compacted_summary_prompt_content(
            self.compacted_summary,
            max_chars=_int_or_default(
                self.limits.get("maxSummaryChars"),
                DEFAULT_LIMITS["maxSummaryChars"],
            ),
        )
        if not content:
            return []
        return [{
            "messageId": "rtm_compacted_summary",
            "role": "assistant",
            "content": content,
            "taskId": "",
            "rootFrameId": "",
            "createdAt": self.compacted_summary.get("compactedAt") or self.updated_at,
            "metadata": {
                "source": "compaction",
                "summaryQuality": self.compacted_summary.get("summaryQuality") or "unknown",
            },
        }]


def context_execution_lock(context_id: str) -> Lock:
    """Return the in-process execution lock for a context id."""
    with _CONTEXT_LOCKS_GUARD:
        lock = _CONTEXT_LOCKS.get(context_id)
        if lock is None:
            lock = Lock()
            _CONTEXT_LOCKS[context_id] = lock
        return lock


def context_state_lock(context_id: str) -> Lock:
    """Return the short critical-section lock for context memory state writes."""
    with _CONTEXT_STATE_LOCKS_GUARD:
        lock = _CONTEXT_STATE_LOCKS.get(context_id)
        if lock is None:
            lock = Lock()
            _CONTEXT_STATE_LOCKS[context_id] = lock
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


def runtime_protocol_messages_from_langchain(
    messages: list[Any],
    *,
    task_id: str,
    root_frame_id: str,
    now: str | None = None,
    max_chars: int | None = None,
    max_tool_result_chars: int | None = None,
    max_tool_args_chars: int | None = None,
) -> list[dict[str, Any]]:
    """Serialize root-visible LangChain messages for bounded runtime replay."""
    if not isinstance(messages, list):
        return []
    timestamp = now or _now()
    limit = _int_or_default(max_chars, DEFAULT_LIMITS["maxMessageChars"])
    tool_result_limit = _int_or_default(
        max_tool_result_chars,
        DEFAULT_LIMITS["maxToolResultChars"],
    )
    tool_args_limit = _int_or_default(
        max_tool_args_chars,
        DEFAULT_LIMITS["maxToolCallArgsChars"],
    )
    protocol: list[dict[str, Any]] = []
    for index, message in enumerate(messages):
        role = _langchain_message_role(message)
        if role == "system" or not role:
            continue
        content_limit = tool_result_limit if role == "tool" else limit
        content = _message_content_text(getattr(message, "content", ""), content_limit)
        suffix = f"protocol_{index}_{_safe_id_part(timestamp)}"
        if role == "user":
            if not content:
                continue
            protocol.append(_new_message(
                role="user",
                content=content,
                task_id=task_id,
                root_frame_id=root_frame_id,
                created_at=timestamp,
                source="root_protocol",
                suffix=suffix,
            ))
            continue
        if role == "assistant":
            tool_calls = _normalize_runtime_tool_calls(
                getattr(message, "tool_calls", None),
                max_args_chars=tool_args_limit,
            )
            if not content and not tool_calls:
                continue
            item = _new_message(
                role="assistant",
                content=content,
                task_id=task_id,
                root_frame_id=root_frame_id,
                created_at=timestamp,
                source="root_protocol",
                suffix=suffix,
            )
            if tool_calls:
                item["toolCalls"] = tool_calls
            protocol.append(item)
            continue
        if role == "tool":
            tool_call_id = _str_or_none(getattr(message, "tool_call_id", None))
            if not tool_call_id:
                continue
            item = _new_message(
                role="tool",
                content=content,
                task_id=task_id,
                root_frame_id=root_frame_id,
                created_at=timestamp,
                source="root_protocol",
                suffix=suffix,
                metadata={"toolCallId": tool_call_id},
            )
            item["toolCallId"] = tool_call_id
            protocol.append(item)
    return _ensure_valid_tool_protocol_window(protocol)


def _turn_aware_prompt_tail(
    messages: list[dict[str, Any]],
    *,
    max_messages: int,
    max_chars: int,
) -> list[dict[str, Any]]:
    if not messages:
        return []
    groups = _semantic_turn_groups(_ensure_valid_tool_protocol_window(messages))
    if not groups:
        return []
    selected: list[list[dict[str, Any]]] = []
    selected_count = 0
    selected_chars = 0
    for group in reversed(groups):
        group_count = len(group)
        group_chars = _messages_total_chars(group)
        if (
            selected
            and selected_count + group_count > max_messages
        ):
            break
        if (
            selected
            and selected_chars + group_chars > max_chars
        ):
            break
        selected.insert(0, group)
        selected_count += group_count
        selected_chars += group_chars
    if not selected:
        selected = [groups[-1]]
    flattened = [item for group in selected for item in group]
    return _ensure_valid_tool_protocol_window(flattened)


def _semantic_turn_groups(messages: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    groups: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for item in messages:
        role = str(item.get("role") or "").strip().lower()
        is_summary = item.get("messageId") == "rtm_compacted_summary"
        if role == "user" or is_summary:
            if current:
                groups.append(current)
            current = [item]
            continue
        if not current:
            current = [item]
        else:
            current.append(item)
    if current:
        groups.append(current)
    return groups


def _messages_total_chars(messages: list[dict[str, Any]]) -> int:
    return sum(len(item.get("content") or "") for item in messages if isinstance(item, dict))


def _normalize_compacted_summary(
    candidate: dict[str, Any],
    *,
    covered_messages: list[dict[str, Any]],
    previous_summary: dict[str, Any] | None,
    max_chars: int,
    quality: str,
) -> dict[str, Any]:
    summary = _deterministic_compacted_summary(
        previous_summary,
        covered_messages,
        max_chars=max_chars,
    )
    for key in (
        "durableUserIntent",
        "decisionsAndConstraints",
        "businessEntities",
        "completedWork",
        "openQuestions",
        "pendingActions",
        "errorsAndRecovery",
    ):
        value = candidate.get(key)
        if isinstance(value, str):
            summary[key] = _truncate_text(_redact_sensitive_text(value), max_chars)
        elif isinstance(value, list):
            summary[key] = _normalize_summary_list(value, max_chars=max_chars)
    summary["summaryQuality"] = _str_or_default(candidate.get("summaryQuality"), quality) or quality
    summary["coveredMessageIds"] = _merged_strings(
        _summary_list(previous_summary, "coveredMessageIds"),
        _summary_list(candidate, "coveredMessageIds"),
        _message_ids(covered_messages),
    )
    summary["reportRefs"] = _merged_strings(
        _summary_list(previous_summary, "reportRefs"),
        _summary_list(candidate, "reportRefs"),
        _report_refs_from_messages(covered_messages),
    )
    summary["compactedAt"] = _str_or_default(candidate.get("compactedAt"), _now())
    return summary


def _summary_input_messages(messages: list[dict[str, Any]], *, max_chars: int) -> list[dict[str, Any]]:
    sanitized: list[dict[str, Any]] = []
    for item in messages:
        if not isinstance(item, dict):
            continue
        copy = dict(item)
        content = _str_or_default(copy.get("content"))
        if content:
            copy["content"] = _truncate_text(_redact_sensitive_text(content), max_chars)
        sanitized.append(copy)
    return sanitized


def _deterministic_compacted_summary(
    previous_summary: dict[str, Any] | None,
    messages: list[dict[str, Any]],
    *,
    max_chars: int,
) -> dict[str, Any]:
    previous_intent = ""
    if isinstance(previous_summary, dict):
        previous_intent = _str_or_default(previous_summary.get("durableUserIntent"))
    message_summary = _summary_text_from_messages(messages, max_chars=max_chars)
    durable_parts = [item for item in (previous_intent, message_summary) if item]
    return {
        "summaryQuality": "fallback",
        "durableUserIntent": _truncate_text("\n".join(durable_parts), max_chars),
        "decisionsAndConstraints": _summary_list(previous_summary, "decisionsAndConstraints"),
        "businessEntities": _summary_list(previous_summary, "businessEntities"),
        "completedWork": _summary_list(previous_summary, "completedWork"),
        "openQuestions": _summary_list(previous_summary, "openQuestions"),
        "pendingActions": _summary_list(previous_summary, "pendingActions"),
        "errorsAndRecovery": _summary_list(previous_summary, "errorsAndRecovery"),
        "reportRefs": _merged_strings(
            _summary_list(previous_summary, "reportRefs"),
            _report_refs_from_messages(messages),
        ),
        "coveredMessageIds": _merged_strings(
            _summary_list(previous_summary, "coveredMessageIds"),
            _message_ids(messages),
        ),
        "compactedAt": _now(),
    }


def _compacted_summary_prompt_content(summary: dict[str, Any], *, max_chars: int) -> str:
    sections: list[str] = ["Runtime compacted conversation summary:"]
    durable_intent = _str_or_default(summary.get("durableUserIntent"))
    if durable_intent:
        sections.append(f"Durable intent: {durable_intent}")
    for label, key in (
        ("Decisions/constraints", "decisionsAndConstraints"),
        ("Business entities", "businessEntities"),
        ("Completed work", "completedWork"),
        ("Open questions", "openQuestions"),
        ("Pending actions", "pendingActions"),
        ("Errors/recovery", "errorsAndRecovery"),
        ("Report refs", "reportRefs"),
    ):
        values = _summary_list(summary, key)
        if values:
            sections.append(f"{label}: " + "; ".join(values))
    return _truncate_text(_redact_sensitive_text("\n".join(sections)), max_chars)


def _summary_text_from_messages(messages: list[dict[str, Any]], *, max_chars: int) -> str:
    lines: list[str] = []
    for item in messages:
        if not isinstance(item, dict):
            continue
        role = _str_or_default(item.get("role"), "message")
        content = _str_or_default(item.get("content"))
        if not content:
            continue
        lines.append(f"{role}: {_redact_sensitive_text(content)}")
    return _truncate_text("\n".join(lines), max_chars)


def _normalize_summary_list(values: list[Any], *, max_chars: int) -> list[str]:
    normalized: list[str] = []
    for value in values:
        if not isinstance(value, str):
            continue
        text = _truncate_text(_redact_sensitive_text(value.strip()), max_chars)
        if text and text not in normalized:
            normalized.append(text)
    return normalized


def _summary_list(summary: dict[str, Any] | None, key: str) -> list[str]:
    if not isinstance(summary, dict):
        return []
    value = summary.get(key)
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str) and item]


def _message_ids(messages: list[dict[str, Any]]) -> list[str]:
    return [
        item["messageId"]
        for item in messages
        if isinstance(item, dict) and isinstance(item.get("messageId"), str) and item["messageId"]
    ]


def _report_refs_from_messages(messages: list[dict[str, Any]]) -> list[str]:
    refs: list[str] = []
    for item in messages:
        metadata = item.get("metadata") if isinstance(item, dict) else None
        if not isinstance(metadata, dict):
            continue
        value = metadata.get("reportRef")
        if isinstance(value, str) and value and value not in refs:
            refs.append(value)
    return refs


def _merged_strings(*groups: list[str]) -> list[str]:
    merged: list[str] = []
    for group in groups:
        for item in group:
            if isinstance(item, str) and item and item not in merged:
                merged.append(item)
    return merged


_SENSITIVE_TEXT_KEYS = (
    "api_key",
    "apikey",
    "access_key",
    "access_token",
    "accesstoken",
    "token",
    "password",
    "secret",
    "signature",
    "credential",
)


def _redact_sensitive_text(text: str) -> str:
    redacted = _redact_json_text(text)
    quoted_key_pattern = "|".join(re.escape(key) for key in _SENSITIVE_TEXT_KEYS)
    redacted = re.sub(
        rf"(?i)([\"'](?:{quoted_key_pattern})[\"']\s*:\s*)([\"'])(.*?)(\2)",
        lambda match: f"{match.group(1)}{match.group(2)}[REDACTED]{match.group(2)}",
        redacted,
    )
    redacted = re.sub(
        r"(?i)\b(api[_-]?key|apikey|access[_-]?token|accessToken|token|password|secret|signature|credential)\s*=\s*([^\s&;,]+)",
        lambda match: f"{match.group(1)}=[REDACTED]",
        redacted,
    )
    redacted = re.sub(
        r"(?i)\b(api[_-]?key|apikey|access[_-]?token|accessToken|token|password|secret|signature|credential)\s*:\s*([^\s,;}]+)",
        lambda match: f"{match.group(1)}: [REDACTED]",
        redacted,
    )
    return re.sub(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]", redacted)


def _redact_json_text(text: str) -> str:
    stripped = text.strip()
    if not stripped or stripped[0] not in "{[":
        return text
    try:
        value = json.loads(stripped)
    except Exception:
        return text
    return json.dumps(_redact_json_value(value), ensure_ascii=False)


def _redact_json_value(value: Any, key_hint: str | None = None) -> Any:
    if key_hint and _is_sensitive_text_key(key_hint):
        return "[REDACTED]"
    if isinstance(value, dict):
        return {str(key): _redact_json_value(item, str(key)) for key, item in value.items()}
    if isinstance(value, list):
        return [_redact_json_value(item) for item in value]
    return value


def _is_sensitive_text_key(key: str) -> bool:
    normalized = key.lower().replace("-", "_")
    return normalized in _SENSITIVE_TEXT_KEYS or any(part in normalized for part in _SENSITIVE_TEXT_KEYS)


def _truncate_text(text: str, max_chars: int) -> str:
    if max_chars <= 0:
        return ""
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip()


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


def _prompt_message_copy(item: dict[str, Any]) -> dict[str, Any]:
    copied = {
        "role": item["role"],
        "content": item.get("content", ""),
        "messageId": item.get("messageId"),
        "taskId": item.get("taskId"),
        "metadata": item.get("metadata") if isinstance(item.get("metadata"), dict) else {},
    }
    tool_calls = item.get("toolCalls")
    if isinstance(tool_calls, list) and tool_calls:
        copied["toolCalls"] = _normalize_runtime_tool_calls(tool_calls)
    tool_call_id = _str_or_none(item.get("toolCallId"))
    if tool_call_id:
        copied["toolCallId"] = tool_call_id
    return copied


def _message_visible_in_prompt(item: dict[str, Any]) -> bool:
    if not isinstance(item, dict):
        return False
    role = str(item.get("role") or "").strip().lower()
    if role == "assistant":
        return bool(item.get("content") or item.get("toolCalls"))
    if role == "tool":
        return bool(item.get("toolCallId"))
    return role == "user" and bool(item.get("content"))


def _sanitize_messages(value: Any, *, limits: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    limit_config = limits if isinstance(limits, dict) else DEFAULT_LIMITS
    max_message_chars = _int_or_default(
        limit_config.get("maxMessageChars"),
        DEFAULT_LIMITS["maxMessageChars"],
    )
    max_tool_result_chars = _int_or_default(
        limit_config.get("maxToolResultChars"),
        DEFAULT_LIMITS["maxToolResultChars"],
    )
    max_tool_args_chars = _int_or_default(
        limit_config.get("maxToolCallArgsChars"),
        DEFAULT_LIMITS["maxToolCallArgsChars"],
    )
    messages: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "").strip().lower()
        if role not in {"user", "assistant", "tool"}:
            continue
        content_limit = max_tool_result_chars if role == "tool" else max_message_chars
        content = _message_content_text(item.get("content"), content_limit)
        tool_calls = _normalize_runtime_tool_calls(
            item.get("toolCalls") or item.get("tool_calls"),
            max_args_chars=max_tool_args_chars,
        )
        tool_call_id = _str_or_none(item.get("toolCallId") or item.get("tool_call_id"))
        if role == "user" and not content:
            continue
        if role == "assistant" and not content and not tool_calls:
            continue
        if role == "tool" and not tool_call_id:
            continue
        normalized = dict(item)
        normalized["role"] = role
        normalized["content"] = content
        if tool_calls:
            normalized["toolCalls"] = tool_calls
        else:
            normalized.pop("toolCalls", None)
            normalized.pop("tool_calls", None)
        if tool_call_id:
            normalized["toolCallId"] = tool_call_id
        else:
            normalized.pop("toolCallId", None)
            normalized.pop("tool_call_id", None)
        messages.append(normalized)
    return _ensure_valid_tool_protocol_window(messages)


def _ensure_valid_tool_protocol_window(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not messages or _has_valid_tool_protocol_messages(messages):
        return messages
    best: list[dict[str, Any]] = []
    best_end = -1
    total = len(messages)
    for start in range(total):
        for end in range(start + 1, total + 1):
            candidate = messages[start:end]
            if not _has_valid_tool_protocol_messages(candidate):
                continue
            if len(candidate) > len(best) or (len(candidate) == len(best) and end > best_end):
                best = candidate
                best_end = end
    return best


def _has_valid_tool_protocol_messages(messages: list[dict[str, Any]]) -> bool:
    pending_tool_call_ids: set[str] = set()
    for item in messages:
        if not isinstance(item, dict):
            return False
        role = str(item.get("role") or "").strip().lower()
        if role == "assistant":
            if pending_tool_call_ids:
                return False
            for tool_call in _normalize_runtime_tool_calls(item.get("toolCalls")):
                call_id = tool_call.get("id")
                if isinstance(call_id, str) and call_id:
                    pending_tool_call_ids.add(call_id)
        elif role == "tool":
            tool_call_id = _str_or_none(item.get("toolCallId"))
            if not tool_call_id or tool_call_id not in pending_tool_call_ids:
                return False
            pending_tool_call_ids.remove(tool_call_id)
        elif role == "user":
            if pending_tool_call_ids:
                return False
        else:
            return False
    return not pending_tool_call_ids


def _protocol_contains_user_message(
    protocol_messages: list[dict[str, Any]],
    pending_user_message: dict[str, Any],
) -> bool:
    expected = _str_or_default(pending_user_message.get("content"))
    expected_task_id = _str_or_none(pending_user_message.get("taskId"))
    if not expected and not expected_task_id:
        return False
    for item in protocol_messages:
        if not isinstance(item, dict) or item.get("role") != "user":
            continue
        if expected_task_id and item.get("taskId") == expected_task_id:
            return True
        content = _str_or_default(item.get("content"))
        if expected and (content == expected or content.startswith(f"{expected}\n\n---")):
            return True
    return False


def _is_final_assistant_message(item: dict[str, Any]) -> bool:
    return (
        item.get("role") == "assistant"
        and bool(item.get("content"))
        and not item.get("toolCalls")
    )


def _langchain_message_role(message: Any) -> str:
    message_type = str(getattr(message, "type", "") or "").strip().lower()
    if message_type in {"system", "human", "ai", "tool"}:
        return {
            "human": "user",
            "ai": "assistant",
        }.get(message_type, message_type)
    class_name = message.__class__.__name__.lower()
    if "system" in class_name:
        return "system"
    if "human" in class_name or "user" in class_name:
        return "user"
    if "tool" in class_name:
        return "tool"
    if "ai" in class_name or "assistant" in class_name:
        return "assistant"
    return ""


def _message_content_text(value: Any, max_chars: int) -> str:
    if isinstance(value, str):
        return _clean_content(value, max_chars)
    if value in (None, "", [], {}):
        return ""
    try:
        return _clean_content(json.dumps(value, ensure_ascii=False), max_chars)
    except Exception:
        return _clean_content(str(value), max_chars)


def _normalize_runtime_tool_calls(value: Any, *, max_args_chars: int | None = None) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        call_id = _str_or_none(item.get("id") or item.get("tool_call_id"))
        name = _str_or_none(item.get("name"))
        args = item.get("args")
        function = item.get("function")
        if isinstance(function, dict):
            name = name or _str_or_none(function.get("name"))
            args = args if args is not None else function.get("arguments")
        if not call_id or not name:
            continue
        normalized.append({
            "name": name,
            "args": _normalize_tool_args(args, max_chars=max_args_chars),
            "id": call_id,
        })
    return normalized


def _normalize_tool_args(value: Any, *, max_chars: int | None = None) -> dict[str, Any]:
    if isinstance(value, dict):
        parsed = value
    elif isinstance(value, str) and value.strip():
        try:
            candidate = json.loads(value)
        except json.JSONDecodeError:
            return {}
        parsed = candidate if isinstance(candidate, dict) else {}
    else:
        return {}
    if not parsed:
        return {}
    limit = max_chars if isinstance(max_chars, int) and max_chars > 0 else None
    if limit is None:
        return parsed
    try:
        serialized = json.dumps(parsed, ensure_ascii=False, default=str)
    except Exception:
        serialized = str(parsed)
    if len(serialized) <= limit:
        return parsed
    return {
        "_truncated": True,
        "_original_chars": len(serialized),
        "preview": serialized[:limit].rstrip(),
    }


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
