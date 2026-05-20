"""Shared helpers for date-sharded worker data directories."""

from __future__ import annotations

from datetime import date, datetime, timezone
import hashlib
from pathlib import Path
import re
from typing import Any, Iterable
import uuid

STANDARD_CONTEXT_ID_DESCRIPTION = "bctx_yyyyMMdd_<hash>_<id>"
_STANDARD_CONTEXT_ID_PATTERN = re.compile(r"^bctx_(\d{8})_([0-9a-fA-F]{2})_[A-Za-z0-9._-]+$")


def safe_path_segment(value: str) -> str:
    """Return a filesystem-safe single path segment."""
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in str(value)) or "_"


def hash_shard_path(value: str) -> Path:
    """Return a one-level hash shard path for a high-cardinality key."""
    embedded = _embedded_hash_shard(value)
    if embedded is not None:
        return Path(embedded)
    digest = hashlib.sha256(str(value).encode("utf-8")).hexdigest()
    return Path(digest[:2])


def hashed_segment_path(value: str) -> Path:
    """Return ``<hash>/<safe-segment>`` for high-cardinality keys."""
    return hash_shard_path(value) / safe_path_segment(value)


def context_segment_path(context_id: str) -> Path:
    """Return ``<embedded-hash>/<contextId>`` for a standard business context id."""
    normalized = require_standard_context_id(context_id)
    embedded = _embedded_hash_shard(normalized)
    if embedded is None:  # guarded by require_standard_context_id
        raise ValueError(f"contextId must match {STANDARD_CONTEXT_ID_DESCRIPTION}")
    return Path(embedded) / safe_path_segment(normalized)


def date_parts_for_context_id(context_id: str) -> tuple[str, str, str]:
    """Return YYYY/MM/DD parts embedded in a standard business context id."""
    normalized = require_standard_context_id(context_id)
    match = _STANDARD_CONTEXT_ID_PATTERN.match(normalized)
    if not match:  # guarded by require_standard_context_id
        raise ValueError(f"contextId must match {STANDARD_CONTEXT_ID_DESCRIPTION}")
    compact = match.group(1)
    return compact[0:4], compact[4:6], compact[6:8]


def require_standard_context_id(context_id: Any) -> str:
    """Validate and normalize a business context id with an embedded shard."""
    if not isinstance(context_id, str) or not context_id.strip():
        raise ValueError("contextId is required")
    normalized = context_id.strip()
    match = _STANDARD_CONTEXT_ID_PATTERN.match(normalized)
    if not match:
        raise ValueError(f"contextId must match {STANDARD_CONTEXT_ID_DESCRIPTION}")
    compact_date = match.group(1)
    try:
        date(int(compact_date[0:4]), int(compact_date[4:6]), int(compact_date[6:8]))
    except ValueError as exc:
        raise ValueError(f"contextId must match {STANDARD_CONTEXT_ID_DESCRIPTION}") from exc
    return normalized


def generate_standard_context_id(today: date | None = None) -> str:
    """Generate a standard BizWorker-owned conversation context id."""
    entropy = uuid.uuid4().hex
    compact_date = (today or datetime.now(timezone.utc).date()).strftime("%Y%m%d")
    return f"bctx_{compact_date}_{entropy[:2]}_{entropy}"


def _embedded_hash_shard(value: str) -> str | None:
    match = _STANDARD_CONTEXT_ID_PATTERN.match(str(value))
    if not match:
        return None
    return match.group(2).lower()


def session_key_for_frame(frame: Any) -> str:
    """Return the stable session directory key for a frame-like object."""
    return (
        getattr(frame, "conversation_id", None)
        or getattr(frame, "session_id", None)
        or getattr(frame, "task_id", None)
        or "_no-session"
    )


def session_data_dir(
    data_root: str | Path,
    date_parts: tuple[str, str, str],
    session_id: str,
    *,
    require_standard_context: bool = False,
) -> Path:
    """Return the canonical session data directory for runtime artifacts."""
    has_embedded_context = _embedded_hash_shard(str(session_id)) is not None
    if require_standard_context or has_embedded_context:
        effective_date_parts = date_parts_for_context_id(session_id)
        segment_path = context_segment_path(session_id)
    else:
        effective_date_parts = date_parts
        segment_path = hashed_segment_path(session_id)
    return (
        Path(data_root) / "runtime" / "sessions" / "by-date"
        / date_path(effective_date_parts) / segment_path
    )


def date_parts_for_frame(frame: Any) -> tuple[str, str, str]:
    """Return UTC YYYY/MM/DD parts for a frame-like object."""
    for value in (
        getattr(frame, "started_at", None),
        getattr(frame, "journal_updated_at", None),
        getattr(frame, "ended_at", None),
    ):
        parsed = parse_datetime(value)
        if parsed is not None:
            return _date_parts(parsed)
    return _date_parts(datetime.now(timezone.utc))


def date_parts_for_now() -> tuple[str, str, str]:
    """Return current UTC YYYY/MM/DD parts."""
    return _date_parts(datetime.now(timezone.utc))


def parse_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    normalized = value.strip()
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def parse_date(value: str | date | datetime) -> date:
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).date() if value.tzinfo else value.date()
    if isinstance(value, date):
        return value
    normalized = value.strip().replace("\\", "/")
    if "/" in normalized:
        parts = normalized.split("/")
        if len(parts) != 3:
            raise ValueError("date must be YYYY-MM-DD or YYYY/MM/DD")
        return date(int(parts[0]), int(parts[1]), int(parts[2]))
    return date.fromisoformat(normalized)


def date_path(parts: tuple[str, str, str]) -> Path:
    return Path(parts[0]) / parts[1] / parts[2]


def date_string(parts: tuple[str, str, str]) -> str:
    return "/".join(parts)


def relative_to_root(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def resolve_relative_path(root: Path, value: Any) -> Path | None:
    if not isinstance(value, str) or not value:
        return None
    candidate = (root / value).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    return candidate


def iter_date_dirs(root: Path) -> Iterable[tuple[date, Path]]:
    """Yield valid YYYY/MM/DD directories immediately under ``root``."""
    if not root.is_dir():
        return
    for year_dir in sorted(root.iterdir()):
        if not year_dir.is_dir() or not year_dir.name.isdigit():
            continue
        for month_dir in sorted(year_dir.iterdir()):
            if not month_dir.is_dir() or not month_dir.name.isdigit():
                continue
            for day_dir in sorted(month_dir.iterdir()):
                if not day_dir.is_dir() or not day_dir.name.isdigit():
                    continue
                try:
                    yield date(int(year_dir.name), int(month_dir.name), int(day_dir.name)), day_dir
                except ValueError:
                    continue


def _date_parts(value: datetime) -> tuple[str, str, str]:
    value = value.astimezone(timezone.utc)
    return f"{value.year:04d}", f"{value.month:02d}", f"{value.day:02d}"
