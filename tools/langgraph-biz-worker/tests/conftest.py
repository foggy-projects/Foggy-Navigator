"""Shared test fixtures for langgraph-biz-worker."""

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app


@pytest.fixture(autouse=True)
def isolate_llm_execution_default(monkeypatch):
    """Keep default HTTP tests independent from local .env LLM settings.

    Scripted LLM tests explicitly re-enable the LLM path with their own mock
    provider. Legacy route/skill tests should remain deterministic even when a
    developer's local worker env points at a real provider.
    """
    from langgraph_biz_worker.graphs import root_graph as root_graph_module

    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", False)


@pytest.fixture
async def client():
    """Async HTTP client bound to the FastAPI app."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c
