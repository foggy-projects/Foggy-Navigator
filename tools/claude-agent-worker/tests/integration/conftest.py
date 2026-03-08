"""Shared fixtures for HTTP integration tests.

Key fixtures:
  - ``client``: async httpx client bound to the FastAPI app (dev mode, no auth)
  - ``clean_registries``: auto-clears task_registry / permission_pending / session_store
  - ``make_broadcast``: factory to create pre-populated EventBroadcast instances
  - ``collect_sse_events``: helper to send request and parse SSE events
"""

from __future__ import annotations

import asyncio
import json
import re
from typing import Any, AsyncGenerator
from unittest.mock import patch

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

# ---------------------------------------------------------------------------
# Import app and shared state *before* tests so patches apply correctly
# ---------------------------------------------------------------------------
from agent_worker.main import app
from agent_worker.claude.sdk_wrapper import (
    EventBroadcast,
    task_registry,
    permission_pending,
    session_store,
)
from agent_worker.config import settings


# ---------------------------------------------------------------------------
# SSE parsing helper
# ---------------------------------------------------------------------------

def parse_sse_body(text: str) -> list[dict[str, Any]]:
    """Parse raw SSE response body into a list of JSON event payloads.

    Handles both ``\\n\\n`` and ``\\r\\n\\r\\n`` block separators.
    Only events with a parseable JSON ``data:`` line are returned.
    """
    # Normalize line endings to \\n
    text = text.replace("\r\n", "\n")
    events: list[dict[str, Any]] = []
    for block in text.split("\n\n"):
        block = block.strip()
        if not block:
            continue
        parsed = _parse_sse_block(block)
        if parsed is not None:
            events.append(parsed)
    return events


async def collect_sse_events(
    client: AsyncClient,
    method: str,
    url: str,
    *,
    json_body: dict | None = None,
    params: dict | None = None,
    timeout: float = 10.0,
) -> list[dict[str, Any]]:
    """Send a request and collect all SSE ``message`` events.

    Uses a non-streaming request — since mock generators finish immediately,
    the full response body is available. Handles both \\n\\n and \\r\\n\\r\\n
    SSE block separators.
    """
    kwargs: dict[str, Any] = {"timeout": timeout}
    if json_body is not None:
        kwargs["json"] = json_body
    if params is not None:
        kwargs["params"] = params

    resp = await client.request(method, url, **kwargs)
    if resp.status_code != 200:
        return []
    return parse_sse_body(resp.text)


def _parse_sse_block(block: str) -> dict[str, Any] | None:
    """Parse a single SSE block into a dict, or ``None`` for non-data blocks."""
    data_lines: list[str] = []

    for line in block.split("\n"):
        stripped = line.strip()
        if stripped.startswith("data:"):
            data_lines.append(stripped[5:].strip())
        # Skip event:, id:, comment lines

    if not data_lines:
        return None

    raw = "\n".join(data_lines)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest_asyncio.fixture
async def client() -> AsyncGenerator[AsyncClient, None]:
    """Async httpx client bound to the FastAPI app.

    Auth is disabled (dev mode) by patching ``worker_token`` to empty string.
    ``allowed_cwds`` is cleared so any cwd passes validation.
    """
    with (
        patch.object(settings, "worker_token", ""),
        patch.object(settings, "allowed_cwds", []),
    ):
        transport = ASGITransport(app=app)  # type: ignore[arg-type]
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c


@pytest.fixture(autouse=True)
def clean_registries():
    """Clear shared global registries before each test."""
    task_registry.clear()
    permission_pending.clear()
    session_store.clear()
    yield
    # Also clean up after
    task_registry.clear()
    permission_pending.clear()
    session_store.clear()


@pytest.fixture
def make_broadcast():
    """Factory to create an EventBroadcast with pre-populated events."""

    async def _make(
        task_id: str,
        events: list[dict[str, Any]],
        *,
        closed: bool = True,
    ) -> EventBroadcast:
        bc = EventBroadcast(task_id=task_id)
        for evt in events:
            await bc.put(evt)
        if closed:
            await bc.put(None)  # close sentinel
        return bc

    return _make
