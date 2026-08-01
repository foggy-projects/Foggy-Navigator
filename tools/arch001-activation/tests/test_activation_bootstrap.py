import copy
import hashlib
import json
import os
import stat
import tempfile
import unittest
from pathlib import Path

from activation_bootstrap import (
    ActivationTargetError,
    PROVISIONING_MANIFEST_SCHEMA,
    PROVISIONING_RESULT_SCHEMA,
    SCHEMA_PLAN_SCHEMA,
    SCHEMA_RESULT_SCHEMA,
    WORKER_READINESS_SCHEMA,
    apply_schema_plan,
    provision_runtime,
    purge_credentials,
    recover_provisioning_progress,
    schema_plan_digest,
    seal_target,
    validate_schema_runtime,
    verify_schema_plan,
    verify_worker_and_resources,
)
from activation_target import manifest_digest


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def digest_without(value, field):
    normalized = dict(value)
    normalized.pop(field, None)
    canonical = json.dumps(
        normalized, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


class Completed:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeProvisioningClient:
    def __init__(self):
        self.calls = []

    def request(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, copy.deepcopy(body), sorted(kwargs)))
        if path == "/api/v1/auth/register":
            return {"code": 200, "data": "11111111-1111-4111-8111-111111111111"}
        if path == "/api/v1/auth/login":
            return {
                "code": 200,
                "data": {
                    "token": "ephemeral-bearer",
                    "user": {
                        "id": "11111111-1111-4111-8111-111111111111",
                        "tenantId": "synthetic-arch001-tenant",
                    },
                },
            }
        if path == "/api/v1/claude-workers":
            return {"code": 200, "data": {"workerId": "a1b2c3d4"}}
        if path == "/api/v1/config/platform/llm":
            return {"code": 200, "data": "22222222-2222-4222-8222-222222222222"}
        if path.endswith("/api-keys"):
            return {"code": 200, "data": {"apiKey": "runtime-key-value"}}
        raise AssertionError(path)


class FakeRecoveryClient:
    def __init__(self):
        self.calls = []

    def request(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, copy.deepcopy(body), sorted(kwargs)))
        if path == "/api/v1/auth/login":
            return {
                "code": 200,
                "data": {
                    "token": "ephemeral-bearer",
                    "user": {
                        "id": "11111111-1111-4111-8111-111111111111",
                        "tenantId": "synthetic-arch001-tenant",
                    },
                },
            }
        if path == "/api/v1/claude-workers":
            return {"code": 200, "data": [{
                "workerId": "a1b2c3d4",
                "name": "arch001-activation-worker",
                "baseUrl": "http://127.0.0.1:13051",
                "codexBaseUrl": "http://127.0.0.1:13051",
                "codexModel": "gpt-5.6-sol",
                "codexAuthTokenConfigured": True,
            }]}
        raise AssertionError(path)


class FakeResumeClient(FakeProvisioningClient):
    def request(self, method, path, body=None, **kwargs):
        if path == "/api/v1/claude-workers/a1b2c3d4":
            self.calls.append((method, path, copy.deepcopy(body), sorted(kwargs)))
            return {"code": 200, "data": {
                "workerId": "a1b2c3d4",
                "name": "arch001-activation-worker",
                "baseUrl": "http://127.0.0.1:13051",
                "codexBaseUrl": "http://127.0.0.1:13051",
                "codexModel": "gpt-5.6-sol",
                "codexAuthTokenConfigured": True,
            }}
        return super().request(method, path, body, **kwargs)


class FakeNavigatorReadinessClient:
    def request(self, method, path, body=None, **kwargs):
        if path == "/api/v1/claude-workers/a1b2c3d4":
            return {
                "code": 200,
                "data": {
                    "workerId": "a1b2c3d4",
                    "baseUrl": "http://127.0.0.1:13051",
                    "codexBaseUrl": "http://127.0.0.1:13051",
                    "codexModel": "gpt-5.6-sol",
                    "codexAuthTokenConfigured": True,
                },
            }
        if path == "/api/v1/config/platform/llm/22222222-2222-4222-8222-222222222222":
            return {
                "code": 200,
                "data": {
                    "id": "22222222-2222-4222-8222-222222222222",
                    "tenantId": "synthetic-arch001-tenant",
                    "modelName": "gpt-5.6-sol",
                    "scope": "RESTRICTED",
                    "allowedWorkerIds": ["a1b2c3d4"],
                    "workerBackend": "OPENAI_CODEX",
                    "hasApiKey": True,
                },
            }
        raise AssertionError(path)


class FakeWorkerReadinessClient:
    def request(self, method, path, body=None, **kwargs):
        if path == "/health":
            return {
                "ready": True,
                "version": "1.0.30",
                "active_tasks": 0,
                "lifecycle_contract": {
                    "ready": True,
                    "version": 1,
                    "physical_worker_id": "a1b2c3d4",
                    "state_generation": "generation-1",
                    "instance_epoch": "instance-1",
                    "capabilities": [
                        "AUTHENTICATED_LIFECYCLE_V1",
                        "FENCED_INVENTORY_V1",
                    ],
                },
            }
        if path == "/api/v1/lifecycle/inventory?after_sequence=0":
            self.last_inventory_kwargs = kwargs
            return {
                "physical_worker_id": "a1b2c3d4",
                "state_generation": "generation-1",
                "instance_epoch": "instance-1",
                "coverage": "COMPLETE",
            }
        raise AssertionError(path)


class FakeHealthClient:
    def request(self, method, path, body=None, **kwargs):
        if method == "GET" and path == "/actuator/health":
            return {"status": "UP"}
        raise AssertionError(path)


class ActivationBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name) / "repo"
        self.migration = self.repo / "docs/migration"
        self.migration.mkdir(parents=True)
        self.root = Path(self.temp.name) / "arch001-act-run-closed"
        self.root.mkdir()
        self.artifact = self.root / "artifacts/launcher.jar"
        self.artifact.parent.mkdir()
        self.artifact.write_bytes(b"candidate-launcher")
        self.baseline = self.migration / "activation-current-baseline.sql"
        self.forward = self.migration / "activation-forward.sql"
        self.baseline.write_text(
            "CREATE TABLE IF NOT EXISTS sample_table (id VARCHAR(8) PRIMARY KEY);\n",
            encoding="utf-8",
        )
        self.forward.write_text(
            "ALTER TABLE sample_table ADD COLUMN marker VARCHAR(8) NULL;\n",
            encoding="utf-8",
        )
        self.plan_path = self.root / "schema-plan.json"
        self.plan = {
            "schema": SCHEMA_PLAN_SCHEMA,
            "planId": "arch001-test-plan",
            "candidateHead": "f" * 40,
            "mysqlVersion": "8.0.44",
            "files": [
                {
                    "order": 1,
                    "path": "docs/migration/activation-current-baseline.sql",
                    "sha256": sha256(self.baseline),
                    "role": "CURRENT_SCHEMA_BASELINE",
                },
                {
                    "order": 2,
                    "path": "docs/migration/activation-forward.sql",
                    "sha256": sha256(self.forward),
                    "role": "FORWARD_MIGRATION",
                },
            ],
        }
        self.plan["planDigest"] = schema_plan_digest(self.plan)
        self._write_json(self.plan_path, self.plan)
        self.manifest_path = self.root / "activation-target-manifest.json"
        self.manifest = self._manifest()
        self._write_json(self.manifest_path, self.manifest)
        self._write_profiles()

    def tearDown(self):
        self.temp.cleanup()

    def _write_json(self, path, value):
        Path(path).write_text(json.dumps(value), encoding="utf-8")
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)

    def _profile(self, name, content):
        path = self.root / name
        path.write_text(content, encoding="utf-8")
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
        return path

    def _write_profiles(self):
        self.bootstrap = self._profile(
            "bootstrap.env",
            "ARCH001_SYNTHETIC_TENANT_ID=synthetic-arch001-tenant\n"
            "ARCH001_SYNTHETIC_USERNAME=synthetic-user-random\n"
            "ARCH001_SYNTHETIC_PASSWORD=bootstrap-password-random\n"
            "ARCH001_SYNTHETIC_EMAIL=synthetic@example.invalid\n",
        )
        self.provider = self._profile(
            "provider.env", "OPENAI_API_KEY=provider-key-value\nOPENAI_BASE_URL=https://provider.invalid\n"
        )
        self.worker = self._profile(
            "worker.env",
            "CODEX_WORKER_PORT=13051\n"
            "CODEX_WORKER_HOST=127.0.0.1\n"
            "CODEX_WORKER_NAME=arch001-worker\n"
            "CODEX_WORKER_TOKEN=worker-token-value\n"
            "CODEX_WORKER_EXTERNAL_ENABLED=false\n"
            f"CODEX_ALLOWED_CWDS={self.root / 'workdir'}\n"
            f"CODEX_WORKER_CODEX_HOME={self.root / 'worker-home/codex'}\n"
            f"CODEX_BIZ_HOME_ROOT={self.root / 'worker-home/biz'}\n"
            "CODEX_NAVIGATOR_WORKER_ID=__GENERATED_WORKER_ID__\n"
            f"CODEX_TERMINATION_OPERATION_LEDGER_DIR={self.root / 'worker-home/termination'}\n"
            f"CODEX_LIFECYCLE_STORE_DIR={self.root / 'worker-home/lifecycle'}\n",
        )
        self.runtime = self._profile("runtime-credential.env", "NAVI_RUNTIME_API_KEY=\n")
        self.navigator = self._profile(
            "navigator-runtime.env",
            "SERVER_PORT=18112\nSPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1/test\n"
            "SPRING_DATASOURCE_USERNAME=test\nSPRING_DATASOURCE_PASSWORD=db-pass\n"
            "SPRING_JPA_HIBERNATE_DDL_AUTO=validate\n"
            "NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED=true\n"
            "NAVIGATOR_EXTERNAL_ENABLED=false\nNAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false\n"
            "NAVIGATOR_LIFECYCLE_SHADOW_ENABLED=false\n",
        )
        self.database = self._profile(
            "database.env",
            "MYSQL_DATABASE=arch001_act_run_closed\nMYSQL_USER=test\n"
            "MYSQL_PASSWORD=db-pass\nMYSQL_ROOT_PASSWORD=root-pass\n",
        )
        self.control = self._profile(
            "activation-control.env",
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_TOKEN=control-value\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_CONTROL_ENABLED=false\n"
            "NAVIGATOR_LIFECYCLE_ACTIVATION_ADMISSION_ENABLED=false\n",
        )

    def _manifest(self):
        target = {
            "host": "127.0.0.1",
            "navigatorPort": 18112,
            "workerPort": 13051,
            "mysqlPort": 13306,
            "mysqlVersion": "8.0.44",
            "database": "arch001_act_run_closed",
            "dockerProject": "arch001-act-run-closed",
            "root": str(self.root),
            "workdir": str(self.root / "workdir"),
            "workerHome": str(self.root / "worker-home"),
            "providerProfile": str(self.root / "provider.env"),
            "workerProfile": str(self.root / "worker.env"),
            "navigatorRuntimeProfile": str(self.root / "navigator-runtime.env"),
            "databaseProfile": str(self.root / "database.env"),
            "controlProfile": str(self.root / "activation-control.env"),
            "bootstrapProfile": str(self.root / "bootstrap.env"),
            "runtimeCredentialProfile": str(self.root / "runtime-credential.env"),
            "composeFile": str(self.root / "compose.mysql.yml"),
            "navigatorArtifact": str(self.artifact),
            "navigatorArtifactSha256": sha256(self.artifact),
            "schemaPlan": str(self.plan_path),
            "schemaResult": str(self.root / "schema-result.json"),
            "provisioningProgress": str(self.root / "provisioning-progress.json"),
            "provisioningResult": str(self.root / "provisioning-result.json"),
            "workerReadinessResult": str(self.root / "worker-readiness-result.json"),
            "provisioningSeal": str(self.root / "provisioning-seal.json"),
            "evidenceDir": str(self.root / "evidence"),
            "navigatorPidFile": str(self.root / "navigator.pid"),
            "workerPidFile": str(self.root / "worker.pid"),
            "observationFile": str(self.root / "controller-observation.json"),
        }
        return {
            "schema": PROVISIONING_MANIFEST_SCHEMA,
            "lifecyclePhase": "PROVISIONING_CLOSED",
            "targetId": "arch001-act-target-closed",
            "runId": "arch001-act-run-closed",
            "candidate": {"head": "f" * 40, "patchSha256": "a" * 64, "ownerProtocol": 1},
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
            "target": target,
            "worker": {
                "version": "1.0.30",
                "protocolVersion": 1,
                "requiredCapabilities": [
                    "AUTHENTICATED_LIFECYCLE_V1",
                    "FENCED_INVENTORY_V1",
                ],
            },
        }

    def test_schema_plan_is_strictly_ordered_and_digest_sealed(self):
        result = verify_schema_plan(self.plan_path, self.repo)
        self.assertTrue(result["ready"])
        self.assertEqual(result["fileCount"], 2)

        tampered = copy.deepcopy(self.plan)
        tampered["files"][1]["sha256"] = "0" * 64
        self._write_json(self.plan_path, tampered)
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_SCHEMA_PLAN_FILE_DIGEST_MISMATCH"
        ):
            verify_schema_plan(self.plan_path, self.repo)

        self.forward.write_text("DROP TABLE sample_table;\n", encoding="utf-8")
        tampered["files"][1]["sha256"] = sha256(self.forward)
        tampered["planDigest"] = schema_plan_digest(tampered)
        self._write_json(self.plan_path, tampered)
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_SCHEMA_PLAN_DESTRUCTIVE_SQL_FORBIDDEN"
        ):
            verify_schema_plan(self.plan_path, self.repo)

    def test_schema_apply_requires_empty_database_and_exact_reapply_confirmation(self):
        counts = iter(["0\n", "4\n", "4\n", "4\n"])
        calls = []

        def runner(command, **kwargs):
            calls.append((command, sorted(kwargs), "input" in kwargs))
            joined = " ".join(command)
            if "SELECT VERSION()" in joined:
                return Completed(stdout="8.0.44\n")
            if "information_schema.tables" in joined:
                return Completed(stdout=next(counts))
            return Completed()

        result_path = self.root / "schema-result.json"
        first = apply_schema_plan(
            self.manifest, self.plan_path, self.repo, result_path,
            command_runner=runner,
            environment_snapshot=self._schema_snapshot(),
        )
        self.assertEqual(first["applyCount"], 1)
        self.assertEqual(first["writesPerformed"], 2)
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_SCHEMA_REAPPLY_CONFIRMATION_REQUIRED"
        ):
            apply_schema_plan(
                self.manifest, self.plan_path, self.repo, result_path,
                command_runner=runner,
                environment_snapshot=self._schema_snapshot(),
            )
        second = apply_schema_plan(
            self.manifest,
            self.plan_path,
            self.repo,
            result_path,
            reapply_confirmation=self.plan["planDigest"],
            command_runner=runner,
            environment_snapshot=self._schema_snapshot(),
        )
        self.assertEqual(second["applyCount"], 2)
        self.assertTrue(any(has_input for _, _, has_input in calls))
        count_commands = [
            " ".join(command)
            for command, _, _ in calls
            if "information_schema.tables" in " ".join(command)
        ]
        self.assertTrue(count_commands)
        self.assertTrue(all("DATABASE()" in command for command in count_commands))
        self.assertTrue(all('"$MYSQL_DATABASE"' in command for command in count_commands))

        wrong_owner = self._schema_snapshot()
        wrong_owner["dockerResources"][0]["runId"] = "different-run"
        with self.assertRaisesRegex(
            ActivationTargetError,
            "ACTIVATION_SCHEMA_RESOURCE_OWNERSHIP_UNPROVEN",
        ):
            apply_schema_plan(
                self.manifest,
                self.plan_path,
                self.repo,
                result_path,
                reapply_confirmation=self.plan["planDigest"],
                command_runner=runner,
                environment_snapshot=wrong_owner,
            )

        wrong_candidate = copy.deepcopy(self.manifest)
        wrong_candidate["candidate"]["head"] = "e" * 40
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_SCHEMA_PLAN_CANDIDATE_MISMATCH"
        ):
            apply_schema_plan(
                wrong_candidate,
                self.plan_path,
                self.repo,
                result_path,
                reapply_confirmation=self.plan["planDigest"],
                command_runner=runner,
                environment_snapshot=self._schema_snapshot(),
            )

    def _schema_snapshot(self):
        project = self.manifest["target"]["dockerProject"]
        run_id = self.manifest["runId"]
        return {
            "evidenceSource": "LIVE_LOCAL_INSPECTION",
            "inventoryComplete": True,
            "portProbeOnly": False,
            "unknownControllerCount": 0,
            "listeningPorts": [self.manifest["target"]["mysqlPort"]],
            "processes": [],
            "dockerResources": [
                {
                    "kind": "container", "name": "mysql",
                    "image": "mysql:8.0.44", "project": project,
                    "runId": run_id, "restartPolicy": "no",
                },
                {
                    "kind": "network", "name": f"{project}_network",
                    "project": project, "runId": run_id,
                },
                {
                    "kind": "volume", "name": f"{project}_data",
                    "project": project, "runId": run_id,
                },
            ],
        }

    def test_schema_validate_requires_live_candidate_process_and_closed_runtime(self):
        result = {
            "schema": SCHEMA_RESULT_SCHEMA,
            "runId": self.manifest["runId"],
            "targetId": self.manifest["targetId"],
            "planDigest": self.plan["planDigest"],
            "applyCount": 2,
            "hibernateValidated": False,
        }
        result["resultDigest"] = digest_without(result, "resultDigest")
        result_path = self.root / "schema-result.json"
        self._write_json(result_path, result)
        validated = validate_schema_runtime(
            self.manifest,
            result_path,
            process_inspector=lambda _manifest, artifact: {
                "pid": 12345,
                "cwd": str(self.root),
                "artifact": str(artifact),
            },
            health_client=FakeHealthClient(),
        )
        self.assertTrue(validated["hibernateValidated"])
        self.assertEqual(
            validated["validationEvidenceSource"], "LIVE_LOCAL_INSPECTION"
        )
        self.assertNotIn("db-pass", result_path.read_text(encoding="utf-8"))

        self.artifact.write_bytes(b"drifted")
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_SCHEMA_VALIDATION_ARTIFACT_MISMATCH"
        ):
            validate_schema_runtime(
                self.manifest,
                result_path,
                process_inspector=lambda _manifest, artifact: {
                    "pid": 12345,
                    "cwd": str(self.root),
                    "artifact": str(artifact),
                },
                health_client=FakeHealthClient(),
            )

    def test_closed_provisioning_uses_server_ids_and_purges_bootstrap_profile(self):
        client = FakeProvisioningClient()
        result_path = self.root / "provisioning-result.json"
        result = provision_runtime(self.manifest, result_path, client=client)

        self.assertTrue(result["serverGeneratedIds"])
        self.assertEqual(result["apiCallCount"], 5)
        self.assertEqual(result["activationMutationCount"], 0)
        self.assertEqual(result["providerEffectCount"], 0)
        self.assertFalse(self.bootstrap.exists())
        self.assertEqual(stat.S_IMODE(self.runtime.stat().st_mode), 0o600)
        self.assertIn(
            "CODEX_NAVIGATOR_WORKER_ID=a1b2c3d4",
            self.worker.read_text(encoding="utf-8"),
        )
        serialized = result_path.read_text(encoding="utf-8")
        for secret in (
            "provider-key-value", "worker-token-value", "runtime-key-value",
            "bootstrap-password-random", "ephemeral-bearer",
        ):
            self.assertNotIn(secret, serialized)
        model_calls = [call for call in client.calls if call[1] == "/api/v1/config/platform/llm"]
        self.assertEqual(model_calls[0][2]["category"], "GENERAL")

    def test_partial_provisioning_recovers_exact_resources_without_duplicates(self):
        recovery = recover_provisioning_progress(
            self.manifest, prior_api_call_count=4, client=FakeRecoveryClient()
        )
        self.assertEqual(recovery["apiCallCount"], 6)
        resume = FakeResumeClient()
        result = provision_runtime(
            self.manifest, self.root / "provisioning-result.json", client=resume
        )
        self.assertEqual(result["apiCallCount"], 10)
        self.assertTrue(result["resumedFromProgress"])
        self.assertFalse(any(
            method == "POST" and path in {
                "/api/v1/auth/register", "/api/v1/claude-workers"
            }
            for method, path, _body, _kwargs in resume.calls
        ))

    def _provision_and_readiness(self):
        provision = provision_runtime(
            self.manifest,
            self.root / "provisioning-result.json",
            client=FakeProvisioningClient(),
        )
        readiness = verify_worker_and_resources(
            self.manifest,
            provision,
            self.root / "worker-readiness-result.json",
            navigator_client=FakeNavigatorReadinessClient(),
            worker_client=FakeWorkerReadinessClient(),
        )
        return provision, readiness

    def test_readiness_is_authenticated_and_target_seal_rejects_drift(self):
        provision, readiness = self._provision_and_readiness()
        self.assertTrue(readiness["authenticatedInventory"])
        schema_result = {
            "schema": SCHEMA_RESULT_SCHEMA,
            "runId": self.manifest["runId"],
            "targetId": self.manifest["targetId"],
            "planDigest": self.plan["planDigest"],
            "applyCount": 2,
            "hibernateValidated": True,
        }
        schema_result["resultDigest"] = digest_without(schema_result, "resultDigest")
        self._write_json(self.root / "schema-result.json", schema_result)
        confirmation = manifest_digest(self.manifest)
        result = seal_target(
            self.manifest_path,
            self.root / "schema-result.json",
            self.root / "provisioning-result.json",
            self.root / "worker-readiness-result.json",
            self.root / "provisioning-seal.json",
            confirmation,
        )
        self.assertTrue(result["ready"])
        sealed = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(sealed["schema"], "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2")
        self.assertEqual(sealed["exactTuple"]["physicalWorkerId"], "a1b2c3d4")
        self.assertNotIn("provider-key-value", json.dumps(sealed))

        drifted = copy.deepcopy(sealed)
        drifted["exactTuple"]["physicalWorkerId"] = "deadbeef"
        self._write_json(self.manifest_path, drifted)
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_TARGET_SEAL_CONFIRMATION_REQUIRED"
        ):
            seal_target(
                self.manifest_path,
                self.root / "schema-result.json",
                self.root / "provisioning-result.json",
                self.root / "worker-readiness-result.json",
                self.root / "provisioning-seal.json",
                manifest_digest(drifted),
            )

    def test_credential_purge_is_exactly_confirmed_and_preserves_evidence(self):
        self._provision_and_readiness()
        sealed = copy.deepcopy(self.manifest)
        sealed["schema"] = "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2"
        sealed["lifecyclePhase"] = "SEALED_STOPPED"
        evidence = self.root / "evidence/result.json"
        evidence.parent.mkdir()
        evidence.write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(
            ActivationTargetError, "ACTIVATION_CREDENTIAL_PURGE_CONFIRMATION_REQUIRED"
        ):
            purge_credentials(sealed, "wrong")
        result = purge_credentials(sealed, manifest_digest(sealed))
        self.assertEqual(result["purgedProfileCount"], 6)
        self.assertTrue(evidence.exists())
        self.assertFalse(self.provider.exists())
        self.assertFalse(self.runtime.exists())


if __name__ == "__main__":
    unittest.main()
