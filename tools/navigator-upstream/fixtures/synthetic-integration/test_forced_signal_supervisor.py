#!/usr/bin/env python3
"""Offline regression tests for the BUG-009 forced-SIGNAL supervisor.

These tests deliberately replace every process, HTTP, Docker, and signal
boundary. They must never start the synthetic harness, connect to a real
Listener, query Docker, or signal a real process. The assertions protect the
supervisor's fail-closed preconditions rather than a disposable runtime.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import json
import os
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
        proof = self._prove()

        self.assertTrue(proof.ok)
        self.assertEqual(self.pid, proof.pid)
        self.assertEqual(self.start_ticks, proof.start_ticks)
        self.assertEqual(self.socket_inode, proof.socket_inode)

    def test_listener_proof_rejects_each_identity_component(self) -> None:
        cases: tuple[tuple[str, dict[str, object], str], ...] = (
            ("socket-owner", {"holders": (self.pid, self.pid + 1)}, "socket-owner"),
            ("java", {"java": None}, "listener-java"),
            ("jar", {"expected_argv": None}, "launcher-expected"),
            ("cwd", {"cwd": self.root / "wrong-cwd"}, "listener-cwd"),
            (
                "run-id",
                {
                    "command": [
                        str(self.java),
                        "-Dint001.run-id=other-run",
                        "-jar",
                        "/trusted/launcher.jar",
                        "--spring.profiles.active=mock",
                    ]
                },
                "listener-argv",
            ),
            ("ancestor", {"ancestor": False}, "listener-ancestor"),
        )
        for label, overrides, expected_reason in cases:
            with self.subTest(label=label):
                proof = self._prove(**overrides)
                self.assertFalse(proof.ok)
                self.assertEqual(expected_reason, proof.reason)

    def _prove(
        self,
        *,
        holders: tuple[int, ...] | None = None,
        socket_probe: tuple[int | None, str] | None = None,
        java: Path | None | object = ...,
        expected_argv: list[str] | None | object = ...,
        cwd: Path | None = None,
        command: list[str] | None = None,
        ancestor: bool = True,
    ) -> object:
        actual_java = self.java if java is ... else java
        actual_expected_argv = self.expected_argv if expected_argv is ... else expected_argv
        actual_cwd = self.run_dir if cwd is None else cwd
        actual_command = self.expected_argv if command is None else command
        actual_holders = (self.pid,) if holders is None else holders
        actual_socket_probe = (
            (self.socket_inode, "socket-listener") if socket_probe is None else socket_probe
        )
        pid = self.pid
        root = self.root
        run_dir = self.run_dir
        trusted_java = self.java

        class FakeProcPath:
            def __init__(self, raw_path: object) -> None:
                self.raw_path = os.fspath(raw_path)

            def stat(self) -> SimpleNamespace:
                if self.raw_path == f"/proc/{pid}":
                    return SimpleNamespace(st_uid=os.getuid())
                raise AssertionError(f"unexpected stat path: {self.raw_path}")

            def resolve(self, strict: bool = False) -> Path:
                if self.raw_path == f"/proc/{pid}/cwd":
                    return actual_cwd
                if self.raw_path == f"/proc/{pid}/exe":
                    return actual_java if actual_java is not None else trusted_java
                raise AssertionError(f"unexpected resolve path: {self.raw_path}")

        with contextlib.ExitStack() as stack:
            stack.enter_context(mock.patch.object(supervisor, "safe_run_directory", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "expected_launcher_argv", return_value=actual_expected_argv))
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "listener_socket_probe_for_loopback_port",
                    side_effect=[actual_socket_probe, actual_socket_probe],
                )
            )
            stack.enter_context(
                mock.patch.object(
                    supervisor,
                    "current_uid_socket_holders",
                    side_effect=[actual_holders, (pid,)],
                )
            )
            stack.enter_context(mock.patch.object(supervisor, "proc_is_live", return_value=True))
            stack.enter_context(mock.patch.object(supervisor, "proc_stat", return_value=(0, 0, self.start_ticks)))
            stack.enter_context(mock.patch.object(supervisor, "trusted_java_executable", return_value=actual_java))
            stack.enter_context(mock.patch.object(supervisor, "command_line", return_value=actual_command))
            stack.enter_context(mock.patch.object(supervisor, "is_descendant_of", return_value=ancestor))
            stack.enter_context(mock.patch.object(supervisor, "Path", side_effect=FakeProcPath))
            return supervisor.prove_owned_loopback_launcher(
                port=24_570,
                run_id=self.run_id,
                run_dir=run_dir,
                artifact_root=self.artifact_root,
                repo_root=root,
                exercise_pid=40_001,
                exercise_start_ticks=40_002,
            )


class ListenerSocketProbeTest(unittest.TestCase):
    def test_socket_probe_returns_only_fixed_fail_closed_reasons(self) -> None:
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
        payloads = {"/proc/net/tcp": tcp, "/proc/net/tcp6": tcp6}

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
            return supervisor.listener_socket_probe_for_loopback_port(port)


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
            "listener-cwd",
            "listener-argv",
            "listener-ancestor",
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
