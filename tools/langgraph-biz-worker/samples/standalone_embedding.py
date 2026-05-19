"""Minimal embedded SkillAgent sample.

Run from ``tools/langgraph-biz-worker``:

    $env:PYTHONPATH = "src"
    python samples/standalone_embedding.py
"""

from __future__ import annotations

import asyncio
from pathlib import Path

from langchain_core.messages import AIMessage

from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.tools import LocalPythonToolProvider


class ScriptedOrderModel:
    """Tiny deterministic model for local smoke checks."""

    def __init__(self) -> None:
        self.calls = 0

    def bind_tools(self, tools):
        self.tools = tools
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
                "summary": "Order O-1001 is OPEN.",
                "structured_output": {"order_id": "O-1001", "status": "OPEN"},
            },
        }])


async def main() -> None:
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch one order by id")
    def query_order(order_id: str) -> dict[str, str]:
        return {"order_id": order_id, "status": "OPEN", "owner": "demo-account"}

    runtime_root = Path(__file__).parent / ".runtime"
    agent = SkillAgent(
        runtime_root / "skills",
        data_root=runtime_root / "data",
        tool_provider=provider,
        model_provider=ScriptedOrderModel(),
    )
    agent.register_skill(
        "order-assistant",
        description="Standalone order helper sample.",
        instructions="Use query_order when order details are needed, then submit a concise result.",
        tools=["query_order"],
        overwrite=True,
    )

    result = await agent.ask(
        skill_name="order-assistant",
        message="Check order O-1001",
        context={"account_id": "demo-account", "order_id": "O-1001"},
    )
    print(result["summary"])
    print(result["structured_output"])


if __name__ == "__main__":
    asyncio.run(main())
