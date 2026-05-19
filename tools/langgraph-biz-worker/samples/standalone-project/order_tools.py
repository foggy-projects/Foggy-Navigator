from __future__ import annotations


def register_tools(provider) -> None:
    @provider.tool(description="Fetch one order by id")
    def query_order(order_id: str) -> dict[str, str]:
        return {"order_id": order_id, "status": "OPEN", "owner": "demo-account"}
