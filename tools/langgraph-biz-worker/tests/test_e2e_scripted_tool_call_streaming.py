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
from langgraph_biz_worker.runtime.frame_execution_report import read_frame_execution_report
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


@pytest.fixture
def worker_http_server():
    port = _free_port()
    server = uvicorn.Server(
        uvicorn.Config(
            worker_app,
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
                    response = await client.get("/health")
                    if response.status_code == 200:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(0.1)
        raise AssertionError("worker HTTP server did not become ready")

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
async def test_scripted_child_waiting_user_input_resumes_same_frame(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A child WAITING_FOR_USER_INPUT turn should resume the same child frame next turn."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-await-user-{run_id}"
    session_id = f"sess-e2e-child-await-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-await-{run_id}"
    first_task_id = f"task_e2e_child_await_001_{run_id}"
    second_task_id = f"task_e2e_child_await_002_{run_id}"
    first_prompt = "请回复 1 或 2 选择工单类型。"
    second_prompt = "请继续补充工单标题。"

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / "tms-ticket-agent.yaml").write_text(
        """
id: tms-ticket-agent
name: TMS Ticket Agent
description: Collect ticket fields and submit a TMS work order.
input_schema:
  type: object
output_schema:
  type: object
  additionalProperties: true
promote_to_parent:
  - result_summary
  - structured_output
  - artifact_refs
  - evidence_refs
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-await-user",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"引导用户选择工单类型 next:{trace_id}:002",
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
                                        "summary": first_prompt,
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": first_prompt,
                                            "required_fields": ["ticket_type"],
                                        },
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
                                        "summary": second_prompt,
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": second_prompt,
                                            "required_fields": ["title"],
                                            "ticket_type": "运单异常件",
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
                "prompt": f"你可以帮我提交工单吗 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {
                    "contextId": context_id,
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "description": "Collect ticket fields and submit a TMS work order.",
                        }
                    ],
                },
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"1 next:{trace_id}:003",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert continued_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)

    first_root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert any(event.tool_name == "invoke_business_skill" for event in first_events)
    assert not any(event.tool_name == "invoke_business_skill" for event in continued_events)
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in continued_events)

    first_result = next(event for event in first_events if event.type == "result")
    continued_result = next(event for event in continued_events if event.type == "result")
    assert first_result.content == first_prompt
    assert continued_result.content == second_prompt
    assert continued_result.structured_output["required_fields"] == ["title"]

    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(first_root_open.skill_frame_id)
    child = runtime.get_frame(first_child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.WAITING_CHILD
    assert child.status == FrameStatus.AWAITING_USER
    assert root.current_task_id == second_task_id
    assert child.current_task_id == second_task_id
    assert root.private_working_state["active_focus_frame_id"] == child.frame_id
    assert root.private_working_state["active_focus_status"] == "AWAITING_USER"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]
    resumed_turn = records[-1]
    assert resumed_turn["responseSummary"]["toolCalls"] == ["submit_skill_result"]
    resumed_user_messages = [
        message["content"]
        for message in resumed_turn["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Previous child skill turn is waiting for user input." in content for content in resumed_user_messages)
    assert any(first_prompt in content for content in resumed_user_messages)
    assert any(f"Current user reply: 1 next:{trace_id}:003" in content for content in resumed_user_messages)


@pytest.mark.anyio
async def test_scripted_awaiting_child_completed_result_returns_directly(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A resumed awaiting-user child that completes should not re-enter root synthesis."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-final-direct-{run_id}"
    session_id = f"sess-e2e-child-final-direct-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-final-direct-{run_id}"
    first_task_id = f"task_e2e_child_final_direct_001_{run_id}"
    second_task_id = f"task_e2e_child_final_direct_002_{run_id}"
    first_prompt = "请补充工单类型、标题和详细描述。"
    final_summary = f"工单已创建成功，编号 TKT-E2E-DIRECT。 next:{trace_id}:004"

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / "tms-ticket-agent.yaml").write_text(
        """
id: tms-ticket-agent
name: TMS Ticket Agent
description: Collect ticket fields and submit a TMS work order.
input_schema:
  type: object
output_schema:
  type: object
  additionalProperties: true
promote_to_parent:
  - result_summary
  - structured_output
  - artifact_refs
  - evidence_refs
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-final-direct",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"引导用户补充工单字段 next:{trace_id}:002",
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
                                        "summary": first_prompt,
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": first_prompt,
                                            "required_fields": [
                                                "ticket_type",
                                                "title",
                                                "description",
                                            ],
                                        },
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
                                        "summary": final_summary,
                                        "structured_output": {
                                            "status": "COMPLETED",
                                            "ticket_id": "TKT-E2E-DIRECT",
                                            "ticket_status": "SUBMITTED",
                                            "remaining_work": [],
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:004",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "已完成操作。",
                                        "structured_output": {"status": "COMPLETED"},
                                    },
                                }
                            ],
                        },
                    },
                ],
            },
        )
        assert register.status_code == 200

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
                "prompt": f"帮我生成一个工单 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {
                    "contextId": context_id,
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "description": "Collect ticket fields and submit a TMS work order.",
                        }
                    ],
                },
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"平台反馈，标题是历史会话标题优化，描述是不要再显示未命名会话 next:{trace_id}:003",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert continued_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)

    first_result = next(event for event in first_events if event.type == "result")
    continued_result = next(event for event in continued_events if event.type == "result")
    assert first_result.content == first_prompt
    assert continued_result.content == final_summary
    assert continued_result.structured_output == {
        "status": "COMPLETED",
        "ticket_id": "TKT-E2E-DIRECT",
        "ticket_status": "SUBMITTED",
        "remaining_work": [],
    }

    assert not any(
        event.tool_name == "invoke_business_skill"
        for event in continued_events
    )
    assert sum(
        1 for event in continued_events
        if event.type == "skill_result_submit"
    ) == 1

    first_root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(first_root_open.skill_frame_id)
    child = runtime.get_frame(first_child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert "active_focus_frame_id" not in root.private_working_state

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]


@pytest.mark.anyio
async def test_scripted_awaiting_child_resume_creates_ticket_with_http_attachment_url(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """BUG-036: second-turn attachments must reach the resumed child as TMS URL refs."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-resume-attachment-url-{run_id}"
    session_id = f"sess-e2e-child-resume-attachment-url-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-resume-attachment-url-{run_id}"
    first_task_id = f"task_e2e_child_resume_attachment_url_001_{run_id}"
    second_task_id = f"task_e2e_child_resume_attachment_url_002_{run_id}"
    first_prompt = "请补充工单类型、标题和详细描述。"
    ticket_id = "TKT-E2E-ATTACHMENT-URL"
    attachment_id = "local/tenant-88800/org-88834/2026/05/20/d6376119804b42829ce1fef9e1222766.png"
    attachment_url = (
        "http://192.168.31.119:12580/x3-web/tenant/attachment/local/"
        "88800/88834/d6376119804b42829ce1fef9e1222766.png"
    )
    attachment_ref = {
        "attachmentId": attachment_id,
        "attachmentName": "image.png",
        "attachmentType": "IMAGE",
        "refType": "NAVIGATOR_CHAT",
        "attachmentUrl": attachment_url,
        "contentType": "image/png",
        "sizeBytes": 19397,
        "width": 330,
        "height": 706,
    }
    attachment = {
        "id": attachment_id,
        "name": "image.png",
        "mimeType": "image/png",
        "size": 19397,
        "kind": "image",
        "provider": "tms-bff",
        "url": attachment_url,
        "metadata": {
            "refType": "NAVIGATOR_CHAT",
            "width": 330,
            "height": 706,
        },
    }
    captured_calls = []

    def fake_invoke_business_function(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        captured_calls.append({
            "task_scoped_token": task_scoped_token,
            "function_id": function_id,
            "version": version,
            "input_data": input_data,
            "idempotency_key": idempotency_key,
        })
        return {
            "functionId": function_id,
            "status": "OK",
            "summary": f"ticket accepted next:{trace_id}:004",
            "data": {
                "ticketNo": ticket_id,
                "attachmentRefs": (input_data or {}).get("attachmentRefs"),
            },
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke_business_function,
    )

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / "tms-ticket-agent.yaml").write_text(
        """
id: tms-ticket-agent
name: TMS Ticket Agent
description: Collect ticket fields and submit a TMS work order.
input_schema:
  type: object
output_schema:
  type: object
  additionalProperties: true
promote_to_parent:
  - result_summary
  - structured_output
  - artifact_refs
  - evidence_refs
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-resume-attachment-url",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"引导用户补充平台反馈工单字段 next:{trace_id}:002",
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
                                        "summary": first_prompt,
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": first_prompt,
                                            "required_fields": [
                                                "ticket_type",
                                                "title",
                                                "description",
                                            ],
                                        },
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
                                    "name": "invoke_business_function",
                                    "args": {
                                        "function_id": "tms.ticket.createPlatformFeedback",
                                        "version": "v1",
                                        "input": {
                                            "subType": "SYSTEM_OPTIMIZATION",
                                            "title": "历史会话标题优化",
                                            "summary": "建议历史会话显示真实标题，不要总是未命名会话。",
                                            "subjectType": "TMS_PRODUCT",
                                            "subjectKey": "TMS_PRODUCT",
                                            "attachmentRefs": [attachment_ref],
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:004",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": f"工单已创建成功，编号 {ticket_id}。",
                                        "structured_output": {
                                            "status": "COMPLETED",
                                            "ticket_id": ticket_id,
                                            "ticket_status": "SUBMITTED",
                                            "attachment_handoff": True,
                                            "remaining_work": [],
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
                "prompt": f"帮我生成一个工单 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {
                    "contextId": context_id,
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "description": "Collect ticket fields and submit a TMS work order.",
                        }
                    ],
                },
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": (
                    "帮我提交一个系统优化建议，标题是历史会话标题优化，"
                    f"描述是不要再显示未命名会话，并附上图片 next:{trace_id}:003"
                ),
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
                "runtime_context": {"task_scoped_token": "btt-e2e"},
                "attachments": [attachment],
            },
        )

    assert first_response.status_code == 200
    assert continued_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)

    first_result = next(event for event in first_events if event.type == "result")
    continued_result = next(event for event in continued_events if event.type == "result")
    assert first_result.content == first_prompt
    assert continued_result.structured_output["ticket_id"] == ticket_id
    assert continued_result.structured_output["attachment_handoff"] is True

    first_root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert not any(event.tool_name == "invoke_business_skill" for event in continued_events)
    assert not any(event.tool_name == "analyze_attachment" for event in continued_events)
    assert any(event.tool_name == "invoke_business_function" for event in continued_events)

    assert len(captured_calls) == 1
    business_call = captured_calls[0]
    assert business_call["task_scoped_token"] == "btt-e2e"
    assert business_call["function_id"] == "tms.ticket.createPlatformFeedback"
    assert business_call["version"] == "v1"
    payload = business_call["input_data"]
    assert payload["attachmentRefs"] == [attachment_ref]
    attachment_payload = payload["attachmentRefs"][0]
    assert attachment_payload["attachmentId"] == attachment_id
    assert attachment_payload["attachmentUrl"] == attachment_url
    assert attachment_payload["attachmentUrl"].startswith("http://")
    assert not attachment_payload["attachmentUrl"].startswith("local/")

    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(first_root_open.skill_frame_id)
    child = runtime.get_frame(first_child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert "active_focus_frame_id" not in root.private_working_state

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
        f"next:{trace_id}:004",
    ]
    resumed_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:003")
    resumed_user_messages = "\n".join(
        message["content"] or ""
        for message in resumed_turn["request"]["messages"]
        if message["role"] == "user"
    )
    assert "Previous child skill turn is waiting for user input." in resumed_user_messages
    assert "Attachments provided by upstream system:" in resumed_user_messages
    assert attachment_id in resumed_user_messages
    assert attachment_url in resumed_user_messages
    assert f"Current user reply:" in resumed_user_messages
    assert f"next:{trace_id}:003" in resumed_user_messages


@pytest.mark.anyio
async def test_client_detach_then_next_turn_reuses_persistent_frame(
    monkeypatch,
    mock_llm_server,
    worker_http_server,
):
    """A client read timeout must not cancel server work; next turn reuses the same root frame."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-detach-reattach-{run_id}"
    session_id = f"sess-e2e-detach-reattach-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-detach-reattach-{run_id}"
    first_task_id = f"task_e2e_detach_first_{run_id}"
    second_task_id = f"task_e2e_detach_second_{run_id}"
    first_summary = "First turn completed after client detach."
    second_summary = "Second turn reused the persistent root frame."

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-client-detach-reattach",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "delay_ms": 650,
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": first_summary,
                                        "structured_output": {
                                            "server_completed_after_detach": True,
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
                                        "summary": second_summary,
                                        "structured_output": {
                                            "next_turn_reused_context": True,
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
    llm_config = {
        "provider": "openai",
        "api_key": "sk-test",
        "base_url": f"{mock_llm_server}/v1",
        "model": "navigator-e2e-scripted",
        "temperature": 0,
    }
    common_context = {"contextId": context_id}

    async with httpx.AsyncClient(
        base_url=worker_http_server,
        timeout=httpx.Timeout(connect=2.0, read=0.2, write=2.0, pool=2.0),
    ) as worker_client:
        with pytest.raises(httpx.TimeoutException):
            await worker_client.post(
                "/api/v1/query",
                json={
                    "prompt": f"simulate client detach while server continues next:{trace_id}:001",
                    "taskId": first_task_id,
                    "session_id": session_id,
                    "model": "navigator-e2e-scripted",
                    "llm_config": llm_config,
                    "context": common_context,
                },
            )

    frame = None
    for _ in range(40):
        frames = root_graph_module.get_runtime().get_frames_by_conversation(context_id)
        frame = next((item for item in frames if item.skill_id == "system.root"), None)
        if frame and frame.result_summary == first_summary:
            break
        await asyncio.sleep(0.1)

    assert frame is not None
    assert frame.result_summary == first_summary
    assert frame.output["server_completed_after_detach"] is True
    first_frame_id = frame.frame_id

    async with httpx.AsyncClient(base_url=worker_http_server, timeout=5.0) as worker_client:
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"continue after detach next:{trace_id}:002",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": common_context,
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    frame_event = next(event for event in events if event.type == "skill_frame_open")
    assert frame_event.skill_frame_id == first_frame_id
    assert frame_event.content == "Reusing frame for skill: system.root"
    result = next(event for event in events if event.type == "result")
    assert result.content == second_summary
    assert result.structured_output["next_turn_reused_context"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    assert records[0]["responseSummary"]["responseDelayMs"] == 650


@pytest.mark.anyio
async def test_scripted_tms_ticket_child_receives_attachment_context(monkeypatch, mock_llm_server):
    """A TMS ticket child skill must see redacted attachments even if the tool call only has instruction."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-tms-ticket-attachment-{run_id}"
    task_id = f"task_e2e_tms_ticket_attachment_{run_id}"
    session_id = f"sess-e2e-tms-ticket-attachment-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-tms-ticket-attachment-{run_id}"
    client_app_id = "capp_2852124a-48f7-4098-9d5e-33eb736c4375"
    attachment = {
        "id": "att-028",
        "name": "image.png",
        "mimeType": "image/png",
        "size": 33485,
        "kind": "image",
        "provider": "tms-bff",
        "url": "https://tms.example.com/files/image.png?token=secret",
        "metadata": {
            "traceId": "trace-028",
            "accessToken": "hidden",
        },
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-tms-ticket-attachment",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"请创建一个平台反馈工单 next:{trace_id}:002",
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
                                        "summary": f"TMS ticket child received attachment context next:{trace_id}:003",
                                        "structured_output": {
                                            "ticket_ready": True,
                                            "attachment_id": "att-028",
                                        },
                                        "evidence_refs": ["attachment:att-028"],
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
                                        "summary": "Root completed TMS ticket attachment handoff.",
                                        "structured_output": {
                                            "child_skill": "tms-ticket-agent",
                                            "attachment_handoff": True,
                                        },
                                        "evidence_refs": ["e2e:tms-ticket-attachment"],
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
                "prompt": f"你可以帮我提个工单吗 next:{trace_id}:001",
                "taskId": task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {
                    "contextId": context_id,
                    "client_app_id": client_app_id,
                },
                "attachments": [attachment],
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert any(
        event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
        for event in events
    )
    result = next(event for event in events if event.type == "result")
    assert result.content == "Root completed TMS ticket attachment handoff."
    assert result.structured_output["attachment_handoff"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]

    child_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:002")
    child_user_prompt = "\n".join(
        message["content"] or ""
        for message in child_turn["request"]["messages"]
        if message["role"] == "user"
    )
    assert "SKILL_AGENT_START tms-ticket-agent" in child_user_prompt
    assert "Attachments provided by upstream system:" in child_user_prompt
    assert "att-028" in child_user_prompt
    assert "image.png" in child_user_prompt
    assert "tms-bff" in child_user_prompt
    assert "https://tms.example.com/files/image.png" in child_user_prompt
    assert "trace-028" in child_user_prompt
    assert "token=secret" not in child_user_prompt
    assert "accessToken" not in child_user_prompt
    assert "hidden" not in child_user_prompt


@pytest.mark.anyio
async def test_scripted_ticket_with_attachment_does_not_analyze_image_by_default(monkeypatch, mock_llm_server):
    """Attaching an image to a business operation should not trigger image analysis unless requested."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-ticket-attach-no-analysis-{run_id}"
    task_id = f"task_e2e_ticket_attach_no_analysis_{run_id}"
    session_id = f"sess-e2e-ticket-attach-no-analysis-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-ticket-attach-no-analysis-{run_id}"
    attachment = {
        "id": "att-no-analysis",
        "name": "exception-photo.png",
        "url": "https://tms.example.com/files/exception-photo.png?token=secret",
        "kind": "image",
    }
    captured_inputs = []

    def fake_invoke_business_function(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        captured_inputs.append(input_data)
        return {
            "functionId": function_id,
            "status": "OK",
            "summary": f"ticket accepted next:{trace_id}:002",
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke_business_function,
    )

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-ticket-attach-no-analysis",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_function",
                                    "args": {
                                        "function_id": "tms.ticket.create",
                                        "input": {
                                            "title": "异常工单",
                                            "description": "用户要求附图提交异常工单",
                                            "attachments": [{"id": "att-no-analysis"}],
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
                                        "summary": "Ticket submitted with original attachment.",
                                        "structured_output": {
                                            "ticket_submitted": True,
                                            "attachment_handoff": True,
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
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"帮我提交一个异常工单，然后附上图片 next:{trace_id}:001",
                "taskId": task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {"contextId": context_id, "client_app_id": "capp-e2e-attachment"},
                "runtime_context": {"task_scoped_token": "btt-e2e"},
                "attachments": [attachment],
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert not any(event.type == "tool_use" and event.tool_name == "analyze_attachment" for event in events)
    assert any(event.type == "tool_use" and event.tool_name == "invoke_business_function" for event in events)
    assert captured_inputs == [{
        "title": "异常工单",
        "description": "用户要求附图提交异常工单",
        "attachments": [{"id": "att-no-analysis"}],
    }]
    result = next(event for event in events if event.type == "result")
    assert result.structured_output["attachment_handoff"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    records = debug.json()
    assert [record["responseSummary"]["toolCalls"] for record in records] == [
        ["invoke_business_function"],
        ["submit_skill_result"],
    ]


@pytest.mark.anyio
async def test_scripted_ticket_from_image_content_analyzes_then_uses_result(monkeypatch, mock_llm_server):
    """When the user asks to use image content, the agent can analyze first and pass derived fields onward."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-ticket-image-analysis-{run_id}"
    task_id = f"task_e2e_ticket_image_analysis_{run_id}"
    session_id = f"sess-e2e-ticket-image-analysis-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-ticket-image-analysis-{run_id}"
    attachment = {
        "id": "att-vision",
        "name": "cargo-damage.JPG",
        "url": "https://tms.example.com/files/cargo-damage.JPG?token=secret",
    }
    captured_inputs = []

    def fake_invoke_business_function(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        captured_inputs.append(input_data)
        return {
            "functionId": function_id,
            "status": "OK",
            "summary": f"ticket accepted next:{trace_id}:004",
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke_business_function,
    )

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-ticket-image-analysis",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "analyze_attachment",
                                    "args": {
                                        "attachment_id": "att-vision",
                                        "purpose": f"识别异常照片并提取工单字段 next:{trace_id}:002",
                                        "expected_fields": ["exception_type", "damage_visible"],
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {
                            "content": json.dumps({
                                "summary": f"图片显示货物外包装破损 next:{trace_id}:003",
                                "extracted_text": "",
                                "extracted_fields": {
                                    "exception_type": "cargo_damage",
                                    "damage_visible": True,
                                },
                                "confidence": 0.91,
                                "warnings": [],
                            }, ensure_ascii=False),
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_function",
                                    "args": {
                                        "function_id": "tms.ticket.create",
                                        "input": {
                                            "title": "货损异常工单",
                                            "exception_type": "cargo_damage",
                                            "analysis_summary": "图片显示货物外包装破损",
                                            "attachments": [{"id": "att-vision"}],
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:004",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "Ticket submitted from image analysis.",
                                        "structured_output": {
                                            "ticket_submitted": True,
                                            "exception_type": "cargo_damage",
                                            "used_attachment_analysis": True,
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
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"根据图片内容提交异常工单 next:{trace_id}:001",
                "taskId": task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "vision_llm_config": {
                    "provider": "openai",
                    "api_key": "sk-vision-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-vision",
                    "temperature": 0,
                },
                "context": {"contextId": context_id, "client_app_id": "capp-e2e-attachment"},
                "runtime_context": {"task_scoped_token": "btt-e2e"},
                "attachments": [attachment],
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert any(event.type == "tool_use" and event.tool_name == "analyze_attachment" for event in events)
    assert any(event.type == "tool_use" and event.tool_name == "invoke_business_function" for event in events)
    analysis_result_event = next(
        event for event in events if event.type == "tool_result" and event.tool_name == "analyze_attachment"
    )
    analysis_result = json.loads(analysis_result_event.content or "{}")
    assert analysis_result["attachment_id"] == "att-vision"
    assert analysis_result["attachment_evidence"]["attachment_ids"] == ["att-vision"]
    assert analysis_result["attachment_evidence"]["attachment_url_digests"][0].startswith("sha256:")
    assert "token=secret" not in str(analysis_result["attachment_evidence"])
    assert "https://tms.example.com/files/cargo-damage.JPG" not in str(analysis_result["attachment_evidence"])
    assert captured_inputs == [{
        "title": "货损异常工单",
        "exception_type": "cargo_damage",
        "analysis_summary": "图片显示货物外包装破损",
        "attachments": [{"id": "att-vision"}],
    }]
    result = next(event for event in events if event.type == "result")
    assert result.structured_output["used_attachment_analysis"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
        f"next:{trace_id}:004",
    ]
    vision_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:002")
    assert vision_turn["model"] == "navigator-e2e-vision"
    vision_user_message = next(
        message for message in vision_turn["request"]["messages"] if message["role"] == "user"
    )
    assert isinstance(vision_user_message["content"], list)
    assert vision_user_message["content"][1]["type"] == "image_url"
    assert vision_user_message["content"][1]["image_url"]["url"] == (
        "https://tms.example.com/files/cargo-damage.JPG?token=secret"
    )


@pytest.mark.anyio
async def test_scripted_root_skill_reuses_frame_across_tasks(monkeypatch, mock_llm_server):
    """Same session/context should reuse one persistent system.root frame."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-frame-reuse-{run_id}"
    session_id = f"sess-e2e-root-reuse-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-reuse-{run_id}"
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
async def test_scripted_root_skill_second_turn_uses_runtime_visible_conversation(
    monkeypatch,
    mock_llm_server,
):
    """Second turn should expose BizWorker-owned runtime-visible conversation."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-recent-conversation-{run_id}"
    session_id = f"sess-e2e-root-recent-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-recent-{run_id}"
    first_task_id = f"task_e2e_root_recent_001_{run_id}"
    second_task_id = f"task_e2e_root_recent_002_{run_id}"
    previous_user_message = "我刚才提到工单 TMS-1001"
    previous_assistant_message = "工单 TMS-1001 当前需要继续处理"
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-recent-conversation",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": previous_assistant_message,
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
                                        "summary": "Second turn saw prior conversation.",
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
                "prompt": f"{previous_user_message} next:{trace_id}:001",
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
                "prompt": f"我上一个消息是什么 next:{trace_id}:002",
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
                "context": {
                    "contextId": context_id,
                    "recentConversation": [
                        {"role": "user", "content": previous_user_message},
                        {"role": "assistant", "content": previous_assistant_message},
                    ],
                },
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
    assert any("Runtime-visible conversation before this turn:" in content for content in second_user_messages)
    assert not any("Recent conversation before this turn:" in content for content in second_user_messages)
    assert any(f"user: {previous_user_message}" in content for content in second_user_messages)
    assert any(f"assistant: {previous_assistant_message}" in content for content in second_user_messages)
    assert any("User request: 我上一个消息是什么" in content for content in second_user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_active_plan_survives_across_tasks(monkeypatch, mock_llm_server):
    """Root active_plan should be persisted and injected into the next mock LLM request."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-active-plan-{run_id}"
    session_id = f"sess-e2e-active-plan-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-active-plan-{run_id}"
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
    context_id = f"bctx_20260520_ab_ctx-e2e-root-continue-{run_id}"
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
async def test_scripted_continue_prompt_resumes_child_waiting_user_input(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """If a child returns WAITING_USER, continue should resume that unfinished child frame."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-child-summary-continue-{run_id}"
    session_id = f"sess-e2e-child-summary-continue-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-summary-continue-{run_id}"
    first_task_id = f"task_e2e_child_summary_continue_001_{run_id}"
    second_task_id = f"task_e2e_child_summary_continue_002_{run_id}"
    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / "tms-ticket-agent.yaml").write_text(
        """
id: tms-ticket-agent
name: TMS Ticket Agent
description: Collect ticket fields and submit a TMS work order.
input_schema:
  type: object
output_schema:
  type: object
  additionalProperties: true
promote_to_parent:
  - result_summary
  - structured_output
  - artifact_refs
  - evidence_refs
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-child-summary-continue",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"提交工单，先确认必要字段 next:{trace_id}:002",
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
                                        "summary": "Need ticket fields",
                                        "structured_output": {
                                            "status": "WAITING_USER",
                                            "next_step": "请回复工单类型、运单号（如适用）、标题及详细描述。",
                                            "missing_fields": [
                                                "ticket_type",
                                                "title",
                                                "description",
                                            ],
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:004",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "继续上一轮工单字段收集。",
                                        "structured_output": {
                                            "intent_resolution": "CONTINUE_PREVIOUS",
                                            "status": "WAITING_USER",
                                            "next_step": "请回复工单类型、运单号（如适用）、标题及详细描述。",
                                            "missing_fields": [
                                                "ticket_type",
                                                "title",
                                                "description",
                                            ],
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
    monkeypatch.setattr(root_graph_module.settings, "llm_skill_max_iterations", 1)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        failed_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"你可以帮我提交工单吗 next:{trace_id}:001",
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
                "context": {
                    "contextId": context_id,
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "description": "Collect ticket fields and submit a TMS work order.",
                        }
                    ],
                },
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:004",
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

    root_open = next(event for event in failed_events if event.type == "skill_frame_open" and event.skill_id == "system.root")
    child_open_candidates = [
        event for event in failed_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    ]
    assert child_open_candidates, [
        {
            "type": event.type,
            "skill_id": event.skill_id,
            "tool_name": event.tool_name,
            "content": event.content,
            "error": event.error,
        }
        for event in failed_events
    ]
    child_open = child_open_candidates[0]
    continued_open = next(event for event in continued_events if event.type == "skill_frame_open" and event.skill_id == "system.root")
    assert continued_open.skill_frame_id == root_open.skill_frame_id
    assert continued_open.content == "Reusing frame for skill: system.root"
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_child_open.skill_frame_id == child_open.skill_frame_id
    assert not any(event.tool_name == "invoke_business_skill" for event in continued_events)
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in continued_events)
    assert not any(
        event.type == "error"
        and event.error == "LLM skill agent reached max iterations without valid submit"
        for event in failed_events + continued_events
    )

    result_events = [event for event in continued_events if event.type == "result"]
    assert result_events, [
        {
            "type": event.type,
            "skill_id": event.skill_id,
            "tool_name": event.tool_name,
            "content": event.content,
            "error": event.error,
        }
        for event in continued_events
    ]
    result = result_events[0]
    assert result.structured_output["intent_resolution"] == "CONTINUE_PREVIOUS"
    assert result.structured_output["status"] == "WAITING_USER"
    assert result.structured_output["missing_fields"] == [
        "ticket_type",
        "title",
        "description",
    ]

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:004",
    ]
    continue_user_messages = [
        message["content"]
        for message in records[-1]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any(
        "Previous child skill turn is waiting for user input." in content
        for content in continue_user_messages
    ), continue_user_messages
    assert any(child_open.skill_frame_id in content for content in continue_user_messages)
    assert any("tms-ticket-agent" in content for content in continue_user_messages)
    assert any("WAITING_USER" in content for content in continue_user_messages)
    assert any("Current user reply:" in content and f"next:{trace_id}:004" in content for content in continue_user_messages)
    assert any(
        "请回复工单类型、运单号（如适用）、标题及详细描述。" in content
        for content in continue_user_messages
    )
    assert any("missing_fields" in content for content in continue_user_messages)
    assert not any("read_frame_execution_report" in (event.tool_name or "") for event in continued_events)


@pytest.mark.anyio
async def test_scripted_tms_continue_after_cancel_uses_root_state_without_child_reinvoke(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """Replay the observed TMS cancel+continue shape with deterministic LLM turns."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-tms-cancel-continue-{run_id}"
    session_id = f"sess-e2e-tms-cancel-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-tms-cancel-{run_id}"
    first_task_id = f"task_e2e_tms_cancel_001_{run_id}"
    second_task_id = f"task_e2e_tms_cancel_002_{run_id}"
    child_summary = (
        "您好！我是 TMS 工单助手。请问您想创建哪种类型的工单？\n\n"
        "1. **运单异常件**：针对具体运单出现的异常情况（如破损、丢失、延误等）。\n"
        "2. **平台反馈**：针对系统使用、流程配置或其他非运单类问题。\n\n"
        "请回复数字 1 或 2。"
    )
    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / "tms-ticket-agent.yaml").write_text(
        """
id: tms-ticket-agent
name: TMS Ticket Agent
description: Collect ticket fields and submit a TMS work order.
input_schema:
  type: object
output_schema:
  type: object
  additionalProperties: true
promote_to_parent:
  - result_summary
  - structured_output
  - artifact_refs
  - evidence_refs
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)

    active_plan = {
        "current_step": "waiting_for_user_input",
        "pending_info": "ticket_type",
    }
    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-tms-cancel-continue",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_skill",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": f"引导用户选择工单类型 next:{trace_id}:002",
                                    },
                                },
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "已引导用户选择工单类型（运单异常件或平台反馈），等待用户进一步指示。",
                                        "structured_output": {
                                            "status": "WAITING_USER",
                                            "active_plan": active_plan,
                                        },
                                    },
                                },
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
                                        "summary": child_summary,
                                        "structured_output": {},
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
                                        "summary": "等待用户选择工单类型（运单异常件或平台反馈）以继续处理。",
                                        "structured_output": {
                                            "intent_resolution": "CONTINUE_PREVIOUS",
                                            "status": "WAITING_USER",
                                            "active_plan": active_plan,
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
    monkeypatch.setattr(root_graph_module.settings, "llm_skill_max_iterations", 3)
    frame_interruption_module.configure(
        root_graph_module.get_runtime(),
        root_graph_module.get_journal(),
    )

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
                "prompt": f"你可以帮我提交工单吗 next:{trace_id}:001",
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {
                    "contextId": context_id,
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "description": "Collect ticket fields and submit a TMS work order.",
                        }
                    ],
                },
            },
        )
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "user_cancelled",
                "error": "Cancelled by user after the ticket type prompt.",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"继续 next:{trace_id}:003",
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert interruption_response.status_code == 200
    assert interruption_response.json()["status"] == "recorded"
    assert continued_response.status_code == 200

    first_events = _parse_worker_sse(first_response.text)
    continued_events = _parse_worker_sse(continued_response.text)
    root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "system.root"
    )
    assert continued_root_open.skill_frame_id == root_open.skill_frame_id
    assert continued_root_open.content == "Reusing frame for skill: system.root"
    assert any(event.tool_name == "invoke_business_skill" for event in first_events)
    assert not any(event.tool_name == "invoke_business_skill" for event in continued_events)
    assert not any(event.tool_name == "read_frame_execution_report" for event in continued_events)
    assert not any(
        event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
        for event in continued_events
    )

    result = next(event for event in continued_events if event.type == "result")
    assert result.content == "等待用户选择工单类型（运单异常件或平台反馈）以继续处理。"
    assert result.structured_output == {
        "intent_resolution": "CONTINUE_PREVIOUS",
        "status": "WAITING_USER",
        "active_plan": active_plan,
    }

    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(root_open.skill_frame_id)
    child = runtime.get_frame(child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert root.current_task_id == second_task_id
    assert root.origin_task_id == first_task_id
    assert child.task_id == first_task_id
    assert root.private_working_state["active_plan"] == active_plan
    latest_child = root.private_working_state["latest_child_result_summary"]
    assert latest_child["frame_id"] == child_open.skill_frame_id
    assert latest_child["skill_id"] == "tms-ticket-agent"
    assert latest_child["result_summary"] == child_summary
    assert latest_child["execution_report_ref"] == (
        f"frame-report://{first_task_id}/{child_open.skill_frame_id}"
    )

    continued_report = read_frame_execution_report(
        root_graph_module._data_root,
        task_id=second_task_id,
        frame_id=root_open.skill_frame_id,
        mode="summary",
    )
    assert continued_report["ok"] is True
    child_reports = continued_report["summary"]["child_reports"]
    assert child_reports[0]["status"] == "COMPLETED"
    assert child_reports[0]["report_ref"] == f"frame-report://{first_task_id}/{child_open.skill_frame_id}"
    assert child_reports[0]["summary"] == child_summary

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]
    continue_user_messages = [
        message["content"]
        for message in records[-1]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Previous execution was interrupted." in content for content in continue_user_messages)
    assert any("Reason: user_cancelled" in content for content in continue_user_messages)
    assert any("Continuation summary from promoted child result:" in content for content in continue_user_messages)
    assert any("TMS 工单助手" in content for content in continue_user_messages)
    assert any("运单异常件" in content and "平台反馈" in content for content in continue_user_messages)
    assert any("Active task plan:" in content for content in continue_user_messages)
    assert any("pending_info" in content and "ticket_type" in content for content in continue_user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_continues_after_user_cancelled_interruption(
    monkeypatch,
    mock_llm_server,
):
    """Java-style cancel callback should make the root frame visible to the next mock LLM turn."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-cancel-continue-{run_id}"
    session_id = f"sess-e2e-root-cancel-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-cancel-{run_id}"
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
    context_id = f"bctx_20260520_ab_ctx-e2e-root-shelve-{run_id}"
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
    """A "continue" request should resume the child focus before the root loop."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-resume-child-{run_id}"
    session_id = f"sess-e2e-root-resume-child-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-resume-child-{run_id}"
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
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": f"Child resumed successfully next:{trace_id}:002",
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
                        "cursor": f"next:{trace_id}:002",
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
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in events)
    assert not any(event.tool_name == "invoke_business_skill" for event in events)
    assert not any(event.tool_name == "read_frame_execution_report" for event in events)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    child_user_messages = [
        message["content"]
        for message in records[0]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("User request: 继续" in content for content in child_user_messages)
    assert any("Skill input:" in content and "ORD-1" in content for content in child_user_messages)
    root_user_messages = [
        message["content"]
        for message in records[1]["request"]["messages"]
        if message["role"] == "user"
    ]
    assert any("Continuation summary from promoted child result:" in content for content in root_user_messages)
    assert any(child_frame_id in content for content in root_user_messages)
    assert not any("Pending child skill:" in content for content in root_user_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_real_smoke_fixture(monkeypatch, mock_llm_server):
    """Replay the accepted smoke trace and avoid duplicate child work on continue."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-real-smoke-{run_id}"
    session_id = f"sess-e2e-real-smoke-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-real-smoke-{run_id}"
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
    assert continued_result.structured_output["confidence"] == 0.9
    assert continued_result.structured_output["continued_from_existing_child_result"] is True
    assert any(
        event.type == "skill_result_submit" and "\"ok\": true" in (event.content or "")
        for event in continued_events
    )
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in continued_events)
    assert not any(event.tool_name == "invoke_business_skill" for event in continued_events)
    assert not any(event.tool_name == "read_frame_execution_report" for event in continued_events)

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
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
        f"next:{trace_id}:004",
        f"next:{trace_id}:005",
        f"next:{trace_id}:006",
        f"next:{trace_id}:012",
    ]
    continued_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:006")
    assert continued_turn["responseSummary"]["toolCalls"] == ["submit_skill_result"]
