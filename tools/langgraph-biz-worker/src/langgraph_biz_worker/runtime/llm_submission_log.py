"""Exact LLM request body snapshots for runtime debugging."""

from __future__ import annotations

import json
import logging
import re
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ..config import settings
from .file_layout import safe_path_segment, session_data_dir

logger = logging.getLogger(__name__)

_SUBMISSION_LOG_LOCK = threading.Lock()
_NUMERIC_PREFIX_RE = re.compile(r"^(\d+)")
_DEFAULT_DATE_PARTS = ("1970", "01", "01")
_MODEL_ATTRS = (
    "model",
    "model_name",
    "model_id",
    "deployment_name",
    "temperature",
    "max_tokens",
    "base_url",
    "openai_api_base",
    "anthropic_api_url",
)


def record_llm_submission(
    model: Any,
    messages: list[Any],
    runtime_context: dict[str, Any] | None,
    *,
    operation: str,
    task_id: str = "",
    frame_id: str = "",
    attempt: int = 1,
) -> Path | None:
    """Persist one exact LLM submission snapshot if the feature is enabled."""
    if not settings.llm_submission_log_enabled:
        return None
    context = runtime_context if isinstance(runtime_context, dict) else {}
    data_root = context.get("_llm_submission_data_root")
    session_id = context.get("_llm_submission_session_id")
    if not isinstance(data_root, (str, Path)) or not data_root:
        return None
    if not isinstance(session_id, str) or not session_id.strip():
        session_id = task_id or frame_id or "unknown-session"

    try:
        log_dir = _submission_log_dir(Path(data_root), session_id, context)
        with _SUBMISSION_LOG_LOCK:
            log_dir.mkdir(parents=True, exist_ok=True)
            seq = _next_sequence(log_dir)
            path = log_dir / _submission_file_name(
                seq,
                task_id=task_id,
                frame_id=frame_id,
                skill_id=_string_value(context.get("_llm_submission_skill_id")),
                iteration=_int_value(context.get("_llm_submission_iteration"), 0),
                attempt=attempt,
            )
            payload = _payload(
                model,
                messages,
                context,
                seq=seq,
                operation=operation,
                task_id=task_id,
                frame_id=frame_id,
                attempt=attempt,
                session_id=session_id,
            )
            path.write_text(
                json.dumps(payload, ensure_ascii=False, indent=2, default=str),
                encoding="utf-8",
            )
            _prune_old_files(log_dir, max(1, settings.llm_submission_log_max_files))
            return path
    except Exception:
        logger.warning("Failed to record LLM submission snapshot", exc_info=True)
        return None


def _submission_log_dir(data_root: Path, session_id: str, context: dict[str, Any]) -> Path:
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
        / "logs" / "llm-submissions"
    )


def _payload(
    model: Any,
    messages: list[Any],
    context: dict[str, Any],
    *,
    seq: int,
    operation: str,
    task_id: str,
    frame_id: str,
    attempt: int,
    session_id: str,
) -> dict[str, Any]:
    return {
        "meta": {
            "schemaVersion": 1,
            "seq": seq,
            "recordedAt": datetime.now(timezone.utc).isoformat(),
            "operation": operation,
            "sessionId": session_id,
            "contextId": session_id if context.get("_llm_submission_require_standard_context") else None,
            "taskId": task_id,
            "frameId": frame_id,
            "skillId": _string_value(context.get("_llm_submission_skill_id")),
            "iteration": _int_value(context.get("_llm_submission_iteration"), 0),
            "attempt": attempt,
            "runtimeRevision": context.get("_runtime_context_revision"),
        },
        "body": {
            "model": _model_snapshot(model),
            "messages": [_jsonable(message) for message in messages],
            "tools": _jsonable(context.get("_llm_submission_tools") or []),
            "tool_choice": _jsonable(context.get("_llm_submission_tool_choice")),
        },
    }


def _model_snapshot(model: Any) -> dict[str, Any]:
    snapshot: dict[str, Any] = {"class": model.__class__.__name__}
    for attr in _MODEL_ATTRS:
        if not hasattr(model, attr):
            continue
        value = getattr(model, attr)
        if value in (None, ""):
            continue
        snapshot[attr] = _jsonable(value)
    return snapshot


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


def _next_sequence(log_dir: Path) -> int:
    max_seq = 0
    for file_path in log_dir.glob("*.json"):
        seq = _sequence_from_name(file_path.name)
        if seq > max_seq:
            max_seq = seq
    return max_seq + 1


def _prune_old_files(log_dir: Path, max_files: int) -> None:
    files = sorted(
        (file_path for file_path in log_dir.glob("*.json") if _sequence_from_name(file_path.name) > 0),
        key=lambda item: (_sequence_from_name(item.name), item.name),
    )
    for file_path in files[:-max_files]:
        try:
            file_path.unlink()
        except FileNotFoundError:
            continue


def _sequence_from_name(name: str) -> int:
    match = _NUMERIC_PREFIX_RE.match(name)
    if not match:
        return 0
    try:
        return int(match.group(1))
    except ValueError:
        return 0


def _submission_file_name(
    seq: int,
    *,
    task_id: str,
    frame_id: str,
    skill_id: str,
    iteration: int,
    attempt: int,
) -> str:
    parts = [
        f"{seq:06d}",
        _segment(skill_id or "unknown-skill"),
        _segment(task_id or "unknown-task"),
        _segment(frame_id or "unknown-frame"),
        f"iter{max(0, iteration):02d}",
        f"attempt{max(1, attempt):02d}",
    ]
    return "_".join(parts) + ".json"


def _segment(value: str, limit: int = 48) -> str:
    segment = safe_path_segment(value.strip() or "unknown")
    return segment[:limit] if len(segment) > limit else segment


def _string_value(value: Any) -> str:
    return value.strip() if isinstance(value, str) else ""


def _int_value(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _valid_date_parts(value: Any) -> bool:
    return (
        isinstance(value, tuple)
        and len(value) == 3
        and all(isinstance(part, str) and part for part in value)
    )
