#!/usr/bin/env python3
"""Run one isolated INT-001 parent-TERM rehearsal without widening its boundary.

This is a test-only supervisor for BUG-009.  It creates a fresh
`exercise --forced-signal-rehearsal` child in its own session, waits only for
that run's loopback Launcher health, proves the exact exercise parent from
`/proc`, then sends that PID one TERM.  The held lifecycle child remains in
the parent's lineage until this signal is forwarded through the harness's
existing owned-cleanup path.  The supervisor never targets a port process,
process group, child PID, Docker resource, or an existing run.  Its stdout is
a redacted evidence summary; it never writes credentials, profiles, logs, or
process metadata into durable evidence.
"""

from __future__ import annotations

import argparse
import fcntl
import http.client
import json
import os
import pwd
import re
import secrets
import signal
import socket
import stat
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SAFE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
LOCAL_DOCKER_HOST = "unix:///var/run/docker.sock"
LOCAL_DOCKER_SOCKET = Path("/var/run/docker.sock")
TRUSTED_JAVA_LINK = Path("/usr/bin/java")
DYNAMIC_PORT_MIN = 20000
DYNAMIC_PORT_MAX = 29999
RESERVED_PORTS = {8112, 8200, 3031, 3051, 3061, 3071, 3131, 3151, 3161, 3062, 5174, 5181}
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{5,63}$")
UTC_TIMESTAMP_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
MAX_HEALTH_BODY_BYTES = 4096
DOCKER_COMMAND_TIMEOUT_SECONDS = 10
MAX_DESCENDANT_DOMAIN_PROCESSES = 256
MAX_DESCENDANT_DOMAIN_TASKS = 4096
MAX_DESCENDANT_DOMAIN_DEPTH = 64
MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS = 8
STABLE_DESCENDANT_DOMAIN_RETRY_SECONDS = 0.05
RECEIPT_FIELDS = {
    "schemaVersion",
    "runId",
    "result",
    "failureStage",
    "rehearsalLifecycleObservation",
    "launcherReadinessObservation",
    "launcherFailureClass",
    "finishedAtUtc",
    "secretsRedacted",
}
CLEANUP_RESULTS = {"CLEANED", "FAILED_CLEANUP"}
CLEANUP_FAILURE_STAGES = {
    "NONE",
    "PREPARE",
    "PREFLIGHT",
    "BUILD",
    "COMPOSE",
    "DIRECTORY_FACADE",
    "BIZ_WORKER",
    "BIZ_INGRESS_PROXY",
    "LAUNCHER",
    "BOOTSTRAP",
    "AUDIT",
    "MANIFEST",
    "SIGNAL",
    "UNKNOWN",
}
LAUNCHER_READINESS_OBSERVATIONS = {
    "NOT_OBSERVED",
    "START_FAILED",
    "HEALTH_READY",
    "CHILD_EXITED_BEFORE_HEALTH",
    "CHILD_OWNERSHIP_UNPROVEN",
    "CHILD_ALIVE_AT_HEALTH_TIMEOUT",
}
LAUNCHER_FAILURE_CLASSES = {
    "NOT_APPLICABLE",
    "START_EXEC_FAILURE",
    "PORT_BIND_CONFLICT",
    "DATABASE_CONNECTIVITY",
    "DATABASE_AUTHORIZATION",
    "DATABASE_SCHEMA",
    "SPRING_CONFIGURATION",
    "JVM_OR_ARTIFACT",
    "APPLICATION_INITIALIZATION",
    "HEALTH_TIMEOUT",
    "OWNERSHIP_UNPROVEN",
    "UNKNOWN",
}
REHEARSAL_LIFECYCLE_OBSERVATIONS = {
    "NOT_REHEARSAL",
    "HOLD_ENTERED",
    "HOLD_TIMEOUT",
    "HOLD_WAIT_FAILURE",
    "HOLD_SIGNAL_RECEIVED",
}
PROJECTION_FIELDS = {
    "schemaVersion",
    "runId",
    "phase",
    "outcome",
    "receiptState",
    "rootSnapshotState",
    "stdoutSummaryState",
    "secretsRedacted",
}
PROJECTION_PHASES = {
    "SUPERVISOR_STARTED",
    "PREFLIGHT_COMPLETE",
    "EXERCISE_STARTED",
    "SUPERVISION_COMPLETE",
    "EVIDENCE_SAMPLED",
    "STDOUT_EMITTED",
    "COMPLETE",
    "FAILED",
}
PROJECTION_OUTCOMES = {
    "IN_PROGRESS",
    "PREFLIGHT_FAILED",
    "EXERCISE_START_FAILED",
    "SUPERVISION_FAILED",
    "CHILD_EXITED_BEFORE_HEALTH",
    "HEALTH_OR_OWNERSHIP_INELIGIBLE",
    "TERM_NOT_DISPATCHED",
    "TERM_DISPATCH_INELIGIBLE",
    "SUPERVISOR_INTERRUPTED",
    "RECEIPT_MISSING_OR_INVALID",
    "ROOT_SNAPSHOT_UNAVAILABLE",
    "DOCKER_SNAPSHOT_UNAVAILABLE",
    "SUCCESS_GATE_NOT_MET",
    "SUCCESS_GATE_MET",
    "UNEXPECTED_FAILURE",
}
PROJECTION_RECEIPT_STATES = {"NOT_SAMPLED", "VALID", "MISSING_OR_INVALID", "SUPPRESSED"}
PROJECTION_ROOT_SNAPSHOT_STATES = {"NOT_SAMPLED", "COMPLETE", "UNAVAILABLE", "SUPPRESSED"}
PROJECTION_STDOUT_STATES = {"NOT_EMITTED", "EMITTED"}
PROJECTION_SUFFIX = ".forced-signal-projection.json"
MAX_PROJECTION_BYTES = 4096
PORT_RESERVATION_DIRECTORY_NAME = ".port-reservations"
PORT_RESERVATION_SUFFIX = ".ports"
PORT_RESERVATION_SCHEMA_VERSION = "1"
PORT_RESERVATION_FIELDS = (
    "INT001_PORT_RESERVATION_SCHEMA",
    "INT001_RUN_ID",
    "INT001_NAVIGATOR_PORT",
    "INT001_MYSQL_PORT",
    "INT001_MOCK_LLM_PORT",
    "INT001_BIZ_PORT",
    "INT001_BIZ_INGRESS_PROXY_PORT",
    "INT001_DIRECTORY_FACADE_PORT",
)
PORT_RESERVATION_PORT_FIELDS = PORT_RESERVATION_FIELDS[2:]
MAX_PORT_RESERVATION_BYTES = 4096
SOCKET_LISTENER_ABSENT = "socket-listener-absent"
SOCKET_LISTENER_AMBIGUOUS = "socket-listener-ambiguous"
SOCKET_LISTENER_NONLOOPBACK_OR_IPV6 = "socket-listener-nonloopback-or-ipv6"
SOCKET_LISTENER_PROC_UNAVAILABLE = "socket-listener-proc-unavailable"
SOCKET_LISTENER_PROC_MALFORMED = "socket-listener-proc-malformed"
PROC_TCP_IPV4_LOOPBACK = "0100007F"
PROC_TCP6_IPV4_MAPPED_LOOPBACK = "0000000000000000FFFF00000100007F"
LISTENER_CANDIDATE_ABSENT = "listener-candidate-absent"
LISTENER_CANDIDATE_AMBIGUOUS = "listener-candidate-ambiguous"
LISTENER_CANDIDATE_PROC_UNAVAILABLE = "listener-candidate-proc-unavailable"
LISTENER_CANDIDATE_PROC_MALFORMED = "listener-candidate-proc-malformed"
IDENTITY_MATCH = "MATCH"
IDENTITY_MISMATCH = "MISMATCH"
IDENTITY_PROC_UNAVAILABLE = "PROC_UNAVAILABLE"
IDENTITY_PROC_MALFORMED = "PROC_MALFORMED"
LISTENER_IDENTITY_NOT_OBSERVED = "NOT_OBSERVED"
LISTENER_IDENTITY_NO_TRUSTED_JAVA = "NO_TRUSTED_JAVA_CANDIDATE"
LISTENER_IDENTITY_NO_EXACT_ARGV = "NO_EXACT_ARGV_MATCH"
LISTENER_IDENTITY_CWD_MISMATCH = "ARGV_MATCH_CWD_MISMATCH"
LISTENER_IDENTITY_EXE_MISMATCH = "ARGV_CWD_MATCH_EXE_MISMATCH"
LISTENER_IDENTITY_LINEAGE_MISMATCH = "ARGV_CWD_EXE_MATCH_LINEAGE_MISMATCH"
LISTENER_IDENTITY_STABILITY_MISMATCH = "IDENTITY_STABILITY_MISMATCH"
LISTENER_IDENTITY_PROC_UNAVAILABLE = "PROC_UNAVAILABLE"
LISTENER_IDENTITY_PROC_MALFORMED = "PROC_MALFORMED"
LISTENER_IDENTITY_EXACT = "EXACT_CANDIDATE_FOUND"
LISTENER_IDENTITY_EXACT_AMBIGUOUS = "EXACT_CANDIDATE_AMBIGUOUS"
LISTENER_PROOF_STAGE_NOT_OBSERVED = "NOT_OBSERVED"
LISTENER_PROOF_STAGE_EXACT_IDENTITY = "EXACT_IDENTITY_FOUND"
LISTENER_PROOF_STAGE_SOCKET_FOUND = "LISTENER_SOCKET_FOUND"
LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP = "INITIAL_OWNERSHIP_PROVED"
LISTENER_PROOF_STAGE_FULL_ELIGIBLE = "FULL_ELIGIBLE"
EXACT_PARENT_PROOF_REASON = "commandLine+cwd+runId+uid+session+startTicks"
EXACT_LISTENER_PROOF_REASON = "uid+java+argv+cwd+ancestor+socket+startTicks"
FORCED_SIGNAL_OUTER_EXIT_CODE = 128
LISTENER_IDENTITY_DIAGNOSTICS = frozenset(
    {
        LISTENER_IDENTITY_NOT_OBSERVED,
        LISTENER_IDENTITY_NO_TRUSTED_JAVA,
        LISTENER_IDENTITY_NO_EXACT_ARGV,
        LISTENER_IDENTITY_CWD_MISMATCH,
        LISTENER_IDENTITY_EXE_MISMATCH,
        LISTENER_IDENTITY_LINEAGE_MISMATCH,
        LISTENER_IDENTITY_STABILITY_MISMATCH,
        LISTENER_IDENTITY_PROC_UNAVAILABLE,
        LISTENER_IDENTITY_PROC_MALFORMED,
        LISTENER_IDENTITY_EXACT,
        LISTENER_IDENTITY_EXACT_AMBIGUOUS,
    }
)
LISTENER_IDENTITY_DIAGNOSTIC_RANK = {
    LISTENER_IDENTITY_NOT_OBSERVED: 0,
    LISTENER_IDENTITY_NO_TRUSTED_JAVA: 1,
    LISTENER_IDENTITY_NO_EXACT_ARGV: 2,
    LISTENER_IDENTITY_CWD_MISMATCH: 3,
    LISTENER_IDENTITY_EXE_MISMATCH: 4,
    LISTENER_IDENTITY_LINEAGE_MISMATCH: 5,
    LISTENER_IDENTITY_STABILITY_MISMATCH: 6,
    LISTENER_IDENTITY_EXACT: 7,
    LISTENER_IDENTITY_EXACT_AMBIGUOUS: 8,
    LISTENER_IDENTITY_PROC_UNAVAILABLE: 9,
    LISTENER_IDENTITY_PROC_MALFORMED: 10,
}
LISTENER_PROOF_STAGES = frozenset(
    {
        LISTENER_PROOF_STAGE_NOT_OBSERVED,
        LISTENER_PROOF_STAGE_EXACT_IDENTITY,
        LISTENER_PROOF_STAGE_SOCKET_FOUND,
        LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
        LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
    }
)
LISTENER_PROOF_STAGE_RANK = {
    LISTENER_PROOF_STAGE_NOT_OBSERVED: 0,
    LISTENER_PROOF_STAGE_EXACT_IDENTITY: 1,
    LISTENER_PROOF_STAGE_SOCKET_FOUND: 2,
    LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP: 3,
    LISTENER_PROOF_STAGE_FULL_ELIGIBLE: 4,
}
SIGNAL_LABELS = {signal.SIGHUP: "HUP", signal.SIGINT: "INT", signal.SIGTERM: "TERM"}
CONTROL_SIGNALS = frozenset(SIGNAL_LABELS)
CONTROL_SIGNAL_ORDER = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)

# A signal to the supervisor is never reinterpreted as the controlled parent
# TERM. The handler only makes the ongoing rehearsal ineligible and lets the
# main loop emit one redacted summary. The isolated exercise session is not
# signalled before the required health precondition.
SUPERVISOR_INTERRUPTION: str | None = None


@dataclass(frozen=True)
class ParentProof:
    ok: bool
    reason: str
    start_ticks: int | None = None


@dataclass(frozen=True)
class ListenerProof:
    """A redaction-safe proof that the health listener is this run's Launcher.

    The process/socket identifiers remain in memory only.  The caller may
    retain the fixed reason enum in its summary, but must never write PID,
    argv, inode, cwd, or FD information into a rehearsal artifact.
    """

    ok: bool
    reason: str
    pid: int | None = None
    start_ticks: int | None = None
    socket_inode: int | None = None
    identity_diagnostic: str = LISTENER_IDENTITY_NOT_OBSERVED
    proof_stage_diagnostic: str = LISTENER_PROOF_STAGE_NOT_OBSERVED


@dataclass(frozen=True)
class ListenerCandidate:
    """One exact current-run Launcher process, retained only in memory."""

    pid: int
    start_ticks: int


@dataclass(frozen=True)
class ListenerCandidateIdentityProbe:
    """Tri-state-plus-malformed result for one procfs identity inspection."""

    status: str
    candidate: ListenerCandidate | None = None
    identity_diagnostic: str = LISTENER_IDENTITY_NOT_OBSERVED


@dataclass(frozen=True, order=True)
class DescendantDomainIdentity:
    """One stable process edge in the exercise-parent descendant domain."""

    pid: int
    parent_pid: int
    start_ticks: int


@dataclass(frozen=True)
class RunRootSnapshot:
    private_absent: bool | None
    nonreceipt_residue_count: int | None


@dataclass(frozen=True)
class RehearsalOutcome:
    health_precondition: bool
    parent_proof: ParentProof | None
    term_dispatches: int
    child_exit: int | None
    listener_proof: ListenerProof | None = None
    dispatch_safe: bool = False
    listener_proof_ever_eligible: bool = False
    furthest_listener_identity_diagnostic: str = LISTENER_IDENTITY_NOT_OBSERVED
    furthest_listener_proof_stage_diagnostic: str = LISTENER_PROOF_STAGE_NOT_OBSERVED


@dataclass
class ExecutionProjectionWriter:
    """Keep one non-authoritative, fixed-enum execution projection open.

    The file is outside the run directory so it cannot change the cleanup
    residue contract. A partial or malformed update is diagnostic failure,
    never permission to signal or clean anything.
    """

    descriptor: int
    run_id: str

    def write(
        self,
        *,
        phase: str,
        outcome: str,
        receipt_state: str,
        root_snapshot_state: str,
        stdout_summary_state: str,
    ) -> None:
        value = execution_projection_value(
            run_id=self.run_id,
            phase=phase,
            outcome=outcome,
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        raw = (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
        if len(raw) > MAX_PROJECTION_BYTES:
            raise RuntimeError("execution projection exceeds its fixed size bound")
        os.lseek(self.descriptor, 0, os.SEEK_SET)
        os.ftruncate(self.descriptor, 0)
        remaining = memoryview(raw)
        while remaining:
            written = os.write(self.descriptor, remaining)
            if written <= 0:
                raise RuntimeError("execution projection write failed")
            remaining = remaining[written:]
        os.fsync(self.descriptor)

    def close(self) -> None:
        os.close(self.descriptor)


def repository_root() -> Path:
    root = Path(__file__).resolve().parents[3]
    if not (root / "pom.xml").is_file():
        raise RuntimeError("repository root cannot be verified")
    return root


def execution_projection_path(artifact_root: Path, run_id: str) -> Path:
    return artifact_root / f"{run_id}{PROJECTION_SUFFIX}"


def execution_projection_value(
    *,
    run_id: str,
    phase: str,
    outcome: str,
    receipt_state: str,
    root_snapshot_state: str,
    stdout_summary_state: str,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "schemaVersion": 1,
        "runId": run_id,
        "phase": phase,
        "outcome": outcome,
        "receiptState": receipt_state,
        "rootSnapshotState": root_snapshot_state,
        "stdoutSummaryState": stdout_summary_state,
        "secretsRedacted": True,
    }
    if not valid_execution_projection(value, run_id):
        raise RuntimeError("execution projection contains an invalid fixed enum")
    return value


def valid_execution_projection(value: Any, run_id: str) -> bool:
    return (
        isinstance(value, dict)
        and set(value) == PROJECTION_FIELDS
        and type(value.get("schemaVersion")) is int
        and value["schemaVersion"] == 1
        and value.get("runId") == run_id
        and value.get("secretsRedacted") is True
        and isinstance(value.get("phase"), str)
        and value["phase"] in PROJECTION_PHASES
        and isinstance(value.get("outcome"), str)
        and value["outcome"] in PROJECTION_OUTCOMES
        and isinstance(value.get("receiptState"), str)
        and value["receiptState"] in PROJECTION_RECEIPT_STATES
        and isinstance(value.get("rootSnapshotState"), str)
        and value["rootSnapshotState"] in PROJECTION_ROOT_SNAPSHOT_STATES
        and isinstance(value.get("stdoutSummaryState"), str)
        and value["stdoutSummaryState"] in PROJECTION_STDOUT_STATES
    )


def open_execution_projection(artifact_root: Path, run_id: str) -> ExecutionProjectionWriter:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None or not safe_directory(artifact_root, 0o700):
        raise RuntimeError("execution projection root is unsafe")
    path = execution_projection_path(artifact_root, run_id)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | nofollow
    descriptor = os.open(path, flags, 0o600)
    try:
        os.fchmod(descriptor, 0o600)
        details = os.fstat(descriptor)
        if (
            not stat.S_ISREG(details.st_mode)
            or details.st_uid != os.getuid()
            or stat.S_IMODE(details.st_mode) != 0o600
            or details.st_nlink != 1
        ):
            raise RuntimeError("execution projection file is unsafe")
        writer = ExecutionProjectionWriter(descriptor, run_id)
        writer.write(
            phase="SUPERVISOR_STARTED",
            outcome="IN_PROGRESS",
            receipt_state="NOT_SAMPLED",
            root_snapshot_state="NOT_SAMPLED",
            stdout_summary_state="NOT_EMITTED",
        )
        return writer
    except BaseException:
        os.close(descriptor)
        raise


def read_execution_projection(
    repo_root: Path,
    artifact_root: Path,
    run_id: str,
) -> dict[str, Any] | None:
    """Read only the allow-listed artifact-root execution projection."""
    if not artifact_root_is_safe(repo_root, artifact_root):
        return None
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        return None
    descriptor: int | None = None
    try:
        descriptor = os.open(
            execution_projection_path(artifact_root, run_id),
            os.O_RDONLY | os.O_CLOEXEC | nofollow,
        )
        details = os.fstat(descriptor)
        if (
            not stat.S_ISREG(details.st_mode)
            or details.st_uid != os.getuid()
            or stat.S_IMODE(details.st_mode) != 0o600
            or details.st_nlink != 1
        ):
            return None
        raw = os.read(descriptor, MAX_PROJECTION_BYTES + 1)
        if len(raw) > MAX_PROJECTION_BYTES:
            return None
        value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=reject_duplicate_json_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
        return None
    finally:
        if descriptor is not None:
            os.close(descriptor)
    return value if valid_execution_projection(value, run_id) else None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run one fresh, loopback-only INT-001 parent-TERM rehearsal.",
        allow_abbrev=False,
    )
    parser.add_argument("--run-id", required=True, help="new lower-case INT-001 run identifier")
    parser.add_argument(
        "--navigator-port",
        type=int,
        help="optional loopback-only Navigator port; defaults to a bounded fresh allocation",
    )
    parser.add_argument(
        "--health-timeout-seconds",
        type=int,
        default=900,
        help="bounded health wait before the run is recorded as ineligible (30-1800)",
    )
    parser.add_argument(
        "--post-term-timeout-seconds",
        type=int,
        default=120,
        help="bounded wait after the one allowed TERM (30-300)",
    )
    args = parser.parse_args()
    if not RUN_ID_RE.fullmatch(args.run_id) or "--" in args.run_id or args.run_id.endswith("-"):
        parser.error("--run-id must match [a-z0-9][a-z0-9-]{5,63} without repeated/trailing '-'")
    for label, value, lower, upper in (
        ("--health-timeout-seconds", args.health_timeout_seconds, 30, 1800),
        ("--post-term-timeout-seconds", args.post_term_timeout_seconds, 30, 300),
    ):
        if not lower <= value <= upper:
            parser.error(f"{label} must be between {lower} and {upper}")
    if args.navigator_port is not None:
        validate_navigator_port(args.navigator_port, parser)
    return args


def validate_navigator_port(port: int, parser: argparse.ArgumentParser | None = None) -> None:
    message: str | None = None
    if not DYNAMIC_PORT_MIN <= port <= DYNAMIC_PORT_MAX:
        message = f"Navigator port must be within the disposable range {DYNAMIC_PORT_MIN}-{DYNAMIC_PORT_MAX}"
    elif port in RESERVED_PORTS:
        message = "Navigator port is reserved by an existing local stack"
    if message:
        if parser is not None:
            parser.error(message)
        raise RuntimeError(message)


def choose_navigator_port() -> int:
    for _ in range(128):
        port = DYNAMIC_PORT_MIN + secrets.randbelow(DYNAMIC_PORT_MAX - DYNAMIC_PORT_MIN + 1)
        if port in RESERVED_PORTS:
            continue
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
            candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
            try:
                candidate.bind(("127.0.0.1", port))
            except OSError:
                continue
        return port
    raise RuntimeError("could not allocate a disposable loopback Navigator port")


def minimal_child_environment() -> dict[str, str]:
    home = pwd.getpwuid(os.getuid()).pw_dir
    home_path = Path(home)
    if not home or not home_path.is_dir() or home_path.is_symlink() or home_path.stat().st_uid != os.getuid():
        raise RuntimeError("current user home cannot be safely resolved")
    return {"PATH": SAFE_PATH, "HOME": home}


def mark_supervisor_interruption(signum: int, _frame: Any) -> None:
    global SUPERVISOR_INTERRUPTION
    SUPERVISOR_INTERRUPTION = SIGNAL_LABELS.get(signum, "UNKNOWN")


def install_supervisor_signal_handlers() -> None:
    for signum in SIGNAL_LABELS:
        signal.signal(signum, mark_supervisor_interruption)


def safe_directory(path: Path, mode: int) -> bool:
    try:
        details = path.lstat()
        return (
            stat.S_ISDIR(details.st_mode)
            and not path.is_symlink()
            and details.st_uid == os.getuid()
            and stat.S_IMODE(details.st_mode) == mode
            and details.st_nlink >= 2
        )
    except OSError:
        return False


def artifact_root_is_safe(repo_root: Path, artifact_root: Path) -> bool:
    expected = repo_root
    try:
        if not expected.is_dir() or expected.is_symlink():
            return False
        for component in ("temp", "test-artifacts", "INT-001"):
            expected = expected / component
            details = expected.lstat()
            if not stat.S_ISDIR(details.st_mode) or expected.is_symlink():
                return False
    except OSError:
        return False
    return expected == artifact_root and safe_directory(artifact_root, 0o700)


def safe_run_directory(run_dir: Path, artifact_root: Path | None = None) -> bool:
    if artifact_root is None:
        return False
    return run_dir.parent == artifact_root and safe_directory(artifact_root, 0o700) and safe_directory(run_dir, 0o700)


def assert_artifact_root(repo_root: Path) -> Path:
    artifact_root = repo_root / "temp" / "test-artifacts" / "INT-001"
    if not artifact_root_is_safe(repo_root, artifact_root):
        raise RuntimeError("INT-001 artifact root is unsafe")
    return artifact_root


def port_is_unused(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("127.0.0.1", port)) != 0


def proc_stat_fields(pid: int) -> list[str]:
    raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    separator = raw.rfind(")")
    if separator < 0:
        raise RuntimeError("malformed proc stat")
    fields = raw[separator + 2 :].split()
    if len(fields) <= 19:
        raise RuntimeError("incomplete proc stat")
    return fields


def proc_stat(pid: int) -> tuple[int, int, int]:
    fields = proc_stat_fields(pid)
    return int(fields[2]), int(fields[3]), int(fields[19])  # pgrp, session, start ticks


def proc_parent_pid(pid: int) -> int:
    return int(proc_stat_fields(pid)[1])


def proc_is_live(pid: int) -> bool:
    try:
        return proc_stat_fields(pid)[0] != "Z"
    except (OSError, ValueError, RuntimeError):
        return False


def trusted_java_executable() -> Path | None:
    """Resolve the same fixed Java executable that the harness verifies."""
    try:
        resolved = TRUSTED_JAVA_LINK.resolve(strict=True)
        details = resolved.lstat()
        if resolved.is_symlink() or not stat.S_ISREG(details.st_mode) or not os.access(resolved, os.X_OK):
            return None
        return resolved
    except OSError:
        return None


def expected_launcher_argv(repo_root: Path, run_id: str) -> list[str] | None:
    java = trusted_java_executable()
    launcher_jar = repo_root / "launcher" / "target" / "launcher-1.0.0-SNAPSHOT.jar"
    try:
        details = launcher_jar.lstat()
        if java is None or launcher_jar.is_symlink() or not stat.S_ISREG(details.st_mode):
            return None
    except OSError:
        return None
    # The runId is intentionally non-secret.  It is a correlation guard for
    # this supervisor only and is never copied to the root cleanup receipt.
    return [
        str(java),
        f"-Dint001.run-id={run_id}",
        "-jar",
        str(launcher_jar),
        "--spring.profiles.active=mock",
    ]


def listener_socket_probe_from_tables(
    port: int,
    tcp_table: Path,
    tcp6_table: Path,
) -> tuple[int | None, str]:
    """Return one strict loopback LISTEN inode from an explicit procfs view."""
    if not 1 <= port <= 65535:
        return None, SOCKET_LISTENER_ABSENT
    port_hex = f"{port:04X}"
    expected_inodes: list[int] = []
    for proc_net in (tcp_table, tcp6_table):
        try:
            lines = proc_net.read_text(encoding="utf-8").splitlines()
        except OSError:
            return None, SOCKET_LISTENER_PROC_UNAVAILABLE
        except UnicodeDecodeError:
            return None, SOCKET_LISTENER_PROC_MALFORMED
        if not lines:
            return None, SOCKET_LISTENER_PROC_MALFORMED
        for raw in lines[1:]:
            fields = raw.split()
            if len(fields) < 10:
                return None, SOCKET_LISTENER_PROC_MALFORMED
            local_address = fields[1]
            state = fields[3]
            address, separator, observed_port = local_address.rpartition(":")
            if (
                not separator
                or len(address) != (8 if proc_net.name == "tcp" else 32)
                or any(character not in "0123456789ABCDEFabcdef" for character in address)
                or len(observed_port) != 4
                or any(character not in "0123456789ABCDEFabcdef" for character in observed_port)
                or len(state) != 2
                or any(character not in "0123456789ABCDEFabcdef" for character in state)
                or not fields[9].isdigit()
            ):
                return None, SOCKET_LISTENER_PROC_MALFORMED
            if state != "0A" or observed_port.upper() != port_hex:
                continue
            # Tomcat opens the JDK-default server channel. On an IPv6-capable
            # Linux host that may carry an exact 127.0.0.1 bind as the
            # canonical IPv4-mapped address in tcp6. Accept only those two
            # procfs representations; native IPv6, wildcard, and any other
            # mapped address remain ineligible.
            expected_address = (
                PROC_TCP_IPV4_LOOPBACK
                if proc_net.name == "tcp"
                else PROC_TCP6_IPV4_MAPPED_LOOPBACK
            )
            if address.upper() != expected_address:
                return None, SOCKET_LISTENER_NONLOOPBACK_OR_IPV6
            expected_inodes.append(int(fields[9]))
    if not expected_inodes:
        return None, SOCKET_LISTENER_ABSENT
    if len(expected_inodes) != 1:
        return None, SOCKET_LISTENER_AMBIGUOUS
    return expected_inodes[0], "socket-listener"


def listener_socket_probe_for_loopback_port(port: int) -> tuple[int | None, str]:
    """Compatibility probe using the supervisor's own network namespace."""
    return listener_socket_probe_from_tables(port, Path("/proc/net/tcp"), Path("/proc/net/tcp6"))


def listener_socket_probe_for_candidate(port: int, pid: int) -> tuple[int | None, str]:
    """Inspect the already-proven candidate's network namespace, not ours."""
    return listener_socket_probe_from_tables(
        port,
        Path(f"/proc/{pid}/net/tcp"),
        Path(f"/proc/{pid}/net/tcp6"),
    )


def listener_inode_for_loopback_port(port: int) -> int | None:
    """Compatibility helper for callers that need only the safe inode."""
    return listener_socket_probe_for_loopback_port(port)[0]


def current_uid_socket_holders(socket_inode: int) -> tuple[int, ...] | None:
    """Map a socket inode to stable, inspectable current-user process holders."""
    target = f"socket:[{socket_inode}]"
    holders: list[int] = []
    try:
        proc_entries = tuple(Path("/proc").iterdir())
    except OSError:
        return None
    for proc_dir in proc_entries:
        if not proc_dir.name.isdecimal():
            continue
        try:
            if proc_dir.stat().st_uid != os.getuid():
                continue
            fd_entries = tuple((proc_dir / "fd").iterdir())
        except OSError:
            # A current-user process that remains present but cannot be
            # inspected prevents an exclusive ownership conclusion.
            if proc_dir.exists():
                return None
            continue
        has_socket = False
        for fd in fd_entries:
            try:
                if os.readlink(fd) == target:
                    has_socket = True
            except OSError:
                if fd.exists():
                    return None
        if has_socket:
            holders.append(int(proc_dir.name))
    return tuple(sorted(holders))


def process_socket_holder_status(pid: int, socket_inode: int) -> bool | None:
    """Return exact-inode ownership, or None when this process FD view is incomplete."""
    target = f"socket:[{socket_inode}]"
    fd_root = Path(f"/proc/{pid}/fd")
    try:
        fd_entries = tuple(fd_root.iterdir())
    except OSError:
        return None
    found = False
    for fd in fd_entries:
        try:
            if os.readlink(fd) == target:
                found = True
        except OSError:
            # The caller decides whether this PID is inside or outside the
            # run-owned domain.  Never reinterpret an unreadable FD as a
            # disappeared FD here: in-domain uncertainty must fail closed,
            # while unrelated out-of-domain uncertainty may be ignored only
            # by the bounded holder proof below.
            return None
    return found


def readable_current_uid_process_ids() -> tuple[int, ...] | None:
    """Return readable current-user proc entries, omitting unrelated uncertainty."""
    try:
        proc_entries = tuple(Path("/proc").iterdir())
    except OSError:
        return None
    pids: list[int] = []
    for proc_dir in proc_entries:
        if not proc_dir.name.isdecimal():
            continue
        try:
            if proc_dir.stat().st_uid == os.getuid():
                pids.append(int(proc_dir.name))
        except OSError:
            continue
    return tuple(sorted(pids))


def run_owned_socket_holder_is_exclusive(
    *,
    socket_inode: int,
    candidate: ListenerCandidate,
    exercise_pid: int,
    exercise_start_ticks: int,
) -> bool:
    """Prove exact holder exclusivity in the stable run-owned process domain.

    In-domain procfs and FD views are mandatory. Readable out-of-domain
    current-user processes still veto an observed shared holder, while an
    unrelated unreadable host process cannot make the owned domain unknown.
    The local disposable harness threat model already trusts the single
    same-UID operator; this function does not grant authority outside the
    exact parent/candidate lineage.
    """
    initial_domain, status = stable_exercise_descendant_domain(exercise_pid, exercise_start_ticks)
    if status != IDENTITY_MATCH or initial_domain is None:
        return False
    domain_by_pid = {identity.pid: identity for identity in initial_domain}
    candidate_identity = domain_by_pid.get(candidate.pid)
    if candidate_identity is None or candidate_identity.start_ticks != candidate.start_ticks:
        return False

    in_domain_holders: list[int] = []
    for identity in initial_domain:
        holder = process_socket_holder_status(identity.pid, socket_inode)
        if holder is None:
            return False
        if holder:
            in_domain_holders.append(identity.pid)
    if in_domain_holders != [candidate.pid]:
        return False

    readable_current_uid_pids = readable_current_uid_process_ids()
    if readable_current_uid_pids is None:
        return False
    for pid in readable_current_uid_pids:
        if pid in domain_by_pid:
            continue
        holder = process_socket_holder_status(pid, socket_inode)
        if holder is True:
            return False

    final_domain, status = stable_exercise_descendant_domain(exercise_pid, exercise_start_ticks)
    return status == IDENTITY_MATCH and final_domain == initial_domain


def command_line(pid: int) -> list[str] | None:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
        if not raw.endswith(b"\0"):
            return None
        return [part.decode("utf-8", "surrogateescape") for part in raw.split(b"\0")[:-1]]
    except (OSError, UnicodeDecodeError):
        return None


def proc_identity_failure_status(pid: int, *, malformed: bool) -> str:
    """Classify a failed procfs read without treating uncertainty as absence."""
    try:
        details = Path(f"/proc/{pid}").stat()
    except FileNotFoundError:
        return IDENTITY_MISMATCH
    except OSError:
        return IDENTITY_PROC_UNAVAILABLE
    if details.st_uid != os.getuid():
        return IDENTITY_MISMATCH
    return IDENTITY_PROC_MALFORMED if malformed else IDENTITY_PROC_UNAVAILABLE


def proc_identity_snapshot(pid: int) -> tuple[tuple[str, int, int] | None, str]:
    """Read state, parent PID, and start ticks with explicit failure classes."""
    try:
        if Path(f"/proc/{pid}").stat().st_uid != os.getuid():
            return None, IDENTITY_MISMATCH
    except FileNotFoundError:
        return None, IDENTITY_MISMATCH
    except OSError:
        return None, IDENTITY_PROC_UNAVAILABLE
    try:
        fields = proc_stat_fields(pid)
        state = fields[0]
        parent_pid = int(fields[1])
        start_ticks = int(fields[19])
        if state not in set("RSDZTtXxKWPI") or parent_pid < 0 or start_ticks <= 0:
            return None, proc_identity_failure_status(pid, malformed=True)
        return (state, parent_pid, start_ticks), IDENTITY_MATCH
    except FileNotFoundError:
        return None, proc_identity_failure_status(pid, malformed=False)
    except OSError:
        return None, proc_identity_failure_status(pid, malformed=False)
    except (UnicodeDecodeError, ValueError, RuntimeError, IndexError):
        return None, proc_identity_failure_status(pid, malformed=True)


def command_line_identity_probe(pid: int) -> tuple[list[str] | None, str]:
    """Read one exact argv, distinguishing a race from unsafe procfs data."""
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except FileNotFoundError:
        return None, proc_identity_failure_status(pid, malformed=False)
    except OSError:
        return None, proc_identity_failure_status(pid, malformed=False)
    if not raw or not raw.endswith(b"\0"):
        return None, proc_identity_failure_status(pid, malformed=True)
    try:
        return [part.decode("utf-8", "strict") for part in raw.split(b"\0")[:-1]], IDENTITY_MATCH
    except UnicodeDecodeError:
        return None, proc_identity_failure_status(pid, malformed=True)


def proc_link_identity_probe(pid: int, name: str) -> tuple[Path | None, str]:
    """Resolve one candidate procfs link without hiding read or shape errors."""
    try:
        return Path(f"/proc/{pid}/{name}").resolve(strict=True), IDENTITY_MATCH
    except FileNotFoundError:
        return None, proc_identity_failure_status(pid, malformed=False)
    except OSError:
        return None, proc_identity_failure_status(pid, malformed=False)
    except RuntimeError:
        return None, proc_identity_failure_status(pid, malformed=True)


def launcher_lineage_identity_status(pid: int, ancestor_pid: int, ancestor_start_ticks: int) -> str:
    """Prove the candidate lineage with the same explicit procfs failure states."""
    current = pid
    seen: set[int] = set()
    for _ in range(64):
        if current <= 1:
            return IDENTITY_MISMATCH
        if current in seen:
            return IDENTITY_PROC_MALFORMED
        seen.add(current)
        snapshot, status = proc_identity_snapshot(current)
        if status != IDENTITY_MATCH or snapshot is None:
            return status
        state, parent_pid, start_ticks = snapshot
        if state == "Z":
            return IDENTITY_MISMATCH
        if current == ancestor_pid:
            return IDENTITY_MATCH if start_ticks == ancestor_start_ticks else IDENTITY_MISMATCH
        current = parent_pid
    return IDENTITY_MISMATCH


def identity_failure_diagnostic(status: str, mismatch_diagnostic: str) -> str:
    if status == IDENTITY_PROC_MALFORMED:
        return LISTENER_IDENTITY_PROC_MALFORMED
    if status == IDENTITY_PROC_UNAVAILABLE:
        return LISTENER_IDENTITY_PROC_UNAVAILABLE
    return mismatch_diagnostic


def furthest_identity_diagnostic(current: str, observed: str) -> str:
    """Merge only allow-listed stages using a deterministic fail-closed order."""
    safe_current = current if current in LISTENER_IDENTITY_DIAGNOSTICS else LISTENER_IDENTITY_NOT_OBSERVED
    safe_observed = observed if observed in LISTENER_IDENTITY_DIAGNOSTICS else LISTENER_IDENTITY_NOT_OBSERVED
    if LISTENER_IDENTITY_DIAGNOSTIC_RANK[safe_observed] > LISTENER_IDENTITY_DIAGNOSTIC_RANK[safe_current]:
        return safe_observed
    return safe_current


def furthest_temporal_identity_diagnostic(current: str, observed: str) -> str:
    """Retain the furthest identity progress observed across supervision time.

    Discovery within one snapshot keeps procfs uncertainty above a readable
    candidate because that snapshot cannot authorize a signal.  Across time,
    however, a previously completed exact identity proof is durable redacted
    diagnostic evidence and must not be overwritten by cleanup-time absence or
    procfs failure.  A later exact proof likewise supersedes an earlier failure.
    """
    safe_current = current if current in LISTENER_IDENTITY_DIAGNOSTICS else LISTENER_IDENTITY_NOT_OBSERVED
    safe_observed = observed if observed in LISTENER_IDENTITY_DIAGNOSTICS else LISTENER_IDENTITY_NOT_OBSERVED
    if safe_current == LISTENER_IDENTITY_EXACT or safe_observed == LISTENER_IDENTITY_EXACT:
        return LISTENER_IDENTITY_EXACT
    return furthest_identity_diagnostic(safe_current, safe_observed)


def redacted_listener_proof_stage(listener_proof: ListenerProof | None) -> str:
    """Return only the fixed non-authorizing proof-stage enum."""
    if listener_proof is None or listener_proof.proof_stage_diagnostic not in LISTENER_PROOF_STAGES:
        return LISTENER_PROOF_STAGE_NOT_OBSERVED
    return listener_proof.proof_stage_diagnostic


def furthest_temporal_listener_proof_stage(current: str, observed: str) -> str:
    """Retain the furthest safe listener stage reached across supervision time."""
    safe_current = current if current in LISTENER_PROOF_STAGES else LISTENER_PROOF_STAGE_NOT_OBSERVED
    safe_observed = observed if observed in LISTENER_PROOF_STAGES else LISTENER_PROOF_STAGE_NOT_OBSERVED
    if LISTENER_PROOF_STAGE_RANK[safe_observed] > LISTENER_PROOF_STAGE_RANK[safe_current]:
        return safe_observed
    return safe_current


def proc_task_ids(pid: int) -> tuple[tuple[int, ...] | None, str]:
    """Read one bounded current-user task set for an in-domain process."""
    task_root = Path(f"/proc/{pid}/task")
    try:
        task_entries = tuple(task_root.iterdir())
    except FileNotFoundError:
        return None, IDENTITY_MISMATCH
    except OSError:
        return None, IDENTITY_PROC_UNAVAILABLE

    task_ids: list[int] = []
    for task_dir in task_entries:
        if not task_dir.name.isdecimal():
            continue
        if len(task_ids) >= MAX_DESCENDANT_DOMAIN_TASKS:
            return None, IDENTITY_PROC_MALFORMED
        try:
            if task_dir.stat().st_uid != os.getuid():
                return None, IDENTITY_PROC_MALFORMED
        except FileNotFoundError:
            return None, IDENTITY_MISMATCH
        except OSError:
            return None, IDENTITY_PROC_UNAVAILABLE
        task_ids.append(int(task_dir.name))
    if not task_ids:
        return None, proc_identity_failure_status(pid, malformed=True)
    return tuple(sorted(task_ids)), IDENTITY_MATCH


def proc_task_child_pids(pid: int, task_id: int) -> tuple[tuple[int, ...] | None, str]:
    """Read one task's child PID list with strict fixed-shape validation."""
    children_path = Path(f"/proc/{pid}/task/{task_id}/children")
    try:
        raw_children = children_path.read_bytes()
    except FileNotFoundError:
        return None, IDENTITY_MISMATCH
    except OSError:
        return None, IDENTITY_PROC_UNAVAILABLE
    try:
        text_children = raw_children.decode("ascii", "strict").strip()
    except UnicodeDecodeError:
        return None, IDENTITY_PROC_MALFORMED
    if not text_children:
        return (), IDENTITY_MATCH

    children: set[int] = set()
    for value in text_children.split():
        if not value.isdecimal():
            return None, IDENTITY_PROC_MALFORMED
        child_pid = int(value)
        if child_pid <= 1:
            return None, IDENTITY_PROC_MALFORMED
        children.add(child_pid)
        if len(children) > MAX_DESCENDANT_DOMAIN_PROCESSES:
            return None, IDENTITY_PROC_MALFORMED
    return tuple(sorted(children)), IDENTITY_MATCH


def proc_task_children(pid: int) -> tuple[tuple[int, ...] | None, str]:
    """Read every thread's children and require a stable task set."""
    initial_task_ids, status = proc_task_ids(pid)
    if status != IDENTITY_MATCH or initial_task_ids is None:
        return None, status

    children: set[int] = set()
    for task_id in initial_task_ids:
        task_children, status = proc_task_child_pids(pid, task_id)
        if status != IDENTITY_MATCH or task_children is None:
            return None, status
        children.update(task_children)
        if len(children) > MAX_DESCENDANT_DOMAIN_PROCESSES:
            return None, IDENTITY_PROC_MALFORMED

    final_task_ids, status = proc_task_ids(pid)
    if status != IDENTITY_MATCH or final_task_ids is None:
        return None, status
    if final_task_ids != initial_task_ids:
        return None, IDENTITY_MISMATCH
    return tuple(sorted(children)), IDENTITY_MATCH


def exercise_descendant_domain_snapshot(
    exercise_pid: int,
    exercise_start_ticks: int,
) -> tuple[tuple[DescendantDomainIdentity, ...] | None, str]:
    """Collect one complete bounded descendant snapshot from a proven root."""
    root_snapshot, status = proc_identity_snapshot(exercise_pid)
    if status != IDENTITY_MATCH or root_snapshot is None:
        return None, status
    root_state, root_parent_pid, root_start_ticks = root_snapshot
    if root_state == "Z" or root_start_ticks != exercise_start_ticks:
        return None, IDENTITY_MISMATCH

    identities: dict[int, DescendantDomainIdentity] = {
        exercise_pid: DescendantDomainIdentity(exercise_pid, root_parent_pid, root_start_ticks)
    }
    queue: list[tuple[int, int]] = [(exercise_pid, 0)]
    queue_index = 0
    while queue_index < len(queue):
        pid, depth = queue[queue_index]
        queue_index += 1
        if depth > MAX_DESCENDANT_DOMAIN_DEPTH:
            return None, IDENTITY_PROC_MALFORMED
        expected = identities[pid]
        current_snapshot, status = proc_identity_snapshot(pid)
        if status != IDENTITY_MATCH or current_snapshot is None:
            return None, status
        state, parent_pid, start_ticks = current_snapshot
        if state == "Z" or parent_pid != expected.parent_pid or start_ticks != expected.start_ticks:
            return None, IDENTITY_MISMATCH

        child_pids, status = proc_task_children(pid)
        if status != IDENTITY_MATCH or child_pids is None:
            return None, status
        for child_pid in child_pids:
            existing = identities.get(child_pid)
            if existing is not None:
                if existing.parent_pid != pid:
                    return None, IDENTITY_PROC_MALFORMED
                continue
            if len(identities) >= MAX_DESCENDANT_DOMAIN_PROCESSES:
                return None, IDENTITY_PROC_MALFORMED
            child_snapshot, status = proc_identity_snapshot(child_pid)
            if status != IDENTITY_MATCH or child_snapshot is None:
                return None, status
            child_state, child_parent_pid, child_start_ticks = child_snapshot
            if child_state == "Z" or child_parent_pid != pid:
                return None, IDENTITY_MISMATCH
            identities[child_pid] = DescendantDomainIdentity(
                child_pid,
                child_parent_pid,
                child_start_ticks,
            )
            queue.append((child_pid, depth + 1))

    for identity in identities.values():
        final_snapshot, status = proc_identity_snapshot(identity.pid)
        if status != IDENTITY_MATCH or final_snapshot is None:
            return None, status
        final_state, final_parent_pid, final_start_ticks = final_snapshot
        if (
            final_state == "Z"
            or final_parent_pid != identity.parent_pid
            or final_start_ticks != identity.start_ticks
        ):
            return None, IDENTITY_MISMATCH
    return tuple(sorted(identities.values())), IDENTITY_MATCH


def stable_exercise_descendant_domain(
    exercise_pid: int,
    exercise_start_ticks: int,
) -> tuple[tuple[DescendantDomainIdentity, ...] | None, str]:
    """Require two identical complete snapshots within a bounded retry window.

    A JVM may add or retire a native thread while one complete task/children
    snapshot is being read. That transient mismatch cannot authorize a
    candidate, but it also must not permanently hide a later pair of complete,
    identical snapshots. Procfs unavailability or malformed data still fails
    immediately; every successful attempt retains the full task-set proof.
    """
    for _attempt in range(MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS):
        first, status = exercise_descendant_domain_snapshot(exercise_pid, exercise_start_ticks)
        if status in (IDENTITY_PROC_UNAVAILABLE, IDENTITY_PROC_MALFORMED):
            return None, status
        if status != IDENTITY_MATCH or first is None:
            if _attempt + 1 < MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS:
                time.sleep(STABLE_DESCENDANT_DOMAIN_RETRY_SECONDS)
            continue
        second, status = exercise_descendant_domain_snapshot(exercise_pid, exercise_start_ticks)
        if status in (IDENTITY_PROC_UNAVAILABLE, IDENTITY_PROC_MALFORMED):
            return None, status
        if status == IDENTITY_MATCH and second is not None and first == second:
            return first, IDENTITY_MATCH
        if _attempt + 1 < MAX_STABLE_DESCENDANT_DOMAIN_ATTEMPTS:
            time.sleep(STABLE_DESCENDANT_DOMAIN_RETRY_SECONDS)
    return None, IDENTITY_MISMATCH


def exact_launcher_candidate_identity(
    *,
    pid: int,
    expected_start_ticks: int | None,
    expected_argv: list[str],
    java: Path,
    run_dir: Path,
    exercise_pid: int,
    exercise_start_ticks: int,
) -> ListenerCandidateIdentityProbe:
    """Return an explicit stable-identity result for one Launcher candidate.

    Every field is re-read from procfs. A caller must repeat this proof after
    socket inspection so PID reuse or identity changes cannot authorize TERM.
    """
    initial_snapshot, status = proc_identity_snapshot(pid)
    if status != IDENTITY_MATCH or initial_snapshot is None:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(
                status,
                LISTENER_IDENTITY_NOT_OBSERVED,
            ),
        )
    state, _parent_pid, start_ticks = initial_snapshot
    if state == "Z":
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=(
                LISTENER_IDENTITY_STABILITY_MISMATCH
                if expected_start_ticks is not None
                else LISTENER_IDENTITY_NOT_OBSERVED
            ),
        )
    if expected_start_ticks is not None and start_ticks != expected_start_ticks:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_STABILITY_MISMATCH,
        )

    initial_executable, status = proc_link_identity_probe(pid, "exe")
    if status != IDENTITY_MATCH:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(
                status,
                LISTENER_IDENTITY_NO_TRUSTED_JAVA,
            ),
        )
    if initial_executable != java:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_NO_TRUSTED_JAVA,
        )

    argv, status = command_line_identity_probe(pid)
    if status != IDENTITY_MATCH:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(status, LISTENER_IDENTITY_NO_EXACT_ARGV),
        )
    if argv != expected_argv:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_NO_EXACT_ARGV,
        )

    cwd, status = proc_link_identity_probe(pid, "cwd")
    if status != IDENTITY_MATCH:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(status, LISTENER_IDENTITY_CWD_MISMATCH),
        )
    if cwd != run_dir:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_CWD_MISMATCH,
        )

    executable, status = proc_link_identity_probe(pid, "exe")
    if status != IDENTITY_MATCH:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(status, LISTENER_IDENTITY_EXE_MISMATCH),
        )
    if executable != java:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_EXE_MISMATCH,
        )

    status = launcher_lineage_identity_status(pid, exercise_pid, exercise_start_ticks)
    if status != IDENTITY_MATCH:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(status, LISTENER_IDENTITY_LINEAGE_MISMATCH),
        )

    final_snapshot, status = proc_identity_snapshot(pid)
    if status != IDENTITY_MATCH or final_snapshot is None:
        return ListenerCandidateIdentityProbe(
            status,
            identity_diagnostic=identity_failure_diagnostic(
                status,
                LISTENER_IDENTITY_STABILITY_MISMATCH,
            ),
        )
    final_state, _final_parent_pid, final_start_ticks = final_snapshot
    if final_state == "Z" or final_start_ticks != start_ticks:
        return ListenerCandidateIdentityProbe(
            IDENTITY_MISMATCH,
            identity_diagnostic=LISTENER_IDENTITY_STABILITY_MISMATCH,
        )
    return ListenerCandidateIdentityProbe(
        IDENTITY_MATCH,
        ListenerCandidate(pid, start_ticks),
        LISTENER_IDENTITY_EXACT,
    )


def find_exact_launcher_candidate(
    *,
    expected_argv: list[str],
    java: Path,
    run_dir: Path,
    exercise_pid: int,
    exercise_start_ticks: int,
) -> tuple[ListenerCandidate | None, str, str]:
    """Find exactly one Launcher in the stable exercise descendant domain."""
    domain, domain_status = stable_exercise_descendant_domain(
        exercise_pid,
        exercise_start_ticks,
    )
    if domain_status == IDENTITY_PROC_MALFORMED:
        return None, LISTENER_CANDIDATE_PROC_MALFORMED, LISTENER_IDENTITY_PROC_MALFORMED
    if domain_status == IDENTITY_PROC_UNAVAILABLE:
        return None, LISTENER_CANDIDATE_PROC_UNAVAILABLE, LISTENER_IDENTITY_PROC_UNAVAILABLE
    if domain_status != IDENTITY_MATCH or domain is None:
        return None, LISTENER_CANDIDATE_ABSENT, LISTENER_IDENTITY_STABILITY_MISMATCH

    candidates: list[ListenerCandidate] = []
    identity_diagnostic = LISTENER_IDENTITY_NOT_OBSERVED
    for domain_identity in domain:
        pid = domain_identity.pid
        identity = exact_launcher_candidate_identity(
            pid=pid,
            expected_start_ticks=domain_identity.start_ticks,
            expected_argv=expected_argv,
            java=java,
            run_dir=run_dir,
            exercise_pid=exercise_pid,
            exercise_start_ticks=exercise_start_ticks,
        )
        observed_diagnostic = identity.identity_diagnostic
        if identity.status == IDENTITY_PROC_UNAVAILABLE:
            observed_diagnostic = LISTENER_IDENTITY_PROC_UNAVAILABLE
        elif identity.status == IDENTITY_PROC_MALFORMED:
            observed_diagnostic = LISTENER_IDENTITY_PROC_MALFORMED
        elif identity.status == IDENTITY_MATCH:
            observed_diagnostic = LISTENER_IDENTITY_EXACT
        identity_diagnostic = furthest_identity_diagnostic(identity_diagnostic, observed_diagnostic)
        if identity.status == IDENTITY_MATCH and identity.candidate is not None:
            candidates.append(identity.candidate)
    if identity_diagnostic == LISTENER_IDENTITY_PROC_MALFORMED:
        return None, LISTENER_CANDIDATE_PROC_MALFORMED, identity_diagnostic
    if identity_diagnostic == LISTENER_IDENTITY_PROC_UNAVAILABLE:
        return None, LISTENER_CANDIDATE_PROC_UNAVAILABLE, identity_diagnostic
    if not candidates:
        return None, LISTENER_CANDIDATE_ABSENT, identity_diagnostic
    if len(candidates) != 1:
        return None, LISTENER_CANDIDATE_AMBIGUOUS, LISTENER_IDENTITY_EXACT_AMBIGUOUS
    return candidates[0], "listener-candidate", LISTENER_IDENTITY_EXACT


def candidate_holds_socket(pid: int, socket_inode: int) -> bool:
    """Require the exact candidate to retain at least one FD for the inode."""
    target = f"socket:[{socket_inode}]"
    fd_root = Path(f"/proc/{pid}/fd")
    try:
        fd_entries = tuple(fd_root.iterdir())
    except OSError:
        return False
    found = False
    for fd in fd_entries:
        try:
            if os.readlink(fd) == target:
                found = True
        except OSError:
            if fd.exists():
                return False
    return found


def is_descendant_of(pid: int, ancestor_pid: int, ancestor_start_ticks: int) -> bool:
    current = pid
    seen: set[int] = set()
    for _ in range(64):
        if current <= 1 or current in seen:
            return False
        seen.add(current)
        try:
            if current == ancestor_pid:
                return proc_stat(current)[2] == ancestor_start_ticks and proc_is_live(current)
            current = proc_parent_pid(current)
        except (OSError, ValueError, RuntimeError):
            return False
    return False


def same_listener(left: ListenerProof | None, right: ListenerProof | None) -> bool:
    return (
        left is not None
        and right is not None
        and left.ok
        and right.ok
        and left.pid == right.pid
        and left.start_ticks == right.start_ticks
        and left.socket_inode == right.socket_inode
    )


def prove_owned_loopback_launcher(
    *,
    port: int,
    run_id: str,
    run_dir: Path,
    artifact_root: Path,
    repo_root: Path,
    exercise_pid: int,
    exercise_start_ticks: int,
) -> ListenerProof:
    """Prove one current-run Java Launcher owns the loopback health socket."""
    if not safe_run_directory(run_dir, artifact_root):
        return ListenerProof(False, "run-directory")
    expected_argv = expected_launcher_argv(repo_root, run_id)
    if expected_argv is None:
        return ListenerProof(False, "launcher-expected")
    java = trusted_java_executable()
    if java is None:
        return ListenerProof(False, "listener-java")
    candidate, candidate_reason, identity_diagnostic = find_exact_launcher_candidate(
        expected_argv=expected_argv,
        java=java,
        run_dir=run_dir,
        exercise_pid=exercise_pid,
        exercise_start_ticks=exercise_start_ticks,
    )
    if candidate is None:
        proof_stage = (
            LISTENER_PROOF_STAGE_EXACT_IDENTITY
            if identity_diagnostic in {LISTENER_IDENTITY_EXACT, LISTENER_IDENTITY_EXACT_AMBIGUOUS}
            else LISTENER_PROOF_STAGE_NOT_OBSERVED
        )
        return ListenerProof(
            False,
            candidate_reason,
            identity_diagnostic=identity_diagnostic,
            proof_stage_diagnostic=proof_stage,
        )
    pid = candidate.pid
    initial_start_ticks = candidate.start_ticks
    socket_inode, socket_reason = listener_socket_probe_for_candidate(port, pid)
    if socket_inode is None:
        return ListenerProof(
            False,
            socket_reason,
            identity_diagnostic=identity_diagnostic,
            proof_stage_diagnostic=LISTENER_PROOF_STAGE_EXACT_IDENTITY,
        )
    if not candidate_holds_socket(pid, socket_inode):
        return ListenerProof(
            False,
            "socket-owner",
            identity_diagnostic=identity_diagnostic,
            proof_stage_diagnostic=LISTENER_PROOF_STAGE_SOCKET_FOUND,
        )
    if not run_owned_socket_holder_is_exclusive(
        socket_inode=socket_inode,
        candidate=candidate,
        exercise_pid=exercise_pid,
        exercise_start_ticks=exercise_start_ticks,
    ):
        return ListenerProof(
            False,
            "socket-owner",
            identity_diagnostic=identity_diagnostic,
            proof_stage_diagnostic=LISTENER_PROOF_STAGE_SOCKET_FOUND,
        )
    try:
        reproved = exact_launcher_candidate_identity(
            pid=pid,
            expected_start_ticks=initial_start_ticks,
            expected_argv=expected_argv,
            java=java,
            run_dir=run_dir,
            exercise_pid=exercise_pid,
            exercise_start_ticks=exercise_start_ticks,
        )
        if reproved.status == IDENTITY_PROC_UNAVAILABLE:
            return ListenerProof(
                False,
                LISTENER_CANDIDATE_PROC_UNAVAILABLE,
                identity_diagnostic=reproved.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if reproved.status == IDENTITY_PROC_MALFORMED:
            return ListenerProof(
                False,
                LISTENER_CANDIDATE_PROC_MALFORMED,
                identity_diagnostic=reproved.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if reproved.status != IDENTITY_MATCH or reproved.candidate != candidate:
            return ListenerProof(
                False,
                "listener-start-ticks",
                identity_diagnostic=reproved.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        final_socket_inode, final_socket_reason = listener_socket_probe_for_candidate(port, pid)
        if final_socket_inode is None:
            return ListenerProof(
                False,
                final_socket_reason,
                identity_diagnostic=LISTENER_IDENTITY_EXACT,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if final_socket_inode != socket_inode:
            return ListenerProof(
                False,
                "listener-inode",
                identity_diagnostic=LISTENER_IDENTITY_EXACT,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if not candidate_holds_socket(pid, socket_inode):
            return ListenerProof(
                False,
                "socket-owner",
                identity_diagnostic=LISTENER_IDENTITY_EXACT,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if not run_owned_socket_holder_is_exclusive(
            socket_inode=socket_inode,
            candidate=candidate,
            exercise_pid=exercise_pid,
            exercise_start_ticks=exercise_start_ticks,
        ):
            return ListenerProof(
                False,
                "socket-owner",
                identity_diagnostic=LISTENER_IDENTITY_EXACT,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        final_identity = exact_launcher_candidate_identity(
            pid=pid,
            expected_start_ticks=initial_start_ticks,
            expected_argv=expected_argv,
            java=java,
            run_dir=run_dir,
            exercise_pid=exercise_pid,
            exercise_start_ticks=exercise_start_ticks,
        )
        if final_identity.status == IDENTITY_PROC_UNAVAILABLE:
            return ListenerProof(
                False,
                LISTENER_CANDIDATE_PROC_UNAVAILABLE,
                identity_diagnostic=final_identity.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if final_identity.status == IDENTITY_PROC_MALFORMED:
            return ListenerProof(
                False,
                LISTENER_CANDIDATE_PROC_MALFORMED,
                identity_diagnostic=final_identity.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        if final_identity.status != IDENTITY_MATCH or final_identity.candidate != candidate:
            return ListenerProof(
                False,
                "listener-start-ticks",
                identity_diagnostic=final_identity.identity_diagnostic,
                proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
            )
        return ListenerProof(
            True,
            EXACT_LISTENER_PROOF_REASON,
            pid,
            initial_start_ticks,
            socket_inode,
            LISTENER_IDENTITY_EXACT,
            LISTENER_PROOF_STAGE_FULL_ELIGIBLE,
        )
    except (OSError, ValueError, RuntimeError):
        return ListenerProof(
            False,
            "unavailable",
            identity_diagnostic=LISTENER_IDENTITY_PROC_UNAVAILABLE,
            proof_stage_diagnostic=LISTENER_PROOF_STAGE_INITIAL_OWNERSHIP,
        )


def exact_child_argv(harness: Path, run_id: str, navigator_port: int) -> list[str]:
    return [
        "/usr/bin/bash",
        "-p",
        str(harness),
        "exercise",
        "--allow-create",
        "--allow-execute",
        "--build-launcher",
        "--run-id",
        run_id,
        "--navigator-port",
        str(navigator_port),
        "--forced-signal-rehearsal",
    ]


def prove_exercise_parent(
    pid: int,
    initial_start_ticks: int,
    repo_root: Path,
    expected_argv: list[str],
) -> ParentProof:
    try:
        if Path(f"/proc/{pid}").stat().st_uid != os.getuid():
            return ParentProof(False, "uid")
        pgrp, session, current_start_ticks = proc_stat(pid)
        if current_start_ticks != initial_start_ticks:
            return ParentProof(False, "start-ticks", current_start_ticks)
        if pgrp != pid or session != pid:
            return ParentProof(False, "dedicated-session", current_start_ticks)
        if Path(f"/proc/{pid}/cwd").resolve() != repo_root:
            return ParentProof(False, "cwd", current_start_ticks)
        raw_argv = Path(f"/proc/{pid}/cmdline").read_bytes()
        if not raw_argv.endswith(b"\0"):
            return ParentProof(False, "argv", current_start_ticks)
        argv = [part.decode("utf-8", "surrogateescape") for part in raw_argv.split(b"\0")[:-1]]
        if argv != expected_argv:
            return ParentProof(False, "argv", current_start_ticks)
        final_start_ticks = proc_stat(pid)[2]
        if final_start_ticks != initial_start_ticks:
            return ParentProof(False, "start-ticks", final_start_ticks)
        return ParentProof(True, EXACT_PARENT_PROOF_REASON, final_start_ticks)
    except (OSError, ValueError, RuntimeError):
        return ParentProof(False, "unavailable")


def start_exercise(repo_root: Path, harness: Path, run_id: str, navigator_port: int) -> tuple[int, int]:
    ready_read, ready_write = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(ready_read)
        try:
            os.setsid()
            os.chdir(repo_root)
            start_ticks = proc_stat(os.getpid())[2]
            os.write(ready_write, f"READY:{start_ticks}".encode("ascii"))
            os.close(ready_write)
            devnull = os.open(os.devnull, os.O_WRONLY)
            os.dup2(devnull, 1)
            os.dup2(devnull, 2)
            os.close(devnull)
            os.execve(
                "/usr/bin/bash",
                exact_child_argv(harness, run_id, navigator_port),
                minimal_child_environment(),
            )
        except BaseException:
            try:
                os.write(ready_write, b"FAILED")
            except OSError:
                pass
            os._exit(125)
    os.close(ready_write)
    try:
        payload = os.read(ready_read, 64).decode("ascii", "strict")
    finally:
        os.close(ready_read)
    if not payload.startswith("READY:"):
        _, _ = os.waitpid(pid, 0)
        raise RuntimeError("exercise child did not establish a dedicated session")
    try:
        return pid, int(payload.removeprefix("READY:"))
    except ValueError as exc:
        _, _ = os.waitpid(pid, 0)
        raise RuntimeError("exercise child returned malformed start proof") from exc


def health_ready(port: int) -> bool:
    connection: http.client.HTTPConnection | None = None
    try:
        # `HTTPConnection` receives a literal loopback address and never
        # follows redirects or consults proxy/DNS settings.  A 30x response is
        # deliberately not a health proof for the disposable target.
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
        connection.request(
            "GET",
            "/actuator/health",
            headers={"Host": f"127.0.0.1:{port}", "Connection": "close"},
        )
        response = connection.getresponse()
        if response.status != 200:
            return False
        body = response.read(MAX_HEALTH_BODY_BYTES + 1)
        if len(body) > MAX_HEALTH_BODY_BYTES:
            return False
        value = json.loads(body.decode("utf-8", "strict"))
        return isinstance(value, dict) and value.get("status") == "UP"
    except (OSError, http.client.HTTPException, UnicodeDecodeError, json.JSONDecodeError):
        return False
    finally:
        if connection is not None:
            connection.close()


def poll_child_exit(pid: int) -> int | None:
    exited_pid, status = os.waitpid(pid, os.WNOHANG)
    return status if exited_pid == pid else None


def wait_for_child_exit(pid: int, timeout_seconds: int) -> int | None:
    deadline = time.monotonic() + timeout_seconds
    while SUPERVISOR_INTERRUPTION is None and time.monotonic() < deadline:
        status = poll_child_exit(pid)
        if status is not None:
            return status
        time.sleep(0.2)
    return poll_child_exit(pid)


def pending_control_signals() -> set[signal.Signals] | None:
    """Return blocked control signals, or ``None`` when observation is unsafe."""
    pending_signals = getattr(signal, "sigpending", None)
    if not callable(pending_signals):
        return None
    try:
        return set(pending_signals()) & CONTROL_SIGNALS
    except (OSError, RuntimeError, ValueError, TypeError):
        return None


def latch_pending_control_signal() -> bool:
    """Make an observed or unobservable control signal permanently ineligible.

    A Python signal handler may not run while this thread has the signal
    blocked.  Latching a fixed, non-secret label makes the later evidence
    branch fail closed even in that interval.  An unavailable ``sigpending``
    is deliberately treated as an unknown interruption.
    """
    global SUPERVISOR_INTERRUPTION
    pending = pending_control_signals()
    if pending is None:
        if SUPERVISOR_INTERRUPTION is None:
            SUPERVISOR_INTERRUPTION = "UNKNOWN"
        return True
    if pending:
        if SUPERVISOR_INTERRUPTION is None:
            for signum in CONTROL_SIGNAL_ORDER:
                if signum in pending:
                    SUPERVISOR_INTERRUPTION = SIGNAL_LABELS[signum]
                    break
        return True
    return SUPERVISOR_INTERRUPTION is not None


def control_signal_mask_is_clear() -> bool:
    """Require a startable thread whose control signals are not inherited blocked."""
    signal_mask = getattr(signal, "pthread_sigmask", None)
    if not callable(signal_mask):
        return False
    try:
        current_mask = signal_mask(signal.SIG_BLOCK, set())
        return not bool(set(current_mask) & CONTROL_SIGNALS)
    except (OSError, RuntimeError, ValueError, TypeError):
        return False


def assert_control_signal_startable() -> None:
    """Reject an inherited mask or pending signal before any child can inherit it."""
    if not control_signal_mask_is_clear() or latch_pending_control_signal():
        raise RuntimeError("supervisor control-signal state is unsafe before exercise start")


def dispatch_owned_parent_term(
    *,
    child_pid: int,
    initial_start_ticks: int,
    repo_root: Path,
    expected_argv: list[str],
    run_id: str,
    run_dir: Path,
    artifact_root: Path,
    navigator_port: int,
    prior_listener_proof: ListenerProof,
) -> tuple[ParentProof, ListenerProof, int, bool]:
    """Reprove under a signal mask and issue at most one deliberate TERM."""
    signal_mask = getattr(signal, "pthread_sigmask", None)
    if not callable(signal_mask):
        return (
            ParentProof(False, "signal-mask-unavailable"),
            ListenerProof(
                False,
                "signal-mask-unavailable",
                identity_diagnostic=prior_listener_proof.identity_diagnostic,
            ),
            0,
            False,
        )
    try:
        prior_mask = signal_mask(signal.SIG_BLOCK, CONTROL_SIGNALS)
    except (OSError, RuntimeError, ValueError, TypeError):
        return (
            ParentProof(False, "signal-mask-unavailable"),
            ListenerProof(
                False,
                "signal-mask-unavailable",
                identity_diagnostic=prior_listener_proof.identity_diagnostic,
            ),
            0,
            False,
        )

    parent_proof = ParentProof(False, "not-reproved")
    listener_proof = ListenerProof(
        False,
        "not-reproved",
        identity_diagnostic=prior_listener_proof.identity_diagnostic,
    )
    term_dispatches = 0
    dispatch_safe = False
    try:
        if set(prior_mask) & CONTROL_SIGNALS:
            # ``main`` rejects this before fork, but the helper also refuses a
            # direct caller whose child could have inherited a blocked mask.
            parent_proof = ParentProof(False, "signal-mask-preblocked")
            listener_proof = ListenerProof(
                False,
                "signal-mask-preblocked",
                identity_diagnostic=prior_listener_proof.identity_diagnostic,
            )
        elif not latch_pending_control_signal():
            parent_proof = prove_exercise_parent(child_pid, initial_start_ticks, repo_root, expected_argv)
            if parent_proof.ok:
                listener_proof = prove_owned_loopback_launcher(
                    port=navigator_port,
                    run_id=run_id,
                    run_dir=run_dir,
                    artifact_root=artifact_root,
                    repo_root=repo_root,
                    exercise_pid=child_pid,
                    exercise_start_ticks=initial_start_ticks,
                )
                if not listener_proof.ok:
                    # Preserve a concrete re-proof failure (for example a
                    # malformed /proc socket table) instead of collapsing it
                    # into a generic listener-change diagnosis.
                    pass
                elif not same_listener(prior_listener_proof, listener_proof):
                    listener_proof = ListenerProof(
                        False,
                        "listener-changed",
                        identity_diagnostic=listener_proof.identity_diagnostic,
                    )
                elif not latch_pending_control_signal():
                    # This final observation is the deliberate dispatch
                    # commit point. POSIX cannot atomically test pending
                    # signals and signal another PID. A control signal seen
                    # after this point may leave one TERM dispatched, but it
                    # can never leave a dispatch-safe/success-shaped result.
                    os.kill(child_pid, signal.SIGTERM)
                    term_dispatches = 1
                    if latch_pending_control_signal():
                        listener_proof = ListenerProof(
                            False,
                            "signal-pending-after-dispatch",
                            identity_diagnostic=listener_proof.identity_diagnostic,
                        )
                    else:
                        dispatch_safe = True
        elif SUPERVISOR_INTERRUPTION is None:
            parent_proof = ParentProof(False, "signal-pending")
            listener_proof = ListenerProof(
                False,
                "signal-pending",
                identity_diagnostic=prior_listener_proof.identity_diagnostic,
            )
    except (OSError, TypeError):
        pass
    finally:
        try:
            signal_mask(signal.SIG_SETMASK, prior_mask)
        except (OSError, RuntimeError, ValueError, TypeError):
            listener_proof = ListenerProof(
                False,
                "signal-mask-restore",
                identity_diagnostic=listener_proof.identity_diagnostic,
            )
            dispatch_safe = False
        if latch_pending_control_signal():
            dispatch_safe = False
    return parent_proof, listener_proof, term_dispatches, dispatch_safe


def supervise_exercise(
    *,
    child_pid: int,
    initial_start_ticks: int,
    repo_root: Path,
    expected_argv: list[str],
    run_id: str,
    run_dir: Path,
    artifact_root: Path,
    navigator_port: int,
    health_timeout_seconds: int,
    post_term_timeout_seconds: int,
) -> RehearsalOutcome:
    """Observe one child and send the sole controlled TERM, if eligible.

    This deliberately has no abort signal path.  Missing health, an external
    supervisor signal, a dead parent, or an incomplete ownership proof all
    leave the exercise parent untouched and make the rehearsal ineligible.
    """
    deadline = time.monotonic() + health_timeout_seconds
    child_exit: int | None = None
    health_precondition = False
    parent_proof: ParentProof | None = None
    listener_proof: ListenerProof | None = None
    listener_proof_ever_eligible = False
    furthest_listener_identity_diagnostic = LISTENER_IDENTITY_NOT_OBSERVED
    furthest_listener_proof_stage_diagnostic = LISTENER_PROOF_STAGE_NOT_OBSERVED
    term_dispatches = 0
    dispatch_safe = False

    while SUPERVISOR_INTERRUPTION is None and time.monotonic() < deadline:
        child_exit = poll_child_exit(child_pid)
        if child_exit is not None:
            break
        parent_proof = prove_exercise_parent(child_pid, initial_start_ticks, repo_root, expected_argv)
        if not parent_proof.ok or SUPERVISOR_INTERRUPTION is not None:
            break
        listener_proof_a = prove_owned_loopback_launcher(
            port=navigator_port,
            run_id=run_id,
            run_dir=run_dir,
            artifact_root=artifact_root,
            repo_root=repo_root,
            exercise_pid=child_pid,
            exercise_start_ticks=initial_start_ticks,
        )
        listener_proof = listener_proof_a
        furthest_listener_identity_diagnostic = furthest_temporal_identity_diagnostic(
            furthest_listener_identity_diagnostic,
            redacted_listener_identity_diagnostic(listener_proof_a),
        )
        furthest_listener_proof_stage_diagnostic = furthest_temporal_listener_proof_stage(
            furthest_listener_proof_stage_diagnostic,
            redacted_listener_proof_stage(listener_proof_a),
        )
        if not listener_proof_a.ok:
            time.sleep(0.5)
            continue
        listener_proof_ever_eligible = True
        if SUPERVISOR_INTERRUPTION is not None:
            break
        if not health_ready(navigator_port):
            time.sleep(0.5)
            continue
        parent_proof = prove_exercise_parent(child_pid, initial_start_ticks, repo_root, expected_argv)
        if not parent_proof.ok or SUPERVISOR_INTERRUPTION is not None:
            break
        listener_proof_b = prove_owned_loopback_launcher(
            port=navigator_port,
            run_id=run_id,
            run_dir=run_dir,
            artifact_root=artifact_root,
            repo_root=repo_root,
            exercise_pid=child_pid,
            exercise_start_ticks=initial_start_ticks,
        )
        listener_proof = listener_proof_b
        furthest_listener_identity_diagnostic = furthest_temporal_identity_diagnostic(
            furthest_listener_identity_diagnostic,
            redacted_listener_identity_diagnostic(listener_proof_b),
        )
        furthest_listener_proof_stage_diagnostic = furthest_temporal_listener_proof_stage(
            furthest_listener_proof_stage_diagnostic,
            redacted_listener_proof_stage(listener_proof_b),
        )
        listener_proof_ever_eligible = listener_proof_ever_eligible or listener_proof_b.ok
        if not listener_proof_b.ok:
            # Keep a precise re-proof diagnosis (such as a socket-table
            # failure) rather than rewriting every failed second proof as an
            # identity change.
            break
        if not same_listener(listener_proof_a, listener_proof_b):
            listener_proof = ListenerProof(
                False,
                "listener-changed",
                identity_diagnostic=listener_proof_b.identity_diagnostic,
            )
            break
        parent_proof = prove_exercise_parent(child_pid, initial_start_ticks, repo_root, expected_argv)
        if not parent_proof.ok or SUPERVISOR_INTERRUPTION is not None:
            break
        health_precondition = True
        parent_proof, listener_proof, term_dispatches, dispatch_safe = dispatch_owned_parent_term(
            child_pid=child_pid,
            initial_start_ticks=initial_start_ticks,
            repo_root=repo_root,
            expected_argv=expected_argv,
            run_id=run_id,
            run_dir=run_dir,
            artifact_root=artifact_root,
            navigator_port=navigator_port,
            prior_listener_proof=listener_proof_b,
        )
        listener_proof_ever_eligible = listener_proof_ever_eligible or listener_proof.ok
        furthest_listener_identity_diagnostic = furthest_temporal_identity_diagnostic(
            furthest_listener_identity_diagnostic,
            redacted_listener_identity_diagnostic(listener_proof),
        )
        furthest_listener_proof_stage_diagnostic = furthest_temporal_listener_proof_stage(
            furthest_listener_proof_stage_diagnostic,
            redacted_listener_proof_stage(listener_proof),
        )
        if term_dispatches != 1:
            break
        child_exit = wait_for_child_exit(child_pid, post_term_timeout_seconds)
        break

    if child_exit is None:
        child_exit = poll_child_exit(child_pid)
    return RehearsalOutcome(
        health_precondition,
        parent_proof,
        term_dispatches,
        child_exit,
        listener_proof,
        dispatch_safe,
        listener_proof_ever_eligible,
        furthest_listener_identity_diagnostic,
        furthest_listener_proof_stage_diagnostic,
    )


def exit_summary(status: int | None) -> str:
    if status is None:
        return "NOT_OBSERVED"
    if os.WIFEXITED(status):
        return f"EXIT_{os.WEXITSTATUS(status)}"
    if os.WIFSIGNALED(status):
        return f"SIGNAL_{os.WTERMSIG(status)}"
    return "OTHER"


def reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value


def read_redacted_receipt(run_dir: Path, run_id: str, artifact_root: Path) -> dict[str, Any] | None:
    """Return only a fixed, non-secret receipt summary from a safe run root."""
    if not safe_run_directory(run_dir, artifact_root):
        return None
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        return None
    receipt = run_dir / "cleanup-report.json"
    descriptor: int | None = None
    try:
        descriptor = os.open(receipt, os.O_RDONLY | os.O_CLOEXEC | nofollow)
        details = os.fstat(descriptor)
        if (
            not stat.S_ISREG(details.st_mode)
            or details.st_uid != os.getuid()
            or stat.S_IMODE(details.st_mode) != 0o600
            or details.st_nlink != 1
        ):
            return None
        raw = os.read(descriptor, 16 * 1024 + 1)
        if len(raw) > 16 * 1024:
            return None
        value = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=reject_duplicate_json_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
        return None
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if not isinstance(value, dict) or set(value) != RECEIPT_FIELDS:
        return None
    if (
        type(value.get("schemaVersion")) is not int
        or value["schemaVersion"] != 4
        or value.get("runId") != run_id
        or value.get("secretsRedacted") is not True
    ):
        return None
    if not isinstance(value.get("result"), str) or value["result"] not in CLEANUP_RESULTS:
        return None
    if not isinstance(value.get("failureStage"), str) or value["failureStage"] not in CLEANUP_FAILURE_STAGES:
        return None
    if (
        not isinstance(value.get("launcherReadinessObservation"), str)
        or value["launcherReadinessObservation"] not in LAUNCHER_READINESS_OBSERVATIONS
    ):
        return None
    if (
        not isinstance(value.get("launcherFailureClass"), str)
        or value["launcherFailureClass"] not in LAUNCHER_FAILURE_CLASSES
    ):
        return None
    if (
        not isinstance(value.get("rehearsalLifecycleObservation"), str)
        or value["rehearsalLifecycleObservation"] not in REHEARSAL_LIFECYCLE_OBSERVATIONS
    ):
        return None
    if not isinstance(value.get("finishedAtUtc"), str) or not UTC_TIMESTAMP_RE.fullmatch(value["finishedAtUtc"]):
        return None
    return {
        "mode": "0600",
        "schemaVersion": 4,
        "result": value["result"],
        "failureStage": value["failureStage"],
        "rehearsalLifecycleObservation": value["rehearsalLifecycleObservation"],
        "launcherReadinessObservation": value["launcherReadinessObservation"],
        "launcherFailureClass": value["launcherFailureClass"],
        "secretsRedacted": True,
    }


def current_run_reservation_absent(artifact_root: Path, run_id: str) -> bool:
    """Fail closed unless the strict registry is safe and this run has no reservation."""
    nofollow = getattr(os, "O_NOFOLLOW", None)
    directory = getattr(os, "O_DIRECTORY", None)
    if nofollow is None or directory is None or not RUN_ID_RE.fullmatch(run_id):
        return False
    root_fd: int | None = None
    registry_fd: int | None = None
    try:
        root_fd = os.open(artifact_root, os.O_RDONLY | os.O_CLOEXEC | nofollow | directory)
        root_details = os.fstat(root_fd)
        if (
            not stat.S_ISDIR(root_details.st_mode)
            or root_details.st_uid != os.getuid()
            or stat.S_IMODE(root_details.st_mode) != 0o700
        ):
            return False
        fcntl.flock(root_fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        registry_fd = os.open(
            PORT_RESERVATION_DIRECTORY_NAME,
            os.O_RDONLY | os.O_CLOEXEC | nofollow | directory,
            dir_fd=root_fd,
        )
        registry_details = os.fstat(registry_fd)
        if (
            not stat.S_ISDIR(registry_details.st_mode)
            or registry_details.st_uid != os.getuid()
            or stat.S_IMODE(registry_details.st_mode) != 0o700
        ):
            return False
        seen_ports: set[int] = set()
        exact_name = f"{run_id}{PORT_RESERVATION_SUFFIX}"
        for name in os.listdir(registry_fd):
            if not name.endswith(PORT_RESERVATION_SUFFIX):
                return False
            reservation_run_id = name[: -len(PORT_RESERVATION_SUFFIX)]
            if not RUN_ID_RE.fullmatch(reservation_run_id) or reservation_run_id.endswith("-") or "--" in reservation_run_id:
                return False
            file_fd: int | None = None
            try:
                file_fd = os.open(name, os.O_RDONLY | os.O_CLOEXEC | nofollow, dir_fd=registry_fd)
                details = os.fstat(file_fd)
                if (
                    not stat.S_ISREG(details.st_mode)
                    or details.st_uid != os.getuid()
                    or stat.S_IMODE(details.st_mode) != 0o600
                    or details.st_nlink != 1
                    or details.st_size > MAX_PORT_RESERVATION_BYTES
                ):
                    return False
                chunks = bytearray()
                while len(chunks) <= MAX_PORT_RESERVATION_BYTES:
                    chunk = os.read(
                        file_fd,
                        min(4096, MAX_PORT_RESERVATION_BYTES + 1 - len(chunks)),
                    )
                    if not chunk:
                        break
                    chunks.extend(chunk)
                if len(chunks) > MAX_PORT_RESERVATION_BYTES or len(chunks) != details.st_size:
                    return False
                raw = bytes(chunks)
            finally:
                if file_fd is not None:
                    os.close(file_fd)
            text = raw.decode("utf-8", "strict")
            # Keep this parser aligned with the harness's authoritative
            # parse_strict_env contract. Bash read -r splits only on LF;
            # splitlines() would incorrectly normalize CRLF, bare CR and
            # Unicode/control record separators into accepted lines.
            if any(separator in text for separator in "\x00\r\v\f\x1c\x1d\x1e\x85\u2028\u2029"):
                return False
            lines = text.split("\n")
            if lines and lines[-1] == "":
                lines.pop()
            if len(lines) != len(PORT_RESERVATION_FIELDS):
                return False
            values: dict[str, str] = {}
            for line in lines:
                if "=" not in line:
                    return False
                key, value = line.split("=", 1)
                if key in values or key not in PORT_RESERVATION_FIELDS or not value:
                    return False
                values[key] = value
            if tuple(values) != PORT_RESERVATION_FIELDS:
                return False
            if (
                values["INT001_PORT_RESERVATION_SCHEMA"] != PORT_RESERVATION_SCHEMA_VERSION
                or values["INT001_RUN_ID"] != reservation_run_id
            ):
                return False
            for key in PORT_RESERVATION_PORT_FIELDS:
                value = values[key]
                if not value.isascii() or not value.isdigit():
                    return False
                port = int(value, 10)
                if port < 1025 or port > 65535 or port in RESERVED_PORTS or port in seen_ports:
                    return False
                seen_ports.add(port)
            if name == exact_name:
                return False
        return True
    except (OSError, UnicodeDecodeError, ValueError):
        return False
    finally:
        if registry_fd is not None:
            os.close(registry_fd)
        if root_fd is not None:
            os.close(root_fd)


def run_root_snapshot(run_dir: Path, artifact_root: Path) -> RunRootSnapshot:
    """Inspect root entry names only; never traverse private/ or children/."""
    if not safe_run_directory(run_dir, artifact_root):
        return RunRootSnapshot(None, None)
    nofollow = getattr(os, "O_NOFOLLOW", None)
    directory = getattr(os, "O_DIRECTORY", None)
    if nofollow is None or directory is None:
        return RunRootSnapshot(None, None)
    descriptor: int | None = None
    try:
        descriptor = os.open(run_dir, os.O_RDONLY | os.O_CLOEXEC | nofollow | directory)
        details = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(details.st_mode)
            or details.st_uid != os.getuid()
            or stat.S_IMODE(details.st_mode) != 0o700
            or details.st_nlink < 2
        ):
            return RunRootSnapshot(None, None)
        names = os.listdir(descriptor)
        return RunRootSnapshot(
            private_absent="private" not in names,
            nonreceipt_residue_count=sum(name != "cleanup-report.json" for name in names),
        )
    except OSError:
        return RunRootSnapshot(None, None)
    finally:
        if descriptor is not None:
            os.close(descriptor)


def root_nonreceipt_residue_count(run_dir: Path, artifact_root: Path) -> int | None:
    return run_root_snapshot(run_dir, artifact_root).nonreceipt_residue_count


def docker_environment() -> dict[str, str]:
    home = pwd.getpwuid(os.getuid()).pw_dir
    home_path = Path(home)
    if not home or not home_path.is_dir() or home_path.is_symlink() or home_path.stat().st_uid != os.getuid():
        raise RuntimeError("current user home cannot be safely resolved")
    return {"PATH": SAFE_PATH, "HOME": home}


def local_docker_socket_is_safe() -> bool:
    """Accept only the configured local Unix socket, never a symlink/context."""
    try:
        details = LOCAL_DOCKER_SOCKET.lstat()
        return not LOCAL_DOCKER_SOCKET.is_symlink() and stat.S_ISSOCK(details.st_mode)
    except OSError:
        return False


def run_local_docker(command: list[str]) -> subprocess.CompletedProcess[str] | None:
    if (
        not local_docker_socket_is_safe()
        or not command
        or command[0] != "docker"
        or "--context" in command
    ):
        return None
    try:
        return subprocess.run(
            ["docker", "--host", LOCAL_DOCKER_HOST, *command[1:]],
            cwd=repository_root(),
            env=docker_environment(),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=DOCKER_COMMAND_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired, RuntimeError):
        return None


def docker_residue_counts(run_id: str) -> dict[str, int | None]:
    project = f"int001_{run_id.replace('-', '_')}"
    commands = {
        "container": ["docker", "ps", "-aq"],
        "network": ["docker", "network", "ls", "-q"],
        "volume": ["docker", "volume", "ls", "-q"],
    }
    result: dict[str, int | None] = {}
    for kind, command in commands.items():
        identifiers: set[str] = set()
        for label in (f"com.docker.compose.project={project}", f"com.foggy.navigator.int001.run-id={run_id}"):
            completed = run_local_docker([*command, "--filter", f"label={label}"])
            if completed is None or completed.returncode != 0:
                result[kind] = None
                break
            identifiers.update(line for line in completed.stdout.splitlines() if line)
        else:
            result[kind] = len(identifiers)
    return result


def redacted_listener_identity_diagnostic(listener_proof: ListenerProof | None) -> str:
    if listener_proof is None:
        return LISTENER_IDENTITY_NOT_OBSERVED
    if listener_proof.identity_diagnostic not in LISTENER_IDENTITY_DIAGNOSTICS:
        return LISTENER_IDENTITY_NOT_OBSERVED
    return listener_proof.identity_diagnostic


def outcome_listener_identity_diagnostic(
    listener_proof: ListenerProof | None,
    furthest_listener_identity_diagnostic: str,
) -> str:
    diagnostic = furthest_listener_identity_diagnostic
    if diagnostic in LISTENER_IDENTITY_DIAGNOSTICS and diagnostic != LISTENER_IDENTITY_NOT_OBSERVED:
        return diagnostic
    return redacted_listener_identity_diagnostic(listener_proof)


def outcome_listener_proof_stage_diagnostic(
    listener_proof: ListenerProof | None,
    furthest_listener_proof_stage_diagnostic: str,
) -> str:
    diagnostic = furthest_listener_proof_stage_diagnostic
    if diagnostic in LISTENER_PROOF_STAGES and diagnostic != LISTENER_PROOF_STAGE_NOT_OBSERVED:
        return diagnostic
    return redacted_listener_proof_stage(listener_proof)


def emit_summary(
    *,
    run_id: str,
    health_precondition: bool,
    parent_proof: ParentProof | None,
    listener_proof: ListenerProof | None,
    listener_proof_ever_eligible: bool,
    term_dispatches: int,
    dispatch_safe: bool,
    child_exit: int | None,
    receipt: dict[str, Any] | None,
    root_snapshot: RunRootSnapshot,
    docker_snapshot: dict[str, int | None],
    furthest_listener_identity_diagnostic: str = LISTENER_IDENTITY_NOT_OBSERVED,
    furthest_listener_proof_stage_diagnostic: str = LISTENER_PROOF_STAGE_NOT_OBSERVED,
) -> None:
    summary = {
        "schemaVersion": 1,
        "runId": run_id,
        "controlledHealthPrecondition": health_precondition,
        "parentProof": parent_proof.reason if parent_proof else "NOT_ATTEMPTED",
        "listenerProof": listener_proof.reason if listener_proof else "NOT_ATTEMPTED",
        "listenerIdentityDiagnostic": outcome_listener_identity_diagnostic(
            listener_proof,
            furthest_listener_identity_diagnostic,
        ),
        "listenerProofStageDiagnostic": outcome_listener_proof_stage_diagnostic(
            listener_proof,
            furthest_listener_proof_stage_diagnostic,
        ),
        "listenerProofEverEligible": listener_proof_ever_eligible,
        "termDispatches": term_dispatches,
        "dispatchSafe": dispatch_safe,
        "exerciseExit": exit_summary(child_exit),
        "receipt": receipt,
        "privateAbsent": root_snapshot.private_absent,
        "rootNonReceiptResidueCount": root_snapshot.nonreceipt_residue_count,
        "dockerResidueCounts": docker_snapshot,
        "supervisorInterruption": SUPERVISOR_INTERRUPTION or "NONE",
    }
    print(json.dumps(summary, sort_keys=True, separators=(",", ":")), flush=True)


def projection_receipt_state(receipt: dict[str, Any] | None, *, suppressed: bool) -> str:
    if suppressed:
        return "SUPPRESSED"
    return "VALID" if receipt is not None else "MISSING_OR_INVALID"


def projection_root_snapshot_state(root_snapshot: RunRootSnapshot, *, suppressed: bool) -> str:
    if suppressed:
        return "SUPPRESSED"
    if root_snapshot.private_absent is None or root_snapshot.nonreceipt_residue_count is None:
        return "UNAVAILABLE"
    return "COMPLETE"


def forced_signal_completion_gate_met(
    *,
    supervisor_interruption: str | None,
    outcome: RehearsalOutcome,
    receipt: dict[str, Any] | None,
    root_snapshot: RunRootSnapshot,
    reservation_absent: bool,
    docker_snapshot: dict[str, int | None],
) -> bool:
    """Accept only the exact real-harness forced-SIGNAL completion contract."""
    return (
        supervisor_interruption is None
        and outcome.health_precondition
        and outcome.parent_proof is not None
        and outcome.parent_proof.ok
        and outcome.parent_proof.reason == EXACT_PARENT_PROOF_REASON
        and outcome.listener_proof is not None
        and outcome.listener_proof.ok
        and outcome.listener_proof.reason == EXACT_LISTENER_PROOF_REASON
        and outcome.listener_proof.identity_diagnostic == LISTENER_IDENTITY_EXACT
        and outcome.listener_proof_ever_eligible
        and outcome.term_dispatches == 1
        and outcome.dispatch_safe
        and outcome.child_exit is not None
        and os.WIFEXITED(outcome.child_exit)
        and os.WEXITSTATUS(outcome.child_exit) == FORCED_SIGNAL_OUTER_EXIT_CODE
        and receipt is not None
        and receipt.get("result") == "CLEANED"
        and receipt.get("failureStage") == "SIGNAL"
        and receipt.get("rehearsalLifecycleObservation") == "HOLD_SIGNAL_RECEIVED"
        and receipt.get("launcherReadinessObservation") == "HEALTH_READY"
        and receipt.get("launcherFailureClass") == "NOT_APPLICABLE"
        and root_snapshot.private_absent is True
        and root_snapshot.nonreceipt_residue_count == 0
        and reservation_absent
        and docker_snapshot == {"container": 0, "network": 0, "volume": 0}
    )


def classify_projection_outcome(
    outcome: RehearsalOutcome,
    receipt: dict[str, Any] | None,
    root_snapshot: RunRootSnapshot,
    docker_snapshot: dict[str, int | None],
    complete: bool,
) -> str:
    """Return one fixed diagnostic label without changing the completion gate."""
    if SUPERVISOR_INTERRUPTION is not None:
        return "SUPERVISOR_INTERRUPTED"
    if outcome.child_exit is not None and not outcome.health_precondition:
        return "CHILD_EXITED_BEFORE_HEALTH"
    if not outcome.health_precondition or outcome.parent_proof is None or not outcome.parent_proof.ok:
        return "HEALTH_OR_OWNERSHIP_INELIGIBLE"
    if outcome.term_dispatches != 1:
        return "TERM_NOT_DISPATCHED"
    if not outcome.dispatch_safe or outcome.listener_proof is None or not outcome.listener_proof.ok:
        return "TERM_DISPATCH_INELIGIBLE"
    if receipt is None:
        return "RECEIPT_MISSING_OR_INVALID"
    if root_snapshot.private_absent is None or root_snapshot.nonreceipt_residue_count is None:
        return "ROOT_SNAPSHOT_UNAVAILABLE"
    if any(value is None for value in docker_snapshot.values()):
        return "DOCKER_SNAPSHOT_UNAVAILABLE"
    return "SUCCESS_GATE_MET" if complete else "SUCCESS_GATE_NOT_MET"


def projection_exception_outcome(phase: str) -> str:
    if phase == "SUPERVISOR_STARTED":
        return "PREFLIGHT_FAILED"
    if phase == "PREFLIGHT_COMPLETE":
        return "EXERCISE_START_FAILED"
    if phase == "EXERCISE_STARTED":
        return "SUPERVISION_FAILED"
    return "UNEXPECTED_FAILURE"


def main() -> int:
    global SUPERVISOR_INTERRUPTION
    SUPERVISOR_INTERRUPTION = None
    args = parse_args()
    install_supervisor_signal_handlers()
    assert_control_signal_startable()
    repo_root = repository_root()
    artifact_root = assert_artifact_root(repo_root)
    projection = open_execution_projection(artifact_root, args.run_id)
    projection_phase = "SUPERVISOR_STARTED"
    receipt_state = "NOT_SAMPLED"
    root_snapshot_state = "NOT_SAMPLED"
    stdout_summary_state = "NOT_EMITTED"
    harness = repo_root / "tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"
    run_dir = artifact_root / args.run_id
    try:
        if not harness.is_file() or harness.is_symlink():
            raise RuntimeError("synthetic upstream harness is missing or unsafe")
        if os.path.lexists(run_dir):
            raise RuntimeError("runId already exists; supervisor only accepts a fresh run")
        if not local_docker_socket_is_safe():
            raise RuntimeError("INT-001 requires a non-symlink local Docker Unix socket")
        navigator_port = args.navigator_port if args.navigator_port is not None else choose_navigator_port()
        if not port_is_unused(navigator_port):
            raise RuntimeError("selected disposable Navigator port is already in use")
        # Repeat immediately before fork so no child can inherit an unsafe mask
        # or start after a control signal became observable during preflight.
        assert_control_signal_startable()
        projection_phase = "PREFLIGHT_COMPLETE"
        projection.write(
            phase=projection_phase,
            outcome="IN_PROGRESS",
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        child_pid, initial_start_ticks = start_exercise(repo_root, harness, args.run_id, navigator_port)
        projection_phase = "EXERCISE_STARTED"
        projection.write(
            phase=projection_phase,
            outcome="IN_PROGRESS",
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        expected_argv = exact_child_argv(harness, args.run_id, navigator_port)
        outcome = supervise_exercise(
            child_pid=child_pid,
            initial_start_ticks=initial_start_ticks,
            repo_root=repo_root,
            expected_argv=expected_argv,
            run_id=args.run_id,
            run_dir=run_dir,
            artifact_root=artifact_root,
            navigator_port=navigator_port,
            health_timeout_seconds=args.health_timeout_seconds,
            post_term_timeout_seconds=args.post_term_timeout_seconds,
        )
        projection_phase = "SUPERVISION_COMPLETE"
        projection.write(
            phase=projection_phase,
            outcome="IN_PROGRESS",
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        # Collect each evidence source at most once.  The summary and
        # completion decision consume the same snapshot, so a changing Docker
        # state cannot make the recorded result disagree with the exit code.
        receipt: dict[str, Any] | None = None
        root_snapshot = RunRootSnapshot(None, None)
        reservation_absent = False
        docker_snapshot: dict[str, int | None] = {"container": None, "network": None, "volume": None}
        if not latch_pending_control_signal():
            receipt = read_redacted_receipt(run_dir, args.run_id, artifact_root)
            if not latch_pending_control_signal():
                root_snapshot = run_root_snapshot(run_dir, artifact_root)
            if not latch_pending_control_signal():
                reservation_absent = current_run_reservation_absent(artifact_root, args.run_id)
            if receipt is not None and not reservation_absent:
                receipt = None
            if not latch_pending_control_signal():
                docker_snapshot = docker_residue_counts(args.run_id)
        # A supervisor interruption makes this rehearsal ineligible even if
        # it arrived after a deliberate TERM. Do not serialize stale
        # success-shaped evidence sampled while an external stop was pending.
        suppressed = latch_pending_control_signal()
        if suppressed:
            receipt = None
            root_snapshot = RunRootSnapshot(None, None)
            reservation_absent = False
            docker_snapshot = {"container": None, "network": None, "volume": None}
        receipt_state = projection_receipt_state(receipt, suppressed=suppressed)
        root_snapshot_state = projection_root_snapshot_state(root_snapshot, suppressed=suppressed)
        projection_phase = "EVIDENCE_SAMPLED"
        projection.write(
            phase=projection_phase,
            outcome="IN_PROGRESS",
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )

        emit_summary(
            run_id=args.run_id,
            health_precondition=outcome.health_precondition,
            parent_proof=outcome.parent_proof,
            listener_proof=outcome.listener_proof,
            listener_proof_ever_eligible=outcome.listener_proof_ever_eligible,
            term_dispatches=outcome.term_dispatches,
            dispatch_safe=outcome.dispatch_safe,
            child_exit=outcome.child_exit,
            receipt=receipt,
            root_snapshot=root_snapshot,
            docker_snapshot=docker_snapshot,
            furthest_listener_identity_diagnostic=outcome.furthest_listener_identity_diagnostic,
            furthest_listener_proof_stage_diagnostic=(
                outcome.furthest_listener_proof_stage_diagnostic
            ),
        )
        stdout_summary_state = "EMITTED"
        projection_phase = "STDOUT_EMITTED"
        projection.write(
            phase=projection_phase,
            outcome="IN_PROGRESS",
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        latch_pending_control_signal()
        complete = forced_signal_completion_gate_met(
            supervisor_interruption=SUPERVISOR_INTERRUPTION,
            outcome=outcome,
            receipt=receipt,
            root_snapshot=root_snapshot,
            reservation_absent=reservation_absent,
            docker_snapshot=docker_snapshot,
        )
        projection_phase = "COMPLETE"
        projection.write(
            phase=projection_phase,
            outcome=classify_projection_outcome(outcome, receipt, root_snapshot, docker_snapshot, complete),
            receipt_state=receipt_state,
            root_snapshot_state=root_snapshot_state,
            stdout_summary_state=stdout_summary_state,
        )
        return 0 if complete else 1
    except Exception:
        try:
            projection.write(
                phase="FAILED",
                outcome=projection_exception_outcome(projection_phase),
                receipt_state=receipt_state,
                root_snapshot_state=root_snapshot_state,
                stdout_summary_state=stdout_summary_state,
            )
        except Exception:
            pass
        raise
    finally:
        projection.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"INT-001 forced-signal supervisor: {exc}", file=sys.stderr)
        raise SystemExit(2)
