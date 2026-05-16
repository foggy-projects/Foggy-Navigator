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
        self._frame_task_index: dict[str, str] = {}
        self._conversation_index: dict[str, list[str]] = {}
        self._frame_conversation_index: dict[str, str] = {}

    # -- write ---------------------------------------------------------------

    def save(self, frame: SkillFrameState) -> None:
        previous_task_id = self._frame_task_index.get(frame.frame_id)
        if previous_task_id and previous_task_id != frame.task_id:
            previous_task_frames = self._task_index.get(previous_task_id, [])
            if frame.frame_id in previous_task_frames:
                previous_task_frames.remove(frame.frame_id)

        previous_conversation_id = self._frame_conversation_index.get(frame.frame_id)
        if previous_conversation_id and previous_conversation_id != frame.conversation_id:
            previous_conversation_frames = self._conversation_index.get(previous_conversation_id, [])
            if frame.frame_id in previous_conversation_frames:
                previous_conversation_frames.remove(frame.frame_id)

        self._frames[frame.frame_id] = frame
        self._frame_task_index[frame.frame_id] = frame.task_id
        task_frames = self._task_index.setdefault(frame.task_id, [])
        if frame.frame_id not in task_frames:
            task_frames.append(frame.frame_id)
        if frame.conversation_id:
            conversation_frames = self._conversation_index.setdefault(frame.conversation_id, [])
            if frame.frame_id not in conversation_frames:
                conversation_frames.append(frame.frame_id)
            self._frame_conversation_index[frame.frame_id] = frame.conversation_id
        else:
            self._frame_conversation_index.pop(frame.frame_id, None)

    def delete(self, frame_id: str) -> None:
        frame = self._frames.pop(frame_id, None)
        if frame:
            task_id = self._frame_task_index.pop(frame_id, frame.task_id)
            task_frames = self._task_index.get(task_id, [])
            if frame_id in task_frames:
                task_frames.remove(frame_id)
            conversation_id = self._frame_conversation_index.pop(frame_id, None)
            if conversation_id:
                conversation_frames = self._conversation_index.get(conversation_id, [])
                if frame_id in conversation_frames:
                    conversation_frames.remove(frame_id)

    # -- read ----------------------------------------------------------------

    def get(self, frame_id: str) -> SkillFrameState | None:
        return self._frames.get(frame_id)

    def get_by_task(self, task_id: str) -> list[SkillFrameState]:
        frame_ids = self._task_index.get(task_id, [])
        return [self._frames[fid] for fid in frame_ids if fid in self._frames]

    def get_by_conversation(self, conversation_id: str) -> list[SkillFrameState]:
        frame_ids = self._conversation_index.get(conversation_id, [])
        return [self._frames[fid] for fid in frame_ids if fid in self._frames]

    def list_all(self) -> list[SkillFrameState]:
        return list(self._frames.values())

    def clear(self) -> None:
        self._frames.clear()
        self._task_index.clear()
        self._frame_task_index.clear()
        self._conversation_index.clear()
        self._frame_conversation_index.clear()
