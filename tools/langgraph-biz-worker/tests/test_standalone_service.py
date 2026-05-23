from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage

from langgraph_biz_worker.routes import standalone
from langgraph_biz_worker.tools import LocalPythonToolProvider


class ScriptedToolModel:
    def __init__(self) -> None:
        self.calls = 0
        self.bound_tools = []

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    def invoke(self, messages):
        self.calls += 1
        if self.calls == 1:
            return AIMessage(content="", tool_calls=[{
                "id": "call_query_order",
                "name": "query_order",
                "args": {"order_id": "O-1001"},
            }])
        return AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Order fetched.",
                "structured_output": {"order_id": "O-1001", "status": "OPEN"},
            },
        }])


@pytest.fixture(autouse=True)
def standalone_roots(tmp_path):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"
    standalone.configure(skills_root, data_root=data_root)
    return {"skills_root": skills_root, "data_root": data_root}


@pytest.mark.asyncio
async def test_standalone_skill_crud_flow(client, standalone_roots):
    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "description": "Order helper",
            "instructions": "Use query_order, then submit a concise result.",
            "tools": ["query_order"],
            "resources": {"resources/example.txt": "order O-1001"},
        },
    )

    assert response.status_code == 201
    created = response.json()
    assert created["skill_name"] == "order-assistant"
    assert created["manifest"]["manifest_id"] == "order-assistant"
    assert created["manifest"]["allowed_tools"] == ["query_order"]
    assert created["validation"]["ok"] is True
    assert (
        standalone_roots["skills_root"] / "order-assistant" / "resources" / "example.txt"
    ).read_text(encoding="utf-8") == "order O-1001"

    response = await client.get("/api/v1/skills")
    assert response.status_code == 200
    skills = response.json()["skills"]
    assert [skill["skill_name"] for skill in skills] == ["order-assistant"]

    response = await client.get("/api/v1/skills/order-assistant")
    assert response.status_code == 200
    loaded = response.json()
    assert "Use query_order" in loaded["content"]

    response = await client.post("/api/v1/skills/order-assistant/validate")
    assert response.status_code == 200
    assert response.json()["ok"] is True

    response = await client.delete("/api/v1/skills/order-assistant")
    assert response.status_code == 200
    assert response.json() == {"deleted": True, "skill_name": "order-assistant"}

    response = await client.get("/api/v1/skills/order-assistant")
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_standalone_skill_route_rejects_path_traversal(client, standalone_roots):
    response = await client.post(
        "/api/v1/skills",
        json={"skill_name": "../escape", "instructions": "Unsafe."},
    )

    assert response.status_code == 400
    assert not (standalone_roots["skills_root"].parent / "escape").exists()

    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "instructions": "Unsafe resource path.",
            "resources": {"../escape.txt": "x"},
        },
    )

    assert response.status_code == 400
    assert not (standalone_roots["skills_root"].parent / "escape.txt").exists()
    assert not (standalone_roots["skills_root"] / "order-assistant").exists()

    response = await client.delete("/api/v1/skills/public")
    assert response.status_code == 400


@pytest.mark.asyncio
async def test_standalone_ask_uses_skill_name_without_navigator_java(client):
    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "description": "Order helper",
            "instructions": "Submit a concise result.",
        },
    )
    assert response.status_code == 201

    response = await client.post(
        "/api/v1/ask",
        json={
            "skill_name": "order-assistant",
            "message": "Check order O-1001",
            "context": {"account_id": "demo-account", "order_id": "O-1001"},
        },
    )

    assert response.status_code == 200
    result = response.json()
    assert result["ok"] is True
    assert result["skill_name"] == "order-assistant"
    assert result["summary"] == "Skill completed."
    assert result["structured_output"] == {}


@pytest.mark.asyncio
async def test_standalone_ask_accepts_prompt_alias(client):
    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "instructions": "Submit a concise result.",
        },
    )
    assert response.status_code == 201

    response = await client.post(
        "/api/v1/ask",
        json={"skill_name": "order-assistant", "prompt": "Check order O-1001"},
    )

    assert response.status_code == 200
    assert response.json()["skill_name"] == "order-assistant"


@pytest.mark.asyncio
async def test_standalone_ask_uses_configured_tool_provider(client, standalone_roots):
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}

    model = ScriptedToolModel()
    standalone.configure(
        standalone_roots["skills_root"],
        data_root=standalone_roots["data_root"],
        tool_provider=provider,
        model_provider=model,
    )

    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "description": "Order helper",
            "instructions": "Use query_order, then submit a concise result.",
            "tools": ["query_order"],
        },
    )
    assert response.status_code == 201

    response = await client.post(
        "/api/v1/ask",
        json={"skill_name": "order-assistant", "message": "Check order O-1001"},
    )

    assert response.status_code == 200
    result = response.json()
    assert result["ok"] is True
    assert result["summary"] == "Order fetched."
    assert result["structured_output"] == {"order_id": "O-1001", "status": "OPEN"}
    assert provider.calls[0]["tool_name"] == "query_order"
    assert {tool["function"]["name"] for tool in model.bound_tools} >= {
        "query_order",
        "submit_frame_result",
    }


@pytest.mark.asyncio
async def test_standalone_ask_rejects_workdir_outside_allowed_dirs(client, standalone_roots):
    response = await client.post(
        "/api/v1/skills",
        json={
            "skill_name": "order-assistant",
            "instructions": "Submit a concise result.",
        },
    )
    assert response.status_code == 201

    response = await client.post(
        "/api/v1/ask",
        json={
            "skill_name": "order-assistant",
            "message": "Check order O-1001",
            "context": {
                "execution_policy": {
                    "workdir": str(standalone_roots["skills_root"]),
                    "allowed_dirs": [str(standalone_roots["data_root"])],
                }
            },
        },
    )

    assert response.status_code == 400
    assert "WORKDIR_NOT_AUTHORIZED" in response.text


@pytest.mark.asyncio
async def test_standalone_status_reports_non_sensitive_provider_state(client, standalone_roots):
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id}

    class SecretBearingModel:
        def __repr__(self) -> str:
            return "SecretBearingModel(api_key='sk-should-not-leak')"

    standalone.configure(
        standalone_roots["skills_root"],
        data_root=standalone_roots["data_root"],
        tool_provider=provider,
        model_provider=SecretBearingModel(),
        tool_modules=["sample_project.order_tools"],
        model_provider_configured=True,
        llm_provider="openai",
    )

    response = await client.get("/api/v1/standalone/status")

    assert response.status_code == 200
    status = response.json()
    assert status["configured"] is True
    assert status["skillsRoot"] == str(standalone_roots["skills_root"])
    assert status["dataRoot"] == str(standalone_roots["data_root"])
    assert status["toolModules"] == ["sample_project.order_tools"]
    assert status["loadedTools"] == ["query_order"]
    assert status["modelProviderConfigured"] is True
    assert status["llmProvider"] == "openai"
    assert "sk-should-not-leak" not in response.text
    assert "worker-token" not in response.text
