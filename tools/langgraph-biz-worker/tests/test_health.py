"""Tests for the /health endpoint."""

import pytest


@pytest.mark.asyncio
async def test_health_returns_200(client, monkeypatch):
    """Health endpoint should return 200 with expected fields."""
    from langgraph_biz_worker.routes import health as health_route

    monkeypatch.setattr(health_route.settings, "max_agent_nesting_depth", 1)

    resp = await client.get("/health")
    assert resp.status_code == 200

    data = resp.json()
    assert "hostname" in data
    assert "version" in data
    assert "active_tasks" in data
    assert "worker_name" in data
    assert isinstance(data["active_tasks"], int)
    assert data["active_tasks"] >= 0
    assert data["capabilities"]["agent_delegation"]["max_agent_nesting_depth"] == 1
    assert data["capabilities"]["agent_delegation"]["nested_agent_delegation_allowed"] is False
    assert data["capabilities"]["agent_delegation"]["child_agent_inherits_parent_tools"] is False
    assert data["capabilities"]["agent_delegation"]["tools"]["spawn_agent"]["tool_name"] == "invoke_business_agent"


@pytest.mark.asyncio
async def test_health_version_matches_package(client):
    """Health should report the version from __init__.py."""
    from langgraph_biz_worker import __version__

    resp = await client.get("/health")
    data = resp.json()
    assert data["version"] == __version__
