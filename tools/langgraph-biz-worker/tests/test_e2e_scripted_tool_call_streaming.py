"""Integration test for scripted E2E tool-call loops via OpenAI streaming."""

from __future__ import annotations

# ruff: noqa: E402

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
from langgraph_biz_worker.runtime.file_layout import session_data_dir
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


def _record_messages(record: dict, role: str) -> list[str]:
    return [
        message.get("content") or ""
        for message in record["request"]["messages"]
        if message["role"] == role
    ]


def _record_role_messages(record: dict, role: str) -> list[dict]:
    return [
        message
        for message in record["request"]["messages"]
        if message["role"] == role
    ]


def _message_tool_call_names(message: dict) -> list[str]:
    names: list[str] = []
    for call in message.get("tool_calls") or message.get("toolCalls") or []:
        if isinstance(call, dict):
            function = call.get("function")
            if isinstance(function, dict) and isinstance(function.get("name"), str):
                names.append(function["name"])
            elif isinstance(call.get("name"), str):
                names.append(call["name"])
    return names


def _record_tool_call_names(record: dict, role: str = "assistant") -> list[str]:
    names: list[str] = []
    for message in _record_role_messages(record, role):
        names.extend(_message_tool_call_names(message))
    return names


def _llm_submission_payloads(context_id: str) -> list[dict]:
    log_dir = _session_dir(context_id) / "logs" / "llm-submissions"
    return [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(log_dir.glob("*.json"))
    ]


def _session_dir(context_id: str) -> Path:
    return session_data_dir(
        Path(root_graph_module._data_root),
        ("1970", "01", "01"),
        context_id,
        require_standard_context=True,
    )


def _runtime_message_events(
    context_id: str,
    *,
    task_id: str | None = None,
    frame_id: str | None = None,
) -> list[dict]:
    log_dir = _session_dir(context_id) / "logs" / "runtime-message-events"
    if task_id and frame_id:
        pattern = f"{task_id}_{frame_id}.jsonl"
    elif frame_id:
        pattern = f"*_{frame_id}.jsonl"
    else:
        pattern = "*.jsonl"
    events: list[dict] = []
    for path in sorted(log_dir.glob(pattern)):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                events.append(json.loads(line))
    return events


def _runtime_initial_roles(events: list[dict]) -> list[str]:
    return [
        event["message"]["role"]
        for event in events
        if event.get("eventType") == "message" and event.get("phase") == "initial"
    ]


def _runtime_tool_call_names(events: list[dict]) -> list[str]:
    return [
        event["toolCall"]["name"]
        for event in events
        if event.get("eventType") == "assistant_tool_call"
    ]


def _runtime_checkpoints(events: list[dict]) -> list[str]:
    return [
        event["checkpoint"]
        for event in events
        if event.get("eventType") == "checkpoint"
    ]


def _llm_submission_for(
    payloads: list[dict],
    *,
    task_id: str,
    skill_id: str | None = None,
    iteration: int | None = None,
) -> dict:
    matches = []
    for payload in payloads:
        meta = payload.get("meta") or {}
        if meta.get("taskId") != task_id:
            continue
        if skill_id is not None and meta.get("skillId") != skill_id:
            continue
        if iteration is not None and meta.get("iteration") != iteration:
            continue
        matches.append(payload)
    assert matches, f"missing llm submission task_id={task_id} skill_id={skill_id} iteration={iteration}"
    return matches[-1]


def _submission_messages(payload: dict) -> list[dict]:
    return list(((payload.get("body") or {}).get("messages") or []))


def _submission_texts(payload: dict, message_type: str) -> list[str]:
    return [
        message.get("content") or ""
        for message in _submission_messages(payload)
        if (message.get("type") or message.get("role")) == message_type
    ]


def _submission_role_sequence(payload: dict) -> list[str]:
    return [
        message.get("type") or message.get("role") or ""
        for message in _submission_messages(payload)
    ]


def _submission_tool_call_names(payload: dict, message_type: str = "ai") -> list[str]:
    names: list[str] = []
    for message in _submission_messages(payload):
        if (message.get("type") or message.get("role")) != message_type:
            continue
        names.extend(_message_tool_call_names(message))
    return names


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
                                    "name": "invoke_business_agent",
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
    assert any(event.tool_name == "invoke_business_agent" for event in events)
    assert not any(
        "Unknown tool: invoke_business_agent" in (event.content or "")
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
    tool_loop_records = [
        record for record in records
        if record["request"].get("tools")
    ]
    cursors = [record["cursor"] for record in tool_loop_records]
    assert cursors == [f"next:{trace_id}:001", f"next:{trace_id}:002", f"next:{trace_id}:003"]
    first_turn = next(record for record in tool_loop_records if record["cursor"] == f"next:{trace_id}:001")
    second_turn = next(record for record in tool_loop_records if record["cursor"] == f"next:{trace_id}:002")
    third_turn = next(record for record in tool_loop_records if record["cursor"] == f"next:{trace_id}:003")
    assert first_turn["responseSummary"]["toolCalls"] == ["invoke_business_agent"]
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
                                    "name": "invoke_business_agent",
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
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert any(event.tool_name == "invoke_business_agent" for event in first_events)
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
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
    resumed_system_messages = _record_messages(resumed_turn, "system")
    resumed_user_messages = _record_messages(resumed_turn, "user")
    resumed_assistant_messages = _record_role_messages(resumed_turn, "assistant")
    resumed_tool_messages = _record_role_messages(resumed_turn, "tool")
    assert resumed_user_messages == [
        f"引导用户选择工单类型 next:{trace_id}:002",
        f"1 next:{trace_id}:003",
    ]
    assert any("submit_skill_result" in _message_tool_call_names(message) for message in resumed_assistant_messages)
    assert any("WAITING_FOR_USER_INPUT" in (message.get("content") or "") for message in resumed_tool_messages)
    assert any("上一个子 Agent frame 正在等待用户输入。" in content for content in resumed_system_messages)
    assert any(first_prompt in content for content in resumed_system_messages)
    assert any("当前 human message 是用户对上次提示的回复。" in content for content in resumed_system_messages)


@pytest.mark.anyio
async def test_scripted_llm_submission_log_captures_awaiting_child_resume_protocol(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """Awaiting-user child resume should persist the exact child tool protocol replayed to the LLM."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-resume-log-{run_id}"
    session_id = f"sess-e2e-child-resume-log-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-resume-log-{run_id}"
    first_task_id = f"task_e2e_child_resume_log_001_{run_id}"
    second_task_id = f"task_e2e_child_resume_log_002_{run_id}"
    first_prompt = f"帮我提交一个工单 next:{trace_id}:001"
    child_instruction = f"收集工单必要字段 next:{trace_id}:002"
    second_prompt = f"类型平台反馈，标题日志对账，描述验证恢复协议 next:{trace_id}:003"

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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-resume-log",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_agent",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": child_instruction,
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
                                        "summary": "请提供工单类型、标题、详细描述。",
                                        "structured_output": {
                                            "status": "WAITING_USER",
                                            "next_step": "请提供工单类型、标题、详细描述。",
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
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "工单字段已收齐。",
                                        "structured_output": {
                                            "ticket_ready": True,
                                            "ticket_type": "平台反馈",
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
                "prompt": first_prompt,
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
                "prompt": second_prompt,
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

    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert continued_child_open.content == "Resuming frame for agent: tms-ticket-agent"
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)

    continued_result = next(event for event in continued_events if event.type == "result")
    assert continued_result.content == "工单字段已收齐。"
    assert continued_result.structured_output == {
        "ticket_ready": True,
        "ticket_type": "平台反馈",
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
        f"next:{trace_id}:003",
    ]
    resumed_record = records[-2]
    assert _record_messages(resumed_record, "user") == [child_instruction, second_prompt]
    assert any(
        "submit_skill_result" in _message_tool_call_names(message)
        for message in _record_role_messages(resumed_record, "assistant")
    )
    assert any(
        "WAITING_FOR_USER_INPUT" in (message.get("content") or "")
        for message in _record_role_messages(resumed_record, "tool")
    )

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 4
    resumed_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="tms-ticket-agent",
    )
    assert _submission_role_sequence(resumed_payload) == [
        "system",
        "human",
        "ai",
        "tool",
        "human",
    ]
    assert _submission_texts(resumed_payload, "human") == [child_instruction, second_prompt]
    assert _submission_tool_call_names(resumed_payload) == ["submit_skill_result"]
    assert any(
        "WAITING_FOR_USER_INPUT" in text
        for text in _submission_texts(resumed_payload, "tool")
    )
    assert any(
        "当前 human message 是用户对上次提示的回复。" in text
        for text in _submission_texts(resumed_payload, "system")
    )

    root_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="conversation.root",
    )
    assert _submission_role_sequence(root_payload) == ["system", "human", "ai", "human"]
    assert _submission_texts(root_payload, "human") == [first_prompt, second_prompt]
    assert _submission_texts(root_payload, "ai") == ["请提供工单类型、标题、详细描述。"]

    first_child_events = _runtime_message_events(
        context_id,
        task_id=first_task_id,
        frame_id=first_child_open.skill_frame_id,
    )
    assert _runtime_initial_roles(first_child_events) == ["system", "user"]
    assert _runtime_tool_call_names(first_child_events) == ["submit_skill_result"]
    assert "suspended" in _runtime_checkpoints(first_child_events)
    assert any(
        "WAITING_FOR_USER_INPUT" in ((event.get("message") or {}).get("content") or "")
        for event in first_child_events
        if event.get("eventType") == "tool_result"
    )

    resumed_child_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=first_child_open.skill_frame_id,
    )
    assert _runtime_initial_roles(resumed_child_events) == [
        "system",
        "user",
        "assistant",
        "tool",
        "user",
    ]
    resumed_initial_messages = [
        event["message"]
        for event in resumed_child_events
        if event.get("eventType") == "message" and event.get("phase") == "initial"
    ]
    assert resumed_initial_messages[2]["toolCalls"][0]["name"] == "submit_skill_result"
    assert "WAITING_FOR_USER_INPUT" in resumed_initial_messages[3]["content"]


@pytest.mark.anyio
async def test_scripted_awaiting_child_can_handoff_cancel_to_parent(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A resumed awaiting-user child can exit via handoff without reopening the skill."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-handoff-cancel-{run_id}"
    session_id = f"sess-e2e-child-handoff-cancel-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-handoff-cancel-{run_id}"
    first_task_id = f"task_e2e_child_handoff_cancel_001_{run_id}"
    second_task_id = f"task_e2e_child_handoff_cancel_002_{run_id}"
    child_instruction = f"收集工单必要字段 next:{trace_id}:002"
    cancel_prompt = f"取消这个工单任务，回到主对话 next:{trace_id}:003"
    handoff_summary = "已取消当前工单流程，回到主对话。"

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
    root_graph_module._skill_registry.load()
    root_graph_module._ensure_system_root_skill()
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-handoff-cancel",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_agent",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": child_instruction,
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
                                        "summary": "请提供工单类型、标题、详细描述。",
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": "请提供工单类型、标题、详细描述。",
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
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "handoff_to_parent",
                                    "args": {
                                        "summary": handoff_summary,
                                        "reason": "USER_CANCELLED",
                                        "intent_resolution": "RETURN_TO_PARENT",
                                        "requires_parent_synthesis": False,
                                        "structured_output": {
                                            "cancelled_ticket_flow": True,
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
                "prompt": f"帮我提交一个工单 next:{trace_id}:001",
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
                "prompt": cancel_prompt,
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
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    result = next(event for event in continued_events if event.type == "result")
    close_event = next(event for event in continued_events if event.type == "skill_frame_close")

    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in continued_events)
    assert any(event.tool_name == "handoff_to_parent" for event in continued_events)
    assert close_event.skill_frame_id == first_child_open.skill_frame_id
    assert result.content == handoff_summary
    assert result.structured_output["status"] == "HANDOFF_TO_PARENT"
    assert result.structured_output["handoff_to_parent"] is True
    assert result.structured_output["requires_parent_synthesis"] is False

    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(first_root_open.skill_frame_id)
    child = runtime.get_frame(first_child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert "active_focus_frame_id" not in root.private_working_state
    assert "active_focus_status" not in root.private_working_state
    assert root.private_working_state["turn_results"][-1]["summary"] == handoff_summary
    assert root.private_working_state["child_results"][child.frame_id]["structured_output"]["status"] == (
        "HANDOFF_TO_PARENT"
    )

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
    ]
    assert records[-1]["responseSummary"]["toolCalls"] == ["handoff_to_parent"]

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 3
    resumed_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="tms-ticket-agent",
    )
    assert _submission_role_sequence(resumed_payload) == [
        "system",
        "human",
        "ai",
        "tool",
        "human",
    ]
    assert _submission_texts(resumed_payload, "human") == [child_instruction, cancel_prompt]
    system_text = "\n".join(_submission_texts(resumed_payload, "system"))
    assert "子 Agent 退出策略:" in system_text
    assert "handoff_to_parent" in system_text

    resumed_child_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=first_child_open.skill_frame_id,
    )
    assert _runtime_initial_roles(resumed_child_events) == [
        "system",
        "user",
        "assistant",
        "tool",
        "user",
    ]
    assert _runtime_tool_call_names(resumed_child_events) == ["handoff_to_parent"]
    assert "frame_completed" in _runtime_checkpoints(resumed_child_events)


@pytest.mark.anyio
async def test_scripted_child_handoff_can_request_root_synthesis(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A child handoff with parent synthesis should resume Root on the same user turn."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-child-handoff-synthesis-{run_id}"
    session_id = f"sess-e2e-child-handoff-synthesis-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-child-handoff-synthesis-{run_id}"
    first_task_id = f"task_e2e_child_handoff_synthesis_001_{run_id}"
    second_task_id = f"task_e2e_child_handoff_synthesis_002_{run_id}"
    child_instruction = f"收集工单必要字段 next:{trace_id}:002"
    switch_prompt = f"先不提交工单了，帮我总结刚才的状态 next:{trace_id}:003"
    handoff_summary = "用户切换意图，当前工单收集流程交回 Root 处理。"
    root_summary = "已切换回主对话：刚才的工单流程停留在字段收集阶段，尚未提交。"

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
  - requires_parent_synthesis
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)
    root_graph_module._skill_registry.load()
    root_graph_module._ensure_system_root_skill()
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-child-handoff-synthesis",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_agent",
                                    "args": {
                                        "skill_id": "tms-ticket-agent",
                                        "instruction": child_instruction,
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
                                        "summary": "请提供工单类型、标题、详细描述。",
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": "请提供工单类型、标题、详细描述。",
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
                        "cursor": f"next:{trace_id}:003",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "handoff_to_parent",
                                    "args": {
                                        "summary": handoff_summary,
                                        "reason": "CHANGE_TOPIC",
                                        "intent_resolution": "ASK_PARENT_TO_DECIDE",
                                        "requires_parent_synthesis": True,
                                        "parent_instruction": "请 Root 根据用户新意图综合答复。",
                                        "structured_output": {
                                            "handoff_kind": "change_topic",
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
                                        "summary": root_summary,
                                        "structured_output": {
                                            "root_synthesized_after_handoff": True,
                                            "previous_flow": "ticket_field_collection",
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
                "prompt": f"帮我提交一个工单 next:{trace_id}:001",
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
                "prompt": switch_prompt,
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
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    close_event = next(event for event in continued_events if event.type == "skill_frame_close")
    result = next(event for event in continued_events if event.type == "result")

    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert close_event.skill_frame_id == first_child_open.skill_frame_id
    assert any(event.tool_name == "handoff_to_parent" for event in continued_events)
    assert result.content == root_summary
    assert result.structured_output["root_synthesized_after_handoff"] is True
    assert result.structured_output.get("status") != "HANDOFF_TO_PARENT"

    runtime = root_graph_module.get_runtime()
    root = runtime.get_frame(first_child_open.parent_frame_id)
    child = runtime.get_frame(first_child_open.skill_frame_id)
    assert root is not None
    assert child is not None
    assert child.status == FrameStatus.COMPLETED
    assert root.status == FrameStatus.RUNNING
    assert "active_focus_frame_id" not in root.private_working_state
    child_result = root.private_working_state["child_results"][child.frame_id]
    assert child_result["structured_output"]["requires_parent_synthesis"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
        f"next:{trace_id}:003",
        f"next:{trace_id}:003",
    ]
    assert [record["responseSummary"]["toolCalls"] for record in records] == [
        ["invoke_business_agent"],
        ["submit_skill_result"],
        ["handoff_to_parent"],
        ["submit_skill_result"],
    ]

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 4
    child_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="tms-ticket-agent",
    )
    root_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="conversation.root",
    )
    assert _submission_role_sequence(child_payload) == [
        "system",
        "human",
        "ai",
        "tool",
        "human",
    ]
    assert _submission_texts(child_payload, "human") == [child_instruction, switch_prompt]
    assert _submission_tool_call_names(child_payload) == ["submit_skill_result"]
    assert _submission_texts(root_payload, "human")[-1] == switch_prompt
    assert any(
        "HANDOFF_TO_PARENT" in text or handoff_summary in text
        for text in _submission_texts(root_payload, "system")
    )

    child_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=first_child_open.skill_frame_id,
    )
    root_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=first_child_open.parent_frame_id,
    )
    assert _runtime_tool_call_names(child_events) == ["handoff_to_parent"]
    assert _runtime_tool_call_names(root_events) == ["submit_skill_result"]
    assert "frame_completed" in _runtime_checkpoints(child_events)
    assert "persistent_turn_completed" in _runtime_checkpoints(root_events)


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
                                    "name": "invoke_business_agent",
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
        event.tool_name == "invoke_business_agent"
        for event in continued_events
    )
    assert sum(
        1 for event in continued_events
        if event.type == "skill_result_submit"
    ) == 1

    first_root_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
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
                                    "name": "invoke_business_agent",
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
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    first_child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_child_open.skill_frame_id == first_child_open.skill_frame_id
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
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
    resumed_system_messages = "\n".join(_record_messages(resumed_turn, "system"))
    resumed_user_messages = _record_messages(resumed_turn, "user")
    resumed_assistant_messages = _record_role_messages(resumed_turn, "assistant")
    resumed_tool_messages = _record_role_messages(resumed_turn, "tool")
    assert resumed_user_messages == [
        f"引导用户补充平台反馈工单字段 next:{trace_id}:002",
        "帮我提交一个系统优化建议，标题是历史会话标题优化，"
        f"描述是不要再显示未命名会话，并附上图片 next:{trace_id}:003",
    ]
    assert any("submit_skill_result" in _message_tool_call_names(message) for message in resumed_assistant_messages)
    assert any("WAITING_FOR_USER_INPUT" in (message.get("content") or "") for message in resumed_tool_messages)
    assert "上一个子 Agent frame 正在等待用户输入。" in resumed_system_messages
    assert "上游系统提供的附件:" in resumed_system_messages
    assert attachment_id in resumed_system_messages
    assert attachment_url in resumed_system_messages
    assert "当前 human message 是用户对上次提示的回复。" in resumed_system_messages


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
    assert frame_event.content == "Reusing conversation root frame"
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
                                    "name": "invoke_business_agent",
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
    child_system_prompt = "\n".join(_record_messages(child_turn, "system"))
    child_user_prompt = "\n".join(_record_messages(child_turn, "user"))
    assert "当前业务 Agent frame 上下文:" in child_system_prompt
    assert "SKILL_AGENT_START" not in child_system_prompt
    assert "SKILL_AGENT_START" not in child_user_prompt
    assert "上游系统提供的附件:" in child_system_prompt
    assert "att-028" in child_system_prompt
    assert "image.png" in child_system_prompt
    assert "tms-bff" in child_system_prompt
    assert "https://tms.example.com/files/image.png" in child_system_prompt
    assert "trace-028" in child_system_prompt
    assert "token=secret" not in child_system_prompt
    assert "accessToken" not in child_system_prompt
    assert "hidden" not in child_system_prompt


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
async def test_scripted_llm_submission_log_captures_business_function_tool_protocol(
    monkeypatch,
    mock_llm_server,
):
    """Persisted LLM body should include raw tool_call and tool_result protocol messages."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-llm-log-function-{run_id}"
    task_id = f"task_e2e_llm_log_function_{run_id}"
    session_id = f"sess-e2e-llm-log-function-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-llm-log-function-{run_id}"
    captured_inputs = []

    def fake_invoke_business_function(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        captured_inputs.append({
            "function_id": function_id,
            "version": version,
            "input_data": input_data,
            "idempotency_key": idempotency_key,
        })
        return {
            "functionId": function_id,
            "status": "OK",
            "summary": f"业务函数已受理 next:{trace_id}:002",
            "ticketNo": "BUG-LLM-LOG-001",
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
                "scenarioId": "worker-llm-log-function",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "invoke_business_function",
                                    "args": {
                                        "function_id": "tms.ticket.createPlatformFeedback",
                                        "version": "v1",
                                        "input": {
                                            "title": "提交日志对账工单",
                                            "description": "验证 invoke_business_function tool protocol 落盘。",
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
                                        "summary": "工单 BUG-LLM-LOG-001 已提交。",
                                        "structured_output": {
                                            "ticketNo": "BUG-LLM-LOG-001",
                                            "function_result_seen": True,
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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"帮我提交一个平台反馈工单 next:{trace_id}:001",
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
                "context": {"contextId": context_id},
                "runtime_context": {"task_scoped_token": "btt-e2e"},
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert [event.tool_name for event in events if event.type == "tool_use"] == [
        "invoke_business_function",
        "submit_skill_result",
    ]
    assert next(event for event in events if event.type == "result").structured_output == {
        "ticketNo": "BUG-LLM-LOG-001",
        "function_result_seen": True,
    }
    assert len(captured_inputs) == 1
    assert captured_inputs[0] == {
        "function_id": "tms.ticket.createPlatformFeedback",
        "version": "v1",
        "input_data": {
            "title": "提交日志对账工单",
            "description": "验证 invoke_business_function tool protocol 落盘。",
        },
        "idempotency_key": captured_inputs[0]["idempotency_key"],
    }
    assert captured_inputs[0]["idempotency_key"].startswith(
        "navigator:frm_"
    )

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    records = debug.json()
    assert [record["responseSummary"]["toolCalls"] for record in records] == [
        ["invoke_business_function"],
        ["submit_skill_result"],
    ]
    assert _message_tool_call_names(_record_role_messages(records[1], "assistant")[0]) == [
        "invoke_business_function",
    ]
    assert any("BUG-LLM-LOG-001" in (message.get("content") or "") for message in _record_role_messages(records[1], "tool"))

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 2
    second_payload = _llm_submission_for(payloads, task_id=task_id, skill_id="conversation.root", iteration=2)
    assert _submission_role_sequence(second_payload) == ["system", "human", "ai", "tool"]
    assert _submission_tool_call_names(second_payload) == ["invoke_business_function"]
    assert any("BUG-LLM-LOG-001" in content for content in _submission_texts(second_payload, "tool"))

    root_events = _runtime_message_events(
        context_id,
        task_id=task_id,
        frame_id=second_payload["meta"]["frameId"],
    )
    assert _runtime_initial_roles(root_events) == ["system", "user"]
    assert _runtime_tool_call_names(root_events) == [
        "invoke_business_function",
        "submit_skill_result",
    ]
    assert "after_tool_call" in _runtime_checkpoints(root_events)
    assert "persistent_turn_completed" in _runtime_checkpoints(root_events)
    assert any(
        "BUG-LLM-LOG-001" in ((event.get("message") or {}).get("content") or "")
        for event in root_events
        if event.get("eventType") == "tool_result"
    )


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
    assert first_open.content == "Opening conversation root frame"
    assert second_open.content == "Reusing conversation root frame"

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
    assert second_open.content == "Reusing conversation root frame"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    second_system_messages = _record_messages(records[1], "system")
    second_user_messages = _record_messages(records[1], "user")
    second_ai_messages = _record_messages(records[1], "assistant")
    assert second_user_messages == [
        f"{previous_user_message} next:{trace_id}:001",
        f"我上一个消息是什么 next:{trace_id}:002",
    ]
    assert previous_assistant_message in second_ai_messages
    assert _record_tool_call_names(records[1]) == ["submit_skill_result"]
    assert not any("本回合前的运行时可见对话:" in content for content in second_system_messages)
    assert not any("本回合前的最近对话:" in content for content in second_system_messages)
    assert not any(f"用户: {previous_user_message}" in content for content in second_system_messages)
    assert not any(f"助手: {previous_assistant_message}" in content for content in second_system_messages)


@pytest.mark.anyio
async def test_scripted_llm_submission_log_matches_root_recent_conversation(
    monkeypatch,
    mock_llm_server,
):
    """The persisted LLM body should match the runtime-visible two-turn conversation."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-llm-log-root-recent-{run_id}"
    session_id = f"sess-e2e-llm-log-root-recent-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-llm-log-root-recent-{run_id}"
    first_task_id = f"task_e2e_llm_log_root_recent_001_{run_id}"
    second_task_id = f"task_e2e_llm_log_root_recent_002_{run_id}"
    first_prompt = f"请记住工单 TMS-LLM-LOG-001 next:{trace_id}:001"
    first_answer = "已记录工单 TMS-LLM-LOG-001。"
    second_prompt = f"我刚才让你记住哪个工单 next:{trace_id}:002"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-llm-log-root-recent",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": first_answer,
                                        "structured_output": {"remembered_ticket": "TMS-LLM-LOG-001"},
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
                                        "summary": "你刚才让我记住工单 TMS-LLM-LOG-001。",
                                        "structured_output": {"saw_prior_turn": True},
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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

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
                "prompt": first_prompt,
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )
        second_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": second_prompt,
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert second_response.status_code == 200
    second_events = _parse_worker_sse(second_response.text)
    assert next(event for event in second_events if event.type == "result").structured_output == {
        "saw_prior_turn": True,
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    assert _record_messages(records[1], "user") == [first_prompt, second_prompt]
    assert first_answer in _record_messages(records[1], "assistant")
    assert _record_tool_call_names(records[1]) == ["submit_skill_result"]

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 2
    second_payload = _llm_submission_for(payloads, task_id=second_task_id, skill_id="conversation.root")
    assert _submission_role_sequence(second_payload) == ["system", "human", "ai", "tool", "ai", "human"]
    assert _submission_texts(second_payload, "human") == [first_prompt, second_prompt]
    assert first_answer in _submission_texts(second_payload, "ai")
    assert _submission_tool_call_names(second_payload) == ["submit_skill_result"]

    second_root_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=second_payload["meta"]["frameId"],
    )
    assert _runtime_initial_roles(second_root_events) == [
        "system",
        "user",
        "assistant",
        "tool",
        "assistant",
        "user",
    ]
    initial_texts = [
        event["message"]["content"]
        for event in second_root_events
        if event.get("eventType") == "message" and event.get("phase") == "initial"
    ]
    assert first_prompt in initial_texts
    assert first_answer in initial_texts
    assert second_prompt in initial_texts
    assert _runtime_tool_call_names(second_root_events) == ["submit_skill_result"]
    assert "persistent_turn_completed" in _runtime_checkpoints(second_root_events)


@pytest.mark.anyio
async def test_scripted_api_root_plain_final_commits_runtime_memory_without_retry_prompt(
    monkeypatch,
    mock_llm_server,
):
    """Root plain assistant answers should commit memory without synthetic retry prompts."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-plain-final-{run_id}"
    session_id = f"sess-e2e-root-plain-final-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-plain-final-{run_id}"
    first_task_id = f"task_e2e_root_plain_final_001_{run_id}"
    second_task_id = f"task_e2e_root_plain_final_002_{run_id}"
    first_prompt = f"hi plain final next:{trace_id}:001"
    first_answer = "你好，我可以帮你处理运输、工单和履约相关问题。"
    second_prompt = f"我刚才问了什么 next:{trace_id}:002"
    second_answer = "你刚才发送了 hi plain final。"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-plain-final",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {"content": first_answer},
                    },
                    {
                        "cursor": f"next:{trace_id}:002",
                        "response": {"content": second_answer},
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

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
                "prompt": first_prompt,
                "taskId": first_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )
        second_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": second_prompt,
                "taskId": second_task_id,
                "session_id": session_id,
                "model": "navigator-e2e-scripted",
                "llm_config": llm_config,
                "context": {"contextId": context_id},
            },
        )

    assert first_response.status_code == 200
    assert second_response.status_code == 200
    first_events = _parse_worker_sse(first_response.text)
    second_events = _parse_worker_sse(second_response.text)
    assert next(event for event in first_events if event.type == "result").content == first_answer
    assert next(event for event in second_events if event.type == "result").content == second_answer

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    assert _record_messages(records[1], "user") == [first_prompt, second_prompt]
    assert _record_messages(records[1], "assistant") == [first_answer]

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 2
    first_payload = _llm_submission_for(payloads, task_id=first_task_id, skill_id="conversation.root")
    second_payload = _llm_submission_for(payloads, task_id=second_task_id, skill_id="conversation.root")
    assert _submission_role_sequence(first_payload) == ["system", "human"]
    assert _submission_role_sequence(second_payload) == ["system", "human", "ai", "human"]
    assert _submission_texts(second_payload, "human") == [first_prompt, second_prompt]
    assert _submission_texts(second_payload, "ai") == [first_answer]
    assert _submission_tool_call_names(first_payload) == []
    assert _submission_tool_call_names(second_payload) == []
    assert "No tool call was produced" not in json.dumps(payloads, ensure_ascii=False)

    second_root_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=second_payload["meta"]["frameId"],
    )
    assert _runtime_initial_roles(second_root_events) == [
        "system",
        "user",
        "assistant",
        "user",
    ]
    assert "persistent_turn_completed" in _runtime_checkpoints(second_root_events)
    assert not any(event.get("phase") == "runtime_retry_instruction" for event in second_root_events)


@pytest.mark.anyio
async def test_scripted_root_prompt_contract_ignores_skill_alias_and_keeps_user_message_clean(
    monkeypatch,
    mock_llm_server,
):
    """Explicit skill aliases must not revive the removed root.agentic_routing prompt path."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-root-prompt-contract-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-root-prompt-contract-{run_id}"
    task_id = f"task_e2e_root_prompt_contract_{run_id}"
    prompt = f"hi prompt contract next:{trace_id}:001"
    answer = "你好，我可以帮你处理 TMS 业务。"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-root-prompt-contract",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {"content": answer},
                    },
                ],
            },
        )
        assert register.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": prompt,
                "taskId": task_id,
                "session_id": context_id,
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
                    "businessSkillName": "tms-ticket-agent",
                    "allowed_skills": [
                        {
                            "id": "tms-ticket-agent",
                            "name": "TMS 工单 Agent",
                            "description": "创建工单和查询工单。",
                        },
                    ],
                    "auto_inject_public_skills": False,
                    "auto_inject_app_public_skills": False,
                },
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert next(event for event in events if event.type == "result").content == answer

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 1
    payload = _llm_submission_for(payloads, task_id=task_id, skill_id="conversation.root")
    assert payload["meta"]["operation"] == "skill_agent.invoke"
    assert payload["meta"]["frameId"].startswith("frm_")
    assert payload["meta"]["contextId"] == context_id
    assert payload["meta"]["sessionId"] == context_id
    assert _submission_role_sequence(payload) == ["system", "human"]
    system_text = _submission_texts(payload, "system")[0]
    human_text = _submission_texts(payload, "human")[0]
    assert "你是当前业务会话的根编排 Agent" in system_text
    assert "可以直接输出自然语言作为本回合最终答复" in system_text
    assert "普通寒暄、简单问答、无需保留结构化状态的答复，不要调用 submit_skill_result" in system_text
    assert "普通业务技能请求默认加载 Skill 材料并在 Root 当前上下文继续" in system_text
    assert "不要仅因为 bundle 名称包含 agent 就调用 invoke_business_agent" in system_text
    assert "必须通过 submit_skill_result" not in system_text
    assert "不要直接输出自然语言" not in system_text
    assert "可用业务技能:" in system_text
    assert "`tms-ticket-agent`（TMS 工单 Agent）: 创建工单和查询工单。" in system_text
    assert "You are an intelligent business assistant" not in system_text
    assert "Available Agents" not in system_text
    assert human_text == prompt
    assert "allowed_skills" not in human_text
    assert "Context:" not in human_text
    assert "tms-ticket-agent" not in human_text
    assert not any((item.get("meta") or {}).get("operation") == "root.agentic_routing" for item in payloads)


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
    assert second_open.content == "Reusing conversation root frame"

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
    second_system_messages = _record_messages(records[1], "system")
    second_user_messages = _record_messages(records[1], "user")
    second_ai_messages = _record_messages(records[1], "assistant")
    assert second_user_messages == [
        f"先分析再处理运输异常 next:{trace_id}:001",
        f"继续 next:{trace_id}:002",
    ]
    assert "Root plan created." in second_ai_messages
    assert _record_tool_call_names(records[1]) == ["submit_skill_result"]
    assert any("当前活动任务计划:" in content for content in second_system_messages)
    assert any("handle multi-skill shipment issue" in content for content in second_system_messages)
    assert any("持久根计划策略:" in content for content in second_system_messages)
    assert any("主动调用 submit_skill_result" in content for content in second_system_messages)
    assert any("structured_output.active_plan" in content for content in second_system_messages)


@pytest.mark.anyio
async def test_scripted_root_skill_continues_after_recoverable_model_loop_failure(
    monkeypatch,
    mock_llm_server,
):
    """An empty root LLM response should keep conversation root recoverable for the next task."""
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
                        "response": {"content": ""},
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
        and event.error == "Root assistant returned no tool call and no final content"
        for event in failed_events
    )
    assert continued_open.content == "Reusing conversation root frame"
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
        f"next:{trace_id}:003",
    ]
    continue_record = records[1]
    system_messages = _record_messages(continue_record, "system")
    user_messages = _record_messages(continue_record, "user")
    assert user_messages == [f"继续 next:{trace_id}:003"]
    assert any("上一次执行被中断。" in content for content in system_messages)
    assert any("原因: model_error" in content for content in system_messages)
    assert any(
        "Root assistant returned no tool call and no final content" in content
        for content in system_messages
    )
    assert any("当前用户消息见下一条 human message。" in content for content in system_messages)


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
                                    "name": "invoke_business_agent",
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

    root_open = next(event for event in failed_events if event.type == "skill_frame_open" and event.presentation_hint == "root_frame")
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
    continued_open = next(event for event in continued_events if event.type == "skill_frame_open" and event.presentation_hint == "root_frame")
    assert continued_open.skill_frame_id == root_open.skill_frame_id
    assert continued_open.content == "Reusing conversation root frame"
    continued_child_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    assert continued_child_open.skill_frame_id == child_open.skill_frame_id
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
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
    continue_system_messages = _record_messages(records[-1], "system")
    continue_user_messages = _record_messages(records[-1], "user")
    continue_assistant_messages = _record_role_messages(records[-1], "assistant")
    continue_tool_messages = _record_role_messages(records[-1], "tool")
    assert continue_user_messages == [
        f"提交工单，先确认必要字段 next:{trace_id}:002",
        f"继续 next:{trace_id}:004",
    ]
    assert any("submit_skill_result" in _message_tool_call_names(message) for message in continue_assistant_messages)
    assert any("WAITING_USER" in (message.get("content") or "") for message in continue_tool_messages)
    assert any(
        "上一个子 Agent frame 正在等待用户输入。" in content
        for content in continue_system_messages
    ), continue_system_messages
    assert any(child_open.skill_frame_id in content for content in continue_system_messages)
    assert any("WAITING_USER" in content for content in continue_system_messages)
    assert any("当前 human message 是用户对上次提示的回复。" in content for content in continue_system_messages)
    assert any(
        "请回复工单类型、运单号（如适用）、标题及详细描述。" in content
        for content in continue_system_messages
    )
    assert any("missing_fields" in content for content in continue_system_messages)
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
                                    "name": "invoke_business_agent",
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
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    child_open = next(
        event for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "tms-ticket-agent"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    assert continued_root_open.skill_frame_id == root_open.skill_frame_id
    assert continued_root_open.content == "Reusing conversation root frame"
    assert any(event.tool_name == "invoke_business_agent" for event in first_events)
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
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
    continue_system_messages = _record_messages(records[-1], "system")
    continue_user_messages = _record_messages(records[-1], "user")
    continue_ai_messages = _record_messages(records[-1], "assistant")
    assert continue_user_messages[-1] == f"继续 next:{trace_id}:003"
    assert any(message.startswith("你可以帮我提交工单吗 ") for message in continue_user_messages)
    assert any("已引导用户选择工单类型" in message for message in continue_ai_messages)
    assert any("上一次执行被中断。" in content for content in continue_system_messages)
    assert any("原因: user_cancelled" in content for content in continue_system_messages)
    assert any("来自子 Agent 提升结果的续跑摘要:" in content for content in continue_system_messages)
    assert any("TMS 工单助手" in content for content in continue_system_messages)
    assert any("运单异常件" in content and "平台反馈" in content for content in continue_system_messages)
    assert any("当前活动任务计划:" in content for content in continue_system_messages)
    assert any("pending_info" in content and "ticket_type" in content for content in continue_system_messages)


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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
    assert continued_open.content == "Reusing conversation root frame"

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
    system_messages = _record_messages(continue_record, "system")
    user_messages = _record_messages(continue_record, "user")
    ai_messages = _record_messages(continue_record, "assistant")
    assert user_messages == [
        f"initial root turn next:{trace_id}:001",
        f"继续 next:{trace_id}:002",
    ]
    assert "Initial root turn complete." in ai_messages
    assert _record_tool_call_names(continue_record) == ["submit_skill_result"]
    assert any("上一次执行被中断。" in content for content in system_messages)
    assert any("原因: user_cancelled" in content for content in system_messages)
    assert any("Cancelled by user" in content for content in system_messages)
    assert any("当前用户消息见下一条 human message。" in content for content in system_messages)


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
    assert unrelated_open.content == "Reusing conversation root frame"

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
    system_messages = _record_messages(records[1], "system")
    user_messages = _record_messages(records[1], "user")
    ai_messages = _record_messages(records[1], "assistant")
    assert user_messages == [
        f"创建车辆 next:{trace_id}:001",
        f"帮我查一下新的订单列表 next:{trace_id}:002",
    ]
    assert "Initial vehicle task started." in ai_messages
    assert _record_tool_call_names(records[1]) == ["submit_skill_result"]
    assert any("中断工作只是可恢复候选" in content for content in system_messages)
    assert any("可恢复焦点:" in content for content in system_messages)
    assert any("可恢复焦点栈:" in content for content in system_messages)
    assert any("CONTINUE_PREVIOUS" in content for content in system_messages)
    assert any("ASK_CLARIFICATION" in content for content in system_messages)
    assert any("START_UNRELATED_NEW_TASK" in content for content in system_messages)
    assert any("intent_resolution" in content for content in system_messages)
    assert any("abandoned_interruption" in content for content in system_messages)
    assert any("shelve_interrupted_frame" in content for content in system_messages)


@pytest.mark.anyio
async def test_scripted_llm_submission_log_directly_resumes_nested_interruption_leaf(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A nested interrupted focus should resume the deepest leaf before root LLM fallback."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-nested-interrupt-log-{run_id}"
    session_id = f"sess-e2e-nested-interrupt-log-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-nested-interrupt-log-{run_id}"
    first_task_id = f"task_e2e_nested_interrupt_log_001_{run_id}"
    second_task_id = f"task_e2e_nested_interrupt_log_002_{run_id}"
    child_skill_id = f"nested-child-{run_id}"
    grandchild_skill_id = f"nested-grandchild-{run_id}"
    second_prompt = f"补充深层节点信息，请继续处理 next:{trace_id}:001"

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / f"{child_skill_id}.yaml").write_text(
        f"""
id: {child_skill_id}
name: Nested Child Skill
description: Holds the first nested business frame.
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
    (manifest_dir / f"{grandchild_skill_id}.yaml").write_text(
        f"""
id: {grandchild_skill_id}
name: Nested Grandchild Skill
description: Handles the deepest interrupted business frame.
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
    root_graph_module._skill_registry.load()
    root_graph_module._ensure_system_root_skill()

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-nested-interrupt-log",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": "请继续提供深层业务信息。",
                                        "structured_output": {
                                            "turn_status": "WAITING_FOR_USER_INPUT",
                                            "user_message": "请继续提供深层业务信息。",
                                            "required_fields": ["deep_detail"],
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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
        child_skill_id,
        {"order_id": "ORD-NESTED"},
    )
    grandchild_frame_id = runtime.invoke_child_skill(
        child_frame_id,
        grandchild_skill_id,
        {"order_id": "ORD-NESTED", "step": "deep"},
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
                "error": "User aborted while nested child skill was running",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": second_prompt,
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
    focus_open = next(
        event for event in events
        if event.type == "skill_frame_open" and event.skill_id == grandchild_skill_id
    )
    result = next(event for event in events if event.type == "result")
    assert focus_open.skill_frame_id == grandchild_frame_id
    assert focus_open.parent_frame_id == child_frame_id
    assert focus_open.content == f"Resuming frame for agent: {grandchild_skill_id}"
    assert result.content == "请继续提供深层业务信息。"
    assert result.structured_output["required_fields"] == ["deep_detail"]
    assert not any(event.tool_name == "shelve_interrupted_frame" for event in events)
    assert not any(event.tool_name == "resume_recoverable_child_skill" for event in events)
    assert not any(event.tool_name == "invoke_business_agent" for event in events)

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    grandchild = runtime.get_frame(grandchild_frame_id)
    assert root is not None
    assert child is not None
    assert grandchild is not None
    assert root.status == FrameStatus.WAITING_CHILD
    assert child.status == FrameStatus.WAITING_CHILD
    assert grandchild.status == FrameStatus.AWAITING_USER
    assert root.current_task_id == second_task_id
    assert child.current_task_id == second_task_id
    assert grandchild.current_task_id == second_task_id
    assert root.private_working_state["recoverable_focus_frame_id"] == grandchild_frame_id
    assert [entry["frame_id"] for entry in root.private_working_state["recoverable_focus_stack"]] == [
        root_frame_id,
        child_frame_id,
        grandchild_frame_id,
    ]

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [f"next:{trace_id}:001"]
    system_messages = _record_messages(records[0], "system")
    assert _record_messages(records[0], "user") == [second_prompt]
    assert records[0]["responseSummary"]["toolCalls"] == ["submit_skill_result"]
    assert any("当前业务技能回合上下文:" in content for content in system_messages)
    assert any(grandchild_skill_id in content for content in system_messages)
    assert not any("可恢复焦点栈:" in content for content in system_messages)
    assert not any("shelve_interrupted_frame" in content for content in system_messages)

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 1
    payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id=grandchild_skill_id,
    )
    assert _submission_role_sequence(payload) == ["system", "human"]
    system_text = "\n".join(_submission_texts(payload, "system"))
    assert "当前业务技能回合上下文:" in system_text
    assert grandchild_skill_id in system_text
    assert "可恢复焦点栈:" not in system_text
    assert _submission_texts(payload, "human") == [second_prompt]

    leaf_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=grandchild_frame_id,
    )
    assert _runtime_initial_roles(leaf_events) == ["system", "user"]
    assert _runtime_tool_call_names(leaf_events) == ["submit_skill_result"]
    assert "suspended" in _runtime_checkpoints(leaf_events)
    assert any(
        "WAITING_FOR_USER_INPUT" in ((event.get("message") or {}).get("content") or "")
        for event in leaf_events
        if event.get("eventType") == "tool_result"
    )
    assert _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=root_frame_id,
    ) == []


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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
    root_open = next(event for event in events if event.type == "skill_frame_open" and event.presentation_hint == "root_frame")
    child_open = next(event for event in events if event.type == "skill_frame_open" and event.skill_id == "exception_triage")
    result = next(event for event in events if event.type == "result")
    assert root_open.skill_frame_id == root_frame_id
    assert root_open.content == "Reusing conversation root frame"
    assert child_open.skill_frame_id == child_frame_id
    assert child_open.content == "Resuming frame for agent: exception_triage"
    assert result.content == f"Child resumed successfully next:{trace_id}:002"
    assert result.structured_output == {
        "classification": "vehicle_delay",
        "recommended_action": "manual_dispatch",
        "confidence": 0.91,
    }

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
    assert not any(event.tool_name == "invoke_business_agent" for event in events)
    assert not any(event.tool_name == "read_frame_execution_report" for event in events)

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert records
    assert {record["cursor"] for record in records} == {f"next:{trace_id}:001"}
    all_system_messages = [
        content
        for record in records
        for content in _record_messages(record, "system")
    ]
    all_user_messages = [
        content
        for record in records
        for content in _record_messages(record, "user")
    ]
    assert all_user_messages == [f"继续 next:{trace_id}:001"] * len(records)
    assert any("当前业务技能回合上下文:" in content for content in all_system_messages)

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 2
    payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="exception_triage",
    )
    assert _submission_role_sequence(payload) == ["system", "human"]
    assert _submission_texts(payload, "human") == [f"继续 next:{trace_id}:001"]
    system_text = "\n".join(_submission_texts(payload, "system"))
    assert "当前业务技能回合上下文:" in system_text

    root_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="conversation.root",
    )
    assert _submission_role_sequence(root_payload) == ["system", "human"]
    assert _submission_texts(root_payload, "human") == [f"继续 next:{trace_id}:001"]

    child_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=child_frame_id,
    )
    assert _runtime_initial_roles(child_events) == ["system", "user"]
    assert _runtime_tool_call_names(child_events) == ["submit_skill_result"]
    assert "frame_completed" in _runtime_checkpoints(child_events)

    root_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=root_frame_id,
    )
    assert _runtime_initial_roles(root_events) == ["system", "user"]
    assert _runtime_tool_call_names(root_events) == ["submit_skill_result"]
    assert "persistent_turn_completed" in _runtime_checkpoints(root_events)


@pytest.mark.anyio
async def test_scripted_nested_focus_completion_unwinds_to_parent_result(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    """A completed nested focus should resume and close its parent before root result."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-nested-completion-unwind-{run_id}"
    session_id = f"sess-e2e-nested-completion-unwind-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-nested-completion-unwind-{run_id}"
    first_task_id = f"task_e2e_nested_completion_unwind_001_{run_id}"
    second_task_id = f"task_e2e_nested_completion_unwind_002_{run_id}"
    child_skill_id = f"nested-completion-child-{run_id}"
    grandchild_skill_id = f"nested-completion-grandchild-{run_id}"
    second_prompt = f"补充完成信息，请继续处理 next:{trace_id}:001"
    final_summary = f"嵌套任务已完成 next:{trace_id}:001"

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / f"{child_skill_id}.yaml").write_text(
        f"""
id: {child_skill_id}
name: Nested Completion Parent Skill
description: Synthesizes a completed nested child result.
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
    (manifest_dir / f"{grandchild_skill_id}.yaml").write_text(
        f"""
id: {grandchild_skill_id}
name: Nested Completion Leaf Skill
description: Finishes the deepest interrupted business frame.
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
    root_graph_module._skill_registry.load()
    root_graph_module._ensure_system_root_skill()

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-nested-completion-unwind",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": final_summary,
                                        "structured_output": {
                                            "status": "FINAL_FOR_USER",
                                            "message": final_summary,
                                            "requires_parent_synthesis": False,
                                            "nested_unwind_done": True,
                                        },
                                        "evidence_refs": ["e2e:nested-unwind"],
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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
        child_skill_id,
        {"order_id": "ORD-NESTED-COMPLETE"},
    )
    grandchild_frame_id = runtime.invoke_child_skill(
        child_frame_id,
        grandchild_skill_id,
        {"order_id": "ORD-NESTED-COMPLETE", "step": "leaf"},
    )

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": "model_timeout",
                "error": "Model timed out while nested child skill was running",
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": second_prompt,
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
    assert not [event for event in events if event.type == "error"]
    grandchild_open = next(
        event for event in events
        if event.type == "skill_frame_open" and event.skill_id == grandchild_skill_id
    )
    parent_resume = next(
        event for event in events
        if (
            event.type == "skill_frame_open"
            and event.skill_id == child_skill_id
            and event.skill_frame_id == child_frame_id
        )
    )
    close_ids = [
        event.skill_frame_id
        for event in events
        if event.type == "skill_frame_close"
    ]
    result = next(event for event in events if event.type == "result")
    assert grandchild_open.skill_frame_id == grandchild_frame_id
    assert grandchild_open.parent_frame_id == child_frame_id
    assert parent_resume.content == f"Resuming parent frame after child completion: {child_skill_id}"
    assert close_ids == [grandchild_frame_id, child_frame_id]
    assert result.content == final_summary
    assert result.structured_output["nested_unwind_done"] is True

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    grandchild = runtime.get_frame(grandchild_frame_id)
    assert root is not None
    assert child is not None
    assert grandchild is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert grandchild.status == FrameStatus.COMPLETED
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert "active_focus_frame_id" not in root.private_working_state
    assert root.private_working_state["child_results"][child_frame_id]["structured_output"] == {
        "status": "FINAL_FOR_USER",
        "message": final_summary,
        "requires_parent_synthesis": False,
        "nested_unwind_done": True,
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:001",
    ]
    assert [_record_messages(record, "user") for record in records] == [
        [second_prompt],
        [second_prompt],
    ]
    parent_system_messages = _record_messages(records[1], "system")
    assert any("刚完成的子 Agent 提升结果:" in content for content in parent_system_messages)
    assert any(grandchild_skill_id in content for content in parent_system_messages)
    assert any(final_summary in content for content in parent_system_messages)

    payloads = _llm_submission_payloads(context_id)
    leaf_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id=grandchild_skill_id,
    )
    parent_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id=child_skill_id,
    )
    assert _submission_role_sequence(leaf_payload) == ["system", "human"]
    assert _submission_role_sequence(parent_payload) == ["system", "human"]
    parent_system_text = "\n".join(_submission_texts(parent_payload, "system"))
    assert "刚完成的子 Agent 提升结果:" in parent_system_text
    assert grandchild_skill_id in parent_system_text
    assert final_summary in parent_system_text
    assert _submission_texts(parent_payload, "human") == [second_prompt]

    leaf_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=leaf_payload["meta"]["frameId"],
    )
    assert _runtime_initial_roles(leaf_events) == ["system", "user"]
    assert _runtime_tool_call_names(leaf_events) == ["submit_skill_result"]
    assert "frame_completed" in _runtime_checkpoints(leaf_events)

    parent_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=parent_payload["meta"]["frameId"],
    )
    assert _runtime_initial_roles(parent_events) == ["system", "user"]
    assert _runtime_tool_call_names(parent_events) == ["submit_skill_result"]
    assert "frame_completed" in _runtime_checkpoints(parent_events)
    parent_initial_system = next(
        event["message"]["content"]
        for event in parent_events
        if (
            event.get("eventType") == "message"
            and event.get("phase") == "initial"
            and event["message"]["role"] == "system"
        )
    )
    assert "刚完成的子 Agent 提升结果:" in parent_initial_system
    assert grandchild_skill_id in parent_initial_system
    assert final_summary in parent_initial_system


@pytest.mark.anyio
@pytest.mark.parametrize(
    ("interrupt_reason", "error_text"),
    [
        ("model_timeout", "Model timed out while nested leaf was running"),
        ("model_error", "Model error while nested leaf was running"),
    ],
)
async def test_scripted_nested_recoverable_leaf_can_handoff_to_parent_after_error(
    monkeypatch,
    mock_llm_server,
    tmp_path,
    interrupt_reason,
    error_text,
):
    """A recoverable ERROR/TIMEOUT leaf can hand off to its parent on resume."""
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"worker-nested-handoff-{interrupt_reason}-{run_id}"
    session_id = f"sess-e2e-nested-handoff-{interrupt_reason}-{run_id}"
    context_id = f"bctx_20260520_ab_ctx-e2e-nested-handoff-{interrupt_reason}-{run_id}"
    first_task_id = f"task_e2e_nested_handoff_001_{run_id}"
    second_task_id = f"task_e2e_nested_handoff_002_{run_id}"
    child_skill_id = f"nested-handoff-child-{run_id}"
    grandchild_skill_id = f"nested-handoff-grandchild-{run_id}"
    second_prompt = f"刚才中断后换个处理方式，让上层综合判断 next:{trace_id}:001"
    leaf_handoff_summary = "叶子技能已把中断后的用户新意图交回父技能处理。"
    parent_summary = "父技能已接管中断后的新意图，并给出最终答复。"

    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir()
    (manifest_dir / f"{child_skill_id}.yaml").write_text(
        f"""
id: {child_skill_id}
name: Nested Handoff Parent Skill
description: Synthesizes a recoverable leaf handoff.
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
  - requires_parent_synthesis
""".strip(),
        encoding="utf-8",
    )
    (manifest_dir / f"{grandchild_skill_id}.yaml").write_text(
        f"""
id: {grandchild_skill_id}
name: Nested Handoff Leaf Skill
description: Hands recoverable interruption context back to its parent.
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
  - requires_parent_synthesis
""".strip(),
        encoding="utf-8",
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "_legacy_dir", manifest_dir)
    root_graph_module._skill_registry.load()
    root_graph_module._ensure_system_root_skill()

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        await mock_client.delete(f"/__e2e/scripts/{trace_id}")
        register = await mock_client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "scenarioId": "worker-nested-recoverable-leaf-handoff",
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "handoff_to_parent",
                                    "args": {
                                        "summary": leaf_handoff_summary,
                                        "reason": "CHANGE_TOPIC",
                                        "intent_resolution": "ASK_PARENT_TO_DECIDE",
                                        "requires_parent_synthesis": True,
                                        "parent_instruction": "请父技能基于用户新意图综合答复。",
                                        "structured_output": {
                                            "handoff_stage": "leaf",
                                            "interruption_reason": interrupt_reason,
                                        },
                                    },
                                }
                            ],
                        },
                    },
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {
                            "tool_calls": [
                                {
                                    "name": "submit_skill_result",
                                    "args": {
                                        "summary": parent_summary,
                                        "structured_output": {
                                            "status": "FINAL_FOR_USER",
                                            "message": parent_summary,
                                            "requires_parent_synthesis": False,
                                            "handled_leaf_handoff": True,
                                            "interruption_reason": interrupt_reason,
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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
        child_skill_id,
        {"order_id": "ORD-NESTED-HANDOFF"},
    )
    grandchild_frame_id = runtime.invoke_child_skill(
        child_frame_id,
        grandchild_skill_id,
        {"order_id": "ORD-NESTED-HANDOFF", "step": "leaf"},
    )

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://worker") as worker_client:
        interruption_response = await worker_client.post(
            "/api/v1/frames/interruption",
            json={
                "taskId": first_task_id,
                "session_id": session_id,
                "context_id": context_id,
                "reason": interrupt_reason,
                "error": error_text,
            },
        )
        continued_response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": second_prompt,
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
    assert not [event for event in events if event.type == "error"]
    leaf_open = next(
        event for event in events
        if event.type == "skill_frame_open" and event.skill_id == grandchild_skill_id
    )
    parent_open = next(
        event for event in events
        if (
            event.type == "skill_frame_open"
            and event.skill_id == child_skill_id
            and event.skill_frame_id == child_frame_id
        )
    )
    close_ids = [
        event.skill_frame_id
        for event in events
        if event.type == "skill_frame_close"
    ]
    result = next(event for event in events if event.type == "result")
    assert leaf_open.skill_frame_id == grandchild_frame_id
    assert leaf_open.parent_frame_id == child_frame_id
    assert leaf_open.content == f"Resuming frame for agent: {grandchild_skill_id}"
    assert parent_open.content == f"Resuming parent frame after child completion: {child_skill_id}"
    assert close_ids == [grandchild_frame_id, child_frame_id]
    assert any(event.tool_name == "handoff_to_parent" for event in events)
    assert result.content == parent_summary
    assert result.structured_output["handled_leaf_handoff"] is True
    assert result.structured_output["interruption_reason"] == interrupt_reason

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    grandchild = runtime.get_frame(grandchild_frame_id)
    assert root is not None
    assert child is not None
    assert grandchild is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert grandchild.status == FrameStatus.COMPLETED
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert "active_focus_frame_id" not in root.private_working_state
    parent_promoted = root.private_working_state["child_results"][child_frame_id]
    assert parent_promoted["structured_output"]["handled_leaf_handoff"] is True

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:001",
    ]
    assert [_record_messages(record, "user") for record in records] == [
        [second_prompt],
        [second_prompt],
    ]
    assert [record["responseSummary"]["toolCalls"] for record in records] == [
        ["handoff_to_parent"],
        ["submit_skill_result"],
    ]
    parent_system_messages = _record_messages(records[1], "system")
    assert any("刚完成的子 Agent 提升结果:" in content for content in parent_system_messages)
    assert any(leaf_handoff_summary in content for content in parent_system_messages)

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 2
    leaf_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id=grandchild_skill_id,
    )
    parent_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id=child_skill_id,
    )
    assert _submission_role_sequence(leaf_payload) == ["system", "human"]
    assert _submission_role_sequence(parent_payload) == ["system", "human"]
    assert _submission_texts(leaf_payload, "human") == [second_prompt]
    assert _submission_texts(parent_payload, "human") == [second_prompt]
    parent_system_text = "\n".join(_submission_texts(parent_payload, "system"))
    assert "刚完成的子 Agent 提升结果:" in parent_system_text
    assert leaf_handoff_summary in parent_system_text

    leaf_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=grandchild_frame_id,
    )
    parent_events = _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=child_frame_id,
    )
    assert _runtime_initial_roles(leaf_events) == ["system", "user"]
    assert _runtime_tool_call_names(leaf_events) == ["handoff_to_parent"]
    assert "frame_completed" in _runtime_checkpoints(leaf_events)
    assert _runtime_initial_roles(parent_events) == ["system", "user"]
    assert _runtime_tool_call_names(parent_events) == ["submit_skill_result"]
    assert "frame_completed" in _runtime_checkpoints(parent_events)
    assert _runtime_message_events(
        context_id,
        task_id=second_task_id,
        frame_id=root_frame_id,
    ) == []


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
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(root_graph_module.settings, "llm_submission_log_max_files", 100)
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
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    continued_root_open = next(
        event for event in continued_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    unrelated_root_open = next(
        event for event in unrelated_events
        if event.type == "skill_frame_open" and event.presentation_hint == "root_frame"
    )
    assert continued_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert unrelated_root_open.skill_frame_id == first_root_open.skill_frame_id
    assert continued_root_open.content == "Reusing conversation root frame"
    assert unrelated_root_open.content == "Reusing conversation root frame"

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
    assert not any(event.tool_name == "invoke_business_agent" for event in continued_events)
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

    payloads = _llm_submission_payloads(context_id)
    assert len(payloads) == 7
    first_root_payloads = [
        payload
        for payload in payloads
        if (
            (payload.get("meta") or {}).get("taskId") == first_task_id
            and (payload.get("meta") or {}).get("skillId") == "conversation.root"
        )
    ]
    first_child_payloads = [
        payload
        for payload in payloads
        if (
            (payload.get("meta") or {}).get("taskId") == first_task_id
            and (payload.get("meta") or {}).get("skillId") == "exception_triage"
        )
    ]
    assert len(first_root_payloads) == 2
    assert len(first_child_payloads) == 3
    assert _submission_role_sequence(first_root_payloads[0]) == ["system", "human"]
    assert _submission_role_sequence(first_child_payloads[0]) == ["system", "human"]
    assert _submission_tool_call_names(first_child_payloads[-1]) == [
        "mock_get_order",
        "mock_get_vehicle_status",
    ]
    assert _submission_role_sequence(first_root_payloads[-1])[-2:] == ["ai", "tool"]
    assert _submission_tool_call_names(first_root_payloads[-1]) == ["invoke_business_agent"]

    continued_payload = _llm_submission_for(
        payloads,
        task_id=second_task_id,
        skill_id="conversation.root",
    )
    assert _submission_role_sequence(continued_payload)[-1] == "human"
    assert _submission_texts(continued_payload, "human")[-1] == (
        f"继续刚才被中断的异常订单处理，沿用已有上下文给出下一步。 next:{trace_id}:006"
    )
    continued_tool_names = _submission_tool_call_names(continued_payload)
    assert "invoke_business_agent" in continued_tool_names
    assert "submit_skill_result" in continued_tool_names
    assert any(
        "vehicle_delay" in text
        for text in _submission_texts(continued_payload, "system")
    )

    unrelated_payload = _llm_submission_for(
        payloads,
        task_id=third_task_id,
        skill_id="conversation.root",
    )
    assert _submission_texts(unrelated_payload, "human")[-1] == (
        "先不用处理刚才那个异常了。帮我看一个新的异常订单 "
        f"ORD-REAL-SMOKE-NEW 是否需要人工介入。 next:{trace_id}:012"
    )
    assert any(
        "可恢复焦点栈" in text or "中断" in text
        for text in _submission_texts(unrelated_payload, "system")
    )

    root_events = _runtime_message_events(context_id, frame_id=first_root_open.skill_frame_id)
    child_frame_ids = {
        event.skill_frame_id
        for event in first_events
        if event.type == "skill_frame_open" and event.skill_id == "exception_triage"
    }
    assert child_frame_ids
    child_events = _runtime_message_events(context_id, frame_id=next(iter(child_frame_ids)))
    assert "persistent_turn_completed" in _runtime_checkpoints(root_events)
    assert "frame_completed" in _runtime_checkpoints(child_events)
    assert "mock_get_order" in _runtime_tool_call_names(child_events)
    assert "mock_get_vehicle_status" in _runtime_tool_call_names(child_events)
