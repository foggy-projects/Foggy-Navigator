"""Append-only protocol message events for LLM turn recovery."""

from __future__ import annotations

import json
import logging
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from ..config import settings
from .file_layout import safe_path_segment, session_data_dir

logger = logging.getLogger(__name__)

_EVENT_LOG_LOCK = threading.Lock()
_DEFAULT_DATE_PARTS = ("1970", "01", "01")


def record_initial_runtime_messages(
    messages: list[Any],
    runtime_context: dict[str, Any] | None,
    *,
    task_id: str,
    frame_id: str,
) -> Path | None:
    """Record the initial protocol messages for the current LLM turn."""
    path: Path | None = None
    for message in messages:
        path = record_runtime_message_event(
            "message",
            runtime_context,
            task_id=task_id,
            frame_id=frame_id,
            message=message,
            phase="initial",
        ) or path
    return path


def record_assistant_runtime_message(
    message: Any,
    runtime_context: dict[str, Any] | None,
    *,
    task_id: str,
    frame_id: str,
) -> Path | None:
    """Record an assistant model response and its tool calls."""
    path = record_runtime_message_event(
        "assistant",
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
        message=message,
    )
    for tool_call in _message_tool_calls(message):
        path = record_runtime_message_event(
            "assistant_tool_call",
            runtime_context,
            task_id=task_id,
            frame_id=frame_id,
            tool_call=tool_call,
        ) or path
    return path


def record_tool_result_runtime_message(
    tool_message: ToolMessage,
    tool_result: Any,
    runtime_context: dict[str, Any] | None,
    *,
    task_id: str,
    frame_id: str,
) -> Path | None:
    return record_runtime_message_event(
        "tool_result",
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
        message=tool_message,
        tool_result=tool_result,
    )


def record_checkpoint_runtime_event(
    runtime_context: dict[str, Any] | None,
    *,
    task_id: str,
    frame_id: str,
    checkpoint: str,
) -> Path | None:
    return record_runtime_message_event(
        "checkpoint",
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
        checkpoint=checkpoint,
    )


def restore_runtime_protocol_messages(
    runtime_context: dict[str, Any] | None,
    *,
    frame_id: str,
    checkpoint: str | None = None,
) -> list[Any]:
    """Restore provider protocol messages for an unfinished frame.

    This intentionally reconstructs only message events. Separate
    ``assistant_tool_call`` rows are audit detail because the matching
    assistant message already carries ``toolCalls``.
    """
    if not settings.runtime_message_event_log_enabled:
        return []
    context = runtime_context if isinstance(runtime_context, dict) else {}
    data_root = context.get("_llm_submission_data_root")
    session_id = context.get("_llm_submission_session_id")
    if not isinstance(data_root, (str, Path)) or not data_root:
        return []
    if not isinstance(session_id, str) or not session_id.strip():
        return []

    try:
        event_file = _latest_event_log_for_frame(Path(data_root), session_id, context, frame_id=frame_id)
        if event_file is None:
            return []
        events = _read_events(event_file)
        if checkpoint:
            events = _events_through_checkpoint(events, checkpoint)
        else:
            events = _events_through_last_checkpoint(events)
        messages = _messages_from_events(events)
        messages = _drop_system_messages(messages)
        if not _has_valid_tool_protocol(messages):
            logger.warning("Ignoring invalid runtime protocol message log: %s", event_file)
            return []
        context["_runtime_protocol_restore_source"] = str(event_file)
        context["_runtime_protocol_restore_message_count"] = len(messages)
        return messages
    except Exception:
        logger.warning("Failed to restore runtime protocol messages", exc_info=True)
        return []


def record_runtime_message_event(
    event_type: str,
    runtime_context: dict[str, Any] | None,
    *,
    task_id: str,
    frame_id: str,
    message: Any | None = None,
    tool_call: Any | None = None,
    tool_result: Any | None = None,
    checkpoint: str | None = None,
    phase: str | None = None,
) -> Path | None:
    if not settings.runtime_message_event_log_enabled:
        return None
    context = runtime_context if isinstance(runtime_context, dict) else {}
    data_root = context.get("_llm_submission_data_root")
    session_id = context.get("_llm_submission_session_id")
    if not isinstance(data_root, (str, Path)) or not data_root:
        return None
    if not isinstance(session_id, str) or not session_id.strip():
        session_id = task_id or frame_id or "unknown-session"

    try:
        log_path = _event_log_path(Path(data_root), session_id, context, task_id=task_id, frame_id=frame_id)
        with _EVENT_LOG_LOCK:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            seq = _next_sequence(context)
            payload = _payload(
                event_type,
                context,
                seq=seq,
                task_id=task_id,
                frame_id=frame_id,
                session_id=session_id,
                message=message,
                tool_call=tool_call,
                tool_result=tool_result,
                checkpoint=checkpoint,
                phase=phase,
            )
            with log_path.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(json.dumps(payload, ensure_ascii=False, default=str))
                handle.write("\n")
            return log_path
    except Exception:
        logger.warning("Failed to record runtime message event", exc_info=True)
        return None


def _event_log_path(data_root: Path, session_id: str, context: dict[str, Any], *, task_id: str, frame_id: str) -> Path:
    date_parts = context.get("_llm_submission_date_parts")
    if not _valid_date_parts(date_parts):
        date_parts = _DEFAULT_DATE_PARTS
    return (
        session_data_dir(
            data_root,
            tuple(date_parts),  # type: ignore[arg-type]
            session_id,
            require_standard_context=bool(context.get("_llm_submission_require_standard_context")),
        )
        / "logs" / "runtime-message-events"
        / f"{_segment(task_id or 'unknown-task')}_{_segment(frame_id or 'unknown-frame')}.jsonl"
    )


def _event_log_dir(data_root: Path, session_id: str, context: dict[str, Any]) -> Path:
    date_parts = context.get("_llm_submission_date_parts")
    if not _valid_date_parts(date_parts):
        date_parts = _DEFAULT_DATE_PARTS
    return (
        session_data_dir(
            data_root,
            tuple(date_parts),  # type: ignore[arg-type]
            session_id,
            require_standard_context=bool(context.get("_llm_submission_require_standard_context")),
        )
        / "logs" / "runtime-message-events"
    )


def _latest_event_log_for_frame(
    data_root: Path,
    session_id: str,
    context: dict[str, Any],
    *,
    frame_id: str,
) -> Path | None:
    log_dir = _event_log_dir(data_root, session_id, context)
    if not log_dir.exists():
        return None
    frame_segment = _segment(frame_id or "unknown-frame")
    candidates = [
        file_path
        for file_path in log_dir.glob(f"*_{frame_segment}.jsonl")
        if file_path.is_file()
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda item: (item.stat().st_mtime_ns, item.name))


def _read_events(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    return sorted(events, key=lambda event: _int_value(event.get("seq"), 0))


def _events_through_checkpoint(events: list[dict[str, Any]], checkpoint: str) -> list[dict[str, Any]]:
    checkpoint_seq = 0
    for event in events:
        if event.get("eventType") == "checkpoint" and event.get("checkpoint") == checkpoint:
            checkpoint_seq = _int_value(event.get("seq"), 0)
    if checkpoint_seq <= 0:
        return events
    return [event for event in events if _int_value(event.get("seq"), 0) <= checkpoint_seq]


def _events_through_last_checkpoint(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    checkpoint_seq = 0
    for event in events:
        if event.get("eventType") == "checkpoint":
            checkpoint_seq = _int_value(event.get("seq"), 0)
    if checkpoint_seq <= 0:
        return events
    return [event for event in events if _int_value(event.get("seq"), 0) <= checkpoint_seq]


def _messages_from_events(events: list[dict[str, Any]]) -> list[Any]:
    messages: list[Any] = []
    for event in events:
        event_type = event.get("eventType")
        if event_type not in {"message", "assistant", "tool_result"}:
            continue
        message_payload = event.get("message")
        if not isinstance(message_payload, dict):
            continue
        message = _message_from_payload(message_payload)
        if message is not None:
            messages.append(message)
    return messages


def _message_from_payload(payload: dict[str, Any]) -> Any | None:
    role = str(payload.get("role") or "").strip().lower()
    content = payload.get("content")
    if content is None:
        content = ""
    if role == "system":
        return SystemMessage(content=content)
    if role == "user":
        return HumanMessage(content=content)
    if role == "assistant":
        tool_calls = payload.get("toolCalls")
        if isinstance(tool_calls, list) and tool_calls:
            return AIMessage(content=content, tool_calls=_normalize_tool_calls(tool_calls))
        return AIMessage(content=content)
    if role == "tool":
        tool_call_id = payload.get("toolCallId")
        if not isinstance(tool_call_id, str) or not tool_call_id:
            return None
        return ToolMessage(content=content, tool_call_id=tool_call_id)
    return None


def _normalize_tool_calls(tool_calls: list[Any]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for item in tool_calls:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        call_id = item.get("id")
        args = item.get("args")
        if not isinstance(name, str) or not name:
            continue
        if not isinstance(call_id, str) or not call_id:
            continue
        normalized.append({
            "name": name,
            "args": args if isinstance(args, dict) else {},
            "id": call_id,
        })
    return normalized


def _drop_system_messages(messages: list[Any]) -> list[Any]:
    return [message for message in messages if not isinstance(message, SystemMessage)]


def _has_valid_tool_protocol(messages: list[Any]) -> bool:
    pending_tool_call_ids: set[str] = set()
    for message in messages:
        if isinstance(message, AIMessage):
            if pending_tool_call_ids:
                return False
            for tool_call in _message_tool_calls(message):
                if isinstance(tool_call, dict) and isinstance(tool_call.get("id"), str):
                    pending_tool_call_ids.add(tool_call["id"])
        elif isinstance(message, ToolMessage):
            tool_call_id = getattr(message, "tool_call_id", None)
            if not isinstance(tool_call_id, str) or tool_call_id not in pending_tool_call_ids:
                return False
            pending_tool_call_ids.remove(tool_call_id)
        elif pending_tool_call_ids:
            return False
    return not pending_tool_call_ids


def _payload(
    event_type: str,
    context: dict[str, Any],
    *,
    seq: int,
    task_id: str,
    frame_id: str,
    session_id: str,
    message: Any | None,
    tool_call: Any | None,
    tool_result: Any | None,
    checkpoint: str | None,
    phase: str | None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "seq": seq,
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "eventType": event_type,
        "sessionId": session_id,
        "contextId": session_id if context.get("_llm_submission_require_standard_context") else None,
        "taskId": task_id,
        "frameId": frame_id,
        "skillId": _string_value(context.get("_llm_submission_skill_id")),
        "iteration": _int_value(context.get("_llm_submission_iteration"), 0),
        "runtimeRevision": context.get("_runtime_context_revision"),
    }
    if phase:
        payload["phase"] = phase
    if checkpoint:
        payload["checkpoint"] = checkpoint
    if message is not None:
        payload["message"] = _message_payload(message)
    if tool_call is not None:
        payload["toolCall"] = _jsonable(tool_call)
    if tool_result is not None:
        payload["toolResult"] = _jsonable(tool_result)
    return payload


def _message_payload(message: Any) -> dict[str, Any]:
    payload = {
        "type": getattr(message, "type", message.__class__.__name__),
        "role": _message_role(message),
        "content": getattr(message, "content", None),
    }
    tool_call_id = getattr(message, "tool_call_id", None)
    if tool_call_id:
        payload["toolCallId"] = tool_call_id
    tool_calls = _message_tool_calls(message)
    if tool_calls:
        payload["toolCalls"] = _jsonable(tool_calls)
    return _jsonable(payload)


def _message_role(message: Any) -> str:
    if isinstance(message, SystemMessage):
        return "system"
    if isinstance(message, HumanMessage):
        return "user"
    if isinstance(message, AIMessage):
        return "assistant"
    if isinstance(message, ToolMessage):
        return "tool"
    role = getattr(message, "role", None)
    return str(role) if role else str(getattr(message, "type", "message"))


def _message_tool_calls(message: Any) -> list[Any]:
    tool_calls = getattr(message, "tool_calls", None)
    return tool_calls if isinstance(tool_calls, list) else []


def _next_sequence(context: dict[str, Any]) -> int:
    current = _int_value(context.get("_runtime_message_event_seq"), 0) + 1
    context["_runtime_message_event_seq"] = current
    return current


def _jsonable(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_jsonable(item) for item in value]
    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump):
        try:
            return _jsonable(model_dump(mode="json"))
        except TypeError:
            return _jsonable(model_dump())
    dict_method = getattr(value, "dict", None)
    if callable(dict_method):
        try:
            return _jsonable(dict_method())
        except Exception:
            pass
    return str(value)


def _valid_date_parts(value: Any) -> bool:
    return (
        isinstance(value, (list, tuple))
        and len(value) == 3
        and all(isinstance(item, str) and item for item in value)
    )


def _segment(value: str, limit: int = 64) -> str:
    segment = safe_path_segment(value.strip() or "unknown")
    return segment[:limit] if len(segment) > limit else segment


def _string_value(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def _int_value(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default
