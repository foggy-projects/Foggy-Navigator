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


@pytest.mark.asyncio
async def test_external_mode_is_explicitly_unready_and_fail_closed(client, monkeypatch):
    """External mode exposes health but blocks every business route before auth."""
    from langgraph_biz_worker import main as main_module

    monkeypatch.setattr(main_module.settings, "external_enabled", True)
    monkeypatch.setattr(main_module.settings, "worker_token", "")

    health = await client.get("/health")
    assert health.status_code == 200
    health_body = health.json()
    assert health_body["status"] == "degraded"
    assert health_body["ready"] is False
    assert health_body["mode"] == "external-enabled"
    assert health_body["external_enabled"] is True
    assert health_body["external_ready"] is False
    assert health_body["auth_configured"] is False
    assert health_body["reasons"] == [
        "EXTERNAL_AUTH_TOKEN_REQUIRED",
        "EXTERNAL_EXECUTION_POLICY_PENDING",
    ]

    response = await client.post("/api/v1/query", json={"prompt": "must not execute"})
    assert response.status_code == 503
    assert response.json() == {
        "detail": "External worker is not ready",
        "code": "EXTERNAL_WORKER_UNREADY",
        "reasons": [
            "EXTERNAL_AUTH_TOKEN_REQUIRED",
            "EXTERNAL_EXECUTION_POLICY_PENDING",
        ],
    }


@pytest.mark.asyncio
async def test_external_mode_token_does_not_bypass_pending_execution_policy(client, monkeypatch):
    from langgraph_biz_worker import main as main_module

    monkeypatch.setattr(main_module.settings, "external_enabled", True)
    monkeypatch.setattr(main_module.settings, "worker_token", "configured-secret")

    response = await client.post(
        "/api/v1/query",
        json={"prompt": "must not execute"},
        headers={"Authorization": "Bearer configured-secret"},
    )
    assert response.status_code == 503
    assert response.json()["reasons"] == ["EXTERNAL_EXECUTION_POLICY_PENDING"]
