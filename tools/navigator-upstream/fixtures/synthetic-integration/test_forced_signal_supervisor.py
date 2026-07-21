#!/usr/bin/env python3
"""Offline regression tests for the BUG-009 forced-SIGNAL supervisor.

Most tests replace process, HTTP, Docker, and signal boundaries. A narrowly
scoped topology test may open a test-owned loopback listener and inspect its
test-owned process topology through ``/proc``. The suite must never start the
synthetic harness, query Docker, use a runtime profile, or touch a non-test
process. The assertions protect the supervisor's fail-closed preconditions
rather than a disposable runtime.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import os
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest import mock


REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
SUPERVISOR_PATH = (
    REPOSITORY_ROOT
    / "tools"
    / "navigator-upstream"
    / "scripts"
    / "synthetic-upstream-forced-signal-supervisor.py"
)


def load_supervisor() -> ModuleType:
    """Load the standalone script without executing its CLI entrypoint."""

    module_name = "int001_forced_signal_supervisor_offline_test_target"
    sys.modules.pop(module_name, None)
    spec = importlib.util.spec_from_file_location(module_name, SUPERVISOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load forced-signal supervisor")
    module = importlib.util.module_from_spec(spec)
    # Dataclass annotation resolution requires registration before execution.
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


supervisor = load_supervisor()


class FakeHttpResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body
        self.read_sizes: list[int] = []

    def read(self, amount: int = -1) -> bytes:
        self.read_sizes.append(amount)
        return self._body if amount < 0 else self._body[:amount]


def http_connection_for(response: FakeHttpResponse) -> tuple[type[object], list[object]]:
    """Return a strict loopback-only HTTPConnection fake plus its instances."""

    instances: list[object] = []

    class FakeHttpConnection:
        def __init__(self, host: str, port: int | None = None, timeout: object | None = None, **kwargs: object) -> None:
            self.host = host
            self.port = port
            self.timeout = timeout
            self.extra = kwargs
            self.requests: list[tuple[str, str, object, object]] = []
            self.closed = False
            instances.append(self)

        def request(
            self,
            method: str,
            url: str,
            body: object = None,
            headers: object = None,
        ) -> None:
            self.requests.append((method, url, body, headers))

        def getresponse(self) -> FakeHttpResponse:
            return response

        def close(self) -> None:
            self.closed = True

    return FakeHttpConnection, instances


class StrictLoopbackHealthTest(unittest.TestCase):
    def setUp(self) -> None:
        supervisor.SUPERVISOR_INTERRUPTION = None

    def tearDown(self) -> None:
        supervisor.SUPERVISOR_INTERRUPTION = None

    def test_health_uses_only_exact_loopback_actuator_endpoint(self) -> None:
        response = FakeHttpResponse(200, b'{"status":"UP"}')
        connection, instances = http_connection_for(response)

        with mock.patch.object(supervisor.http.client, "HTTPConnection", connection):
            self.assertTrue(supervisor.health_ready(24_567))

        self.assertEqual(1, len(instances))
        observed = instances[0]
        self.assertEqual("127.0.0.1", observed.host)
        self.assertEqual(24_567, observed.port)
        self.assertGreater(float(observed.timeout), 0.0)
        self.assertEqual(1, len(observed.requests))
        method, path, body, headers = observed.requests[0]
        self.assertEqual("GET", method)
        self.assertEqual("/actuator/health", path)
        self.assertIsNone(body)
        self.assertEqual({"Host": "127.0.0.1:24567", "Connection": "close"}, headers)
        self.assertTrue(observed.closed)

    def test_health_rejects_redirect_non_200_and_non_up_payloads(self) -> None:
        cases = (
            ("redirect", 302, b'{"status":"UP"}'),
            ("service-unavailable", 503, b'{"status":"UP"}'),
            ("down", 200, b'{"status":"DOWN"}'),
            ("not-json", 200, b"not-json"),
            ("missing-status", 200, b"{}"),
        )
        for label, status, body in cases:
            with self.subTest(label=label):
                response = FakeHttpResponse(status, body)
                connection, instances = http_connection_for(response)
                with mock.patch.object(supervisor.http.client, "HTTPConnection", connection):
                    self.assertFalse(supervisor.health_ready(24_568))
                # A redirect must be rejected in place; a second connection
                # would be evidence of an unsafe redirect follow.
                self.assertEqual(1, len(instances))
                self.assertEqual("127.0.0.1", instances[0].host)
                self.assertEqual(1, len(instances[0].requests))
                self.assertEqual("GET", instances[0].requests[0][0])
                self.assertEqual("/actuator/health", instances[0].requests[0][1])


class ReceiptBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.artifact_root = self.root / "artifact-root"
        self.artifact_root.mkdir(mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        self.run_id = "int001-supervisor-receipt"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_accepts_only_a_safe_root_receipt(self) -> None:
        run_dir = self._new_run_dir("safe")
        self._write_receipt(run_dir)

        receipt = supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root)

        self.assertEqual(
            {
                "mode": "0600",
                "schemaVersion": 4,
                "result": "CLEANED",
                "failureStage": "SIGNAL",
                "rehearsalLifecycleObservation": "HOLD_SIGNAL_RECEIVED",
                "launcherReadinessObservation": "HEALTH_READY",
                "launcherFailureClass": "NOT_APPLICABLE",
                "secretsRedacted": True,
            },
            receipt,
        )

    def test_rejects_unsafe_receipt_and_run_directory_shapes(self) -> None:
        with self.subTest("world-readable-receipt"):
            run_dir = self._new_run_dir("world-readable")
            receipt = self._write_receipt(run_dir)
            os.chmod(receipt, 0o644)
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("world-readable-run-directory"):
            run_dir = self._new_run_dir("world-readable-run")
            self._write_receipt(run_dir)
            os.chmod(run_dir, 0o755)
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("symlinked-receipt"):
            run_dir = self._new_run_dir("symlink")
            target = self._write_receipt(run_dir, name="target.json")
            receipt = run_dir / "cleanup-report.json"
            os.symlink(target.name, receipt)
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("hard-linked-receipt"):
            run_dir = self._new_run_dir("hard-link")
            receipt = self._write_receipt(run_dir)
            os.link(receipt, run_dir / "receipt-copy.json")
            self.assertGreater(receipt.stat().st_nlink, 1)
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("unknown-fixed-enum"):
            run_dir = self._new_run_dir("unknown-enum")
            self._write_receipt(run_dir, overrides={"failureStage": "UNSAFE"})
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("legacy-schema-v3"):
            run_dir = self._new_run_dir("legacy-schema")
            self._write_receipt(run_dir, overrides={"schemaVersion": 3})
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

        with self.subTest("unsafe-lifecycle-observation"):
            run_dir = self._new_run_dir("unsafe-lifecycle-observation")
            self._write_receipt(
                run_dir,
                overrides={"rehearsalLifecycleObservation": "private-log-derived-value"},
            )
            self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

    def test_rejects_duplicate_json_keys(self) -> None:
        run_dir = self._new_run_dir("duplicate-json")
        raw = (
            '{"schemaVersion":4,"schemaVersion":4,'
            f'"runId":"{self.run_id}","result":"CLEANED","failureStage":"SIGNAL",'
            '"rehearsalLifecycleObservation":"HOLD_SIGNAL_RECEIVED",'
            '"launcherReadinessObservation":"HEALTH_READY",'
            '"launcherFailureClass":"NOT_APPLICABLE",'
            '"finishedAtUtc":"2026-07-21T00:00:00Z","secretsRedacted":true}'
        )
        receipt = run_dir / "cleanup-report.json"
        receipt.write_text(raw, encoding="utf-8")
        os.chmod(receipt, 0o600)

        self.assertIsNone(supervisor.read_redacted_receipt(run_dir, self.run_id, self.artifact_root))

    def test_root_snapshot_refuses_an_unsafe_root_without_listing_entries(self) -> None:
        run_dir = self._new_run_dir("unsafe-root-snapshot")
        self._write_receipt(run_dir)
        os.chmod(run_dir, 0o755)

        snapshot = supervisor.run_root_snapshot(run_dir, self.artifact_root)

        self.assertIsNone(snapshot.private_absent)
        self.assertIsNone(snapshot.nonreceipt_residue_count)

    def test_artifact_root_containment_rejects_external_0700_run_without_reads(self) -> None:
        outside_run = self.root / "outside-0700-run"
        outside_run.mkdir(mode=0o700)
        os.chmod(outside_run, 0o700)
        self._write_receipt(outside_run)

        with mock.patch.object(supervisor.os, "open") as open_file, mock.patch.object(
            supervisor.os, "listdir"
        ) as list_directory:
            self.assertIsNone(supervisor.read_redacted_receipt(outside_run, self.run_id, self.artifact_root))
            snapshot = supervisor.run_root_snapshot(outside_run, self.artifact_root)

        self.assertEqual(supervisor.RunRootSnapshot(None, None), snapshot)
        open_file.assert_not_called()
        list_directory.assert_not_called()

    def _new_run_dir(self, suffix: str) -> Path:
        run_dir = self.artifact_root / suffix
        run_dir.mkdir(mode=0o700)
        os.chmod(run_dir, 0o700)
        return run_dir

    def _write_receipt(
        self,
        run_dir: Path,
        *,
        name: str = "cleanup-report.json",
        overrides: dict[str, object] | None = None,
    ) -> Path:
        value: dict[str, object] = {
            "schemaVersion": 4,
            "runId": self.run_id,
            "result": "CLEANED",
            "failureStage": "SIGNAL",
            "rehearsalLifecycleObservation": "HOLD_SIGNAL_RECEIVED",
            "launcherReadinessObservation": "HEALTH_READY",
            "launcherFailureClass": "NOT_APPLICABLE",
            "finishedAtUtc": "2026-07-21T00:00:00Z",
            "secretsRedacted": True,
        }
        if overrides:
            value.update(overrides)
        receipt = run_dir / name
        receipt.write_text(json.dumps(value), encoding="utf-8")
        os.chmod(receipt, 0o600)
        return receipt


class ExecutionProjectionBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository_root = Path(self.temporary_directory.name)
        self.artifact_root = self.repository_root / "temp" / "test-artifacts" / "INT-001"
        self.artifact_root.mkdir(parents=True, mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        self.run_id = "int001-projection-boundary"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_writer_and_reader_keep_only_the_fixed_redacted_contract(self) -> None:
        writer = supervisor.open_execution_projection(self.artifact_root, self.run_id)
        writer.write(
            phase="COMPLETE",
            outcome="SUCCESS_GATE_NOT_MET",
            receipt_state="MISSING_OR_INVALID",
            root_snapshot_state="UNAVAILABLE",
            stdout_summary_state="EMITTED",
        )
        writer.close()

        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )

        self.assertEqual(
            {
                "schemaVersion": 1,
                "runId": self.run_id,
                "phase": "COMPLETE",
                "outcome": "SUCCESS_GATE_NOT_MET",
                "receiptState": "MISSING_OR_INVALID",
                "rootSnapshotState": "UNAVAILABLE",
                "stdoutSummaryState": "EMITTED",
                "secretsRedacted": True,
            },
            projection,
        )
        raw = supervisor.execution_projection_path(self.artifact_root, self.run_id).read_text(encoding="utf-8")
        for prohibited in (
            "pid",
            "argv",
            "cwd",
            "port",
            "inode",
            "socket",
            "profile",
            "credential",
            "payload",
            "exception",
            "private",
            "dockerResidueCounts",
        ):
            self.assertNotIn(prohibited, raw)

    def test_missing_projection_is_not_inferred_as_any_execution_state(self) -> None:
        self.assertIsNone(
            supervisor.read_execution_projection(
                self.repository_root,
                self.artifact_root,
                "int001-projection-missing",
            )
        )

    def test_reader_rejects_duplicate_unknown_mismatched_and_unsafe_projection(self) -> None:
        cases = (
            (
                "duplicate-key",
                '{"schemaVersion":1,"schemaVersion":1,"runId":"int001-projection-duplicate",'
                '"phase":"FAILED","outcome":"UNEXPECTED_FAILURE","receiptState":"NOT_SAMPLED",'
                '"rootSnapshotState":"NOT_SAMPLED","stdoutSummaryState":"NOT_EMITTED",'
                '"secretsRedacted":true}',
                "int001-projection-duplicate",
            ),
            (
                "unknown-enum",
                json.dumps(
                    supervisor.execution_projection_value(
                        run_id="int001-projection-unknown",
                        phase="FAILED",
                        outcome="UNEXPECTED_FAILURE",
                        receipt_state="NOT_SAMPLED",
                        root_snapshot_state="NOT_SAMPLED",
                        stdout_summary_state="NOT_EMITTED",
                    )
                    | {"phase": "PRIVATE_LOG_READ"}
                ),
                "int001-projection-unknown",
            ),
            (
                "extra-field",
                json.dumps(
                    supervisor.execution_projection_value(
                        run_id="int001-projection-extra",
                        phase="FAILED",
                        outcome="UNEXPECTED_FAILURE",
                        receipt_state="NOT_SAMPLED",
                        root_snapshot_state="NOT_SAMPLED",
                        stdout_summary_state="NOT_EMITTED",
                    )
                    | {"detail": "unsafe"}
                ),
                "int001-projection-extra",
            ),
            (
                "wrong-run",
                json.dumps(
                    supervisor.execution_projection_value(
                        run_id="int001-projection-other",
                        phase="FAILED",
                        outcome="UNEXPECTED_FAILURE",
                        receipt_state="NOT_SAMPLED",
                        root_snapshot_state="NOT_SAMPLED",
                        stdout_summary_state="NOT_EMITTED",
                    )
                ),
                "int001-projection-wrong",
            ),
        )
        for label, raw, run_id in cases:
            with self.subTest(label=label):
                path = supervisor.execution_projection_path(self.artifact_root, run_id)
                path.write_text(raw, encoding="utf-8")
                os.chmod(path, 0o600)
                self.assertIsNone(
                    supervisor.read_execution_projection(self.repository_root, self.artifact_root, run_id)
                )

        unsafe_run_id = "int001-projection-world-readable"
        writer = supervisor.open_execution_projection(self.artifact_root, unsafe_run_id)
        writer.close()
        os.chmod(supervisor.execution_projection_path(self.artifact_root, unsafe_run_id), 0o644)
        self.assertIsNone(
            supervisor.read_execution_projection(self.repository_root, self.artifact_root, unsafe_run_id)
        )

        linked_run_id = "int001-projection-hard-linked"
        writer = supervisor.open_execution_projection(self.artifact_root, linked_run_id)
        writer.close()
        source = supervisor.execution_projection_path(self.artifact_root, linked_run_id)
        os.link(source, self.artifact_root / "projection-copy.json")
        self.assertIsNone(
            supervisor.read_execution_projection(self.repository_root, self.artifact_root, linked_run_id)
        )

    def test_projection_is_a_sibling_and_never_changes_run_root_residue(self) -> None:
        run_dir = self.artifact_root / self.run_id
        run_dir.mkdir(mode=0o700)
        os.chmod(run_dir, 0o700)
        writer = supervisor.open_execution_projection(self.artifact_root, self.run_id)
        writer.close()

        snapshot = supervisor.run_root_snapshot(run_dir, self.artifact_root)

        self.assertEqual(supervisor.RunRootSnapshot(True, 0), snapshot)
        self.assertEqual(self.artifact_root, supervisor.execution_projection_path(self.artifact_root, self.run_id).parent)
        self.assertNotEqual(run_dir, supervisor.execution_projection_path(self.artifact_root, self.run_id).parent)


class DockerPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.socket_path = self.root / "docker.sock"
        self.unix_server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.unix_server.bind(str(self.socket_path))

    def tearDown(self) -> None:
        self.unix_server.close()
        self.temporary_directory.cleanup()

    def test_accepts_only_a_real_non_symlink_local_unix_socket(self) -> None:
        with mock.patch.object(supervisor, "LOCAL_DOCKER_SOCKET", self.socket_path):
            self.assertTrue(supervisor.local_docker_socket_is_safe())

    def test_rejects_missing_regular_and_symlink_socket_paths(self) -> None:
        missing = self.root / "missing.sock"
        regular = self.root / "regular.sock"
        regular.write_text("not a socket", encoding="utf-8")
        link = self.root / "docker-link.sock"
        os.symlink(self.socket_path.name, link)

        for label, candidate in (("missing", missing), ("regular", regular), ("symlink", link)):
            with self.subTest(label=label), mock.patch.object(supervisor, "LOCAL_DOCKER_SOCKET", candidate):
                self.assertFalse(supervisor.local_docker_socket_is_safe())

    def test_local_docker_uses_fixed_host_and_rejects_context_switch(self) -> None:
        completed = subprocess.CompletedProcess(args=["docker"], returncode=0, stdout="", stderr="")
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "local_docker_socket_is_safe", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "repository_root", return_value=self.root))
            stack.enter_context(mock.patch.object(supervisor, "docker_environment", return_value={"PATH": "/safe"}))
            run = stack.enter_context(mock.patch.object(supervisor.subprocess, "run", return_value=completed))

            result = supervisor.run_local_docker(["docker", "ps", "-aq"])
            blocked = supervisor.run_local_docker(["docker", "--context", "remote", "ps"])

        self.assertIs(completed, result)
        self.assertIsNone(blocked)
        self.assertEqual(1, run.call_count)
        self.assertEqual(
            ["docker", "--host", supervisor.LOCAL_DOCKER_HOST, "ps", "-aq"],
            run.call_args.args[0],
        )
        self.assertIn("timeout", run.call_args.kwargs)
        self.assertGreater(float(run.call_args.kwargs["timeout"]), 0.0)

    def test_residue_queries_use_the_fixed_local_docker_wrapper(self) -> None:
        completed = subprocess.CompletedProcess(args=["docker"], returncode=0, stdout="", stderr="")
        with mock.patch.object(supervisor, "run_local_docker", return_value=completed) as run:
            self.assertEqual(
                {"container": 0, "network": 0, "volume": 0},
                supervisor.docker_residue_counts("int001-supervisor-docker"),
            )

        self.assertEqual(6, run.call_count)
        for call in run.call_args_list:
            self.assertEqual("docker", call.args[0][0])
            self.assertNotIn("--context", call.args[0])


class ListenerProofGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.artifact_root = self.root / "artifact-root"
        self.artifact_root.mkdir(mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        self.run_dir = self.artifact_root / "int001-listener-proof"
        self.run_dir.mkdir(mode=0o700)
        os.chmod(self.run_dir, 0o700)
        self.run_id = "int001-listener-proof"
        self.pid = 42_424
        self.start_ticks = 123_456
        self.socket_inode = 789_012
        self.java = Path("/trusted/java")
        self.expected_argv = [
            str(self.java),
            f"-Dint001.run-id={self.run_id}",
            "-jar",
            "/trusted/launcher.jar",
            "--spring.profiles.active=mock",
        ]

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_listener_proof_accepts_only_stable_exact_owned_launcher(self) -> None:
        proof, patches = self._prove(return_patches=True)

        self.assertTrue(proof.ok)
        self.assertEqual(self.pid, proof.pid)
        self.assertEqual(self.start_ticks, proof.start_ticks)
        self.assertEqual(self.socket_inode, proof.socket_inode)
        patches["find_candidate"].assert_called_once()
        self.assertEqual(
            [mock.call(24_570, self.pid), mock.call(24_570, self.pid)],
            patches["socket_probe"].call_args_list,
        )
        self.assertEqual(
            [mock.call(self.pid, self.socket_inode), mock.call(self.pid, self.socket_inode)],
            patches["candidate_holds"].call_args_list,
        )
        self.assertEqual(
            [mock.call(self.socket_inode), mock.call(self.socket_inode)],
            patches["holders"].call_args_list,
        )
        self.assertEqual(2, patches["identity"].call_count)
        for call in patches["identity"].call_args_list:
            self.assertEqual(self.pid, call.kwargs["pid"])
            self.assertEqual(self.start_ticks, call.kwargs["expected_start_ticks"])

    def test_listener_proof_rejects_missing_launcher_contract_before_candidate_discovery(self) -> None:
        cases = (
            ("launcher-expected", {"expected_argv": None}, "launcher-expected"),
            ("listener-java", {"java": None}, "listener-java"),
        )
        for label, overrides, expected_reason in cases:
            with self.subTest(label=label):
                proof, patches = self._prove(return_patches=True, **overrides)
                self.assertFalse(proof.ok)
                self.assertEqual(expected_reason, proof.reason)
                patches["find_candidate"].assert_not_called()

    def test_listener_proof_rejects_candidate_discovery_failures_before_socket_reads(self) -> None:
        for reason in (
            "listener-candidate-absent",
            "listener-candidate-ambiguous",
            "listener-candidate-proc-unavailable",
            "listener-candidate-proc-malformed",
        ):
            with self.subTest(reason=reason):
                proof, patches = self._prove(
                    candidate_result=(None, reason),
                    return_patches=True,
                )

                self.assertFalse(proof.ok)
                self.assertEqual(reason, proof.reason)
                patches["socket_probe"].assert_not_called()
                patches["candidate_holds"].assert_not_called()
                patches["holders"].assert_not_called()
                patches["identity"].assert_not_called()

    def test_listener_proof_requires_candidate_alone_to_hold_listener_inode(self) -> None:
        cases: tuple[tuple[str, dict[str, object]], ...] = (
            ("candidate-fd-missing-initial", {"candidate_holds": [False]}),
            ("another-current-user-holder-initial", {"holders": [(self.pid, self.pid + 1)]}),
            ("holder-enumeration-unavailable-initial", {"holders": [None]}),
            ("candidate-fd-lost-on-reproof", {"candidate_holds": [True, False]}),
            (
                "another-current-user-holder-on-reproof",
                {"holders": [(self.pid,), (self.pid, self.pid + 1)]},
            ),
        )
        for label, overrides in cases:
            with self.subTest(label=label):
                proof = self._prove(**overrides)
                self.assertFalse(proof.ok)
                self.assertEqual("socket-owner", proof.reason)

    def test_listener_proof_rejects_listener_loss_inode_change_and_identity_drift(self) -> None:
        candidate = supervisor.ListenerCandidate(self.pid, self.start_ticks)
        match = supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MATCH, candidate)
        mismatch = supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH)
        cases: tuple[tuple[str, dict[str, object], str], ...] = (
            (
                "listener-lost",
                {
                    "socket_probes": [
                        (self.socket_inode, "socket-listener"),
                        (None, "socket-listener-absent"),
                    ]
                },
                "socket-listener-absent",
            ),
            (
                "listener-inode-changed",
                {
                    "socket_probes": [
                        (self.socket_inode, "socket-listener"),
                        (self.socket_inode + 1, "socket-listener"),
                    ]
                },
                "listener-inode",
            ),
            (
                "identity-drift-after-first-socket-proof",
                {"identity_reproofs": [mismatch]},
                "listener-start-ticks",
            ),
            (
                "identity-drift-after-final-socket-proof",
                {"identity_reproofs": [match, mismatch]},
                "listener-start-ticks",
            ),
            (
                "identity-unavailable-after-first-socket-proof",
                {
                    "identity_reproofs": [
                        supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_PROC_UNAVAILABLE)
                    ]
                },
                "listener-candidate-proc-unavailable",
            ),
            (
                "identity-malformed-after-final-socket-proof",
                {
                    "identity_reproofs": [
                        match,
                        supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_PROC_MALFORMED),
                    ]
                },
                "listener-candidate-proc-malformed",
            ),
        )
        for label, overrides, expected_reason in cases:
            with self.subTest(label=label):
                proof = self._prove(**overrides)
                self.assertFalse(proof.ok)
                self.assertEqual(expected_reason, proof.reason)

    def _prove(
        self,
        *,
        candidate_result: tuple[object | None, str] | None = None,
        socket_probes: list[tuple[int | None, str]] | None = None,
        candidate_holds: list[bool] | None = None,
        holders: list[tuple[int, ...] | None] | None = None,
        identity_reproofs: list[object | None] | None = None,
        java: Path | None | object = ...,
        expected_argv: list[str] | None | object = ...,
        return_patches: bool = False,
    ) -> object:
        actual_java = self.java if java is ... else java
        actual_expected_argv = self.expected_argv if expected_argv is ... else expected_argv
        candidate = supervisor.ListenerCandidate(self.pid, self.start_ticks)
        actual_candidate_result = (
            (candidate, "listener-candidate") if candidate_result is None else candidate_result
        )
        actual_socket_probes = socket_probes or [
            (self.socket_inode, "socket-listener"),
            (self.socket_inode, "socket-listener"),
        ]
        actual_candidate_holds = candidate_holds or [True, True]
        actual_holders = holders or [(self.pid,), (self.pid,)]
        match = supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MATCH, candidate)
        actual_identity_reproofs = identity_reproofs or [match, match]

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "safe_run_directory", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "expected_launcher_argv", return_value=actual_expected_argv))
            stack.enter_context(mock.patch.object(supervisor, "trusted_java_executable", return_value=actual_java))
            find_candidate = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "find_exact_launcher_candidate",
                    return_value=actual_candidate_result,
                )
            )
            socket_probe = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "listener_socket_probe_for_candidate",
                    side_effect=actual_socket_probes,
                )
            )
            candidate_holds_patch = stack.enter_context(
                mock.patch.object(supervisor, "candidate_holds_socket", side_effect=actual_candidate_holds)
            )
            holders_patch = stack.enter_context(
                mock.patch.object(supervisor, "current_uid_socket_holders", side_effect=actual_holders)
            )
            identity = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "exact_launcher_candidate_identity",
                    side_effect=actual_identity_reproofs,
                )
            )
            proof = supervisor.prove_owned_loopback_launcher(
                port=24_570,
                run_id=self.run_id,
                run_dir=self.run_dir,
                artifact_root=self.artifact_root,
                repo_root=self.root,
                exercise_pid=40_001,
                exercise_start_ticks=40_002,
            )
            if return_patches:
                return proof, {
                    "find_candidate": find_candidate,
                    "socket_probe": socket_probe,
                    "candidate_holds": candidate_holds_patch,
                    "holders": holders_patch,
                    "identity": identity,
                }
            return proof


class ListenerCandidateIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.pid = 42_424
        self.start_ticks = 123_456
        self.run_dir = Path("/safe/run")
        self.java = Path("/trusted/java")
        self.expected_argv = [str(self.java), "-jar", "/trusted/launcher.jar"]

    def test_exact_candidate_identity_requires_every_stable_component(self) -> None:
        probe = self._identity()
        self.assertEqual(supervisor.IDENTITY_MATCH, probe.status)
        self.assertEqual(supervisor.ListenerCandidate(self.pid, self.start_ticks), probe.candidate)

        cases: tuple[tuple[str, dict[str, object]], ...] = (
            ("initial-identity", {"initial_status": supervisor.IDENTITY_MISMATCH}),
            ("zombie", {"initial_state": "Z"}),
            ("start-ticks", {"initial_start_ticks": self.start_ticks + 1}),
            ("argv", {"argv": [str(self.java), "-jar", "/wrong/launcher.jar"]}),
            ("cwd", {"cwd": Path("/wrong/run")}),
            ("java", {"java": Path("/wrong/java")}),
            ("ancestor", {"lineage_status": supervisor.IDENTITY_MISMATCH}),
            ("final-start-ticks", {"final_start_ticks": self.start_ticks + 1}),
            ("final-zombie", {"final_state": "Z"}),
        )
        for label, overrides in cases:
            with self.subTest(label=label):
                rejected = self._identity(**overrides)
                self.assertEqual(supervisor.IDENTITY_MISMATCH, rejected.status)
                self.assertIsNone(rejected.candidate)

    def test_identity_unavailable_and_malformed_are_never_collapsed_to_mismatch(self) -> None:
        cases: tuple[tuple[str, dict[str, object], str], ...] = (
            (
                "initial-unavailable",
                {"initial_status": supervisor.IDENTITY_PROC_UNAVAILABLE},
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "initial-malformed",
                {"initial_status": supervisor.IDENTITY_PROC_MALFORMED},
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
            (
                "argv-unavailable",
                {"argv_status": supervisor.IDENTITY_PROC_UNAVAILABLE},
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "argv-malformed",
                {"argv_status": supervisor.IDENTITY_PROC_MALFORMED},
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
            (
                "cwd-unavailable",
                {"cwd_status": supervisor.IDENTITY_PROC_UNAVAILABLE},
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "exe-malformed",
                {"exe_status": supervisor.IDENTITY_PROC_MALFORMED},
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
            (
                "lineage-unavailable",
                {"lineage_status": supervisor.IDENTITY_PROC_UNAVAILABLE},
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "lineage-malformed",
                {"lineage_status": supervisor.IDENTITY_PROC_MALFORMED},
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
            (
                "final-malformed",
                {"final_status": supervisor.IDENTITY_PROC_MALFORMED},
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
        )
        for label, overrides, expected_status in cases:
            with self.subTest(label=label):
                probe = self._identity(**overrides)
                self.assertEqual(expected_status, probe.status)
                self.assertIsNone(probe.candidate)

    def test_readable_argv_mismatch_short_circuits_deeper_identity_reads(self) -> None:
        probe, patches = self._identity(
            argv=[str(self.java), "-jar", "/unrelated.jar"],
            return_patches=True,
        )

        self.assertEqual(supervisor.IDENTITY_MISMATCH, probe.status)
        patches["links"].assert_not_called()
        patches["lineage"].assert_not_called()

    def _identity(
        self,
        *,
        initial_status: str = supervisor.IDENTITY_MATCH,
        initial_state: str = "S",
        initial_start_ticks: int | None = None,
        final_status: str = supervisor.IDENTITY_MATCH,
        final_state: str = "S",
        final_start_ticks: int | None = None,
        argv_status: str = supervisor.IDENTITY_MATCH,
        cwd: Path | None = None,
        cwd_status: str = supervisor.IDENTITY_MATCH,
        java: Path | None = None,
        exe_status: str = supervisor.IDENTITY_MATCH,
        argv: list[str] | None = None,
        lineage_status: str = supervisor.IDENTITY_MATCH,
        return_patches: bool = False,
    ) -> object:
        observed_initial_ticks = self.start_ticks if initial_start_ticks is None else initial_start_ticks
        observed_final_ticks = self.start_ticks if final_start_ticks is None else final_start_ticks
        observed_cwd = self.run_dir if cwd is None else cwd
        observed_java = self.java if java is None else java
        observed_argv = self.expected_argv if argv is None else argv

        with contextlib.ExitStack() as stack:
            snapshots = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "proc_identity_snapshot",
                    side_effect=[
                        (
                            (initial_state, 40_001, observed_initial_ticks)
                            if initial_status == supervisor.IDENTITY_MATCH
                            else None,
                            initial_status,
                        ),
                        (
                            (final_state, 40_001, observed_final_ticks)
                            if final_status == supervisor.IDENTITY_MATCH
                            else None,
                            final_status,
                        ),
                    ],
                )
            )
            command = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "command_line_identity_probe",
                    return_value=(observed_argv if argv_status == supervisor.IDENTITY_MATCH else None, argv_status),
                )
            )

            def link_probe(_pid: int, name: str) -> tuple[Path | None, str]:
                if name == "cwd":
                    return (observed_cwd if cwd_status == supervisor.IDENTITY_MATCH else None, cwd_status)
                if name == "exe":
                    return (observed_java if exe_status == supervisor.IDENTITY_MATCH else None, exe_status)
                raise AssertionError(f"unexpected proc link: {name}")

            links = stack.enter_context(
                mock.patch.object(supervisor, "proc_link_identity_probe", side_effect=link_probe)
            )
            lineage = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "launcher_lineage_identity_status",
                    return_value=lineage_status,
                )
            )
            probe = supervisor.exact_launcher_candidate_identity(
                pid=self.pid,
                expected_start_ticks=self.start_ticks,
                expected_argv=self.expected_argv,
                java=self.java,
                run_dir=self.run_dir,
                exercise_pid=40_001,
                exercise_start_ticks=40_002,
            )
            if return_patches:
                return probe, {
                    "snapshots": snapshots,
                    "command": command,
                    "links": links,
                    "lineage": lineage,
                }
            return probe


class ListenerProcIdentityFieldTest(unittest.TestCase):
    def setUp(self) -> None:
        self.pid = 42_425
        self.proc_dir = f"/proc/{self.pid}"

    def test_stat_field_distinguishes_disappearance_unavailable_and_malformed(self) -> None:
        cases: tuple[tuple[str, object, BaseException | None, str], ...] = (
            (
                "disappeared",
                FileNotFoundError("stat disappeared"),
                FileNotFoundError("process disappeared"),
                supervisor.IDENTITY_MISMATCH,
            ),
            (
                "unavailable",
                PermissionError("stat denied"),
                None,
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "malformed",
                "not-a-proc-stat-record",
                None,
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
        )
        for label, stat_payload, proc_error, expected_status in cases:
            with self.subTest(label=label):
                path_type = self._path_type(
                    text_payloads={f"{self.proc_dir}/stat": stat_payload},
                    proc_error=proc_error,
                )
                with mock.patch.object(supervisor, "Path", side_effect=path_type):
                    snapshot, status = supervisor.proc_identity_snapshot(self.pid)
                self.assertIsNone(snapshot)
                self.assertEqual(expected_status, status)

    def test_cmdline_field_distinguishes_disappearance_unavailable_and_malformed(self) -> None:
        cases: tuple[tuple[str, object, BaseException | None, str], ...] = (
            (
                "disappeared",
                FileNotFoundError("cmdline disappeared"),
                FileNotFoundError("process disappeared"),
                supervisor.IDENTITY_MISMATCH,
            ),
            (
                "unavailable",
                PermissionError("cmdline denied"),
                None,
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            ("missing-terminator", b"/trusted/java", None, supervisor.IDENTITY_PROC_MALFORMED),
            ("invalid-utf8", b"\xff\0", None, supervisor.IDENTITY_PROC_MALFORMED),
        )
        for label, cmdline_payload, proc_error, expected_status in cases:
            with self.subTest(label=label):
                path_type = self._path_type(
                    byte_payloads={f"{self.proc_dir}/cmdline": cmdline_payload},
                    proc_error=proc_error,
                )
                with mock.patch.object(supervisor, "Path", side_effect=path_type):
                    argv, status = supervisor.command_line_identity_probe(self.pid)
                self.assertIsNone(argv)
                self.assertEqual(expected_status, status)

    def test_cwd_and_exe_fields_distinguish_disappearance_unavailable_and_malformed(self) -> None:
        cases: tuple[tuple[str, BaseException, BaseException | None, str], ...] = (
            (
                "disappeared",
                FileNotFoundError("link disappeared"),
                FileNotFoundError("process disappeared"),
                supervisor.IDENTITY_MISMATCH,
            ),
            (
                "unavailable",
                PermissionError("link denied"),
                None,
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "malformed",
                RuntimeError("symlink loop"),
                None,
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
        )
        for link_name in ("cwd", "exe"):
            for label, link_error, proc_error, expected_status in cases:
                with self.subTest(link=link_name, label=label):
                    path_type = self._path_type(
                        resolve_payloads={f"{self.proc_dir}/{link_name}": link_error},
                        proc_error=proc_error,
                    )
                    with mock.patch.object(supervisor, "Path", side_effect=path_type):
                        resolved, status = supervisor.proc_link_identity_probe(self.pid, link_name)
                    self.assertIsNone(resolved)
                    self.assertEqual(expected_status, status)

    def test_failed_field_after_pid_owner_replacement_is_a_mismatch(self) -> None:
        path_type = self._path_type(proc_uid=os.getuid() + 1)
        with mock.patch.object(supervisor, "Path", side_effect=path_type):
            status = supervisor.proc_identity_failure_status(self.pid, malformed=True)
        self.assertEqual(supervisor.IDENTITY_MISMATCH, status)

    def _path_type(
        self,
        *,
        text_payloads: dict[str, object] | None = None,
        byte_payloads: dict[str, object] | None = None,
        resolve_payloads: dict[str, object] | None = None,
        proc_error: BaseException | None = None,
        proc_uid: int | None = None,
    ) -> type[object]:
        proc_dir = self.proc_dir
        text_payloads = text_payloads or {}
        byte_payloads = byte_payloads or {}
        resolve_payloads = resolve_payloads or {}

        class FakeProcPath:
            def __init__(self, raw_path: object) -> None:
                self.raw_path = os.fspath(raw_path)

            def stat(self) -> SimpleNamespace:
                if self.raw_path != proc_dir:
                    raise AssertionError(f"unexpected stat path: {self.raw_path}")
                if proc_error is not None:
                    raise proc_error
                return SimpleNamespace(st_uid=os.getuid() if proc_uid is None else proc_uid)

            def read_text(self, *, encoding: str) -> str:
                payload = text_payloads[self.raw_path]
                if isinstance(payload, BaseException):
                    raise payload
                if not isinstance(payload, str):
                    raise AssertionError(f"unexpected text payload: {payload!r}")
                return payload

            def read_bytes(self) -> bytes:
                payload = byte_payloads[self.raw_path]
                if isinstance(payload, BaseException):
                    raise payload
                if not isinstance(payload, bytes):
                    raise AssertionError(f"unexpected bytes payload: {payload!r}")
                return payload

            def resolve(self, *, strict: bool) -> Path:
                payload = resolve_payloads[self.raw_path]
                if isinstance(payload, BaseException):
                    raise payload
                if not isinstance(payload, Path):
                    raise AssertionError(f"unexpected link payload: {payload!r}")
                return payload

        return FakeProcPath


class ListenerCandidateDiscoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.expected_argv = ["/trusted/java", "-jar", "/trusted/launcher.jar"]
        self.java = Path("/trusted/java")
        self.run_dir = Path("/safe/run")

    def test_discovery_requires_exactly_one_candidate(self) -> None:
        exact_candidates = {
            42_001: supervisor.ListenerCandidate(42_001, 101),
            42_002: supervisor.ListenerCandidate(42_002, 102),
        }
        probes = {
            pid: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MATCH, candidate)
            for pid, candidate in exact_candidates.items()
        }
        cases = (
            (
                "zero",
                [42_001],
                {42_001: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH)},
                "listener-candidate-absent",
            ),
            (
                "one",
                [42_001],
                {42_001: probes[42_001]},
                "listener-candidate",
            ),
            (
                "multiple",
                [42_001, 42_002],
                probes,
                "listener-candidate-ambiguous",
            ),
        )
        for label, pids, identities, expected_reason in cases:
            with self.subTest(label=label):
                candidate, reason = self._find(pids=pids, identities=identities)
                self.assertEqual(expected_reason, reason)
                if label == "one":
                    self.assertEqual(exact_candidates[42_001], candidate)
                else:
                    self.assertIsNone(candidate)

    def test_discovery_fails_closed_for_unavailable_or_malformed_current_user_identity(self) -> None:
        with self.subTest("proc-root-unavailable"):
            candidate, reason = self._find(pids=[], root_error=OSError("proc unavailable"))
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-unavailable", reason)

        with self.subTest("current-user-entry-unavailable"):
            candidate, reason = self._find(
                pids=[42_001],
                stat_errors={42_001: PermissionError("entry unavailable")},
            )
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-unavailable", reason)

        for status, expected_reason in (
            (supervisor.IDENTITY_PROC_UNAVAILABLE, "listener-candidate-proc-unavailable"),
            (supervisor.IDENTITY_PROC_MALFORMED, "listener-candidate-proc-malformed"),
        ):
            with self.subTest(identity_status=status):
                candidate, reason = self._find(
                    pids=[42_001],
                    identities={42_001: supervisor.ListenerCandidateIdentityProbe(status)},
                )
                self.assertIsNone(candidate)
                self.assertEqual(expected_reason, reason)

    def test_disappeared_race_is_ignored_but_readable_mismatch_does_not_block_exact_candidate(self) -> None:
        with self.subTest("disappeared-entry-is-not-a-candidate"):
            candidate, reason = self._find(
                pids=[42_001],
                stat_errors={42_001: FileNotFoundError("entry disappeared")},
            )
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-absent", reason)

        exact = supervisor.ListenerCandidate(42_002, 102)
        candidate, reason = self._find(
            pids=[42_001, 42_002],
            identities={
                42_001: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH),
                42_002: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MATCH, exact),
            },
        )
        self.assertEqual("listener-candidate", reason)
        self.assertEqual(exact, candidate)

    def _find(
        self,
        *,
        pids: list[int],
        identities: dict[int, object] | None = None,
        root_error: OSError | None = None,
        stat_errors: dict[int, BaseException] | None = None,
    ) -> tuple[object | None, str]:
        identities = identities or {}
        stat_errors = stat_errors or {}

        class FakeProcEntry:
            def __init__(self, pid: int) -> None:
                self.pid = pid
                self.name = str(pid)

            def stat(self) -> SimpleNamespace:
                error = stat_errors.get(self.pid)
                if error is not None:
                    raise error
                return SimpleNamespace(st_uid=os.getuid())

        class FakeProcRoot:
            def iterdir(self) -> tuple[FakeProcEntry, ...]:
                if root_error is not None:
                    raise root_error
                return tuple(FakeProcEntry(pid) for pid in pids)

        def exact_identity(*, pid: int, **_kwargs: object) -> object:
            return identities.get(
                pid,
                supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH),
            )

        with mock.patch.object(supervisor, "Path", return_value=FakeProcRoot()), mock.patch.object(
            supervisor,
            "exact_launcher_candidate_identity",
            side_effect=exact_identity,
        ):
            return supervisor.find_exact_launcher_candidate(
                expected_argv=self.expected_argv,
                java=self.java,
                run_dir=self.run_dir,
                exercise_pid=41_999,
                exercise_start_ticks=99,
            )


class ListenerSocketProbeTest(unittest.TestCase):
    def test_candidate_socket_probe_returns_only_fixed_fail_closed_reasons(self) -> None:
        port = 24_570
        header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
        loopback_listener = self._tcp_line(f"0100007F:{port:04X}", inode="789012")
        alternate_loopback_listener = self._tcp_line(f"0100007F:{port:04X}", inode="789013")
        wildcard_listener = self._tcp_line(f"00000000:{port:04X}", inode="789012")
        malformed_inode = self._tcp_line(f"0100007F:{port:04X}", inode="not-an-inode")
        cases: tuple[tuple[str, object, object, tuple[int | None, str]], ...] = (
            (
                "strict-loopback-listener",
                f"{header}\n{loopback_listener}\n",
                f"{header}\n",
                (789012, "socket-listener"),
            ),
            ("absent", f"{header}\n", f"{header}\n", (None, "socket-listener-absent")),
            (
                "ambiguous",
                f"{header}\n{loopback_listener}\n{alternate_loopback_listener}\n",
                f"{header}\n",
                (None, "socket-listener-ambiguous"),
            ),
            (
                "nonloopback-or-ipv6",
                f"{header}\n{wildcard_listener}\n",
                f"{header}\n",
                (None, "socket-listener-nonloopback-or-ipv6"),
            ),
            (
                "proc-unavailable",
                OSError("offline proc unavailable"),
                f"{header}\n",
                (None, "socket-listener-proc-unavailable"),
            ),
            (
                "proc-malformed",
                f"{header}\n{malformed_inode}\n",
                f"{header}\n",
                (None, "socket-listener-proc-malformed"),
            ),
        )
        for label, tcp, tcp6, expected in cases:
            with self.subTest(label=label):
                self.assertEqual(expected, self._probe(port, tcp=tcp, tcp6=tcp6))

    @staticmethod
    def _tcp_line(local_address: str, *, inode: str) -> str:
        return (
            f"0: {local_address} 00000000:0000 0A 00000000:00000000 "
            f"00:00000000 00000000 1000 0 {inode} 1"
        )

    @staticmethod
    def _probe(port: int, *, tcp: object, tcp6: object) -> tuple[int | None, str]:
        pid = 42_424
        payloads = {f"/proc/{pid}/net/tcp": tcp, f"/proc/{pid}/net/tcp6": tcp6}

        class FakeProcNetPath:
            def __init__(self, raw_path: object) -> None:
                self.raw_path = os.fspath(raw_path)
                self.name = self.raw_path.rsplit("/", 1)[-1]

            def read_text(self, *, encoding: str) -> str:
                payload = payloads[self.raw_path]
                if isinstance(payload, BaseException):
                    raise payload
                if not isinstance(payload, str):
                    raise AssertionError(f"unexpected proc payload: {payload!r}")
                return payload

        with mock.patch.object(supervisor, "Path", side_effect=FakeProcNetPath):
            return supervisor.listener_socket_probe_for_candidate(port, pid)


@unittest.skipUnless(Path("/proc/self/net/tcp").is_file(), "Linux procfs is required")
class ListenerProcTopologyTest(unittest.TestCase):
    def test_outer_parent_held_child_exact_ipv4_listener_has_real_proc_lineage_and_ownership(self) -> None:
        topology_process = self._start_topology()
        try:
            if topology_process.stdout is None or topology_process.stdin is None:
                self.fail("isolated topology pipes were not captured")
            ready, _, _ = select.select([topology_process.stdout], [], [], 5.0)
            if not ready:
                self.fail("isolated topology did not publish its test-owned listener")
            topology = json.loads(topology_process.stdout.readline())
            self.assertEqual({"outerPid", "childPid", "port"}, set(topology))
            outer_pid = int(topology["outerPid"])
            child_pid = int(topology["childPid"])
            port = int(topology["port"])

            self.assertGreater(outer_pid, 1)
            self.assertGreater(child_pid, 1)
            self.assertGreaterEqual(port, 1)
            self.assertLessEqual(port, 65_535)

            topology_process.stdin.write("verify\n")
            topology_process.stdin.flush()
            ready, _, _ = select.select([topology_process.stdout], [], [], 5.0)
            if not ready:
                self.fail("isolated topology did not publish procfs verification")
            evidence = json.loads(topology_process.stdout.readline())

            self.assertEqual(outer_pid, evidence["outerPid"])
            self.assertEqual(child_pid, evidence["childPid"])
            self.assertEqual(port, evidence["port"])
            self.assertGreater(int(evidence["outerStartTicks"]), 0)
            self.assertEqual(outer_pid, evidence["observedParentPid"])
            self.assertTrue(evidence["descendant"])
            self.assertEqual("socket-listener", evidence["listenerReason"])
            self.assertTrue(evidence["listenerInodePresent"])
            self.assertTrue(evidence["candidateHoldsSocket"])
            self.assertEqual([child_pid], evidence["currentUidSocketHolders"])
            self.assertEqual("MATCH", evidence["identityStatus"])
            self.assertEqual(child_pid, evidence["identityCandidatePid"])
            self.assertEqual("listener-candidate", evidence["candidateReason"])
            self.assertEqual(child_pid, evidence["candidatePid"])
            self.assertTrue(evidence["ownedLoopbackProofOk"])
            self.assertEqual(
                "uid+java+argv+cwd+ancestor+socket+startTicks",
                evidence["ownedLoopbackProofReason"],
            )
        finally:
            returncode, stderr = self._stop_topology(topology_process)

        self.assertEqual(0, returncode, stderr)

    @staticmethod
    def _start_topology() -> subprocess.Popen[str]:
        unshare = shutil.which("unshare")
        if unshare is None:
            raise unittest.SkipTest("unshare is required for an isolated real procfs topology")
        child_code = (
            "import json, os, socket, sys\n"
            "listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)\n"
            "listener.bind(('127.0.0.1', 0))\n"
            "listener.listen()\n"
            "print(json.dumps({'childPid': os.getpid(), 'port': listener.getsockname()[1]}), flush=True)\n"
            "command = sys.stdin.readline().strip()\n"
            "listener.close()\n"
            "raise SystemExit(0 if command == 'stop' else 3)\n"
        )
        outer_code = (
            "import json, os, subprocess, sys\n"
            "from pathlib import Path\n"
            f"child_code = {child_code!r}\n"
            "python = str(Path(sys.executable).resolve())\n"
            "child = subprocess.Popen([python, '-c', child_code], "
            "stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)\n"
            "try:\n"
            "    child_line = child.stdout.readline() if child.stdout is not None else ''\n"
            "    child_topology = json.loads(child_line)\n"
            "    print(json.dumps({'outerPid': os.getpid(), **child_topology}), flush=True)\n"
            "    command = sys.stdin.readline().strip()\n"
            "    if child.stdin is not None:\n"
            "        child.stdin.write('stop\\n' if command == 'stop' else 'abort\\n')\n"
            "        child.stdin.flush()\n"
            "        child.stdin.close()\n"
            "    raise SystemExit(child.wait(timeout=5))\n"
            "finally:\n"
            "    if child.poll() is None:\n"
            "        child.terminate()\n"
            "        try:\n"
            "            child.wait(timeout=5)\n"
            "        except subprocess.TimeoutExpired:\n"
            "            child.kill()\n"
            "            child.wait(timeout=5)\n"
        )
        verifier_code = (
            "import importlib.util, json, subprocess, sys\n"
            "from pathlib import Path\n"
            f"supervisor_path = {str(SUPERVISOR_PATH)!r}\n"
            f"child_code = {child_code!r}\n"
            f"outer_code = {outer_code!r}\n"
            "module_name = 'int001_real_proc_topology_target'\n"
            "spec = importlib.util.spec_from_file_location(module_name, supervisor_path)\n"
            "if spec is None or spec.loader is None:\n"
            "    raise RuntimeError('could not load supervisor')\n"
            "target = importlib.util.module_from_spec(spec)\n"
            "sys.modules[module_name] = target\n"
            "spec.loader.exec_module(target)\n"
            "outer = subprocess.Popen([sys.executable, '-c', outer_code], "
            "stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)\n"
            "try:\n"
            "    topology_line = outer.stdout.readline() if outer.stdout is not None else ''\n"
            "    topology = json.loads(topology_line)\n"
            "    print(json.dumps(topology), flush=True)\n"
            "    command = sys.stdin.readline().strip()\n"
            "    if command == 'verify':\n"
            "        outer_pid = int(topology['outerPid'])\n"
            "        child_pid = int(topology['childPid'])\n"
            "        port = int(topology['port'])\n"
            "        outer_start_ticks = target.proc_stat(outer_pid)[2]\n"
            "        java = Path(sys.executable).resolve()\n"
            "        expected_argv = [str(java), '-c', child_code]\n"
            "        run_dir = Path.cwd().resolve()\n"
            "        identity = target.exact_launcher_candidate_identity(\n"
            "            pid=child_pid, expected_start_ticks=None, expected_argv=expected_argv,\n"
            "            java=java, run_dir=run_dir, exercise_pid=outer_pid,\n"
            "            exercise_start_ticks=outer_start_ticks,\n"
            "        )\n"
            "        candidate, candidate_reason = target.find_exact_launcher_candidate(\n"
            "            expected_argv=expected_argv, java=java, run_dir=run_dir,\n"
            "            exercise_pid=outer_pid, exercise_start_ticks=outer_start_ticks,\n"
            "        )\n"
            "        inode, reason = target.listener_socket_probe_for_candidate(port, child_pid)\n"
            "        holders = target.current_uid_socket_holders(inode) if inode is not None else None\n"
            "        target.safe_run_directory = lambda run_dir, artifact_root: True\n"
            "        target.expected_launcher_argv = lambda repo_root, run_id: expected_argv\n"
            "        target.trusted_java_executable = lambda: java\n"
            "        proof = target.prove_owned_loopback_launcher(\n"
            "            port=port, run_id='test-owned-real-proc', run_dir=run_dir,\n"
            "            artifact_root=run_dir.parent, repo_root=run_dir,\n"
            "            exercise_pid=outer_pid, exercise_start_ticks=outer_start_ticks,\n"
            "        )\n"
            "        evidence = {\n"
            "            'outerPid': outer_pid,\n"
            "            'childPid': child_pid,\n"
            "            'port': port,\n"
            "            'outerStartTicks': outer_start_ticks,\n"
            "            'observedParentPid': target.proc_parent_pid(child_pid),\n"
            "            'descendant': target.is_descendant_of(child_pid, outer_pid, outer_start_ticks),\n"
            "            'listenerReason': reason,\n"
            "            'listenerInodePresent': inode is not None,\n"
            "            'candidateHoldsSocket': (\n"
            "                target.candidate_holds_socket(child_pid, inode) if inode is not None else False\n"
            "            ),\n"
            "            'currentUidSocketHolders': list(holders) if holders is not None else None,\n"
            "            'identityStatus': identity.status,\n"
            "            'identityCandidatePid': (identity.candidate.pid if identity.candidate is not None else None),\n"
            "            'candidateReason': candidate_reason,\n"
            "            'candidatePid': candidate.pid if candidate is not None else None,\n"
            "            'ownedLoopbackProofOk': proof.ok,\n"
            "            'ownedLoopbackProofReason': proof.reason,\n"
            "        }\n"
            "        print(json.dumps(evidence), flush=True)\n"
            "        command = sys.stdin.readline().strip()\n"
            "    if outer.stdin is not None:\n"
            "        outer.stdin.write('stop\\n' if command == 'stop' else 'abort\\n')\n"
            "        outer.stdin.flush()\n"
            "        outer.stdin.close()\n"
            "    raise SystemExit(outer.wait(timeout=5))\n"
            "finally:\n"
            "    if outer.poll() is None:\n"
            "        outer.terminate()\n"
            "        try:\n"
            "            outer.wait(timeout=5)\n"
            "        except subprocess.TimeoutExpired:\n"
            "            outer.kill()\n"
            "            outer.wait(timeout=5)\n"
        )
        return subprocess.Popen(
            [
                unshare,
                "--user",
                "--map-current-user",
                "--pid",
                "--fork",
                "--mount-proc",
                sys.executable,
                "-c",
                verifier_code,
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            start_new_session=True,
            env={"LANG": "C.UTF-8", "PYTHONUNBUFFERED": "1"},
        )

    @staticmethod
    def _stop_topology(topology_process: subprocess.Popen[str]) -> tuple[int, str]:
        if topology_process.poll() is None and topology_process.stdin is not None:
            try:
                topology_process.stdin.write("stop\n")
                topology_process.stdin.flush()
                topology_process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
        try:
            returncode = topology_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(topology_process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                returncode = topology_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(topology_process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                returncode = topology_process.wait(timeout=5)
        if returncode != 0:
            try:
                os.killpg(topology_process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        stderr = topology_process.stderr.read() if topology_process.stderr is not None else ""
        if topology_process.stdout is not None:
            topology_process.stdout.close()
        if topology_process.stderr is not None:
            topology_process.stderr.close()
        return returncode, stderr


class ExerciseParentProofTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository_root = Path(self.temporary_directory.name)
        self.harness = (
            self.repository_root
            / "tools"
            / "navigator-upstream"
            / "scripts"
            / "synthetic-upstream-harness.sh"
        )
        self.run_id = "int001-parent-proof"
        self.navigator_port = 24_571
        self.pid = 42_471
        self.start_ticks = 123_457

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_exact_outer_exercise_argv_includes_forced_signal_rehearsal(self) -> None:
        self.assertEqual(
            [
                "/usr/bin/bash",
                "-p",
                str(self.harness),
                "exercise",
                "--allow-create",
                "--allow-execute",
                "--build-launcher",
                "--run-id",
                self.run_id,
                "--navigator-port",
                str(self.navigator_port),
                "--forced-signal-rehearsal",
            ],
            supervisor.exact_child_argv(self.harness, self.run_id, self.navigator_port),
        )

    def test_parent_proof_rejects_noncanonical_rehearsal_argv(self) -> None:
        canonical_argv = supervisor.exact_child_argv(self.harness, self.run_id, self.navigator_port)
        variants = (
            ("missing-rehearsal-flag", canonical_argv[:-1]),
            (
                "reordered-rehearsal-flag",
                [*canonical_argv[:-2], "--forced-signal-rehearsal", *canonical_argv[-2:]],
            ),
            ("extra-argument", [*canonical_argv, "--unexpected"]),
        )

        self.assertTrue(self._prove(canonical_argv, canonical_argv).ok)
        for label, observed_argv in variants:
            with self.subTest(label=label):
                proof = self._prove(observed_argv, canonical_argv)
                self.assertFalse(proof.ok)
                self.assertEqual("argv", proof.reason)

    def _prove(self, observed_argv: list[str], expected_argv: list[str]) -> object:
        pid = self.pid
        repository_root = self.repository_root

        class FakeProcPath:
            def __init__(self, raw_path: object) -> None:
                self.raw_path = os.fspath(raw_path)

            def stat(self) -> SimpleNamespace:
                if self.raw_path == f"/proc/{pid}":
                    return SimpleNamespace(st_uid=os.getuid())
                raise AssertionError(f"unexpected stat path: {self.raw_path}")

            def resolve(self, strict: bool = False) -> Path:
                if self.raw_path == f"/proc/{pid}/cwd":
                    return repository_root
                raise AssertionError(f"unexpected resolve path: {self.raw_path}")

            def read_bytes(self) -> bytes:
                if self.raw_path == f"/proc/{pid}/cmdline":
                    return b"\0".join(item.encode("utf-8") for item in observed_argv) + b"\0"
                raise AssertionError(f"unexpected read path: {self.raw_path}")

        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "proc_stat",
                    side_effect=[
                        (pid, pid, self.start_ticks),
                        (pid, pid, self.start_ticks),
                    ],
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "Path", side_effect=FakeProcPath))
            return supervisor.prove_exercise_parent(
                pid,
                self.start_ticks,
                repository_root,
                expected_argv,
            )


class SupervisorOrchestrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repository_root = Path(self.temporary_directory.name)
        self.harness = (
            self.repository_root
            / "tools"
            / "navigator-upstream"
            / "scripts"
            / "synthetic-upstream-harness.sh"
        )
        self.harness.parent.mkdir(parents=True)
        self.harness.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        os.chmod(self.harness, 0o700)
        self.artifact_root = self.repository_root / "temp" / "test-artifacts" / "INT-001"
        self.artifact_root.mkdir(parents=True, mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        self.run_id = "int001-supervisor-main"
        self.run_dir = self.artifact_root / self.run_id
        self.args = argparse.Namespace(
            run_id=self.run_id,
            navigator_port=24_569,
            health_timeout_seconds=30,
            post_term_timeout_seconds=30,
        )
        supervisor.SUPERVISOR_INTERRUPTION = None

    def tearDown(self) -> None:
        supervisor.SUPERVISOR_INTERRUPTION = None
        self.temporary_directory.cleanup()

    def test_unsafe_docker_socket_blocks_before_child_start(self) -> None:
        with self._base_patches(docker_socket_safe=False) as stack:
            start = stack.enter_context(mock.patch.object(supervisor, "start_exercise"))

            with self.assertRaises(RuntimeError):
                supervisor.main()

        start.assert_not_called()
        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("FAILED", projection["phase"])
        self.assertEqual("PREFLIGHT_FAILED", projection["outcome"])
        self.assertEqual("NOT_EMITTED", projection["stdoutSummaryState"])

    def test_preblocked_control_mask_blocks_before_child_start(self) -> None:
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "control_signal_mask_is_clear", return_value=False))
            start = stack.enter_context(mock.patch.object(supervisor, "start_exercise"))

            with self.assertRaises(RuntimeError):
                supervisor.main()

        start.assert_not_called()

    def test_pending_control_signal_blocks_before_child_start(self) -> None:
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "control_signal_mask_is_clear", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "pending_control_signals", return_value={signal.SIGTERM}))
            start = stack.enter_context(mock.patch.object(supervisor, "start_exercise"))

            with self.assertRaises(RuntimeError):
                supervisor.main()

        start.assert_not_called()
        self.assertEqual("TERM", supervisor.SUPERVISOR_INTERRUPTION)

    def test_unsafe_canonical_artifact_root_blocks_before_child_start(self) -> None:
        os.chmod(self.artifact_root, 0o755)
        with self._base_patches(docker_socket_safe=True) as stack:
            start = stack.enter_context(mock.patch.object(supervisor, "start_exercise"))

            with self.assertRaises(RuntimeError):
                supervisor.main()

        start.assert_not_called()

    def test_health_timeout_never_dispatches_parent_term(self) -> None:
        with contextlib.ExitStack() as stack:
            listener = stack.enter_context(mock.patch.object(supervisor, "prove_owned_loopback_launcher"))
            parent = stack.enter_context(mock.patch.object(supervisor, "prove_exercise_parent"))
            health = stack.enter_context(mock.patch.object(supervisor, "health_ready"))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            wait = stack.enter_context(mock.patch.object(supervisor, "wait_for_child_exit", return_value=0))
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
            stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 31.0]))

            outcome = self._supervise()

        listener.assert_not_called()
        parent.assert_not_called()
        health.assert_not_called()
        dispatch.assert_not_called()
        wait.assert_not_called()
        self.assertFalse(outcome.health_precondition)
        self.assertEqual(0, outcome.term_dispatches)
        self.assertFalse(outcome.listener_proof_ever_eligible)

    def test_foreign_200_up_listener_never_dispatches_parent_term(self) -> None:
        foreign = supervisor.ListenerProof(False, "socket-owner")
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "prove_owned_loopback_launcher", return_value=foreign))
            health = stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
            stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 31.0]))
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            outcome = self._supervise()

        health.assert_not_called()
        dispatch.assert_not_called()
        kill.assert_not_called()
        self.assertEqual("socket-owner", outcome.listener_proof.reason)
        self.assertEqual(0, outcome.term_dispatches)
        self.assertFalse(outcome.listener_proof_ever_eligible)

    def test_valid_listener_with_noncanonical_rehearsal_parent_never_health_checks_or_dispatches(self) -> None:
        listener_proof = self._listener_proof()
        parent_proof = supervisor.ParentProof(False, "argv", 99)
        with contextlib.ExitStack() as stack:
            listener = stack.enter_context(
                mock.patch.object(supervisor, "prove_owned_loopback_launcher", return_value=listener_proof)
            )
            parent = stack.enter_context(
                mock.patch.object(supervisor, "prove_exercise_parent", return_value=parent_proof)
            )
            health = stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1]))
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            outcome = self._supervise()

        listener.assert_called_once()
        parent.assert_called_once()
        health.assert_not_called()
        dispatch.assert_not_called()
        kill.assert_not_called()
        self.assertFalse(outcome.health_precondition)
        self.assertEqual("argv", outcome.parent_proof.reason)
        self.assertEqual(0, outcome.term_dispatches)
        self.assertTrue(outcome.listener_proof_ever_eligible)

    def test_each_listener_identity_mismatch_never_dispatches_parent_term(self) -> None:
        reasons = (
            "socket-owner",
            "listener-java",
            "launcher-expected",
            "listener-candidate-absent",
            "listener-candidate-ambiguous",
            "listener-candidate-proc-unavailable",
            "listener-start-ticks",
            "listener-inode",
        )
        for reason in reasons:
            with self.subTest(reason=reason), contextlib.ExitStack() as stack:
                stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_owned_loopback_launcher",
                        return_value=supervisor.ListenerProof(False, reason),
                    )
                )
                stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
                stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
                stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 31.0]))
                dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
                kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

                outcome = self._supervise()

            dispatch.assert_not_called()
            kill.assert_not_called()
            self.assertEqual(0, outcome.term_dispatches)
            self.assertEqual(reason, outcome.listener_proof.reason)
            self.assertFalse(outcome.listener_proof_ever_eligible)

    def test_each_socket_probe_failure_never_health_checks_parent_or_dispatches(self) -> None:
        reasons = (
            "socket-listener-absent",
            "socket-listener-ambiguous",
            "socket-listener-nonloopback-or-ipv6",
            "socket-listener-proc-unavailable",
            "socket-listener-proc-malformed",
        )
        for reason in reasons:
            with self.subTest(reason=reason), contextlib.ExitStack() as stack:
                stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_owned_loopback_launcher",
                        return_value=supervisor.ListenerProof(False, reason),
                    )
                )
                parent = stack.enter_context(mock.patch.object(supervisor, "prove_exercise_parent"))
                health = stack.enter_context(mock.patch.object(supervisor, "health_ready"))
                stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
                stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
                stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 31.0]))
                dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
                kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

                outcome = self._supervise()

            parent.assert_not_called()
            health.assert_not_called()
            dispatch.assert_not_called()
            kill.assert_not_called()
            self.assertEqual(0, outcome.term_dispatches)
            self.assertEqual(reason, outcome.listener_proof.reason)
            self.assertFalse(outcome.listener_proof_ever_eligible)

    def test_listener_change_between_a_and_b_never_dispatches(self) -> None:
        baseline = self._listener_proof()
        variants = (
            ("pid", self._listener_proof(pid=baseline.pid + 1)),
            ("start-ticks", self._listener_proof(start_ticks=baseline.start_ticks + 1)),
            ("socket-inode", self._listener_proof(socket_inode=baseline.socket_inode + 1)),
        )
        for label, changed in variants:
            with self.subTest(label=label), contextlib.ExitStack() as stack:
                stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_owned_loopback_launcher",
                        side_effect=[baseline, changed],
                    )
                )
                stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_exercise_parent",
                        return_value=supervisor.ParentProof(True, "parent-proof", 100),
                    )
                )
                stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
                stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
                stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1]))
                dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
                kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

                outcome = self._supervise()

            dispatch.assert_not_called()
            kill.assert_not_called()
            self.assertFalse(outcome.listener_proof.ok)
            self.assertEqual("listener-changed", outcome.listener_proof.reason)
            self.assertEqual(0, outcome.term_dispatches)
            self.assertTrue(outcome.listener_proof_ever_eligible)

    def test_a_to_b_socket_probe_failure_is_preserved_and_never_authorizes_dispatch(self) -> None:
        baseline = self._listener_proof()
        socket_failure = supervisor.ListenerProof(False, "socket-listener-proc-malformed")
        with contextlib.ExitStack() as stack:
            listener = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_owned_loopback_launcher",
                    side_effect=[baseline, socket_failure],
                )
            )
            parent = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_exercise_parent",
                    return_value=supervisor.ParentProof(True, "parent-proof", 100),
                )
            )
            health = stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1]))
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            outcome = self._supervise()

        self.assertEqual(2, listener.call_count)
        parent.assert_called_once()
        health.assert_called_once()
        dispatch.assert_not_called()
        kill.assert_not_called()
        self.assertFalse(outcome.health_precondition)
        self.assertEqual("socket-listener-proc-malformed", outcome.listener_proof.reason)
        self.assertEqual(0, outcome.term_dispatches)
        # This is a summary diagnostic only: a once-eligible listener cannot
        # authorize dispatch after the required B re-proof has failed.
        self.assertTrue(outcome.listener_proof_ever_eligible)

    def test_listener_change_during_masked_final_reproof_never_dispatches(self) -> None:
        baseline = self._listener_proof()
        variants = (
            ("pid", self._listener_proof(pid=baseline.pid + 1)),
            ("start-ticks", self._listener_proof(start_ticks=baseline.start_ticks + 1)),
            ("socket-inode", self._listener_proof(socket_inode=baseline.socket_inode + 1)),
        )
        for label, changed in variants:
            with self.subTest(label=label), contextlib.ExitStack() as stack:
                stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
                stack.enter_context(mock.patch.object(supervisor.signal, "sigpending", return_value=set()))
                kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

                _parent, listener, dispatches, dispatch_safe = self._dispatch(
                    baseline,
                    final_listener=changed,
                )

            kill.assert_not_called()
            self.assertFalse(listener.ok)
            self.assertEqual("listener-changed", listener.reason)
            self.assertEqual(0, dispatches)
            self.assertFalse(dispatch_safe)

    def test_final_socket_probe_failure_is_preserved_and_never_dispatches(self) -> None:
        baseline = self._listener_proof()
        final_failure = supervisor.ListenerProof(False, "socket-listener-proc-malformed")
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
            stack.enter_context(mock.patch.object(supervisor.signal, "sigpending", return_value=set()))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            _parent, listener, dispatches, dispatch_safe = self._dispatch(
                baseline,
                final_listener=final_failure,
            )

        kill.assert_not_called()
        self.assertFalse(listener.ok)
        self.assertEqual("socket-listener-proc-malformed", listener.reason)
        self.assertEqual(0, dispatches)
        self.assertFalse(dispatch_safe)

    def test_stable_a_b_and_final_listener_proof_dispatches_exactly_one_term(self) -> None:
        proof = self._listener_proof()
        parent = supervisor.ParentProof(True, "parent-proof", 100)
        with contextlib.ExitStack() as stack:
            listener = stack.enter_context(
                mock.patch.object(supervisor, "prove_owned_loopback_launcher", side_effect=[proof, proof, proof])
            )
            parent_proof = stack.enter_context(mock.patch.object(supervisor, "prove_exercise_parent", return_value=parent))
            stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            wait = stack.enter_context(mock.patch.object(supervisor, "wait_for_child_exit", return_value=0))
            stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1]))
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
            stack.enter_context(mock.patch.object(supervisor.signal, "sigpending", return_value=set()))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            outcome = self._supervise()

        self.assertEqual(3, listener.call_count)
        self.assertEqual(3, parent_proof.call_count)
        kill.assert_called_once_with(42_421, signal.SIGTERM)
        wait.assert_called_once_with(42_421, 30)
        self.assertTrue(outcome.health_precondition)
        self.assertTrue(outcome.listener_proof.ok)
        self.assertTrue(outcome.parent_proof.ok)
        self.assertTrue(outcome.dispatch_safe)
        self.assertEqual(1, outcome.term_dispatches)
        self.assertTrue(outcome.listener_proof_ever_eligible)

    def test_interruption_before_dispatch_never_sends_term(self) -> None:
        supervisor.SUPERVISOR_INTERRUPTION = "INT"
        proof = self._listener_proof()
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            _parent, _listener, dispatches, dispatch_safe = self._dispatch(proof)

        kill.assert_not_called()
        self.assertEqual(0, dispatches)
        self.assertFalse(dispatch_safe)

    def test_pending_blocked_signal_never_sends_term(self) -> None:
        proof = self._listener_proof()
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
            stack.enter_context(mock.patch.object(supervisor.signal, "sigpending", return_value={signal.SIGTERM}))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            _parent, _listener, dispatches, dispatch_safe = self._dispatch(proof)

        kill.assert_not_called()
        self.assertEqual(0, dispatches)
        self.assertFalse(dispatch_safe)

    def test_inherited_control_mask_never_sends_term(self) -> None:
        proof = self._listener_proof()
        inherited_mask = {signal.SIGINT}
        with contextlib.ExitStack() as stack:
            mask = stack.enter_context(
                mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=inherited_mask)
            )
            stack.enter_context(mock.patch.object(supervisor, "pending_control_signals", return_value=set()))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            parent, listener, dispatches, dispatch_safe = self._dispatch(proof)

        kill.assert_not_called()
        self.assertEqual("signal-mask-preblocked", parent.reason)
        self.assertEqual("signal-mask-preblocked", listener.reason)
        self.assertEqual(0, dispatches)
        self.assertFalse(dispatch_safe)
        self.assertIn(mock.call(signal.SIG_SETMASK, inherited_mask), mask.call_args_list)

    def test_post_commit_pending_signal_keeps_one_term_but_makes_result_ineligible(self) -> None:
        proof = self._listener_proof()
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", return_value=set()))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "pending_control_signals",
                    side_effect=[set(), set(), {signal.SIGTERM}, set()],
                )
            )
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            _parent, listener, dispatches, dispatch_safe = self._dispatch(proof)

        kill.assert_called_once_with(42_421, signal.SIGTERM)
        self.assertEqual(1, dispatches)
        self.assertFalse(dispatch_safe)
        self.assertEqual("signal-pending-after-dispatch", listener.reason)
        self.assertEqual("TERM", supervisor.SUPERVISOR_INTERRUPTION)

    def test_interruption_during_mask_restore_never_exceeds_one_term(self) -> None:
        proof = self._listener_proof()

        def masked_signal(how: signal.Signals, _signals: set[signal.Signals]) -> set[signal.Signals]:
            if how == signal.SIG_SETMASK:
                supervisor.SUPERVISOR_INTERRUPTION = "TERM"
            return set()

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor.signal, "pthread_sigmask", side_effect=masked_signal))
            stack.enter_context(mock.patch.object(supervisor.signal, "sigpending", return_value=set()))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            _parent, _listener, dispatches, dispatch_safe = self._dispatch(proof)

        self.assertEqual(1, dispatches)
        self.assertEqual(1, kill.call_count)
        self.assertFalse(dispatch_safe)

    def test_main_interruption_after_dispatch_returns_nonzero_without_reading_evidence(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=True,
            parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
            listener_proof=self._listener_proof(),
            term_dispatches=1,
            dispatch_safe=True,
            child_exit=0,
            listener_proof_ever_eligible=True,
        )

        def interrupted_supervision(**_kwargs: object) -> object:
            supervisor.SUPERVISOR_INTERRUPTION = "TERM"
            return outcome

        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(mock.patch.object(supervisor, "supervise_exercise", side_effect=interrupted_supervision))
            receipt = stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt"))
            root = stack.enter_context(mock.patch.object(supervisor, "run_root_snapshot"))
            docker = stack.enter_context(mock.patch.object(supervisor, "docker_residue_counts"))
            emit = stack.enter_context(mock.patch.object(supervisor, "emit_summary"))

            self.assertEqual(1, supervisor.main())

        receipt.assert_not_called()
        root.assert_not_called()
        docker.assert_not_called()
        self.assertEqual(supervisor.RunRootSnapshot(None, None), emit.call_args.kwargs["root_snapshot"])
        self.assertEqual(
            {"container": None, "network": None, "volume": None},
            emit.call_args.kwargs["docker_snapshot"],
        )

    def test_main_latched_pending_signal_returns_nonzero_without_reading_evidence(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=True,
            parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
            listener_proof=self._listener_proof(),
            term_dispatches=1,
            dispatch_safe=False,
            child_exit=0,
            listener_proof_ever_eligible=True,
        )
        pending_after_supervision = False

        def pending_control_signals() -> set[signal.Signals]:
            return {signal.SIGTERM} if pending_after_supervision else set()

        def supervision_with_latched_signal(**_kwargs: object) -> object:
            nonlocal pending_after_supervision
            pending_after_supervision = True
            return outcome

        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "control_signal_mask_is_clear", return_value=True))
            stack.enter_context(
                mock.patch.object(supervisor, "pending_control_signals", side_effect=pending_control_signals)
            )
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(
                mock.patch.object(supervisor, "supervise_exercise", side_effect=supervision_with_latched_signal)
            )
            receipt = stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt"))
            root = stack.enter_context(mock.patch.object(supervisor, "run_root_snapshot"))
            docker = stack.enter_context(mock.patch.object(supervisor, "docker_residue_counts"))

            self.assertEqual(1, supervisor.main())

        receipt.assert_not_called()
        root.assert_not_called()
        docker.assert_not_called()
        self.assertEqual("TERM", supervisor.SUPERVISOR_INTERRUPTION)

    def test_success_path_collects_one_docker_snapshot_without_gating_on_lifecycle_diagnostic(self) -> None:
        receipt = {
            "mode": "0600",
            "schemaVersion": 4,
            "result": "CLEANED",
            "failureStage": "SIGNAL",
            # The fixed lifecycle enum is projected for diagnosis, not made
            # a substitute for the complete ownership/TERM/residue gate.
            "rehearsalLifecycleObservation": "NOT_REHEARSAL",
            "launcherReadinessObservation": "HEALTH_READY",
            "launcherFailureClass": "NOT_APPLICABLE",
            "secretsRedacted": True,
        }
        snapshot = {"container": 0, "network": 0, "volume": 0}
        root_snapshot = supervisor.RunRootSnapshot(private_absent=True, nonreceipt_residue_count=0)
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            supervise = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "supervise_exercise",
                    return_value=supervisor.RehearsalOutcome(
                        health_precondition=True,
                        parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
                        listener_proof=self._listener_proof(),
                        term_dispatches=1,
                        dispatch_safe=True,
                        child_exit=0,
                        listener_proof_ever_eligible=True,
                    ),
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=receipt))
            stack.enter_context(mock.patch.object(supervisor, "run_root_snapshot", return_value=root_snapshot))
            docker_counts = stack.enter_context(
                mock.patch.object(supervisor, "docker_residue_counts", return_value=snapshot)
            )
            emit = stack.enter_context(mock.patch.object(supervisor, "emit_summary"))

            self.assertEqual(0, supervisor.main())

        self.assertEqual(self.run_dir, supervise.call_args.kwargs["run_dir"])
        self.assertEqual(self.artifact_root, supervise.call_args.kwargs["artifact_root"])
        self.assertEqual(1, docker_counts.call_count)
        self.assertEqual(snapshot, emit.call_args.kwargs["docker_snapshot"])
        self.assertEqual(root_snapshot, emit.call_args.kwargs["root_snapshot"])
        self.assertTrue(emit.call_args.kwargs["listener_proof_ever_eligible"])
        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("COMPLETE", projection["phase"])
        self.assertEqual("SUCCESS_GATE_MET", projection["outcome"])
        self.assertEqual("VALID", projection["receiptState"])
        self.assertEqual("COMPLETE", projection["rootSnapshotState"])
        self.assertEqual("EMITTED", projection["stdoutSummaryState"])

    def test_missing_receipt_is_projected_without_authorizing_success(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=True,
            parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
            listener_proof=self._listener_proof(),
            term_dispatches=1,
            dispatch_safe=True,
            child_exit=0,
            listener_proof_ever_eligible=True,
        )
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(mock.patch.object(supervisor, "supervise_exercise", return_value=outcome))
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=None))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_root_snapshot",
                    return_value=supervisor.RunRootSnapshot(False, 7),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "docker_residue_counts",
                    return_value={"container": 0, "network": 0, "volume": 0},
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "emit_summary"))

            self.assertEqual(1, supervisor.main())

        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("RECEIPT_MISSING_OR_INVALID", projection["outcome"])
        self.assertEqual("MISSING_OR_INVALID", projection["receiptState"])
        self.assertEqual("COMPLETE", projection["rootSnapshotState"])
        self.assertEqual("EMITTED", projection["stdoutSummaryState"])

    def test_child_early_exit_is_projected_as_fixed_enum_only(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=False,
            parent_proof=None,
            listener_proof=None,
            term_dispatches=0,
            dispatch_safe=False,
            child_exit=256,
            listener_proof_ever_eligible=False,
        )
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(mock.patch.object(supervisor, "supervise_exercise", return_value=outcome))
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=None))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_root_snapshot",
                    return_value=supervisor.RunRootSnapshot(False, 7),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "docker_residue_counts",
                    return_value={"container": 1, "network": 1, "volume": 1},
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "emit_summary"))

            self.assertEqual(1, supervisor.main())

        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("CHILD_EXITED_BEFORE_HEALTH", projection["outcome"])
        self.assertEqual(set(supervisor.PROJECTION_FIELDS), set(projection))

    def test_health_ready_receipt_cannot_override_candidate_ineligible_zero_term_outcome(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=False,
            parent_proof=None,
            listener_proof=supervisor.ListenerProof(False, "listener-candidate-absent"),
            term_dispatches=0,
            dispatch_safe=False,
            child_exit=512,
            listener_proof_ever_eligible=False,
        )
        receipt = {
            "mode": "0600",
            "schemaVersion": 4,
            "result": "CLEANED",
            "failureStage": "UNKNOWN",
            "rehearsalLifecycleObservation": "HOLD_TIMEOUT",
            "launcherReadinessObservation": "HEALTH_READY",
            "launcherFailureClass": "NOT_APPLICABLE",
            "secretsRedacted": True,
        }
        output = io.StringIO()
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(mock.patch.object(supervisor, "supervise_exercise", return_value=outcome))
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=receipt))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_root_snapshot",
                    return_value=supervisor.RunRootSnapshot(True, 0),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "docker_residue_counts",
                    return_value={"container": 0, "network": 0, "volume": 0},
                )
            )
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            with contextlib.redirect_stdout(output):
                self.assertEqual(1, supervisor.main())

        kill.assert_not_called()
        summary_lines = output.getvalue().splitlines()
        self.assertEqual(1, len(summary_lines))
        summary = json.loads(summary_lines[0])
        self.assertFalse(summary["controlledHealthPrecondition"])
        self.assertEqual("NOT_ATTEMPTED", summary["parentProof"])
        self.assertEqual("listener-candidate-absent", summary["listenerProof"])
        self.assertFalse(summary["listenerProofEverEligible"])
        self.assertEqual(0, summary["termDispatches"])
        self.assertFalse(summary["dispatchSafe"])
        self.assertEqual("EXIT_2", summary["exerciseExit"])
        self.assertEqual(receipt, summary["receipt"])

        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("COMPLETE", projection["phase"])
        self.assertEqual("CHILD_EXITED_BEFORE_HEALTH", projection["outcome"])
        self.assertNotEqual("SUCCESS_GATE_MET", projection["outcome"])
        self.assertEqual("VALID", projection["receiptState"])
        self.assertEqual("COMPLETE", projection["rootSnapshotState"])
        self.assertEqual("EMITTED", projection["stdoutSummaryState"])

    def test_stdout_emit_failure_keeps_projection_non_authoritative_and_non_emitted(self) -> None:
        outcome = supervisor.RehearsalOutcome(
            health_precondition=True,
            parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
            listener_proof=self._listener_proof(),
            term_dispatches=1,
            dispatch_safe=True,
            child_exit=0,
            listener_proof_ever_eligible=True,
        )
        with self._base_patches(docker_socket_safe=True) as stack:
            stack.enter_context(mock.patch.object(supervisor, "start_exercise", return_value=(42_422, 100)))
            stack.enter_context(mock.patch.object(supervisor, "supervise_exercise", return_value=outcome))
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=None))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_root_snapshot",
                    return_value=supervisor.RunRootSnapshot(False, 7),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "docker_residue_counts",
                    return_value={"container": 0, "network": 0, "volume": 0},
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "emit_summary", side_effect=BrokenPipeError))

            with self.assertRaises(BrokenPipeError):
                supervisor.main()

        projection = supervisor.read_execution_projection(
            self.repository_root,
            self.artifact_root,
            self.run_id,
        )
        self.assertEqual("FAILED", projection["phase"])
        self.assertEqual("UNEXPECTED_FAILURE", projection["outcome"])
        self.assertEqual("MISSING_OR_INVALID", projection["receiptState"])
        self.assertEqual("COMPLETE", projection["rootSnapshotState"])
        self.assertEqual("NOT_EMITTED", projection["stdoutSummaryState"])

    def test_redacted_summary_exposes_listener_eligibility_once_without_process_identifiers(self) -> None:
        receipt = {
            "mode": "0600",
            "schemaVersion": 4,
            "result": "CLEANED",
            "failureStage": "SIGNAL",
            "rehearsalLifecycleObservation": "HOLD_SIGNAL_RECEIVED",
            "launcherReadinessObservation": "HEALTH_READY",
            "launcherFailureClass": "NOT_APPLICABLE",
            "secretsRedacted": True,
        }
        output = io.StringIO()

        with contextlib.redirect_stdout(output):
            supervisor.emit_summary(
                run_id=self.run_id,
                health_precondition=True,
                parent_proof=supervisor.ParentProof(True, "parent-proof", 100),
                listener_proof=self._listener_proof(),
                listener_proof_ever_eligible=True,
                term_dispatches=1,
                dispatch_safe=True,
                child_exit=0,
                receipt=receipt,
                root_snapshot=supervisor.RunRootSnapshot(True, 0),
                docker_snapshot={"container": 0, "network": 0, "volume": 0},
            )

        lines = output.getvalue().splitlines()
        self.assertEqual(1, len(lines))
        summary = json.loads(lines[0])
        self.assertTrue(summary["listenerProofEverEligible"])
        self.assertEqual("uid+java+argv+cwd+ancestor+socket+startTicks", summary["listenerProof"])
        self.assertEqual(receipt, summary["receipt"])
        for prohibited in ("pid", "argv", "cwd", "port", "inode", "socketInode"):
            self.assertNotIn(prohibited, summary)

    def _listener_proof(
        self,
        *,
        pid: int = 50_001,
        start_ticks: int = 50_002,
        socket_inode: int = 50_003,
    ) -> object:
        return supervisor.ListenerProof(
            True,
            "uid+java+argv+cwd+ancestor+socket+startTicks",
            pid,
            start_ticks,
            socket_inode,
        )

    def _dispatch(
        self,
        proof: object,
        *,
        final_listener: object | None = None,
    ) -> tuple[object, object, int, bool]:
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_exercise_parent",
                    return_value=supervisor.ParentProof(True, "parent-proof", 100),
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_owned_loopback_launcher",
                    return_value=proof if final_listener is None else final_listener,
                )
            )
            return supervisor.dispatch_owned_parent_term(
                child_pid=42_421,
                initial_start_ticks=100,
                repo_root=self.repository_root,
                expected_argv=["/usr/bin/bash", "-p", "safe-harness"],
                run_id=self.run_id,
                run_dir=self.run_dir,
                artifact_root=self.artifact_root,
                navigator_port=24_569,
                prior_listener_proof=proof,
            )

    def _supervise(self) -> object:
        return supervisor.supervise_exercise(
            child_pid=42_421,
            initial_start_ticks=99,
            repo_root=self.repository_root,
            expected_argv=["/usr/bin/bash", "-p", "safe-harness"],
            run_id=self.run_id,
            run_dir=self.run_dir,
            artifact_root=self.artifact_root,
            navigator_port=24_569,
            health_timeout_seconds=30,
            post_term_timeout_seconds=30,
        )

    @contextlib.contextmanager
    def _base_patches(self, *, docker_socket_safe: bool):
        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "parse_args", return_value=self.args))
            stack.enter_context(mock.patch.object(supervisor, "repository_root", return_value=self.repository_root))
            stack.enter_context(mock.patch.object(supervisor, "install_supervisor_signal_handlers"))
            stack.enter_context(mock.patch.object(supervisor, "port_is_unused", return_value=True))
            stack.enter_context(
                mock.patch.object(supervisor, "local_docker_socket_is_safe", return_value=docker_socket_safe)
            )
            yield stack


if __name__ == "__main__":
    unittest.main(verbosity=2)
