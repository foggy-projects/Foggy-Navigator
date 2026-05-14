"""Integration test for scripted E2E tool-call loops via OpenAI streaming."""

from __future__ import annotations

import asyncio
import json
import socket
import sys
import threading
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
from mock_llm.main import app as mock_llm_app


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
                                        "summary": "TMS Navigator deterministic tool loop ok",
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
    assert [event.type for event in events].count("skill_frame_open") == 1
    assert any(event.type == "skill_result_submit" for event in events)
    result = next(event for event in events if event.type == "result")
    assert result.content == "TMS Navigator deterministic tool loop ok"
    assert result.structured_output["classification"] == "vehicle_delay"

    async with httpx.AsyncClient(base_url=mock_llm_server) as mock_client:
        debug = await mock_client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    cursors = [record["cursor"] for record in records]
    assert cursors[0] == f"next:{trace_id}:001"
    assert cursors[-1] == f"next:{trace_id}:002"
    assert f"next:{trace_id}:002" in cursors
    first_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:001")
    second_turn = next(record for record in records if record["cursor"] == f"next:{trace_id}:002")
    assert first_turn["responseSummary"]["toolCalls"] == ["invoke_business_skill"]
    assert second_turn["responseSummary"]["toolCalls"] == ["submit_skill_result"]
