from __future__ import annotations

import asyncio
from pathlib import Path

from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.tools import LocalPythonToolProvider

from order_model import create_model
from order_tools import register_tools


async def main() -> None:
    project_root = Path(__file__).resolve().parent
    provider = LocalPythonToolProvider()
    register_tools(provider)

    agent = SkillAgent(
        project_root / "skills",
        data_root=project_root / ".runtime" / "data",
        tool_provider=provider,
        model_provider=create_model(),
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
