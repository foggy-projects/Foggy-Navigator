#!/usr/bin/env python3
"""Offline layout tests for INT-001's directory-only facade."""

from __future__ import annotations

import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path


FIXTURE_DIR = Path(__file__).resolve().parent
if str(FIXTURE_DIR) not in sys.path:
    sys.path.insert(0, str(FIXTURE_DIR))

import directory_facade as facade  # noqa: E402


class DirectoryFacadeConfigTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.original_artifact_root = facade.ARTIFACT_ROOT
        self.artifact_root = Path(self.temp_dir.name) / "temp" / "test-artifacts" / "INT-001"
        self.artifact_root.mkdir(parents=True, mode=0o700)
        os.chmod(self.artifact_root, 0o700)
        facade.ARTIFACT_ROOT = self.artifact_root

        self.run_id = f"int001-facade-{os.urandom(6).hex()}"
        self.run_dir = self.artifact_root / self.run_id
        self.run_dir.mkdir(mode=0o700)
        os.chmod(self.run_dir, 0o700)
        self.private_dir = self.run_dir / facade.PRIVATE_DIR_NAME
        self.private_dir.mkdir(mode=0o700)
        os.chmod(self.private_dir, 0o700)
        self.workspace_root = self.run_dir / "directory-workspaces"

    def tearDown(self) -> None:
        facade.ARTIFACT_ROOT = self.original_artifact_root
        self.temp_dir.cleanup()

    def test_accepts_only_the_private_run_carrier_and_creates_workspace_root(self) -> None:
        self._write_config()

        config = facade.load_config(self._config_path())

        self.assertEqual(self.run_id, config.run_id)
        self.assertEqual(self.workspace_root, config.root)
        self.assertTrue(self.workspace_root.is_dir())
        self.assertEqual(0o700, stat.S_IMODE(self.workspace_root.stat().st_mode))

    def test_rejects_legacy_root_carrier_without_opening_it(self) -> None:
        self._write_config()
        legacy = self.run_dir / facade.CONFIG_FILE_NAME
        legacy.write_text("INT001_TEST_ONLY_ROOT_CARRIER=not-a-real-secret\n", encoding="utf-8")
        os.chmod(legacy, 0o600)

        with self.assertRaises(ValueError):
            facade.load_config(self._config_path())

    def test_rejects_a_workspace_root_outside_the_fixed_run_layout(self) -> None:
        self._write_config(overrides={"INT001_DIRECTORY_FACADE_ROOT": str(self.private_dir / "directory-workspaces")})

        with self.assertRaises(ValueError):
            facade.load_config(self._config_path())

    def test_rejects_a_nonprivate_private_directory(self) -> None:
        self._write_config()
        os.chmod(self.private_dir, 0o755)

        with self.assertRaises(ValueError):
            facade.load_config(self._config_path())

    def _write_config(self, *, overrides: dict[str, str] | None = None) -> None:
        values = {
            "INT001_RUN_ID": self.run_id,
            "INT001_DIRECTORY_FACADE_HOST": "127.0.0.1",
            "INT001_DIRECTORY_FACADE_PORT": "19003",
            "INT001_DIRECTORY_FACADE_ROOT": str(self.workspace_root),
            "INT001_DIRECTORY_FACADE_TOKEN": "int001-facade-test-token-not-real",
        }
        if overrides:
            values.update(overrides)
        self._config_path().write_text(
            "".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8"
        )
        os.chmod(self._config_path(), 0o600)

    def _config_path(self) -> Path:
        return self.private_dir / facade.CONFIG_FILE_NAME


if __name__ == "__main__":
    unittest.main(verbosity=2)
