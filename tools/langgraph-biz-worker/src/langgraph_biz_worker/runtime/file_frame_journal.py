"""File-based Frame journal - writes Frame state to JSON files on every mutation.

This is a sidecar persistence layer; the in-memory ``FrameStore`` remains the
primary runtime index.  The journal's sole purpose is to enable **external**
recovery (e.g. Java-side resume after Worker restart or approval flow).

Worker does NOT auto-recover on restart.  Recovery is always triggered
externally via ``POST /api/v1/resume`` with ``taskId`` plus the upstream
``contextId``/``sessionId`` locator.

Directory layout::

    <data_root>/
      runtime/
        sessions/
          by-date/YYYY/MM/DD/
            <hash>/<session-id>/
              frames/<frame-id>.json
              reports/<frame-id>.md
              logs/...

Historical ``frames``/``runtime/frames`` layouts are intentionally not read by
this journal; callers must provide the current ``contextId`` locator.

Design reference: Doc 31 §16.3
"""

from __future__ import annotations

import json
import logging
import time
from datetime import date, datetime, timezone
from pathlib import Path
from threading import Lock
from typing import Any

from ..models import FrameKind, FrameStatus, SkillFrameState
from .file_layout import (
    context_segment_path,
    date_parts_for_frame,
    iter_date_dirs,
    parse_date,
    require_standard_context_id,
    safe_path_segment,
    session_data_dir,
    session_key_for_frame,
)

logger = logging.getLogger(__name__)

_JOURNAL_SEQ_LOCK = Lock()
_LAST_JOURNAL_SEQ = 0

_RECOVERABLE_FRAME_STATUSES = frozenset({
    FrameStatus.RUNNING,
    FrameStatus.WAITING_CHILD,
    FrameStatus.AWAITING_APPROVAL,
    FrameStatus.AWAITING_USER,
})


class FileFrameJournal:
    """Persist ``SkillFrameState`` snapshots to JSON files.

    Parameters
    ----------
    data_root:
        Base directory under which date-sharded session data is created.
        Typically ``<worker-root>/accounts/<account-id>`` or a test
        temporary directory.
    """

    def __init__(self, data_root: str | Path) -> None:
        self._data_root = Path(data_root)
        self._session_root = self._data_root / "runtime" / "sessions"

    @property
    def data_root(self) -> Path:
        """Base directory that contains both frame snapshots and reports."""
        return self._data_root

    # -- write ---------------------------------------------------------------

    def save(self, frame: SkillFrameState) -> Path:
        """Write (or overwrite) the Frame snapshot to disk.

        Returns the path of the written file.
        """
        frame.journal_seq = self._next_journal_seq()
        frame.journal_updated_at = datetime.now(timezone.utc).isoformat()

        date_parts = date_parts_for_frame(frame)
        conversation_id = _standard_conversation_id(frame.conversation_id)
        session_key = conversation_id or session_key_for_frame(frame)
        if conversation_id:
            frame.conversation_id = conversation_id
        file_path = self._session_frame_path(
            session_key,
            frame.frame_id,
            date_parts,
            require_standard_context=conversation_id is not None,
        )
        payload = frame.model_dump(mode="json")
        self._write_json(file_path, payload)

        logger.debug("Journal saved frame=%s to %s", frame.frame_id, file_path)
        return file_path

    # -- read ----------------------------------------------------------------

    def load(
        self,
        task_id: str,
        frame_id: str,
        *,
        conversation_id: str | None = None,
    ) -> SkillFrameState | None:
        """Load a single Frame snapshot from disk."""
        for file_path in self._candidate_task_frame_paths(
            task_id,
            frame_id,
            conversation_id=conversation_id,
        ):
            frame = self._read_file(file_path)
            if frame and frame.task_id == task_id and frame.frame_id == frame_id:
                if conversation_id is not None and not _matches_conversation(frame, conversation_id):
                    continue
                return frame
        return None

    def path_for_frame(
        self,
        task_id: str,
        frame_id: str,
        *,
        conversation_id: str | None = None,
    ) -> Path | None:
        """Return the best known snapshot path for a frame."""
        for file_path in self._candidate_task_frame_paths(
            task_id,
            frame_id,
            conversation_id=conversation_id,
        ):
            frame = self._read_file(file_path)
            if frame and frame.task_id == task_id and frame.frame_id == frame_id:
                if conversation_id is not None and not _matches_conversation(frame, conversation_id):
                    continue
                return file_path
        return None

    def load_by_task(
        self,
        task_id: str,
        *,
        conversation_id: str | None = None,
    ) -> list[SkillFrameState]:
        """Load all Frame snapshots for a given task."""
        if conversation_id:
            paths = self._scan_conversation_frame_paths(conversation_id)
        else:
            paths = self._scan_task_frame_paths(task_id)

        return _sort_frames_by_journal_order(
            _dedupe_latest_frames(
                self._read_frame_paths(
                    paths,
                    task_id=task_id,
                    conversation_id=conversation_id,
                )
            )
        )

    def load_by_conversation(self, conversation_id: str) -> list[SkillFrameState]:
        """Load all Frame snapshots for a conversation/session."""
        paths = self._scan_conversation_frame_paths(conversation_id)

        return _sort_frames_by_journal_order(
            _dedupe_latest_frames(self._read_frame_paths(paths, conversation_id=conversation_id))
        )

    def _read_frames(self, directory: Path) -> list[SkillFrameState]:
        frames: list[SkillFrameState] = []
        for file_path in sorted(directory.glob("*.json")):
            frame = self._read_file(file_path)
            if frame:
                frames.append(frame)
        return frames

    def find_awaiting_approval(
        self,
        task_id: str,
        *,
        conversation_id: str | None = None,
    ) -> SkillFrameState | None:
        """Find a Frame in AWAITING_APPROVAL state for a task.

        Used by the resume endpoint.  The normal path passes ``contextId`` so
        lookup stays inside one sharded session directory.
        """
        candidates = [
            frame for frame in self.load_by_task(task_id, conversation_id=conversation_id)
            if frame.status == FrameStatus.AWAITING_APPROVAL
        ]
        for frame in candidates:
            if (
                frame.frame_kind == FrameKind.SKILL
                and isinstance(
                    frame.private_working_state.get("pending_child_approval_frame_id"),
                    str,
                )
            ):
                return frame
        for frame in candidates:
            if frame.frame_kind == FrameKind.SKILL:
                return frame
        for frame in candidates:
            if frame.status == FrameStatus.AWAITING_APPROVAL:
                return frame
        return None

    # -- delete / cleanup ----------------------------------------------------

    def delete(self, task_id: str, frame_id: str) -> None:
        """Delete a single Frame file."""
        frame = self.load(task_id, frame_id)

        paths: list[Path] = []
        known_path = self.path_for_frame(
            task_id,
            frame_id,
            conversation_id=frame.conversation_id if frame is not None else None,
        )
        if known_path is not None:
            paths.append(known_path)
        paths.extend(self._scan_task_frame_paths(task_id, frame_id))

        conversation_id = frame.conversation_id if frame is not None else None
        if conversation_id:
            paths.extend(self._scan_conversation_frame_paths(conversation_id, frame_id=frame_id))

        for file_path in _unique_paths(paths):
            self._delete_file(file_path, stop=self._data_root)
        logger.debug("Journal deleted frame=%s", frame_id)

    def cleanup_task(self, task_id: str) -> None:
        """Remove the entire task directory (all Frame files)."""
        for frame in self.load_by_task(task_id):
            self.delete(task_id, frame.frame_id)

        logger.debug("Journal cleaned up task=%s", task_id)

    def dry_run_cleanup_before(
        self,
        before_date: str | date | datetime,
        *,
        active_task_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        """Plan date-shard cleanup without deleting data.

        A date shard is skipped if it contains an explicitly active task or a
        recoverable frame state. ``before_date`` is exclusive.
        """
        cutoff = parse_date(before_date)
        active_tasks = set(active_task_ids or set())
        plans: list[dict[str, Any]] = []
        for shard_date, date_dir in iter_date_dirs(self._session_root / "by-date"):
            if shard_date >= cutoff:
                continue
            frames = self._read_frames_under_date_dir(date_dir)
            active_frames = [
                frame for frame in frames
                if frame.task_id in active_tasks or _is_recoverable_frame(frame)
            ]
            plans.append({
                "date": shard_date.isoformat(),
                "path": str(date_dir),
                "action": "skip" if active_frames else "delete",
                "reason": "active_or_recoverable_frames" if active_frames else "expired_date_shard",
                "frame_count": len(frames),
                "active_or_recoverable_frame_count": len(active_frames),
            })
        return plans

    # -- internal ------------------------------------------------------------

    def _read_file(self, file_path: Path) -> SkillFrameState | None:
        if not file_path.is_file():
            return None
        try:
            data: dict[str, Any] = json.loads(file_path.read_text(encoding="utf-8"))
            return SkillFrameState(**data)
        except Exception:
            logger.warning("Failed to parse frame file: %s", file_path, exc_info=True)
            return None

    def _next_journal_seq(self) -> int:
        global _LAST_JOURNAL_SEQ
        with _JOURNAL_SEQ_LOCK:
            next_value = time.time_ns()
            if next_value <= _LAST_JOURNAL_SEQ:
                next_value = _LAST_JOURNAL_SEQ + 1
            _LAST_JOURNAL_SEQ = next_value
            return next_value

    def _write_json(self, file_path: Path, payload: dict[str, Any]) -> None:
        file_path.parent.mkdir(parents=True, exist_ok=True)
        file_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def _session_frame_path(
        self,
        session_id: str,
        frame_id: str,
        date_parts: tuple[str, str, str],
        *,
        require_standard_context: bool = False,
    ) -> Path:
        return (
            session_data_dir(
                self._data_root,
                date_parts,
                session_id,
                require_standard_context=require_standard_context,
            )
            / "frames" / f"{safe_path_segment(frame_id)}.json"
        )

    def _candidate_task_frame_paths(
        self,
        task_id: str,
        frame_id: str,
        *,
        conversation_id: str | None = None,
    ) -> list[Path]:
        paths: list[Path] = []
        if conversation_id:
            paths.extend(self._scan_conversation_frame_paths(conversation_id, frame_id=frame_id))
        else:
            paths.extend(self._scan_task_frame_paths(task_id, frame_id))
        return _unique_paths(paths)

    def _scan_task_frame_paths(self, task_id: str, frame_id: str | None = None) -> list[Path]:
        frame_pattern = f"{safe_path_segment(frame_id)}.json" if frame_id else "*.json"
        paths = sorted((self._session_root / "by-date").glob(f"*/*/*/*/*/frames/{frame_pattern}"))
        return _unique_paths(paths)

    def _scan_conversation_frame_paths(
        self,
        conversation_id: str,
        *,
        frame_id: str | None = None,
    ) -> list[Path]:
        frame_pattern = f"{safe_path_segment(frame_id)}.json" if frame_id else "*.json"
        session_pattern = context_segment_path(conversation_id).as_posix()
        paths = sorted((self._session_root / "by-date").glob(
            f"*/*/*/{session_pattern}/frames/{frame_pattern}",
        ))
        return _unique_paths(paths)

    def _read_frame_paths(
        self,
        paths: list[Path],
        *,
        task_id: str | None = None,
        conversation_id: str | None = None,
    ) -> list[SkillFrameState]:
        frames: list[SkillFrameState] = []
        for file_path in _unique_paths(paths):
            frame = self._read_file(file_path)
            if not frame:
                continue
            if task_id is not None and frame.task_id != task_id:
                continue
            if conversation_id is not None and not _matches_conversation(frame, conversation_id):
                continue
            frames.append(frame)
        return frames

    def _read_frames_under_date_dir(self, date_dir: Path) -> list[SkillFrameState]:
        frames: list[SkillFrameState] = []
        for file_path in sorted(date_dir.glob("*/*/frames/*.json")):
            frame = self._read_file(file_path)
            if frame:
                frames.append(frame)
        return frames

    def _delete_file(self, file_path: Path, *, stop: Path) -> None:
        if file_path.is_file():
            file_path.unlink()
            self._prune_empty_parents(file_path.parent, stop=stop)

    def _delete_empty_dir(self, directory: Path, *, stop: Path) -> None:
        if directory.is_dir():
            try:
                directory.rmdir()
            except OSError:
                return
            self._prune_empty_parents(directory.parent, stop=stop)

    def _prune_empty_parents(self, directory: Path, *, stop: Path) -> None:
        stop = stop.resolve()
        current = directory
        while True:
            try:
                current.resolve().relative_to(stop)
            except ValueError:
                break
            if current.resolve() == stop:
                break
            try:
                current.rmdir()
            except OSError:
                break
            current = current.parent


def _sort_frames_by_journal_order(frames: list[SkillFrameState]) -> list[SkillFrameState]:
    return sorted(frames, key=_journal_sort_key)


def _journal_sort_key(frame: SkillFrameState) -> tuple[int, str, str]:
    sequence = frame.journal_seq if isinstance(frame.journal_seq, int) else -1
    updated_at = frame.journal_updated_at or frame.ended_at or frame.started_at or ""
    return sequence, updated_at, frame.frame_id


def _dedupe_latest_frames(frames: list[SkillFrameState]) -> list[SkillFrameState]:
    latest: dict[str, SkillFrameState] = {}
    for frame in frames:
        current = latest.get(frame.frame_id)
        if current is None or _journal_sort_key(frame) >= _journal_sort_key(current):
            latest[frame.frame_id] = frame
    return list(latest.values())


def _unique_paths(paths: list[Path]) -> list[Path]:
    unique: list[Path] = []
    seen: set[str] = set()
    for path in paths:
        key = str(path)
        if key in seen:
            continue
        seen.add(key)
        unique.append(path)
    return unique


def _matches_conversation(frame: SkillFrameState, conversation_id: str) -> bool:
    return frame.conversation_id == conversation_id or frame.session_id == conversation_id


def _standard_conversation_id(value: Any) -> str | None:
    if value is None:
        return None
    return require_standard_context_id(value)


def _is_recoverable_frame(frame: SkillFrameState) -> bool:
    if frame.status in _RECOVERABLE_FRAME_STATUSES:
        return True
    return frame.private_working_state.get("recoverable") is True
