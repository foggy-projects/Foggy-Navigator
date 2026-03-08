"""Phase 4 — Full E2E tests: HTTP → worker → SDK → Claude CLI → mock-llm-service.

Each test sends a real HTTP request through the FastAPI worker, which spawns
a real Claude CLI subprocess.  The CLI hits the mock-llm-service (running as
a subprocess on a free port) and returns Anthropic-format SSE events.
The worker maps them to QueryEvent SSE and streams them back.

Prerequisites (auto-skipped when absent):
  * ``claude`` CLI on PATH  (claude-agent-sdk installed)
  * ``mock-llm-service`` source accessible at ``../../mock-llm-service``

Marker: ``@pytest.mark.e2e``  — exclude with ``pytest -m 'not e2e'``.
"""

from __future__ import annotations

import pytest
from httpx import AsyncClient

from .conftest import HAS_CLI, collect_sse_events

pytestmark = [
    pytest.mark.e2e,
    pytest.mark.skipif(not HAS_CLI, reason="Claude CLI not available"),
]


def _query_body(prompt: str, cwd: str, **overrides) -> dict:
    """Build a QueryRequest payload for E2E tests."""
    base = {
        "prompt": prompt,
        "cwd": cwd,
        "max_turns": 1,         # single-turn — no multi-step loops
        "model": "sonnet",      # fast model for testing
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# Core E2E — full query chain
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestE2EFullChain:
    """Verify the complete query pipeline: HTTP → CLI → mock API → SSE."""

    async def test_query_returns_assistant_text_and_result(
        self, client: AsyncClient, tmp_path,
    ):
        """A simple prompt should produce at least one assistant_text and one result event."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: Please say hello",
                cwd=str(tmp_path),
            ),
        )

        types = [e.get("type") for e in events]
        assert "assistant_text" in types, (
            f"Expected 'assistant_text' in event types but got: {types}"
        )
        assert "result" in types, (
            f"Expected 'result' in event types but got: {types}"
        )

    async def test_response_contains_mock_content(
        self, client: AsyncClient, tmp_path,
    ):
        """The assistant_text events should contain text from the mock rule."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: Please respond",
                cwd=str(tmp_path),
            ),
        )

        text_events = [e for e in events if e.get("type") == "assistant_text"]
        all_text = " ".join(e.get("content", "") for e in text_events)

        # The mock rule "e2e-greeting" returns content containing "E2E test response"
        assert "E2E test response" in all_text or "mock" in all_text.lower(), (
            f"Expected mock content in assistant text but got: {all_text[:300]}"
        )

    async def test_default_response_for_unknown_prompt(
        self, client: AsyncClient, tmp_path,
    ):
        """A prompt without keyword match should get the default mock response."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="A completely unrelated prompt with no matching keywords 12345",
                cwd=str(tmp_path),
            ),
        )

        types = [e.get("type") for e in events]
        # Should still produce events (the default rule fires)
        assert "assistant_text" in types, (
            f"Expected events from default rule but got: {types}"
        )
        assert "result" in types


# ---------------------------------------------------------------------------
# Result event metadata
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestE2EResultMetadata:
    """Verify metadata carried by the ``result`` event."""

    async def test_result_has_session_id(
        self, client: AsyncClient, tmp_path,
    ):
        """The result event should carry a session_id assigned by the CLI."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: hello",
                cwd=str(tmp_path),
            ),
        )

        result_events = [e for e in events if e.get("type") == "result"]
        assert len(result_events) == 1, f"Expected 1 result event, got {len(result_events)}"

        result = result_events[0]
        # session_id is assigned by the CLI — should be a non-empty string
        session_id = result.get("session_id")
        assert session_id, (
            f"Expected non-empty session_id in result but got: {result}"
        )

    async def test_result_has_task_id(
        self, client: AsyncClient, tmp_path,
    ):
        """The result event should carry a task_id from the worker."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: hello",
                cwd=str(tmp_path),
            ),
        )

        result_events = [e for e in events if e.get("type") == "result"]
        assert len(result_events) == 1

        result = result_events[0]
        assert result.get("task_id"), (
            f"Expected task_id in result but got: {result}"
        )

    async def test_result_has_token_counts(
        self, client: AsyncClient, tmp_path,
    ):
        """The result event should report input_tokens and output_tokens."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: hello",
                cwd=str(tmp_path),
            ),
        )

        result_events = [e for e in events if e.get("type") == "result"]
        assert len(result_events) == 1

        result = result_events[0]
        # Token counts come from the SDK ResultMessage — should be present
        assert result.get("input_tokens") is not None or result.get("output_tokens") is not None, (
            f"Expected token counts in result but got: {result}"
        )


# ---------------------------------------------------------------------------
# Coding prompt scenario
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestE2ECodingScenario:
    """Exercise the coding response rule through the full chain."""

    async def test_coding_prompt_returns_code(
        self, client: AsyncClient, tmp_path,
    ):
        """A coding prompt should return assistant_text containing code."""
        events = await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-coding-test: Write a hello function",
                cwd=str(tmp_path),
            ),
        )

        text_events = [e for e in events if e.get("type") == "assistant_text"]
        all_text = " ".join(e.get("content", "") for e in text_events)

        # The mock rule returns content with "def hello()" or similar code
        assert "def hello" in all_text or "Hello from E2E" in all_text or "implementation" in all_text.lower(), (
            f"Expected code content but got: {all_text[:300]}"
        )


# ---------------------------------------------------------------------------
# Health endpoint during/after E2E
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
class TestE2EHealthEndpoint:
    """Health endpoint reflects the correct state during E2E tests."""

    async def test_health_ok_before_query(self, client: AsyncClient):
        """Health check should return 200 with active_tasks=0 before any query."""
        resp = await client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["active_tasks"] == 0

    async def test_health_ok_after_query(
        self, client: AsyncClient, tmp_path,
    ):
        """Health check should return 200 after a completed query."""
        # Run a quick query first
        await collect_sse_events(
            client, "POST", "/api/v1/query",
            json_body=_query_body(
                prompt="e2e-greeting-test: health check test",
                cwd=str(tmp_path),
            ),
        )

        # After query completes, health should be fine
        resp = await client.get("/health")
        assert resp.status_code == 200
