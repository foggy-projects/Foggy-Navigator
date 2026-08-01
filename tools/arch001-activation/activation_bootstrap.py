#!/usr/bin/env python3
"""Closed provisioning for the ARCH-001 disposable activation target.

The module deliberately keeps schema migration, Navigator bootstrap,
runtime credentials, Worker credentials, provider credentials, and activation
control credentials in separate lanes.  Successful results contain only IDs,
hashes, booleans, counts, stable reason codes, and timestamps.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from activation_target import (
    ActivationTargetError,
    EXACT_MYSQL_VERSION,
    _canonical_json,
    _deny,
    _under,
    live_environment_snapshot,
    load_manifest,
    manifest_digest,
)


SCHEMA_PLAN_SCHEMA = "NAVIGATOR_ARCH001_FRESH_SCHEMA_PLAN_V1"
SCHEMA_RESULT_SCHEMA = "NAVIGATOR_ARCH001_FRESH_SCHEMA_RESULT_V1"
PROVISIONING_RESULT_SCHEMA = "NAVIGATOR_ARCH001_PROVISIONING_RESULT_V1"
PROVISIONING_PROGRESS_SCHEMA = "NAVIGATOR_ARCH001_PROVISIONING_PROGRESS_V1"
WORKER_READINESS_SCHEMA = "NAVIGATOR_ARCH001_WORKER_READINESS_SEAL_V1"
TARGET_SEAL_SCHEMA = "NAVIGATOR_ARCH001_TARGET_SEAL_V1"
SEALED_MANIFEST_SCHEMA = "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2"
PROVISIONING_MANIFEST_SCHEMA = "NAVIGATOR_ARCH001_PROVISIONING_TARGET_V1"

BOOTSTRAP_ALLOWLIST = frozenset(
    {
        "ARCH001_SYNTHETIC_TENANT_ID",
        "ARCH001_SYNTHETIC_USERNAME",
        "ARCH001_SYNTHETIC_PASSWORD",
        "ARCH001_SYNTHETIC_EMAIL",
    }
)
BOOTSTRAP_REQUIRED = BOOTSTRAP_ALLOWLIST
RUNTIME_CREDENTIAL_ALLOWLIST = frozenset({"NAVI_RUNTIME_API_KEY"})
WORKER_SECRET_NAMES = frozenset({"CODEX_WORKER_TOKEN"})
PROVIDER_SECRET_NAMES = frozenset({"OPENAI_API_KEY"})
GENERATED_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{5,127}$")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    try:
        return _sha256_bytes(path.read_bytes())
    except OSError:
        _deny("ACTIVATION_SCHEMA_PLAN_FILE_UNAVAILABLE")


def _digest_without(value: dict[str, Any], field: str) -> str:
    normalized = dict(value)
    normalized.pop(field, None)
    return _sha256_bytes(_canonical_json(normalized).encode("utf-8"))


def _atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(_canonical_json(value) + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def _safe_profile_values(
    path: Path,
    allowlist: frozenset[str],
    required: frozenset[str],
) -> dict[str, str]:
    if not path.is_file() or path.is_symlink():
        _deny("ACTIVATION_PROVISIONING_PROFILE_UNAVAILABLE")
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode != (stat.S_IRUSR | stat.S_IWUSR):
        _deny("ACTIVATION_PROVISIONING_PROFILE_PERMISSIONS_UNSAFE")
    values: dict[str, str] = {}
    try:
        with path.open("r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                name, separator, value = line.partition("=")
                if (
                    not separator
                    or not name.isidentifier()
                    or name.upper() != name
                    or name in values
                ):
                    _deny("ACTIVATION_PROVISIONING_PROFILE_CONTRACT_INVALID")
                values[name] = value
    except (OSError, UnicodeError):
        _deny("ACTIVATION_PROVISIONING_PROFILE_UNAVAILABLE")
    if not set(values).issubset(allowlist):
        _deny("ACTIVATION_PROVISIONING_PROFILE_VARIABLE_NOT_ALLOWLISTED")
    if not required.issubset(values) or any(not values[name] for name in required):
        _deny("ACTIVATION_PROVISIONING_PROFILE_VALUE_MISSING")
    return values


def _atomic_profile(path: Path, values: dict[str, str]) -> None:
    if any("\n" in value or "\r" in value for value in values.values()):
        _deny("ACTIVATION_PROVISIONING_PROFILE_CONTRACT_INVALID")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(
        "".join(f"{name}={value}\n" for name, value in values.items()),
        encoding="utf-8",
    )
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def _replace_profile_value(path: Path, name: str, value: str) -> None:
    if not value or "\n" in value or "\r" in value:
        _deny("ACTIVATION_PROVISIONING_GENERATED_ID_INVALID")
    if not path.is_file() or path.is_symlink():
        _deny("ACTIVATION_PROVISIONING_PROFILE_UNAVAILABLE")
    if stat.S_IMODE(path.stat().st_mode) != (stat.S_IRUSR | stat.S_IWUSR):
        _deny("ACTIVATION_PROVISIONING_PROFILE_PERMISSIONS_UNSAFE")
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        _deny("ACTIVATION_PROVISIONING_PROFILE_UNAVAILABLE")
    matches = 0
    rewritten: list[str] = []
    for line in lines:
        if line.startswith(f"{name}="):
            rewritten.append(f"{name}={value}")
            matches += 1
        else:
            rewritten.append(line)
    if matches != 1:
        _deny("ACTIVATION_PROVISIONING_PROFILE_CONTRACT_INVALID")
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def schema_plan_digest(plan: dict[str, Any]) -> str:
    return _digest_without(plan, "planDigest")


def verify_schema_plan(
    plan_path: str | Path, repo_root: str | Path
) -> dict[str, Any]:
    path = Path(plan_path)
    root = Path(repo_root).resolve()
    plan = load_manifest(path)
    if plan.get("schema") != SCHEMA_PLAN_SCHEMA:
        _deny("ACTIVATION_SCHEMA_PLAN_SCHEMA_MISMATCH")
    if plan.get("mysqlVersion") != EXACT_MYSQL_VERSION:
        _deny("ACTIVATION_SCHEMA_PLAN_MYSQL_VERSION_MISMATCH")
    candidate_head = plan.get("candidateHead")
    if (
        not isinstance(candidate_head, str)
        or len(candidate_head) != 40
        or any(ch not in "0123456789abcdef" for ch in candidate_head)
    ):
        _deny("ACTIVATION_SCHEMA_PLAN_CANDIDATE_INVALID")
    files = plan.get("files")
    if not isinstance(files, list) or not files:
        _deny("ACTIVATION_SCHEMA_PLAN_EMPTY")
    expected_orders = list(range(1, len(files) + 1))
    if [entry.get("order") for entry in files if isinstance(entry, dict)] != expected_orders:
        _deny("ACTIVATION_SCHEMA_PLAN_ORDER_INVALID")
    verified: list[dict[str, Any]] = []
    baseline_count = 0
    for entry in files:
        if not isinstance(entry, dict):
            _deny("ACTIVATION_SCHEMA_PLAN_ENTRY_INVALID")
        relative = entry.get("path")
        expected_sha = entry.get("sha256")
        role = entry.get("role")
        if not isinstance(relative, str) or not relative:
            _deny("ACTIVATION_SCHEMA_PLAN_ENTRY_INVALID")
        if (
            Path(relative).is_absolute()
            or ".." in Path(relative).parts
            or not relative.startswith("docs/migration/")
            or relative.endswith("-rollback.sql")
        ):
            _deny("ACTIVATION_SCHEMA_PLAN_PATH_FORBIDDEN")
        sql_path = (root / relative).resolve()
        if not _under(sql_path, root / "docs/migration") or sql_path.is_symlink():
            _deny("ACTIVATION_SCHEMA_PLAN_PATH_FORBIDDEN")
        if not sql_path.is_file():
            _deny("ACTIVATION_SCHEMA_PLAN_FILE_UNAVAILABLE")
        actual_sha = _sha256_file(sql_path)
        if not isinstance(expected_sha, str) or actual_sha != expected_sha:
            _deny("ACTIVATION_SCHEMA_PLAN_FILE_DIGEST_MISMATCH")
        try:
            sql = sql_path.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            _deny("ACTIVATION_SCHEMA_PLAN_FILE_UNAVAILABLE")
        upper = sql.upper()
        forbidden = (
            "DROP TABLE",
            "DROP DATABASE",
            "DROP COLUMN",
            "TRUNCATE TABLE",
            "RENAME TABLE",
            "CREATE DATABASE",
            "INSERT INTO",
            "DELETE FROM",
            "REPLACE INTO",
            "CREATE TABLE CLAUDE_WORKING_DIRECTORIES_BAK",
            " AS SELECT ",
        )
        if any(token in upper for token in forbidden) or re.search(
            r"(?im)^\s*UPDATE\s+", sql
        ):
            _deny("ACTIVATION_SCHEMA_PLAN_DESTRUCTIVE_SQL_FORBIDDEN")
        if role == "CURRENT_SCHEMA_BASELINE":
            baseline_count += 1
            if "CREATE TABLE IF NOT EXISTS" not in upper:
                _deny("ACTIVATION_SCHEMA_PLAN_BASELINE_INVALID")
        elif role != "FORWARD_MIGRATION":
            _deny("ACTIVATION_SCHEMA_PLAN_ENTRY_INVALID")
        verified.append(
            {
                "order": entry["order"],
                "path": relative,
                "sha256": actual_sha,
                "role": role,
            }
        )
    if baseline_count != 1 or files[0].get("role") != "CURRENT_SCHEMA_BASELINE":
        _deny("ACTIVATION_SCHEMA_PLAN_BASELINE_INVALID")
    expected_plan_digest = schema_plan_digest(plan)
    if plan.get("planDigest") != expected_plan_digest:
        _deny("ACTIVATION_SCHEMA_PLAN_DIGEST_MISMATCH")
    return {
        "schema": "NAVIGATOR_ARCH001_FRESH_SCHEMA_PLAN_VERIFICATION_V1",
        "ready": True,
        "safeReasonCode": "ACTIVATION_SCHEMA_PLAN_READY",
        "mysqlVersion": EXACT_MYSQL_VERSION,
        "planId": plan.get("planId"),
        "candidateHead": candidate_head,
        "planDigest": expected_plan_digest,
        "fileCount": len(verified),
        "files": verified,
        "writesPerformed": 0,
    }


def _mysql_compose_command(manifest: dict[str, Any], shell_command: str) -> list[str]:
    target = manifest["target"]
    return [
        "docker",
        "compose",
        "--project-name",
        target["dockerProject"],
        "--file",
        target["composeFile"],
        "exec",
        "-T",
        "mysql",
        "sh",
        "-c",
        shell_command,
    ]


def _validate_schema_resource_snapshot(
    manifest: dict[str, Any], snapshot: dict[str, Any]
) -> None:
    """Require the one exact, run-labelled disposable MySQL resource set."""
    if (
        snapshot.get("evidenceSource") != "LIVE_LOCAL_INSPECTION"
        or snapshot.get("inventoryComplete") is not True
        or snapshot.get("portProbeOnly") is not False
        or snapshot.get("unknownControllerCount") != 0
    ):
        _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
    target = manifest["target"]
    project = target["dockerProject"]
    run_id = manifest["runId"]
    resources = snapshot.get("dockerResources")
    if not isinstance(resources, list):
        _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
    counts = {"container": 0, "network": 0, "volume": 0}
    for resource in resources:
        if (
            not isinstance(resource, dict)
            or resource.get("project") != project
            or resource.get("runId") != run_id
            or resource.get("kind") not in counts
        ):
            _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
        kind = str(resource["kind"])
        if kind == "container" and (
            resource.get("image") != "mysql:8.0.44"
            or resource.get("restartPolicy") != "no"
        ):
            _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
        counts[kind] += 1
    if counts != {"container": 1, "network": 1, "volume": 1}:
        _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
    listening = set(snapshot.get("listeningPorts", []))
    if target["mysqlPort"] not in listening or {
        target["navigatorPort"], target["workerPort"]
    } & listening:
        _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")
    if snapshot.get("processes"):
        _deny("ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN")


def apply_schema_plan(
    manifest: dict[str, Any],
    plan_path: str | Path,
    repo_root: str | Path,
    result_path: str | Path,
    *,
    reapply_confirmation: str | None = None,
    command_runner: Any = subprocess.run,
    environment_snapshot: dict[str, Any] | None = None,
) -> dict[str, Any]:
    target = manifest.get("target")
    if (
        manifest.get("schema") != PROVISIONING_MANIFEST_SCHEMA
        or manifest.get("lifecyclePhase") != "PROVISIONING_CLOSED"
        or not isinstance(target, dict)
        or target.get("mysqlVersion") != EXACT_MYSQL_VERSION
    ):
        _deny("ACTIVATION_SCHEMA_PLAN_TARGET_INVALID")
    target_root = Path(str(target.get("root", ""))).resolve()
    requested_plan = Path(plan_path).resolve()
    if (
        requested_plan != Path(str(target.get("schemaPlan", ""))).resolve()
        or not _under(requested_plan, target_root)
    ):
        _deny("ACTIVATION_SCHEMA_PLAN_TARGET_MISMATCH")
    verification = verify_schema_plan(requested_plan, repo_root)
    if verification["candidateHead"] != manifest.get("candidate", {}).get("head"):
        _deny("ACTIVATION_SCHEMA_PLAN_CANDIDATE_MISMATCH")
    expected_result = Path(str(target.get("schemaResult", ""))).resolve()
    requested_result = Path(result_path).resolve()
    if requested_result != expected_result or not _under(requested_result, target_root):
        _deny("ACTIVATION_SCHEMA_RESULT_TARGET_MISMATCH")
    existing: dict[str, Any] | None = None
    if requested_result.is_file():
        existing = load_manifest(requested_result)
        if (
            existing.get("schema") != SCHEMA_RESULT_SCHEMA
            or existing.get("runId") != manifest.get("runId")
            or existing.get("targetId") != manifest.get("targetId")
            or existing.get("planDigest") != verification["planDigest"]
            or existing.get("resultDigest")
            != _digest_without(existing, "resultDigest")
        ):
            _deny("ACTIVATION_SCHEMA_RESULT_INVALID")
        if reapply_confirmation != verification["planDigest"]:
            _deny("ACTIVATION_SCHEMA_REAPPLY_CONFIRMATION_REQUIRED")
    snapshot = environment_snapshot or live_environment_snapshot(manifest)
    _validate_schema_resource_snapshot(manifest, snapshot)
    version_command = _mysql_compose_command(
        manifest,
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot -e "SELECT VERSION()"',
    )
    version = command_runner(
        version_command,
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if version.returncode != 0 or version.stdout.strip() != EXACT_MYSQL_VERSION:
        _deny("ACTIVATION_SCHEMA_PLAN_MYSQL_VERSION_MISMATCH")
    count_command = _mysql_compose_command(
        manifest,
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot '
        '"$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM information_schema.tables '
        'WHERE table_schema = DATABASE()"',
    )
    before = command_runner(
        count_command,
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    try:
        before_count = int(before.stdout.strip()) if before.returncode == 0 else -1
    except ValueError:
        before_count = -1
    if before_count < 0 or (existing is None and before_count != 0):
        _deny("ACTIVATION_SCHEMA_PLAN_DATABASE_NOT_EMPTY")
    writes = 0
    root = Path(repo_root).resolve()
    for entry in verification["files"]:
        sql_path = (root / entry["path"]).resolve()
        sql = sql_path.read_text(encoding="utf-8")
        completed = command_runner(
            _mysql_compose_command(
                manifest,
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --binary-mode=1 -uroot "$MYSQL_DATABASE"',
            ),
            input=sql,
            check=False,
            capture_output=True,
            text=True,
            timeout=120,
        )
        if completed.returncode != 0:
            _deny("ACTIVATION_SCHEMA_PLAN_APPLY_FAILED")
        writes += 1
    after = command_runner(
        count_command,
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    try:
        after_count = int(after.stdout.strip()) if after.returncode == 0 else -1
    except ValueError:
        after_count = -1
    if after_count <= 0:
        _deny("ACTIVATION_SCHEMA_PLAN_APPLY_FAILED")
    result = {
        "schema": SCHEMA_RESULT_SCHEMA,
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "mysqlVersion": EXACT_MYSQL_VERSION,
        "database": target["database"],
        "planDigest": verification["planDigest"],
        "fileCount": verification["fileCount"],
        "beforeTableCount": before_count,
        "afterTableCount": after_count,
        "applyCount": 1 if existing is None else int(existing.get("applyCount", 1)) + 1,
        "hibernateModeRequired": "validate",
        "hibernateValidated": False,
        "writesPerformed": writes,
        "appliedAt": datetime.now(timezone.utc).isoformat(),
    }
    result["resultDigest"] = _digest_without(result, "resultDigest")
    _atomic_json(requested_result, result)
    return result


def _live_navigator_process_proof(
    manifest: dict[str, Any], artifact: Path
) -> dict[str, Any]:
    target = manifest["target"]
    root = Path(target["root"]).resolve()
    pid_path = Path(target["navigatorPidFile"]).resolve()
    if (
        not _under(pid_path, root)
        or not pid_path.is_file()
        or pid_path.is_symlink()
        or stat.S_IMODE(pid_path.stat().st_mode) & (stat.S_IWGRP | stat.S_IWOTH)
    ):
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROCESS_UNPROVEN")
    try:
        pid = int(pid_path.read_text(encoding="utf-8").strip())
        proc = Path("/proc") / str(pid)
        cwd = (proc / "cwd").resolve(strict=True)
        command = (proc / "cmdline").read_bytes().split(b"\0")
    except (OSError, RuntimeError, ValueError):
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROCESS_UNPROVEN")
    if not _under(cwd, root) or os.fsencode(str(artifact.resolve())) not in command:
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROCESS_UNPROVEN")
    return {"pid": pid, "cwd": str(cwd), "artifact": str(artifact.resolve())}


def validate_schema_runtime(
    manifest: dict[str, Any],
    result_path: str | Path,
    *,
    process_inspector: Any = _live_navigator_process_proof,
    health_client: Any | None = None,
) -> dict[str, Any]:
    """Seal live Hibernate validate evidence without exposing runtime secrets."""
    target = manifest.get("target")
    if (
        manifest.get("schema") != PROVISIONING_MANIFEST_SCHEMA
        or manifest.get("lifecyclePhase") != "PROVISIONING_CLOSED"
        or not isinstance(target, dict)
    ):
        _deny("ACTIVATION_SCHEMA_VALIDATION_TARGET_INVALID")
    root = Path(str(target.get("root", ""))).resolve()
    result_file = Path(result_path).resolve()
    if (
        result_file != Path(str(target.get("schemaResult", ""))).resolve()
        or not _under(result_file, root)
    ):
        _deny("ACTIVATION_SCHEMA_RESULT_TARGET_MISMATCH")
    result = load_manifest(result_file)
    if (
        result.get("schema") != SCHEMA_RESULT_SCHEMA
        or result.get("runId") != manifest.get("runId")
        or result.get("targetId") != manifest.get("targetId")
        or result.get("applyCount", 0) < 2
        or result.get("resultDigest") != _digest_without(result, "resultDigest")
    ):
        _deny("ACTIVATION_SCHEMA_RESULT_INVALID")
    runtime_path = Path(str(target.get("navigatorRuntimeProfile", ""))).resolve()
    if not _under(runtime_path, root):
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROFILE_INVALID")
    runtime_names = frozenset(
        {
            "SERVER_PORT", "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
            "SPRING_JPA_HIBERNATE_DDL_AUTO",
            "NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED",
            "NAVIGATOR_EXTERNAL_ENABLED",
            "NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED",
            "NAVIGATOR_LIFECYCLE_SHADOW_ENABLED",
        }
    )
    runtime = _safe_profile_values(runtime_path, runtime_names, runtime_names)
    if (
        runtime["SPRING_JPA_HIBERNATE_DDL_AUTO"] != "validate"
        or runtime["NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED"] != "true"
        or runtime["NAVIGATOR_EXTERNAL_ENABLED"] != "false"
        or runtime["NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED"] != "false"
        or runtime["NAVIGATOR_LIFECYCLE_SHADOW_ENABLED"] != "false"
        or runtime["SERVER_PORT"] != str(target.get("navigatorPort"))
    ):
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROFILE_INVALID")
    artifact = Path(str(target.get("navigatorArtifact", ""))).resolve()
    expected_artifact_digest = target.get("navigatorArtifactSha256")
    if (
        not _under(artifact, root)
        or not artifact.is_file()
        or artifact.is_symlink()
        or not isinstance(expected_artifact_digest, str)
        or _sha256_file(artifact) != expected_artifact_digest
    ):
        _deny("ACTIVATION_SCHEMA_VALIDATION_ARTIFACT_MISMATCH")
    proof = process_inspector(manifest, artifact)
    if (
        not isinstance(proof, dict)
        or proof.get("artifact") != str(artifact)
        or not _under(Path(str(proof.get("cwd", ""))), root)
        or not isinstance(proof.get("pid"), int)
    ):
        _deny("ACTIVATION_SCHEMA_VALIDATION_PROCESS_UNPROVEN")
    health = (health_client or NavigatorJsonClient(
        f"http://127.0.0.1:{target['navigatorPort']}"
    )).request("GET", "/actuator/health")
    if not isinstance(health, dict) or health.get("status") != "UP":
        _deny("ACTIVATION_SCHEMA_VALIDATION_HEALTH_FAILED")
    validated = dict(result)
    validated.update(
        {
            "hibernateValidated": True,
            "validationEvidenceSource": "LIVE_LOCAL_INSPECTION",
            "navigatorArtifactSha256": expected_artifact_digest,
            "navigatorPid": proof["pid"],
            "validatedAt": datetime.now(timezone.utc).isoformat(),
        }
    )
    validated["resultDigest"] = _digest_without(validated, "resultDigest")
    _atomic_json(result_file, validated)
    return validated


class NavigatorJsonClient:
    """Minimal JSON client that never logs request or response content."""

    def __init__(self, base_url: str):
        if not base_url.startswith("http://127.0.0.1:"):
            _deny("ACTIVATION_PROVISIONING_NON_LOOPBACK")
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        *,
        bearer: str | None = None,
        api_key: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any:
        payload = None if body is None else _canonical_json(body).encode("utf-8")
        request_headers = {"Accept": "application/json"}
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        if bearer:
            request_headers["Authorization"] = f"Bearer {bearer}"
        if api_key:
            request_headers["X-API-Key"] = api_key
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=payload,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                raw = response.read()
        except (urllib.error.URLError, TimeoutError, OSError):
            _deny("ACTIVATION_PROVISIONING_API_FAILED")
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError):
            _deny("ACTIVATION_PROVISIONING_API_RESPONSE_INVALID")
        return value


def _rx_data(value: Any) -> Any:
    if not isinstance(value, dict) or value.get("code") not in {0, 200}:
        _deny("ACTIVATION_PROVISIONING_API_REJECTED")
    if "data" not in value:
        _deny("ACTIVATION_PROVISIONING_API_RESPONSE_INVALID")
    return value["data"]


def _generated_id(value: Any) -> str:
    if (
        not isinstance(value, str)
        or not GENERATED_ID.fullmatch(value)
        or value.startswith("synthetic-")
    ):
        _deny("ACTIVATION_PROVISIONING_GENERATED_ID_INVALID")
    return value


def _provisioning_progress_path(manifest: dict[str, Any]) -> Path:
    target = manifest["target"]
    root = Path(target["root"]).resolve()
    path = Path(str(target.get("provisioningProgress", ""))).resolve()
    if not _under(path, root):
        _deny("ACTIVATION_PROVISIONING_PROGRESS_TARGET_MISMATCH")
    return path


def _write_provisioning_progress(
    manifest: dict[str, Any],
    *,
    user_id: str,
    worker_id: str | None,
    model_config_id: str | None,
    runtime_credential_created: bool,
    api_call_count: int,
    recovered: bool,
) -> dict[str, Any]:
    progress = {
        "schema": PROVISIONING_PROGRESS_SCHEMA,
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "tenantId": manifest["exactTuple"]["tenantId"],
        "userId": user_id,
        "physicalWorkerId": worker_id,
        "modelConfigId": model_config_id,
        "runtimeCredentialCreated": runtime_credential_created,
        "serverGeneratedIds": True,
        "productionApiOnly": True,
        "recovered": recovered,
        "apiCallCount": api_call_count,
        "directApplicationDmlCount": 0,
        "activationMutationCount": 0,
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
    }
    progress["resultDigest"] = _digest_without(progress, "resultDigest")
    _atomic_json(_provisioning_progress_path(manifest), progress)
    return progress


def _load_provisioning_progress(manifest: dict[str, Any]) -> dict[str, Any] | None:
    path = _provisioning_progress_path(manifest)
    if not path.exists():
        return None
    progress = load_manifest(path)
    if (
        progress.get("schema") != PROVISIONING_PROGRESS_SCHEMA
        or progress.get("runId") != manifest.get("runId")
        or progress.get("targetId") != manifest.get("targetId")
        or progress.get("tenantId") != manifest.get("exactTuple", {}).get("tenantId")
        or progress.get("resultDigest") != _digest_without(progress, "resultDigest")
        or progress.get("productionApiOnly") is not True
        or progress.get("directApplicationDmlCount") != 0
        or progress.get("activationMutationCount") != 0
        or progress.get("providerEffectCount") != 0
        or progress.get("modelSubmissionCount") != 0
        or not isinstance(progress.get("apiCallCount"), int)
        or progress.get("apiCallCount", -1) < 0
        or progress.get("userId") != _generated_id(progress.get("userId"))
        or (
            progress.get("physicalWorkerId") is not None
            and progress.get("physicalWorkerId")
            != _generated_id(progress.get("physicalWorkerId"))
        )
        or (
            progress.get("modelConfigId") is not None
            and progress.get("modelConfigId")
            != _generated_id(progress.get("modelConfigId"))
        )
        or not isinstance(progress.get("runtimeCredentialCreated"), bool)
    ):
        _deny("ACTIVATION_PROVISIONING_PROGRESS_INVALID")
    return progress


def recover_provisioning_progress(
    manifest: dict[str, Any],
    *,
    prior_api_call_count: int,
    client: Any | None = None,
) -> dict[str, Any]:
    """Recover only exact server IDs after a partial production-API attempt."""
    if prior_api_call_count < 1 or _load_provisioning_progress(manifest) is not None:
        _deny("ACTIVATION_PROVISIONING_RECOVERY_NOT_ALLOWED")
    target = manifest["target"]
    exact = manifest["exactTuple"]
    root = Path(target["root"]).resolve()
    bootstrap_path = Path(target["bootstrapProfile"]).resolve()
    if not _under(bootstrap_path, root):
        _deny("ACTIVATION_PROVISIONING_PROFILE_OWNERSHIP_UNPROVEN")
    bootstrap = _safe_profile_values(
        bootstrap_path, BOOTSTRAP_ALLOWLIST, BOOTSTRAP_REQUIRED
    )
    api = client or NavigatorJsonClient(
        f"http://127.0.0.1:{target['navigatorPort']}"
    )
    login = _rx_data(api.request(
        "POST", "/api/v1/auth/login",
        {
            "username": bootstrap["ARCH001_SYNTHETIC_USERNAME"],
            "password": bootstrap["ARCH001_SYNTHETIC_PASSWORD"],
        },
    ))
    if (
        not isinstance(login, dict)
        or not isinstance(login.get("token"), str)
        or not isinstance(login.get("user"), dict)
        or login["user"].get("tenantId") != exact.get("tenantId")
    ):
        _deny("ACTIVATION_PROVISIONING_RECOVERY_IDENTITY_MISMATCH")
    user_id = _generated_id(login["user"].get("id"))
    workers = _rx_data(api.request(
        "GET", "/api/v1/claude-workers", bearer=login["token"]
    ))
    worker_url = f"http://127.0.0.1:{target['workerPort']}"
    matches = [
        item for item in workers
        if isinstance(item, dict)
        and item.get("name") == "arch001-activation-worker"
        and item.get("baseUrl") == worker_url
        and item.get("codexBaseUrl") == worker_url
        and item.get("codexModel") == exact.get("model")
        and item.get("codexAuthTokenConfigured") is True
    ] if isinstance(workers, list) else []
    if len(matches) != 1:
        _deny("ACTIVATION_PROVISIONING_RECOVERY_RESOURCE_MISMATCH")
    worker_id = _generated_id(matches[0].get("workerId"))
    _replace_profile_value(
        Path(target["workerProfile"]),
        "CODEX_NAVIGATOR_WORKER_ID",
        worker_id,
    )
    progress = _write_provisioning_progress(
        manifest,
        user_id=user_id,
        worker_id=worker_id,
        model_config_id=None,
        runtime_credential_created=False,
        api_call_count=prior_api_call_count + 2,
        recovered=True,
    )
    return {
        "schema": "NAVIGATOR_ARCH001_PROVISIONING_RECOVERY_RESULT_V1",
        "ready": True,
        "safeReasonCode": "ACTIVATION_PROVISIONING_PROGRESS_RECOVERED",
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "userId": user_id,
        "physicalWorkerId": worker_id,
        "apiCallCount": progress["apiCallCount"],
        "writesPerformed": 2,
        "credentialValuesLogged": False,
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
    }


def provision_runtime(
    manifest: dict[str, Any],
    result_path: str | Path,
    *,
    client: Any | None = None,
) -> dict[str, Any]:
    target = manifest.get("target")
    exact = manifest.get("exactTuple")
    if (
        manifest.get("schema") != PROVISIONING_MANIFEST_SCHEMA
        or manifest.get("lifecyclePhase") != "PROVISIONING_CLOSED"
        or not isinstance(target, dict)
        or not isinstance(exact, dict)
    ):
        _deny("ACTIVATION_PROVISIONING_TARGET_INVALID")
    if any(exact.get(name) not in {None, ""} for name in (
        "userId", "physicalWorkerId", "modelConfigId"
    )):
        _deny("ACTIVATION_PROVISIONING_CALLER_SELECTED_ID_FORBIDDEN")
    root = Path(str(target.get("root", ""))).resolve()
    output = Path(result_path).resolve()
    expected_output = Path(str(target.get("provisioningResult", ""))).resolve()
    if output != expected_output or not _under(output, root) or output.exists():
        _deny("ACTIVATION_PROVISIONING_RESULT_TARGET_MISMATCH")
    bootstrap_path = Path(str(target.get("bootstrapProfile", ""))).resolve()
    provider_path = Path(str(target.get("providerProfile", ""))).resolve()
    worker_path = Path(str(target.get("workerProfile", ""))).resolve()
    runtime_path = Path(str(target.get("runtimeCredentialProfile", ""))).resolve()
    for path in (bootstrap_path, provider_path, worker_path, runtime_path):
        if not _under(path, root):
            _deny("ACTIVATION_PROVISIONING_PROFILE_OWNERSHIP_UNPROVEN")
    bootstrap = _safe_profile_values(
        bootstrap_path, BOOTSTRAP_ALLOWLIST, BOOTSTRAP_REQUIRED
    )
    provider = _safe_profile_values(
        provider_path,
        frozenset({"OPENAI_API_KEY", "OPENAI_BASE_URL"}),
        PROVIDER_SECRET_NAMES,
    )
    worker = _safe_profile_values(
        worker_path,
        frozenset(
            {
                "CODEX_WORKER_PORT", "CODEX_WORKER_HOST", "CODEX_WORKER_NAME",
                "CODEX_WORKER_TOKEN", "CODEX_WORKER_EXTERNAL_ENABLED",
                "CODEX_ALLOWED_CWDS", "CODEX_WORKER_CODEX_HOME",
                "CODEX_BIZ_HOME_ROOT", "CODEX_NAVIGATOR_WORKER_ID",
                "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
                "CODEX_LIFECYCLE_STORE_DIR", "CODEX_MAX_CONCURRENT_TASKS",
                "CODEX_THREAD_WATCHDOG_INTERVAL_MS",
                "CODEX_THREAD_PROCESS_MISSING_GRACE_MS", "CODEX_LOG_LEVEL",
                "CODEX_WORKER_AUTO_UPDATE_SDK",
            }
        ),
        frozenset(
            {
                "CODEX_WORKER_PORT", "CODEX_WORKER_HOST", "CODEX_WORKER_NAME",
                "CODEX_WORKER_TOKEN", "CODEX_WORKER_EXTERNAL_ENABLED",
                "CODEX_ALLOWED_CWDS", "CODEX_WORKER_CODEX_HOME",
                "CODEX_BIZ_HOME_ROOT", "CODEX_NAVIGATOR_WORKER_ID",
                "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
                "CODEX_LIFECYCLE_STORE_DIR",
            }
        ),
    )
    if bootstrap["ARCH001_SYNTHETIC_TENANT_ID"] != exact.get("tenantId"):
        _deny("ACTIVATION_PROVISIONING_TENANT_MISMATCH")
    progress = _load_provisioning_progress(manifest)
    configured_worker_id = worker["CODEX_NAVIGATOR_WORKER_ID"]
    if progress is None:
        if configured_worker_id not in {"__GENERATED_WORKER_ID__", ""}:
            _deny("ACTIVATION_PROVISIONING_CALLER_SELECTED_ID_FORBIDDEN")
        api_call_count = 0
        recovered = False
    else:
        if (
            progress.get("physicalWorkerId") is not None
            and configured_worker_id != progress.get("physicalWorkerId")
        ):
            _deny("ACTIVATION_PROVISIONING_PROGRESS_INVALID")
        api_call_count = progress["apiCallCount"]
        recovered = bool(progress.get("recovered"))
    port = target.get("navigatorPort")
    api = client or NavigatorJsonClient(f"http://127.0.0.1:{port}")
    if progress is None:
        user_id = _generated_id(
            _rx_data(
                api.request(
                    "POST",
                    "/api/v1/auth/register",
                    {
                        "tenantId": bootstrap["ARCH001_SYNTHETIC_TENANT_ID"],
                        "username": bootstrap["ARCH001_SYNTHETIC_USERNAME"],
                        "password": bootstrap["ARCH001_SYNTHETIC_PASSWORD"],
                        "email": bootstrap["ARCH001_SYNTHETIC_EMAIL"],
                        "displayName": "ARCH-001 synthetic canary",
                        "roles": "VIEWER",
                    },
                )
            )
        )
        api_call_count += 1
        progress = _write_provisioning_progress(
            manifest,
            user_id=user_id,
            worker_id=None,
            model_config_id=None,
            runtime_credential_created=False,
            api_call_count=api_call_count,
            recovered=False,
        )
    else:
        user_id = progress["userId"]
    login = _rx_data(
        api.request(
            "POST",
            "/api/v1/auth/login",
            {
                "username": bootstrap["ARCH001_SYNTHETIC_USERNAME"],
                "password": bootstrap["ARCH001_SYNTHETIC_PASSWORD"],
            },
        )
    )
    api_call_count += 1
    if (
        not isinstance(login, dict)
        or not isinstance(login.get("token"), str)
        or not isinstance(login.get("user"), dict)
        or login["user"].get("id") != user_id
        or login["user"].get("tenantId") != exact.get("tenantId")
    ):
        _deny("ACTIVATION_PROVISIONING_API_RESPONSE_INVALID")
    bearer = login["token"]
    worker_url = f"http://127.0.0.1:{target['workerPort']}"
    if progress.get("physicalWorkerId") is None:
        worker_data = _rx_data(
            api.request(
                "POST",
                "/api/v1/claude-workers",
                {
                    "name": "arch001-activation-worker",
                    "baseUrl": worker_url,
                    "authToken": worker["CODEX_WORKER_TOKEN"],
                    "authMode": "API_KEY",
                    "codexConfig": {
                        "baseUrl": worker_url,
                        "authToken": worker["CODEX_WORKER_TOKEN"],
                        "model": exact["model"],
                    },
                },
                bearer=bearer,
            )
        )
        api_call_count += 1
        if not isinstance(worker_data, dict):
            _deny("ACTIVATION_PROVISIONING_API_RESPONSE_INVALID")
        worker_id = _generated_id(worker_data.get("workerId"))
        _replace_profile_value(worker_path, "CODEX_NAVIGATOR_WORKER_ID", worker_id)
        progress = _write_provisioning_progress(
            manifest,
            user_id=user_id,
            worker_id=worker_id,
            model_config_id=None,
            runtime_credential_created=False,
            api_call_count=api_call_count,
            recovered=recovered,
        )
    else:
        worker_id = progress["physicalWorkerId"]
        worker_data = _rx_data(api.request(
            "GET", f"/api/v1/claude-workers/{worker_id}", bearer=bearer
        ))
        api_call_count += 1
        if (
            not isinstance(worker_data, dict)
            or worker_data.get("workerId") != worker_id
            or worker_data.get("name") != "arch001-activation-worker"
            or worker_data.get("baseUrl") != worker_url
            or worker_data.get("codexBaseUrl") != worker_url
            or worker_data.get("codexModel") != exact.get("model")
            or worker_data.get("codexAuthTokenConfigured") is not True
        ):
            _deny("ACTIVATION_PROVISIONING_RESOURCE_MISMATCH")
    model_body: dict[str, Any] = {
        "name": "ARCH-001 synthetic Codex model",
        "category": "GENERAL",
        "modelName": exact["model"],
        "apiKey": provider["OPENAI_API_KEY"],
        "isDefault": False,
        "scope": "RESTRICTED",
        "allowedWorkerIds": [worker_id],
        "workerBackend": "OPENAI_CODEX",
        "availableModels": [exact["model"]],
    }
    if provider.get("OPENAI_BASE_URL"):
        model_body["baseUrl"] = provider["OPENAI_BASE_URL"]
    if progress.get("modelConfigId") is None:
        model_config_id = _generated_id(
            _rx_data(
                api.request(
                    "POST", "/api/v1/config/platform/llm", model_body,
                    bearer=bearer,
                )
            )
        )
        api_call_count += 1
        progress = _write_provisioning_progress(
            manifest,
            user_id=user_id,
            worker_id=worker_id,
            model_config_id=model_config_id,
            runtime_credential_created=False,
            api_call_count=api_call_count,
            recovered=recovered,
        )
    else:
        model_config_id = progress["modelConfigId"]
        model_record = _rx_data(api.request(
            "GET", f"/api/v1/config/platform/llm/{model_config_id}",
            bearer=bearer,
        ))
        api_call_count += 1
        if (
            not isinstance(model_record, dict)
            or model_record.get("id") != model_config_id
            or model_record.get("tenantId") != exact.get("tenantId")
            or model_record.get("modelName") != exact.get("model")
            or model_record.get("scope") != "RESTRICTED"
            or model_record.get("allowedWorkerIds") != [worker_id]
            or model_record.get("workerBackend") != "OPENAI_CODEX"
            or model_record.get("hasApiKey") is not True
        ):
            _deny("ACTIVATION_PROVISIONING_RESOURCE_MISMATCH")
    if progress.get("runtimeCredentialCreated") is not True:
        api_key_data = _rx_data(
            api.request(
                "POST",
                f"/api/v1/users/{user_id}/api-keys",
                {"name": "ARCH-001 one-task runtime"},
                bearer=bearer,
            )
        )
        api_call_count += 1
        if not isinstance(api_key_data, dict) or not isinstance(
            api_key_data.get("apiKey"), str
        ) or not api_key_data["apiKey"]:
            _deny("ACTIVATION_PROVISIONING_API_RESPONSE_INVALID")
        _atomic_profile(runtime_path, {"NAVI_RUNTIME_API_KEY": api_key_data["apiKey"]})
        progress = _write_provisioning_progress(
            manifest,
            user_id=user_id,
            worker_id=worker_id,
            model_config_id=model_config_id,
            runtime_credential_created=True,
            api_call_count=api_call_count,
            recovered=recovered,
        )
    else:
        _safe_profile_values(
            runtime_path, RUNTIME_CREDENTIAL_ALLOWLIST, RUNTIME_CREDENTIAL_ALLOWLIST
        )
    result = {
        "schema": PROVISIONING_RESULT_SCHEMA,
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "tenantId": exact["tenantId"],
        "userId": user_id,
        "physicalWorkerId": worker_id,
        "modelConfigId": model_config_id,
        "model": exact["model"],
        "serverGeneratedIds": True,
        "productionApiOnly": True,
        "apiCallCount": api_call_count,
        "directApplicationDmlCount": 0,
        "activationMutationCount": 0,
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
        "runtimeCredentialCreated": True,
        "resumedFromProgress": recovered,
        "bootstrapMaterialPurged": True,
        "createdAt": datetime.now(timezone.utc).isoformat(),
    }
    result["resultDigest"] = _digest_without(result, "resultDigest")
    try:
        bootstrap_path.unlink()
    except OSError:
        _deny("ACTIVATION_CREDENTIAL_PURGE_FAILED")
    _atomic_json(output, result)
    return result


def verify_worker_and_resources(
    manifest: dict[str, Any],
    provisioning_result: dict[str, Any],
    output_path: str | Path,
    *,
    navigator_client: Any | None = None,
    worker_client: Any | None = None,
) -> dict[str, Any]:
    target = manifest["target"]
    root = Path(target["root"]).resolve()
    output = Path(output_path).resolve()
    expected = Path(target["workerReadinessResult"]).resolve()
    if output != expected or not _under(output, root):
        _deny("ACTIVATION_TARGET_SEAL_PATH_MISMATCH")
    runtime = _safe_profile_values(
        Path(target["runtimeCredentialProfile"]),
        RUNTIME_CREDENTIAL_ALLOWLIST,
        RUNTIME_CREDENTIAL_ALLOWLIST,
    )
    worker = _safe_profile_values(
        Path(target["workerProfile"]),
        frozenset(
            {
                "CODEX_WORKER_PORT", "CODEX_WORKER_HOST", "CODEX_WORKER_NAME",
                "CODEX_WORKER_TOKEN", "CODEX_WORKER_EXTERNAL_ENABLED",
                "CODEX_ALLOWED_CWDS", "CODEX_WORKER_CODEX_HOME",
                "CODEX_BIZ_HOME_ROOT", "CODEX_NAVIGATOR_WORKER_ID",
                "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
                "CODEX_LIFECYCLE_STORE_DIR", "CODEX_MAX_CONCURRENT_TASKS",
                "CODEX_THREAD_WATCHDOG_INTERVAL_MS",
                "CODEX_THREAD_PROCESS_MISSING_GRACE_MS", "CODEX_LOG_LEVEL",
                "CODEX_WORKER_AUTO_UPDATE_SDK",
            }
        ),
        frozenset(
            {
                "CODEX_WORKER_PORT", "CODEX_WORKER_HOST", "CODEX_WORKER_NAME",
                "CODEX_WORKER_TOKEN", "CODEX_WORKER_EXTERNAL_ENABLED",
                "CODEX_ALLOWED_CWDS", "CODEX_WORKER_CODEX_HOME",
                "CODEX_BIZ_HOME_ROOT", "CODEX_NAVIGATOR_WORKER_ID",
                "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
                "CODEX_LIFECYCLE_STORE_DIR",
            }
        ),
    )
    worker_id = provisioning_result.get("physicalWorkerId")
    if worker.get("CODEX_NAVIGATOR_WORKER_ID") != worker_id:
        _deny("ACTIVATION_TARGET_SEAL_WORKER_ID_MISMATCH")
    nav = navigator_client or NavigatorJsonClient(
        f"http://127.0.0.1:{target['navigatorPort']}"
    )
    worker_api = worker_client or NavigatorJsonClient(
        f"http://127.0.0.1:{target['workerPort']}"
    )
    worker_record = _rx_data(
        nav.request(
            "GET", f"/api/v1/claude-workers/{worker_id}",
            api_key=runtime["NAVI_RUNTIME_API_KEY"],
        )
    )
    model_record = _rx_data(
        nav.request(
            "GET",
            f"/api/v1/config/platform/llm/{provisioning_result['modelConfigId']}",
            api_key=runtime["NAVI_RUNTIME_API_KEY"],
        )
    )
    if (
        not isinstance(worker_record, dict)
        or worker_record.get("workerId") != worker_id
        or worker_record.get("baseUrl")
        != f"http://127.0.0.1:{target['workerPort']}"
        or worker_record.get("codexBaseUrl")
        != f"http://127.0.0.1:{target['workerPort']}"
        or worker_record.get("codexModel") != provisioning_result.get("model")
        or worker_record.get("codexAuthTokenConfigured") is not True
        or not isinstance(model_record, dict)
        or model_record.get("id") != provisioning_result.get("modelConfigId")
        or model_record.get("tenantId") != provisioning_result.get("tenantId")
        or model_record.get("modelName") != provisioning_result.get("model")
        or model_record.get("scope") != "RESTRICTED"
        or model_record.get("allowedWorkerIds") != [worker_id]
        or model_record.get("workerBackend") != "OPENAI_CODEX"
        or model_record.get("hasApiKey") is not True
    ):
        _deny("ACTIVATION_PROVISIONING_RESOURCE_MISMATCH")
    health = worker_api.request("GET", "/health")
    if not isinstance(health, dict):
        _deny("ACTIVATION_PROVISIONING_WORKER_NOT_READY")
    lifecycle = health.get("lifecycle_contract")
    required = set(manifest["worker"]["requiredCapabilities"])
    if (
        health.get("ready") is not True
        or health.get("version") != manifest["worker"]["version"]
        or not isinstance(lifecycle, dict)
        or lifecycle.get("ready") is not True
        or lifecycle.get("version") != manifest["worker"]["protocolVersion"]
        or lifecycle.get("physical_worker_id") != worker_id
        or not required.issubset(set(lifecycle.get("capabilities", [])))
    ):
        _deny("ACTIVATION_PROVISIONING_WORKER_NOT_READY")
    state_generation = lifecycle.get("state_generation")
    instance_epoch = lifecycle.get("instance_epoch")
    if not isinstance(state_generation, str) or not state_generation or not isinstance(
        instance_epoch, str
    ) or not instance_epoch:
        _deny("ACTIVATION_PROVISIONING_WORKER_NOT_READY")
    inventory = worker_api.request(
        "GET",
        "/api/v1/lifecycle/inventory?after_sequence=0",
        bearer=worker["CODEX_WORKER_TOKEN"],
        headers={
            "X-Navigator-Expected-Physical-Worker-Id": worker_id,
            "X-Navigator-Expected-State-Generation": state_generation,
        },
    )
    if (
        not isinstance(inventory, dict)
        or inventory.get("physical_worker_id") != worker_id
        or inventory.get("state_generation") != state_generation
        or inventory.get("instance_epoch") != instance_epoch
        or inventory.get("coverage") != "COMPLETE"
    ):
        _deny("ACTIVATION_PROVISIONING_AUTHENTICATED_INVENTORY_FAILED")
    result = {
        "schema": WORKER_READINESS_SCHEMA,
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "physicalWorkerId": worker_id,
        "modelConfigId": provisioning_result["modelConfigId"],
        "workerVersion": health["version"],
        "protocolVersion": lifecycle["version"],
        "capabilities": sorted(required),
        "stateGeneration": state_generation,
        "instanceEpoch": instance_epoch,
        "authenticatedInventory": True,
        "providerConfigurationPresent": True,
        "receiptEnabled": True,
        "activeTaskCount": health.get("active_tasks", 0),
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
        "verifiedAt": datetime.now(timezone.utc).isoformat(),
    }
    if result["activeTaskCount"] != 0:
        _deny("ACTIVATION_PROVISIONING_WORKER_NOT_IDLE")
    result["resultDigest"] = _digest_without(result, "resultDigest")
    _atomic_json(output, result)
    return result


def seal_target(
    manifest_path: str | Path,
    schema_result_path: str | Path,
    provisioning_result_path: str | Path,
    worker_readiness_path: str | Path,
    seal_path: str | Path,
    confirmation: str,
) -> dict[str, Any]:
    path = Path(manifest_path)
    manifest = load_manifest(path)
    if (
        manifest.get("schema") != PROVISIONING_MANIFEST_SCHEMA
        or manifest.get("lifecyclePhase") != "PROVISIONING_CLOSED"
        or confirmation != manifest_digest(manifest)
    ):
        _deny("ACTIVATION_TARGET_SEAL_CONFIRMATION_REQUIRED")
    target = manifest["target"]
    root = Path(target["root"]).resolve()
    expected_paths = {
        "schema": Path(target["schemaResult"]).resolve(),
        "provisioning": Path(target["provisioningResult"]).resolve(),
        "worker": Path(target["workerReadinessResult"]).resolve(),
        "seal": Path(target["provisioningSeal"]).resolve(),
    }
    supplied = {
        "schema": Path(schema_result_path).resolve(),
        "provisioning": Path(provisioning_result_path).resolve(),
        "worker": Path(worker_readiness_path).resolve(),
        "seal": Path(seal_path).resolve(),
    }
    if supplied != expected_paths or not all(_under(value, root) for value in supplied.values()):
        _deny("ACTIVATION_TARGET_SEAL_PATH_MISMATCH")
    if supplied["seal"].exists():
        _deny("ACTIVATION_TARGET_SEAL_ALREADY_EXISTS")
    schema_result = load_manifest(supplied["schema"])
    provisioning = load_manifest(supplied["provisioning"])
    readiness = load_manifest(supplied["worker"])
    schema_plan_path = Path(str(target.get("schemaPlan", ""))).resolve()
    if not _under(schema_plan_path, root):
        _deny("ACTIVATION_TARGET_SEAL_PATH_MISMATCH")
    schema_plan = load_manifest(schema_plan_path)
    if (
        schema_plan.get("schema") != SCHEMA_PLAN_SCHEMA
        or schema_plan.get("candidateHead") != manifest.get("candidate", {}).get("head")
        or schema_plan.get("planDigest") != schema_plan_digest(schema_plan)
        or schema_result.get("schema") != SCHEMA_RESULT_SCHEMA
        or schema_result.get("applyCount", 0) < 2
        or schema_result.get("hibernateValidated") is not True
        or schema_result.get("planDigest") != schema_plan.get("planDigest")
        or schema_result.get("resultDigest") != _digest_without(schema_result, "resultDigest")
        or provisioning.get("schema") != PROVISIONING_RESULT_SCHEMA
        or provisioning.get("resultDigest") != _digest_without(provisioning, "resultDigest")
        or readiness.get("schema") != WORKER_READINESS_SCHEMA
        or readiness.get("resultDigest") != _digest_without(readiness, "resultDigest")
    ):
        _deny("ACTIVATION_TARGET_SEAL_INPUT_INVALID")
    if any(
        provisioning.get(field) != readiness.get(field)
        for field in ("physicalWorkerId", "modelConfigId")
    ) or any(
        provisioning.get(field) != manifest["exactTuple"].get(field)
        for field in ("tenantId", "model")
    ) or any(
        value.get("runId") != manifest.get("runId")
        or value.get("targetId") != manifest.get("targetId")
        for value in (schema_result, provisioning, readiness)
    ) or schema_result.get("planDigest") is None:
        _deny("ACTIVATION_TARGET_SEAL_IDENTITY_MISMATCH")
    exact_tuple = dict(manifest["exactTuple"])
    for field in ("userId", "physicalWorkerId", "modelConfigId"):
        exact_tuple[field] = provisioning[field]
    profile_paths = {
        role: str(Path(target[key]).resolve())
        for role, key in (
            ("provider", "providerProfile"),
            ("runtime", "runtimeCredentialProfile"),
            ("worker", "workerProfile"),
            ("navigator", "navigatorRuntimeProfile"),
            ("database", "databaseProfile"),
            ("control", "controlProfile"),
        )
    }
    if not all(_under(Path(value), root) for value in profile_paths.values()):
        _deny("ACTIVATION_TARGET_SEAL_PATH_MISMATCH")
    seal = {
        "schema": TARGET_SEAL_SCHEMA,
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "candidate": manifest["candidate"],
        "exactTuple": exact_tuple,
        "schemaPlanDigest": schema_result["planDigest"],
        "schemaResultDigest": schema_result["resultDigest"],
        "provisioningResultDigest": provisioning["resultDigest"],
        "workerReadinessDigest": readiness["resultDigest"],
        "workerStateGeneration": readiness["stateGeneration"],
        "workerInstanceEpoch": readiness["instanceEpoch"],
        "profilePathDigests": {
            role: _sha256_bytes(f"{role}\0{value}".encode("utf-8"))
            for role, value in profile_paths.items()
        },
        "serverGeneratedIds": True,
        "productionApiOnly": True,
        "bootstrapMaterialPurged": not Path(target["bootstrapProfile"]).exists(),
        "activationMutationCount": 0,
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
        "sealedAt": datetime.now(timezone.utc).isoformat(),
    }
    if seal["bootstrapMaterialPurged"] is not True:
        _deny("ACTIVATION_CREDENTIAL_PURGE_REQUIRED")
    seal["sealDigest"] = _digest_without(seal, "sealDigest")
    _atomic_json(supplied["seal"], seal)
    sealed_manifest = dict(manifest)
    sealed_manifest["schema"] = SEALED_MANIFEST_SCHEMA
    sealed_manifest["lifecyclePhase"] = "SEALED_STOPPED"
    sealed_manifest["exactTuple"] = exact_tuple
    sealed_manifest["seal"] = {
        "path": str(supplied["seal"]),
        "sealDigest": seal["sealDigest"],
        "schemaPlanDigest": seal["schemaPlanDigest"],
        "schemaResultDigest": seal["schemaResultDigest"],
        "provisioningResultDigest": seal["provisioningResultDigest"],
        "workerReadinessDigest": seal["workerReadinessDigest"],
    }
    temporary = path.with_name(f"{path.name}.{os.getpid()}.seal.tmp")
    temporary.write_text(_canonical_json(sealed_manifest) + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)
    return {
        "schema": "NAVIGATOR_ARCH001_TARGET_SEAL_RESULT_V1",
        "ready": True,
        "safeReasonCode": "ACTIVATION_TARGET_SEALED_STOPPED",
        "runId": manifest["runId"],
        "targetId": manifest["targetId"],
        "sealDigest": seal["sealDigest"],
        "manifestDigest": manifest_digest(sealed_manifest),
        "generatedIdentityCount": 3,
        "activationMutationCount": 0,
        "providerEffectCount": 0,
        "modelSubmissionCount": 0,
        "writesPerformed": 2,
    }


def purge_credentials(
    manifest: dict[str, Any], confirmation: str
) -> dict[str, Any]:
    if manifest.get("schema") != SEALED_MANIFEST_SCHEMA:
        _deny("ACTIVATION_CREDENTIAL_PURGE_TARGET_INVALID")
    expected = manifest_digest(manifest)
    if confirmation != expected:
        _deny("ACTIVATION_CREDENTIAL_PURGE_CONFIRMATION_REQUIRED")
    target = manifest["target"]
    root = Path(target["root"]).resolve()
    keys = (
        "providerProfile",
        "runtimeCredentialProfile",
        "workerProfile",
        "navigatorRuntimeProfile",
        "databaseProfile",
        "controlProfile",
    )
    paths = [Path(target[key]).resolve() for key in keys]
    if not all(_under(path, root) and path.is_file() and not path.is_symlink() for path in paths):
        _deny("ACTIVATION_CREDENTIAL_PURGE_OWNERSHIP_UNPROVEN")
    if any(
        stat.S_IMODE(path.stat().st_mode) != (stat.S_IRUSR | stat.S_IWUSR)
        for path in paths
    ):
        _deny("ACTIVATION_CREDENTIAL_PURGE_OWNERSHIP_UNPROVEN")
    for path in paths:
        try:
            path.unlink()
        except OSError:
            _deny("ACTIVATION_CREDENTIAL_PURGE_FAILED")
    return {
        "schema": "NAVIGATOR_ARCH001_CREDENTIAL_PURGE_RESULT_V1",
        "ready": True,
        "safeReasonCode": "ACTIVATION_CREDENTIALS_PURGED",
        "runId": manifest["runId"],
        "manifestDigest": expected,
        "purgedProfileCount": len(paths),
        "credentialValuesLogged": False,
        "writesPerformed": len(paths),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("schema-plan-verify")
    verify.add_argument("--plan", required=True)
    verify.add_argument("--repo-root", required=True)
    apply = commands.add_parser("schema-apply")
    apply.add_argument("--manifest", required=True)
    apply.add_argument("--plan", required=True)
    apply.add_argument("--repo-root", required=True)
    apply.add_argument("--result", required=True)
    apply.add_argument("--reapply-confirmation")
    validate = commands.add_parser("schema-validate")
    validate.add_argument("--manifest", required=True)
    validate.add_argument("--result", required=True)
    provision = commands.add_parser("provision")
    provision.add_argument("--manifest", required=True)
    provision.add_argument("--result", required=True)
    recover = commands.add_parser("recover-progress")
    recover.add_argument("--manifest", required=True)
    recover.add_argument("--prior-api-call-count", required=True, type=int)
    readiness = commands.add_parser("verify-readiness")
    readiness.add_argument("--manifest", required=True)
    readiness.add_argument("--provisioning-result", required=True)
    readiness.add_argument("--output", required=True)
    seal = commands.add_parser("seal")
    seal.add_argument("--manifest", required=True)
    seal.add_argument("--schema-result", required=True)
    seal.add_argument("--provisioning-result", required=True)
    seal.add_argument("--worker-readiness", required=True)
    seal.add_argument("--output", required=True)
    seal.add_argument("--confirmation", required=True)
    purge = commands.add_parser("purge-credentials")
    purge.add_argument("--manifest", required=True)
    purge.add_argument("--confirmation", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "schema-plan-verify":
            result = verify_schema_plan(args.plan, args.repo_root)
        else:
            manifest = load_manifest(args.manifest)
            if args.command == "schema-apply":
                result = apply_schema_plan(
                    manifest,
                    args.plan,
                    args.repo_root,
                    args.result,
                    reapply_confirmation=args.reapply_confirmation,
                )
            elif args.command == "schema-validate":
                result = validate_schema_runtime(manifest, args.result)
            elif args.command == "provision":
                result = provision_runtime(manifest, args.result)
            elif args.command == "recover-progress":
                result = recover_provisioning_progress(
                    manifest, prior_api_call_count=args.prior_api_call_count
                )
            elif args.command == "verify-readiness":
                result = verify_worker_and_resources(
                    manifest,
                    load_manifest(args.provisioning_result),
                    args.output,
                )
            elif args.command == "seal":
                result = seal_target(
                    args.manifest,
                    args.schema_result,
                    args.provisioning_result,
                    args.worker_readiness,
                    args.output,
                    args.confirmation,
                )
            else:
                result = purge_credentials(manifest, args.confirmation)
        print(_canonical_json(result))
        return 0
    except ActivationTargetError as error:
        print(
            _canonical_json(
                {
                    "schema": "NAVIGATOR_ARCH001_BOOTSTRAP_RESULT_V1",
                    "ready": False,
                    "safeReasonCode": str(error),
                    "credentialValuesLogged": False,
                    "writesPerformed": 0,
                }
            )
        )
        return 2


if __name__ == "__main__":
    sys.exit(main())
