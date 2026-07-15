"""Tests for Navigator worker-gateway business function tools."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.tools import business_function_tools as tools


def test_get_business_function_schema_splits_inline_version(monkeypatch):
    captured = {}

    def fake_request_json(method, path, task_scoped_token, body=None):
        captured.update({
            "method": method,
            "path": path,
            "token": task_scoped_token,
            "body": body,
        })
        return {"ok": True}

    monkeypatch.setattr(tools, "_request_json", fake_request_json)

    result = tools.get_business_function_schema(
        "task-token",
        "tms.dataset.listModels@v1",
    )

    assert result == {"ok": True}
    assert captured["method"] == "GET"
    assert captured["path"] == (
        "/internal/worker-gateway/v1/business-functions/"
        "tms.dataset.listModels/schema?version=v1"
    )
    assert captured["token"] == "task-token"


def test_invoke_business_function_splits_inline_version(monkeypatch):
    captured = {}

    def fake_request_json(method, path, task_scoped_token, body=None):
        captured.update({
            "method": method,
            "path": path,
            "token": task_scoped_token,
            "body": body,
        })
        return {"ok": True}

    monkeypatch.setattr(tools, "_request_json", fake_request_json)

    result = tools.invoke_business_function(
        "task-token",
        "tms.dataset.listModels@v1",
        None,
        {"keyword": "order"},
    )

    assert result == {"ok": True}
    assert captured["method"] == "POST"
    assert captured["path"] == (
        "/internal/worker-gateway/v1/business-functions/"
        "tms.dataset.listModels/invoke"
    )
    assert captured["body"] == {
        "version": "v1",
        "input": {"keyword": "order"},
    }


def test_classifies_upstream_ref_pattern_error_as_non_recoverable_configuration():
    error = tools._classified_gateway_error(
        'HTTP 400: {"code":600,"exCode":"B600","msg":"upstreamRef must match [A-Za-z0-9._-]{1,128}"}'
    )

    result = error.to_tool_result()

    assert result["ok"] is False
    assert result["error_category"] == "CONFIGURATION"
    assert result["recoverable"] is False
    assert result["llm_retry_allowed"] is False
    assert "业务函数配置错误" in result["user_message"]


def test_worker_gateway_request_adds_local_credential_and_runtime_lease(monkeypatch):
    captured = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

        def read(self):
            return b"{}"

    def fake_urlopen(req, timeout):
        captured["headers"] = {key.lower(): value for key, value in req.header_items()}
        captured["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(tools.settings, "navigator_worker_id", "worker-a")
    monkeypatch.setattr(tools.settings, "navigator_worker_credential", "bwc_secret")
    monkeypatch.setattr(tools.request, "urlopen", fake_urlopen)

    with tools.worker_gateway_runtime_context({
        "worker_id": "worker-a",
        "worker_lease_id": "lease-1",
    }):
        tools._request_json("GET", "/internal/worker-gateway/v1/business-functions", "task-token")

    assert captured["headers"]["x-task-scoped-token"] == "task-token"
    assert captured["headers"]["x-navigator-worker-id"] == "worker-a"
    assert captured["headers"]["x-navigator-worker-credential"] == "bwc_secret"
    assert captured["headers"]["x-navigator-worker-lease-id"] == "lease-1"


def test_worker_gateway_internal_dev_sends_no_worker_identity_headers(monkeypatch):
    monkeypatch.setattr(tools.settings, "navigator_worker_id", "")
    monkeypatch.setattr(tools.settings, "navigator_worker_credential", "")

    assert tools._worker_identity_headers({
        "worker_id": "untrusted-request-value",
        "worker_lease_id": "lease-1",
    }) == {}


@pytest.mark.parametrize(
    ("local_worker_id", "credential", "runtime_context", "message"),
    [
        ("worker-a", "", {"worker_id": "worker-a", "worker_lease_id": "lease-1"}, "configured together"),
        ("worker-a", "bwc_secret", {"worker_id": "worker-b", "worker_lease_id": "lease-1"}, "does not match"),
        ("worker-a", "bwc_secret", {"worker_id": "worker-a"}, "worker_lease_id is required"),
    ],
)
def test_worker_gateway_identity_fails_closed_before_network(
    monkeypatch,
    local_worker_id,
    credential,
    runtime_context,
    message,
):
    monkeypatch.setattr(tools.settings, "navigator_worker_id", local_worker_id)
    monkeypatch.setattr(tools.settings, "navigator_worker_credential", credential)

    with pytest.raises(tools.BusinessFunctionToolError, match=message) as exc_info:
        tools._worker_identity_headers(runtime_context)

    assert exc_info.value.error_category == "CONFIGURATION"
    assert exc_info.value.recoverable is False
    assert exc_info.value.llm_retry_allowed is False
    assert "bwc_secret" not in str(exc_info.value)


def test_gateway_error_sanitizer_redacts_worker_and_task_credentials():
    message = tools._sanitize_gateway_error_message(
        "rejected bwc_worker_secret for btt_task_secret"
    )

    assert "redacted" in message
    assert "bwc_worker_secret" not in message
    assert "btt_task_secret" not in message
