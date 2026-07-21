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
RECEIPT_FIELDS = {
    "schemaVersion",
    "runId",
    "result",
    "failureStage",
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


def repository_root() -> Path:
    root = Path(__file__).resolve().parents[3]
    if not (root / "pom.xml").is_file():
        raise RuntimeError("repository root cannot be verified")
    return root


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


def listener_inode_for_loopback_port(port: int) -> int | None:
    """Return one IPv4 loopback LISTEN inode, rejecting every ambiguity."""
    if not 1 <= port <= 65535:
        return None
    port_hex = f"{port:04X}"
    expected_inodes: list[int] = []
    for proc_net in (Path("/proc/net/tcp"), Path("/proc/net/tcp6")):
        try:
            lines = proc_net.read_text(encoding="utf-8").splitlines()
        except OSError:
            return None
        if not lines:
            return None
        for raw in lines[1:]:
            fields = raw.split()
            if len(fields) < 10:
                return None
            local_address = fields[1]
            state = fields[3]
            address, separator, observed_port = local_address.rpartition(":")
            if (
                not separator
                or len(observed_port) != 4
                or any(character not in "0123456789ABCDEFabcdef" for character in observed_port)
            ):
                return None
            if state != "0A" or observed_port.upper() != port_hex:
                continue
            # The Launcher is configured for literal 127.0.0.1.  A wildcard,
            # non-loopback, or IPv6 listener on the same port makes the health
            # endpoint ambiguous, even if a loopback socket also exists.
            if proc_net.name != "tcp" or address != "0100007F" or not fields[9].isdigit():
                return None
            expected_inodes.append(int(fields[9]))
    return expected_inodes[0] if len(expected_inodes) == 1 else None


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


def command_line(pid: int) -> list[str] | None:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
        if not raw.endswith(b"\0"):
            return None
        return [part.decode("utf-8", "surrogateescape") for part in raw.split(b"\0")[:-1]]
    except (OSError, UnicodeDecodeError):
        return None


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
    socket_inode = listener_inode_for_loopback_port(port)
    if socket_inode is None:
        return ListenerProof(False, "socket-listener")
    holders = current_uid_socket_holders(socket_inode)
    if holders is None or len(holders) != 1:
        return ListenerProof(False, "socket-owner")
    pid = holders[0]
    try:
        if Path(f"/proc/{pid}").stat().st_uid != os.getuid() or not proc_is_live(pid):
            return ListenerProof(False, "listener-process")
        initial_start_ticks = proc_stat(pid)[2]
        if Path(f"/proc/{pid}/cwd").resolve() != run_dir:
            return ListenerProof(False, "listener-cwd")
        java = trusted_java_executable()
        if java is None or Path(f"/proc/{pid}/exe").resolve(strict=True) != java:
            return ListenerProof(False, "listener-java")
        if command_line(pid) != expected_argv:
            return ListenerProof(False, "listener-argv")
        if not is_descendant_of(pid, exercise_pid, exercise_start_ticks):
            return ListenerProof(False, "listener-ancestor")
        if proc_stat(pid)[2] != initial_start_ticks or not proc_is_live(pid):
            return ListenerProof(False, "listener-start-ticks")
        if listener_inode_for_loopback_port(port) != socket_inode:
            return ListenerProof(False, "listener-inode")
        if current_uid_socket_holders(socket_inode) != (pid,):
            return ListenerProof(False, "socket-owner")
        return ListenerProof(True, "uid+java+argv+cwd+ancestor+socket+startTicks", pid, initial_start_ticks, socket_inode)
    except (OSError, ValueError, RuntimeError):
        return ListenerProof(False, "unavailable")


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
        return ParentProof(True, "commandLine+cwd+runId+uid+session+startTicks", final_start_ticks)
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
            ListenerProof(False, "signal-mask-unavailable"),
            0,
            False,
        )
    try:
        prior_mask = signal_mask(signal.SIG_BLOCK, CONTROL_SIGNALS)
    except (OSError, RuntimeError, ValueError, TypeError):
        return (
            ParentProof(False, "signal-mask-unavailable"),
            ListenerProof(False, "signal-mask-unavailable"),
            0,
            False,
        )

    parent_proof = ParentProof(False, "not-reproved")
    listener_proof = ListenerProof(False, "not-reproved")
    term_dispatches = 0
    dispatch_safe = False
    try:
        if set(prior_mask) & CONTROL_SIGNALS:
            # ``main`` rejects this before fork, but the helper also refuses a
            # direct caller whose child could have inherited a blocked mask.
            parent_proof = ParentProof(False, "signal-mask-preblocked")
            listener_proof = ListenerProof(False, "signal-mask-preblocked")
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
                if not same_listener(prior_listener_proof, listener_proof):
                    listener_proof = ListenerProof(False, "listener-changed")
                elif not latch_pending_control_signal():
                    # This final observation is the deliberate dispatch
                    # commit point. POSIX cannot atomically test pending
                    # signals and signal another PID. A control signal seen
                    # after this point may leave one TERM dispatched, but it
                    # can never leave a dispatch-safe/success-shaped result.
                    os.kill(child_pid, signal.SIGTERM)
                    term_dispatches = 1
                    if latch_pending_control_signal():
                        listener_proof = ListenerProof(False, "signal-pending-after-dispatch")
                    else:
                        dispatch_safe = True
        elif SUPERVISOR_INTERRUPTION is None:
            parent_proof = ParentProof(False, "signal-pending")
            listener_proof = ListenerProof(False, "signal-pending")
    except (OSError, TypeError):
        pass
    finally:
        try:
            signal_mask(signal.SIG_SETMASK, prior_mask)
        except (OSError, RuntimeError, ValueError, TypeError):
            listener_proof = ListenerProof(False, "signal-mask-restore")
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
    term_dispatches = 0
    dispatch_safe = False

    while SUPERVISOR_INTERRUPTION is None and time.monotonic() < deadline:
        child_exit = poll_child_exit(child_pid)
        if child_exit is not None:
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
        if not listener_proof_a.ok:
            time.sleep(0.5)
            continue
        if SUPERVISOR_INTERRUPTION is not None:
            break
        parent_proof = prove_exercise_parent(child_pid, initial_start_ticks, repo_root, expected_argv)
        if not parent_proof.ok or SUPERVISOR_INTERRUPTION is not None:
            break
        if not health_ready(navigator_port):
            time.sleep(0.5)
            continue
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
        if not same_listener(listener_proof_a, listener_proof_b):
            listener_proof = ListenerProof(False, "listener-changed")
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
        or value["schemaVersion"] != 3
        or value.get("runId") != run_id
        or value.get("secretsRedacted") is not True
    ):
        return None
    if value.get("result") not in CLEANUP_RESULTS:
        return None
    if value.get("failureStage") not in CLEANUP_FAILURE_STAGES:
        return None
    if value.get("launcherReadinessObservation") not in LAUNCHER_READINESS_OBSERVATIONS:
        return None
    if value.get("launcherFailureClass") not in LAUNCHER_FAILURE_CLASSES:
        return None
    if not isinstance(value.get("finishedAtUtc"), str) or not UTC_TIMESTAMP_RE.fullmatch(value["finishedAtUtc"]):
        return None
    return {
        "mode": "0600",
        "schemaVersion": 3,
        "result": value["result"],
        "failureStage": value["failureStage"],
        "secretsRedacted": True,
    }


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


def emit_summary(
    *,
    run_id: str,
    health_precondition: bool,
    parent_proof: ParentProof | None,
    listener_proof: ListenerProof | None,
    term_dispatches: int,
    dispatch_safe: bool,
    child_exit: int | None,
    receipt: dict[str, Any] | None,
    root_snapshot: RunRootSnapshot,
    docker_snapshot: dict[str, int | None],
) -> None:
    summary = {
        "schemaVersion": 1,
        "runId": run_id,
        "controlledHealthPrecondition": health_precondition,
        "parentProof": parent_proof.reason if parent_proof else "NOT_ATTEMPTED",
        "listenerProof": listener_proof.reason if listener_proof else "NOT_ATTEMPTED",
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


def main() -> int:
    global SUPERVISOR_INTERRUPTION
    SUPERVISOR_INTERRUPTION = None
    args = parse_args()
    install_supervisor_signal_handlers()
    assert_control_signal_startable()
    repo_root = repository_root()
    harness = repo_root / "tools/navigator-upstream/scripts/synthetic-upstream-harness.sh"
    artifact_root = assert_artifact_root(repo_root)
    run_dir = artifact_root / args.run_id
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
    child_pid, initial_start_ticks = start_exercise(repo_root, harness, args.run_id, navigator_port)
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
    # Collect each evidence source at most once.  The summary and completion
    # decision consume the same snapshot, so a changing Docker state cannot
    # make the recorded result disagree with the exit code.
    receipt: dict[str, Any] | None = None
    root_snapshot = RunRootSnapshot(None, None)
    docker_snapshot: dict[str, int | None] = {"container": None, "network": None, "volume": None}
    if not latch_pending_control_signal():
        receipt = read_redacted_receipt(run_dir, args.run_id, artifact_root)
        if not latch_pending_control_signal():
            root_snapshot = run_root_snapshot(run_dir, artifact_root)
        if not latch_pending_control_signal():
            docker_snapshot = docker_residue_counts(args.run_id)
    # A supervisor interruption makes this rehearsal ineligible even if it
    # arrived after a deliberate TERM.  Do not serialize stale success-shaped
    # evidence that was sampled while an external stop was pending.
    if latch_pending_control_signal():
        receipt = None
        root_snapshot = RunRootSnapshot(None, None)
        docker_snapshot = {"container": None, "network": None, "volume": None}

    emit_summary(
        run_id=args.run_id,
        health_precondition=outcome.health_precondition,
        parent_proof=outcome.parent_proof,
        listener_proof=outcome.listener_proof,
        term_dispatches=outcome.term_dispatches,
        dispatch_safe=outcome.dispatch_safe,
        child_exit=outcome.child_exit,
        receipt=receipt,
        root_snapshot=root_snapshot,
        docker_snapshot=docker_snapshot,
    )
    complete = (
        not latch_pending_control_signal()
        and outcome.health_precondition
        and outcome.parent_proof is not None
        and outcome.parent_proof.ok
        and outcome.listener_proof is not None
        and outcome.listener_proof.ok
        and outcome.term_dispatches == 1
        and outcome.dispatch_safe
        and outcome.child_exit is not None
        and receipt is not None
        and receipt["result"] == "CLEANED"
        and receipt["failureStage"] == "SIGNAL"
        and root_snapshot.private_absent is True
        and root_snapshot.nonreceipt_residue_count == 0
        and docker_snapshot == {"container": 0, "network": 0, "volume": 0}
    )
    return 0 if complete else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"INT-001 forced-signal supervisor: {exc}", file=sys.stderr)
        raise SystemExit(2)
