#!/usr/bin/env python3
"""Run the owner's isolated Workbench FAP canary beside the legacy deployment.

Only ``temp/personal-fap-canary`` and the new FAP platform state are owned by
this controller.  The legacy 8112 deployment, its database, and 303x Workers
are never inspected for data or stopped.  Shutdown uses recorded process
identity, never a port lookup.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PLATFORM_ROOT = Path(
    os.environ.get("FAP_PLATFORM_REPOSITORY", "/home/sa/workspace/foggy-agent-platform")
).resolve()
PLATFORM_CONTROLLER = PLATFORM_ROOT / "scripts/personal_canary_stack.py"
PLATFORM_STATE_ROOT = PLATFORM_ROOT / ".foggy-dev/personal-canary"
STATE_ROOT = REPOSITORY_ROOT / "temp/personal-fap-canary"
PROCESS_ROOT = STATE_ROOT / "processes"
LOG_ROOT = STATE_ROOT / "logs"
SECRETS_PATH = STATE_ROOT / "secrets.json"
LOGIN_PATH = STATE_ROOT / "login.json"
OWNER_PATH = STATE_ROOT / "owner.json"
PROPERTIES_PATH = STATE_ROOT / "personal-canary.properties"
BACKEND_PORT = 8122
FRONTEND_PORT = 5175
BACKEND_URL = f"http://127.0.0.1:{BACKEND_PORT}"
COMPONENTS = ("backend", "frontend")
TERMINAL_KINDS = {"SUCCEEDED", "FAILED", "CANCELLED", "REJECTED", "EXPIRED"}


def main() -> int:
    parser = argparse.ArgumentParser(description="Personal Workbench FAP canary controller")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("start")
    subparsers.add_parser("stop")
    subparsers.add_parser("status")
    smoke_parser = subparsers.add_parser("smoke")
    smoke_parser.add_argument("--timeout-seconds", type=int, default=600)
    arguments = parser.parse_args()
    if arguments.command == "start":
        start_canary()
    elif arguments.command == "stop":
        stop_canary()
    elif arguments.command == "smoke":
        focused_smoke(arguments.timeout_seconds)
    else:
        print_status()
    return 0


def start_canary() -> None:
    ensure_private_directories()
    states = {component: process_state(component) for component in COMPONENTS}
    if all(state == "RUNNING" for state in states.values()):
        print_status()
        return
    if any(state != "STOPPED" for state in states.values()):
        raise RuntimeError(f"PERSONAL_WORKBENCH_PARTIAL_OR_UNSAFE_STATE: {states}")
    assert_artifacts()
    assert_port_available(BACKEND_PORT)
    assert_port_available(FRONTEND_PORT)
    private = load_or_create_secrets()

    if not OWNER_PATH.is_file():
        write_properties(private, None, False, None)
        spawn_backend()
        try:
            wait_http(f"{BACKEND_URL}/actuator/health", "backend", timeout=120)
            login = navigator_login(private)
            owner_id = required_text(login["user"].get("id"), "root user id")
            write_private_json(
                OWNER_PATH,
                {
                    "schema": "navigator.personal-fap-owner.v1",
                    "userId": owner_id,
                    "username": private["rootUsername"],
                },
                exclusive=True,
            )
        finally:
            stop_component("backend")

    owner_id = required_text(read_json(OWNER_PATH).get("userId"), "owner user id")
    platform_was_running = platform_running()
    subprocess.run(
        [
            required_executable("python3"),
            str(PLATFORM_CONTROLLER),
            "start",
            "--owner-user-id",
            owner_id,
        ],
        cwd=PLATFORM_ROOT,
        check=True,
    )
    platform_private = read_json(PLATFORM_STATE_ROOT / "secrets.json")
    deployment = read_json(PLATFORM_STATE_ROOT / "deployment.json")
    if deployment.get("ownerUserId") != owner_id:
        raise RuntimeError("PLATFORM_OWNER_BINDING_MISMATCH")

    started: list[str] = []
    try:
        write_properties(private, owner_id, True, platform_private)
        spawn_backend()
        started.append("backend")
        wait_http(f"{BACKEND_URL}/actuator/health", "backend", timeout=120)
        login = navigator_login(private)
        if login["user"].get("id") != owner_id:
            raise RuntimeError("NAVIGATOR_OWNER_ID_CHANGED")
        availability = rx_data(
            request_json(
                "GET",
                f"{BACKEND_URL}/api/v1/workbench/fap/availability",
                bearer=login["token"],
            )
        )
        if not all(availability.get(key) is True for key in ("packaged", "enabled", "eligible")):
            raise RuntimeError(f"FAP_PERSONAL_CANARY_GATE_CLOSED: {availability}")

        spawn_frontend()
        started.append("frontend")
        wait_http(f"http://127.0.0.1:{FRONTEND_PORT}/", "frontend", timeout=45)
    except Exception:
        for component in reversed(started):
            stop_component(component)
        if not platform_was_running:
            platform_command("stop")
        raise
    print_status()


def stop_canary() -> None:
    for component in reversed(COMPONENTS):
        stop_component(component)
    if PLATFORM_CONTROLLER.is_file():
        platform_command("stop")
    print_status()


def focused_smoke(timeout_seconds: int) -> None:
    if process_state("backend") != "RUNNING":
        raise RuntimeError("PERSONAL_WORKBENCH_BACKEND_NOT_RUNNING")
    private = load_or_create_secrets()
    login = navigator_login(private)
    bearer = login["token"]
    for resource_type in ("WORKER_PROFILE", "WORKSPACE", "MODEL_CONFIG"):
        catalog = rx_data(
            request_json(
                "GET",
                f"{BACKEND_URL}/api/v1/workbench/fap/catalog?"
                + urlencode({"resourceType": resource_type}),
                bearer=bearer,
            )
        )
        if not catalog.get("entries"):
            raise RuntimeError(f"FAP_CATALOG_EMPTY_{resource_type}")

    start_marker = f"FAP_WORKBENCH_START_{uuid.uuid4().hex[:10].upper()}"
    start = rx_data(
        request_json(
            "POST",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations",
            bearer=bearer,
            body={
                "requestId": f"canary-start-{uuid.uuid4()}",
                "title": "Personal FAP focused canary",
                "workerProfileRef": "codex-personal-default",
                "workspaceRef": "sim-navi",
                "modelConfigRef": "codex-personal-model",
                "allowDefaultModelConfig": False,
                "prompt": f"只回复 {start_marker}，不调用任何工具。",
                "providerOptions": {
                    "namespace": "foggy.codex",
                    "version": "1",
                    "payload": {"reasoningEffort": "low"},
                },
            },
        )
    )
    conversation_id = required_text(start.get("conversationId"), "conversation id")
    start_terminal = wait_conversation(conversation_id, bearer, timeout_seconds)
    assert_succeeded(start_terminal, "START")
    start_events = events(conversation_id, bearer)
    if start_marker not in json.dumps(start_events, ensure_ascii=False):
        raise RuntimeError("FAP_START_OUTPUT_MARKER_NOT_OBSERVED")
    if "worker.operation.input.accepted" not in json.dumps(start_events):
        raise RuntimeError("FAP_SAFE_INPUT_FACT_NOT_OBSERVED")

    recovery = rx_data(
        request_json(
            "GET",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}/recovery",
            bearer=bearer,
        )
    )
    reattach = rx_data(
        request_json(
            "POST",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}:reattach",
            bearer=bearer,
            body={"requestId": f"canary-reattach-{uuid.uuid4()}"},
        )
    )

    continue_marker = f"FAP_WORKBENCH_CONTINUE_{uuid.uuid4().hex[:10].upper()}"
    continued = rx_data(
        request_json(
            "POST",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}/tasks",
            bearer=bearer,
            body={
                "requestId": f"canary-continue-{uuid.uuid4()}",
                "prompt": f"只回复 {continue_marker}，不调用任何工具。",
                "providerOptions": {
                    "namespace": "foggy.codex",
                    "version": "1",
                    "payload": {"reasoningEffort": "low"},
                },
            },
        )
    )
    if continued.get("conversationId") != conversation_id:
        raise RuntimeError("FAP_CONTINUE_CONVERSATION_CHANGED")
    continue_terminal = wait_conversation(conversation_id, bearer, timeout_seconds)
    assert_succeeded(continue_terminal, "CONTINUE")
    continue_events = events(conversation_id, bearer)
    if continue_marker not in json.dumps(continue_events, ensure_ascii=False):
        raise RuntimeError("FAP_CONTINUE_OUTPUT_MARKER_NOT_OBSERVED")

    resources = rx_data(
        request_json(
            "GET",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}/resources",
            bearer=bearer,
        )
    )
    print(
        json.dumps(
            {
                "status": "PASSED",
                "conversationId": conversation_id,
                "startTerminal": start_terminal.get("terminalKind"),
                "continueTerminal": continue_terminal.get("terminalKind"),
                "safeInputFact": True,
                "recoveryLoaded": bool(recovery),
                "reattachAccepted": bool(reattach),
                "resourceCount": len(resources.get("items", resources.get("resources", []))),
            },
            ensure_ascii=False,
        )
    )


def wait_conversation(conversation_id: str, bearer: str, timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        current = rx_data(
            request_json(
                "GET",
                f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}",
                bearer=bearer,
            )
        )
        if current.get("definitiveTerminal") is True:
            return current
        time.sleep(0.5)
    raise TimeoutError("FAP_WORKBENCH_TASK_DID_NOT_BECOME_TERMINAL")


def assert_succeeded(conversation: dict[str, Any], operation: str) -> None:
    terminal = conversation.get("terminalKind")
    if terminal not in TERMINAL_KINDS or terminal != "SUCCEEDED":
        raise RuntimeError(
            f"FAP_{operation}_NOT_SUCCEEDED: {terminal}/{conversation.get('lastErrorCode')}"
        )


def events(conversation_id: str, bearer: str) -> dict[str, Any]:
    return rx_data(
        request_json(
            "GET",
            f"{BACKEND_URL}/api/v1/workbench/fap/conversations/{conversation_id}/events?"
            + urlencode({"afterSeq": 0, "limit": 500}),
            bearer=bearer,
        )
    )


def navigator_login(private: dict[str, str]) -> dict[str, Any]:
    return rx_data(
        request_json(
            "POST",
            f"{BACKEND_URL}/api/v1/auth/login",
            body={
                "username": private["rootUsername"],
                "password": private["rootPassword"],
            },
        )
    )


def write_properties(
    private: dict[str, str],
    owner_user_id: str | None,
    enabled: bool,
    platform_private: dict[str, str] | None,
) -> None:
    properties = {
        "server.address": "127.0.0.1",
        "server.port": str(BACKEND_PORT),
        "spring.datasource.url": (
            f"jdbc:h2:file:{STATE_ROOT / 'navigator-db'};MODE=MySQL;"
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE"
        ),
        "spring.datasource.username": "sa",
        "spring.datasource.password": "",
        "spring.datasource.driver-class-name": "org.h2.Driver",
        "spring.datasource.hikari.maximum-pool-size": "5",
        "spring.datasource.hikari.minimum-idle": "1",
        "spring.jpa.hibernate.ddl-auto": "update",
        "spring.jpa.database-platform": "org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect": "org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.jdbc.time_zone": "UTC",
        "jwt.secret": private["jwtSecret"],
        "jwt.expiration": "86400",
        "system.root.username": private["rootUsername"],
        "system.root.password": private["rootPassword"],
        "system.root.email": private["rootEmail"],
        "system.root.password-reset": "false",
        "navigator.security.credential-key": private["credentialKey"],
        "navigator.security.credential-salt": private["credentialSalt"],
        "navigator.background-recovery.global.enabled": "false",
        "navigator.cross-project-task.mutations-enabled": "false",
        "navigator.external.enabled": "false",
        "navigator.worker-gateway.external-enabled": "false",
        "navigator.lifecycle.shadow-enabled": "false",
        "navigator.lifecycle.activation.control-enabled": "false",
        "navigator.lifecycle.activation.admission-enabled": "false",
        "navigator.workbench.fap.enabled": str(enabled).lower(),
        "navigator.workbench.fap.access-base-uri": "http://127.0.0.1:4860",
        "navigator.workbench.fap.runtime-base-uri": "http://127.0.0.1:4850",
        "navigator.workbench.fap.caller-application-ref": "navigator-workbench",
        "navigator.workbench.fap.internal-principal-prefix": "navigator-user:",
        "navigator.workbench.fap.environment-class": "DEV",
        "navigator.workbench.fap.timeout-seconds": "30",
        "management.endpoints.web.exposure.include": "health,info",
    }
    if enabled:
        if owner_user_id is None or platform_private is None:
            raise RuntimeError("FAP_ENABLED_PROPERTIES_REQUIRE_OWNER_AND_PLATFORM_SECRETS")
        properties["navigator.workbench.fap.owner-user-ids[0]"] = owner_user_id
        properties["navigator.workbench.fap.access-bearer-token"] = platform_private[
            "accessCallerCredential"
        ]
        properties["navigator.workbench.fap.runtime-bearer-token"] = platform_private[
            "runtimeManagedCredential"
        ]
    write_private_text(
        PROPERTIES_PATH,
        "".join(f"{key}={property_value(value)}\n" for key, value in properties.items()),
    )


def spawn_backend() -> None:
    spawn(
        "backend",
        [
            required_executable("java"),
            "-Xms256m",
            "-Xmx1g",
            "-Dfile.encoding=UTF-8",
            "-jar",
            str(REPOSITORY_ROOT / "launcher/target/launcher-1.0.0-SNAPSHOT.jar"),
        ],
        REPOSITORY_ROOT,
        clean_navigator_environment(
            {"SPRING_CONFIG_ADDITIONAL_LOCATION": f"optional:file:{PROPERTIES_PATH}"}
        ),
    )


def spawn_frontend() -> None:
    vite = REPOSITORY_ROOT / "packages/navigator-frontend/node_modules/vite/bin/vite.js"
    spawn(
        "frontend",
        [
            required_executable("node"),
            str(vite),
            "--host",
            "127.0.0.1",
            "--port",
            str(FRONTEND_PORT),
            "--strictPort",
        ],
        REPOSITORY_ROOT / "packages/navigator-frontend",
        clean_navigator_environment({"VITE_API_PROXY_TARGET": BACKEND_URL}),
    )


def load_or_create_secrets() -> dict[str, str]:
    if SECRETS_PATH.is_file():
        result = read_json(SECRETS_PATH)
        if result.get("schema") != "navigator.personal-fap-secrets.v1":
            raise RuntimeError("PERSONAL_FAP_SECRETS_SCHEMA_MISMATCH")
        return result
    result = {
        "schema": "navigator.personal-fap-secrets.v1",
        "rootUsername": "root",
        "rootPassword": secrets.token_urlsafe(20),
        "rootEmail": "root.personal-fap@foggy.local",
        "jwtSecret": secrets.token_urlsafe(48),
        "credentialKey": secrets.token_urlsafe(32),
        "credentialSalt": secrets.token_hex(16),
    }
    write_private_json(SECRETS_PATH, result, exclusive=True)
    write_private_json(
        LOGIN_PATH,
        {
            "url": f"http://localhost:{FRONTEND_PORT}",
            "username": result["rootUsername"],
            "password": result["rootPassword"],
        },
        exclusive=True,
    )
    return result


def platform_running() -> bool:
    if not PLATFORM_CONTROLLER.is_file():
        raise RuntimeError("PLATFORM_PERSONAL_CANARY_CONTROLLER_NOT_FOUND")
    result = subprocess.run(
        [required_executable("python3"), str(PLATFORM_CONTROLLER), "status"],
        cwd=PLATFORM_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    status = json.loads(result.stdout)
    return all(value == "RUNNING" for value in status["components"].values())


def platform_command(command: str) -> None:
    subprocess.run(
        [required_executable("python3"), str(PLATFORM_CONTROLLER), command],
        cwd=PLATFORM_ROOT,
        check=True,
    )


def print_status() -> None:
    platform: dict[str, Any] | str = "NOT_CONFIGURED"
    if PLATFORM_CONTROLLER.is_file():
        result = subprocess.run(
            [required_executable("python3"), str(PLATFORM_CONTROLLER), "status"],
            cwd=PLATFORM_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        platform = json.loads(result.stdout)
    print(
        json.dumps(
            {
                "stack": "workbench-personal-fap",
                "components": {
                    component: process_state(component) for component in COMPONENTS
                },
                "platform": platform,
                "url": f"http://localhost:{FRONTEND_PORT}",
                "loginFile": str(LOGIN_PATH) if LOGIN_PATH.is_file() else None,
                "legacyDeploymentTouched": False,
            },
            ensure_ascii=False,
        )
    )


def spawn(name: str, command: list[str], cwd: Path, environment: dict[str, str]) -> None:
    record_path = process_record_path(name)
    if record_path.exists():
        raise RuntimeError(f"{name.upper()}_PROCESS_RECORD_ALREADY_EXISTS")
    log_path = LOG_ROOT / f"{name}.log"
    log_descriptor = os.open(log_path, os.O_CREAT | os.O_APPEND | os.O_WRONLY, 0o600)
    os.chmod(log_path, 0o600)
    with os.fdopen(log_descriptor, "ab", buffering=0) as log_handle:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name.upper()}_EXITED_DURING_START")
        try:
            ticks = process_start_ticks(process.pid)
            break
        except FileNotFoundError:
            time.sleep(0.02)
    else:
        process.terminate()
        raise RuntimeError(f"{name.upper()}_PROCESS_IDENTITY_UNAVAILABLE")
    if os.getpgid(process.pid) != process.pid:
        process.terminate()
        raise RuntimeError(f"{name.upper()}_PROCESS_GROUP_NOT_ISOLATED")
    write_private_json(
        record_path,
        {
            "schema": "navigator.process-identity.v1",
            "name": name,
            "pid": process.pid,
            "startTicks": ticks,
            "processGroupId": process.pid,
            "cwd": str(cwd.resolve()),
            "argv": command,
        },
        exclusive=True,
    )


def stop_component(name: str) -> None:
    path = process_record_path(name)
    if not path.is_file():
        return
    record = read_json(path)
    pid = int(record["pid"])
    if process_absent_or_zombie(pid):
        path.unlink()
        return
    try:
        assert_process_identity(record)
    except (FileNotFoundError, ProcessLookupError):
        if process_absent_or_zombie(pid):
            path.unlink(missing_ok=True)
            return
        raise
    os.killpg(pid, signal.SIGTERM)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and not process_absent_or_zombie(pid):
        time.sleep(0.1)
    if not process_absent_or_zombie(pid):
        assert_process_identity(record)
        os.killpg(pid, signal.SIGKILL)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not process_absent_or_zombie(pid):
            time.sleep(0.05)
    if not process_absent_or_zombie(pid):
        raise RuntimeError(f"{name.upper()}_DID_NOT_STOP")
    path.unlink(missing_ok=True)


def assert_process_identity(record: dict[str, Any]) -> None:
    pid = int(record["pid"])
    actual = {
        "startTicks": process_start_ticks(pid),
        "cwd": str(Path(f"/proc/{pid}/cwd").resolve()),
        "argv": process_argv(pid),
        "processGroupId": os.getpgid(pid),
    }
    if (
        actual["startTicks"] != int(record["startTicks"])
        or actual["cwd"] != record["cwd"]
        or actual["argv"] != record["argv"]
        or actual["processGroupId"] != int(record["processGroupId"])
        or actual["processGroupId"] != pid
    ):
        raise RuntimeError(f"PROCESS_IDENTITY_MISMATCH_{record['name'].upper()}")


def process_state(name: str) -> str:
    path = process_record_path(name)
    if not path.is_file():
        return "STOPPED"
    record = read_json(path)
    pid = int(record["pid"])
    if process_absent_or_zombie(pid):
        path.unlink()
        return "STOPPED"
    try:
        assert_process_identity(record)
        return "RUNNING"
    except (FileNotFoundError, ProcessLookupError, RuntimeError):
        return "IDENTITY_MISMATCH"


def wait_http(url: str, name: str, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process_state(name) != "RUNNING":
            raise RuntimeError(f"{name.upper()}_EXITED_BEFORE_READINESS")
        try:
            with urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (URLError, TimeoutError):
            pass
        time.sleep(0.25)
    raise TimeoutError(f"{name.upper()}_READINESS_TIMED_OUT")


def request_json(
    method: str,
    url: str,
    *,
    bearer: str | None = None,
    body: dict[str, Any] | None = None,
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    encoded = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        encoded = json.dumps(body).encode()
    request = Request(url, data=encoded, headers=headers, method=method)
    try:
        with urlopen(request, timeout=35) as response:
            return json.loads(response.read().decode())
    except HTTPError as error:
        try:
            payload = json.loads(error.read().decode())
            code = payload.get("code") or payload.get("message") or error.reason
        except (json.JSONDecodeError, UnicodeDecodeError):
            code = error.reason
        raise RuntimeError(f"HTTP_{error.code}: {code}") from error


def rx_data(payload: dict[str, Any]) -> dict[str, Any]:
    data = payload.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("NAVIGATOR_RESPONSE_DATA_MISSING")
    return data


def assert_artifacts() -> None:
    required = (
        REPOSITORY_ROOT / "launcher/target/launcher-1.0.0-SNAPSHOT.jar",
        REPOSITORY_ROOT / "packages/navigator-frontend/node_modules/vite/bin/vite.js",
        PLATFORM_CONTROLLER,
    )
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise RuntimeError(f"PERSONAL_FAP_ARTIFACTS_MISSING: {missing}")


def ensure_private_directories() -> None:
    for path in (STATE_ROOT, PROCESS_ROOT, LOG_ROOT):
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.chmod(0o700)


def clean_navigator_environment(values: dict[str, str]) -> dict[str, str]:
    result = {
        key: value
        for key, value in os.environ.items()
        if not key.startswith(("NAVIGATOR_", "SYSTEM_ROOT_", "JWT_"))
        and key
        not in {
            "SERVER_PORT",
            "SERVER_ADDRESS",
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_PROFILES_ACTIVE",
            "SPRING_CONFIG_ADDITIONAL_LOCATION",
        }
    }
    result.update(values)
    return result


def assert_port_available(port: int) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as candidate:
        candidate.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            candidate.bind(("127.0.0.1", port))
        except OSError as error:
            raise RuntimeError(f"PERSONAL_FAP_PORT_{port}_NOT_AVAILABLE") from error


def process_start_ticks(pid: int) -> int:
    value = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    return int(value[value.rfind(")") + 2 :].split()[19])


def process_lifecycle_state(pid: int) -> str:
    value = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    return value[value.rfind(")") + 2 :].split()[0]


def process_absent_or_zombie(pid: int) -> bool:
    try:
        return process_lifecycle_state(pid) == "Z"
    except FileNotFoundError:
        return True


def process_argv(pid: int) -> list[str]:
    raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    return [item.decode() for item in raw.split(b"\0") if item]


def process_record_path(name: str) -> Path:
    return PROCESS_ROOT / f"{name}.json"


def required_executable(name: str) -> str:
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(directory) / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate.resolve())
    raise RuntimeError(f"EXECUTABLE_NOT_FOUND_{name.upper()}")


def required_text(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(f"{name.upper().replace(' ', '_')}_REQUIRED")
    return value.strip()


def property_value(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_private_json(
    path: Path, value: object, *, exclusive: bool = False
) -> None:
    write_private_text(
        path,
        json.dumps(value, sort_keys=True, separators=(",", ":")),
        exclusive=exclusive,
    )


def write_private_text(path: Path, value: str, *, exclusive: bool = False) -> None:
    flags = os.O_CREAT | os.O_WRONLY | (os.O_EXCL if exclusive else os.O_TRUNC)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(value)
    path.chmod(0o600)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"personal Workbench canary failed: {error}", file=sys.stderr)
        raise SystemExit(1)
