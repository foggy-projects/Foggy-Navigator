"""E2E test fixtures: real Claude CLI  ⇆  mock-llm-service.

The full chain under test::

    httpx client → FastAPI worker (ASGITransport, in-process)
                       ↓
                  SdkWrapper.run_query()
                       ↓
                  claude-agent-sdk  →  Claude CLI subprocess
                       ↓
                  POST /v1/messages  →  mock-llm-service (subprocess, real HTTP)
                       ↓
                  Anthropic SSE response
                       ↓
                  CLI → SDK message → event dict → SSE back to client

Fixtures
--------
- ``mock_llm_url``  — session-scoped; starts mock-llm-service on a free port
- ``client``        — function-scoped async httpx client (worker, dev-mode auth)
- ``clean_registries`` — autouse; clears global state between tests
"""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from unittest.mock import patch

import httpx
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from agent_worker.main import app
from agent_worker.claude.sdk_wrapper import (
    task_registry,
    permission_pending,
    session_store,
)
from agent_worker.config import settings

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_THIS_DIR = Path(__file__).resolve().parent
_WORKER_ROOT = _THIS_DIR.parents[1]          # tools/claude-agent-worker
_MOCK_SERVICE_DIR = _WORKER_ROOT.parent / "mock-llm-service"
_E2E_RESPONSES_DIR = _THIS_DIR / "responses"


def _cli_available() -> bool:
    """Return True if the ``claude`` CLI binary is on the system PATH."""
    return shutil.which("claude") is not None


HAS_CLI = _cli_available()


def _find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _wait_for_server(url: str, *, timeout: float = 30) -> None:
    """Poll *url* until it responds with 200 or *timeout* is exceeded."""
    deadline = time.monotonic() + timeout
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        try:
            resp = httpx.get(url, timeout=3)
            if resp.status_code == 200:
                return
        except (httpx.ConnectError, httpx.ReadTimeout) as exc:
            last_err = exc
        time.sleep(0.5)
    raise RuntimeError(
        f"mock-llm-service at {url} did not become healthy within {timeout}s"
        + (f" (last error: {last_err})" if last_err else "")
    )


# ---------------------------------------------------------------------------
# SSE parsing (duplicated from integration conftest for independence)
# ---------------------------------------------------------------------------

def parse_sse_body(text: str) -> list[dict[str, Any]]:
    """Parse raw SSE response body into a list of JSON event payloads."""
    text = text.replace("\r\n", "\n")
    events: list[dict[str, Any]] = []
    for block in text.split("\n\n"):
        block = block.strip()
        if not block:
            continue
        data_lines: list[str] = []
        for line in block.split("\n"):
            stripped = line.strip()
            if stripped.startswith("data:"):
                data_lines.append(stripped[5:].strip())
        if not data_lines:
            continue
        raw = "\n".join(data_lines)
        try:
            events.append(json.loads(raw))
        except json.JSONDecodeError:
            pass
    return events


async def collect_sse_events(
    client: AsyncClient,
    method: str,
    url: str,
    *,
    json_body: dict | None = None,
    params: dict | None = None,
    timeout: float = 120.0,
) -> list[dict[str, Any]]:
    """Send a request and collect all SSE ``message`` events.

    Uses a non-streaming request — the full response body is available once
    the SSE generator finishes.  A generous default timeout (120 s) accounts
    for CLI startup time.
    """
    kwargs: dict[str, Any] = {"timeout": timeout}
    if json_body is not None:
        kwargs["json"] = json_body
    if params is not None:
        kwargs["params"] = params

    resp = await client.request(method, url, **kwargs)
    if resp.status_code != 200:
        return []
    return parse_sse_body(resp.text)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def mock_llm_url():
    """Start mock-llm-service as a subprocess and yield its base URL.

    The server binds to a random free port and loads response rules from
    ``tests/e2e/responses/``.  Torn down (SIGTERM / kill) after the session.
    """
    if not HAS_CLI:
        pytest.skip("Claude CLI not found — skipping E2E tests")

    port = _find_free_port()
    env = {
        **os.environ,
        "MOCK_LLM_RESPONSES_DIR": str(_E2E_RESPONSES_DIR),
        "MOCK_LLM_PORT": str(port),
        "PYTHONPATH": str(_MOCK_SERVICE_DIR / "src"),
    }

    proc = subprocess.Popen(
        [
            sys.executable, "-m", "uvicorn",
            "mock_llm.main:app",
            "--host", "127.0.0.1",
            "--port", str(port),
            "--log-level", "warning",
        ],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    try:
        _wait_for_server(f"http://127.0.0.1:{port}/admin/health")
        yield f"http://127.0.0.1:{port}"
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


@pytest_asyncio.fixture
async def client(mock_llm_url: str) -> AsyncClient:
    """Async httpx client bound to the worker app.

    * Auth disabled (dev mode: ``worker_token=""``).
    * CWD allow-list cleared.
    * ``anthropic_base_url`` pointed at the mock-llm-service.
    * ``anthropic_api_key`` set to a dummy value so the CLI uses API-key mode.
    """
    with (
        patch.object(settings, "worker_token", ""),
        patch.object(settings, "allowed_cwds", []),
        patch.object(settings, "anthropic_base_url", mock_llm_url),
        patch.object(settings, "anthropic_api_key", "sk-ant-api03-e2e-test-000000000000000000000000000000000000000000000000AA"),
        # Disable event persistence to avoid writing to disk during tests
        patch.object(settings, "event_persistence_enabled", False),
    ):
        transport = ASGITransport(app=app)  # type: ignore[arg-type]
        async with AsyncClient(transport=transport, base_url="http://test") as c:
            yield c


@pytest.fixture(autouse=True)
def clean_registries():
    """Clear shared global registries before and after each test."""
    task_registry.clear()
    permission_pending.clear()
    session_store.clear()
    yield
    # Cancel any live asyncio tasks to prevent resource leaks
    for entry in list(task_registry.values()):
        t = entry.get("asyncio_task")
        if t and not t.done():
            t.cancel()
    task_registry.clear()
    permission_pending.clear()
    session_store.clear()
