"""Tests for Bearer token authentication (P0)."""

from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app


@pytest.fixture
async def authed_client():
    """Client with worker_token enabled — must supply Bearer header."""
    with patch("langgraph_biz_worker.auth.settings") as mock_settings:
        mock_settings.worker_token = "secret-test-token"
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c


@pytest.mark.asyncio
async def test_valid_bearer_token_accepted(authed_client):
    """Request with correct Bearer token should succeed."""
    resp = await authed_client.post(
        "/api/v1/query",
        json={"prompt": "test"},
        headers={"Authorization": "Bearer secret-test-token"},
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_invalid_bearer_token_returns_401(authed_client):
    """Request with wrong Bearer token should return 401."""
    resp = await authed_client.post(
        "/api/v1/query",
        json={"prompt": "test"},
        headers={"Authorization": "Bearer wrong-token"},
    )
    assert resp.status_code == 401
    assert "Invalid or missing bearer token" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_missing_authorization_header_returns_401(authed_client):
    """Request with no Authorization header should return 401."""
    resp = await authed_client.post(
        "/api/v1/query",
        json={"prompt": "test"},
    )
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_health_bypasses_auth(authed_client):
    """GET /health should work without any auth (no Depends(verify_token))."""
    resp = await authed_client.get("/health")
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_dev_mode_no_token_required(client):
    """With empty worker_token (dev mode), no auth is needed."""
    resp = await client.post(
        "/api/v1/query",
        json={"prompt": "dev mode test"},
    )
    assert resp.status_code == 200
