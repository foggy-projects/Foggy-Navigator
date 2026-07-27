import json
import stat
from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.main import app
from langgraph_biz_worker.models import QueryEvent, QueryRequest
from langgraph_biz_worker.routes.health import active_task_metadata, active_tasks
from langgraph_biz_worker.routes.query import _event_generator
from langgraph_biz_worker.runtime.completion_receipt import (
    CompletionReceiptStore,
    RECEIPT_SCHEMA,
    sha256_json,
    sha256_text,
)


def _mode(path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def test_completed_receipt_is_content_free_atomic_and_private(tmp_path):
    store = CompletionReceiptStore(tmp_path)
    receipt = store.record_terminal(
        task_id="task-completed-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="COMPLETED",
        final_output="sensitive final output",
        structured_output={"z": 1, "a": "sensitive structured value"},
    )

    assert receipt["schema"] == RECEIPT_SCHEMA
    assert receipt["terminal_status"] == "COMPLETED"
    assert receipt["final_output_digest"] == sha256_text("sensitive final output")
    assert receipt["structured_output_digest"] == sha256_json(
        {"z": 1, "a": "sensitive structured value"}
    )
    assert receipt["completion_signal_present"] is True
    assert _mode(store.receipt_root) == 0o700
    assert _mode(store.receipt_path("task-completed-001")) == 0o600

    persisted = store.receipt_path("task-completed-001").read_text(encoding="utf-8")
    assert "sensitive final output" not in persisted
    assert "sensitive structured value" not in persisted


def test_failed_receipt_uses_stable_error_code_and_has_no_completion_signal(tmp_path):
    store = CompletionReceiptStore(tmp_path)
    receipt = store.record_terminal(
        task_id="task-failed-001",
        worker_id="worker-001",
        dispatch_count=2,
        terminal_status="FAILED",
        terminal_error_code="provider auth failed: token=secret",
    )

    assert receipt["terminal_signal_present"] is True
    assert receipt["completion_signal_present"] is False
    assert receipt["terminal_error_code"] == "LANGGRAPH_PROVIDER_TASK_FAILED"
    assert "secret" not in store.receipt_path("task-failed-001").read_text(encoding="utf-8")


def test_receipt_is_idempotent_and_conflict_fails_closed(tmp_path):
    store = CompletionReceiptStore(tmp_path)
    first = store.record_terminal(
        task_id="task-conflict-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="COMPLETED",
        final_output="done",
    )
    repeated = store.record_terminal(
        task_id="task-conflict-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="COMPLETED",
        final_output="done",
    )
    conflict = store.record_terminal(
        task_id="task-conflict-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="FAILED",
        terminal_error_code="LANGGRAPH_PROVIDER_TASK_FAILED",
    )

    assert repeated == first
    assert conflict["terminal_status"] == "COMPLETED"
    assert conflict["evidence_conflict"] is True


def test_corrupt_receipt_is_reported_without_mutation(tmp_path):
    store = CompletionReceiptStore(tmp_path)
    store.receipt_root.mkdir(parents=True)
    target = store.receipt_path("task-corrupt-001")
    target.write_text("not-json", encoding="utf-8")
    before = target.stat().st_mtime_ns

    receipt, error_code = store.inspect("task-corrupt-001")

    assert receipt is None
    assert error_code == "LANGGRAPH_COMPLETION_RECEIPT_INVALID"
    assert target.stat().st_mtime_ns == before


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("terminal_signal_source", "PROVIDER_TERMINAL_EVENT"),
        ("completion_signal_source", "PROVIDER_TERMINAL_EVENT"),
        ("final_output_digest", "sha256:not-a-digest"),
    ],
)
def test_tampered_completed_receipt_profile_fails_closed(tmp_path, field, value):
    store = CompletionReceiptStore(tmp_path)
    receipt = store.record_terminal(
        task_id="task-tampered-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="COMPLETED",
        final_output="done",
    )
    receipt[field] = value
    store.receipt_path("task-tampered-001").write_text(
        json.dumps(receipt),
        encoding="utf-8",
    )

    observed, error_code = store.inspect("task-tampered-001")

    assert observed is None
    assert error_code == "LANGGRAPH_COMPLETION_RECEIPT_INVALID"


def test_tampered_failure_error_code_fails_closed(tmp_path):
    store = CompletionReceiptStore(tmp_path)
    receipt = store.record_terminal(
        task_id="task-tampered-failure-001",
        worker_id="worker-001",
        dispatch_count=1,
        terminal_status="FAILED",
        terminal_error_code="LANGGRAPH_PROVIDER_TASK_FAILED",
    )
    receipt["terminal_error_code"] = "provider failed: token=must-not-leak"
    store.receipt_path("task-tampered-failure-001").write_text(
        json.dumps(receipt),
        encoding="utf-8",
    )

    observed, error_code = store.inspect("task-tampered-failure-001")

    assert observed is None
    assert error_code == "LANGGRAPH_COMPLETION_RECEIPT_INVALID"


@pytest.mark.asyncio
async def test_unknown_readiness_is_200_and_does_not_create_storage(client, tmp_path, monkeypatch):
    from langgraph_biz_worker.routes import completion_readiness as readiness_route

    monkeypatch.setattr(readiness_route.settings, "data_root", str(tmp_path))
    response = await client.get("/api/v1/tasks/unknown-task/completion-readiness")

    assert response.status_code == 200
    body = response.json()
    assert body["worker_reachable"] is True
    assert body["worker_task_known"] is False
    assert body["sanitized_error_code"] == "WORKER_TASK_NOT_FOUND"
    assert not (tmp_path / "runtime").exists()


@pytest.mark.asyncio
async def test_readiness_route_is_bearer_protected(tmp_path):
    with patch("langgraph_biz_worker.auth.settings") as auth_settings:
        auth_settings.worker_token = "completion-secret"
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            denied = await client.get("/api/v1/tasks/task-001/completion-readiness")
            allowed = await client.get(
                "/api/v1/tasks/task-001/completion-readiness",
                headers={"Authorization": "Bearer completion-secret"},
            )

    assert denied.status_code == 401
    assert allowed.status_code == 200


@pytest.mark.asyncio
async def test_active_task_readiness_uses_exact_in_memory_identity(client):
    task_id = "task-active-001"
    active_tasks.add(task_id)
    active_task_metadata[task_id] = {"worker_id": "worker-001", "dispatch_count": 3}
    try:
        response = await client.get(f"/api/v1/tasks/{task_id}/completion-readiness")
    finally:
        active_tasks.discard(task_id)
        active_task_metadata.pop(task_id, None)

    body = response.json()
    assert body["worker_task_known"] is True
    assert body["worker_task_state"] == "RUNNING"
    assert body["provider_active_task_present"] is True
    assert body["receipt_worker_id"] == "worker-001"
    assert body["receipt_dispatch_count"] == 3


@pytest.mark.asyncio
async def test_query_terminal_result_writes_content_free_receipt(tmp_path, monkeypatch):
    from langgraph_biz_worker.routes import query as query_route

    store = CompletionReceiptStore(tmp_path)
    monkeypatch.setattr(query_route.settings, "navigator_worker_id", "worker-001")
    monkeypatch.setattr(query_route, "receipt_store", lambda: store)

    with patch("langgraph_biz_worker.routes.query.root_graph") as graph:
        graph.invoke.return_value = {
            "events": [
                QueryEvent(
                    type="result",
                    task_id="task-query-receipt-001",
                    content="private result",
                    structured_output={"private": "value"},
                )
            ]
        }
        events = [
            json.loads(item["data"])
            async for item in _event_generator(
                "task-query-receipt-001",
                QueryRequest(prompt="private prompt", dispatch_count=4),
            )
        ]

    receipt, error_code = store.inspect("task-query-receipt-001")
    assert error_code is None
    assert events[-1]["type"] == "result"
    assert receipt is not None
    assert receipt["dispatch_count"] == 4
    assert receipt["final_output_digest"] == sha256_text("private result")
    persisted = store.receipt_path("task-query-receipt-001").read_text(encoding="utf-8")
    assert "private prompt" not in persisted
    assert "private result" not in persisted
    assert "value" not in persisted


@pytest.mark.asyncio
async def test_terminal_receipt_exists_before_terminal_event_is_observable(tmp_path, monkeypatch):
    from langgraph_biz_worker.routes import query as query_route

    store = CompletionReceiptStore(tmp_path)
    monkeypatch.setattr(query_route.settings, "navigator_worker_id", "worker-001")
    monkeypatch.setattr(query_route, "receipt_store", lambda: store)

    with patch("langgraph_biz_worker.routes.query.root_graph") as graph:
        graph.invoke.return_value = {
            "events": [
                QueryEvent(
                    type="result",
                    task_id="task-before-yield-001",
                    content="private result",
                )
            ]
        }
        generator = _event_generator(
            "task-before-yield-001",
            QueryRequest(prompt="private prompt"),
        )
        terminal_item = await anext(generator)
        receipt, error_code = store.inspect("task-before-yield-001")
        await generator.aclose()

    assert json.loads(terminal_item["data"])["type"] == "result"
    assert error_code is None
    assert receipt is not None
    assert receipt["terminal_status"] == "COMPLETED"


@pytest.mark.asyncio
async def test_result_receipt_failure_fails_closed_as_authoritative_error(tmp_path, monkeypatch):
    from langgraph_biz_worker.routes import query as query_route

    store = CompletionReceiptStore(tmp_path)
    real_record_terminal = store.record_terminal
    attempts = 0

    def fail_first_receipt(**kwargs):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise OSError("private filesystem detail")
        return real_record_terminal(**kwargs)

    monkeypatch.setattr(query_route.settings, "navigator_worker_id", "worker-001")
    monkeypatch.setattr(store, "record_terminal", fail_first_receipt)
    monkeypatch.setattr(query_route, "receipt_store", lambda: store)

    with patch("langgraph_biz_worker.routes.query.root_graph") as graph:
        graph.invoke.return_value = {
            "events": [
                QueryEvent(
                    type="result",
                    task_id="task-receipt-fail-001",
                    content="private result",
                )
            ]
        }
        events = [
            json.loads(item["data"])
            async for item in _event_generator(
                "task-receipt-fail-001",
                QueryRequest(prompt="private prompt"),
            )
        ]

    receipt, error_code = store.inspect("task-receipt-fail-001")
    assert error_code is None
    assert attempts == 2
    assert events[-1]["type"] == "error"
    assert events[-1]["error_code"] == "LANGGRAPH_COMPLETION_RECEIPT_PERSISTENCE_FAILED"
    assert receipt is not None
    assert receipt["terminal_status"] == "FAILED"
    assert receipt["terminal_error_code"] == "LANGGRAPH_COMPLETION_RECEIPT_PERSISTENCE_FAILED"
    persisted = store.receipt_path("task-receipt-fail-001").read_text(encoding="utf-8")
    assert "private filesystem detail" not in persisted
    assert "private result" not in persisted


@pytest.mark.asyncio
async def test_query_terminal_error_writes_failed_receipt(tmp_path, monkeypatch):
    from langgraph_biz_worker.routes import query as query_route

    store = CompletionReceiptStore(tmp_path)
    monkeypatch.setattr(query_route.settings, "navigator_worker_id", "worker-001")
    monkeypatch.setattr(query_route, "receipt_store", lambda: store)

    with patch("langgraph_biz_worker.routes.query.root_graph") as graph:
        graph.invoke.return_value = {
            "events": [
                QueryEvent(
                    type="error",
                    task_id="task-query-error-001",
                    error="private provider error",
                    error_code="LANGGRAPH_PROVIDER_AUTH_FAILED",
                )
            ]
        }
        async for _ in _event_generator(
            "task-query-error-001",
            QueryRequest(prompt="private prompt"),
        ):
            pass

    receipt, error_code = store.inspect("task-query-error-001")
    assert error_code is None
    assert receipt is not None
    assert receipt["terminal_status"] == "FAILED"
    assert receipt["terminal_error_code"] == "LANGGRAPH_PROVIDER_AUTH_FAILED"
    persisted = store.receipt_path("task-query-error-001").read_text(encoding="utf-8")
    assert "private prompt" not in persisted
    assert "private provider error" not in persisted
