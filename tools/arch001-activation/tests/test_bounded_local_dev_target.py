import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from activation_target import canonical_controller_digest
from bounded_local_dev_target import (
    ObservationDenied,
    manifest_owned_worker_pid,
    validate_manifest,
    write_atomic,
)


class BoundedLocalDevelopmentTargetTest(unittest.TestCase):
    def manifest(self, root: Path) -> dict:
        controllers = [
            {"kind": "process", "id": "target-process-set", "state": "DISABLED", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "proc-cwd-scan", "artifactCommit": "a" * 40, "cwd": str(root)},
            {"kind": "supervisor", "id": "none", "state": "NOT_APPLICABLE", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "local-target-no-supervisor", "artifactCommit": "a" * 40, "cwd": str(root)},
            {"kind": "manual_launcher", "id": "target-pidfiles", "state": "DISABLED", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "target-pidfile-scan", "artifactCommit": "a" * 40, "cwd": str(root)},
            {"kind": "ci", "id": "none", "state": "NOT_APPLICABLE", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "local-target-no-ci", "artifactCommit": "a" * 40, "cwd": str(root)},
            {"kind": "timer", "id": "none", "state": "NOT_APPLICABLE", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "local-target-no-timer", "artifactCommit": "a" * 40, "cwd": str(root)},
            {"kind": "docker", "id": "mysql-compose", "state": "DISABLED", "restartPolicy": "NONE", "ownershipRunId": "run-1", "source": "compose-label-scan", "artifactCommit": "a" * 40, "cwd": str(root)},
        ]
        return {
            "schema": "NAVIGATOR_ARCH001_ACTIVATION_TARGET_V2",
            "targetId": "target-1",
            "runId": "run-1",
            "targetClass": "BOUNDED_ISOLATED_LOCAL_DEVELOPMENT",
            "providerEvidenceLane": "REAL_CODEX_MODEL",
            "candidate": {"head": "a" * 40, "patchSha256": "b" * 64, "ownerProtocol": 1},
            "exactTuple": {"providerType": "codex-worker", "physicalWorkerId": "worker-1"},
            "target": {"host": "127.0.0.1", "navigatorPort": 8112, "workerPort": 3151, "mysqlPort": 13309, "mysqlVersion": "8.0.44", "root": str(root)},
            "worker": {"version": "1.0.32", "protocolVersion": 1, "requiredCapabilities": []},
            "localDevelopment": {},
            "controllers": controllers,
            "controllerInventoryDigest": canonical_controller_digest(controllers),
        }

    def test_validates_sealed_content_free_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            root.chmod(0o700)
            manifest_path = root / "manifest.json"
            manifest = self.manifest(root)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            manifest_path.chmod(0o600)

            parts = validate_manifest(manifest, manifest_path)

            self.assertEqual(parts["exact"]["providerType"], "codex-worker")

    def test_rejects_nonlocal_provider_and_unsafe_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            root.chmod(0o700)
            manifest_path = root / "manifest.json"
            manifest = self.manifest(root)
            manifest["exactTuple"]["providerType"] = "codex-biz-worker"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            manifest_path.chmod(0o600)
            with self.assertRaises(ObservationDenied):
                validate_manifest(manifest, manifest_path)
            with self.assertRaises(ObservationDenied):
                write_atomic(root.parent / "outside.json", {}, root)

    def test_selects_target_owned_worker_when_another_installation_is_running(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            root.chmod(0o700)
            pid_file = root / "worker.pid"
            pid_file.write_text("200\n", encoding="utf-8")
            pid_file.chmod(0o600)
            target = {"root": str(root), "workerPidFile": str(pid_file)}

            def wsl_output(_prefix, args, _reason):
                if args == ["pgrep", "-f", "^node dist/index.js$"]:
                    return "100\n200"
                if args == ["readlink", "-f", "/proc/100/cwd"]:
                    return "/home/navigator/.codex-worker"
                if args == ["readlink", "-f", "/proc/200/cwd"]:
                    return "/tmp/activation-run/worker"
                self.fail(f"unexpected WSL command: {args}")

            with patch("bounded_local_dev_target.wsl_command", side_effect=wsl_output):
                pid = manifest_owned_worker_pid(
                    target, ["wsl"], "/tmp/activation-run/worker"
                )

            self.assertEqual("200", pid)

    def test_rejects_duplicate_or_pid_drift_in_manifest_worker_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            root.chmod(0o700)
            pid_file = root / "worker.pid"
            pid_file.write_text("200\n", encoding="utf-8")
            pid_file.chmod(0o600)
            target = {"root": str(root), "workerPidFile": str(pid_file)}

            def duplicate_root(_prefix, args, _reason):
                if args == ["pgrep", "-f", "^node dist/index.js$"]:
                    return "200\n201"
                if args == ["readlink", "-f", "/proc/200/cwd"]:
                    return "/tmp/activation-run/worker"
                if args == ["readlink", "-f", "/proc/201/cwd"]:
                    return "/tmp/activation-run/worker"
                self.fail(f"unexpected WSL command: {args}")

            with patch("bounded_local_dev_target.wsl_command", side_effect=duplicate_root):
                with self.assertRaisesRegex(
                    ObservationDenied, "BOUNDED_LOCAL_DEV_WORKER_PROCESS_MISMATCH"
                ):
                    manifest_owned_worker_pid(
                        target, ["wsl"], "/tmp/activation-run/worker"
                    )

            def pid_drift(_prefix, args, _reason):
                if args == ["pgrep", "-f", "^node dist/index.js$"]:
                    return "201"
                self.fail(f"unexpected WSL command: {args}")

            with patch("bounded_local_dev_target.wsl_command", side_effect=pid_drift):
                with self.assertRaisesRegex(
                    ObservationDenied, "BOUNDED_LOCAL_DEV_WORKER_PROCESS_MISMATCH"
                ):
                    manifest_owned_worker_pid(
                        target, ["wsl"], "/tmp/activation-run/worker"
                    )


if __name__ == "__main__":
    unittest.main()
