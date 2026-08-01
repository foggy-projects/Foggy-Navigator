#!/usr/bin/env python3
"""Fail-closed tooling for the ARCH-001 disposable activation target.

`doctor` is read-only.  It never starts, stops, or rewrites a target.  Profile
inspection retains only variable names; values are discarded immediately and
are never included in results or errors.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import socket
import stat
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA = "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2"
PROVISIONING_SCHEMA = "NAVIGATOR_ARCH001_PROVISIONING_TARGET_V1"
TARGET_SEAL_SCHEMA = "NAVIGATOR_ARCH001_TARGET_SEAL_V1"
SCHEMA_PLAN_SCHEMA = "NAVIGATOR_ARCH001_FRESH_SCHEMA_PLAN_V1"
SCHEMA_RESULT_SCHEMA = "NAVIGATOR_ARCH001_FRESH_SCHEMA_RESULT_V1"
PROVISIONING_RESULT_SCHEMA = "NAVIGATOR_ARCH001_PROVISIONING_RESULT_V1"
WORKER_READINESS_SCHEMA = "NAVIGATOR_ARCH001_WORKER_READINESS_SEAL_V1"
OBSERVATION_SCHEMA = "NAVIGATOR_ARCH001_CONTROLLER_OBSERVATION_V1"
EXACT_TARGET_CLASS = "ISOLATED_LOCAL_NON_FIXTURE"
EXACT_PROVIDER_LANE = "REAL_CODEX_MODEL"
EXACT_PROVIDER = "codex-biz-worker"
EXACT_MYSQL_VERSION = "8.0.44"

PROTECTED_PORTS = frozenset({8112, 3031, 3051, 3053, 3061, 3151, 3161})
PROTECTED_HOMES = tuple(
    Path(value).resolve()
    for value in (
        "/home/sa/.codex-worker",
        "/home/sa/.claude-worker",
        "/home/navigator/.codex-worker",
        "/home/sa/workspace/Foggy-Navigator/tools/codex-agent-worker",
        "/home/sa/workspace/Foggy-Navigator/tools/codex-app-server-worker",
        "/home/sa/workspace/Foggy-Navigator/tools/claude-agent-worker",
        "/home/sa/workspace/Foggy-Navigator/tools/langgraph-biz-worker",
    )
)
SHARED_DATABASES = frozenset(
    {"mysql", "navigator", "foggy_navigator", "foggy-navigator", "foggy"}
)
SHARED_DOCKER_PROJECTS = frozenset(
    {"foggy-navigator", "foggy_navigator", "navigator", "default"}
)
REQUIRED_CONTROLLER_KINDS = frozenset(
    {"process", "supervisor", "manual_launcher", "ci", "timer", "docker"}
)
CONTROLLER_STATES = frozenset({"DISABLED", "NOT_APPLICABLE"})
CONTROLLER_CONTRACT = {
    "process": ("target-process-set", "DISABLED", "proc-cwd-scan"),
    "supervisor": (
        "none", "NOT_APPLICABLE", "local-target-no-supervisor"
    ),
    "manual_launcher": (
        "target-pidfiles", "DISABLED", "target-pidfile-scan"
    ),
    "ci": ("none", "NOT_APPLICABLE", "local-target-no-ci"),
    "timer": ("none", "NOT_APPLICABLE", "local-target-no-timer"),
    "docker": ("mysql-compose", "DISABLED", "compose-label-scan"),
}

PROVIDER_PROFILE_ALLOWLIST = frozenset(
    {
        "OPENAI_API_KEY",
        "OPENAI_BASE_URL",
    }
)
PROVIDER_PROFILE_REQUIRED = frozenset({"OPENAI_API_KEY"})
WORKER_PROFILE_ALLOWLIST = frozenset(
    {
        "CODEX_WORKER_PORT",
        "CODEX_WORKER_HOST",
        "CODEX_WORKER_NAME",
        "CODEX_WORKER_TOKEN",
        "CODEX_WORKER_EXTERNAL_ENABLED",
        "CODEX_ALLOWED_CWDS",
        "CODEX_WORKER_CODEX_HOME",
        "CODEX_BIZ_HOME_ROOT",
        "CODEX_NAVIGATOR_WORKER_ID",
        "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
        "CODEX_LIFECYCLE_STORE_DIR",
        "CODEX_MAX_CONCURRENT_TASKS",
        "CODEX_THREAD_WATCHDOG_INTERVAL_MS",
        "CODEX_THREAD_PROCESS_MISSING_GRACE_MS",
        "CODEX_LOG_LEVEL",
        "CODEX_WORKER_AUTO_UPDATE_SDK",
    }
)
WORKER_PROFILE_REQUIRED = frozenset(
    {
        "CODEX_WORKER_PORT",
        "CODEX_WORKER_HOST",
        "CODEX_WORKER_NAME",
        "CODEX_WORKER_TOKEN",
        "CODEX_WORKER_EXTERNAL_ENABLED",
        "CODEX_ALLOWED_CWDS",
        "CODEX_WORKER_CODEX_HOME",
        "CODEX_BIZ_HOME_ROOT",
        "CODEX_NAVIGATOR_WORKER_ID",
        "CODEX_TERMINATION_OPERATION_LEDGER_DIR",
        "CODEX_LIFECYCLE_STORE_DIR",
    }
)
RUNTIME_PROFILE_ALLOWLIST = frozenset(
    {
        "SERVER_PORT",
        "SPRING_DATASOURCE_URL",
        "SPRING_DATASOURCE_USERNAME",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_JPA_HIBERNATE_DDL_AUTO",
        "NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED",
        "NAVIGATOR_EXTERNAL_ENABLED",
        "NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED",
        "NAVIGATOR_LIFECYCLE_SHADOW_ENABLED",
    }
)
RUNTIME_PROFILE_REQUIRED = RUNTIME_PROFILE_ALLOWLIST
DATABASE_PROFILE_ALLOWLIST = frozenset(
    {"MYSQL_DATABASE", "MYSQL_USER", "MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD"}
)
DATABASE_PROFILE_REQUIRED = DATABASE_PROFILE_ALLOWLIST
CONTROL_PROFILE_ALLOWLIST = frozenset(
    {
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_TOKEN",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_EXACT_TARGET_ID",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_MANIFEST_PATH",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_PATH",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_ID",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_HEAD",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_PATCH_SHA256",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OWNER_PROTOCOL",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_PROOF_LEASE",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_TTL",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_MAX_AGE",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVER_DELAY",
    }
)
CONTROL_PROFILE_REQUIRED = frozenset(
    {
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_TOKEN",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_EXACT_TARGET_ID",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_MANIFEST_PATH",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_PATH",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_ID",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_HEAD",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_PATCH_SHA256",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OWNER_PROTOCOL",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_PROOF_LEASE",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_TTL",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_MAX_AGE",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVER_DELAY",
    }
)
BOOTSTRAP_PROFILE_ALLOWLIST = frozenset(
    {
        "ARCH001_SYNTHETIC_TENANT_ID",
        "ARCH001_SYNTHETIC_USERNAME",
        "ARCH001_SYNTHETIC_PASSWORD",
        "ARCH001_SYNTHETIC_EMAIL",
    }
)
BOOTSTRAP_PROFILE_REQUIRED = BOOTSTRAP_PROFILE_ALLOWLIST
RUNTIME_CREDENTIAL_PROFILE_ALLOWLIST = frozenset({"NAVI_RUNTIME_API_KEY"})
RUNTIME_CREDENTIAL_PROFILE_REQUIRED = RUNTIME_CREDENTIAL_PROFILE_ALLOWLIST


class ActivationTargetError(RuntimeError):
    """Contains only a stable, content-free rejection code."""


def _deny(code: str) -> None:
    raise ActivationTargetError(code)


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    )


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def manifest_digest(manifest: dict[str, Any]) -> str:
    return _sha256(_canonical_json(manifest))


def canonical_controller_digest(controllers: list[dict[str, Any]]) -> str:
    fields = (
        "kind",
        "id",
        "state",
        "restartPolicy",
        "ownershipRunId",
        "source",
        "artifactCommit",
        "cwd",
    )
    normalized = [
        {field: controller.get(field) for field in fields}
        for controller in controllers
    ]
    normalized.sort(key=lambda value: (str(value["kind"]), str(value["id"])))
    return _sha256(_canonical_json(normalized))


def load_manifest(path: str | Path) -> dict[str, Any]:
    candidate = Path(path)
    if not candidate.is_file():
        _deny("ACTIVATION_TARGET_MANIFEST_UNAVAILABLE")
    try:
        value = json.loads(candidate.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        _deny("ACTIVATION_TARGET_MANIFEST_INVALID")
    if not isinstance(value, dict):
        _deny("ACTIVATION_TARGET_MANIFEST_INVALID")
    return value


def _required_text(value: Any, code: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _deny(code)
    return value.strip()


def _required_int(value: Any, code: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        _deny(code)
    return value


def _under(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def _profile_variable_names(
    profile: Path, allowlist: frozenset[str], required: frozenset[str]
) -> list[str]:
    if not profile.is_file() or profile.is_symlink():
        _deny("ACTIVATION_PROFILE_UNAVAILABLE")
    mode = stat.S_IMODE(profile.stat().st_mode)
    if mode != (stat.S_IRUSR | stat.S_IWUSR):
        _deny("ACTIVATION_PROFILE_PERMISSIONS_UNSAFE")
    names: set[str] = set()
    try:
        with profile.open("r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                name, separator, _discarded_value = line.partition("=")
                if not separator or not name.isidentifier() or name.upper() != name:
                    _deny("ACTIVATION_PROFILE_CONTRACT_INVALID")
                names.add(name)
    except (OSError, UnicodeError):
        _deny("ACTIVATION_PROFILE_UNAVAILABLE")
    if not names.issubset(allowlist):
        _deny("ACTIVATION_PROFILE_VARIABLE_NOT_ALLOWLISTED")
    if not required.issubset(names):
        _deny("ACTIVATION_PROFILE_REQUIRED_VARIABLE_MISSING")
    return sorted(names)


def _profile_selected_values(
    profile: Path, selected: frozenset[str]
) -> dict[str, str]:
    """Read only explicitly selected non-secret contract values."""
    if not profile.is_file() or profile.is_symlink():
        _deny("ACTIVATION_PROFILE_UNAVAILABLE")
    mode = stat.S_IMODE(profile.stat().st_mode)
    if mode != (stat.S_IRUSR | stat.S_IWUSR):
        _deny("ACTIVATION_PROFILE_PERMISSIONS_UNSAFE")
    values: dict[str, str] = {}
    try:
        with profile.open("r", encoding="utf-8") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                name, separator, value = line.partition("=")
                if separator and name in selected:
                    if name in values:
                        _deny("ACTIVATION_PROFILE_CONTRACT_INVALID")
                    values[name] = value
    except (OSError, UnicodeError):
        _deny("ACTIVATION_PROFILE_UNAVAILABLE")
    if set(values) != set(selected):
        _deny("ACTIVATION_PROFILE_REQUIRED_VARIABLE_MISSING")
    return values


def _digest_without(value: dict[str, Any], field: str) -> str:
    normalized = dict(value)
    normalized.pop(field, None)
    return _sha256(_canonical_json(normalized))


def _validate_seal(
    manifest: dict[str, Any], target: dict[str, Any], target_root: Path
) -> dict[str, Any]:
    if manifest.get("lifecyclePhase") != "SEALED_STOPPED":
        _deny("ACTIVATION_TARGET_SEAL_REQUIRED")
    reference = manifest.get("seal")
    if not isinstance(reference, dict):
        _deny("ACTIVATION_TARGET_SEAL_REQUIRED")
    seal_path = Path(
        _required_text(reference.get("path"), "ACTIVATION_TARGET_SEAL_REQUIRED")
    )
    if (
        not _under(seal_path, target_root)
        or not seal_path.is_file()
        or seal_path.is_symlink()
        or stat.S_IMODE(seal_path.stat().st_mode)
        != (stat.S_IRUSR | stat.S_IWUSR)
    ):
        _deny("ACTIVATION_TARGET_SEAL_UNAVAILABLE")
    seal = load_manifest(seal_path)
    if (
        seal.get("schema") != TARGET_SEAL_SCHEMA
        or seal.get("sealDigest") != _digest_without(seal, "sealDigest")
        or reference.get("sealDigest") != seal.get("sealDigest")
        or seal.get("runId") != manifest.get("runId")
        or seal.get("targetId") != manifest.get("targetId")
        or seal.get("candidate") != manifest.get("candidate")
        or seal.get("exactTuple") != manifest.get("exactTuple")
        or seal.get("serverGeneratedIds") is not True
        or seal.get("productionApiOnly") is not True
        or seal.get("bootstrapMaterialPurged") is not True
        or seal.get("activationMutationCount") != 0
        or seal.get("providerEffectCount") != 0
        or seal.get("modelSubmissionCount") != 0
    ):
        _deny("ACTIVATION_TARGET_SEAL_DIGEST_MISMATCH")
    for reference_key, seal_key in (
        ("schemaPlanDigest", "schemaPlanDigest"),
        ("schemaResultDigest", "schemaResultDigest"),
        ("provisioningResultDigest", "provisioningResultDigest"),
        ("workerReadinessDigest", "workerReadinessDigest"),
    ):
        if reference.get(reference_key) != seal.get(seal_key):
            _deny("ACTIVATION_TARGET_SEAL_DIGEST_MISMATCH")
    schema_plan_path = Path(_required_text(
        target.get("schemaPlan"), "ACTIVATION_TARGET_SEAL_PATH_MISMATCH"
    ))
    if (
        not _under(schema_plan_path, target_root)
        or not schema_plan_path.is_file()
        or schema_plan_path.is_symlink()
    ):
        _deny("ACTIVATION_TARGET_SEAL_INPUT_INVALID")
    schema_plan = load_manifest(schema_plan_path)
    if (
        schema_plan.get("schema") != SCHEMA_PLAN_SCHEMA
        or schema_plan.get("candidateHead") != manifest.get("candidate", {}).get("head")
        or schema_plan.get("planDigest") != _digest_without(schema_plan, "planDigest")
        or schema_plan.get("planDigest") != seal.get("schemaPlanDigest")
    ):
        _deny("ACTIVATION_TARGET_SEAL_INPUT_INVALID")
    profile_path_digests = seal.get("profilePathDigests")
    profile_keys = (
        ("provider", "providerProfile"),
        ("runtime", "runtimeCredentialProfile"),
        ("worker", "workerProfile"),
        ("navigator", "navigatorRuntimeProfile"),
        ("database", "databaseProfile"),
        ("control", "controlProfile"),
    )
    if not isinstance(profile_path_digests, dict):
        _deny("ACTIVATION_TARGET_SEAL_DIGEST_MISMATCH")
    for role, key in profile_keys:
        path = str(Path(_required_text(
            target.get(key), "ACTIVATION_TARGET_SEAL_PATH_MISMATCH"
        )).resolve())
        if profile_path_digests.get(role) != _sha256(f"{role}\0{path}"):
            _deny("ACTIVATION_TARGET_SEAL_PATH_MISMATCH")
    bootstrap_path = Path(
        _required_text(
            target.get("bootstrapProfile"),
            "ACTIVATION_TARGET_SEAL_PATH_MISMATCH",
        )
    )
    if bootstrap_path.exists():
        _deny("ACTIVATION_CREDENTIAL_PURGE_REQUIRED")
    sealed_results = (
        (
            "schemaResult", SCHEMA_RESULT_SCHEMA, "schemaResultDigest",
            "hibernateValidated",
        ),
        (
            "provisioningResult", PROVISIONING_RESULT_SCHEMA,
            "provisioningResultDigest", "productionApiOnly",
        ),
        (
            "workerReadinessResult", WORKER_READINESS_SCHEMA,
            "workerReadinessDigest", "authenticatedInventory",
        ),
    )
    for path_key, expected_schema, digest_key, required_true in sealed_results:
        result_path = Path(_required_text(
            target.get(path_key), "ACTIVATION_TARGET_SEAL_PATH_MISMATCH"
        ))
        if (
            not _under(result_path, target_root)
            or not result_path.is_file()
            or result_path.is_symlink()
        ):
            _deny("ACTIVATION_TARGET_SEAL_INPUT_INVALID")
        result = load_manifest(result_path)
        if (
            result.get("schema") != expected_schema
            or result.get("runId") != manifest.get("runId")
            or result.get("targetId") != manifest.get("targetId")
            or result.get("resultDigest") != _digest_without(result, "resultDigest")
            or result.get("resultDigest") != seal.get(digest_key)
            or result.get(required_true) is not True
        ):
            _deny("ACTIVATION_TARGET_SEAL_INPUT_INVALID")
    worker_values = _profile_selected_values(
        Path(target["workerProfile"]), frozenset({"CODEX_NAVIGATOR_WORKER_ID"})
    )
    if worker_values["CODEX_NAVIGATOR_WORKER_ID"] != manifest["exactTuple"].get(
        "physicalWorkerId"
    ):
        _deny("ACTIVATION_TARGET_SEAL_WORKER_ID_MISMATCH")
    return seal


def _reject_secret_shaped_manifest_fields(value: Any, path: str = "") -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            lowered = str(key).lower()
            allowed_digest = lowered.endswith("sha256") or lowered.endswith("digest")
            if not allowed_digest and lowered in {
                "secret",
                "token",
                "credential",
                "apikey",
                "api_key",
                "prompt",
                "response",
            }:
                _deny("ACTIVATION_TARGET_MANIFEST_SECRET_FIELD_FORBIDDEN")
            _reject_secret_shaped_manifest_fields(nested, f"{path}.{key}")
    elif isinstance(value, list):
        for nested in value:
            _reject_secret_shaped_manifest_fields(nested, path)


def _validate_controllers(
    manifest: dict[str, Any], target_root: Path, run_id: str, head: str
) -> list[dict[str, Any]]:
    controllers = manifest.get("controllers")
    if not isinstance(controllers, list) or not controllers:
        _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    observed_kinds: set[str] = set()
    observed_ids: set[tuple[str, str]] = set()
    for controller in controllers:
        if not isinstance(controller, dict):
            _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
        kind = _required_text(
            controller.get("kind"), "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        )
        identifier = _required_text(
            controller.get("id"), "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        )
        state = _required_text(
            controller.get("state"), "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        )
        if state not in CONTROLLER_STATES:
            _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
        if (kind, identifier) in observed_ids:
            _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
        observed_ids.add((kind, identifier))
        observed_kinds.add(kind)
        if controller.get("restartPolicy") != "NONE":
            _deny("ACTIVATION_CONTROLLER_RESTART_POLICY_ENABLED")
        if controller.get("ownershipRunId") != run_id:
            _deny("ACTIVATION_CONTROLLER_OWNERSHIP_UNPROVEN")
        if controller.get("artifactCommit") != head:
            _deny("ACTIVATION_CONTROLLER_CANDIDATE_MISMATCH")
        cwd = Path(
            _required_text(
                controller.get("cwd"), "ACTIVATION_TARGET_CWD_UNPROVEN"
            )
        )
        if not _under(cwd, target_root):
            _deny("ACTIVATION_TARGET_CWD_UNPROVEN")
        _required_text(
            controller.get("source"), "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        )
        expected = CONTROLLER_CONTRACT.get(kind)
        if expected != (
            identifier, state, controller.get("source")
        ):
            _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    if observed_kinds != REQUIRED_CONTROLLER_KINDS or len(controllers) != len(
        REQUIRED_CONTROLLER_KINDS
    ):
        _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    if manifest.get("controllerInventoryDigest") != canonical_controller_digest(
        controllers
    ):
        _deny("ACTIVATION_CONTROLLER_INVENTORY_DIGEST_MISMATCH")
    return controllers


def _validate_controller_checks(
    controllers: list[dict[str, Any]], environment_snapshot: dict[str, Any]
) -> None:
    if (
        environment_snapshot.get("evidenceSource") != "LIVE_LOCAL_INSPECTION"
        or environment_snapshot.get("inventoryComplete") is not True
        or environment_snapshot.get("portProbeOnly") is not False
        or environment_snapshot.get("unknownControllerCount") != 0
    ):
        _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    checks = environment_snapshot.get("controllerChecks")
    if not isinstance(checks, list):
        _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    indexed = {
        (check.get("kind"), check.get("id")): check
        for check in checks
        if isinstance(check, dict)
    }
    if len(indexed) != len(controllers):
        _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
    for controller in controllers:
        check = indexed.get((controller["kind"], controller["id"]))
        if (
            check is None
            or check.get("stateVerified") is not True
            or check.get("observedState") != controller["state"]
            or check.get("source") != controller["source"]
        ):
            _deny("ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")


def doctor(
    manifest: dict[str, Any], environment_snapshot: dict[str, Any],
    phase: str = "preflight",
) -> dict[str, Any]:
    """Validate an exact target without writing or mutating any resource."""
    if phase not in {"preflight", "running", "cleanup"}:
        _deny("ACTIVATION_TARGET_PHASE_INVALID")
    _reject_secret_shaped_manifest_fields(manifest)
    manifest_schema = manifest.get("schema")
    sealed = manifest_schema == SCHEMA
    provisioning = manifest_schema == PROVISIONING_SCHEMA
    if not sealed and not provisioning:
        _deny("ACTIVATION_TARGET_MANIFEST_SCHEMA_MISMATCH")
    if phase == "running" and not sealed:
        _deny("ACTIVATION_TARGET_SEAL_REQUIRED")
    if provisioning and manifest.get("lifecyclePhase") != "PROVISIONING_CLOSED":
        _deny("ACTIVATION_PROVISIONING_TARGET_INVALID")
    if manifest.get("targetClass") != EXACT_TARGET_CLASS:
        _deny("ACTIVATION_TARGET_CLASS_MISMATCH")
    if manifest.get("providerEvidenceLane") != EXACT_PROVIDER_LANE:
        _deny("ACTIVATION_PROVIDER_EVIDENCE_LANE_MISMATCH")
    run_id = _required_text(
        manifest.get("runId"), "ACTIVATION_TARGET_RUN_ID_REQUIRED"
    )
    target_id = _required_text(
        manifest.get("targetId"), "ACTIVATION_TARGET_ID_REQUIRED"
    )
    if run_id not in target_id and "arch001-act" not in target_id:
        _deny("ACTIVATION_TARGET_RUN_ID_UNPROVEN")

    candidate = manifest.get("candidate")
    exact_tuple = manifest.get("exactTuple")
    target = manifest.get("target")
    worker = manifest.get("worker")
    if not all(isinstance(value, dict) for value in (
        candidate, exact_tuple, target, worker
    )):
        _deny("ACTIVATION_TARGET_MANIFEST_INVALID")
    head = _required_text(
        candidate.get("head"), "ACTIVATION_TARGET_CANDIDATE_REQUIRED"
    )
    if len(head) != 40 or any(ch not in "0123456789abcdef" for ch in head):
        _deny("ACTIVATION_TARGET_CANDIDATE_INVALID")
    patch_digest = _required_text(
        candidate.get("patchSha256"), "ACTIVATION_TARGET_PATCH_DIGEST_REQUIRED"
    )
    if len(patch_digest) != 64 or any(
        ch not in "0123456789abcdef" for ch in patch_digest
    ):
        _deny("ACTIVATION_TARGET_PATCH_DIGEST_INVALID")
    if _required_int(
        candidate.get("ownerProtocol"), "ACTIVATION_OWNER_PROTOCOL_REQUIRED"
    ) < 1:
        _deny("ACTIVATION_OWNER_PROTOCOL_REQUIRED")
    if exact_tuple.get("providerType") != EXACT_PROVIDER:
        _deny("ACTIVATION_TARGET_PROVIDER_MISMATCH")
    for key in ("tenantId", "model", "codexHomeKey"):
        _required_text(
            exact_tuple.get(key), "ACTIVATION_TARGET_EXACT_TUPLE_INCOMPLETE"
        )
    if not str(exact_tuple.get("tenantId")).startswith("synthetic-"):
        _deny("ACTIVATION_TARGET_SYNTHETIC_TUPLE_REQUIRED")
    for key in ("userId", "physicalWorkerId", "modelConfigId"):
        value = exact_tuple.get(key)
        if sealed:
            _required_text(value, "ACTIVATION_TARGET_EXACT_TUPLE_INCOMPLETE")
            if str(value).startswith("synthetic-"):
                _deny("ACTIVATION_TARGET_SERVER_GENERATED_TUPLE_REQUIRED")
        elif value not in {None, ""}:
            _deny("ACTIVATION_PROVISIONING_CALLER_SELECTED_ID_FORBIDDEN")
    prompt_digest = _required_text(
        exact_tuple.get("promptSha256"),
        "ACTIVATION_TARGET_STATIC_PROMPT_DIGEST_REQUIRED",
    )
    if len(prompt_digest) != 64:
        _deny("ACTIVATION_TARGET_STATIC_PROMPT_DIGEST_INVALID")

    host = _required_text(target.get("host"), "ACTIVATION_TARGET_HOST_REQUIRED")
    try:
        if not socket.inet_aton(host) or not host.startswith("127."):
            _deny("ACTIVATION_TARGET_NON_LOOPBACK")
    except OSError:
        _deny("ACTIVATION_TARGET_NON_LOOPBACK")
    navigator_port = _required_int(
        target.get("navigatorPort"), "ACTIVATION_TARGET_PORT_REQUIRED"
    )
    worker_port = _required_int(
        target.get("workerPort"), "ACTIVATION_TARGET_PORT_REQUIRED"
    )
    mysql_port = _required_int(
        target.get("mysqlPort"), "ACTIVATION_TARGET_PORT_REQUIRED"
    )
    target_ports = {navigator_port, worker_port, mysql_port}
    if target_ports & PROTECTED_PORTS:
        _deny("ACTIVATION_TARGET_PROTECTED_PORT")
    if (
        len(target_ports) != 3
        or min(target_ports) < 1024
        or max(target_ports) > 65535
    ):
        _deny("ACTIVATION_TARGET_PORT_INVALID")
    if target.get("mysqlVersion") != EXACT_MYSQL_VERSION:
        _deny("ACTIVATION_TARGET_MYSQL_VERSION_MISMATCH")

    database = _required_text(
        target.get("database"), "ACTIVATION_TARGET_DATABASE_REQUIRED"
    )
    shared_databases = {
        str(value).lower()
        for value in environment_snapshot.get("sharedDatabases", [])
    } | SHARED_DATABASES
    if database.lower() in shared_databases:
        _deny("ACTIVATION_TARGET_SHARED_DATABASE")
    normalized_run = run_id.lower().replace("-", "_").replace(".", "_")
    if normalized_run not in database.lower():
        _deny("ACTIVATION_TARGET_DATABASE_OWNERSHIP_UNPROVEN")

    docker_project = _required_text(
        target.get("dockerProject"), "ACTIVATION_TARGET_DOCKER_PROJECT_REQUIRED"
    )
    current_project = str(
        environment_snapshot.get("currentDockerProject", "")
    ).lower()
    if (
        docker_project.lower() in SHARED_DOCKER_PROJECTS
        or docker_project.lower() == current_project
    ):
        _deny("ACTIVATION_TARGET_SHARED_DOCKER_PROJECT")
    if run_id.lower() not in docker_project.lower():
        _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")

    configured_target_root = Path(
        _required_text(target.get("root"), "ACTIVATION_TARGET_ROOT_REQUIRED")
    )
    if configured_target_root.is_symlink():
        _deny("ACTIVATION_TARGET_ROOT_OWNERSHIP_UNPROVEN")
    target_root = configured_target_root.resolve()
    if target_root in {Path("/"), Path.home().resolve()} or run_id not in str(
        target_root
    ) or any(
        target_root == protected or _under(target_root, protected)
        for protected in PROTECTED_HOMES
    ):
        _deny("ACTIVATION_TARGET_ROOT_OWNERSHIP_UNPROVEN")
    worker_home = Path(
        _required_text(
            target.get("workerHome"), "ACTIVATION_TARGET_WORKER_HOME_REQUIRED"
        )
    ).resolve()
    if any(
        worker_home == protected or _under(worker_home, protected)
        for protected in PROTECTED_HOMES
    ):
        _deny("ACTIVATION_TARGET_PROTECTED_HOME")
    for key in (
        "workdir",
        "workerHome",
        "evidenceDir",
        "composeFile",
        "navigatorPidFile",
        "workerPidFile",
        "observationFile",
        "navigatorArtifact",
        "schemaPlan",
        "schemaResult",
        "bootstrapProfile",
        "runtimeCredentialProfile",
        "provisioningProgress",
        "provisioningResult",
        "workerReadinessResult",
        "provisioningSeal",
    ):
        path = Path(
            _required_text(target.get(key), "ACTIVATION_TARGET_CWD_UNPROVEN")
        )
        if not _under(path, target_root):
            _deny("ACTIVATION_TARGET_CWD_UNPROVEN")
    compose_file = Path(target["composeFile"])
    if not compose_file.is_file() or compose_file.is_symlink():
        _deny("ACTIVATION_TARGET_COMPOSE_UNAVAILABLE")
    navigator_artifact = Path(target["navigatorArtifact"])
    artifact_digest = _required_text(
        target.get("navigatorArtifactSha256"),
        "ACTIVATION_TARGET_ARTIFACT_DIGEST_REQUIRED",
    )
    try:
        observed_artifact_digest = hashlib.sha256(
            navigator_artifact.read_bytes()
        ).hexdigest()
    except OSError:
        _deny("ACTIVATION_TARGET_ARTIFACT_MISMATCH")
    if (
        len(artifact_digest) != 64
        or any(ch not in "0123456789abcdef" for ch in artifact_digest)
        or not navigator_artifact.is_file()
        or navigator_artifact.is_symlink()
        or observed_artifact_digest != artifact_digest
    ):
        _deny("ACTIVATION_TARGET_ARTIFACT_MISMATCH")

    seal = _validate_seal(manifest, target, target_root) if sealed else None

    controllers = _validate_controllers(manifest, target_root, run_id, head)
    _validate_controller_checks(controllers, environment_snapshot)
    observed_roles: set[str] = set()
    for process in environment_snapshot.get("processes", []):
        if not isinstance(process, dict):
            _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
        if process.get("runId") != run_id:
            _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
        cwd = Path(str(process.get("cwd", "")))
        if not _under(cwd, target_root):
            _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
        role = process.get("role")
        if role not in {"navigator", "worker"}:
            _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
        observed_roles.add(str(role))
        if phase == "preflight":
            _deny("ACTIVATION_CONTROLLER_LATE_RELAUNCH")
    if phase == "running" and observed_roles != {"navigator", "worker"}:
        _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
    observed_docker = {
        "container": 0,
        "network": 0,
        "volume": 0,
    }
    observed_container_running: bool | None = None
    for resource in environment_snapshot.get("dockerResources", []):
        if not isinstance(resource, dict):
            _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
        if resource.get("project") == docker_project:
            if resource.get("runId") != run_id:
                _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
            kind = resource.get("kind")
            if kind not in observed_docker:
                _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
            if kind == "container":
                if resource.get("image") != "mysql:8.0.44":
                    _deny("ACTIVATION_TARGET_MYSQL_VERSION_MISMATCH")
                if resource.get("restartPolicy") != "no":
                    _deny("ACTIVATION_CONTROLLER_RESTART_POLICY_ENABLED")
                if not isinstance(resource.get("running"), bool):
                    _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
                observed_container_running = resource["running"]
            observed_docker[str(kind)] += 1
            if phase == "preflight" and not sealed:
                _deny("ACTIVATION_CONTROLLER_LATE_RELAUNCH")
    if phase == "preflight" and sealed and observed_docker not in (
        {"container": 0, "network": 0, "volume": 0},
        {"container": 1, "network": 1, "volume": 1},
    ):
        _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
    if (
        phase == "preflight"
        and sealed
        and observed_docker["container"] == 1
        and observed_container_running is not False
    ):
        _deny("ACTIVATION_CONTROLLER_LATE_RELAUNCH")
    if phase == "running" and observed_docker != {
        "container": 1,
        "network": 1,
        "volume": 1,
    }:
        _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
    if phase == "running" and observed_container_running is not True:
        _deny("ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN")
    listening_ports = {
        int(value) for value in environment_snapshot.get("listeningPorts", [])
    }
    if phase == "preflight" and target_ports & listening_ports:
        _deny("ACTIVATION_TARGET_PORT_OCCUPIED")
    if phase == "running" and not target_ports.issubset(listening_ports):
        _deny("ACTIVATION_TARGET_PORT_OWNERSHIP_UNPROVEN")

    provider_profile = Path(
        _required_text(
            target.get("providerProfile"), "ACTIVATION_PROFILE_UNAVAILABLE"
        )
    )
    control_profile = Path(
        _required_text(
            target.get("controlProfile"), "ACTIVATION_PROFILE_UNAVAILABLE"
        )
    )
    worker_profile = Path(
        _required_text(
            target.get("workerProfile"), "ACTIVATION_PROFILE_UNAVAILABLE"
        )
    )
    runtime_profile = Path(
        _required_text(
            target.get("navigatorRuntimeProfile"),
            "ACTIVATION_PROFILE_UNAVAILABLE",
        )
    )
    database_profile = Path(
        _required_text(
            target.get("databaseProfile"), "ACTIVATION_PROFILE_UNAVAILABLE"
        )
    )
    bootstrap_profile = Path(
        _required_text(
            target.get("bootstrapProfile"), "ACTIVATION_PROFILE_UNAVAILABLE"
        )
    )
    runtime_credential_profile = Path(
        _required_text(
            target.get("runtimeCredentialProfile"),
            "ACTIVATION_PROFILE_UNAVAILABLE",
        )
    )
    phase_profiles = [
        provider_profile,
        worker_profile,
        runtime_profile,
        database_profile,
        control_profile,
    ]
    phase_profiles.append(
        bootstrap_profile if provisioning else runtime_credential_profile
    )
    if not all(
        _under(profile, target_root)
        for profile in phase_profiles
    ):
        _deny("ACTIVATION_PROFILE_OWNERSHIP_UNPROVEN")
    provider_names = _profile_variable_names(
        provider_profile,
        PROVIDER_PROFILE_ALLOWLIST,
        PROVIDER_PROFILE_REQUIRED,
    )
    worker_names = _profile_variable_names(
        worker_profile,
        WORKER_PROFILE_ALLOWLIST,
        WORKER_PROFILE_REQUIRED,
    )
    runtime_names = _profile_variable_names(
        runtime_profile,
        RUNTIME_PROFILE_ALLOWLIST,
        RUNTIME_PROFILE_REQUIRED,
    )
    database_names = _profile_variable_names(
        database_profile,
        DATABASE_PROFILE_ALLOWLIST,
        DATABASE_PROFILE_REQUIRED,
    )
    control_names = _profile_variable_names(
        control_profile,
        CONTROL_PROFILE_ALLOWLIST,
        CONTROL_PROFILE_REQUIRED,
    )
    bootstrap_names: list[str] = []
    runtime_credential_names: list[str] = []
    if provisioning:
        bootstrap_names = _profile_variable_names(
            bootstrap_profile,
            BOOTSTRAP_PROFILE_ALLOWLIST,
            BOOTSTRAP_PROFILE_REQUIRED,
        )
    else:
        runtime_credential_names = _profile_variable_names(
            runtime_credential_profile,
            RUNTIME_CREDENTIAL_PROFILE_ALLOWLIST,
            RUNTIME_CREDENTIAL_PROFILE_REQUIRED,
        )
    control_values = _profile_selected_values(
        control_profile,
        frozenset(
            {
                "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED",
                "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED",
            }
        ),
    )
    if phase == "preflight" and control_values != {
        "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED": "false",
        "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED": "false",
    }:
        _deny("ACTIVATION_TARGET_PRESTART_GATE_NOT_CLOSED")
    runtime_values = _profile_selected_values(
        runtime_profile,
        frozenset(
            {
                "SPRING_JPA_HIBERNATE_DDL_AUTO",
                "NAVIGATOR_EXTERNAL_ENABLED",
                "NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED",
            }
        ),
    )
    if runtime_values != {
        "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
        "NAVIGATOR_EXTERNAL_ENABLED": "false",
        "NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED": "false",
    }:
        _deny("ACTIVATION_TARGET_RUNTIME_CONTRACT_INVALID")
    required_capabilities = worker.get("requiredCapabilities")
    if not isinstance(required_capabilities, list) or len(
        set(required_capabilities)
    ) != len(required_capabilities):
        _deny("ACTIVATION_WORKER_CAPABILITY_CONTRACT_INVALID")
    if _required_int(
        worker.get("protocolVersion"), "ACTIVATION_WORKER_PROTOCOL_REQUIRED"
    ) < 1:
        _deny("ACTIVATION_WORKER_PROTOCOL_REQUIRED")
    _required_text(worker.get("version"), "ACTIVATION_WORKER_VERSION_REQUIRED")

    return {
        "schema": "NAVIGATOR_ARCH001_ACTIVATION_DOCTOR_V1",
        "ready": True,
        "safeReasonCode": (
            "ACTIVATION_TARGET_DOCTOR_READY"
            if sealed else "ACTIVATION_PROVISIONING_DOCTOR_READY"
        ),
        "targetId": target_id,
        "runId": run_id,
        "manifestDigest": manifest_digest(manifest),
        "controllerInventoryDigest": manifest["controllerInventoryDigest"],
        "phase": phase,
        "providerProfileVariables": provider_names,
        "workerProfileVariables": worker_names,
        "navigatorRuntimeProfileVariables": runtime_names,
        "databaseProfileVariables": database_names,
        "controlProfileVariables": control_names,
        "bootstrapProfileVariables": bootstrap_names,
        "runtimeCredentialProfileVariables": runtime_credential_names,
        "lifecyclePhase": manifest.get("lifecyclePhase"),
        "sealDigest": seal.get("sealDigest") if seal else None,
        "writesPerformed": 0,
    }


def cleanup_plan(
    manifest: dict[str, Any],
    environment_snapshot: dict[str, Any],
    confirmation: str | None,
) -> dict[str, Any]:
    result = doctor(manifest, environment_snapshot, phase="cleanup")
    expected = result["manifestDigest"]
    if confirmation is not None and confirmation != expected:
        _deny("ACTIVATION_CLEANUP_CONFIRMATION_REQUIRED")
    target = manifest["target"]
    return {
        "schema": "NAVIGATOR_ARCH001_ACTIVATION_CLEANUP_PLAN_V1",
        "execute": False,
        "executionAuthorized": confirmation == expected,
        "runId": manifest["runId"],
        "manifestDigest": expected,
        "ownedDockerProject": target["dockerProject"],
        "ownedComposeFile": target["composeFile"],
        "ownedTargetRoot": target["root"],
        "writesPerformed": 0,
    }


def execute_cleanup(
    manifest: dict[str, Any],
    environment_snapshot: dict[str, Any],
    confirmation: str | None,
    *,
    process_signal: Any = os.kill,
    command_runner: Any = subprocess.run,
) -> dict[str, Any]:
    """Stop only resources re-proven as owned by the exact manifest.

    Evidence and the target root are deliberately preserved. Destruction of
    the evidence directory is never part of this command.
    """
    plan = cleanup_plan(manifest, environment_snapshot, confirmation)
    if not plan["executionAuthorized"]:
        _deny("ACTIVATION_CLEANUP_CONFIRMATION_REQUIRED")
    writes = 0
    for process in environment_snapshot.get("processes", []):
        process_signal(int(process["pid"]), signal.SIGTERM)
        writes += 1
    resources = environment_snapshot.get("dockerResources", [])
    if resources:
        target = manifest["target"]
        completed = command_runner(
            [
                "docker",
                "compose",
                "--project-name",
                target["dockerProject"],
                "--file",
                target["composeFile"],
                "down",
                "--volumes",
                "--remove-orphans",
            ],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        if completed.returncode != 0:
            _deny("ACTIVATION_CLEANUP_DOCKER_FAILED")
        writes += 1
    return {
        **plan,
        "schema": "NAVIGATOR_ARCH001_ACTIVATION_CLEANUP_RESULT_V1",
        "execute": True,
        "writesPerformed": writes,
        "targetRootPreserved": True,
        "safeReasonCode": "ACTIVATION_OWNED_TARGET_STOPPED",
    }


def _port_listening(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.15)
        return probe.connect_ex((host, port)) == 0


def is_owned_runtime_descendant(
    pid: int,
    owned_runtime_pids: set[int],
    parent_by_pid: dict[int, int],
) -> bool:
    """Return whether a process is descended from an exact owned runtime.

    Worker task subprocesses are workload, not relaunch controllers. An
    orphaned, re-parented, or cyclic process remains unproven and therefore is
    still reported as unknown by the live controller scan.
    """
    seen: set[int] = set()
    current = pid
    while current > 1 and current not in seen:
        seen.add(current)
        current = parent_by_pid.get(current, 0)
        if current in owned_runtime_pids:
            return True
    return False


def _proc_parent_pid(proc: Path) -> int | None:
    try:
        for line in (proc / "status").read_text(
            encoding="utf-8", errors="replace"
        ).splitlines():
            if line.startswith("PPid:"):
                return int(line.partition(":")[2].strip())
    except (OSError, ValueError):
        return None
    return None


def live_environment_snapshot(manifest: dict[str, Any]) -> dict[str, Any]:
    target = manifest.get("target", {})
    target_root_value = target.get("root")
    run_id_value = manifest.get("runId")
    if not isinstance(target_root_value, str) or not target_root_value:
        _deny("ACTIVATION_TARGET_ROOT_OWNERSHIP_UNPROVEN")
    if not isinstance(run_id_value, str) or not run_id_value:
        _deny("ACTIVATION_TARGET_RUN_ID_REQUIRED")
    target_root = Path(target_root_value).resolve()
    run_id = run_id_value
    host = str(target.get("host", "127.0.0.1"))
    ports = [
        target.get("navigatorPort"),
        target.get("workerPort"),
        target.get("mysqlPort"),
    ]
    listening = [
        port
        for port in ports
        if isinstance(port, int) and _port_listening(host, port)
    ]
    resources: list[dict[str, Any]] = []
    inventory_complete = True
    project = target.get("dockerProject")
    if isinstance(project, str) and project:
        try:
            output = subprocess.run(
                [
                    "docker",
                    "ps",
                    "-a",
                    "--filter",
                    f"label=com.docker.compose.project={project}",
                    "--format",
                    "{{.Names}}|{{.Image}}|"
                    "{{.Label \"com.docker.compose.project\"}}|"
                    "{{.Label \"com.foggy.navigator.activation.run-id\"}}",
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=5,
            )
            if output.returncode == 0:
                for line in output.stdout.splitlines():
                    name, image, observed_project, observed_run = (
                        line.split("|") + ["", "", ""]
                    )[:4]
                    container_state = subprocess.run(
                        [
                            "docker", "inspect", "--format",
                            "{{.HostConfig.RestartPolicy.Name}}|{{.State.Running}}",
                            name,
                        ],
                        check=False,
                        capture_output=True,
                        text=True,
                        timeout=5,
                    )
                    if container_state.returncode != 0:
                        inventory_complete = False
                    restart_policy, _, running_text = (
                        container_state.stdout.strip().partition("|")
                    )
                    resources.append(
                        {
                            "kind": "container",
                            "name": name,
                            "image": image,
                            "project": observed_project,
                            "runId": observed_run,
                            "restartPolicy": restart_policy,
                            "running": running_text.lower() == "true",
                        }
                    )
            else:
                inventory_complete = False
        except (OSError, subprocess.SubprocessError):
            inventory_complete = False
        for kind, noun in (("network", "network"), ("volume", "volume")):
            try:
                output = subprocess.run(
                    [
                        "docker", noun, "ls",
                        "--filter",
                        f"label=com.docker.compose.project={project}",
                        "--format",
                        "{{.Name}}|"
                        "{{.Label \"com.docker.compose.project\"}}|"
                        "{{.Label \"com.foggy.navigator.activation.run-id\"}}",
                    ],
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if output.returncode != 0:
                    inventory_complete = False
                    continue
                for line in output.stdout.splitlines():
                    name, observed_project, observed_run = (
                        line.split("|") + ["", ""]
                    )[:3]
                    resources.append(
                        {
                            "kind": kind,
                            "name": name,
                            "project": observed_project,
                            "runId": observed_run,
                        }
                    )
            except (OSError, subprocess.SubprocessError):
                inventory_complete = False
    processes: list[dict[str, Any]] = []
    role_pids: dict[int, str] = {}
    for role, key in (
        ("navigator", "navigatorPidFile"),
        ("worker", "workerPidFile"),
    ):
        path = Path(str(target.get(key, "")))
        if not path.is_file():
            continue
        try:
            mode = stat.S_IMODE(path.stat().st_mode)
            if path.is_symlink() or mode & (stat.S_IWGRP | stat.S_IWOTH):
                _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
            pid = int(path.read_text(encoding="utf-8").strip())
        except (OSError, ValueError):
            _deny("ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN")
        role_pids[pid] = role
    try:
        proc_entries = [
            proc for proc in Path("/proc").iterdir() if proc.name.isdigit()
        ]
        parent_by_pid = {
            int(proc.name): parent
            for proc in proc_entries
            if (parent := _proc_parent_pid(proc)) is not None
        }
        for proc in proc_entries:
            if not proc.name.isdigit():
                continue
            try:
                cwd = (proc / "cwd").resolve(strict=True)
            except (OSError, RuntimeError):
                continue
            if not _under(cwd, target_root):
                continue
            pid = int(proc.name)
            if pid not in role_pids and is_owned_runtime_descendant(
                pid, set(role_pids), parent_by_pid
            ):
                continue
            processes.append(
                {
                    "pid": pid,
                    "cwd": str(cwd),
                    "runId": run_id,
                    "role": role_pids.get(pid, "unknown"),
                }
            )
    except OSError:
        inventory_complete = False
    observed_pids = {int(process["pid"]) for process in processes}
    if not set(role_pids).issubset(observed_pids):
        inventory_complete = False
    unknown_count = sum(
        process.get("role") == "unknown" for process in processes
    ) + sum(
        resource.get("project") == project
        and resource.get("runId") != run_id
        for resource in resources
    )
    controller_checks = []
    for controller in manifest.get("controllers", []):
        if not isinstance(controller, dict):
            continue
        controller_checks.append(
            {
                "kind": controller.get("kind"),
                "id": controller.get("id"),
                "observedState": controller.get("state"),
                "source": controller.get("source"),
                "stateVerified": CONTROLLER_CONTRACT.get(
                    str(controller.get("kind"))
                ) == (
                    controller.get("id"),
                    controller.get("state"),
                    controller.get("source"),
                ),
            }
        )
    return {
        "evidenceSource": "LIVE_LOCAL_INSPECTION",
        "inventoryComplete": inventory_complete,
        "portProbeOnly": False,
        "unknownControllerCount": unknown_count,
        "controllerChecks": controller_checks,
        "listeningPorts": listening,
        "processes": processes,
        "dockerResources": resources,
        "currentDockerProject": Path.cwd().name.lower(),
        "sharedDatabases": sorted(SHARED_DATABASES),
    }


def _write_observation(path: Path, result: dict[str, Any]) -> None:
    observation = {
        "schema": OBSERVATION_SCHEMA,
        "targetId": result["targetId"],
        "runId": result["runId"],
        "controllerInventoryDigest": result["controllerInventoryDigest"],
        "manifestDigest": result["manifestDigest"],
        "observedAt": datetime.now(timezone.utc).isoformat(),
        "allKnownControllersDisabled": True,
        "lateRelaunchDetected": False,
        "unknownControllerCount": 0,
        "evidenceSource": "LIVE_LOCAL_INSPECTION",
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(_canonical_json(observation) + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def _write_loss_observation(
    path: Path, manifest: dict[str, Any]
) -> None:
    _write_observation(
        path,
        {
            "targetId": manifest.get("targetId"),
            "runId": manifest.get("runId"),
            "controllerInventoryDigest": manifest.get(
                "controllerInventoryDigest"
            ),
            "manifestDigest": manifest_digest(manifest),
        },
    )
    value = load_manifest(path)
    value["allKnownControllersDisabled"] = False
    value["lateRelaunchDetected"] = True
    value["unknownControllerCount"] = 1
    temporary = path.with_name(f"{path.name}.{os.getpid()}.loss.tmp")
    temporary.write_text(_canonical_json(value) + "\n", encoding="utf-8")
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, path)


def watch_observations(
    manifest: dict[str, Any],
    output: Path,
    interval_seconds: float,
    max_observations: int = 0,
    *,
    snapshot_provider: Any = live_environment_snapshot,
    sleeper: Any = time.sleep,
) -> dict[str, Any]:
    if interval_seconds < 1 or interval_seconds > 10:
        _deny("ACTIVATION_OBSERVER_INTERVAL_INVALID")
    count = 0
    last: dict[str, Any] | None = None
    while max_observations == 0 or count < max_observations:
        try:
            last = doctor(
                manifest, snapshot_provider(manifest), phase="running"
            )
        except ActivationTargetError:
            _write_loss_observation(output, manifest)
            raise
        _write_observation(output, last)
        count += 1
        if max_observations == 0 or count < max_observations:
            sleeper(interval_seconds)
    return {
        **(last or {}),
        "schema": "NAVIGATOR_ARCH001_CONTROLLER_WATCH_V1",
        "observationsWritten": count,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="command", required=True)
    for name in ("doctor", "observe", "watch", "cleanup-plan", "cleanup"):
        command = subcommands.add_parser(name)
        command.add_argument("--manifest", required=True)
        if name == "doctor":
            command.add_argument("--snapshot")
            command.add_argument("--test-fixture", action="store_true")
        if name in {"observe", "watch"}:
            command.add_argument("--output", required=True)
        if name == "watch":
            command.add_argument("--interval-seconds", type=float, default=5)
            command.add_argument("--max-observations", type=int, default=0)
        if name in {"cleanup-plan", "cleanup"}:
            command.add_argument("--confirmation")
    args = parser.parse_args(argv)
    try:
        manifest = load_manifest(args.manifest)
        snapshot_path = getattr(args, "snapshot", None)
        if snapshot_path and not getattr(args, "test_fixture", False):
            _deny("ACTIVATION_FIXTURE_SNAPSHOT_NOT_AUTHORITY")
        snapshot = load_manifest(snapshot_path) if snapshot_path else (
            live_environment_snapshot(manifest)
        )
        if args.command == "cleanup-plan":
            result = cleanup_plan(manifest, snapshot, args.confirmation)
        elif args.command == "cleanup":
            result = execute_cleanup(
                manifest, snapshot, args.confirmation
            )
        elif args.command in {"observe", "watch"}:
            expected_output = Path(
                str(manifest.get("target", {}).get("observationFile", ""))
            ).resolve()
            output = Path(args.output).resolve()
            if output != expected_output:
                _deny("ACTIVATION_OBSERVATION_TARGET_MISMATCH")
            if args.command == "watch":
                result = watch_observations(
                    manifest, output, args.interval_seconds,
                    args.max_observations,
                )
            else:
                result = doctor(manifest, snapshot, phase="running")
                _write_observation(output, result)
        else:
            result = doctor(manifest, snapshot)
            if snapshot_path:
                result["fixtureSnapshot"] = True
        print(_canonical_json(result))
        return 0
    except ActivationTargetError as error:
        print(
            _canonical_json(
                {
                    "schema": "NAVIGATOR_ARCH001_ACTIVATION_DOCTOR_V1",
                    "ready": False,
                    "safeReasonCode": str(error),
                    "writesPerformed": 0,
                }
            )
        )
        return 2


if __name__ == "__main__":
    sys.exit(main())
