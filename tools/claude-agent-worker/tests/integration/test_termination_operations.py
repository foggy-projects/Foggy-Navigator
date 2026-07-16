"""GOV-004 regression tests for signed termination operation handling."""

from __future__ import annotations

import asyncio
import time
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi import HTTPException
from httpx import AsyncClient

from agent_worker.claude.sdk_wrapper import EventBroadcast, task_registry
from agent_worker.claude.process_detection import _tracked_pids
from agent_worker.config import settings
from agent_worker.models import CliProcessInfo
from agent_worker.termination import TerminationOperationReceiptLedger

from .conftest import termination_headers


PROCESS_STARTED_AT = "2026-01-01T00:00:00+00:00"
PROCESS_IDENTITY = f"claude-cli:4242:{PROCESS_STARTED_AT}"


@pytest.mark.asyncio
class TestTerminationOperations:
    async def test_process_list_response_excludes_raw_command_line(self, client: AsyncClient):
        raw_command = "claude --print --api-key worker-secret --prompt private-customer-request"
        target = CliProcessInfo(
            pid=4242,
            command=raw_command,
            started_at=PROCESS_STARTED_AT,
            process_identity=PROCESS_IDENTITY,
            is_orphan=True,
        )

        with (
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
        ):
            response = await client.get("/api/v1/processes")

        assert response.status_code == 200
        process = response.json()["processes"][0]
        assert "command" not in process
        assert raw_command not in response.text
        assert process["process_identity"] == PROCESS_IDENTITY

    async def test_missing_or_invalid_cancel_capability_is_rejected(self, client: AsyncClient):
        with patch.object(settings, "worker_token", "test-worker-secret"):
            missing = await client.post(
                "/api/v1/query/task-1/abort",
                headers={"Authorization": "Bearer test-worker-secret"},
            )
            invalid = await client.post(
                "/api/v1/query/task-1/abort",
                headers=termination_headers("task-1", signature_secret="wrong-secret"),
            )

        assert missing.status_code == 401
        assert invalid.status_code == 401

    async def test_valid_cancel_ack_is_pending_and_one_time(self, client: AsyncClient):
        running = asyncio.ensure_future(asyncio.sleep(999))
        broadcast = EventBroadcast(task_id="task-2")
        subscriber = broadcast.subscribe()
        task_registry["task-2"] = {
            "asyncio_task": running,
            "broadcast": broadcast,
            "foggy_task_id": "foggy-task-2",
            "execution_state": "ACTIVE_TASK_EXECUTION",
            "terminal_observed": False,
        }
        headers = termination_headers("task-2", operation_id="op-cancel-once")

        with patch.object(settings, "worker_token", "test-worker-secret"):
            accepted = await client.post("/api/v1/query/task-2/abort", headers=headers)
            replay = await client.post("/api/v1/query/task-2/abort", headers=headers)

        assert accepted.status_code == 200
        assert accepted.json()["status"] == "CANCEL_REQUESTED"
        assert accepted.json()["observed_exit"] is False
        assert accepted.json()["lifecycle_state"] == "CANCEL_REQUESTED"
        assert accepted.json()["attention"][0]["code"] == "CANCELLATION_PENDING_CONFIRMATION"
        assert accepted.json()["available_actions"] == ["CONTINUE_WAIT", "QUERY_DIAGNOSTICS", "CANCEL"]
        assert accepted.json()["termination_operation"]["operation_id"] == "op-cancel-once"
        assert accepted.json()["termination_operation"]["status"] == "CANCEL_REQUESTED"
        assert accepted.json()["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        assert task_registry["task-2"]["cancel_requested"] is True
        assert task_registry["task-2"]["execution_state"] == "CANCEL_REQUESTED"
        lifecycle_event = subscriber.get_nowait()
        assert lifecycle_event["subtype"] == "termination_requested"
        assert lifecycle_event["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        task_status = await client.get("/api/v1/tasks/task-2/status")
        assert task_status.status_code == 200
        assert task_status.json()["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        assert replay.status_code == 409
        await asyncio.sleep(0)
        assert running.cancelled()

    async def test_termination_capability_requires_configured_matching_worker_identity(self, client: AsyncClient):
        """A signed operation cannot be replayed to a different Worker."""
        with patch.object(settings, "worker_token", "test-worker-secret"):
            missing_claim = await client.post(
                "/api/v1/query/task-worker-id/abort",
                headers=termination_headers("task-worker-id", worker_id=None),
            )
            mismatch = await client.post(
                "/api/v1/query/task-worker-id/abort",
                headers=termination_headers("task-worker-id", worker_id="another-navigator-worker"),
            )
            with patch.object(settings, "navigator_worker_id", ""):
                unconfigured = await client.post(
                    "/api/v1/query/task-worker-id/abort",
                    headers=termination_headers("task-worker-id"),
                )

        assert missing_claim.status_code == 400
        assert mismatch.status_code == 403
        assert unconfigured.status_code == 503

    async def test_replay_ledger_never_evicts_live_operation(self, client: AsyncClient):
        first_headers = termination_headers("task-cache-1", operation_id="op-cache-1")
        second_headers = termination_headers("task-cache-2", operation_id="op-cache-2")

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.termination._MAX_REPLAY_ENTRIES", 1),
        ):
            accepted = await client.post("/api/v1/query/task-cache-1/abort", headers=first_headers)
            full = await client.post("/api/v1/query/task-cache-2/abort", headers=second_headers)
            replay = await client.post("/api/v1/query/task-cache-1/abort", headers=first_headers)

        assert accepted.status_code == 200
        assert full.status_code == 503
        assert replay.status_code == 409

    async def test_durable_receipt_replays_across_ledger_instances_and_corruption_fails_closed(
        self,
        client: AsyncClient,
    ):
        """A new verifier must honor a prior disk receipt and reject bad state."""
        operation_id = "op-durable-receipt"
        headers = termination_headers("task-durable-receipt", operation_id=operation_id)

        with patch.object(settings, "worker_token", "test-worker-secret"):
            accepted = await client.post("/api/v1/query/task-durable-receipt/abort", headers=headers)

            # A freshly-created ledger represents the post-restart verifier.
            restarted_ledger = TerminationOperationReceiptLedger(
                Path(settings.termination_operation_ledger_dir),
            )
            with pytest.raises(HTTPException) as replay_error:
                restarted_ledger.consume(
                    settings.navigator_worker_id,
                    operation_id,
                    time.time() + 60,
                    time.time(),
                )

            corrupt_operation_id = "op-corrupt-receipt"
            corrupt_receipt = restarted_ledger.receipt_path_for(
                settings.navigator_worker_id,
                corrupt_operation_id,
            )
            corrupt_receipt.parent.mkdir(parents=True, exist_ok=True)
            corrupt_receipt.write_text("{not-json", encoding="utf-8")
            unavailable = await client.post(
                "/api/v1/query/task-corrupt-receipt/abort",
                headers=termination_headers("task-corrupt-receipt", operation_id=corrupt_operation_id),
            )

            expired_ledger = TerminationOperationReceiptLedger(
                Path(settings.termination_operation_ledger_dir) / "expired-receipts",
            )
            current_time = time.time()
            expired_ledger.consume(
                settings.navigator_worker_id,
                "op-expired-receipt",
                current_time - 1,
                current_time,
            )
            with pytest.raises(HTTPException) as expired_replay:
                expired_ledger.consume(
                    settings.navigator_worker_id,
                    "op-expired-receipt",
                    current_time + 60,
                    current_time,
                )

        assert accepted.status_code == 200
        assert replay_error.value.status_code == 409
        assert unavailable.status_code == 503
        assert expired_replay.value.status_code == 409

    async def test_invalid_receipt_ledger_path_fails_closed(self, client: AsyncClient):
        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch.object(settings, "termination_operation_ledger_dir", "relative-ledger-path"),
        ):
            response = await client.post(
                "/api/v1/query/task-invalid-ledger-path/abort",
                headers=termination_headers("task-invalid-ledger-path"),
            )

        assert response.status_code == 503

    async def test_manual_pid_kill_requires_admin_provenance(self, client: AsyncClient):
        kill_calls: list[tuple[int, int]] = []

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "task-for-pid-4242",
                    kind="MANUAL_PID_KILL",
                    origin="UPSTREAM_USER",
                    expected_pid=4242,
                ),
            )

        assert response.status_code == 403
        assert kill_calls == []

    async def test_manual_pid_kill_requires_authorization_decision(self, client: AsyncClient):
        kill_calls: list[tuple[int, int]] = []

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "task-for-pid-4242",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                    authorization_decision_id=None,
                ),
            )

        assert response.status_code == 403
        assert kill_calls == []

    async def test_manual_pid_kill_is_isolated_from_task_abort(self, client: AsyncClient):
        running = asyncio.ensure_future(asyncio.sleep(999))
        broadcast = EventBroadcast(task_id="task-for-pid-4242")
        subscriber = broadcast.subscribe()
        task_registry["task-for-pid-4242"] = {
            "asyncio_task": running,
            "broadcast": broadcast,
            "execution_state": "ACTIVE_TASK_EXECUTION",
            "terminal_observed": False,
        }
        _tracked_pids[4242] = "task-for-pid-4242"
        target = CliProcessInfo(pid=4242, started_at=PROCESS_STARTED_AT, is_orphan=False)
        kill_calls: list[tuple[int, int]] = []

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                json={"force": False},
                headers=termination_headers(
                    "task-for-pid-4242",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 200
        assert response.json()["status"] == "KILL_REQUESTED"
        assert response.json()["lifecycle_status"] == "KILL_REQUESTED_UNVERIFIED"
        assert response.json()["observed_exit"] is False
        assert response.json()["operation_id"]
        assert response.json()["task_id"] == "task-for-pid-4242"
        assert response.json()["attention"][0]["code"] == "TERMINATION_UNCONFIRMED"
        assert response.json()["termination_operation"]["kind"] == "MANUAL_PID_KILL"
        assert response.json()["termination_operation"]["status"] == "UNCONFIRMED"
        assert response.json()["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        lifecycle_event = subscriber.get_nowait()
        assert lifecycle_event["subtype"] == "manual_pid_kill_requested"
        assert lifecycle_event["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        task_status = await client.get("/api/v1/tasks/task-for-pid-4242/status")
        assert task_status.status_code == 200
        assert task_status.json()["termination_operation"]["worker_id"] == "test-navigator-worker-id"
        assert kill_calls == [(4242, 15)]
        assert task_registry["task-for-pid-4242"].get("cancel_requested") is None
        assert task_registry["task-for-pid-4242"]["attention_state"] == "TERMINATION_UNCONFIRMED"
        assert not running.cancelled()
        running.cancel()

    async def test_manual_pid_kill_rejects_unbound_process(self, client: AsyncClient):
        kill_calls: list[tuple[int, int]] = []
        target = CliProcessInfo(pid=4242, started_at=PROCESS_STARTED_AT, is_orphan=True)

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "task-for-pid-4242",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 409
        assert kill_calls == []

    async def test_manual_pid_kill_rejects_mismatched_foggy_task_binding(self, client: AsyncClient):
        kill_calls: list[tuple[int, int]] = []
        target = CliProcessInfo(
            pid=4242,
            started_at=PROCESS_STARTED_AT,
            foggy_task_id="foggy-bound-task",
            is_orphan=False,
        )

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "different-task",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 403
        assert kill_calls == []

    async def test_manual_pid_kill_accepts_direct_foggy_process_binding(self, client: AsyncClient):
        """A Worker-restarted process may be bound by its own Foggy env only."""
        kill_calls: list[tuple[int, int]] = []
        target = CliProcessInfo(
            pid=4242,
            started_at=PROCESS_STARTED_AT,
            foggy_task_id="foggy-direct-task",
            is_orphan=False,
        )

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "foggy-direct-task",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 200
        assert response.json()["status"] == "KILL_REQUESTED"
        assert response.json()["observed_exit"] is False
        assert kill_calls == [(4242, 15)]

    async def test_manual_pid_kill_rejects_conflicting_direct_and_tracker_binding(self, client: AsyncClient):
        """A stale PID tracker cannot authorize a kill of a differently-bound process."""
        task_registry["worker-stale"] = {
            "foggy_task_id": "foggy-stale-task",
            "execution_state": "ACTIVE_TASK_EXECUTION",
        }
        _tracked_pids[4242] = "worker-stale"
        kill_calls: list[tuple[int, int]] = []
        target = CliProcessInfo(pid=4242, started_at=PROCESS_STARTED_AT, is_orphan=False)

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch(
                "agent_worker.routes.processes._read_foggy_env",
                return_value={"foggy_task_id": "foggy-direct-task", "foggy_session_id": None},
            ),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "foggy-stale-task",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 409
        assert kill_calls == []

    async def test_manual_pid_kill_requires_expected_pid(self, client: AsyncClient):
        with patch.object(settings, "worker_token", "test-worker-secret"):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers("task-for-pid-4242", kind="MANUAL_PID_KILL"),
            )

        assert response.status_code == 400

    async def test_manual_pid_kill_rejects_reused_pid_identity(self, client: AsyncClient):
        """A PID reused after Navigator issued the capability is never signalled."""
        target = CliProcessInfo(
            pid=4242,
            started_at="2026-01-01T00:01:00+00:00",
            foggy_task_id="foggy-direct-task",
            is_orphan=False,
        )
        kill_calls: list[tuple[int, int]] = []

        with (
            patch.object(settings, "worker_token", "test-worker-secret"),
            patch("agent_worker.routes.processes._find_sdk_cli_pids", return_value={4242}),
            patch("agent_worker.routes.processes._get_process_details", return_value=[target]),
            patch("os.kill", side_effect=lambda pid, sig: kill_calls.append((pid, sig))),
        ):
            response = await client.post(
                "/api/v1/processes/4242/kill",
                headers=termination_headers(
                    "foggy-direct-task",
                    kind="MANUAL_PID_KILL",
                    expected_pid=4242,
                    expected_process_identity=PROCESS_IDENTITY,
                ),
            )

        assert response.status_code == 409
        assert kill_calls == []
