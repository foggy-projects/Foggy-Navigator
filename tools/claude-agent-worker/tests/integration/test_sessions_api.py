"""Integration tests for session management endpoints:

  GET  /api/v1/sessions
  GET  /api/v1/sessions/{session_id}
  POST /api/v1/sessions/sync
"""

from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import patch

import pytest
from httpx import AsyncClient

from agent_worker.claude.sdk_wrapper import session_store
from agent_worker.config import settings


def _seed_session(session_id: str, cwd: str = "/project", **extra) -> None:
    """Insert a session entry into the in-memory store."""
    now = datetime.now(timezone.utc).isoformat()
    session_store[session_id] = {
        "cwd": cwd,
        "created_at": now,
        "updated_at": now,
        **extra,
    }


@pytest.mark.asyncio
class TestListSessions:
    """GET /api/v1/sessions"""

    async def test_empty_returns_empty_list(self, client: AsyncClient):
        resp = await client.get("/api/v1/sessions")
        assert resp.status_code == 200
        assert resp.json() == []

    async def test_returns_all_sessions(self, client: AsyncClient):
        _seed_session("sess-1", "/project-a")
        _seed_session("sess-2", "/project-b")

        resp = await client.get("/api/v1/sessions")
        assert resp.status_code == 200
        body = resp.json()
        assert len(body) == 2
        ids = {s["session_id"] for s in body}
        assert ids == {"sess-1", "sess-2"}

    async def test_session_fields(self, client: AsyncClient):
        _seed_session("sess-f", "/proj", slug="fix-bug", git_branch="main")

        resp = await client.get("/api/v1/sessions")
        body = resp.json()
        assert len(body) == 1
        s = body[0]
        assert s["session_id"] == "sess-f"
        assert s["cwd"] == "/proj"
        assert s["slug"] == "fix-bug"
        assert s["git_branch"] == "main"
        assert "created_at" in s
        assert "updated_at" in s


@pytest.mark.asyncio
class TestGetSession:
    """GET /api/v1/sessions/{session_id}"""

    async def test_existing_session(self, client: AsyncClient):
        _seed_session("sess-one", "/home/user/project")

        resp = await client.get("/api/v1/sessions/sess-one")
        assert resp.status_code == 200
        body = resp.json()
        assert body["session_id"] == "sess-one"
        assert body["cwd"] == "/home/user/project"

    async def test_nonexistent_session_returns_404(self, client: AsyncClient):
        resp = await client.get("/api/v1/sessions/no-such-session")
        assert resp.status_code == 404


@pytest.mark.asyncio
class TestSyncSessions:
    """POST /api/v1/sessions/sync"""

    async def test_sync_with_no_allowed_cwds(self, client: AsyncClient):
        """When allowed_cwds is empty, sync returns 0 and does nothing."""
        resp = await client.post("/api/v1/sessions/sync")
        assert resp.status_code == 200
        body = resp.json()
        assert body["synced"] == 0

    async def test_sync_merges_scanned_sessions(self, client: AsyncClient):
        """Newly scanned sessions are added; existing ones are preserved."""
        _seed_session("existing-1", "/proj")

        scanned = {
            "existing-1": {"cwd": "/proj", "created_at": "old", "updated_at": "old"},
            "new-2": {"cwd": "/proj2", "created_at": "now", "updated_at": "now"},
        }

        with (
            patch.object(settings, "allowed_cwds", ["/proj", "/proj2"]),
            patch(
                "agent_worker.routes.sessions.scan_all_sessions",
                return_value=scanned,
            ),
        ):
            resp = await client.post("/api/v1/sessions/sync")

        assert resp.status_code == 200
        body = resp.json()
        assert body["synced"] == 1  # only new-2 is new
        assert body["total"] == 2
        # existing-1 retains its original data (not overwritten)
        assert "new-2" in session_store
        assert "existing-1" in session_store
