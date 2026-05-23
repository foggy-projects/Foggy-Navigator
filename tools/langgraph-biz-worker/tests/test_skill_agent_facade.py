from __future__ import annotations

import pytest
from langchain_core.messages import AIMessage

from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.runtime.file_layout import require_standard_context_id
from langgraph_biz_worker.tools import LocalPythonToolProvider


class ScriptedModel:
    def __init__(self) -> None:
        self.bound_tools = []
        self.calls = 0

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


class OneToolThenSubmitModel:
    def __init__(self, tool_name: str, tool_args: dict | None = None) -> None:
        self.tool_name = tool_name
        self.tool_args = dict(tool_args or {})
        self.bound_tools = []
        self.messages = []
        self.calls = 0

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    def invoke(self, messages):
        self.messages.append(messages)
        self.calls += 1
        if self.calls == 1:
            return AIMessage(content="", tool_calls=[{
                "id": f"call_{self.tool_name}",
                "name": self.tool_name,
                "args": self.tool_args,
            }])
        return AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"done": True},
            },
        }])


class SubmitOnlyModel:
    def __init__(self) -> None:
        self.bound_tools = []

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    def invoke(self, messages):
        return AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {},
            },
        }])


def _write_skill(skills_root, skill_name: str, tools: str = "query_order") -> None:
    skill_dir = skills_root / skill_name
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(
        f"""---
name: order_internal
description: Order helper
tools: {tools}
---

Use {tools}, then submit the result.
""",
        encoding="utf-8",
    )


@pytest.mark.asyncio
async def test_skill_agent_ask_invokes_provider_tool_by_skill_name(tmp_path):
    skills_root = tmp_path / "skills"
    _write_skill(skills_root, "order-assistant")
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch an order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}

    model = ScriptedModel()
    agent = SkillAgent(skills_root, tool_provider=provider, model_provider=model)

    result = await agent.ask(skill_name="order-assistant", message="check order O-1001")

    assert result["ok"] is True
    assert result["skill_name"] == "order-assistant"
    assert result["summary"] == "Order fetched."
    assert result["structured_output"] == {"order_id": "O-1001", "status": "OPEN"}
    assert provider.calls[0]["tool_name"] == "query_order"
    assert {tool["function"]["name"] for tool in model.bound_tools} >= {"query_order", "submit_frame_result"}


@pytest.mark.asyncio
async def test_skill_agent_ask_generates_standard_context_directory_when_missing(tmp_path):
    skills_root = tmp_path / "skills"
    skill_dir = skills_root / "simple-assistant"
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(
        """---
name: simple_internal
description: Simple helper
---

Submit a concise result.
""",
        encoding="utf-8",
    )
    data_root = tmp_path / "data"
    agent = SkillAgent(skills_root, data_root=data_root, model_provider=SubmitOnlyModel())

    result = await agent.ask(skill_name="simple-assistant", message="hello")

    assert result["ok"] is True
    session_dirs = [
        path
        for path in (data_root / "runtime" / "sessions" / "by-date").rglob("bctx_*")
        if path.is_dir()
    ]
    assert len(session_dirs) == 1
    require_standard_context_id(session_dirs[0].name)
    non_standard_session_dirs = [
        path
        for path in (data_root / "runtime" / "sessions" / "by-date").rglob("*")
        if path.is_dir()
        and (path / "frames").is_dir()
        and not path.name.startswith("bctx_")
    ]
    assert non_standard_session_dirs == []


@pytest.mark.asyncio
async def test_skill_agent_filters_and_rejects_tools_not_allowed_by_upstream_policy(tmp_path):
    skills_root = tmp_path / "skills"
    _write_skill(skills_root, "order-assistant")
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch an order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}

    model = OneToolThenSubmitModel("query_order", {"order_id": "O-1001"})
    agent = SkillAgent(skills_root, tool_provider=provider, model_provider=model)

    result = await agent.ask(
        skill_name="order-assistant",
        message="check order O-1001",
        context={"execution_policy": {"allowed_tools": ["other_tool"]}},
    )

    assert result["ok"] is True
    assert "query_order" not in {tool["function"]["name"] for tool in model.bound_tools}
    assert provider.calls == []
    errors = [
        event.get("error")
        for event in result["events"]
        if event.get("tool_name") == "query_order"
        and event.get("error")
    ]
    assert errors == [
        "TOOL_NOT_AUTHORIZED: tool 'query_order' is not allowed by upstream execution_policy"
    ]


@pytest.mark.asyncio
async def test_skill_agent_passes_normalized_execution_policy_to_provider(tmp_path):
    skills_root = tmp_path / "skills"
    _write_skill(skills_root, "order-assistant", tools="inspect_policy")
    workdir = tmp_path / "workspace" / "project"
    workdir.mkdir(parents=True)
    provider = LocalPythonToolProvider()

    @provider.tool(description="Inspect execution policy")
    def inspect_policy(tool_context: dict) -> dict:
        return {
            "workdir": tool_context["workdir"],
            "allowed_dirs": tool_context["allowed_dirs"],
            "allowed_tools": tool_context["allowed_tools"],
        }

    model = OneToolThenSubmitModel("inspect_policy")
    agent = SkillAgent(skills_root, tool_provider=provider, model_provider=model)

    result = await agent.ask(
        skill_name="order-assistant",
        message="inspect policy",
        context={
            "order_id": "O-1001",
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_dirs": [str(workdir)],
                "allowed_tools": ["inspect_policy"],
            },
        },
    )

    assert result["ok"] is True
    assert provider.calls[0]["context"]["workdir"] == str(workdir.resolve())
    assert provider.calls[0]["context"]["allowed_dirs"] == [str(workdir.resolve())]
    assert provider.calls[0]["context"]["allowed_tools"] == ["inspect_policy"]
    assert provider.calls[0]["context"]["execution_policy"] == {
        "workdir": str(workdir.resolve()),
        "allowed_dirs": [str(workdir.resolve())],
        "allowed_tools": ["inspect_policy"],
    }
    joined_messages = "\n".join(str(message.content) for message in model.messages[0][:2])
    assert "execution_policy" not in joined_messages
    assert "allowed_dirs" not in joined_messages
