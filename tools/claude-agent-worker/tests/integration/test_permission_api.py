"""Integration tests for the permission response endpoint:

  POST /api/v1/query/{task_id}/respond
"""

from __future__ import annotations

import asyncio

import pytest
from httpx import AsyncClient

from agent_worker.claude.sdk_wrapper import permission_pending, task_registry


def _make_pending_permission(
    task_id: str = "t-1",
    permission_id: str = "perm-abc",
    *,
    is_question: bool = False,
    is_plan_review: bool = False,
) -> asyncio.Event:
    """Register a pending permission entry and return its Event."""
    evt = asyncio.Event()
    permission_pending[permission_id] = {
        "event": evt,
        "result": None,
        "deny_message": None,
        "scope": "once",
        "task_id": task_id,
        "suggestions": [],
        "is_question": is_question,
        "is_plan_review": is_plan_review,
        "answers": None,
        "questions": None,
        "tool_input": {"command": "ls"},
    }
    return evt


@pytest.mark.asyncio
class TestRespondToPermission:
    """POST /api/v1/query/{task_id}/respond delivers user decisions."""

    async def test_allow_permission(self, client: AsyncClient):
        """Respond with allow sets result and signals the event."""
        evt = _make_pending_permission("t-1", "perm-1")

        resp = await client.post(
            "/api/v1/query/t-1/respond",
            json={
                "permission_id": "perm-1",
                "decision": "allow",
            },
        )

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "responded"
        assert body["permission_id"] == "perm-1"

        # The asyncio.Event should be set
        assert evt.is_set()

        # Note: the respond endpoint mutates the entry and sets the event,
        # but does NOT pop it from permission_pending.  The _can_use_tool
        # callback (in run_query) pops after evt.wait() returns.
        # Here we verify the entry was updated correctly.
        entry = permission_pending["perm-1"]
        assert entry["result"] == "allow"

    async def test_deny_permission(self, client: AsyncClient):
        """Respond with deny sets the deny_message."""
        evt = _make_pending_permission("t-2", "perm-deny")

        resp = await client.post(
            "/api/v1/query/t-2/respond",
            json={
                "permission_id": "perm-deny",
                "decision": "deny",
                "deny_message": "Not safe to run",
            },
        )

        assert resp.status_code == 200
        assert evt.is_set()

    async def test_respond_with_scope(self, client: AsyncClient):
        """Scope (once/session/always) is stored in the entry."""
        _make_pending_permission("t-3", "perm-scope")

        # Capture entry before respond consumes it
        entry = permission_pending["perm-scope"]

        resp = await client.post(
            "/api/v1/query/t-3/respond",
            json={
                "permission_id": "perm-scope",
                "decision": "allow",
                "scope": "session",
            },
        )

        assert resp.status_code == 200
        # The entry was mutated before event.set() and pop
        assert entry["scope"] == "session"

    async def test_respond_with_answers(self, client: AsyncClient):
        """AskUserQuestion: answers are stored in the entry."""
        _make_pending_permission("t-4", "perm-q", is_question=True)
        entry = permission_pending["perm-q"]

        resp = await client.post(
            "/api/v1/query/t-4/respond",
            json={
                "permission_id": "perm-q",
                "decision": "allow",
                "answers": {"Which option?": "Option A"},
            },
        )

        assert resp.status_code == 200
        assert entry["answers"] == {"Which option?": "Option A"}

    async def test_respond_with_plan_action(self, client: AsyncClient):
        """ExitPlanMode: plan_action is stored in the entry."""
        _make_pending_permission("t-5", "perm-plan", is_plan_review=True)
        entry = permission_pending["perm-plan"]

        resp = await client.post(
            "/api/v1/query/t-5/respond",
            json={
                "permission_id": "perm-plan",
                "decision": "allow",
                "plan_action": "acceptEdits",
            },
        )

        assert resp.status_code == 200
        assert entry["plan_action"] == "acceptEdits"

    async def test_respond_unknown_permission_returns_404(self, client: AsyncClient):
        """Responding to a non-existent permission_id returns 404."""
        resp = await client.post(
            "/api/v1/query/t-x/respond",
            json={
                "permission_id": "does-not-exist",
                "decision": "allow",
            },
        )

        assert resp.status_code == 404
        assert "not found" in resp.json()["detail"].lower()

    async def test_respond_wrong_task_returns_400(self, client: AsyncClient):
        """Permission belongs to task A but respond targets task B → 400."""
        _make_pending_permission("t-owner", "perm-mismatch")

        resp = await client.post(
            "/api/v1/query/t-other/respond",
            json={
                "permission_id": "perm-mismatch",
                "decision": "allow",
            },
        )

        assert resp.status_code == 400
        assert "does not belong" in resp.json()["detail"]
        # Entry should NOT be consumed
        assert "perm-mismatch" in permission_pending
