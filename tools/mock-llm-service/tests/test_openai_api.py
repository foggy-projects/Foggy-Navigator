import json
import time

import pytest
from httpx import AsyncClient, ASGITransport
from mock_llm.main import app


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.mark.anyio
async def test_health_check(client):
    """测试健康检查端点"""
    response = await client.get("/admin/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "rules_count" in data


@pytest.mark.anyio
async def test_list_responses(client):
    """测试列出响应规则"""
    response = await client.get("/admin/responses")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)


@pytest.mark.anyio
async def test_chat_completions_sync(client):
    """测试同步对话响应"""
    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [{"role": "user", "content": "你好"}],
            "stream": False,
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert "id" in data
    assert "choices" in data
    assert len(data["choices"]) > 0
    assert data["choices"][0]["message"]["role"] == "assistant"


@pytest.mark.anyio
async def test_chat_completions_stream(client):
    """测试流式对话响应"""
    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [{"role": "user", "content": "hello"}],
            "stream": True,
        },
    )
    assert response.status_code == 200
    assert response.headers["content-type"] == "text/event-stream; charset=utf-8"


@pytest.mark.anyio
async def test_keyword_matching(client):
    """测试关键词匹配"""
    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [{"role": "user", "content": "你好，请问"}],
            "stream": False,
        },
    )
    assert response.status_code == 200
    data = response.json()
    content = data["choices"][0]["message"]["content"]
    assert "Mock LLM" in content or "你好" in content


@pytest.mark.anyio
async def test_reload_responses(client):
    """测试重新加载配置"""
    response = await client.post("/admin/reload")
    assert response.status_code == 200
    data = response.json()
    assert "message" in data
    assert "count" in data


@pytest.mark.anyio
async def test_langgraph_biz_worker_skill_tool_call_sequence(client):
    """测试 Biz Worker Skill 场景可按 tool result 推进。"""
    first = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [{"role": "user", "content": "SKILL_AGENT_START exception_triage"}],
            "stream": False,
        },
    )
    assert first.status_code == 200
    first_call = first.json()["choices"][0]["message"]["tool_calls"][0]
    assert first_call["function"]["name"] == "mock_get_order"

    second = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [
                {"role": "user", "content": "SKILL_AGENT_START exception_triage"},
                {"role": "tool", "content": '{"delay_minutes":45,"vehicle_id":"V09"}'},
            ],
            "stream": False,
        },
    )
    assert second.status_code == 200
    second_call = second.json()["choices"][0]["message"]["tool_calls"][0]
    assert second_call["function"]["name"] == "mock_get_vehicle_status"

    third = await client.post(
        "/v1/chat/completions",
        json={
            "model": "mock-model",
            "messages": [
                {"role": "user", "content": "SKILL_AGENT_START exception_triage"},
                {"role": "tool", "content": '{"status":"breakdown","error_code":"E_MOTOR_OVERHEAT"}'},
            ],
            "stream": False,
        },
    )
    assert third.status_code == 200
    third_call = third.json()["choices"][0]["message"]["tool_calls"][0]
    assert third_call["function"]["name"] == "submit_skill_result"


@pytest.mark.anyio
async def test_scripted_cursor_tool_call_and_debug_requests(client):
    """测试 E2E script 可按首轮 cursor 返回 tool call 并记录 debug request。"""
    trace_id = "e2e-script-001"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    register = await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "scenarioId": "tms-create-order",
            "turns": [
                {
                    "cursor": f"next:{trace_id}:001",
                    "response": {
                        "content": "",
                        "tool_calls": [
                            {
                                "function": {
                                    "name": "tms.order.createOpeningDraft",
                                    "arguments": {
                                        "e2eTraceId": trace_id,
                                        "next": f"next:{trace_id}:002",
                                    },
                                }
                            }
                        ],
                    },
                }
            ],
        },
    )
    assert register.status_code == 200
    assert register.json()["turns"] == 1

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-biz-worker-v1",
            "messages": [
                {
                    "role": "user",
                    "content": f"create order e2eTraceId={trace_id} next:{trace_id}:001",
                }
            ],
        },
    )

    assert response.status_code == 200
    data = response.json()
    message = data["choices"][0]["message"]
    assert data["id"].startswith("chatcmpl-")
    assert data["choices"][0]["finish_reason"] == "tool_calls"
    assert message["tool_calls"][0]["function"]["name"] == "tms.order.createOpeningDraft"
    assert f"next:{trace_id}:002" in message["tool_calls"][0]["function"]["arguments"]

    debug = await client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert len(records) == 1
    assert records[0]["cursor"] == f"next:{trace_id}:001"
    assert records[0]["scenarioId"] == "tms-create-order"
    assert records[0]["responseSummary"]["toolCalls"] == ["tms.order.createOpeningDraft"]


@pytest.mark.anyio
async def test_scripted_cursor_can_start_from_system_message(client):
    """测试系统上下文中的首轮 cursor 可驱动 Java Navi -> BizWorker scripted smoke。"""
    trace_id = "e2e-script-system-cursor-001"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    register = await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "scenarioId": "system-context-script",
            "turns": [
                {
                    "cursor": f"next:{trace_id}:001",
                    "response": {
                        "content": "OK_SYSTEM_CURSOR"
                    },
                }
            ],
        },
    )
    assert register.status_code == 200

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-scripted",
            "messages": [
                {"role": "system", "content": f"script starts at next:{trace_id}:001"},
                {"role": "user", "content": "Business Agent task bt_001"},
            ],
        },
    )

    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "OK_SYSTEM_CURSOR"


@pytest.mark.anyio
async def test_scripted_duplicate_cursor_uses_registered_sequence(client):
    """同一用户消息触发多个 LLM 调用时，可按注册顺序返回不同响应。"""
    trace_id = "e2e-script-duplicate-cursor-001"
    cursor = f"next:{trace_id}:001"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    script_payload = {
        "traceId": trace_id,
        "scenarioId": "duplicate-cursor-sequence",
        "turns": [
            {
                "cursor": cursor,
                "response": {
                    "tool_calls": [
                        {
                            "name": "handoff_to_parent",
                            "args": {
                                "summary": "child asks parent to decide",
                                "requires_parent_synthesis": True,
                            },
                        }
                    ],
                },
            },
            {
                "cursor": cursor,
                "response": {
                    "tool_calls": [
                        {
                            "name": "submit_skill_result",
                            "args": {
                                "summary": "parent synthesized final answer",
                                "structured_output": {"ok": True},
                            },
                        }
                    ],
                },
            },
        ],
    }
    register = await client.post("/__e2e/scripts", json=script_payload)
    assert register.status_code == 200
    assert register.json()["turns"] == 2

    payload = {
        "model": "navigator-e2e-scripted",
        "messages": [{"role": "user", "content": f"same prompt {cursor}"}],
    }
    first = await client.post("/v1/chat/completions", json=payload)
    second = await client.post("/v1/chat/completions", json=payload)
    third = await client.post("/v1/chat/completions", json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    assert third.status_code == 200
    assert first.json()["choices"][0]["message"]["tool_calls"][0]["function"]["name"] == "handoff_to_parent"
    assert second.json()["choices"][0]["message"]["tool_calls"][0]["function"]["name"] == "submit_skill_result"
    assert third.json()["choices"][0]["message"]["tool_calls"][0]["function"]["name"] == "submit_skill_result"

    debug = await client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert [record["cursor"] for record in records] == [cursor, cursor, cursor]
    assert [record["responseSummary"]["toolCalls"] for record in records] == [
        ["handoff_to_parent"],
        ["submit_skill_result"],
        ["submit_skill_result"],
    ]

    register_again = await client.post("/__e2e/scripts", json=script_payload)
    assert register_again.status_code == 200
    reset = await client.post("/v1/chat/completions", json=payload)
    assert reset.status_code == 200
    assert reset.json()["choices"][0]["message"]["tool_calls"][0]["function"]["name"] == "handoff_to_parent"


@pytest.mark.anyio
async def test_scripted_response_can_delay_before_reply(client):
    trace_id = "e2e-script-delay-001"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    register = await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "scenarioId": "slow-provider",
            "turns": [
                {
                    "cursor": f"next:{trace_id}:001",
                    "response": {
                        "content": "slow ok",
                        "delay_ms": 120,
                    },
                }
            ],
        },
    )
    assert register.status_code == 200

    started = time.monotonic()
    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-scripted",
            "messages": [
                {
                    "role": "user",
                    "content": f"run slow provider next:{trace_id}:001",
                }
            ],
        },
    )
    elapsed = time.monotonic() - started

    assert response.status_code == 200
    assert elapsed >= 0.10
    assert response.json()["choices"][0]["message"]["content"] == "slow ok"

    debug = await client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert records[0]["responseSummary"]["responseDelayMs"] == 120


@pytest.mark.anyio
async def test_scripted_stream_accepts_langchain_style_tool_call(client):
    """测试 streaming scripted response 支持 name/args 形式的 tool call。"""
    trace_id = "e2e-script-stream-tool-001"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    register = await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "scenarioId": "tms-tool-loop",
            "turns": [
                {
                    "cursor": f"next:{trace_id}:001",
                    "response": {
                        "tool_calls": [
                            {
                                "name": "invoke_business_skill",
                                "args": {
                                    "skill_id": "foggy-query-agent",
                                    "instruction": f"query and continue next:{trace_id}:002",
                                },
                            }
                        ],
                    },
                }
            ],
        },
    )
    assert register.status_code == 200

    async with client.stream(
        "POST",
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-scripted",
            "messages": [
                {
                    "role": "user",
                    "content": f"run e2eTraceId={trace_id} next:{trace_id}:001",
                }
            ],
            "stream": True,
        },
    ) as response:
        assert response.status_code == 200
        body = await response.aread()

    text = body.decode("utf-8")
    assert "invoke_business_skill" in text
    assert '"finish_reason":"tool_calls"' in text
    assert "data: [DONE]" in text
    argument_chunks = []
    for event in text.split("\n\n"):
        if not event.startswith("data: {"):
            continue
        data = json.loads(event.removeprefix("data: "))
        tool_calls = data["choices"][0]["delta"].get("tool_calls") or []
        for tool_call in tool_calls:
            function = tool_call.get("function") or {}
            if "arguments" in function:
                argument_chunks.append(function["arguments"])
    arguments = "".join(argument_chunks)
    assert "foggy-query-agent" in arguments
    assert f"next:{trace_id}:002" in arguments

    debug = await client.get("/__debug/requests", params={"traceId": trace_id})
    assert debug.status_code == 200
    records = debug.json()
    assert len(records) == 1
    assert records[0]["responseSummary"]["toolCalls"] == ["invoke_business_skill"]


@pytest.mark.anyio
async def test_scripted_cursor_advances_from_assistant_tool_call_arguments(client):
    """测试后续轮次可从 assistant tool_calls arguments 中解析 cursor。"""
    trace_id = "e2e-script-002"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "turns": [
                {
                    "cursor": f"next:{trace_id}:002",
                    "response": {
                        "content": "{\"summary\":\"done\"}",
                    },
                }
            ],
        },
    )

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-biz-worker-v1",
            "messages": [
                {"role": "user", "content": f"start next:{trace_id}:001"},
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                                "name": "mock_tool",
                                "arguments": f"{{\"next\":\"next:{trace_id}:002\"}}",
                            },
                        }
                    ],
                },
                {"role": "tool", "tool_call_id": "call_1", "content": "{\"ok\":true}"},
            ],
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["choices"][0]["finish_reason"] == "stop"
    assert data["choices"][0]["message"]["content"] == "{\"summary\":\"done\"}"


@pytest.mark.anyio
async def test_scripted_cursor_advances_from_latest_tool_message_content(client):
    """测试 tool result content 中的 cursor 优先驱动下一轮。"""
    trace_id = "e2e-script-002b"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "turns": [
                {
                    "cursor": f"next:{trace_id}:003",
                    "response": {
                        "content": "{\"summary\":\"tool-result-cursor\"}",
                    },
                }
            ],
        },
    )

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-biz-worker-v1",
            "messages": [
                {"role": "user", "content": f"start next:{trace_id}:001"},
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "id": "call_1",
                            "type": "function",
                            "function": {
                                "name": "mock_tool",
                                "arguments": f"{{\"next\":\"next:{trace_id}:002\"}}",
                            },
                        }
                    ],
                },
                {
                    "role": "tool",
                    "tool_call_id": "call_1",
                    "content": f"{{\"ok\":true,\"next\":\"next:{trace_id}:003\"}}",
                },
            ],
        },
    )

    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "{\"summary\":\"tool-result-cursor\"}"


@pytest.mark.anyio
async def test_scripted_cursor_uses_last_cursor_inside_message(client):
    """同一条消息内有旧摘要和当前指令时，应选择靠后的当前 cursor。"""
    trace_id = "e2e-script-current-cursor"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "turns": [
                {
                    "cursor": f"next:{trace_id}:002",
                    "response": {
                        "content": "{\"summary\":\"current-cursor\"}",
                    },
                }
            ],
        },
    )

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-biz-worker-v1",
            "messages": [
                {
                    "role": "user",
                    "content": (
                        f"Previous summary next:{trace_id}:001\n"
                        f"User request: continue next:{trace_id}:002"
                    ),
                },
            ],
        },
    )

    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "{\"summary\":\"current-cursor\"}"


@pytest.mark.anyio
async def test_scripted_cursor_idempotent_for_duplicate_request(client):
    """测试相同 trace/cursor/request 重复调用返回稳定 completion/tool_call id。"""
    trace_id = "e2e-script-003"
    await client.delete(f"/__e2e/scripts/{trace_id}")
    await client.post(
        "/__e2e/scripts",
        json={
            "traceId": trace_id,
            "turns": [
                {
                    "cursor": f"next:{trace_id}:001",
                    "response": {
                        "content": "",
                        "tool_calls": [
                            {
                                "function": {
                                    "name": "mock_tool",
                                    "arguments": {"next": f"next:{trace_id}:002"},
                                }
                            }
                        ],
                    },
                }
            ],
        },
    )
    payload = {
        "model": "navigator-e2e-biz-worker-v1",
        "messages": [{"role": "user", "content": f"start next:{trace_id}:001"}],
    }

    first = await client.post("/v1/chat/completions", json=payload)
    second = await client.post("/v1/chat/completions", json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    first_data = first.json()
    second_data = second.json()
    assert first_data["id"] == second_data["id"]
    assert (
        first_data["choices"][0]["message"]["tool_calls"][0]["id"]
        == second_data["choices"][0]["message"]["tool_calls"][0]["id"]
    )


@pytest.mark.anyio
async def test_scripted_cursor_trace_isolation(client):
    """测试不同 traceId 的相同 turnIndex 不串场。"""
    trace_a = "e2e-script-004a"
    trace_b = "e2e-script-004b"
    await client.delete(f"/__e2e/scripts/{trace_a}")
    await client.delete(f"/__e2e/scripts/{trace_b}")
    for trace_id, content in [(trace_a, "response-a"), (trace_b, "response-b")]:
        await client.post(
            "/__e2e/scripts",
            json={
                "traceId": trace_id,
                "turns": [
                    {
                        "cursor": f"next:{trace_id}:001",
                        "response": {"content": content},
                    }
                ],
            },
        )

    response = await client.post(
        "/v1/chat/completions",
        json={
            "model": "navigator-e2e-biz-worker-v1",
            "messages": [{"role": "user", "content": f"start next:{trace_b}:001"}],
        },
    )

    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "response-b"
