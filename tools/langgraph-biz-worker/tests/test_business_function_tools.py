"""Tests for Navigator worker-gateway business function tools."""

from __future__ import annotations

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
