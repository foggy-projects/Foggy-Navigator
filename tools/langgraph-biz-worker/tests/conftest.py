"""Shared test fixtures for langgraph-biz-worker."""

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app


@pytest.fixture
async def client():
    """Async HTTP client bound to the FastAPI app."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
