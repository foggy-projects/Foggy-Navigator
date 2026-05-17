"""Integration test for scripted E2E tool-call loops via OpenAI streaming."""

from __future__ import annotations

import asyncio
import json
import socket
import sys
import threading
import uuid
from pathlib import Path

import httpx
import pytest
import uvicorn
from httpx import ASGITransport, AsyncClient

MOCK_LLM_SRC = Path(__file__).resolve().parents[2] / "mock-llm-service" / "src"
if str(MOCK_LLM_SRC) not in sys.path:
    sys.path.insert(0, str(MOCK_LLM_SRC))

from langgraph_biz_worker.graphs import root_graph as root_graph_module
from langgraph_biz_worker.main import app as worker_app
from langgraph_biz_worker.models import FrameStatus, QueryEvent
from langgraph_biz_worker.routes import frame_interruption as frame_interruption_module
from mock_llm.main import app as mock_llm_app

LLM_SCRIPT_FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "llm_scripts"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


@pytest.fixture
def mock_llm_server():
    port = _free_port()
    server = uvicorn.Server(
        uvicorn.Config(
            mock_llm_app,
            host="127.0.0.1",
            port=port,
            log_level="warning",
        )
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    async def wait_ready() -> None:
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as client:
            for _ in range(50):
                try:
                    response = await client.get("/admin/health")
                    if response.status_code == 200:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(0.1)
        raise AssertionError("mock LLM server did not become ready")

    asyncio.run(wait_ready())
    try:
        yield f"http://127.0.0.1:{port}"
    finally:
        server.should_exit = True
        thread.join(timeout=5)


def _parse_worker_sse(raw_text: str) -> list[QueryEvent]:
    events: list[QueryEvent] = []
    for line in raw_text.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            payload = line[5:].strip()
            if payload:
                events.append(QueryEvent(**json.loads(payload)))
    return events


def _load_script_fixture(name: str, trace_id: str) -> dict:
    text = (LLM_SCRIPT_FIXTURE_DIR / name).read_text(encoding="utf-8")
    script = json.loads(text.replace("__TRACE_ID__", trace_id))
    script["traceId"] = trace_id
    return script


@pytest.mark.anyio
async def test_scripted_tool_call_streaming_reaches_second_turn(monkeypatch, mock_llm_server):
    """Run Worker route_skill -> LLM Skill Agent using real mock LLM HTTP/SSE."""
    trace_id = "worker-scripted-tool-loop-it"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-tool-loop",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "exception_triage",
                                        "instruction": f"route to skill next:{trace_id}:002",
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": f"Child skill deterministic tool loop ok next:{trace_id}:003",
                                        "structured_output": {
                                            "classification": "vehicle_delay",
                                            "recommended_action": "manual_dispatch",
                                            "confidence": 0.91,
                                        },
                                        "evidence_refs": ["e2e:scripted-tool-loop"],
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "TMS Navigator deterministic root skill loop ok",
                                        "structured_output": {
                                            "classification": "vehicle_delay",
                                            "recommended_action": "manual_dispatch",
                                            "confidence": 0.91,
                                        },
                                        "evidence_refs": ["e2e:scripted-root-skill-loop"],
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"run deterministic tool loop next:{trace_id}:001",
                "taskId": "task_scripted_tool_loop_it",
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {
                    "allowed_skills": [
                        {
                            "id": "exception_triage",
                            "description": "Analyze order exception and submit result.",
                        }
                    ],
                },
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    event_types = [event.type for event in events]
    assert event_types.count("skill_frame_open") == 2
    assert "tool_result" in event_types
    assert any(event.tool_name == "invoke_business_skill" for event in events)
    assert not any(
        "Unknown tool: invoke_business_skill" in (event.content or "")
        for event in events
    )
    assert sum(1 for event in events if event.type == "skill_result_submit") == 2
    result = next(event for event in events if event.type == "result")
    assert result.content == "TMS Navigator deterministic root skill loop ok"
    assert result.structured_output["classification"] == "vehicle_delay"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    cursors = [record["cursor"] for record in records]
    assert cursors == [f"next:{trace_id}:001", f"next:{trace_id}:002", f"next:{trace_id}:003"]
    first_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:001")
    second_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:002")
    third_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:003")
    assert first_turn["responseSummary"]["toolCalls"] == ["invoke_business_skill"]
    assert second_turn["responseSummary"]["toolCalls"] == ["submit_skill_result"]
    assert third_turn["responseSummary"]["toolCalls"] == ["submit_skill_result"]


@pytest.mark.anyio
async def test_scripted_root_skill_reuses_frame_across_tasks(monkeypatch, mock_llm_server):
    """Same session/context should reuse one persistent system.root frame."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-frame-reuse-{run_id}"
    session_id = f"sess-e2e-root-reuse-{run_id}"
    context_id = f"ctx-e2e-root-reuse-{run_id}"
    first_task_id = f"task_e2e_root_reuse_001_{run_id}"
    second_task_id = f"task_e2e_root_reuse_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-reuse",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "First root turn complete.",
                                        "structured_output": {"turn": 1},
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Second root turn complete.",
                                        "structured_output": {"turn": 2},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        first_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"first root turn next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )
        second_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"second root turn next:{trace_id}:002",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert second_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    second_events = _parse_worker_sse(second_response.text)

    first_open = next(event for event in first_events if event.type == "skill_frame_open")
    second_open = next(event for event in second_events if event.type == "skill_frame_open")
    assert first_open.skill_frame_id == second_open.skill_frame_id
    assert first_open.content == "Opening frame for skill: system.root"
    assert second_open.content == "Reusing frame for skill: system.root"

    first_result = next(event for event in first_events if event.type == "result")
    second_result = next(event for event in second_events if event.type == "result")
    assert first_result.content == "First root turn complete."
    assert second_result.content == "Second root turn complete."

    frame = root_graph_module.get_runtime().get_frame(first_open.skill_frame_id)
    assert frame is not None
    assert frame.conversation_id == context_id
    assert frame.origin_task_id == first_task_id
    assert frame.current_task_id == second_task_id
    assert frame.last_task_ids[-2:] == [first_task_id, second_task_id]


@pytest.mark.anyio
async def test_scripted_root_skill_active_plan_survives_across_tasks(monkeypatch, mock_llm_server):
    """Root active_plan should be persisted and injected into the next mock LLM request."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-active-plan-{run_id}"
    session_id = f"sess-e2e-active-plan-{run_id}"
    context_id = f"ctx-e2e-active-plan-{run_id}"
    first_task_id = f"task_e2e_active_plan_001_{run_id}"
    second_task_id = f"task_e2e_active_plan_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-active-plan",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Root plan created.",
                                        "structured_output": {
                                            "active_plan": {
                                                "goal": "handle multi-skill shipment issue",
                                                "status": "IN_PROGRESS",
                                                "steps": [
                                                    {"id": "inspect", "status": "DONE"},
                                                    {"id": "execute", "status": "PENDING"},
                                                ],
                                            },
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Root plan continued.",
                                        "structured_output": {
                                            "active_plan": {
                                                "goal": "handle multi-skill shipment issue",
                                                "status": "IN_PROGRESS",
                                                "steps": [
                                                    {"id": "inspect", "status": "DONE"},
                                                    {"id": "execute", "status": "DONE"},
                                                    {"id": "summarize", "status": "PENDING"},
                                                ],
                                            },
                                        },
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        first_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"先分析再处理运输异常 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )
        second_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:002",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert second_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    second_events = _parse_worker_sse(second_response.text)
    first_open = next(event for event in first_events if event.type == "skill_frame_open")
    second_open = next(event for event in second_events if event.type == "skill_frame_open")
    assert first_open.skill_frame_id == second_open.skill_frame_id
    assert second_open.content == "Reusing frame for skill: system.root"

    frame = root_graph_module.get_runtime().get_frame(first_open.skill_frame_id)
    assert frame is not None
    assert frame.private_working_state["active_plan"]["steps"][1]["id"] == "execute"
    assert frame.private_working_state["active_plan"]["steps"][1]["status"] == "DONE"
    assert frame.private_working_state["root_context_summary"]["active_plan"]["goal"] == (
        "handle multi-skill shipment issue"
    )

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    second_user_messages = [
        message["content"]
        for message in records[1]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Active task plan:" in content for content in second_user_messages)
    assert any("handle multi-skill shipment issue" in content for content in second_user_messages)
    assert any("Persistent root planning policy:" in content for content in second_user_messages)
    assert any("submit_skill_result.structured_output.active_plan" in content for content in second_user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_continues_after_recoverable_model_loop_failure(
    monkeypatch,
    mock_llm_server,
):
    """A no-submit mock LLM loop should keep system.root recoverable for the next task."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-continue-{run_id}"
    session_id = f"sess-e2e-root-continue-{run_id}"
    context_id = f"ctx-e2e-root-continue-{run_id}"
    first_task_id = f"task_e2e_root_continue_001_{run_id}"
    second_task_id = f"task_e2e_root_continue_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-continue",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {"content": f"No tool yet next:{trace_id}:002"},
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {"content": "Still no valid submit."},
                    },
                    {
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Continued root turn complete.",
                                        "structured_output": {"continued": True},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_skill_max_iterations", 2)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        failed_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"start and fail recoverably next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:003",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert failed_response.status_code == 200
    assert continued_response.status_code == 200
    failed_events = _parse_worker_sse(failed_response.text)
    continued_events = _parse_worker_sse(continued_response.text)

    failed_open = next(event for event in failed_events if event.type == "skill_frame_open")
    continued_open = next(event for event in continued_events if event.type == "skill_frame_open")
    assert failed_open.skill_frame_id == continued_open.skill_frame_id
    assert any(
        event.type == "error"
        and event.error == "LLM skill agent reached max iterations without valid submit"
        for event in failed_events
    )
    assert continued_open.content == "Reusing frame for skill: system.root"
    result = next(event for event in continued_events if event.type == "result")
    assert result.content == "Continued root turn complete."
    assert result.structured_output == {"continued": True}

    frame = root_graph_module.get_runtime().get_frame(failed_open.skill_frame_id)
    assert frame is not None
    assert frame.status.value == "RUNNING"
    assert frame.current_task_id == second_task_id
    assert "continuation_state" not in frame.private_working_state

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    cursors = [record["cursor"] for record in records]
    assert cursors == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]
    continue_record = records[2]
    user_messages = [
        message["content"]
        for message in continue_record["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Previous execution was interrupted." in content for content in user_messages)
    assert any("Reason: model_error" in content for content in user_messages)
    assert any(
        "LLM skill agent reached max iterations without valid submit" in content
        for content in user_messages
    )
    assert any("User's new instruction: 继续" in content for content in user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_continues_after_user_cancelled_interruption(
    monkeypatch,
    mock_llm_server,
):
    """Java-style cancel callback should make the root frame visible to the next mock LLM turn."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-cancel-continue-{run_id}"
    session_id = f"sess-e2e-root-cancel-{run_id}"
    context_id = f"ctx-e2e-root-cancel-{run_id}"
    first_task_id = f"task_e2e_root_cancel_001_{run_id}"
    second_task_id = f"task_e2e_root_cancel_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-cancel-continue",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Initial root turn complete.",
                                        "structured_output": {"turn": 1},
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Continued after user cancellation.",
                                        "structured_output": {"continued_after_cancel": True},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    frame_interruption_module.configure(
        root_graph_module.get_runtime(),
        root_graph_module.get_journal(),
    )

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        first_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"initial root turn next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Cancelled by user",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:002",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert interruption_response.status_code == 200
    assert interruption_response.json()["status"] == "recorded"
    assert continued_response.status_code == 200

    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)
    first_open = next(event for event in first_events if event.type == "skill_frame_open")
    continued_open = next(event for event in continued_events if event.type == "skill_frame_open")
    assert first_open.skill_frame_id == continued_open.skill_frame_id
    assert continued_open.content == "Reusing frame for skill: system.root"

    result = next(event for event in continued_events if event.type == "result")
    assert result.content == "Continued after user cancellation."
    assert result.structured_output == {"continued_after_cancel": True}

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    continue_record = records[1]
    user_messages = [
        message["content"]
        for message in continue_record["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Previous execution was interrupted." in content for content in user_messages)
    assert any("Reason: user_cancelled" in content for content in user_messages)
    assert any("Cancelled by user" in content for content in user_messages)
    assert any("User's new instruction: 继续" in content for content in user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_shelves_interruption_for_unrelated_task(
    monkeypatch,
    mock_llm_server,
):
    """An unrelated new request should archive the interruption instead of forcing continuation."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-shelve-{run_id}"
    session_id = f"sess-e2e-root-shelve-{run_id}"
    context_id = f"ctx-e2e-root-shelve-{run_id}"
    first_task_id = f"task_e2e_root_shelve_001_{run_id}"
    second_task_id = f"task_e2e_root_shelve_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-shelve",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Initial vehicle task started.",
                                        "structured_output": {"vehicle_task_started": True},
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "shelve_interrupted_frame",
                                    "args": {
                                        "summary": "Previous vehicle task was shelved; handled the new lookup.",
                                        "decision": "START_UNRELATED_NEW_TASK",
                                        "abandoned_interruption": {
                                            "summary": "Vehicle creation was cancelled before completion.",
                                        },
                                        "new_task": {"type": "lookup"},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    frame_interruption_module.configure(
        root_graph_module.get_runtime(),
        root_graph_module.get_journal(),
    )

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        first_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"创建车辆 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Cancelled by user",
            },
        )
        unrelated_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"帮我查一下新的订单列表 next:{trace_id}:002",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert interruption_response.status_code == 200
    assert interruption_response.json()["status"] == "recorded"
    assert unrelated_response.status_code == 200

    first_events = _parse_worker_sse(first_response.text)
    unrelated_events = _parse_worker_sse(unrelated_response.text)
    first_open = next(event for event in first_events if event.type == "skill_frame_open")
    unrelated_open = next(event for event in unrelated_events if event.type == "skill_frame_open")
    assert first_open.skill_frame_id == unrelated_open.skill_frame_id
    assert unrelated_open.content == "Reusing frame for skill: system.root"

    result = next(event for event in unrelated_events if event.type == "result")
    assert result.content == "Previous vehicle task was shelved; handled the new lookup."
    assert result.structured_output["continuation_decision"] == "START_UNRELATED_NEW_TASK"
    assert result.structured_output["intent_resolution"] == "START_UNRELATED_NEW_TASK"

    frame = root_graph_module.get_runtime().get_frame(first_open.skill_frame_id)
    assert frame is not None
    assert frame.current_task_id == second_task_id
    assert "continuation_state" not in frame.private_working_state
    history = frame.private_working_state["root_context_summary"]["interruption_history"]
    assert history[-1]["reason"] == "user_cancelled"
    assert history[-1]["resolution"] == "START_UNRELATED_NEW_TASK"
    assert history[-1]["abandoned_interruption"] == {
        "summary": "Vehicle creation was cancelled before completion.",
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    user_messages = [
        message["content"]
        for message in records[1]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("recoverable candidate, not a mandatory continuation" in content for content in user_messages)
    assert any("Recoverable focus:" in content for content in user_messages)
    assert any("Recoverable focus stack:" in content for content in user_messages)
    assert any("CONTINUE_PREVIOUS" in content for content in user_messages)
    assert any("ASK_CLARIFICATION" in content for content in user_messages)
    assert any("START_UNRELATED_NEW_TASK" in content for content in user_messages)
    assert any("intent_resolution" in content for content in user_messages)
    assert any("abandoned_interruption" in content for content in user_messages)
    assert any("shelve_interrupted_frame" in content for content in user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_resumes_interrupted_child_frame(
    monkeypatch,
    mock_llm_server,
):
    """A "continue" request should resume the exact child frame interrupted earlier."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-resume-child-{run_id}"
    session_id = f"sess-e2e-root-resume-child-{run_id}"
    context_id = f"ctx-e2e-root-resume-child-{run_id}"
    first_task_id = f"task_e2e_root_resume_child_001_{run_id}"
    second_task_id = f"task_e2e_root_resume_child_002_{run_id}"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-resume-child",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "resume_recoverable_child_skill",
                                    "args": {
                                        "instruction": f"继续子技能 next:{trace_id}:002",
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": f"Child resumed successfully next:{trace_id}:003",
                                        "structured_output": {
                                            "classification": "vehicle_delay",
                                            "recommended_action": "manual_dispatch",
                                            "confidence": 0.91,
                                        },
                                        "evidence_refs": ["e2e:resume-child"],
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Root completed after resuming child.",
                                        "structured_output": {"child_resumed": True},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    frame_interruption_module.configure(
        root_graph_module.get_runtime(),
        root_graph_module.get_journal(),
    )
    runtime = root_graph_module.get_runtime()
    root_frame_id = runtime.invoke_skill(
        task_id=first_task_id,
        skill_id="system.root",
        conversation_id=context_id,
        session_id=session_id,
        current_task_id=first_task_id,
        origin_task_id=first_task_id,
    )
    child_frame_id = runtime.invoke_child_skill(
        root_frame_id,
        "exception_triage",
        {"order_id": "ORD-1"},
    )

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Cancelled by user while child skill was running",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:001",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id},
            },
        )

    assert interruption_response.status_code == 200
    assert interruption_response.json()["status"] == "recorded"
    assert continued_response.status_code == 200

    events = _parse_worker_sse(continued_response.text)
    root_open = next(event for event in events if event.type == "skill_frame_open" and event.skill_id == "system.root")
    child_open = next(event for event in events if event.type == "skill_frame_open" and event.skill_id == "exception_triage")
    result = next(event for event in events if event.type == "result")
    assert root_open.skill_frame_id == root_frame_id
    assert root_open.content == "Reusing frame for skill: system.root"
    assert child_open.skill_frame_id == child_frame_id
    assert child_open.content == "Resuming frame for skill: exception_triage"
    assert result.content == "Root completed after resuming child."
    assert result.structured_output == {"child_resumed": True}

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert "pending_recoverable_child_frame_id" not in root.private_working_state
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert root.private_working_state["child_results"][child_frame_id]["structured_output"] == {
        "classification": "vehicle_delay",
        "recommended_action": "manual_dispatch",
        "confidence": 0.91,
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]
    root_user_messages = [
        message["content"]
        for message in records[0]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Pending child skill:" in content for content in root_user_messages)
    assert any("Recoverable focus:" in content for content in root_user_messages)
    assert any("Recoverable focus stack:" in content for content in root_user_messages)
    assert any("CONTINUE_PREVIOUS" in content for content in root_user_messages)
    assert any("ASK_CLARIFICATION" in content for content in root_user_messages)
    assert any("resume_recoverable_child_skill" in content for content in root_user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_real_smoke_fixture(monkeypatch, mock_llm_server):
    """Replay the accepted real-LLM smoke trace through the mock LLM service."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-real-smoke-{run_id}"
    session_id = f"sess-e2e-real-smoke-{run_id}"
    context_id = f"ctx-e2e-real-smoke-{run_id}"
    first_task_id = f"task_e2e_real_smoke_001_{run_id}"
    second_task_id = f"task_e2e_real_smoke_002_{run_id}"
    third_task_id = f"task_e2e_real_smoke_003_{run_id}"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json=_load_script_fixture("root_skill_real_smoke.json", trace_id),
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    frame_interruption_module.configure(
        root_graph_module.get_runtime(),
        root_graph_module.get_journal(),
    )

    base_context = {
        "contextId": context_id,
        "smokeRunId": run_id,
        "available_skills": [
            {
                "id": "exception_triage",
                "description": "Analyze exception orders using mock order and vehicle evidence.",
            }
        ],
        "order_id": "ORD-REAL-SMOKE-001",
    }
    llm_config = {
        "provider": "openai",
        "api_key": "sk-test",
        "base_url": f"{mock_llm_server}/v1",
        "model": "navigator-e2e-scripted",
        "temperature": 0,
    }

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        first_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": (
                    "请通过 exception_triage skill 分析异常订单 ORD-REAL-SMOKE-001，"
                    f"先收集证据，再给出处置建议。 next:{trace_id}:001"
                ),
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": base_context,
            },
        )
        first_interruption = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Real smoke synthetic cancellation after first turn.",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续刚才被中断的异常订单处理，沿用已有上下文给出下一步。 next:{trace_id}:006",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": base_context,
            },
        )
        second_interruption = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": second_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Real smoke synthetic cancellation before unrelated turn.",
            },
        )
        unrelated_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": (
                    "先不用处理刚才那个异常了。帮我看一个新的异常订单 "
                    f"ORD-REAL-SMOKE-NEW 是否需要人工介入。 next:{trace_id}:012"
                ),
                "taskId": third_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {**base_context, "order_id": "ORD-REAL-SMOKE-NEW"},
            },
        )

    assert first_response.status_code == 200
    assert first_interruption.status_code == 200
    assert first_interruption.json()["status"] == "recorded"
    assert continued_response.status_code == 200
    assert second_interruption.status_code == 200
    assert second_interruption.json()["status"] == "recorded"
    assert unrelated_response.status_code == 200

    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)
    unrelated_events = _parse_worker_sse(unrelated_response.text)

    first_root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    unrelated_root_open = next(
        event for event in unrelated_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert unrelated_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_root_open.content == "Reusing frame for skill: system.root"
    assert unrelated_root_open.content == "Reusing frame for skill: system.root"

    first_result = next(event for event in first_events if event.type == "result")
    assert first_result.structured_output["classification"] == "vehicle_delay"
    assert first_result.structured_output["recommended_action"] == "manual_dispatch"
    assert "frm_" not in (first_result.content or "")
    assert "lgt_" not in (first_result.content or "")

    continued_result = next(event for event in continued_events if event.type == "result")
    assert continued_result.structured_output["confidence"] == 0.95
    assert any(
        event.type == "tool_result" and "\"ok\": true" in (event.content or "")
        for event in continued_events
    )

    unrelated_result = next(event for event in unrelated_events if event.type == "result")
    assert unrelated_result.structured_output["intent_resolution"] == "START_UNRELATED_NEW_TASK"
    assert unrelated_result.structured_output["continuation_decision"] == "START_UNRELATED_NEW_TASK"
    assert unrelated_result.structured_output["abandoned_interruption"]["previous_order_id"] == "ORD-REAL-SMOKE-001"

    frame = root_graph_module.get_runtime().get_frame(first_root_open.skill_frame_id)
    assert frame is not None
    assert frame.status == FrameStatus.RUNNING
    assert frame.current_task_id == third_task_id
    history = frame.private_working_state["root_context_summary"]["interruption_history"]
    assert history[-2]["resolution"] == "TURN_COMPLETED"
    assert history[-1]["resolution"] == "START_UNRELATED_NEW_TASK"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:{index:03d}"
        for index in range(1, 13)
    ]
