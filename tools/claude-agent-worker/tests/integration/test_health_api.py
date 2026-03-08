"""Integration tests for GET /health."""

from __future__ import annotations

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
class TestHealthEndpoint:
    """GET /health returns basic service info (no auth required)."""

    async def test_returns_200(self, client: AsyncClient):
        resp = await client.get("/health")
        assert resp.status_code == 200

    async def test_contains_required_fields(self, client: AsyncClient):
        resp = await client.get("/health")
        body = resp.json()
        assert "hostname" in body
        assert "version" in body
        assert "active_tasks" in body
        assert "claude_cli_available" in body

    async def test_active_tasks_initially_zero(self, client: AsyncClient):
        resp = await client.get("/health")
        body = resp.json()
        assert body["active_tasks"] == 0

    async def test_worker_name_present(self, client: AsyncClient):
        resp = await client.get("/health")
        body = resp.json()
        # worker_name can be None or a string — just ensure the key exists
        assert "worker_name" in body
