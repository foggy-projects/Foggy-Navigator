"""Event persistence layer for durable event storage.

Provides a Strategy-pattern ``EventStore`` protocol with pluggable backends.
The default backend writes events as line-delimited JSON (JSONL) files.

Usage::

    from agent_worker.persistence.factory import get_event_store

    store = get_event_store()
    store.append(task_id, event_dict)
"""
