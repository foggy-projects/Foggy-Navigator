"""Tests for the /health endpoint."""

import pytest


@pytest.mark.asyncio
async def test_health_returns_200(client):
    """Health endpoint should return 200 with expected fields."""
    resp = await client.get("/health")
    assert resp.status_code == 200

    data = resp.json()
    assert "hostname" in data
    assert "version" in data
    assert "active_tasks" in data
    assert "worker_name" in data
    assert isinstance(data["active_tasks"], int)
    assert data["active_tasks"] >= 0


@pytest.mark.asyncio
async def test_health_version_matches_package(client):
    """Health should report the version from __init__.py."""
    from langgraph_biz_worker import __version__

    resp = await client.get("/health")
    data = resp.json()
    assert data["version"] == __version__
