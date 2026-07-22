#!/usr/bin/env python3
"""Offline regression tests for the BUG-009 forced-SIGNAL supervisor.

Most tests replace process, HTTP, Docker, and signal boundaries. Narrowly
scoped topology tests may open a test-owned loopback listener and inspect only
their test-owned process topology through ``/proc``. One production-like seam
also sources the real harness functions from a temporary library copy, but it
must never invoke Docker, use a runtime profile, or touch a non-test process.
The assertions protect the supervisor's fail-closed preconditions rather than
a disposable runtime.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import itertools
import json
import os
import select
import shlex
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
HARNESS_PATH = (
    REPOSITORY_ROOT
    / "tools"
    / "navigator-upstream"
    / "scripts"
    / "synthetic-upstream-harness.sh"
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

    def test_reservation_absence_requires_a_strict_safe_registry(self) -> None:
        registry = self.artifact_root / supervisor.PORT_RESERVATION_DIRECTORY_NAME
        registry.mkdir(mode=0o700)
        os.chmod(registry, 0o700)
        self.assertTrue(supervisor.current_run_reservation_absent(self.artifact_root, self.run_id))

        reservation = registry / f"{self.run_id}{supervisor.PORT_RESERVATION_SUFFIX}"
        reservation.write_text(
            "\n".join(
                [
                    "INT001_PORT_RESERVATION_SCHEMA=1",
                    f"INT001_RUN_ID={self.run_id}",
                    "INT001_NAVIGATOR_PORT=24101",
                    "INT001_MYSQL_PORT=24102",
                    "INT001_MOCK_LLM_PORT=24103",
                    "INT001_BIZ_PORT=24104",
                    "INT001_BIZ_INGRESS_PROXY_PORT=24105",
                    "INT001_DIRECTORY_FACADE_PORT=24106",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        os.chmod(reservation, 0o600)
        self.assertFalse(supervisor.current_run_reservation_absent(self.artifact_root, self.run_id))

        reservation.unlink()
        (registry / "unknown-entry").write_text("unsafe\n", encoding="utf-8")
        self.assertFalse(supervisor.current_run_reservation_absent(self.artifact_root, self.run_id))

    def test_reservation_registry_uses_only_lf_record_boundaries(self) -> None:
        registry = self.artifact_root / supervisor.PORT_RESERVATION_DIRECTORY_NAME
        registry.mkdir(mode=0o700)
        os.chmod(registry, 0o700)
        reservation_run_id = "int001-supervisor-sibling"
        reservation = registry / f"{reservation_run_id}{supervisor.PORT_RESERVATION_SUFFIX}"
        lines = [
            "INT001_PORT_RESERVATION_SCHEMA=1",
            f"INT001_RUN_ID={reservation_run_id}",
            "INT001_NAVIGATOR_PORT=24201",
            "INT001_MYSQL_PORT=24202",
            "INT001_MOCK_LLM_PORT=24203",
            "INT001_BIZ_PORT=24204",
            "INT001_BIZ_INGRESS_PROXY_PORT=24205",
            "INT001_DIRECTORY_FACADE_PORT=24206",
        ]
        canonical = ("\n".join(lines) + "\n").encode("utf-8")
        reservation.write_bytes(canonical)
        os.chmod(reservation, 0o600)
        original_read = os.read

        def short_read(descriptor: int, amount: int) -> bytes:
            return original_read(descriptor, min(amount, 7))

        with mock.patch.object(supervisor.os, "read", side_effect=short_read):
            self.assertTrue(supervisor.current_run_reservation_absent(self.artifact_root, self.run_id))

        malformed_payloads = {
            "crlf": ("\r\n".join(lines) + "\r\n").encode("utf-8"),
            "bare-cr": ("\r".join(lines) + "\r").encode("utf-8"),
            "unicode-line-separator": ("\u2028".join(lines) + "\u2028").encode("utf-8"),
            "unicode-next-line": ("\u0085".join(lines) + "\u0085").encode("utf-8"),
            "vertical-tab": ("\v".join(lines) + "\v").encode("utf-8"),
            "nul": canonical.replace(b"=1\n", b"=1\x00\n", 1),
            "embedded-lf": canonical.replace(b"=1\n", b"=1\nunexpected\n", 1),
            "trailing-content": canonical + b"INT001_EXTRA=forbidden\n",
            "oversize": canonical + b"X" * supervisor.MAX_PORT_RESERVATION_BYTES,
        }
        for label, payload in malformed_payloads.items():
            with self.subTest(label=label):
                reservation.write_bytes(payload)
                os.chmod(reservation, 0o600)
                self.assertFalse(supervisor.current_run_reservation_absent(self.artifact_root, self.run_id))

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
        self.assertEqual(supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE, proof.proof_stage_diagnostic)
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
            [
                mock.call(
                    socket_inode=self.socket_inode,
                    candidate=supervisor.ListenerCandidate(self.pid, self.start_ticks),
                    exercise_pid=40_001,
                    exercise_start_ticks=40_002,
                ),
                mock.call(
                    socket_inode=self.socket_inode,
                    candidate=supervisor.ListenerCandidate(self.pid, self.start_ticks),
                    exercise_pid=40_001,
                    exercise_start_ticks=40_002,
                ),
            ],
            patches["holder_exclusive"].call_args_list,
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
                patches["holder_exclusive"].assert_not_called()
                patches["identity"].assert_not_called()

    def test_listener_proof_requires_candidate_alone_to_hold_listener_inode(self) -> None:
        cases: tuple[tuple[str, dict[str, object]], ...] = (
            ("candidate-fd-missing-initial", {"candidate_holds": [False]}),
            ("run-owned-holder-proof-failed-initial", {"holder_exclusive": [False]}),
            ("candidate-fd-lost-on-reproof", {"candidate_holds": [True, False]}),
            (
                "run-owned-holder-proof-failed-on-reproof",
                {"holder_exclusive": [True, False]},
            ),
        )
        for label, overrides in cases:
            with self.subTest(label=label):
                proof = self._prove(**overrides)
                self.assertFalse(proof.ok)
                self.assertEqual("socket-owner", proof.reason)
                expected_stage = (
                    supervisor.LISTENER_PROOF_STAGE_SOCKET_FOUND
                    if label.endswith("initial")
                    else supervisor.LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP
                )
                self.assertEqual(expected_stage, proof.proof_stage_diagnostic)

    def test_listener_proof_stage_records_exact_identity_before_socket_exists(self) -> None:
        proof = self._prove(socket_probes=[(None, "socket-listener-absent")])

        self.assertFalse(proof.ok)
        self.assertEqual("socket-listener-absent", proof.reason)
        self.assertEqual(supervisor.LISTENER_IDENTITY_EXACT, proof.identity_diagnostic)
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
            proof.proof_stage_diagnostic,
        )

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
                self.assertEqual(
                    supervisor.LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
                    proof.proof_stage_diagnostic,
                )

    def _prove(
        self,
        *,
        candidate_result: tuple[object | None, str] | tuple[object | None, str, str] | None = None,
        socket_probes: list[tuple[int | None, str]] | None = None,
        candidate_holds: list[bool] | None = None,
        holder_exclusive: list[bool] | None = None,
        identity_reproofs: list[object | None] | None = None,
        java: Path | None | object = ...,
        expected_argv: list[str] | None | object = ...,
        return_patches: bool = False,
    ) -> object:
        actual_java = self.java if java is ... else java
        actual_expected_argv = self.expected_argv if expected_argv is ... else expected_argv
        candidate = supervisor.ListenerCandidate(self.pid, self.start_ticks)
        if candidate_result is None:
            actual_candidate_result = (candidate, "listener-candidate", "EXACT_CANDIDATE_FOUND")
        elif len(candidate_result) == 2:
            actual_candidate_result = (*candidate_result, "NOT_OBSERVED")
        else:
            actual_candidate_result = candidate_result
        actual_socket_probes = socket_probes or [
            (self.socket_inode, "socket-listener"),
            (self.socket_inode, "socket-listener"),
        ]
        actual_candidate_holds = candidate_holds or [True, True]
        actual_holder_exclusive = holder_exclusive or [True, True]
        match = supervisor.ListenerCandidateIdentityProbe(
            supervisor.IDENTITY_MATCH,
            candidate,
            "EXACT_CANDIDATE_FOUND",
        )
        actual_identity_reproofs = identity_reproofs or [match, match]

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "safe_run_directory", return_value=True))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "expected_launcher_argv",
                    return_value=actual_expected_argv,
                )
            )
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
            holder_exclusive_patch = stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_owned_socket_holder_is_exclusive",
                    side_effect=actual_holder_exclusive,
                )
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
                    "holder_exclusive": holder_exclusive_patch,
                    "identity": identity,
                }
            return proof


class RunOwnedSocketHolderProofTest(unittest.TestCase):
    def setUp(self) -> None:
        self.exercise_pid = 40_001
        self.exercise_start_ticks = 101
        self.candidate = supervisor.ListenerCandidate(40_003, 303)
        self.socket_inode = 789_012
        self.domain = (
            supervisor.DescendantDomainIdentity(self.exercise_pid, 39_999, self.exercise_start_ticks),
            supervisor.DescendantDomainIdentity(40_002, self.exercise_pid, 202),
            supervisor.DescendantDomainIdentity(self.candidate.pid, 40_002, self.candidate.start_ticks),
        )

    def test_accepts_only_candidate_as_in_domain_holder_and_ignores_unreadable_outside_pid(self) -> None:
        self.assertTrue(
            self._prove(
                readable_pids=tuple(identity.pid for identity in self.domain) + (50_001,),
                holder_status={50_001: None},
            )
        )

    def test_rejects_in_domain_fd_uncertainty_second_holder_and_missing_candidate_holder(self) -> None:
        cases = (
            ("in-domain-fd-unavailable", {40_002: None}),
            ("second-in-domain-holder", {40_002: True}),
            ("candidate-does-not-hold", {self.candidate.pid: False}),
        )
        for label, overrides in cases:
            with self.subTest(label=label):
                self.assertFalse(self._prove(holder_status=overrides))

    def test_rejects_candidate_outside_domain_or_start_ticks_mismatch(self) -> None:
        missing_candidate_domain = self.domain[:-1]
        changed_ticks_domain = self.domain[:-1] + (
            supervisor.DescendantDomainIdentity(self.candidate.pid, 40_002, self.candidate.start_ticks + 1),
        )
        for label, domain in (
            ("candidate-outside-domain", missing_candidate_domain),
            ("candidate-start-ticks-mismatch", changed_ticks_domain),
        ):
            with self.subTest(label=label):
                self.assertFalse(self._prove(domains=[domain, domain]))

    def test_rejects_readable_outside_holder_and_proc_root_enumeration_failure(self) -> None:
        self.assertFalse(
            self._prove(
                readable_pids=tuple(identity.pid for identity in self.domain) + (50_001,),
                holder_status={50_001: True},
            )
        )
        self.assertFalse(self._prove(readable_pids=None))

    def test_rejects_domain_or_start_ticks_drift_between_holder_snapshots(self) -> None:
        changed_domain = self.domain[:-1] + (
            supervisor.DescendantDomainIdentity(self.candidate.pid, 40_002, self.candidate.start_ticks + 1),
        )
        self.assertFalse(self._prove(domains=[self.domain, changed_domain]))

    def test_process_holder_status_treats_fd_directory_and_readlink_errors_as_unknown(self) -> None:
        class FakeFdRoot:
            def __init__(self, entries: tuple[object, ...] = (), error: BaseException | None = None) -> None:
                self.entries = entries
                self.error = error

            def iterdir(self) -> tuple[object, ...]:
                if self.error is not None:
                    raise self.error
                return self.entries

        with mock.patch.object(supervisor, "Path", return_value=FakeFdRoot(error=PermissionError("fd-dir"))):
            self.assertIsNone(supervisor.process_socket_holder_status(self.candidate.pid, self.socket_inode))

        fd_entry = object()
        with (
            mock.patch.object(supervisor, "Path", return_value=FakeFdRoot((fd_entry,))),
            mock.patch.object(supervisor.os, "readlink", side_effect=PermissionError("fd-link")),
        ):
            self.assertIsNone(supervisor.process_socket_holder_status(self.candidate.pid, self.socket_inode))

    def _prove(
        self,
        *,
        domains: list[tuple[object, ...]] | None = None,
        holder_status: dict[int, bool | None] | None = None,
        readable_pids: tuple[int, ...] | None | object = ...,
    ) -> bool:
        actual_domains = domains or [self.domain, self.domain]
        statuses = {identity.pid: False for identity in self.domain}
        statuses[self.candidate.pid] = True
        statuses.update(holder_status or {})
        actual_readable_pids = (
            tuple(identity.pid for identity in self.domain)
            if readable_pids is ...
            else readable_pids
        )

        def process_status(pid: int, socket_inode: int) -> bool | None:
            self.assertEqual(self.socket_inode, socket_inode)
            return statuses.get(pid)

        with (
            mock.patch.object(
                supervisor,
                "stable_exercise_descendant_domain",
                side_effect=[(domain, supervisor.IDENTITY_MATCH) for domain in actual_domains],
            ),
            mock.patch.object(supervisor, "process_socket_holder_status", side_effect=process_status),
            mock.patch.object(
                supervisor,
                "readable_current_uid_process_ids",
                return_value=actual_readable_pids,
            ),
        ):
            return supervisor.run_owned_socket_holder_is_exclusive(
                socket_inode=self.socket_inode,
                candidate=self.candidate,
                exercise_pid=self.exercise_pid,
                exercise_start_ticks=self.exercise_start_ticks,
            )


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
                "final-exe-unavailable",
                {"final_exe_status": supervisor.IDENTITY_PROC_UNAVAILABLE},
                supervisor.IDENTITY_PROC_UNAVAILABLE,
            ),
            (
                "final-exe-malformed",
                {"final_exe_status": supervisor.IDENTITY_PROC_MALFORMED},
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
        self.assertEqual([mock.call(self.pid, "exe")], patches["links"].call_args_list)
        patches["lineage"].assert_not_called()

    def test_non_trusted_executable_is_not_reported_as_an_argv_mismatch(self) -> None:
        probe, patches = self._identity(
            java=Path("/unrelated/process"),
            argv=["/unrelated/process", "--not-a-launcher"],
            return_patches=True,
        )

        self.assertEqual(supervisor.IDENTITY_MISMATCH, probe.status)
        self.assertEqual("NO_TRUSTED_JAVA_CANDIDATE", probe.identity_diagnostic)
        patches["command"].assert_not_called()
        patches["lineage"].assert_not_called()

    def test_identity_probe_reports_only_fixed_redacted_progress(self) -> None:
        cases: tuple[tuple[str, dict[str, object], str], ...] = (
            ("argv", {"argv": [str(self.java), "-jar", "/wrong/launcher.jar"]}, "NO_EXACT_ARGV_MATCH"),
            ("cwd", {"cwd": Path("/wrong/run")}, "ARGV_MATCH_CWD_MISMATCH"),
            ("untrusted-java", {"java": Path("/wrong/java")}, "NO_TRUSTED_JAVA_CANDIDATE"),
            ("exe-drift", {"final_java": Path("/wrong/java")}, "ARGV_CWD_MATCH_EXE_MISMATCH"),
            (
                "lineage",
                {"lineage_status": supervisor.IDENTITY_MISMATCH},
                "ARGV_CWD_EXE_MATCH_LINEAGE_MISMATCH",
            ),
            ("stability", {"final_start_ticks": self.start_ticks + 1}, "IDENTITY_STABILITY_MISMATCH"),
            ("unavailable", {"argv_status": supervisor.IDENTITY_PROC_UNAVAILABLE}, "PROC_UNAVAILABLE"),
            ("malformed", {"exe_status": supervisor.IDENTITY_PROC_MALFORMED}, "PROC_MALFORMED"),
            ("exact", {}, "EXACT_CANDIDATE_FOUND"),
        )
        for label, overrides, expected in cases:
            with self.subTest(label=label):
                probe = self._identity(**overrides)
                self.assertEqual(expected, probe.identity_diagnostic)
                self.assertIn(probe.identity_diagnostic, supervisor.LISTENER_IDENTITY_DIAGNOSTICS)

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
        final_java: Path | None = None,
        exe_status: str = supervisor.IDENTITY_MATCH,
        final_exe_status: str | None = None,
        argv: list[str] | None = None,
        lineage_status: str = supervisor.IDENTITY_MATCH,
        return_patches: bool = False,
    ) -> object:
        observed_initial_ticks = self.start_ticks if initial_start_ticks is None else initial_start_ticks
        observed_final_ticks = self.start_ticks if final_start_ticks is None else final_start_ticks
        observed_cwd = self.run_dir if cwd is None else cwd
        observed_java = self.java if java is None else java
        observed_final_java = observed_java if final_java is None else final_java
        observed_final_exe_status = exe_status if final_exe_status is None else final_exe_status
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

            exe_reads = 0

            def link_probe(_pid: int, name: str) -> tuple[Path | None, str]:
                nonlocal exe_reads
                if name == "cwd":
                    return (observed_cwd if cwd_status == supervisor.IDENTITY_MATCH else None, cwd_status)
                if name == "exe":
                    exe_reads += 1
                    executable = observed_java if exe_reads == 1 else observed_final_java
                    observed_status = exe_status if exe_reads == 1 else observed_final_exe_status
                    return (
                        executable if observed_status == supervisor.IDENTITY_MATCH else None,
                        observed_status,
                    )
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


class ProcTaskChildrenTest(unittest.TestCase):
    def test_reads_every_task_and_rejects_task_set_churn(self) -> None:
        with mock.patch.object(
            supervisor,
            "proc_task_ids",
            side_effect=[
                ((50_001, 50_002), supervisor.IDENTITY_MATCH),
                ((50_001, 50_002), supervisor.IDENTITY_MATCH),
            ],
        ), mock.patch.object(
            supervisor,
            "proc_task_child_pids",
            side_effect=[
                ((42_001,), supervisor.IDENTITY_MATCH),
                ((42_002,), supervisor.IDENTITY_MATCH),
            ],
        ) as child_reads:
            children, status = supervisor.proc_task_children(41_999)
        self.assertEqual((42_001, 42_002), children)
        self.assertEqual(supervisor.IDENTITY_MATCH, status)
        self.assertEqual(
            [mock.call(41_999, 50_001), mock.call(41_999, 50_002)],
            child_reads.call_args_list,
        )

        with mock.patch.object(
            supervisor,
            "proc_task_ids",
            side_effect=[
                ((50_001,), supervisor.IDENTITY_MATCH),
                ((50_001, 50_002), supervisor.IDENTITY_MATCH),
            ],
        ), mock.patch.object(
            supervisor,
            "proc_task_child_pids",
            return_value=((), supervisor.IDENTITY_MATCH),
        ):
            children, status = supervisor.proc_task_children(41_999)
        self.assertIsNone(children)
        self.assertEqual(supervisor.IDENTITY_MISMATCH, status)

    def test_task_child_failure_and_combined_child_limit_fail_closed(self) -> None:
        for failure_status in (
            supervisor.IDENTITY_MISMATCH,
            supervisor.IDENTITY_PROC_UNAVAILABLE,
            supervisor.IDENTITY_PROC_MALFORMED,
        ):
            with self.subTest(status=failure_status), mock.patch.object(
                supervisor,
                "proc_task_ids",
                return_value=((50_001,), supervisor.IDENTITY_MATCH),
            ), mock.patch.object(
                supervisor,
                "proc_task_child_pids",
                return_value=(None, failure_status),
            ):
                children, status = supervisor.proc_task_children(41_999)
                self.assertIsNone(children)
                self.assertEqual(failure_status, status)

        first = tuple(range(60_000, 60_000 + supervisor.MAX_DESCENDANT_DOMAIN_PROCESSES))
        with mock.patch.object(
            supervisor,
            "proc_task_ids",
            side_effect=[
                ((50_001, 50_002), supervisor.IDENTITY_MATCH),
                ((50_001, 50_002), supervisor.IDENTITY_MATCH),
            ],
        ), mock.patch.object(
            supervisor,
            "proc_task_child_pids",
            side_effect=[
                (first, supervisor.IDENTITY_MATCH),
                ((70_000,), supervisor.IDENTITY_MATCH),
            ],
        ):
            children, status = supervisor.proc_task_children(41_999)
        self.assertIsNone(children)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

    def test_task_ids_require_current_user_ownership_and_enforce_bound(self) -> None:
        class FakeTaskEntry:
            def __init__(self, task_id: int, uid: int) -> None:
                self.name = str(task_id)
                self.uid = uid

            def stat(self) -> SimpleNamespace:
                return SimpleNamespace(st_uid=self.uid)

        class FakeTaskRoot:
            def __init__(self, entries: tuple[FakeTaskEntry, ...]) -> None:
                self.entries = entries

            def iterdir(self) -> tuple[FakeTaskEntry, ...]:
                return self.entries

        with mock.patch.object(
            supervisor,
            "Path",
            return_value=FakeTaskRoot((FakeTaskEntry(50_001, os.getuid() + 1),)),
        ):
            task_ids, status = supervisor.proc_task_ids(41_999)
        self.assertIsNone(task_ids)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

        boundary_entries = tuple(
            FakeTaskEntry(50_000 + index, os.getuid())
            for index in range(supervisor.MAX_DESCENDANT_DOMAIN_TASKS)
        )
        with mock.patch(supervisor.__name__ + ".Path", return_value=FakeTaskRoot(boundary_entries)):
            task_ids, status = supervisor.proc_task_ids(41_999)
        self.assertEqual(supervisor.MAX_DESCENDANT_DOMAIN_TASKS, len(task_ids or ()))
        self.assertEqual(supervisor.IDENTITY_MATCH, status)

        entries = (
            *boundary_entries,
            FakeTaskEntry(90_000, os.getuid()),
        )
        with mock.patch.object(supervisor, "Path", return_value=FakeTaskRoot(entries)):
            task_ids, status = supervisor.proc_task_ids(41_999)
        self.assertIsNone(task_ids)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

    def test_task_child_parser_rejects_malformed_and_oversized_values(self) -> None:
        path = mock.Mock()
        for payload, expected_status in (
            (b"not-a-pid\n", supervisor.IDENTITY_PROC_MALFORMED),
            (b"1\n", supervisor.IDENTITY_PROC_MALFORMED),
            (b"\xff\n", supervisor.IDENTITY_PROC_MALFORMED),
            (
                " ".join(
                    str(60_000 + index)
                    for index in range(supervisor.MAX_DESCENDANT_DOMAIN_PROCESSES + 1)
                ).encode("ascii"),
                supervisor.IDENTITY_PROC_MALFORMED,
            ),
        ):
            with self.subTest(payload=payload[:16]):
                path.read_bytes.return_value = payload
                with mock.patch.object(supervisor, "Path", return_value=path):
                    children, status = supervisor.proc_task_child_pids(41_999, 50_001)
                self.assertIsNone(children)
                self.assertEqual(expected_status, status)


class ExerciseDescendantDomainTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root_pid = 41_999
        self.root_ticks = 99
        self.snapshots = {
            self.root_pid: ("S", 40_000, self.root_ticks),
            42_001: ("S", self.root_pid, 101),
            42_002: ("S", 42_001, 102),
        }

    def test_snapshot_collects_complete_stable_transitive_domain(self) -> None:
        children = {
            self.root_pid: (42_001,),
            42_001: (42_002,),
            42_002: (),
        }
        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=self._identity), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (children[pid], supervisor.IDENTITY_MATCH),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )

        self.assertEqual(supervisor.IDENTITY_MATCH, status)
        self.assertEqual(
            (
                supervisor.DescendantDomainIdentity(self.root_pid, 40_000, self.root_ticks),
                supervisor.DescendantDomainIdentity(42_001, self.root_pid, 101),
                supervisor.DescendantDomainIdentity(42_002, 42_001, 102),
            ),
            domain,
        )

    def test_in_domain_proc_or_children_failure_is_never_ignored(self) -> None:
        for failure_status in (
            supervisor.IDENTITY_PROC_UNAVAILABLE,
            supervisor.IDENTITY_PROC_MALFORMED,
        ):
            with self.subTest(source="children", status=failure_status), mock.patch.object(
                supervisor,
                "proc_identity_snapshot",
                side_effect=self._identity,
            ), mock.patch.object(
                supervisor,
                "proc_task_children",
                return_value=(None, failure_status),
            ):
                domain, status = supervisor.exercise_descendant_domain_snapshot(
                    self.root_pid,
                    self.root_ticks,
                )
                self.assertIsNone(domain)
                self.assertEqual(failure_status, status)

        def child_unavailable(pid: int) -> tuple[object | None, str]:
            if pid == 42_001:
                return None, supervisor.IDENTITY_PROC_UNAVAILABLE
            return self._identity(pid)

        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=child_unavailable), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (
                ((42_001,) if pid == self.root_pid else ()),
                supervisor.IDENTITY_MATCH,
            ),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_PROC_UNAVAILABLE, status)

    def test_conflicting_parent_edges_and_root_reuse_fail_closed(self) -> None:
        conflicting_children = {
            self.root_pid: (42_001, 42_002),
            42_001: (42_002,),
            42_002: (),
        }
        def conflicting_identity(pid: int) -> tuple[tuple[str, int, int] | None, str]:
            if pid == 42_002:
                return ("S", self.root_pid, 102), supervisor.IDENTITY_MATCH
            return self._identity(pid)

        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=conflicting_identity), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (conflicting_children[pid], supervisor.IDENTITY_MATCH),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

        with mock.patch.object(
            supervisor,
            "proc_identity_snapshot",
            return_value=(("S", 40_000, self.root_ticks + 1), supervisor.IDENTITY_MATCH),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_MISMATCH, status)

    def test_self_cycle_and_domain_process_limit_fail_closed(self) -> None:
        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=self._identity), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (
                ((self.root_pid,) if pid == self.root_pid else ()),
                supervisor.IDENTITY_MATCH,
            ),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

        child_pids = tuple(
            60_000 + index
            for index in range(supervisor.MAX_DESCENDANT_DOMAIN_PROCESSES - 1)
        )
        snapshots = {
            self.root_pid: ("S", 40_000, self.root_ticks),
            **{
                pid: ("S", self.root_pid, 1_000 + index)
                for index, pid in enumerate(child_pids)
            },
        }

        def identity(pid: int) -> tuple[tuple[str, int, int] | None, str]:
            return snapshots[pid], supervisor.IDENTITY_MATCH

        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=identity), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (
                (child_pids if pid == self.root_pid else ()),
                supervisor.IDENTITY_MATCH,
            ),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertEqual(supervisor.MAX_DESCENDANT_DOMAIN_PROCESSES, len(domain or ()))
        self.assertEqual(supervisor.IDENTITY_MATCH, status)

        over_limit_pid = 90_000
        snapshots[over_limit_pid] = ("S", self.root_pid, 99_999)
        with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=identity), mock.patch.object(
            supervisor,
            "proc_task_children",
            side_effect=lambda pid: (
                ((*child_pids, over_limit_pid) if pid == self.root_pid else ()),
                supervisor.IDENTITY_MATCH,
            ),
        ):
            domain, status = supervisor.exercise_descendant_domain_snapshot(
                self.root_pid,
                self.root_ticks,
            )
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

    def test_domain_depth_accepts_boundary_and_rejects_one_over(self) -> None:
        def run_chain(descendant_count: int) -> tuple[object | None, str]:
            pids = [self.root_pid, *(70_000 + index for index in range(descendant_count))]
            snapshots = {
                pid: (
                    "S",
                    (40_000 if index == 0 else pids[index - 1]),
                    (self.root_ticks if index == 0 else 2_000 + index),
                )
                for index, pid in enumerate(pids)
            }

            def identity(pid: int) -> tuple[tuple[str, int, int] | None, str]:
                return snapshots[pid], supervisor.IDENTITY_MATCH

            children = {
                pid: ((pids[index + 1],) if index + 1 < len(pids) else ())
                for index, pid in enumerate(pids)
            }
            with mock.patch.object(supervisor, "proc_identity_snapshot", side_effect=identity), mock.patch.object(
                supervisor,
                "proc_task_children",
                side_effect=lambda pid: (children[pid], supervisor.IDENTITY_MATCH),
            ):
                return supervisor.exercise_descendant_domain_snapshot(
                    self.root_pid,
                    self.root_ticks,
                )

        domain, status = run_chain(supervisor.MAX_DESCENDANT_DOMAIN_DEPTH)
        self.assertEqual(supervisor.MAX_DESCENDANT_DOMAIN_DEPTH + 1, len(domain or ()))
        self.assertEqual(supervisor.IDENTITY_MATCH, status)

        domain, status = run_chain(supervisor.MAX_DESCENDANT_DOMAIN_DEPTH + 1)
        self.assertIsNone(domain)
        self.assertEqual(supervisor.IDENTITY_PROC_MALFORMED, status)

    def test_stable_domain_retries_only_mismatch_and_still_requires_two_equivalent_snapshots(self) -> None:
        first = (
            supervisor.DescendantDomainIdentity(self.root_pid, 40_000, self.root_ticks),
        )
        changed = (
            *first,
            supervisor.DescendantDomainIdentity(42_001, self.root_pid, 101),
        )
        with mock.patch.object(
            supervisor,
            "exercise_descendant_domain_snapshot",
            side_effect=[
                (None, supervisor.IDENTITY_MISMATCH),
                (first, supervisor.IDENTITY_MATCH),
                (changed, supervisor.IDENTITY_MATCH),
                (first, supervisor.IDENTITY_MATCH),
                (first, supervisor.IDENTITY_MATCH),
            ],
        ) as snapshot, mock.patch.object(supervisor.time, "sleep") as retry_sleep:
            domain, status = supervisor.stable_exercise_descendant_domain(
                self.root_pid,
                self.root_ticks,
            )
        self.assertEqual(first, domain)
        self.assertEqual(supervisor.IDENTITY_MATCH, status)
        self.assertEqual(5, snapshot.call_count)
        self.assertEqual(2, retry_sleep.call_count)
        retry_sleep.assert_has_calls(
            [
                mock.call(supervisor.STABLE_DESCENDANT_DOMAIN_RETRY_SECONDS),
                mock.call(supervisor.STABLE_DESCENDANT_DOMAIN_RETRY_SECONDS),
            ]
        )

        drift_variants = (
            (
                supervisor.DescendantDomainIdentity(self.root_pid, 40_001, self.root_ticks),
            ),
            (
                supervisor.DescendantDomainIdentity(self.root_pid, 40_000, self.root_ticks + 1),
            ),
        )
        for changed_identity in drift_variants:
            with self.subTest(changed=changed_identity), mock.patch.object(
                supervisor,
                "exercise_descendant_domain_snapshot",
                side_effect=(
                    [
                        (first, supervisor.IDENTITY_MATCH),
                        (changed_identity, supervisor.IDENTITY_MATCH),
                    ]
                    * supervisor.MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS
                ),
            ) as snapshot, mock.patch.object(supervisor.time, "sleep") as retry_sleep:
                domain, status = supervisor.stable_exercise_descendant_domain(
                    self.root_pid,
                    self.root_ticks,
                )
                self.assertIsNone(domain)
                self.assertEqual(supervisor.IDENTITY_MISMATCH, status)
                self.assertEqual(
                    supervisor.MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS * 2,
                    snapshot.call_count,
                )
                self.assertEqual(
                    supervisor.MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS - 1,
                    retry_sleep.call_count,
                )

        for failure_status in (
            supervisor.IDENTITY_PROC_UNAVAILABLE,
            supervisor.IDENTITY_PROC_MALFORMED,
        ):
            with self.subTest(first_status=failure_status), mock.patch.object(
                supervisor,
                "exercise_descendant_domain_snapshot",
                return_value=(None, failure_status),
            ) as snapshot, mock.patch.object(supervisor.time, "sleep") as retry_sleep:
                domain, status = supervisor.stable_exercise_descendant_domain(
                    self.root_pid,
                    self.root_ticks,
                )
                self.assertIsNone(domain)
                self.assertEqual(failure_status, status)
                self.assertEqual(1, snapshot.call_count)
                retry_sleep.assert_not_called()

            with self.subTest(second_status=failure_status), mock.patch.object(
                supervisor,
                "exercise_descendant_domain_snapshot",
                side_effect=[
                    (first, supervisor.IDENTITY_MATCH),
                    (None, failure_status),
                ],
            ) as snapshot, mock.patch.object(supervisor.time, "sleep") as retry_sleep:
                domain, status = supervisor.stable_exercise_descendant_domain(
                    self.root_pid,
                    self.root_ticks,
                )
                self.assertIsNone(domain)
                self.assertEqual(failure_status, status)
                self.assertEqual(2, snapshot.call_count)
                retry_sleep.assert_not_called()

    def _identity(self, pid: int) -> tuple[tuple[str, int, int] | None, str]:
        return self.snapshots[pid], supervisor.IDENTITY_MATCH


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
                candidate, reason, diagnostic = self._find(pids=pids, identities=identities)
                self.assertEqual(expected_reason, reason)
                if label == "one":
                    self.assertEqual(exact_candidates[42_001], candidate)
                    self.assertEqual("EXACT_CANDIDATE_FOUND", diagnostic)
                elif label == "multiple":
                    self.assertEqual("EXACT_CANDIDATE_AMBIGUOUS", diagnostic)
                else:
                    self.assertIsNone(candidate)

    def test_discovery_never_probes_an_unrelated_process(self) -> None:
        exact = supervisor.ListenerCandidate(42_001, 101)
        probes: list[int] = []

        def exact_identity(*, pid: int, **_kwargs: object) -> object:
            probes.append(pid)
            if pid != 42_001:
                raise PermissionError("unrelated process must not be inspected")
            return supervisor.ListenerCandidateIdentityProbe(
                supervisor.IDENTITY_MATCH,
                exact,
                supervisor.LISTENER_IDENTITY_EXACT,
            )

        domain = (
            supervisor.DescendantDomainIdentity(42_001, 41_999, 101),
        )
        with mock.patch.object(
            supervisor,
            "stable_exercise_descendant_domain",
            return_value=(domain, supervisor.IDENTITY_MATCH),
        ), mock.patch.object(
            supervisor,
            "exact_launcher_candidate_identity",
            side_effect=exact_identity,
        ):
            candidate, reason, diagnostic = supervisor.find_exact_launcher_candidate(
                expected_argv=self.expected_argv,
                java=self.java,
                run_dir=self.run_dir,
                exercise_pid=41_999,
                exercise_start_ticks=99,
            )

        self.assertEqual([42_001], probes)
        self.assertEqual(exact, candidate)
        self.assertEqual("listener-candidate", reason)
        self.assertEqual("EXACT_CANDIDATE_FOUND", diagnostic)

    def test_domain_failure_mapping_short_circuits_candidate_identity(self) -> None:
        cases = (
            (
                supervisor.IDENTITY_PROC_UNAVAILABLE,
                "listener-candidate-proc-unavailable",
                "PROC_UNAVAILABLE",
            ),
            (
                supervisor.IDENTITY_PROC_MALFORMED,
                "listener-candidate-proc-malformed",
                "PROC_MALFORMED",
            ),
            (
                supervisor.IDENTITY_MISMATCH,
                "listener-candidate-absent",
                "IDENTITY_STABILITY_MISMATCH",
            ),
        )
        for domain_status, expected_reason, expected_diagnostic in cases:
            with self.subTest(status=domain_status), mock.patch.object(
                supervisor,
                "stable_exercise_descendant_domain",
                return_value=(None, domain_status),
            ), mock.patch.object(
                supervisor,
                "exact_launcher_candidate_identity",
            ) as identity:
                candidate, reason, diagnostic = supervisor.find_exact_launcher_candidate(
                    expected_argv=self.expected_argv,
                    java=self.java,
                    run_dir=self.run_dir,
                    exercise_pid=41_999,
                    exercise_start_ticks=99,
                )
            identity.assert_not_called()
            self.assertIsNone(candidate)
            self.assertEqual(expected_reason, reason)
            self.assertEqual(expected_diagnostic, diagnostic)

    def test_discovery_fails_closed_for_unavailable_or_malformed_in_domain_identity(self) -> None:
        with self.subTest("domain-unavailable"):
            candidate, reason, diagnostic = self._find(pids=[], root_error=OSError("proc unavailable"))
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-unavailable", reason)
            self.assertEqual("PROC_UNAVAILABLE", diagnostic)

        with self.subTest("in-domain-entry-unavailable"):
            candidate, reason, diagnostic = self._find(
                pids=[42_001],
                stat_errors={42_001: PermissionError("entry unavailable")},
            )
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-unavailable", reason)
            self.assertEqual("PROC_UNAVAILABLE", diagnostic)

        for status, expected_reason in (
            (supervisor.IDENTITY_PROC_UNAVAILABLE, "listener-candidate-proc-unavailable"),
            (supervisor.IDENTITY_PROC_MALFORMED, "listener-candidate-proc-malformed"),
        ):
            with self.subTest(identity_status=status):
                candidate, reason, diagnostic = self._find(
                    pids=[42_001],
                    identities={42_001: supervisor.ListenerCandidateIdentityProbe(status)},
                )
                self.assertIsNone(candidate)
                self.assertEqual(expected_reason, reason)
                self.assertEqual(status, diagnostic)

    def test_unstable_domain_rejects_but_readable_mismatch_does_not_block_exact_candidate(self) -> None:
        with self.subTest("disappeared-domain-entry-is-unstable"):
            candidate, reason, diagnostic = self._find(
                pids=[42_001],
                stat_errors={42_001: FileNotFoundError("entry disappeared")},
            )
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-absent", reason)
            self.assertEqual("IDENTITY_STABILITY_MISMATCH", diagnostic)

        exact = supervisor.ListenerCandidate(42_002, 102)
        candidate, reason, diagnostic = self._find(
            pids=[42_001, 42_002],
            identities={
                42_001: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH),
                42_002: supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MATCH, exact),
            },
        )
        self.assertEqual("listener-candidate", reason)
        self.assertEqual(exact, candidate)
        self.assertEqual("EXACT_CANDIDATE_FOUND", diagnostic)

    def test_discovery_without_a_trusted_java_candidate_is_not_an_argv_mismatch(self) -> None:
        candidate, reason, diagnostic = self._find(
            pids=[42_001, 42_002],
            identities={
                42_001: supervisor.ListenerCandidateIdentityProbe(
                    supervisor.IDENTITY_MISMATCH,
                    identity_diagnostic=supervisor.LISTENER_IDENTITY_NO_TRUSTED_JAVA,
                ),
                42_002: supervisor.ListenerCandidateIdentityProbe(
                    supervisor.IDENTITY_MISMATCH,
                    identity_diagnostic=supervisor.LISTENER_IDENTITY_NO_TRUSTED_JAVA,
                ),
            },
        )

        self.assertIsNone(candidate)
        self.assertEqual("listener-candidate-absent", reason)
        self.assertEqual("NO_TRUSTED_JAVA_CANDIDATE", diagnostic)

    def test_discovery_retains_only_furthest_progress_and_proc_failure_precedence(self) -> None:
        progress = {
            42_001: supervisor.ListenerCandidateIdentityProbe(
                supervisor.IDENTITY_MISMATCH,
                identity_diagnostic="NO_EXACT_ARGV_MATCH",
            ),
            42_002: supervisor.ListenerCandidateIdentityProbe(
                supervisor.IDENTITY_MISMATCH,
                identity_diagnostic="ARGV_CWD_MATCH_EXE_MISMATCH",
            ),
        }
        for pids in ([42_001, 42_002], [42_002, 42_001]):
            with self.subTest(order=pids):
                candidate, reason, diagnostic = self._find(pids=pids, identities=progress)
                self.assertIsNone(candidate)
                self.assertEqual("listener-candidate-absent", reason)
                self.assertEqual("ARGV_CWD_MATCH_EXE_MISMATCH", diagnostic)

        progress[42_003] = supervisor.ListenerCandidateIdentityProbe(
            supervisor.IDENTITY_PROC_UNAVAILABLE,
            identity_diagnostic="PROC_UNAVAILABLE",
        )
        progress[42_004] = supervisor.ListenerCandidateIdentityProbe(
            supervisor.IDENTITY_PROC_MALFORMED,
            identity_diagnostic="PROC_MALFORMED",
        )
        for pids in itertools.permutations([42_001, 42_002, 42_003, 42_004]):
            candidate, reason, diagnostic = self._find(pids=list(pids), identities=progress)
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-malformed", reason)
            self.assertEqual("PROC_MALFORMED", diagnostic)

        exact = supervisor.ListenerCandidate(42_005, 105)
        exact_and_unavailable = {
            42_005: supervisor.ListenerCandidateIdentityProbe(
                supervisor.IDENTITY_MATCH,
                exact,
                "EXACT_CANDIDATE_FOUND",
            ),
            42_003: progress[42_003],
        }
        for pids in ([42_005, 42_003], [42_003, 42_005]):
            candidate, reason, diagnostic = self._find(
                pids=pids,
                identities=exact_and_unavailable,
            )
            self.assertIsNone(candidate)
            self.assertEqual("listener-candidate-proc-unavailable", reason)
            self.assertEqual("PROC_UNAVAILABLE", diagnostic)

    def _find(
        self,
        *,
        pids: list[int],
        identities: dict[int, object] | None = None,
        root_error: OSError | None = None,
        stat_errors: dict[int, BaseException] | None = None,
    ) -> tuple[object | None, str, str]:
        identities = identities or {}
        stat_errors = stat_errors or {}

        domain_status = supervisor.IDENTITY_MATCH
        if root_error is not None:
            domain_status = supervisor.IDENTITY_PROC_UNAVAILABLE
        elif stat_errors:
            domain_status = (
                supervisor.IDENTITY_MISMATCH
                if all(isinstance(error, FileNotFoundError) for error in stat_errors.values())
                else supervisor.IDENTITY_PROC_UNAVAILABLE
            )
        domain = tuple(
            supervisor.DescendantDomainIdentity(pid, 41_999, 100 + index)
            for index, pid in enumerate(pids)
        )

        def exact_identity(*, pid: int, **_kwargs: object) -> object:
            return identities.get(
                pid,
                supervisor.ListenerCandidateIdentityProbe(supervisor.IDENTITY_MISMATCH),
            )

        with mock.patch.object(
            supervisor,
            "stable_exercise_descendant_domain",
            return_value=(domain if domain_status == supervisor.IDENTITY_MATCH else None, domain_status),
        ), mock.patch.object(
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
        mapped_loopback_listener = self._tcp_line(
            f"0000000000000000FFFF00000100007F:{port:04X}",
            inode="789012",
        )
        native_ipv6_loopback_listener = self._tcp_line(
            f"00000000000000000000000001000000:{port:04X}",
            inode="789012",
        )
        ipv6_wildcard_listener = self._tcp_line(f"{'0' * 32}:{port:04X}", inode="789012")
        mapped_nonloopback_listener = self._tcp_line(
            f"0000000000000000FFFF00000200007F:{port:04X}",
            inode="789012",
        )
        malformed_inode = self._tcp_line(f"0100007F:{port:04X}", inode="not-an-inode")
        cases: tuple[tuple[str, object, object, tuple[int | None, str]], ...] = (
            (
                "strict-loopback-listener",
                f"{header}\n{loopback_listener}\n",
                f"{header}\n",
                (789012, "socket-listener"),
            ),
            (
                "strict-mapped-loopback-listener",
                f"{header}\n",
                f"{header}\n{mapped_loopback_listener}\n",
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
                "native-ipv6-loopback",
                f"{header}\n",
                f"{header}\n{native_ipv6_loopback_listener}\n",
                (None, "socket-listener-nonloopback-or-ipv6"),
            ),
            (
                "ipv6-wildcard",
                f"{header}\n",
                f"{header}\n{ipv6_wildcard_listener}\n",
                (None, "socket-listener-nonloopback-or-ipv6"),
            ),
            (
                "mapped-nonloopback",
                f"{header}\n",
                f"{header}\n{mapped_nonloopback_listener}\n",
                (None, "socket-listener-nonloopback-or-ipv6"),
            ),
            (
                "tcp-and-mapped-tcp6-ambiguous",
                f"{header}\n{loopback_listener}\n",
                f"{header}\n{mapped_loopback_listener}\n",
                (None, "socket-listener-ambiguous"),
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


@unittest.skipUnless(Path("/proc/self/cmdline").is_file(), "Linux procfs is required")
class AuthoritativeLauncherArgvProcTest(unittest.TestCase):
    def test_sets_id_clean_env_java_jar_argv_matches_exactly_and_rejects_variants(self) -> None:
        tools = {
            name: shutil.which(name)
            for name in ("env", "jar", "java", "javac", "setsid")
        }
        missing_tools = sorted(name for name, executable in tools.items() if executable is None)
        if missing_tools:
            self.skipTest(f"required local test tools unavailable: {', '.join(missing_tools)}")

        artifact_root = REPOSITORY_ROOT / "temp" / "test-artifacts" / "BUG-009"
        artifact_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=artifact_root) as temporary_directory:
            run_dir = Path(temporary_directory).resolve()
            source_dir = run_dir / "source"
            classes_dir = run_dir / "classes"
            home_dir = run_dir / "home"
            source_dir.mkdir()
            classes_dir.mkdir()
            home_dir.mkdir()
            source = source_dir / "TestOwnedLauncher.java"
            source.write_text(
                "import java.io.BufferedReader;\n"
                "import java.io.InputStreamReader;\n"
                "public final class TestOwnedLauncher {\n"
                "  public static void main(String[] args) throws Exception {\n"
                "    System.out.println(\"READY\");\n"
                "    System.out.flush();\n"
                "    new BufferedReader(new InputStreamReader(System.in)).readLine();\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )
            subprocess.run(
                [str(tools["javac"]), "-d", str(classes_dir), str(source)],
                cwd=run_dir,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            manifest = run_dir / "MANIFEST.MF"
            manifest.write_text("Manifest-Version: 1.0\nMain-Class: TestOwnedLauncher\n\n", encoding="utf-8")
            launcher_jar = run_dir / "launcher-1.0.0-SNAPSHOT.jar"
            subprocess.run(
                [
                    str(tools["jar"]),
                    "--create",
                    "--file",
                    str(launcher_jar),
                    "--manifest",
                    str(manifest),
                    "-C",
                    str(classes_dir),
                    ".",
                ],
                cwd=run_dir,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )

            java = Path(str(tools["java"])).resolve(strict=True)
            run_id = "int001-test-owned-authoritative-argv"
            expected_argv = [
                str(java),
                f"-Dint001.run-id={run_id}",
                "-jar",
                str(launcher_jar),
                "--spring.profiles.active=mock",
            ]
            process = subprocess.Popen(
                [
                    str(tools["setsid"]),
                    str(tools["env"]),
                    "-i",
                    "PATH=/usr/bin:/bin",
                    f"HOME={home_dir}",
                    "LANG=C.UTF-8",
                    *expected_argv,
                ],
                cwd=run_dir,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            try:
                if process.stdout is None:
                    self.fail("test-owned Java stdout was not captured")
                ready, _, _ = select.select([process.stdout], [], [], 10.0)
                if not ready:
                    self.fail("test-owned Java/JAR did not become ready")
                self.assertEqual("READY", process.stdout.readline().strip())

                exercise_pid = os.getpid()
                exercise_start_ticks = supervisor.proc_stat(exercise_pid)[2]
                exact = supervisor.exact_launcher_candidate_identity(
                    pid=process.pid,
                    expected_start_ticks=None,
                    expected_argv=expected_argv,
                    java=java,
                    run_dir=run_dir,
                    exercise_pid=exercise_pid,
                    exercise_start_ticks=exercise_start_ticks,
                )
                self.assertEqual(supervisor.IDENTITY_MATCH, exact.status)
                self.assertEqual("EXACT_CANDIDATE_FOUND", exact.identity_diagnostic)
                self.assertIsNotNone(exact.candidate)

                variants = {
                    "missing-app-arg": expected_argv[:-1],
                    "reordered-jvm-arg": [
                        expected_argv[0],
                        expected_argv[2],
                        expected_argv[1],
                        *expected_argv[3:],
                    ],
                    "extra-app-arg": [*expected_argv, "--unexpected=true"],
                }
                for label, variant in variants.items():
                    with self.subTest(label=label):
                        rejected = supervisor.exact_launcher_candidate_identity(
                            pid=process.pid,
                            expected_start_ticks=None,
                            expected_argv=variant,
                            java=java,
                            run_dir=run_dir,
                            exercise_pid=exercise_pid,
                            exercise_start_ticks=exercise_start_ticks,
                        )
                        self.assertEqual(supervisor.IDENTITY_MISMATCH, rejected.status)
                        self.assertEqual("NO_EXACT_ARGV_MATCH", rejected.identity_diagnostic)
                        self.assertIsNone(rejected.candidate)
            finally:
                if process.poll() is None and process.stdin is not None:
                    try:
                        process.stdin.write("stop\n")
                        process.stdin.flush()
                        process.stdin.close()
                    except (BrokenPipeError, OSError):
                        pass
                try:
                    returncode = process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    returncode = process.wait(timeout=5)
                stderr = process.stderr.read() if process.stderr is not None else ""
                if process.stdout is not None:
                    process.stdout.close()
                if process.stderr is not None:
                    process.stderr.close()
            self.assertEqual(0, returncode, stderr)


@unittest.skipUnless(Path("/proc/self/net/tcp").is_file(), "Linux procfs is required")
class ProductionLikeFullSupervisorJavaSeamTest(unittest.TestCase):
    def test_real_harness_nesting_completes_full_supervisor_one_term_path(self) -> None:
        if os.environ.get("INT001_TEST_PID_NAMESPACE") != "1":
            unshare = shutil.which("unshare")
            if unshare is None:
                self.skipTest("unshare is required for an isolated production-like seam")
            isolated = subprocess.run(
                [
                    unshare,
                    "--user",
                    "--map-current-user",
                    "--pid",
                    "--fork",
                    "--mount-proc",
                    sys.executable,
                    "-m",
                    "unittest",
                    (
                        "tools.navigator-upstream.fixtures.synthetic-integration."
                        "test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest."
                        "test_real_harness_nesting_completes_full_supervisor_one_term_path"
                    ),
                    "-v",
                ],
                cwd=REPOSITORY_ROOT,
                env={
                    "INT001_TEST_PID_NAMESPACE": "1",
                    "LANG": "C.UTF-8",
                    "PATH": "/usr/bin:/bin",
                    "PYTHONDONTWRITEBYTECODE": "1",
                    "PYTHONUNBUFFERED": "1",
                },
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=30,
            )
            self.assertEqual(
                0,
                isolated.returncode,
                f"isolated stdout:\n{isolated.stdout}\nisolated stderr:\n{isolated.stderr}",
            )
            return

        tools = {
            name: shutil.which(name)
            for name in ("jar", "java", "javac")
        }
        missing_tools = sorted(name for name, executable in tools.items() if executable is None)
        if missing_tools:
            self.skipTest(f"required local test tools unavailable: {', '.join(missing_tools)}")

        artifact_root = REPOSITORY_ROOT / "temp" / "test-artifacts" / "BUG-009"
        artifact_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=artifact_root) as temporary_directory:
            test_root = Path(temporary_directory).resolve()
            run_dir = test_root / "run"
            run_dir.mkdir(mode=0o700)
            source_dir = test_root / "source"
            classes_dir = test_root / "classes"
            source_dir.mkdir(mode=0o700)
            classes_dir.mkdir(mode=0o700)
            launcher_jar = test_root / "launcher-1.0.0-SNAPSHOT.jar"
            self._build_listener_jar(
                source_dir=source_dir,
                classes_dir=classes_dir,
                launcher_jar=launcher_jar,
                javac=str(tools["javac"]),
                jar=str(tools["jar"]),
            )

            harness_library = test_root / "harness-library.sh"
            harness_source = HARNESS_PATH.read_text(encoding="utf-8")
            entrypoint = '\nmain "$@"\n'
            self.assertTrue(harness_source.endswith(entrypoint))
            harness_library.write_text(
                harness_source[: -len(entrypoint)] + "\n",
                encoding="utf-8",
            )
            harness_library.chmod(0o700)

            java = Path(str(tools["java"])).resolve(strict=True)
            run_id = "int001-test-production-like-java"
            port = self._unused_loopback_port()
            expected_argv = [
                str(java),
                f"-Dint001.run-id={run_id}",
                "-jar",
                str(launcher_jar),
                "--spring.profiles.active=mock",
            ]
            held_child = test_root / "held-child.sh"
            outer = test_root / "outer.sh"
            test_home = test_root / "outer-home"
            test_home.mkdir(mode=0o700)
            self._write_held_child(
                path=held_child,
                harness_library=harness_library,
                run_dir=run_dir,
                run_id=run_id,
                java=java,
                launcher_jar=launcher_jar,
                port=port,
            )
            self._write_outer(
                path=outer,
                harness_library=harness_library,
                held_child=held_child,
                run_id=run_id,
                test_home=test_home,
            )

            process = subprocess.Popen(
                ["/usr/bin/bash", "-p", str(outer)],
                cwd=REPOSITORY_ROOT,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
                env={"LANG": "C.UTF-8", "PATH": "/usr/bin:/bin"},
            )
            outcome = None

            try:
                exercise_start_ticks = supervisor.proc_stat(process.pid)[2]
                supervisor.SUPERVISOR_INTERRUPTION = None
                with mock.patch.object(
                    supervisor,
                    "expected_launcher_argv",
                    return_value=expected_argv,
                ):
                    outcome = supervisor.supervise_exercise(
                        child_pid=process.pid,
                        initial_start_ticks=exercise_start_ticks,
                        repo_root=REPOSITORY_ROOT,
                        expected_argv=["/usr/bin/bash", "-p", str(outer)],
                        run_id=run_id,
                        run_dir=run_dir,
                        artifact_root=test_root,
                        navigator_port=port,
                        health_timeout_seconds=10,
                        post_term_timeout_seconds=10,
                    )

                self.assertTrue(
                    outcome.health_precondition,
                    (
                        f"listener_reason={outcome.listener_proof.reason if outcome.listener_proof else 'NONE'} "
                        f"listener_identity={outcome.listener_proof.identity_diagnostic if outcome.listener_proof else 'NONE'} "
                        f"ever_eligible={outcome.listener_proof_ever_eligible} "
                        f"term_dispatches={outcome.term_dispatches}"
                    ),
                )
                self.assertIsNotNone(outcome.parent_proof)
                assert outcome.parent_proof is not None
                self.assertTrue(outcome.parent_proof.ok, outcome.parent_proof.reason)
                self.assertTrue(outcome.listener_proof_ever_eligible)
                self.assertIsNotNone(outcome.listener_proof)
                assert outcome.listener_proof is not None
                self.assertTrue(
                    outcome.listener_proof.ok,
                    (
                        f"reason={outcome.listener_proof.reason} "
                        f"identity={outcome.listener_proof.identity_diagnostic}"
                    ),
                )
                self.assertEqual(
                    "uid+java+argv+cwd+ancestor+socket+startTicks",
                    outcome.listener_proof.reason,
                )
                self.assertEqual(supervisor.LISTENER_IDENTITY_EXACT, outcome.listener_proof.identity_diagnostic)
                self.assertEqual(1, outcome.term_dispatches)
                self.assertTrue(outcome.dispatch_safe)
                self.assertIsNotNone(outcome.child_exit)
                assert outcome.child_exit is not None
                self.assertTrue(os.WIFEXITED(outcome.child_exit))
                self.assertEqual(128 + signal.SIGTERM, os.WEXITSTATUS(outcome.child_exit))
            finally:
                supervisor.SUPERVISOR_INTERRUPTION = None
                if outcome is None or outcome.child_exit is None:
                    _returncode, stderr = self._stop_outer(process)
                else:
                    # supervise_exercise owns waitpid for the exact child. Keep
                    # the Popen wrapper in sync so test teardown does not emit
                    # a false "subprocess still running" ResourceWarning.
                    process.returncode = os.waitstatus_to_exitcode(outcome.child_exit)
                    stderr = process.stderr.read() if process.stderr is not None else ""
                    if process.stdout is not None:
                        process.stdout.close()
                    if process.stderr is not None:
                        process.stderr.close()

            self.assertEqual("", stderr)

    def test_real_harness_nesting_exact_identity_without_listener_fails_closed(self) -> None:
        if os.environ.get("INT001_TEST_PID_NAMESPACE") != "1":
            unshare = shutil.which("unshare")
            if unshare is None:
                self.skipTest("unshare is required for an isolated production-like seam")
            isolated = subprocess.run(
                [
                    unshare,
                    "--user",
                    "--map-current-user",
                    "--pid",
                    "--fork",
                    "--mount-proc",
                    sys.executable,
                    "-m",
                    "unittest",
                    (
                        "tools.navigator-upstream.fixtures.synthetic-integration."
                        "test_forced_signal_supervisor.ProductionLikeFullSupervisorJavaSeamTest."
                        "test_real_harness_nesting_exact_identity_without_listener_fails_closed"
                    ),
                    "-v",
                ],
                cwd=REPOSITORY_ROOT,
                env={
                    "INT001_TEST_PID_NAMESPACE": "1",
                    "LANG": "C.UTF-8",
                    "PATH": "/usr/bin:/bin",
                    "PYTHONDONTWRITEBYTECODE": "1",
                    "PYTHONUNBUFFERED": "1",
                },
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=30,
            )
            self.assertEqual(
                0,
                isolated.returncode,
                f"isolated stdout:\n{isolated.stdout}\nisolated stderr:\n{isolated.stderr}",
            )
            return

        tools = {name: shutil.which(name) for name in ("jar", "java", "javac")}
        missing_tools = sorted(name for name, executable in tools.items() if executable is None)
        if missing_tools:
            self.skipTest(f"required local test tools unavailable: {', '.join(missing_tools)}")

        artifact_root = REPOSITORY_ROOT / "temp" / "test-artifacts" / "BUG-009"
        artifact_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=artifact_root) as temporary_directory:
            test_root = Path(temporary_directory).resolve()
            run_dir = test_root / "run"
            run_dir.mkdir(mode=0o700)
            source_dir = test_root / "source"
            classes_dir = test_root / "classes"
            source_dir.mkdir(mode=0o700)
            classes_dir.mkdir(mode=0o700)
            launcher_jar = test_root / "launcher-1.0.0-SNAPSHOT.jar"
            self._build_listener_jar(
                source_dir=source_dir,
                classes_dir=classes_dir,
                launcher_jar=launcher_jar,
                javac=str(tools["javac"]),
                jar=str(tools["jar"]),
            )

            harness_library = test_root / "harness-library.sh"
            harness_source = HARNESS_PATH.read_text(encoding="utf-8")
            entrypoint = '\nmain "$@"\n'
            self.assertTrue(harness_source.endswith(entrypoint))
            harness_library.write_text(
                harness_source[: -len(entrypoint)] + "\n",
                encoding="utf-8",
            )
            harness_library.chmod(0o700)

            java = Path(str(tools["java"])).resolve(strict=True)
            run_id = "int001-test-production-like-no-listener"
            port = self._unused_loopback_port()
            expected_argv = [
                str(java),
                f"-Dint001.run-id={run_id}",
                "-jar",
                str(launcher_jar),
                "--spring.profiles.active=mock",
            ]
            bind_gate = test_root / "allow-listener-bind"
            held_child = test_root / "held-child.sh"
            outer = test_root / "outer.sh"
            test_home = test_root / "outer-home"
            test_home.mkdir(mode=0o700)
            self.assertFalse(bind_gate.exists())
            self._write_held_child(
                path=held_child,
                harness_library=harness_library,
                run_dir=run_dir,
                run_id=run_id,
                java=java,
                launcher_jar=launcher_jar,
                port=port,
                bind_gate=bind_gate,
            )
            self._write_outer(
                path=outer,
                harness_library=harness_library,
                held_child=held_child,
                run_id=run_id,
                test_home=test_home,
            )

            process = subprocess.Popen(
                ["/usr/bin/bash", "-p", str(outer)],
                cwd=REPOSITORY_ROOT,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
                env={"LANG": "C.UTF-8", "PATH": "/usr/bin:/bin"},
            )
            outcome = None

            try:
                exercise_start_ticks = supervisor.proc_stat(process.pid)[2]
                supervisor.SUPERVISOR_INTERRUPTION = None
                with mock.patch.object(
                    supervisor,
                    "expected_launcher_argv",
                    return_value=expected_argv,
                ):
                    outcome = supervisor.supervise_exercise(
                        child_pid=process.pid,
                        initial_start_ticks=exercise_start_ticks,
                        repo_root=REPOSITORY_ROOT,
                        expected_argv=["/usr/bin/bash", "-p", str(outer)],
                        run_id=run_id,
                        run_dir=run_dir,
                        artifact_root=test_root,
                        navigator_port=port,
                        health_timeout_seconds=4,
                        post_term_timeout_seconds=10,
                    )

                self.assertFalse(outcome.health_precondition)
                self.assertIsNotNone(outcome.parent_proof)
                assert outcome.parent_proof is not None
                self.assertTrue(outcome.parent_proof.ok, outcome.parent_proof.reason)
                self.assertIsNotNone(outcome.listener_proof)
                assert outcome.listener_proof is not None
                self.assertFalse(outcome.listener_proof.ok)
                self.assertEqual(supervisor.SOCKET_LISTENER_ABSENT, outcome.listener_proof.reason)
                self.assertEqual(
                    supervisor.LISTENER_IDENTITY_EXACT,
                    outcome.listener_proof.identity_diagnostic,
                )
                self.assertEqual(
                    supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
                    outcome.listener_proof.proof_stage_diagnostic,
                )
                self.assertEqual(
                    supervisor.LISTENER_IDENTITY_EXACT,
                    outcome.furthest_listener_identity_diagnostic,
                )
                self.assertEqual(
                    supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
                    outcome.furthest_listener_proof_stage_diagnostic,
                )
                self.assertFalse(outcome.listener_proof_ever_eligible)
                self.assertEqual(0, outcome.term_dispatches)
                self.assertFalse(outcome.dispatch_safe)
                self.assertIsNone(outcome.child_exit)
                self.assertIsNone(process.poll())
                self.assertFalse(bind_gate.exists())
            finally:
                supervisor.SUPERVISOR_INTERRUPTION = None
                _returncode, stderr = self._stop_outer(process)

            self.assertEqual("", stderr)

    @staticmethod
    def _build_listener_jar(
        *,
        source_dir: Path,
        classes_dir: Path,
        launcher_jar: Path,
        javac: str,
        jar: str,
    ) -> None:
        source = source_dir / "TestOwnedListener.java"
        source.write_text(
            "import java.io.IOException;\n"
            "import java.net.InetSocketAddress;\n"
            "import java.nio.charset.StandardCharsets;\n"
            "import java.nio.channels.ServerSocketChannel;\n"
            "import java.nio.channels.SocketChannel;\n"
            "public final class TestOwnedListener {\n"
            "  public static void main(String[] args) throws Exception {\n"
            "    int port = Integer.parseInt(System.getenv(\"TEST_PORT\"));\n"
            "    String bindGate = System.getenv(\"TEST_BIND_GATE\");\n"
            "    while (bindGate != null && !java.nio.file.Files.exists(java.nio.file.Path.of(bindGate))) {\n"
            "      Thread.sleep(25L);\n"
            "    }\n"
            "    try (ServerSocketChannel listener = ServerSocketChannel.open()) {\n"
            "      listener.bind(new InetSocketAddress(\"127.0.0.1\", port), 16);\n"
            "      byte[] response = (\"HTTP/1.1 200 OK\\r\\n\" +\n"
            "          \"Content-Type: application/json\\r\\n\" +\n"
            "          \"Content-Length: 15\\r\\n\" +\n"
            "          \"Connection: close\\r\\n\\r\\n\" +\n"
            "          \"{\\\"status\\\":\\\"UP\\\"}\").getBytes(StandardCharsets.US_ASCII);\n"
            "      for (;;) {\n"
            "        try (SocketChannel client = listener.accept()) {\n"
            "          client.write(java.nio.ByteBuffer.wrap(response));\n"
            "        } catch (IOException ignored) {\n"
            "        }\n"
            "      }\n"
            "    }\n"
            "  }\n"
            "}\n",
            encoding="utf-8",
        )
        subprocess.run(
            [javac, "--release", "17", "-d", str(classes_dir), str(source)],
            cwd=source_dir.parent,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        manifest = source_dir.parent / "MANIFEST.MF"
        manifest.write_text(
            "Manifest-Version: 1.0\nMain-Class: TestOwnedListener\n\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                jar,
                "--create",
                "--file",
                str(launcher_jar),
                "--manifest",
                str(manifest),
                "-C",
                str(classes_dir),
                ".",
            ],
            cwd=source_dir.parent,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    @staticmethod
    def _write_held_child(
        *,
        path: Path,
        harness_library: Path,
        run_dir: Path,
        run_id: str,
        java: Path,
        launcher_jar: Path,
        port: int,
        bind_gate: Path | None = None,
    ) -> None:
        q = shlex.quote
        bind_gate_environment = (
            "" if bind_gate is None else f" {q(f'TEST_BIND_GATE={bind_gate}')}"
        )
        path.write_text(
            "#!/usr/bin/bash -p\n"
            f"source {q(str(harness_library))}\n"
            f"REPO_ROOT={q(str(REPOSITORY_ROOT))}\n"
            f"HARNESS_SELF={q(str(path))}\n"
            f"RUN_ID={q(run_id)}\n"
            f"RUN_DIR={q(str(run_dir))}\n"
            "exercise_child_argv_is_canonical run-hold "
            '"$TRUSTED_BASH" -p "$HARNESS_SELF" "$@" || exit 65\n'
            'mkdir -m 700 "$RUN_DIR/private" "$RUN_DIR/children" "$RUN_DIR/home"\n'
            "cleanup_test_listener() {\n"
            "  trap - TERM\n"
            "  stop_owned_child \"$RUN_DIR\" launcher launcher-1.0.0-SNAPSHOT.jar || exit 91\n"
            "  exit 143\n"
            "}\n"
            "trap cleanup_test_listener TERM\n"
            'start_child "$RUN_DIR" launcher launcher-1.0.0-SNAPSHOT.jar '
            '"$RUN_DIR/private/launcher-process.log" '
            f"env -i \"PATH=$SAFE_CHILD_PATH\" \"HOME=$RUN_DIR/home\" \"TEST_PORT={port}\""
            f"{bind_gate_environment} "
            f"{q(str(java))} {q(f'-Dint001.run-id={run_id}')} -jar {q(str(launcher_jar))} "
            "--spring.profiles.active=mock\n"
            'parse_child_meta_for_probe "$RUN_DIR/children/launcher.pid"\n'
            'wait "${CHILD_META[PID]}"\n',
            encoding="utf-8",
        )
        path.chmod(0o700)

    @staticmethod
    def _write_outer(
        *,
        path: Path,
        harness_library: Path,
        held_child: Path,
        run_id: str,
        test_home: Path,
    ) -> None:
        q = shlex.quote
        path.write_text(
            "#!/usr/bin/bash -p\n"
            f"source {q(str(harness_library))}\n"
            f"REPO_ROOT={q(str(REPOSITORY_ROOT))}\n"
            f"HARNESS_SELF={q(str(held_child))}\n"
            f"RUN_ID={q(run_id)}\n"
            f"TEST_HOME={q(str(test_home))}\n"
            "local_docker_home() { printf '%s' \"$TEST_HOME\"; }\n"
            "forward_test_term() {\n"
            "  trap - TERM\n"
            "  if exercise_child_is_live_and_owned; then\n"
            "    kill -TERM -- \"-$EXERCISE_CHILD_PID\"\n"
            "  fi\n"
            "}\n"
            "trap forward_test_term TERM\n"
            "exercise_invoke_child run-hold run --allow-execute --build-launcher "
            f"--run-id {q(run_id)} --hold-for-parent-term\n",
            encoding="utf-8",
        )
        path.chmod(0o700)

    @staticmethod
    def _unused_loopback_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
            reservation.bind(("127.0.0.1", 0))
            return int(reservation.getsockname()[1])

    @staticmethod
    def _stop_outer(process: subprocess.Popen[str]) -> tuple[int, str]:
        if process.poll() is None:
            os.kill(process.pid, signal.SIGTERM)
        try:
            returncode = process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.kill(process.pid, signal.SIGKILL)
            returncode = process.wait(timeout=5)
        stderr = process.stderr.read() if process.stderr is not None else ""
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()
        return returncode, stderr


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
            self.assertEqual({"outerPid", "childPid", "holdPid", "port"}, set(topology))
            outer_pid = int(topology["outerPid"])
            child_pid = int(topology["childPid"])
            hold_pid = int(topology["holdPid"])
            port = int(topology["port"])

            self.assertGreater(outer_pid, 1)
            self.assertGreater(child_pid, 1)
            self.assertGreater(hold_pid, 1)
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
            self.assertEqual(hold_pid, evidence["holdPid"])
            self.assertEqual(port, evidence["port"])
            self.assertGreater(int(evidence["outerStartTicks"]), 0)
            self.assertEqual("MATCH", evidence["domainStatus"])
            self.assertEqual(
                sorted((outer_pid, child_pid, hold_pid)),
                sorted(evidence["domainPids"]),
            )
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
            "hold = subprocess.Popen(['/usr/bin/sleep', '30'], stdin=subprocess.DEVNULL, "
            "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
            "try:\n"
            "    child_line = child.stdout.readline() if child.stdout is not None else ''\n"
            "    child_topology = json.loads(child_line)\n"
            "    print(json.dumps({'outerPid': os.getpid(), 'holdPid': hold.pid, **child_topology}), flush=True)\n"
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
            "    if hold.poll() is None:\n"
            "        hold.terminate()\n"
            "        try:\n"
            "            hold.wait(timeout=5)\n"
            "        except subprocess.TimeoutExpired:\n"
            "            hold.kill()\n"
            "            hold.wait(timeout=5)\n"
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
            "        hold_pid = int(topology['holdPid'])\n"
            "        port = int(topology['port'])\n"
            "        outer_start_ticks = target.proc_stat(outer_pid)[2]\n"
            "        domain, domain_status = target.stable_exercise_descendant_domain(\n"
            "            outer_pid, outer_start_ticks,\n"
            "        )\n"
            "        java = Path(sys.executable).resolve()\n"
            "        expected_argv = [str(java), '-c', child_code]\n"
            "        run_dir = Path.cwd().resolve()\n"
            "        identity = target.exact_launcher_candidate_identity(\n"
            "            pid=child_pid, expected_start_ticks=None, expected_argv=expected_argv,\n"
            "            java=java, run_dir=run_dir, exercise_pid=outer_pid,\n"
            "            exercise_start_ticks=outer_start_ticks,\n"
            "        )\n"
            "        candidate, candidate_reason, candidate_diagnostic = target.find_exact_launcher_candidate(\n"
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
            "            'holdPid': hold_pid,\n"
            "            'port': port,\n"
            "            'outerStartTicks': outer_start_ticks,\n"
            "            'domainStatus': domain_status,\n"
            "            'domainPids': [item.pid for item in domain] if domain is not None else [],\n"
            "            'observedParentPid': target.proc_parent_pid(child_pid),\n"
            "            'descendant': target.is_descendant_of(child_pid, outer_pid, outer_start_ticks),\n"
            "            'candidateDiagnostic': candidate_diagnostic,\n"
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


class ForcedSignalCompletionGateTest(unittest.TestCase):
    def test_accepts_only_the_exact_real_outer_completion_contract(self) -> None:
        evidence = self._valid_evidence()
        self.assertTrue(supervisor.forced_signal_completion_gate_met(**evidence))

    def test_rejects_every_incomplete_or_noncanonical_completion_fact(self) -> None:
        cases: list[tuple[str, dict[str, object]]] = [
            ("supervisor-interrupted", {"supervisor_interruption": "TERM"}),
            ("health-false", {"outcome": self._make_outcome(health_precondition=False)}),
            ("parent-missing", {"outcome": self._make_outcome(parent_proof=None)}),
            (
                "parent-not-ok",
                {"outcome": self._make_outcome(parent_proof=supervisor.ParentProof(False, "argv", 100))},
            ),
            (
                "parent-reason-not-exact",
                {"outcome": self._make_outcome(parent_proof=supervisor.ParentProof(True, "parent-proof", 100))},
            ),
            ("listener-missing", {"outcome": self._make_outcome(listener_proof=None)}),
            (
                "listener-not-ok",
                {"outcome": self._make_outcome(listener_proof=supervisor.ListenerProof(False, "listener-candidate"))},
            ),
            (
                "listener-reason-not-exact",
                {
                    "outcome": self._make_outcome(
                        listener_proof=supervisor.ListenerProof(
                            True,
                            "listener-candidate",
                            identity_diagnostic=supervisor.LISTENER_IDENTITY_EXACT,
                        )
                    )
                },
            ),
            (
                "listener-identity-not-exact",
                {
                    "outcome": self._make_outcome(
                        listener_proof=supervisor.ListenerProof(
                            True,
                            supervisor.EXACT_LISTENER_PROOF_REASON,
                            identity_diagnostic=supervisor.LISTENER_IDENTITY_NO_EXACT_ARGV,
                        )
                    )
                },
            ),
            ("listener-never-eligible", {"outcome": self._make_outcome(listener_proof_ever_eligible=False)}),
            ("term-count-not-one", {"outcome": self._make_outcome(term_dispatches=0)}),
            ("dispatch-not-safe", {"outcome": self._make_outcome(dispatch_safe=False)}),
            ("outer-exit-missing", {"outcome": self._make_outcome(child_exit=None)}),
            ("outer-exit-zero", {"outcome": self._make_outcome(child_exit=0)}),
            ("outer-exit-one", {"outcome": self._make_outcome(child_exit=1 << 8)}),
            ("outer-exit-143", {"outcome": self._make_outcome(child_exit=143 << 8)}),
            ("outer-killed-by-term", {"outcome": self._make_outcome(child_exit=signal.SIGTERM)}),
            ("receipt-missing", {"receipt": None}),
            ("receipt-result-missing", {"receipt": self._receipt_without("result")}),
            ("receipt-failure-stage-missing", {"receipt": self._receipt_without("failureStage")}),
            (
                "receipt-lifecycle-observation-missing",
                {"receipt": self._receipt_without("rehearsalLifecycleObservation")},
            ),
            (
                "receipt-readiness-observation-missing",
                {"receipt": self._receipt_without("launcherReadinessObservation")},
            ),
            (
                "receipt-launcher-failure-class-missing",
                {"receipt": self._receipt_without("launcherFailureClass")},
            ),
            ("receipt-not-cleaned", {"receipt": self._receipt(result="FAILED_CLEANUP")}),
            ("receipt-not-signal", {"receipt": self._receipt(failureStage="UNKNOWN")}),
            (
                "receipt-not-held-signal",
                {"receipt": self._receipt(rehearsalLifecycleObservation="NOT_REHEARSAL")},
            ),
            (
                "receipt-not-health-ready",
                {"receipt": self._receipt(launcherReadinessObservation="NOT_OBSERVED")},
            ),
            ("receipt-launcher-failure", {"receipt": self._receipt(launcherFailureClass="UNKNOWN")}),
            ("private-remains", {"root_snapshot": supervisor.RunRootSnapshot(False, 0)}),
            ("root-residue-remains", {"root_snapshot": supervisor.RunRootSnapshot(True, 1)}),
            ("reservation-remains", {"reservation_absent": False}),
            (
                "docker-container-remains",
                {"docker_snapshot": {"container": 1, "network": 0, "volume": 0}},
            ),
            (
                "docker-network-unavailable",
                {"docker_snapshot": {"container": 0, "network": None, "volume": 0}},
            ),
            (
                "docker-volume-remains",
                {"docker_snapshot": {"container": 0, "network": 0, "volume": 1}},
            ),
        ]
        for label, replacement in cases:
            with self.subTest(label=label):
                evidence = self._valid_evidence()
                evidence.update(replacement)
                self.assertFalse(supervisor.forced_signal_completion_gate_met(**evidence))

    def _valid_evidence(self) -> dict[str, object]:
        return {
            "supervisor_interruption": None,
            "outcome": self._make_outcome(),
            "receipt": self._receipt(),
            "root_snapshot": supervisor.RunRootSnapshot(True, 0),
            "reservation_absent": True,
            "docker_snapshot": {"container": 0, "network": 0, "volume": 0},
        }

    @staticmethod
    def _make_outcome(**overrides: object) -> object:
        values: dict[str, object] = {
            "health_precondition": True,
            "parent_proof": supervisor.ParentProof(True, supervisor.EXACT_PARENT_PROOF_REASON, 100),
            "listener_proof": supervisor.ListenerProof(
                True,
                supervisor.EXACT_LISTENER_PROOF_REASON,
                identity_diagnostic=supervisor.LISTENER_IDENTITY_EXACT,
            ),
            "term_dispatches": 1,
            "dispatch_safe": True,
            "child_exit": supervisor.FORCED_SIGNAL_OUTER_EXIT_CODE << 8,
            "listener_proof_ever_eligible": True,
        }
        values.update(overrides)
        return supervisor.RehearsalOutcome(**values)

    @staticmethod
    def _receipt(**overrides: object) -> dict[str, object]:
        values: dict[str, object] = {
            "mode": "0600",
            "schemaVersion": 4,
            "result": "CLEANED",
            "failureStage": "SIGNAL",
            "rehearsalLifecycleObservation": "HOLD_SIGNAL_RECEIVED",
            "launcherReadinessObservation": "HEALTH_READY",
            "launcherFailureClass": "NOT_APPLICABLE",
            "secretsRedacted": True,
        }
        values.update(overrides)
        return values

    @classmethod
    def _receipt_without(cls, key: str) -> dict[str, object]:
        values = cls._receipt()
        del values[key]
        return values


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
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_exercise_parent",
                    return_value=supervisor.ParentProof(True, "parent-proof", 100),
                )
            )
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

    def test_temporal_diagnostic_retains_exact_candidate_after_cleanup_time_absence(self) -> None:
        exact = self._listener_proof()
        cleanup_absent = supervisor.ListenerProof(
            False,
            "listener-candidate-absent",
            identity_diagnostic=supervisor.LISTENER_IDENTITY_NO_TRUSTED_JAVA,
        )
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_owned_loopback_launcher",
                    side_effect=[exact, cleanup_absent],
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_exercise_parent",
                    return_value=supervisor.ParentProof(True, "parent-proof", 100),
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=False))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
            stack.enter_context(
                mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 0.2, 31.0])
            )
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
            kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

            outcome = self._supervise()

        dispatch.assert_not_called()
        kill.assert_not_called()
        self.assertEqual(0, outcome.term_dispatches)
        self.assertTrue(outcome.listener_proof_ever_eligible)
        self.assertEqual(cleanup_absent, outcome.listener_proof)
        self.assertEqual(
            supervisor.LISTENER_IDENTITY_EXACT,
            outcome.furthest_listener_identity_diagnostic,
        )
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
            outcome.furthest_listener_proof_stage_diagnostic,
        )

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            supervisor.emit_summary(
                run_id=self.run_id,
                health_precondition=outcome.health_precondition,
                parent_proof=outcome.parent_proof,
                listener_proof=outcome.listener_proof,
                listener_proof_ever_eligible=outcome.listener_proof_ever_eligible,
                term_dispatches=outcome.term_dispatches,
                dispatch_safe=outcome.dispatch_safe,
                child_exit=outcome.child_exit,
                receipt=None,
                root_snapshot=supervisor.RunRootSnapshot(None, None),
                docker_snapshot={"container": None, "network": None, "volume": None},
                furthest_listener_identity_diagnostic=outcome.furthest_listener_identity_diagnostic,
                furthest_listener_proof_stage_diagnostic=(
                    outcome.furthest_listener_proof_stage_diagnostic
                ),
            )
        summary = json.loads(output.getvalue())
        self.assertEqual(
            supervisor.LISTENER_IDENTITY_EXACT,
            summary["listenerIdentityDiagnostic"],
        )
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
            summary["listenerProofStageDiagnostic"],
        )

    def test_temporal_stage_retains_post_identity_blocker_after_cleanup_time_absence(self) -> None:
        socket_absent = supervisor.ListenerProof(
            False,
            supervisor.SOCKET_LISTENER_ABSENT,
            identity_diagnostic=supervisor.LISTENER_IDENTITY_EXACT,
            proof_stage_diagnostic=supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
        )
        cleanup_absent = supervisor.ListenerProof(
            False,
            supervisor.LISTENER_CANDIDATE_ABSENT,
            identity_diagnostic=supervisor.LISTENER_IDENTITY_NO_TRUSTED_JAVA,
        )
        with contextlib.ExitStack() as stack:
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_owned_loopback_launcher",
                    side_effect=[socket_absent, cleanup_absent],
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "prove_exercise_parent",
                    return_value=supervisor.ParentProof(True, "parent-proof", 100),
                )
            )
            health = stack.enter_context(mock.patch.object(supervisor, "health_ready", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
            stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
            stack.enter_context(
                mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 0.2, 31.0])
            )
            dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))

            outcome = self._supervise()

        health.assert_not_called()
        dispatch.assert_not_called()
        self.assertFalse(outcome.listener_proof_ever_eligible)
        self.assertEqual(0, outcome.term_dispatches)
        self.assertEqual(cleanup_absent, outcome.listener_proof)
        self.assertEqual(
            supervisor.LISTENER_IDENTITY_EXACT,
            outcome.furthest_listener_identity_diagnostic,
        )
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
            outcome.furthest_listener_proof_stage_diagnostic,
        )

    def test_temporal_diagnostic_allows_later_exact_candidate_to_supersede_proc_failure(self) -> None:
        self.assertEqual(
            supervisor.LISTENER_IDENTITY_EXACT,
            supervisor.furthest_temporal_identity_diagnostic(
                supervisor.LISTENER_IDENTITY_PROC_UNAVAILABLE,
                supervisor.LISTENER_IDENTITY_EXACT,
            ),
        )

    def test_temporal_listener_stage_is_monotonic_and_allow_listed(self) -> None:
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            supervisor.furthest_temporal_listener_proof_stage(
                supervisor.LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
                supervisor.LISTENER_PROOF_STAGE_EXACT_IDENTITY,
            ),
        )
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
            supervisor.furthest_temporal_listener_proof_stage(
                supervisor.LISTENER_PROOF_STAGE_SOCKET_FOUND,
                supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
            ),
        )
        self.assertEqual(
            supervisor.LISTENER_PROOF_STAGE_NOT_OBSERVED,
            supervisor.furthest_temporal_listener_proof_stage(
                "pid=919191 cwd=/private/secret",
                "raw exception",
            ),
        )

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

        listener.assert_not_called()
        parent.assert_called_once()
        health.assert_not_called()
        dispatch.assert_not_called()
        kill.assert_not_called()
        self.assertFalse(outcome.health_precondition)
        self.assertEqual("argv", outcome.parent_proof.reason)
        self.assertEqual(0, outcome.term_dispatches)
        self.assertFalse(outcome.listener_proof_ever_eligible)

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
                stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_exercise_parent",
                        return_value=supervisor.ParentProof(True, "parent-proof", 100),
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
                parent = stack.enter_context(
                    mock.patch.object(
                        supervisor,
                        "prove_exercise_parent",
                        return_value=supervisor.ParentProof(True, "parent-proof", 100),
                    )
                )
                health = stack.enter_context(mock.patch.object(supervisor, "health_ready"))
                stack.enter_context(mock.patch.object(supervisor, "poll_child_exit", return_value=None))
                stack.enter_context(mock.patch.object(supervisor.time, "sleep"))
                stack.enter_context(mock.patch.object(supervisor.time, "monotonic", side_effect=[0.0, 0.1, 31.0]))
                dispatch = stack.enter_context(mock.patch.object(supervisor, "dispatch_owned_parent_term"))
                kill = stack.enter_context(mock.patch.object(supervisor.os, "kill"))

                outcome = self._supervise()

            parent.assert_called_once()
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
            self.assertEqual("EXACT_CANDIDATE_FOUND", outcome.listener_proof.identity_diagnostic)
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
        self.assertEqual(2, parent.call_count)
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
            self.assertEqual("EXACT_CANDIDATE_FOUND", listener.identity_diagnostic)
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
        self.assertEqual(4, parent_proof.call_count)
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

    def test_success_path_requires_exact_outer_exit_and_forced_signal_receipt_contract(self) -> None:
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
                        parent_proof=supervisor.ParentProof(
                            True,
                            supervisor.EXACT_PARENT_PROOF_REASON,
                            100,
                        ),
                        listener_proof=self._listener_proof(),
                        term_dispatches=1,
                        dispatch_safe=True,
                        child_exit=supervisor.FORCED_SIGNAL_OUTER_EXIT_CODE << 8,
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

    def test_success_shaped_evidence_with_a_stale_reservation_is_rejected(self) -> None:
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
            stack.enter_context(mock.patch.object(supervisor, "read_redacted_receipt", return_value=receipt))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "run_root_snapshot",
                    return_value=supervisor.RunRootSnapshot(True, 0),
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "current_run_reservation_absent", return_value=False))
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
        self.assertEqual("EXACT_CANDIDATE_FOUND", summary["listenerIdentityDiagnostic"])
        self.assertEqual("FULL_ELIGIBLE", summary["listenerProofStageDiagnostic"])
        self.assertEqual(receipt, summary["receipt"])
        for prohibited in ("pid", "argv", "cwd", "port", "inode", "socketInode"):
            self.assertNotIn(prohibited, summary)

    def test_redacted_summary_drops_unknown_identity_values_without_echoing_them(self) -> None:
        sensitive_sentinel = "pid=919191 cwd=/private/secret argv=raw exception=boom"
        proof = supervisor.ListenerProof(
            False,
            "listener-candidate-absent",
            identity_diagnostic=sensitive_sentinel,
            proof_stage_diagnostic=sensitive_sentinel,
        )
        output = io.StringIO()

        with contextlib.redirect_stdout(output):
            supervisor.emit_summary(
                run_id=self.run_id,
                health_precondition=False,
                parent_proof=None,
                listener_proof=proof,
                listener_proof_ever_eligible=False,
                term_dispatches=0,
                dispatch_safe=False,
                child_exit=None,
                receipt=None,
                root_snapshot=supervisor.RunRootSnapshot(None, None),
                docker_snapshot={"container": None, "network": None, "volume": None},
                furthest_listener_proof_stage_diagnostic=sensitive_sentinel,
            )

        serialized = output.getvalue()
        self.assertNotIn(sensitive_sentinel, serialized)
        summary = json.loads(serialized)
        self.assertEqual("NOT_OBSERVED", summary["listenerIdentityDiagnostic"])
        self.assertEqual("NOT_OBSERVED", summary["listenerProofStageDiagnostic"])

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
            "EXACT_CANDIDATE_FOUND",
            supervisor.LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
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
            stack.enter_context(mock.patch.object(supervisor, "current_run_reservation_absent", return_value=True))
            yield stack


if __name__ == "__main__":
    unittest.main(verbosity=2)
