import copy
import hashlib
import json
import os
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from activation_target import (
    ActivationTargetError,
    canonical_controller_digest,
    cleanup_plan,
    doctor,
    execute_cleanup,
    is_owned_runtime_descendant,
    live_environment_snapshot,
    watch_observations,
)


class ActivationTargetDoctorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "arch001-act-run-20260801"
        self.root.mkdir()
        self.provider_profile = self.root / "provider.env"
        self.worker_profile = self.root / "worker.env"
        self.runtime_profile = self.root / "runtime.env"
        self.database_profile = self.root / "database.env"
        self.control_profile = self.root / "control.env"
        self.bootstrap_profile = self.root / "bootstrap.env"
        self.runtime_credential_profile = self.root / "runtime-credential.env"
        self.compose_file = self.root / "compose.yml"
        self.navigator_artifact = self.root / "artifacts/launcher.jar"
        self.navigator_artifact.parent.mkdir()
        self.navigator_artifact.write_bytes(b"candidate-launcher")
        self.provider_profile.write_text(
            "OPENAI_API_KEY=\n", encoding="utf-8"
        )
        self.worker_profile.write_text(
            "CODEX_WORKER_PORT=\n"
            "CODEX_WORKER_HOST=\n"
            "CODEX_WORKER_NAME=\n"
            "CODEX_WORKER_TOKEN=\n"
            "CODEX_WORKER_EXTERNAL_ENABLED=\n"
            "CODEX_ALLOWED_CWDS=\n"
            "CODEX_WORKER_CODEX_HOME=\n"
            "CODEX_BIZ_HOME_ROOT=\n"
            "CODEX_NAVIGATOR_WORKER_ID=__GENERATED_WORKER_ID__\n"
            "CODEX_TERMINATION_OPERATION_LEDGER_DIR=\n"
            "CODEX_LIFECYCLE_STORE_DIR=\n",
            encoding="utf-8",
        )
        self.runtime_profile.write_text(
            "SERVER_PORT=18112\n"
            "SPRING_DATASOURCE_URL=\n"
            "SPRING_DATASOURCE_USERNAME=\n"
            "SPRING_DATASOURCE_PASSWORD=\n"
            "SPRING_JPA_HIBERNATE_DDL_AUTO=validate\n"
            "NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED=true\n"
            "NAVIGATOR_EXTERNAL_ENABLED=false\n"
            "NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false\n"
            "NAVIGATOR_LIFECYCLE_SHADOW_ENABLED=false\n",
            encoding="utf-8",
        )
        self.database_profile.write_text(
            "MYSQL_DATABASE=\n"
            "MYSQL_USER=\n"
            "MYSQL_PASSWORD=\n"
            "MYSQL_ROOT_PASSWORD=\n",
            encoding="utf-8",
        )
        self.control_profile.write_text(
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_TOKEN=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED=false\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED=false\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_EXACT_TARGET_ID=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_MANIFEST_PATH=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_PATH=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_ID=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_HEAD=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CANDIDATE_PATCH_SHA256=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_OWNER_PROTOCOL=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_PROOF_LEASE=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_INSTANCE_TTL=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVATION_MAX_AGE=\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_OBSERVER_DELAY=\n",
            encoding="utf-8",
        )
        self.bootstrap_profile.write_text(
            "ARCH001_SYNTHETIC_TENANT_ID=synthetic-arch001-tenant\n"
            "ARCH001_SYNTHETIC_USERNAME=synthetic-user\n"
            "ARCH001_SYNTHETIC_PASSWORD=bootstrap-value\n"
            "ARCH001_SYNTHETIC_EMAIL=synthetic@example.invalid\n",
            encoding="utf-8",
        )
        self.runtime_credential_profile.write_text(
            "NAVI_RUNTIME_API_KEY=\n", encoding="utf-8"
        )
        os.chmod(self.provider_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(self.worker_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(self.runtime_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(self.database_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(self.control_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(self.bootstrap_profile, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(
            self.runtime_credential_profile, stat.S_IRUSR | stat.S_IWUSR
        )
        self.compose_file.write_text("services: {}\n", encoding="utf-8")
        self.manifest = self._manifest()
        self.manifest["controllerInventoryDigest"] = (
            canonical_controller_digest(self.manifest["controllers"])
        )
        schema_plan = {
            "schema": "NAVIGATOR_ARCH001_FRESH_SCHEMA_PLAN_V1",
            "candidateHead": self.manifest["candidate"]["head"],
            "mysqlVersion": "8.0.44",
            "files": [],
        }
        schema_plan["planDigest"] = self._digest_without(
            schema_plan, "planDigest"
        )
        schema_plan_path = self.root / "schema-plan.json"
        schema_plan_path.write_text(json.dumps(schema_plan), encoding="utf-8")
        os.chmod(schema_plan_path, stat.S_IRUSR | stat.S_IWUSR)

    def tearDown(self):
        self.temp.cleanup()

    def _manifest(self):
        run_id = "arch001-act-run-20260801"
        controllers = [
            self._controller("process", "target-process-set", "DISABLED"),
            self._controller("supervisor", "none", "NOT_APPLICABLE"),
            self._controller(
                "manual_launcher", "target-pidfiles", "DISABLED"
            ),
            self._controller("ci", "none", "NOT_APPLICABLE"),
            self._controller("timer", "none", "NOT_APPLICABLE"),
            self._controller("docker", "mysql-compose", "DISABLED"),
        ]
        return {
            "schema": "NAVIGATOR_ARCH001_PROVISIONING_TARGET_V1",
            "lifecyclePhase": "PROVISIONING_CLOSED",
            "targetId": "arch001-act-target-20260801",
            "runId": run_id,
            "targetClass": "ISOLATED_LOCAL_NON_FIXTURE",
            "providerEvidenceLane": "REAL_CODEX_MODEL",
            "candidate": {
                "head": "fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00",
                "patchSha256": "a" * 64,
                "ownerProtocol": 1,
            },
            "exactTuple": {
                "providerType": "codex-biz-worker",
                "tenantId": "synthetic-arch001-tenant",
                "userId": None,
                "physicalWorkerId": None,
                "modelConfigId": None,
                "model": "gpt-5.6-sol",
                "codexHomeKey": "synthetic/arch001/canary",
                "promptSha256": "b" * 64,
            },
            "target": {
                "host": "127.0.0.1",
                "navigatorPort": 18112,
                "workerPort": 13051,
                "mysqlPort": 13306,
                "mysqlVersion": "8.0.44",
                "database": "arch001_act_run_20260801",
                "dockerProject": "arch001-act-run-20260801",
                "root": str(self.root),
                "workdir": str(self.root / "workdir"),
                "workerHome": str(self.root / "worker-home"),
                "providerProfile": str(self.provider_profile),
                "workerProfile": str(self.worker_profile),
                "navigatorRuntimeProfile": str(self.runtime_profile),
                "databaseProfile": str(self.database_profile),
                "controlProfile": str(self.control_profile),
                "bootstrapProfile": str(self.bootstrap_profile),
                "runtimeCredentialProfile": str(
                    self.runtime_credential_profile
                ),
                "composeFile": str(self.compose_file),
                "navigatorArtifact": str(self.navigator_artifact),
                "navigatorArtifactSha256": hashlib.sha256(
                    self.navigator_artifact.read_bytes()
                ).hexdigest(),
                "schemaPlan": str(self.root / "schema-plan.json"),
                "schemaResult": str(self.root / "schema-result.json"),
                "provisioningProgress": str(
                    self.root / "provisioning-progress.json"
                ),
                "provisioningResult": str(
                    self.root / "provisioning-result.json"
                ),
                "workerReadinessResult": str(
                    self.root / "worker-readiness-result.json"
                ),
                "provisioningSeal": str(self.root / "provisioning-seal.json"),
                "evidenceDir": str(self.root / "evidence"),
                "navigatorPidFile": str(self.root / "navigator.pid"),
                "workerPidFile": str(self.root / "worker.pid"),
                "observationFile": str(
                    self.root / "controller-observation.json"
                ),
            },
            "worker": {
                "version": "source-candidate",
                "protocolVersion": 1,
                "requiredCapabilities": [
                    "AUTHENTICATED_LIFECYCLE_V1",
                    "FENCED_INVENTORY_V1",
                    "DURABLE_LIFECYCLE_FACTS_V1",
                    "MONOTONIC_ACK_V1",
                    "EXACT_DISPATCH_DEDUPE_V1",
                    "DURABLE_PROVIDER_TASK_ID_V1",
                    "TERMINATION_ATOMIC_CAPABILITY_V1",
                ],
            },
            "controllers": controllers,
            "controllerInventoryDigest": "",
        }

    def _controller(self, kind, controller_id, state):
        sources = {
            "process": "proc-cwd-scan",
            "supervisor": "local-target-no-supervisor",
            "manual_launcher": "target-pidfile-scan",
            "ci": "local-target-no-ci",
            "timer": "local-target-no-timer",
            "docker": "compose-label-scan",
        }
        return {
            "kind": kind,
            "id": controller_id,
            "state": state,
            "restartPolicy": "NONE",
            "ownershipRunId": "arch001-act-run-20260801",
            "source": sources[kind],
            "artifactCommit": "fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00",
            "cwd": str(self.root),
        }

    def _snapshot(self):
        return {
            "evidenceSource": "LIVE_LOCAL_INSPECTION",
            "inventoryComplete": True,
            "portProbeOnly": False,
            "unknownControllerCount": 0,
            "controllerChecks": [
                {
                    "kind": controller["kind"],
                    "id": controller["id"],
                    "observedState": controller["state"],
                    "source": controller["source"],
                    "stateVerified": True,
                }
                for controller in self.manifest["controllers"]
            ],
            "listeningPorts": [],
            "processes": [],
            "dockerResources": [],
            "currentDockerProject": "foggy-navigator",
            "sharedDatabases": ["navigator", "foggy_navigator"],
        }

    def _sealed_manifest(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["schema"] = "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2"
        manifest["lifecyclePhase"] = "SEALED_STOPPED"
        manifest["exactTuple"].update(
            {
                "userId": "11111111-1111-4111-8111-111111111111",
                "physicalWorkerId": "a1b2c3d4",
                "modelConfigId": "22222222-2222-4222-8222-222222222222",
            }
        )
        self.bootstrap_profile.unlink()
        self.runtime_credential_profile.write_text(
            "NAVI_RUNTIME_API_KEY=runtime-value\n", encoding="utf-8"
        )
        os.chmod(
            self.runtime_credential_profile, stat.S_IRUSR | stat.S_IWUSR
        )
        worker = self.worker_profile.read_text(encoding="utf-8").replace(
            "CODEX_NAVIGATOR_WORKER_ID=__GENERATED_WORKER_ID__",
            "CODEX_NAVIGATOR_WORKER_ID=a1b2c3d4",
        )
        self.worker_profile.write_text(worker, encoding="utf-8")
        os.chmod(self.worker_profile, stat.S_IRUSR | stat.S_IWUSR)

        def write_result(name, value):
            value["resultDigest"] = self._digest_without(value, "resultDigest")
            path = self.root / name
            path.write_text(json.dumps(value), encoding="utf-8")
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
            return value

        schema_result = write_result(
            "schema-result.json",
            {
                "schema": "NAVIGATOR_ARCH001_FRESH_SCHEMA_RESULT_V1",
                "runId": manifest["runId"],
                "targetId": manifest["targetId"],
                "planDigest": json.loads(
                    (self.root / "schema-plan.json").read_text(encoding="utf-8")
                )["planDigest"],
                "applyCount": 2,
                "hibernateValidated": True,
            },
        )
        provisioning_result = write_result(
            "provisioning-result.json",
            {
                "schema": "NAVIGATOR_ARCH001_PROVISIONING_RESULT_V1",
                "runId": manifest["runId"],
                "targetId": manifest["targetId"],
                "productionApiOnly": True,
            },
        )
        readiness_result = write_result(
            "worker-readiness-result.json",
            {
                "schema": "NAVIGATOR_ARCH001_WORKER_READINESS_SEAL_V1",
                "runId": manifest["runId"],
                "targetId": manifest["targetId"],
                "authenticatedInventory": True,
            },
        )
        profile_paths = {
            role: str(Path(manifest["target"][key]).resolve())
            for role, key in (
                ("provider", "providerProfile"),
                ("runtime", "runtimeCredentialProfile"),
                ("worker", "workerProfile"),
                ("navigator", "navigatorRuntimeProfile"),
                ("database", "databaseProfile"),
                ("control", "controlProfile"),
            )
        }
        seal = {
            "schema": "NAVIGATOR_ARCH001_TARGET_SEAL_V1",
            "runId": manifest["runId"],
            "targetId": manifest["targetId"],
            "candidate": manifest["candidate"],
            "exactTuple": manifest["exactTuple"],
            "schemaPlanDigest": schema_result["planDigest"],
            "schemaResultDigest": schema_result["resultDigest"],
            "provisioningResultDigest": provisioning_result["resultDigest"],
            "workerReadinessDigest": readiness_result["resultDigest"],
            "profilePathDigests": {
                role: hashlib.sha256(
                    f"{role}\0{path}".encode("utf-8")
                ).hexdigest()
                for role, path in profile_paths.items()
            },
            "serverGeneratedIds": True,
            "productionApiOnly": True,
            "bootstrapMaterialPurged": True,
            "activationMutationCount": 0,
            "providerEffectCount": 0,
            "modelSubmissionCount": 0,
        }
        seal["sealDigest"] = self._digest_without(seal, "sealDigest")
        seal_path = self.root / "provisioning-seal.json"
        seal_path.write_text(json.dumps(seal), encoding="utf-8")
        os.chmod(seal_path, stat.S_IRUSR | stat.S_IWUSR)
        manifest["seal"] = {
            "path": str(seal_path),
            "sealDigest": seal["sealDigest"],
            "schemaPlanDigest": seal["schemaPlanDigest"],
            "schemaResultDigest": seal["schemaResultDigest"],
            "provisioningResultDigest": seal["provisioningResultDigest"],
            "workerReadinessDigest": seal["workerReadinessDigest"],
        }
        return manifest

    @staticmethod
    def _digest_without(value, field):
        normalized = dict(value)
        normalized.pop(field, None)
        canonical = json.dumps(
            normalized, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False,
        )
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    def _owned_docker_resources(self):
        project = self.manifest["target"]["dockerProject"]
        run_id = self.manifest["runId"]
        return [
            {
                "kind": "container",
                "name": "arch001-mysql",
                "image": "mysql:8.0.44",
                "project": project,
                "runId": run_id,
                "restartPolicy": "no",
                "running": True,
            },
            {
                "kind": "network",
                "name": f"{project}_activation_network",
                "project": project,
                "runId": run_id,
            },
            {
                "kind": "volume",
                "name": f"{project}_activation_mysql_data",
                "project": project,
                "runId": run_id,
            },
        ]

    def test_valid_target_is_read_only_and_reports_contract_names_only(self):
        before = sorted(str(path) for path in self.root.rglob("*"))
        result = doctor(copy.deepcopy(self.manifest), self._snapshot())
        after = sorted(str(path) for path in self.root.rglob("*"))

        self.assertTrue(result["ready"])
        self.assertEqual(before, after)
        self.assertNotIn("profileValues", json.dumps(result))
        self.assertIn("OPENAI_API_KEY", result["providerProfileVariables"])

    def test_rejects_every_protected_port(self):
        for port in (8112, 3031, 3051, 3053, 3061, 3151, 3161):
            with self.subTest(port=port):
                candidate = copy.deepcopy(self.manifest)
                candidate["target"]["workerPort"] = port
                with self.assertRaisesRegex(
                    ActivationTargetError, "ACTIVATION_TARGET_PROTECTED_PORT"
                ):
                    doctor(candidate, self._snapshot())

    def test_rejects_shared_or_unowned_target_boundaries(self):
        cases = []
        shared_db = copy.deepcopy(self.manifest)
        shared_db["target"]["database"] = "navigator"
        cases.append((shared_db, "ACTIVATION_TARGET_SHARED_DATABASE"))
        non_loopback = copy.deepcopy(self.manifest)
        non_loopback["target"]["host"] = "0.0.0.0"
        cases.append((non_loopback, "ACTIVATION_TARGET_NON_LOOPBACK"))
        current_project = copy.deepcopy(self.manifest)
        current_project["target"]["dockerProject"] = "foggy-navigator"
        cases.append((current_project, "ACTIVATION_TARGET_SHARED_DOCKER_PROJECT"))
        protected_home = copy.deepcopy(self.manifest)
        protected_home["target"]["workerHome"] = "/home/navigator/.codex-worker"
        cases.append((protected_home, "ACTIVATION_TARGET_PROTECTED_HOME"))
        unproven_cwd = copy.deepcopy(self.manifest)
        unproven_cwd["controllers"][0]["cwd"] = "/tmp/unowned"
        unproven_cwd["controllerInventoryDigest"] = canonical_controller_digest(
            unproven_cwd["controllers"]
        )
        cases.append((unproven_cwd, "ACTIVATION_TARGET_CWD_UNPROVEN"))
        caller_defined = copy.deepcopy(self.manifest)
        caller_defined["controllers"][0]["source"] = "caller-asserted"
        caller_defined["controllerInventoryDigest"] = (
            canonical_controller_digest(caller_defined["controllers"])
        )
        cases.append(
            (caller_defined, "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN")
        )

        for candidate, reason in cases:
            with self.subTest(reason=reason):
                with self.assertRaisesRegex(ActivationTargetError, reason):
                    doctor(candidate, self._snapshot())

    def test_unknown_controller_or_late_relaunch_is_fail_closed(self):
        unknown = copy.deepcopy(self.manifest)
        unknown["controllers"][0]["state"] = "UNKNOWN"
        unknown["controllerInventoryDigest"] = canonical_controller_digest(
            unknown["controllers"]
        )
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        ):
            doctor(unknown, self._snapshot())

        snapshot = self._snapshot()
        snapshot["processes"] = [{
            "pid": 40001,
            "cwd": str(self.root),
            "runId": self.manifest["runId"],
            "controllerId": "navigator",
            "role": "navigator",
        }]
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CONTROLLER_LATE_RELAUNCH"
        ):
            doctor(copy.deepcopy(self.manifest), snapshot)

    def test_port_zero_alone_or_incomplete_inventory_is_never_authority(self):
        incomplete = self._snapshot()
        incomplete["inventoryComplete"] = False
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        ):
            doctor(copy.deepcopy(self.manifest), incomplete)

        port_only = self._snapshot()
        port_only["portProbeOnly"] = True
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CONTROLLER_INVENTORY_UNPROVEN"
        ):
            doctor(copy.deepcopy(self.manifest), port_only)

    def test_running_observation_requires_exact_owned_processes_and_mysql(self):
        sealed = self._sealed_manifest()
        running = self._snapshot()
        running["listeningPorts"] = [18112, 13051, 13306]
        running["processes"] = [
            {
                "pid": 41001,
                "cwd": str(self.root),
                "runId": self.manifest["runId"],
                "role": "navigator",
            },
            {
                "pid": 41002,
                "cwd": str(self.root / "worker-home"),
                "runId": self.manifest["runId"],
                "role": "worker",
            },
        ]
        running["dockerResources"] = self._owned_docker_resources()

        result = doctor(
            copy.deepcopy(sealed), running, phase="running"
        )
        self.assertTrue(result["ready"])
        self.assertEqual(result["phase"], "running")

        missing_worker = copy.deepcopy(running)
        missing_worker["processes"] = missing_worker["processes"][:1]
        with self.assertRaisesRegex(
            ActivationTargetError,
            "ACTIVATION_TARGET_PROCESS_OWNERSHIP_UNPROVEN",
        ):
            doctor(
                copy.deepcopy(sealed),
                missing_worker,
                phase="running",
            )

        restart_enabled = copy.deepcopy(running)
        restart_enabled["dockerResources"][0]["restartPolicy"] = "always"
        with self.assertRaisesRegex(
            ActivationTargetError,
            "ACTIVATION_CONTROLLER_RESTART_POLICY_ENABLED",
        ):
            doctor(
                copy.deepcopy(sealed),
                restart_enabled,
                phase="running",
            )

    def test_sealed_preflight_accepts_only_exact_stopped_mysql_resources(self):
        sealed = self._sealed_manifest()
        stopped = self._snapshot()
        stopped["dockerResources"] = self._owned_docker_resources()
        stopped["dockerResources"][0]["running"] = False

        result = doctor(copy.deepcopy(sealed), stopped, phase="preflight")
        self.assertTrue(result["ready"])

        relaunched = copy.deepcopy(stopped)
        relaunched["dockerResources"][0]["running"] = True
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CONTROLLER_LATE_RELAUNCH"
        ):
            doctor(copy.deepcopy(sealed), relaunched, phase="preflight")

    def test_unowned_docker_resource_and_unknown_profile_variable_are_rejected(self):
        snapshot = self._snapshot()
        snapshot["dockerResources"] = [{
            "kind": "container",
            "name": "wrong-owner",
            "image": "mysql:8.0.44",
            "project": self.manifest["target"]["dockerProject"],
            "runId": "someone-else",
            "restartPolicy": "no",
        }]
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_TARGET_DOCKER_OWNERSHIP_UNPROVEN"
        ):
            doctor(copy.deepcopy(self.manifest), snapshot)

        self.provider_profile.write_text(
            self.provider_profile.read_text(encoding="utf-8")
            + "UNREVIEWED_VARIABLE=\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_PROFILE_VARIABLE_NOT_ALLOWLISTED"
        ):
            doctor(copy.deepcopy(self.manifest), self._snapshot())

    def test_sealed_result_or_artifact_drift_is_rejected(self):
        sealed = self._sealed_manifest()
        schema_result_path = self.root / "schema-result.json"
        schema_result = json.loads(
            schema_result_path.read_text(encoding="utf-8")
        )
        schema_result["hibernateValidated"] = False
        schema_result["resultDigest"] = self._digest_without(
            schema_result, "resultDigest"
        )
        schema_result_path.write_text(
            json.dumps(schema_result), encoding="utf-8"
        )
        os.chmod(schema_result_path, stat.S_IRUSR | stat.S_IWUSR)
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_TARGET_SEAL_INPUT_INVALID"
        ):
            doctor(copy.deepcopy(sealed), self._snapshot())

    def test_cleanup_is_dry_run_and_requires_exact_digest_and_ownership(self):
        plan = cleanup_plan(
            copy.deepcopy(self.manifest), self._snapshot(), confirmation=None
        )
        self.assertFalse(plan["execute"])
        self.assertEqual(plan["runId"], self.manifest["runId"])

        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CLEANUP_CONFIRMATION_REQUIRED"
        ):
            cleanup_plan(
                copy.deepcopy(self.manifest), self._snapshot(), confirmation="wrong"
            )

        authorized = cleanup_plan(
            copy.deepcopy(self.manifest),
            self._snapshot(),
            confirmation=plan["manifestDigest"],
        )
        self.assertFalse(authorized["execute"])
        self.assertTrue(authorized["executionAuthorized"])

    def test_cleanup_executes_only_revalidated_owned_resources(self):
        snapshot = self._snapshot()
        snapshot["processes"] = [
            {
                "pid": 42001,
                "cwd": str(self.root),
                "runId": self.manifest["runId"],
                "role": "navigator",
            },
            {
                "pid": 42002,
                "cwd": str(self.root),
                "runId": self.manifest["runId"],
                "role": "worker",
            },
        ]
        snapshot["dockerResources"] = self._owned_docker_resources()
        plan = cleanup_plan(
            copy.deepcopy(self.manifest), snapshot, confirmation=None
        )
        signalled = []
        commands = []

        class Completed:
            returncode = 0

        result = execute_cleanup(
            copy.deepcopy(self.manifest),
            snapshot,
            plan["manifestDigest"],
            process_signal=lambda pid, sig: signalled.append((pid, sig)),
            command_runner=lambda command, **kwargs: (
                commands.append(command) or Completed()
            ),
        )

        self.assertEqual([pid for pid, _ in signalled], [42001, 42002])
        self.assertEqual(
            commands,
            [[
                "docker",
                "compose",
                "--project-name",
                self.manifest["target"]["dockerProject"],
                "--file",
                self.manifest["target"]["composeFile"],
                "down",
                "--volumes",
                "--remove-orphans",
            ]],
        )
        self.assertTrue(result["targetRootPreserved"])
        self.assertEqual(result["writesPerformed"], 3)

    def test_watch_renews_live_observation_and_records_loss(self):
        sealed = self._sealed_manifest()
        running = self._snapshot()
        running["listeningPorts"] = [18112, 13051, 13306]
        running["processes"] = [
            {
                "pid": 43001,
                "cwd": str(self.root),
                "runId": self.manifest["runId"],
                "role": "navigator",
            },
            {
                "pid": 43002,
                "cwd": str(self.root / "worker-home"),
                "runId": self.manifest["runId"],
                "role": "worker",
            },
        ]
        running["dockerResources"] = self._owned_docker_resources()
        output = Path(self.manifest["target"]["observationFile"])
        sleeps = []

        result = watch_observations(
            copy.deepcopy(sealed),
            output,
            1,
            max_observations=2,
            snapshot_provider=lambda _manifest: copy.deepcopy(running),
            sleeper=lambda seconds: sleeps.append(seconds),
        )

        self.assertEqual(result["observationsWritten"], 2)
        self.assertEqual(sleeps, [1])
        observation = json.loads(output.read_text(encoding="utf-8"))
        self.assertTrue(observation["allKnownControllersDisabled"])
        self.assertEqual(
            stat.S_IMODE(output.stat().st_mode),
            stat.S_IRUSR | stat.S_IWUSR,
        )

        late = copy.deepcopy(running)
        late["processes"].append(
            {
                "pid": 43003,
                "cwd": str(self.root),
                "runId": self.manifest["runId"],
                "role": "unknown",
            }
        )
        with self.assertRaises(ActivationTargetError):
            watch_observations(
                copy.deepcopy(sealed),
                output,
                1,
                max_observations=1,
                snapshot_provider=lambda _manifest: late,
                sleeper=lambda _seconds: None,
            )
        loss = json.loads(output.read_text(encoding="utf-8"))
        self.assertFalse(loss["allKnownControllersDisabled"])
        self.assertTrue(loss["lateRelaunchDetected"])
        self.assertEqual(loss["unknownControllerCount"], 1)

    def test_runtime_descendants_are_not_relaunch_controllers(self):
        parents = {
            43010: 43002,
            43011: 43010,
            43012: 1,
            43013: 43014,
            43014: 43013,
        }

        self.assertTrue(
            is_owned_runtime_descendant(43010, {43001, 43002}, parents)
        )
        self.assertTrue(
            is_owned_runtime_descendant(43011, {43001, 43002}, parents)
        )
        self.assertFalse(
            is_owned_runtime_descendant(43012, {43001, 43002}, parents)
        )
        self.assertFalse(
            is_owned_runtime_descendant(43013, {43001, 43002}, parents)
        )

    def test_live_scan_excludes_owned_worker_children_but_keeps_orphans(self):
        worker = subprocess.Popen(
            [
                sys.executable,
                "-c",
                "import subprocess,time; "
                "subprocess.Popen(['sleep','30']); time.sleep(30)",
            ],
            cwd=self.root,
            start_new_session=True,
        )
        rogue = None
        try:
            worker_pid = Path(
                self.manifest["target"]["workerPidFile"]
            )
            worker_pid.write_text(str(worker.pid), encoding="utf-8")
            os.chmod(worker_pid, stat.S_IRUSR | stat.S_IWUSR)
            deadline = time.monotonic() + 2
            child_observed = False
            while time.monotonic() < deadline and not child_observed:
                for status in Path("/proc").glob("[0-9]*/status"):
                    try:
                        child_observed = any(
                            line == f"PPid:\t{worker.pid}"
                            for line in status.read_text(
                                encoding="utf-8", errors="replace"
                            ).splitlines()
                        )
                    except OSError:
                        continue
                    if child_observed:
                        break
                if not child_observed:
                    time.sleep(0.02)
            self.assertTrue(child_observed)

            owned = live_environment_snapshot(self.manifest)
            self.assertEqual(owned["unknownControllerCount"], 0)
            self.assertEqual(
                [(item["pid"], item["role"]) for item in owned["processes"]],
                [(worker.pid, "worker")],
            )

            rogue = subprocess.Popen(
                ["sleep", "30"], cwd=self.root, start_new_session=True
            )
            drifted = live_environment_snapshot(self.manifest)
            self.assertEqual(drifted["unknownControllerCount"], 1)
            self.assertTrue(any(
                item["pid"] == rogue.pid and item["role"] == "unknown"
                for item in drifted["processes"]
            ))
        finally:
            for process in (rogue, worker):
                if process is not None and process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()
