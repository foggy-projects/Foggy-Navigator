import pytest
from httpx import ASGITransport, AsyncClient

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
async def test_messages_accepts_system_content_blocks(client):
    response = await client.post(
        "/v1/messages",
        json={
            "model": "claude-sonnet-4-6",
            "max_tokens": 256,
            "system": [
                {"type": "text", "text": "You are a test system prompt."},
                {"type": "text", "text": "Cache me.", "cache_control": {"type": "ephemeral"}},
            ],
            "messages": [
                {"role": "user", "content": "e2e-greeting-test: hello from anthropic"},
            ],
            "stream": False,
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["type"] == "message"
    assert data["role"] == "assistant"
    assert isinstance(data["content"], list)
    assert any(block["type"] == "text" for block in data["content"])
