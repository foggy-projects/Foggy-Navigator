#!/usr/bin/env python3
"""Content-free live observer for an explicitly bounded local dev target.

The observer never reads credential values.  Worker lifecycle authentication is
performed independently by Navigator during proof acquisition; this tool binds
the exact Navigator process/artifact, database container, isolated WSL Worker
installation, loopback endpoints, and controller inventory to one manifest.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from activation_target import canonical_controller_digest, manifest_digest


MANIFEST_SCHEMA = "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2"
OBSERVATION_SCHEMA = "NAVIGATOR_ARCH001_CONTROLLER_OBSERVATION_V1"
TARGET_CLASS = "BOUNDED_ISOLATED_LOCAL_DEVELOPMENT"
PROVIDER = "codex-worker"
PROVIDER_LANE = "REAL_CODEX_MODEL"


class ObservationDenied(RuntimeError):
    pass


def deny(reason: str) -> None:
    raise ObservationDenied(reason)


def required_text(value: Any, reason: str) -> str:
    if not isinstance(value, str) or not value.strip():
        deny(reason)
    return value.strip()


def read_json(path: Path) -> dict[str, Any]:
    require_owned_file(path, path.parent)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        deny("BOUNDED_LOCAL_DEV_MANIFEST_UNAVAILABLE")
    if not isinstance(value, dict):
        deny("BOUNDED_LOCAL_DEV_MANIFEST_INVALID")
    return value


def require_owned_file(path: Path, root: Path) -> None:
    try:
        resolved_root = root.resolve(strict=True)
        resolved = path.resolve(strict=True)
        mode = resolved.stat().st_mode
    except OSError:
        deny("BOUNDED_LOCAL_DEV_ARTIFACT_UNAVAILABLE")
    if path.is_symlink() or not resolved.is_file() or not resolved.is_relative_to(resolved_root):
        deny("BOUNDED_LOCAL_DEV_ARTIFACT_OWNERSHIP_UNPROVEN")
    if mode & (stat.S_IWGRP | stat.S_IWOTH):
        deny("BOUNDED_LOCAL_DEV_ARTIFACT_PERMISSIONS_INVALID")


def validate_manifest(manifest: dict[str, Any], manifest_path: Path) -> dict[str, Any]:
    if manifest.get("schema") != MANIFEST_SCHEMA:
        deny("BOUNDED_LOCAL_DEV_MANIFEST_SCHEMA_MISMATCH")
    if manifest.get("targetClass") != TARGET_CLASS:
        deny("BOUNDED_LOCAL_DEV_TARGET_CLASS_MISMATCH")
    if manifest.get("providerEvidenceLane") != PROVIDER_LANE:
        deny("BOUNDED_LOCAL_DEV_PROVIDER_LANE_MISMATCH")
    exact = manifest.get("exactTuple")
    target = manifest.get("target")
    worker = manifest.get("worker")
    local = manifest.get("localDevelopment")
    candidate = manifest.get("candidate")
    if not all(isinstance(value, dict) for value in (exact, target, worker, local, candidate)):
        deny("BOUNDED_LOCAL_DEV_MANIFEST_INVALID")
    if exact.get("providerType") != PROVIDER:
        deny("BOUNDED_LOCAL_DEV_PROVIDER_MISMATCH")
    if target.get("host") != "127.0.0.1":
        deny("BOUNDED_LOCAL_DEV_NON_LOOPBACK_TARGET")
    root = Path(required_text(target.get("root"), "BOUNDED_LOCAL_DEV_ROOT_REQUIRED"))
    try:
        real_root = root.resolve(strict=True)
    except OSError:
        deny("BOUNDED_LOCAL_DEV_ROOT_UNAVAILABLE")
    if root.is_symlink() or not real_root.is_dir() or real_root != root.absolute():
        deny("BOUNDED_LOCAL_DEV_ROOT_OWNERSHIP_UNPROVEN")
    if real_root.stat().st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        deny("BOUNDED_LOCAL_DEV_ROOT_PERMISSIONS_INVALID")
    if not manifest_path.resolve(strict=True).is_relative_to(real_root):
        deny("BOUNDED_LOCAL_DEV_MANIFEST_OWNERSHIP_UNPROVEN")
    if int(target.get("navigatorPort", 0)) <= 0 or int(target.get("workerPort", 0)) <= 0:
        deny("BOUNDED_LOCAL_DEV_PORT_INVALID")
    if target.get("mysqlVersion") != "8.0.44":
        deny("BOUNDED_LOCAL_DEV_DATABASE_VERSION_MISMATCH")
    if len(required_text(candidate.get("head"), "BOUNDED_LOCAL_DEV_CANDIDATE_REQUIRED")) != 40:
        deny("BOUNDED_LOCAL_DEV_CANDIDATE_INVALID")
    controllers = manifest.get("controllers")
    if not isinstance(controllers, list) or len(controllers) != 6:
        deny("BOUNDED_LOCAL_DEV_CONTROLLER_INVENTORY_INVALID")
    if canonical_controller_digest(controllers) != manifest.get("controllerInventoryDigest"):
        deny("BOUNDED_LOCAL_DEV_CONTROLLER_DIGEST_MISMATCH")
    return {"exact": exact, "target": target, "worker": worker, "local": local}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def command(args: list[str], reason: str) -> str:
    result = subprocess.run(args, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        deny(reason)
    return result.stdout.strip()


def fetch_json(url: str, reason: str) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(url, timeout=8) as response:
            if response.status != 200:
                deny(reason)
            value = json.loads(response.read())
    except (OSError, ValueError):
        deny(reason)
    if not isinstance(value, dict):
        deny(reason)
    return value


def observe_navigator(manifest: dict[str, Any], target: dict[str, Any], local: dict[str, Any]) -> None:
    pid_file = Path(required_text(target.get("navigatorPidFile"),
                                  "BOUNDED_LOCAL_DEV_NAVIGATOR_PIDFILE_REQUIRED"))
    root = Path(target["root"])
    require_owned_file(pid_file, root)
    try:
        pid = int(pid_file.read_text(encoding="utf-8").strip())
        proc = Path("/proc") / str(pid)
        cwd = (proc / "cwd").resolve(strict=True)
        argv = (proc / "cmdline").read_bytes().split(b"\0")
    except (OSError, ValueError):
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_PROCESS_UNAVAILABLE")
    expected_cwd = Path(required_text(local.get("navigatorCwd"),
                                      "BOUNDED_LOCAL_DEV_NAVIGATOR_CWD_REQUIRED")).resolve(strict=True)
    artifact = Path(required_text(local.get("navigatorArtifact"),
                                  "BOUNDED_LOCAL_DEV_NAVIGATOR_ARTIFACT_REQUIRED")).resolve(strict=True)
    expected_sha = required_text(local.get("navigatorArtifactSha256"),
                                 "BOUNDED_LOCAL_DEV_NAVIGATOR_SHA_REQUIRED")
    if cwd != expected_cwd or str(artifact).encode() not in argv:
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_PROCESS_MISMATCH")
    if sha256_file(artifact) != expected_sha:
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_ARTIFACT_MISMATCH")
    matches = 0
    for candidate in Path("/proc").iterdir():
        if not candidate.name.isdigit():
            continue
        try:
            if str(artifact).encode() in (candidate / "cmdline").read_bytes().split(b"\0"):
                matches += 1
        except OSError:
            continue
    if matches != 1:
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_CONTROLLER_DRIFT")
    sockets = command(["ss", "-ltnp"], "BOUNDED_LOCAL_DEV_NAVIGATOR_SOCKET_UNAVAILABLE")
    port = int(target["navigatorPort"])
    if f"127.0.0.1]:{port}" not in sockets and f"127.0.0.1:{port}" not in sockets:
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_NON_LOOPBACK")
    if f"pid={pid}," not in sockets:
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_SOCKET_OWNER_MISMATCH")
    info = fetch_json(f"http://127.0.0.1:{port}/actuator/info",
                      "BOUNDED_LOCAL_DEV_NAVIGATOR_PROVENANCE_UNAVAILABLE")
    git = info.get("git") if isinstance(info.get("git"), dict) else {}
    commit = git.get("commit") if isinstance(git.get("commit"), dict) else {}
    commit_id = commit.get("id") if isinstance(commit.get("id"), dict) else {}
    if commit_id.get("full") != manifest["candidate"]["head"] or str(git.get("dirty")).lower() != "false":
        deny("BOUNDED_LOCAL_DEV_NAVIGATOR_PROVENANCE_MISMATCH")


def observe_database(target: dict[str, Any], local: dict[str, Any]) -> None:
    container = required_text(local.get("databaseContainer"),
                              "BOUNDED_LOCAL_DEV_DATABASE_CONTAINER_REQUIRED")
    raw = command(["docker", "inspect", container],
                  "BOUNDED_LOCAL_DEV_DATABASE_CONTAINER_UNAVAILABLE")
    try:
        values = json.loads(raw)
        value = values[0]
    except (ValueError, IndexError, TypeError):
        deny("BOUNDED_LOCAL_DEV_DATABASE_CONTAINER_INVALID")
    labels = value.get("Config", {}).get("Labels", {}) or {}
    restart = value.get("HostConfig", {}).get("RestartPolicy", {}).get("Name")
    ports = value.get("NetworkSettings", {}).get("Ports", {}).get("3306/tcp", []) or []
    if value.get("State", {}).get("Status") != "running":
        deny("BOUNDED_LOCAL_DEV_DATABASE_NOT_RUNNING")
    if labels.get("com.docker.compose.project") != target.get("dockerProject"):
        deny("BOUNDED_LOCAL_DEV_DATABASE_PROJECT_MISMATCH")
    if restart != "no":
        deny("BOUNDED_LOCAL_DEV_DATABASE_RESTART_POLICY_UNBOUNDED")
    if not any(int(item.get("HostPort", 0)) == int(target["mysqlPort"]) for item in ports):
        deny("BOUNDED_LOCAL_DEV_DATABASE_PORT_MISMATCH")


def wsl_prefix(distribution: str) -> list[str]:
    bridge = Path("/init")
    executable = Path("/mnt/c/Windows/System32/wsl.exe")
    if not bridge.exists() or not executable.exists():
        deny("BOUNDED_LOCAL_DEV_WORKER_DISTRIBUTION_UNAVAILABLE")
    return [str(bridge), str(executable), "wsl.exe", "-d", distribution, "--"]


def wsl_command(prefix: list[str], args: list[str], reason: str) -> str:
    result = subprocess.run(prefix + args, text=True, capture_output=True, check=False,
                            cwd="/tmp")
    if result.returncode != 0:
        deny(reason)
    return result.stdout.strip()


def observe_worker(exact: dict[str, Any], target: dict[str, Any], worker: dict[str, Any],
                   local: dict[str, Any], require_idle: bool) -> None:
    port = int(target["workerPort"])
    health = fetch_json(f"http://127.0.0.1:{port}/health",
                        "BOUNDED_LOCAL_DEV_WORKER_HEALTH_UNAVAILABLE")
    lifecycle = health.get("lifecycle_contract")
    required = set(worker.get("requiredCapabilities") or [])
    if not isinstance(lifecycle, dict) or not health.get("ready") or not lifecycle.get("ready"):
        deny("BOUNDED_LOCAL_DEV_WORKER_NOT_READY")
    if lifecycle.get("physical_worker_id") != exact.get("physicalWorkerId"):
        deny("BOUNDED_LOCAL_DEV_WORKER_IDENTITY_MISMATCH")
    if health.get("version") != worker.get("version") or lifecycle.get("version") != worker.get("protocolVersion"):
        deny("BOUNDED_LOCAL_DEV_WORKER_BUILD_MISMATCH")
    if not required.issubset(set(lifecycle.get("capabilities") or [])):
        deny("BOUNDED_LOCAL_DEV_WORKER_CAPABILITY_MISMATCH")
    if require_idle and int(health.get("active_tasks", -1)) != 0:
        deny("BOUNDED_LOCAL_DEV_WORKER_NOT_IDLE")
    distribution = required_text(local.get("workerDistribution"),
                                 "BOUNDED_LOCAL_DEV_WORKER_DISTRIBUTION_REQUIRED")
    install_root = required_text(local.get("workerInstallRoot"),
                                 "BOUNDED_LOCAL_DEV_WORKER_ROOT_REQUIRED")
    prefix = wsl_prefix(distribution)
    version = wsl_command(prefix, ["cat", f"{install_root}/VERSION"],
                          "BOUNDED_LOCAL_DEV_WORKER_VERSION_UNAVAILABLE")
    pids = wsl_command(prefix, ["pgrep", "-f", "^node dist/index.js$"],
                       "BOUNDED_LOCAL_DEV_WORKER_PROCESS_UNAVAILABLE").splitlines()
    if version != worker.get("version") or len(pids) != 1:
        deny("BOUNDED_LOCAL_DEV_WORKER_PROCESS_MISMATCH")
    cwd = wsl_command(prefix, ["readlink", "-f", f"/proc/{pids[0]}/cwd"],
                      "BOUNDED_LOCAL_DEV_WORKER_CWD_UNAVAILABLE")
    if cwd != install_root:
        deny("BOUNDED_LOCAL_DEV_WORKER_CWD_MISMATCH")
    sockets = wsl_command(prefix, ["ss", "-ltnp"],
                          "BOUNDED_LOCAL_DEV_WORKER_SOCKET_UNAVAILABLE")
    if f":{port} " not in sockets or f"pid={pids[0]}," not in sockets:
        deny("BOUNDED_LOCAL_DEV_WORKER_SOCKET_MISMATCH")
    mode = wsl_command(prefix, ["stat", "-c", "%a", f"{install_root}/.env"],
                       "BOUNDED_LOCAL_DEV_WORKER_PROFILE_UNAVAILABLE")
    if mode != "600":
        deny("BOUNDED_LOCAL_DEV_WORKER_PROFILE_PERMISSIONS_INVALID")
    wsl_command(prefix, ["grep", "-q", f"^CODEX_WORKER_PORT={port}$",
                         f"{install_root}/.env"],
                "BOUNDED_LOCAL_DEV_WORKER_PORT_CONFIG_MISMATCH")


def observation(manifest: dict[str, Any], manifest_path: Path,
                require_idle: bool) -> dict[str, Any]:
    parts = validate_manifest(manifest, manifest_path)
    observe_navigator(manifest, parts["target"], parts["local"])
    observe_database(parts["target"], parts["local"])
    observe_worker(parts["exact"], parts["target"], parts["worker"],
                   parts["local"], require_idle)
    return {
        "schema": OBSERVATION_SCHEMA,
        "targetId": manifest["targetId"],
        "runId": manifest["runId"],
        "controllerInventoryDigest": manifest["controllerInventoryDigest"],
        "manifestDigest": manifest_digest(manifest),
        "observedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "allKnownControllersDisabled": True,
        "lateRelaunchDetected": False,
        "unknownControllerCount": 0,
        "evidenceSource": "LIVE_LOCAL_INSPECTION",
    }


def loss_observation(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": OBSERVATION_SCHEMA,
        "targetId": manifest.get("targetId"),
        "runId": manifest.get("runId"),
        "controllerInventoryDigest": manifest.get("controllerInventoryDigest"),
        "manifestDigest": manifest_digest(manifest),
        "observedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "allKnownControllersDisabled": False,
        "lateRelaunchDetected": True,
        "unknownControllerCount": 1,
        "evidenceSource": "LIVE_LOCAL_INSPECTION",
    }


def write_atomic(path: Path, value: dict[str, Any], root: Path) -> None:
    resolved_root = root.resolve(strict=True)
    if not path.absolute().is_relative_to(resolved_root) or path.is_symlink():
        deny("BOUNDED_LOCAL_DEV_OBSERVATION_PATH_INVALID")
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    payload = json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, payload.encode("utf-8"))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.replace(temporary, path)
    os.chmod(path, 0o600)


def run_once(manifest_path: Path, output: Path, require_idle: bool) -> None:
    manifest = read_json(manifest_path)
    value = observation(manifest, manifest_path, require_idle)
    write_atomic(output, value, Path(manifest["target"]["root"]))
    print("BOUNDED_LOCAL_DEV_OBSERVATION_READY "
          f"targetId={manifest['targetId']} manifestDigest={value['manifestDigest'][:16]}")


def watch(manifest_path: Path, output: Path, interval_seconds: int) -> None:
    if interval_seconds < 2 or interval_seconds > 10:
        deny("BOUNDED_LOCAL_DEV_WATCH_INTERVAL_INVALID")
    while True:
        manifest = read_json(manifest_path)
        try:
            value = observation(manifest, manifest_path, False)
            write_atomic(output, value, Path(manifest["target"]["root"]))
        except ObservationDenied:
            write_atomic(output, loss_observation(manifest),
                         Path(manifest["target"]["root"]))
            raise
        time.sleep(interval_seconds)


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    observe_parser = subparsers.add_parser("observe")
    observe_parser.add_argument("--manifest", required=True, type=Path)
    observe_parser.add_argument("--output", required=True, type=Path)
    observe_parser.add_argument("--require-idle", action="store_true")
    watch_parser = subparsers.add_parser("watch")
    watch_parser.add_argument("--manifest", required=True, type=Path)
    watch_parser.add_argument("--output", required=True, type=Path)
    watch_parser.add_argument("--interval-seconds", type=int, default=5)
    args = parser.parse_args()
    try:
        if args.command == "observe":
            run_once(args.manifest, args.output, args.require_idle)
        else:
            watch(args.manifest, args.output, args.interval_seconds)
        return 0
    except ObservationDenied as denied:
        print(str(denied), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
