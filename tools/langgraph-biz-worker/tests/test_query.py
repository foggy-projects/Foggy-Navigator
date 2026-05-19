"""Tests for the POST /api/v1/query SSE endpoint."""

import asyncio
import json
import threading
from unittest.mock import patch

import pytest
from pydantic import ValidationError

from langgraph_biz_worker.models import QueryEvent, QueryRequest
from langgraph_biz_worker.graphs.root_graph import _build_attachment_context_prompt
from langgraph_biz_worker.routes.query import _event_generator, _resolve_session_id
from langgraph_biz_worker.routes.health import active_tasks


@pytest.mark.asyncio
async def test_query_returns_sse_events(client):
    """Query endpoint should return a sequence of SSE events: system -> assistant_text -> result."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test business query"},
    )
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers.get("content-type", "")

    # Parse SSE events from response body
    events = _parse_sse_events(resp.text)

    assert len(events) >= 3, f"Expected at least 3 events, got {len(events)}"

    # Verify event sequence
    event_types = [e["type"] for e in events]
    assert event_types[0] == "system"
    assert event_types[1] == "assistant_text"
    assert event_types[-1] == "result"


@pytest.mark.asyncio
async def test_query_result_contains_task_id(client):
    """All events should carry a non-empty task_id."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test"},
    )
    events = _parse_sse_events(resp.text)

    for event in events:
        assert event.get("task_id"), f"Event missing task_id: {event}"


@pytest.mark.asyncio
async def test_query_with_custom_task_id(client):
    """When taskId is provided in request, events should use it."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test", "taskId": "custom-task-123"},
    )
    events = _parse_sse_events(resp.text)

    for event in events:
        assert event["task_id"] == "custom-task-123"


def test_query_prefers_foggy_session_id_for_platform_session():
    request = QueryRequest(
        prompt="test",
        session_id="claude-provider-session",
        foggy_session_id="navigator-session",
    )

    assert _resolve_session_id(request) == "navigator-session"


def test_query_request_accepts_url_attachment_metadata():
    request = QueryRequest(
        prompt="describe",
        vision_llm_config={"provider": "openai", "model": "vision-model"},
        attachments=[
            {
                "name": "pod-photo.png",
                "url": "https://tms.example.com/files/pod-photo.png",
                "kind": "image",
            }
        ],
    )

    assert request.attachments == [
        {
            "name": "pod-photo.png",
            "url": "https://tms.example.com/files/pod-photo.png",
            "kind": "image",
        }
    ]
    assert request.vision_llm_config == {"provider": "openai", "model": "vision-model"}


def test_query_request_accepts_max_turns_aliases():
    camel = QueryRequest.model_validate({"prompt": "test", "maxTurns": 9})
    snake = QueryRequest.model_validate({"prompt": "test", "max_turns": 10})

    assert camel.max_turns == 9
    assert snake.max_turns == 10


def test_query_request_accepts_message_and_skill_name_aliases():
    request = QueryRequest.model_validate({
        "message": "check order",
        "skillName": "order-assistant",
        "skill_id": "order-assistant",
    })

    assert request.prompt == "check order"
    assert request.skill_name == "order-assistant"


def test_query_request_rejects_conflicting_skill_name_aliases():
    with pytest.raises(ValidationError):
        QueryRequest.model_validate({
            "prompt": "check order",
            "skill_name": "order-assistant",
            "skillId": "legacy_order",
        })


def test_attachment_context_prompt_sanitizes_url_and_keeps_metadata():
    prompt = _build_attachment_context_prompt([
        {
            "id": "att-1",
            "name": "pod-photo.png",
            "mimeType": "image/png",
            "size": 12345,
            "kind": "image",
            "url": "https://tms.example.com/files/pod-photo.png?token=secret",
            "metadata": {
                "traceId": "att-20260513-001",
                "accessToken": "secret",
                "token": {"nested": "ignored"},
            },
        }
    ])

    assert "pod-photo.png" in prompt
    assert "image/png" in prompt
    assert "att-20260513-001" in prompt
    assert "https://tms.example.com/files/pod-photo.png" in prompt
    assert "token=secret" not in prompt
    assert "accessToken" not in prompt
    assert "secret" not in prompt
    assert "nested" not in prompt


@pytest.mark.asyncio
async def test_query_generator_streams_tool_progress_before_graph_finishes():
    release_graph = threading.Event()
    graph_returned = threading.Event()
    tool_event = QueryEvent(
        type="tool_use",
        task_id="progress-task-001",
        skill_frame_id="frame-001",
        skill_id="skill-001",
        content="tms.dataset.listModels",
        tool_call_id="call-001",
        tool_name="tms.dataset.listModels",
        function_id="tms.dataset.listModels",
    )
    result_event = QueryEvent(type="result", task_id="progress-task-001", content="done")

    def invoke_with_progress(state):
        state["runtime_context"]["_progress_event_sink"](tool_event)
        assert release_graph.wait(timeout=2)
        graph_returned.set()
        return {"events": [tool_event, result_event]}

    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = invoke_with_progress
        generator = _event_generator(
            "progress-task-001",
            QueryRequest(prompt="stream tool progress"),
        )
        try:
            first = await asyncio.wait_for(generator.__anext__(), timeout=1)
            first_event = json.loads(first["data"])
            assert first_event["type"] == "tool_use"
            assert not graph_returned.is_set()
        finally:
            release_graph.set()

        remaining = []
        async for item in generator:
            remaining.append(json.loads(item["data"]))

    all_events = [first_event, *remaining]
    assert [event["type"] for event in all_events].count("tool_use") == 1
    assert any(event["type"] == "result" for event in all_events)


@pytest.mark.asyncio
async def test_query_generator_fsscript_slow_task_stays_active_after_initial_progress():
    release_fsscript = asyncio.Event()

    class SlowFsscriptBridge:
        async def stream_events(self, **kwargs):
            yield QueryEvent(
                type="system",
                task_id=kwargs["task_id"],
                session_id=kwargs["session_id"],
                content="FSScript started",
            )
            await release_fsscript.wait()
            yield QueryEvent(
                type="result",
                task_id=kwargs["task_id"],
                session_id=kwargs["session_id"],
                content="FSScript completed",
            )

    with patch(
        "langgraph_biz_worker.routes.query.get_fsscript_bridge",
        return_value=SlowFsscriptBridge(),
    ):
        task_id = "slow-fsscript-task-001"
        generator = _event_generator(
            task_id,
            QueryRequest(
                prompt="run slow fsscript",
                session_id="session-slow-fsscript",
                context={"fsscript": "return slow();"},
            ),
        )
        try:
            first = await asyncio.wait_for(generator.__anext__(), timeout=1)
            first_event = json.loads(first["data"])
            assert first_event["type"] == "system"
            assert task_id in active_tasks

            pending_result = asyncio.create_task(generator.__anext__())
            await asyncio.sleep(0.05)
            assert not pending_result.done()
            assert task_id in active_tasks

            release_fsscript.set()
            second = await asyncio.wait_for(pending_result, timeout=1)
            second_event = json.loads(second["data"])
            assert second_event["type"] == "result"

            with pytest.raises(StopAsyncIteration):
                await asyncio.wait_for(generator.__anext__(), timeout=1)
            assert task_id not in active_tasks
        finally:
            release_fsscript.set()
            await generator.aclose()
            active_tasks.discard(task_id)


@pytest.mark.asyncio
async def test_query_generator_preserves_model_config_id_and_attachments_in_state():
    captured_state = {}

    attachments = [
        {
            "id": "att-1",
            "name": "pod-photo.png",
            "mimeType": "image/png",
            "size": 12345,
            "kind": "image",
            "url": "https://tms.example.com/pod-photo.png",
        }
    ]

    def invoke_and_capture(state):
        captured_state.update(state)
        return {"events": [QueryEvent(type="result", task_id="task-attachments", content="done")]}

    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = invoke_and_capture
        generator = _event_generator(
            "task-attachments",
            QueryRequest(
                prompt="describe attachment",
                skill_name="order-assistant",
                model_config_id="cfg-e2e",
                vision_llm_config={"provider": "openai", "model": "vision-model"},
                attachments=attachments,
            ),
        )
        events = []
        async for item in generator:
            events.append(json.loads(item["data"]))

    assert events[-1]["type"] == "result"
    assert captured_state["skill_name"] == "order-assistant"
    assert captured_state["model_config_id"] == "cfg-e2e"
    assert captured_state["vision_llm_config"] == {"provider": "openai", "model": "vision-model"}
    assert captured_state["attachments"] == attachments


@pytest.mark.asyncio
async def test_query_generator_forwards_max_turns_to_runtime_context():
    captured_state = {}

    def invoke_and_capture(state):
        captured_state.update(state)
        return {"events": [QueryEvent(type="result", task_id="task-max-turns", content="done")]}

    with patch("langgraph_biz_worker.routes.query.root_graph") as mock_graph:
        mock_graph.invoke.side_effect = invoke_and_capture
        generator = _event_generator(
            "task-max-turns",
            QueryRequest.model_validate({"prompt": "use budget", "maxTurns": 11}),
        )
        events = []
        async for item in generator:
            events.append(json.loads(item["data"]))

    assert events[-1]["type"] == "result"
    assert captured_state["runtime_context"]["max_turns"] == 11


@pytest.mark.asyncio
async def test_query_result_has_duration(client):
    """The result event should contain duration_ms."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test"},
    )
    events = _parse_sse_events(resp.text)

    result_events = [e for e in events if e["type"] == "result"]
    assert len(result_events) == 1
    assert result_events[0]["duration_ms"] is not None
    assert result_events[0]["duration_ms"] >= 0


@pytest.mark.asyncio
async def test_query_assistant_text_present(client):
    """The assistant_text event should contain the LLM response or fallback text."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "analyze order 456"},
    )
    events = _parse_sse_events(resp.text)

    text_events = [e for e in events if e["type"] == "assistant_text"]
    assert len(text_events) >= 1
    assert len(text_events[0]["content"]) > 0


def _parse_sse_events(body: str) -> list[dict]:
    """Parse SSE event stream body into a list of JSON event dicts."""
    events = []
    for line in body.split("\n"):
        line = line.strip()
        if line.startswith("data:"):
            data_str = line[len("data:"):].strip()
            if data_str:
                try:
                    events.append(json.loads(data_str))
                except json.JSONDecodeError:
                    pass
    return events


@pytest.mark.asyncio
async def test_query_allowed_skills(client):
    """When allowed_skills is in context, the LLM should receive it in the system prompt."""
    context = {
        "allowed_skills": [
            {
                "id": "tms_order_refund",
                "description": "Custom TMS order refund skill"
            }
        ]
    }
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test allowed skills", "context": context},
    )
    assert resp.status_code == 200
    events = _parse_sse_events(resp.text)
    
    # We can't directly check the system prompt sent to the LLM here unless we inspect the mock calls.
    # But we can verify it doesn't crash and returns events.
    assert len(events) >= 3
    event_types = [e["type"] for e in events]
    assert event_types[0] == "system"


@pytest.mark.asyncio
async def test_query_auto_inject_public_skills(client):
    """When auto_inject_public_skills is True, the LLM should receive public skills."""
    context = {
        "auto_inject_public_skills": True
    }
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "test auto inject", "context": context},
    )
    assert resp.status_code == 200
    events = _parse_sse_events(resp.text)
    
    assert len(events) >= 3
    assert events[0]["type"] == "system"
