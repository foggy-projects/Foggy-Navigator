"""In-memory Frame storage indexed by frame_id and task_id."""

from __future__ import annotations

from ..models import SkillFrameState


class FrameStore:
    """First-version in-memory store for SkillFrameState.

    Provides CRUD operations indexed by ``frame_id`` and lookup by
    ``task_id``.  A production version would persist to SQLite or a
    shared database.
    """

    def __init__(self) -> None:
        self._frames: dict[str, SkillFrameState] = {}
        self._task_index: dict[str, list[str]] = {}

    # -- write ---------------------------------------------------------------

    def save(self, frame: SkillFrameState) -> None:
        self._frames[frame.frame_id] = frame
        task_frames = self._task_index.setdefault(frame.task_id, [])
        if frame.frame_id not in task_frames:
            task_frames.append(frame.frame_id)

    def delete(self, frame_id: str) -> None:
        frame = self._frames.pop(frame_id, None)
        if frame:
            task_frames = self._task_index.get(frame.task_id, [])
            if frame_id in task_frames:
                task_frames.remove(frame_id)

    # -- read ----------------------------------------------------------------

    def get(self, frame_id: str) -> SkillFrameState | None:
        return self._frames.get(frame_id)

    def get_by_task(self, task_id: str) -> list[SkillFrameState]:
        frame_ids = self._task_index.get(task_id, [])
        return [self._frames[fid] for fid in frame_ids if fid in self._frames]

    def list_all(self) -> list[SkillFrameState]:
        return list(self._frames.values())

    def clear(self) -> None:
        self._frames.clear()
        self._task_index.clear()
