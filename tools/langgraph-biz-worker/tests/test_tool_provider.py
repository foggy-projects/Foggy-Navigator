from langgraph_biz_worker.runtime.tool_provider import LocalPythonToolProvider, MockToolProvider, ToolSpec


def test_local_python_tool_provider_registers_and_calls_function():
    provider = LocalPythonToolProvider()

    @provider.tool(description="Fetch an order")
    def query_order(order_id: str) -> dict:
        return {"order_id": order_id, "status": "OPEN"}

    specs = provider.list_tools("order-assistant")

    assert [spec.name for spec in specs] == ["query_order"]
    assert specs[0].parameters["properties"]["order_id"]["type"] == "string"
    assert provider.call_tool("query_order", {"order_id": "O-1"}) == {"order_id": "O-1", "status": "OPEN"}
    assert provider.calls[0]["tool_name"] == "query_order"


def test_mock_tool_provider_records_calls():
    provider = MockToolProvider(
        specs=[ToolSpec(name="ping")],
        results={"ping": {"ok": True, "result": "pong"}},
    )

    assert provider.call_tool("ping", {}, {"skill_name": "test"}) == {"ok": True, "result": "pong"}
    assert provider.calls == [{
        "tool_name": "ping",
        "arguments": {},
        "context": {"skill_name": "test"},
    }]


def test_local_python_tool_provider_injects_reserved_tool_context():
    provider = LocalPythonToolProvider()

    @provider.tool(description="Read execution context")
    def inspect_policy(tool_context: dict) -> dict:
        return {
            "workdir": tool_context["workdir"],
            "allowed_tools": tool_context["allowed_tools"],
        }

    spec = provider.list_tools("order-assistant")[0]
    result = provider.call_tool(
        "inspect_policy",
        {},
        {"workdir": "/tmp/project", "allowed_tools": ["inspect_policy"]},
    )

    assert "tool_context" not in spec.parameters["properties"]
    assert spec.parameters["required"] == []
    assert result == {
        "workdir": "/tmp/project",
        "allowed_tools": ["inspect_policy"],
    }
