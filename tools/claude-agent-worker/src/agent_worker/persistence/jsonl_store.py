"""JSONL file-based event persistence.

Storage layout::

    {base_dir}/events/{task_id}/events.jsonl   -- one JSON object per line
    {base_dir}/events/{task_id}/.closed        -- empty marker file

Each event is appended as a single JSON line.  File-level O_APPEND semantics
ensure that concurrent appends from the same process are safe (single-writer
in practice since each task has one producer).

Pattern follows ``process_detection.py`` — concrete implementation of the
``EventStore`` protocol.
"""

from __future__ import annotations

import json
import logging
import shutil
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)


class JsonlEventStore:
    """File-based event store using JSONL format.

    All methods are **fail-safe** — they log warnings on I/O errors rather
    than raising, so that in-memory event streaming is never interrupted by
    persistence failures.
    """

    def __init__(self, base_dir: Path) -> None:
        self._base_dir = base_dir

    # ---- path helpers ----

    def _task_dir(self, task_id: str) -> Path:
        return self._base_dir / "events" / task_id

    def _events_file(self, task_id: str) -> Path:
        return self._task_dir(task_id) / "events.jsonl"

    def _closed_marker(self, task_id: str) -> Path:
        return self._task_dir(task_id) / ".closed"

    # ---- EventStore protocol ----

    def append(self, task_id: str, event: dict[str, Any]) -> None:
        """Append a single event to the task's JSONL file."""
        task_dir = self._task_dir(task_id)
        try:
            task_dir.mkdir(parents=True, exist_ok=True)
            with self._events_file(task_id).open("a", encoding="utf-8") as f:
                f.write(json.dumps(event, ensure_ascii=False, default=str) + "\n")
        except OSError as exc:
            logger.warning("Failed to persist event for task %s: %s", task_id, exc)

    def load_events(self, task_id: str, after_seq: int = 0) -> list[dict[str, Any]]:
        """Load events with seq > after_seq from the task's JSONL file."""
        events_file = self._events_file(task_id)
        if not events_file.exists():
            return []

        events: list[dict[str, Any]] = []
        try:
            with events_file.open("r", encoding="utf-8") as f:
                for line_num, line in enumerate(f, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        evt = json.loads(line)
                        if evt.get("seq", 0) > after_seq:
                            events.append(evt)
                    except json.JSONDecodeError:
                        logger.warning(
                            "Corrupt JSONL line %d in %s, skipping",
                            line_num, events_file,
                        )
        except OSError as exc:
            logger.warning("Failed to load events for task %s: %s", task_id, exc)
        return events

    def get_latest_seq(self, task_id: str) -> int:
        """Scan the JSONL file and return the highest seq, or 0."""
        events_file = self._events_file(task_id)
        if not events_file.exists():
            return 0

        last_seq = 0
        try:
            # Read from the end for efficiency — but JSONL doesn't support
            # random access, so we scan all lines.  For typical task sizes
            # (< 1000 events) this is fast enough.
            with events_file.open("r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        evt = json.loads(line)
                        seq = evt.get("seq", 0)
                        if seq > last_seq:
                            last_seq = seq
                    except json.JSONDecodeError:
                        pass
        except OSError:
            pass
        return last_seq

    def mark_closed(self, task_id: str) -> None:
        """Create a ``.closed`` marker file to indicate stream completion."""
        task_dir = self._task_dir(task_id)
        try:
            task_dir.mkdir(parents=True, exist_ok=True)
            self._closed_marker(task_id).touch()
        except OSError as exc:
            logger.warning("Failed to mark task %s as closed: %s", task_id, exc)

    def is_closed(self, task_id: str) -> bool:
        """Check if the ``.closed`` marker file exists."""
        return self._closed_marker(task_id).exists()

    def cleanup(self, task_id: str) -> None:
        """Remove the entire task directory and its contents."""
        task_dir = self._task_dir(task_id)
        if task_dir.exists():
            try:
                shutil.rmtree(task_dir)
                logger.debug("Cleaned up event store for task %s", task_id)
            except OSError as exc:
                logger.warning("Failed to cleanup task %s: %s", task_id, exc)
