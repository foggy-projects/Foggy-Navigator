"""File-based Frame journal — writes Frame state to JSON files on every mutation.

This is a sidecar persistence layer; the in-memory ``FrameStore`` remains the
primary runtime index.  The journal's sole purpose is to enable **external**
recovery (e.g. Java-side resume after Worker restart or approval flow).

Worker does NOT auto-recover on restart.  Recovery is always triggered
externally via ``POST /api/v1/resume`` with ``taskId``.

Directory layout::

    <data_root>/
      frames/
        <task-id>/
          <frame-id>.json

Design reference: Doc 31 §16.3
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from ..models import FrameStatus, SkillFrameState

logger = logging.getLogger(__name__)


class FileFrameJournal:
    """Persist ``SkillFrameState`` snapshots to JSON files.

    Parameters
    ----------
    data_root:
        Base directory under which ``frames/<task-id>/`` is created.
        Typically ``<worker-root>/accounts/<account-id>`` or a test
        temporary directory.
    """

    def __init__(self, data_root: str | Path) -> None:
        self._root = Path(data_root) / "frames"

    # -- write ---------------------------------------------------------------

    def save(self, frame: SkillFrameState) -> Path:
        """Write (or overwrite) the Frame snapshot to disk.

        Returns the path of the written file.
        """
        task_dir = self._root / frame.task_id
        task_dir.mkdir(parents=True, exist_ok=True)

        file_path = task_dir / f"{frame.frame_id}.json"
        payload = frame.model_dump(mode="json")
        file_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

        logger.debug("Journal saved frame=%s to %s", frame.frame_id, file_path)
        return file_path

    # -- read ----------------------------------------------------------------

    def load(self, task_id: str, frame_id: str) -> SkillFrameState | None:
        """Load a single Frame snapshot from disk."""
        file_path = self._root / task_id / f"{frame_id}.json"
        return self._read_file(file_path)

    def load_by_task(self, task_id: str) -> list[SkillFrameState]:
        """Load all Frame snapshots for a given task."""
        task_dir = self._root / task_id
        if not task_dir.is_dir():
            return []
        frames: list[SkillFrameState] = []
        for file_path in sorted(task_dir.glob("*.json")):
            frame = self._read_file(file_path)
            if frame:
                frames.append(frame)
        return frames

    def find_awaiting_approval(self, task_id: str) -> SkillFrameState | None:
        """Find a Frame in AWAITING_APPROVAL state for a task.

        Used by the resume endpoint — Java side passes only ``taskId``,
        Worker finds the suspended Frame internally (Doc 31 §16.5).
        """
        for frame in self.load_by_task(task_id):
            if frame.status == FrameStatus.AWAITING_APPROVAL:
                return frame
        return None

    # -- delete / cleanup ----------------------------------------------------

    def delete(self, task_id: str, frame_id: str) -> None:
        """Delete a single Frame file."""
        file_path = self._root / task_id / f"{frame_id}.json"
        if file_path.exists():
            file_path.unlink()
            logger.debug("Journal deleted frame=%s", frame_id)

    def cleanup_task(self, task_id: str) -> None:
        """Remove the entire task directory (all Frame files)."""
        task_dir = self._root / task_id
        if task_dir.is_dir():
            for f in task_dir.iterdir():
                f.unlink()
            task_dir.rmdir()
            logger.debug("Journal cleaned up task=%s", task_id)

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
