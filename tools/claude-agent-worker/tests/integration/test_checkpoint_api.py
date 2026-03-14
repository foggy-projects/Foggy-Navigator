"""Integration tests for checkpoint / rewind HTTP endpoints.

Tests the FastAPI routes:
  - GET /api/v1/sessions/{session_id}/checkpoints
  - POST /api/v1/sessions/{session_id}/rewind-conversation
  - POST /api/v1/query/rewind  (file rewind via CLI)

Uses httpx AsyncClient bound to the FastAPI app with mocked scanner
functions (no real JSONL files or CLI processes).
"""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


# ---------------------------------------------------------------------------
# GET /api/v1/sessions/{session_id}/checkpoints
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestScanCheckpointsEndpoint:
    """GET /api/v1/sessions/{session_id}/checkpoints"""

    async def test_returns_checkpoint_list(self, client: AsyncClient):
        mock_checkpoints = [
            {"id": "uuid-1", "turnIndex": 1, "timestamp": "2026-03-10T10:00:00Z"},
            {"id": "uuid-2", "turnIndex": 2, "timestamp": "2026-03-10T10:01:00Z"},
        ]
        with patch(
            "agent_worker.routes.sessions.scan_session_checkpoints",
            return_value=mock_checkpoints,
        ):
            resp = await client.get("/api/v1/sessions/test-session/checkpoints")

        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert data[0]["id"] == "uuid-1"
        assert data[0]["turnIndex"] == 1
        assert data[1]["id"] == "uuid-2"
        assert data[1]["turnIndex"] == 2

    async def test_missing_session_returns_empty_list(self, client: AsyncClient):
        with patch(
            "agent_worker.routes.sessions.scan_session_checkpoints",
            return_value=[],
        ):
            resp = await client.get("/api/v1/sessions/nonexistent/checkpoints")

        assert resp.status_code == 200
        assert resp.json() == []

    async def test_excludes_tool_results_from_count(self, client: AsyncClient):
        """Verify endpoint returns only real user prompts, not tool_results."""
        # The scanner itself filters tool_results; here we just confirm
        # the endpoint passes through the filtered list faithfully
        mock_checkpoints = [
            {"id": "uuid-real-1", "turnIndex": 1, "timestamp": "t1"},
            {"id": "uuid-real-2", "turnIndex": 2, "timestamp": "t2"},
            {"id": "uuid-real-3", "turnIndex": 3, "timestamp": "t3"},
        ]
        with patch(
            "agent_worker.routes.sessions.scan_session_checkpoints",
            return_value=mock_checkpoints,
        ):
            resp = await client.get("/api/v1/sessions/session-with-tools/checkpoints")

        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 3
        # turnIndex should be sequential (no gaps from filtered tool_results)
        assert [cp["turnIndex"] for cp in data] == [1, 2, 3]


# ---------------------------------------------------------------------------
# POST /api/v1/sessions/{session_id}/rewind-conversation
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestRewindConversationEndpoint:
    """POST /api/v1/sessions/{session_id}/rewind-conversation"""

    async def test_rewind_success(self, client: AsyncClient):
        mock_result = {
            "status": "rewound",
            "user_prompt": "Generate a file",
            "turn_index": 2,
        }
        with patch(
            "agent_worker.routes.sessions.rewind_session_conversation",
            return_value=mock_result,
        ):
            resp = await client.post(
                "/api/v1/sessions/test-session/rewind-conversation",
                json={"turnIndex": 2},
            )

        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "rewound"
        assert data["user_prompt"] == "Generate a file"
        assert data["turn_index"] == 2

    async def test_rewind_missing_turn_index(self, client: AsyncClient):
        resp = await client.post(
            "/api/v1/sessions/test-session/rewind-conversation",
            json={},
        )
        assert resp.status_code == 400

    async def test_rewind_invalid_turn_index_zero(self, client: AsyncClient):
        resp = await client.post(
            "/api/v1/sessions/test-session/rewind-conversation",
            json={"turnIndex": 0},
        )
        assert resp.status_code == 400

    async def test_rewind_invalid_turn_index_negative(self, client: AsyncClient):
        resp = await client.post(
            "/api/v1/sessions/test-session/rewind-conversation",
            json={"turnIndex": -1},
        )
        assert resp.status_code == 400

    async def test_rewind_session_not_found(self, client: AsyncClient):
        mock_result = {
            "status": "error",
            "message": "Session file not found: nonexistent-id",
        }
        with patch(
            "agent_worker.routes.sessions.rewind_session_conversation",
            return_value=mock_result,
        ):
            resp = await client.post(
                "/api/v1/sessions/nonexistent-id/rewind-conversation",
                json={"turnIndex": 1},
            )

        assert resp.status_code == 404

    async def test_rewind_turn_not_found(self, client: AsyncClient):
        mock_result = {
            "status": "error",
            "message": "Turn 99 not found (only 2 user turns)",
        }
        with patch(
            "agent_worker.routes.sessions.rewind_session_conversation",
            return_value=mock_result,
        ):
            resp = await client.post(
                "/api/v1/sessions/test-session/rewind-conversation",
                json={"turnIndex": 99},
            )

        # "not found" in message → 404
        assert resp.status_code == 404

    async def test_rewind_internal_error(self, client: AsyncClient):
        mock_result = {
            "status": "error",
            "message": "Failed to write session file: Permission denied",
        }
        with patch(
            "agent_worker.routes.sessions.rewind_session_conversation",
            return_value=mock_result,
        ):
            resp = await client.post(
                "/api/v1/sessions/test-session/rewind-conversation",
                json={"turnIndex": 1},
            )

        # No "not found" in message → 500
        assert resp.status_code == 500


# ---------------------------------------------------------------------------
# POST /api/v1/query/rewind  (file rewind via CLI)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestRewindFilesEndpoint:
    """POST /api/v1/query/rewind — file rewind via Claude CLI."""

    async def test_rewind_files_sdk_not_available(self, client: AsyncClient):
        """When SDK is not installed, should return 503."""
        with patch("agent_worker.routes.query._sdk_available", False):
            resp = await client.post(
                "/api/v1/query/rewind",
                json={
                    "claude_session_id": "session-1",
                    "checkpoint_id": "cp-1",
                    "cwd": "D:\\projects",
                },
            )
        assert resp.status_code == 503

    async def test_rewind_files_success(self, client: AsyncClient):
        """Successful file rewind via CLI subprocess."""
        mock_subprocess_result = MagicMock()
        mock_subprocess_result.returncode = 0
        mock_subprocess_result.stdout = '{"status": "ok"}'
        mock_subprocess_result.stderr = ""

        with (
            patch("agent_worker.routes.query._sdk_available", True),
            patch("agent_worker.routes.query._use_agent_sdk", True),
            patch("agent_worker.routes.query._find_claude_cli", return_value="/usr/bin/claude"),
            patch("agent_worker.routes.query._validate_cwd", return_value="D:\\projects"),
            patch("agent_worker.routes.query.subprocess.run", return_value=mock_subprocess_result),
            patch("agent_worker.routes.query._wrapper") as mock_wrapper,
        ):
            mock_wrapper._build_env.return_value = {}
            resp = await client.post(
                "/api/v1/query/rewind",
                json={
                    "claude_session_id": "session-1",
                    "checkpoint_id": "cp-1",
                    "cwd": "D:\\projects",
                },
            )

        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "rewound"
        assert data["checkpoint_id"] == "cp-1"

    async def test_rewind_files_cli_failure(self, client: AsyncClient):
        """CLI returns non-zero exit code."""
        mock_subprocess_result = MagicMock()
        mock_subprocess_result.returncode = 1
        mock_subprocess_result.stderr = "fatal: not a git repository"

        with (
            patch("agent_worker.routes.query._sdk_available", True),
            patch("agent_worker.routes.query._use_agent_sdk", True),
            patch("agent_worker.routes.query._find_claude_cli", return_value="/usr/bin/claude"),
            patch("agent_worker.routes.query._validate_cwd", return_value="D:\\projects"),
            patch("agent_worker.routes.query.subprocess.run", return_value=mock_subprocess_result),
            patch("agent_worker.routes.query._wrapper") as mock_wrapper,
        ):
            mock_wrapper._build_env.return_value = {}
            resp = await client.post(
                "/api/v1/query/rewind",
                json={
                    "claude_session_id": "session-1",
                    "checkpoint_id": "cp-1",
                    "cwd": "D:\\projects",
                },
            )

        assert resp.status_code == 500
