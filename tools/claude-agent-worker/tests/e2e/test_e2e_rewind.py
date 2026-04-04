"""E2E coverage for conversation rewind + resume against real Claude CLI."""

from __future__ import annotations

from pathlib import Path

import pytest
from httpx import AsyncClient

from agent_worker.claude.session_scanner import _find_session_file

from .conftest import HAS_CLI, collect_sse_events

pytestmark = [
    pytest.mark.e2e,
    pytest.mark.skipif(not HAS_CLI, reason="Claude CLI not available"),
]


def _query_body(prompt: str, cwd: str, *, session_id: str | None = None) -> dict:
    body = {
        "prompt": prompt,
        "cwd": cwd,
        "max_turns": 1,
        "model": "sonnet",
    }
    if session_id:
        body["session_id"] = session_id
    return body

def _cleanup_session_artifacts(session_id: str) -> None:
    session_file = _find_session_file(session_id)
    if session_file is None:
        return
    backup_file = session_file.with_suffix(".jsonl.rewind-backup.bak")
    for path in (session_file, backup_file):
        path.unlink(missing_ok=True)
    project_dir = session_file.parent
    try:
        project_dir.rmdir()
    except OSError:
        pass


async def _run_turn(
    client: AsyncClient,
    *,
    cwd: str,
    prompt: str,
    session_id: str | None = None,
) -> tuple[list[dict], str]:
    events = await collect_sse_events(
        client,
        "POST",
        "/api/v1/query",
        json_body=_query_body(prompt, cwd, session_id=session_id),
    )
    assert events, "Expected SSE events but got an empty response"

    errors = [e for e in events if e.get("type") == "error"]
    assert not errors, f"Unexpected error events: {errors}"

    result_events = [e for e in events if e.get("type") == "result"]
    assert len(result_events) == 1, f"Expected exactly one result event, got: {events}"

    result = result_events[0]
    sid = result.get("session_id")
    assert sid, f"Expected session_id in result event, got: {result}"
    return events, sid


async def _seed_three_turn_session(client: AsyncClient, cwd: str) -> str:
    session_id: str | None = None
    for idx in range(1, 4):
        _, session_id = await _run_turn(
            client,
            cwd=cwd,
            prompt=f"e2e-greeting-test: rewind turn {idx}",
            session_id=session_id,
        )
    assert session_id is not None
    return session_id


@pytest.mark.asyncio
class TestE2ERewindConversation:
    async def test_rewind_to_middle_turn_can_resume(self, client: AsyncClient, tmp_path: Path):
        workspace = tmp_path / "workspace-middle"
        workspace.mkdir(parents=True, exist_ok=True)
        cwd = str(workspace)
        session_id: str | None = None
        try:
            session_id = await _seed_three_turn_session(client, cwd)

            session_file = _find_session_file(session_id)
            assert session_file is not None and session_file.is_file(), f"Expected session file for {session_id}"

            checkpoints_resp = await client.get(f"/api/v1/sessions/{session_id}/checkpoints")
            assert checkpoints_resp.status_code == 200
            checkpoints = checkpoints_resp.json()
            assert [cp["turnIndex"] for cp in checkpoints] == [1, 2, 3]

            rewind_resp = await client.post(
                f"/api/v1/sessions/{session_id}/rewind-conversation",
                json={"turnIndex": 2},
            )
            assert rewind_resp.status_code == 200
            rewind_data = rewind_resp.json()
            assert rewind_data["status"] == "rewound"
            assert rewind_data["user_prompt"] == "e2e-greeting-test: rewind turn 2"

            count_resp = await client.get(f"/api/v1/sessions/{session_id}/message-count")
            assert count_resp.status_code == 200
            assert count_resp.json() == {"user_count": 1, "assistant_count": 1, "total": 2}

            resumed_events, resumed_session_id = await _run_turn(
                client,
                cwd=cwd,
                prompt=rewind_data["user_prompt"],
                session_id=session_id,
            )
            assert resumed_session_id == session_id
            assert "sync_checkpoint" in [event.get("type") for event in resumed_events]
        finally:
            if session_id:
                _cleanup_session_artifacts(session_id)

    async def test_rewind_to_first_turn_can_resume(self, client: AsyncClient, tmp_path: Path):
        """First-turn rewind deletes the session file; next query starts a fresh session."""
        workspace = tmp_path / "workspace-first"
        workspace.mkdir(parents=True, exist_ok=True)
        cwd = str(workspace)
        session_id: str | None = None
        new_session_id: str | None = None
        try:
            session_id = await _seed_three_turn_session(client, cwd)

            session_file = _find_session_file(session_id)
            assert session_file is not None and session_file.is_file(), f"Expected session file for {session_id}"

            rewind_resp = await client.post(
                f"/api/v1/sessions/{session_id}/rewind-conversation",
                json={"turnIndex": 1},
            )
            assert rewind_resp.status_code == 200
            rewind_data = rewind_resp.json()
            assert rewind_data["status"] == "rewound"
            assert rewind_data["user_prompt"] == "e2e-greeting-test: rewind turn 1"
            assert rewind_data.get("session_cleared") is True

            # Session file should be deleted
            session_file_after = _find_session_file(session_id)
            assert session_file_after is None, "Session file should have been deleted after first-turn rewind"

            # Resume as a NEW session (don't pass the old session_id)
            resumed_events, new_session_id = await _run_turn(
                client,
                cwd=cwd,
                prompt=rewind_data["user_prompt"],
            )
            # Should get a new session ID (different from the old one)
            assert new_session_id is not None
            assert new_session_id != session_id, "Expected a new session after first-turn rewind"
        finally:
            if session_id:
                _cleanup_session_artifacts(session_id)
            if new_session_id and new_session_id != session_id:
                _cleanup_session_artifacts(new_session_id)
