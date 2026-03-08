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
