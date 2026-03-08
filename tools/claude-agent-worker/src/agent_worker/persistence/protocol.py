"""Strategy-pattern event persistence.

Provides pluggable backends behind a common ``EventStore`` protocol.
Use the module-level :func:`~agent_worker.persistence.factory.get_event_store`
factory to obtain the configured singleton.

Pattern follows ``process_detection.py`` — Protocol + factory function.
"""

from __future__ import annotations

from typing import Any, Protocol


class EventStore(Protocol):
    """Pluggable event persistence backend.

    Implementations must be safe to call from both sync and async contexts.
    The default JSONL implementation uses synchronous file I/O which is
    acceptable for append-only writes on local SSDs.

    All methods should be **fail-safe**: log warnings on errors rather than
    raising, so that event streaming is never blocked by persistence failures.
    """

    def append(self, task_id: str, event: dict[str, Any]) -> None:
        """Persist a single event for the given task.

        The event dict is expected to contain a ``seq`` field (injected by
        :class:`~agent_worker.claude.sdk_wrapper.EventBroadcast`).
        """
        ...

    def load_events(self, task_id: str, after_seq: int = 0) -> list[dict[str, Any]]:
        """Load persisted events for the given task.

        Returns events with ``seq > after_seq``, ordered by seq ascending.
        Returns an empty list if the task has no persisted events.
        """
        ...

    def get_latest_seq(self, task_id: str) -> int:
        """Return the latest seq for the given task, or 0 if none persisted."""
        ...

    def mark_closed(self, task_id: str) -> None:
        """Mark the task's event stream as closed (no more events expected)."""
        ...

    def is_closed(self, task_id: str) -> bool:
        """Check if the task's event stream has been marked as closed."""
        ...

    def cleanup(self, task_id: str) -> None:
        """Remove all persisted data for the given task."""
        ...

    def register_alias(self, alias_id: str, task_id: str) -> None:
        """Create a mapping from alias_id (e.g. foggy_task_id) to task_id.

        Used so that external systems querying by foggy_task_id can be
        resolved to the Worker-internal task_id even after the in-memory
        registry has been cleaned up.
        """
        ...

    def resolve_alias(self, maybe_alias: str) -> str:
        """If maybe_alias has a registered alias mapping, return the real task_id.

        Returns the input unchanged if no alias is found.
        """
        ...
