from __future__ import annotations

from langchain_core.messages import AIMessage


class ScriptedOrderModel:
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


def create_model() -> ScriptedOrderModel:
    return ScriptedOrderModel()
