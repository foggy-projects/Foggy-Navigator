"""E2E coverage for the Linux command tool through a scripted mock LLM."""

from __future__ import annotations

# ruff: noqa: E402

import asyncio
import json
import platform
import shutil
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
from langgraph_biz_worker.models import QueryEvent
from langgraph_biz_worker.runtime import command_tool as command_tool_module
from mock_llm.main import app as mock_llm_app


pytestmark = pytest.mark.skipif(
    platform.system() != "Linux"
    or shutil.which("bash") is None
    or shutil.which("git") is None
    or shutil.which("curl") is None,
    reason="command tool E2E requires Linux with bash, git, and curl",
)


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


@pytest.mark.anyio
async def test_scripted_mock_llm_runs_command_tool_and_returns_natural_final(
    monkeypatch,
    mock_llm_server,
    tmp_path,
):
    run_id = uuid.uuid4().hex[:8]
    trace_id = f"command-tool-e2e-{run_id}"
    context_id = f"bctx_20260523_ab_command_e2e_{run_id}"
    task_id = f"task_command_e2e_{run_id}"
    workdir = tmp_path / "workspace"
    workdir.mkdir()

    command = (
        "git --version && "
        "curl --version | head -n 1 && "
        f"printf 'next:{trace_id}:002\\n'"
    )
    script = {
        "traceId": trace_id,
        "scenarioId": "command-tool-e2e",
        "turns": [
            {
                "cursor": f"next:{trace_id}:001",
                "response": {
                    "tool_calls": [
                        {
                            "id": f"call_command_{run_id}",
                            "type": "function",
                            "function": {
                                "name": "command",
                                "arguments": json.dumps(
                                    {
                                        "command": command,
                                        "workdir": ".",
                                        "timeout_seconds": 10,
                                        "max_output_chars": 4000,
                                    }
                                ),
                            },
                        }
                    ],
                },
            },
            {
                "cursor": f"next:{trace_id}:002",
                "response": {
                    "content": "OK_COMMAND_MOCK_LLM_E2E",
                },
            },
        ],
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        registered = await mock_client.post("/__e2e/scripts", json=script)
    assert registered.status_code == 200

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(command_tool_module.settings, "enable_command", True)

    transport = ASGITransport(app=worker_app)
    async with AsyncClient(transport=transport, base_url="http://test") as worker_client:
        response = await worker_client.post(
            "/api/v1/query",
            json={
                "prompt": f"Run the Linux command tool smoke. next:{trace_id}:001",
                "taskId": task_id,
                "contextId": context_id,
                "model": "navigator-e2e-scripted",
                "max_turns": 4,
                "llm_config": {
                    "provider": "openai",
                    "api_key": "sk-test",
                    "base_url": f"{mock_llm_server}/v1",
                    "model": "navigator-e2e-scripted",
                    "temperature": 0,
                },
                "context": {
                    "execution_policy": {
                        "workdir": str(workdir),
                        "allowed_dirs": [str(workdir)],
                        "allowed_tools": ["command"],
                    },
                },
            },
        )

    assert response.status_code == 200
    events = _parse_worker_sse(response.text)
    assert [event.tool_name for event in events if event.type == "tool_use"] == [
        "command",
    ]

    command_result_event = next(
        event for event in events if event.type == "tool_result" and event.tool_name == "command"
    )
    command_result = json.loads(command_result_event.content or "{}")
    assert command_result["ok"] is True
    assert command_result["exit_code"] == 0
    assert "git version" in command_result["stdout"]
    assert "curl " in command_result["stdout"]
    assert f"next:{trace_id}:002" in command_result["stdout"]
    assert command_result["workdir"] == str(workdir)

    result = next(event for event in events if event.type == "result")
    assert result.content == "OK_COMMAND_MOCK_LLM_E2E"
    assert result.structured_output == {
        "turn_status": "FINAL_FOR_USER",
        "message": "OK_COMMAND_MOCK_LLM_E2E",
        "completion_mode": "assistant_message",
    }

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = [record for record in debug.json() if record["request"].get("tools")]
    assert [record["cursor"] for record in records] == [
        f"next:{trace_id}:001",
        f"next:{trace_id}:002",
    ]
    assert records[0]["responseSummary"]["toolCalls"] == ["command"]
    assert records[1]["responseSummary"]["toolCalls"] == []
