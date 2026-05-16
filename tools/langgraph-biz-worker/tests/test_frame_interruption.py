"""Tests for POST /api/v1/frames/interruption."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import FrameStatus
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


@pytest.fixture(autouse=True)
def _setup_frame_interruption_service(tmp_path):
    from langgraph_biz_worker.routes import frame_interruption

    journal = FileFrameJournal(tmp_path)
    runtime = SkillRuntime(journal=journal)
    frame_interruption.configure(runtime, journal)

    yield runtime

    frame_interruption._runtime = None
    frame_interruption._journal = None


@pytest.fixture
async def client():
    from httpx import ASGITransport, AsyncClient
    from langgraph_biz_worker.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


async def test_records_recoverable_interruption_on_running_root(client, _setup_frame_interruption_service):
    runtime = _setup_frame_interruption_service
    frame_id = runtime.invoke_skill(
        task_id="lgt_interruption_1",
        skill_id="system.root",
        conversation_id="ctx-interruption",
        session_id="session-1",
    )

    resp = await client.post("/api/v1/frames/interruption", json={
        "taskId": "lgt_interruption_1",
        "session_id": "session-1",
        "context_id": "ctx-interruption",
        "reason": "user_cancelled",
        "error": "Cancelled by user",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "recorded"
    assert resp.json()["frame_id"] == frame_id

    frame = runtime.get_frame(frame_id)
    assert frame is not None
    assert frame.status == FrameStatus.RUNNING
    assert frame.private_working_state["recoverable"] is True
    assert frame.private_working_state["continuation_state"] == "INTERRUPTED"
    assert frame.private_working_state["interrupt_reason"] == "user_cancelled"
    assert frame.private_working_state["last_error"] == "Cancelled by user"


async def test_restores_by_conversation_and_rebinds_current_task(client, _setup_frame_interruption_service):
    runtime = _setup_frame_interruption_service
    frame_id = runtime.invoke_skill(
        task_id="lgt_old_task",
        skill_id="system.root",
        conversation_id="ctx-rebind",
        session_id="session-2",
    )
    runtime.store.clear()

    resp = await client.post("/api/v1/frames/interruption", json={
        "taskId": "lgt_new_task",
        "sessionId": "session-2",
        "contextId": "ctx-rebind",
        "reason": "stream_error",
        "error": "connection reset",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "recorded"
    frame = runtime.get_frame(frame_id)
    assert frame is not None
    assert frame.task_id == "lgt_new_task"
    assert frame.current_task_id == "lgt_new_task"
    assert frame.private_working_state["interrupt_reason"] == "stream_error"


async def test_user_cancelled_awaiting_root_becomes_reusable(client, _setup_frame_interruption_service):
    runtime = _setup_frame_interruption_service
    frame_id = runtime.invoke_skill(
        task_id="lgt_awaiting_cancel",
        skill_id="system.root",
        conversation_id="ctx-awaiting",
        session_id="session-3",
    )
    runtime.mark_awaiting_approval(frame_id, {"approval_type": "business_function"})

    resp = await client.post("/api/v1/frames/interruption", json={
        "taskId": "lgt_awaiting_cancel",
        "session_id": "session-3",
        "context_id": "ctx-awaiting",
        "reason": "user_cancelled",
        "error": "User aborted while approval was pending",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "recorded"
    frame = runtime.get_frame(frame_id)
    assert frame is not None
    assert frame.status == FrameStatus.RUNNING
    assert frame.private_working_state["recoverable"] is True
    assert frame.private_working_state["interrupt_reason"] == "user_cancelled"


async def test_user_cancelled_waiting_child_records_child_and_reuses_root(
    client,
    _setup_frame_interruption_service,
):
    runtime = _setup_frame_interruption_service
    root_frame_id = runtime.invoke_skill(
        task_id="lgt_waiting_child_cancel",
        skill_id="system.root",
        conversation_id="ctx-waiting-child",
        session_id="session-4",
    )
    child_frame_id = runtime.invoke_child_skill(
        root_frame_id,
        "child_skill",
        {"order_id": "ORD-1"},
    )

    resp = await client.post("/api/v1/frames/interruption", json={
        "taskId": "lgt_waiting_child_cancel",
        "session_id": "session-4",
        "context_id": "ctx-waiting-child",
        "reason": "user_cancelled",
        "error": "User aborted while child skill was running",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "recorded"
    assert resp.json()["frame_id"] == root_frame_id

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert root.private_working_state["continuation_state"] == "INTERRUPTED"
    assert root.private_working_state["pending_recoverable_child_frame_id"] == child_frame_id
    assert root.private_working_state["pending_recoverable_child"]["skill_id"] == "child_skill"
    assert child.status == FrameStatus.RUNNING
    assert child.private_working_state["continuation_state"] == "INTERRUPTED"
    assert child.private_working_state["last_error"] == "User aborted while child skill was running"


async def test_returns_not_found_without_failing_cancel_path(client):
    resp = await client.post("/api/v1/frames/interruption", json={
        "taskId": "missing-task",
        "reason": "user_cancelled",
        "error": "Cancelled by user",
    })

    assert resp.status_code == 200
    assert resp.json()["status"] == "not_found"
