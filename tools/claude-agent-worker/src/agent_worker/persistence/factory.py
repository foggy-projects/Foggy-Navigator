"""Factory for event store singletons.

Pattern follows ``process_detection.py`` — module-level ``get_*()`` factory
that returns a lazily-initialized singleton.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import TYPE_CHECKING

from .jsonl_store import JsonlEventStore

if TYPE_CHECKING:
    from .protocol import EventStore

logger = logging.getLogger(__name__)

# Module-level singleton
_store: EventStore | None = None

# Default base directory: tools/claude-agent-worker/logs/
_DEFAULT_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent / "logs"


def get_event_store() -> EventStore:
    """Return the configured ``EventStore`` singleton.

    On first call, reads configuration from :data:`~agent_worker.config.settings`
    to determine whether persistence is enabled and where to store files.
    When disabled, returns a no-op store that silently drops all writes.
    """
    global _store
    if _store is None:
        from ..config import settings

        if not getattr(settings, "event_persistence_enabled", True):
            _store = _NoOpEventStore()
            logger.info("Event persistence disabled")
        else:
            base_dir_str = getattr(settings, "event_store_dir", "")
            base_dir = Path(base_dir_str) if base_dir_str else _DEFAULT_BASE_DIR
            _store = JsonlEventStore(base_dir)
            logger.info("Event persistence enabled: %s", base_dir / "events")
    return _store


class _NoOpEventStore:
    """No-op event store for when persistence is disabled."""

    def append(self, task_id, event):
        pass

    def load_events(self, task_id, after_seq=0):
        return []

    def get_latest_seq(self, task_id):
        return 0

    def mark_closed(self, task_id):
        pass

    def is_closed(self, task_id):
        return False

    def cleanup(self, task_id):
        pass
