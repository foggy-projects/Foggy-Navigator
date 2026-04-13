"""Tests for manifest loading edge cases and error handling (P1)."""

import tempfile
from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.skill_registry import SkillRegistry


class TestManifestLoadErrors:
    def test_nonexistent_directory_logs_warning(self, caplog):
        """Loading from a missing directory should warn, not crash."""
        reg = SkillRegistry(manifests_dir=Path("/nonexistent/path/manifests"))
        reg.load()
        assert reg.list_skills() == []

    def test_invalid_yaml_skipped(self, tmp_path):
        """Malformed YAML files should be skipped with a logged error."""
        bad_file = tmp_path / "bad.yaml"
        bad_file.write_text("::invalid yaml [[", encoding="utf-8")

        reg = SkillRegistry(manifests_dir=tmp_path)
        reg.load()
        assert reg.list_skills() == []

    def test_missing_required_id_field(self, tmp_path):
        """YAML missing required 'id' field should be skipped."""
        bad_file = tmp_path / "no_id.yaml"
        bad_file.write_text("name: No ID Skill\ndescription: missing id", encoding="utf-8")

        reg = SkillRegistry(manifests_dir=tmp_path)
        reg.load()
        assert reg.list_skills() == []

    def test_idempotent_reload(self, tmp_path):
        """Multiple load() calls should not duplicate manifests."""
        good_file = tmp_path / "test.yaml"
        good_file.write_text("id: test_skill\nname: Test", encoding="utf-8")

        reg = SkillRegistry(manifests_dir=tmp_path)
        reg.load()
        assert len(reg.list_skills()) == 1
        reg.load()
        assert len(reg.list_skills()) == 1

    def test_empty_directory(self, tmp_path):
        """Empty manifests directory should result in no skills."""
        reg = SkillRegistry(manifests_dir=tmp_path)
        reg.load()
        assert reg.list_skills() == []

    def test_non_yaml_files_ignored(self, tmp_path):
        """Non-.yaml files should be ignored."""
        txt_file = tmp_path / "readme.txt"
        txt_file.write_text("not a manifest", encoding="utf-8")

        reg = SkillRegistry(manifests_dir=tmp_path)
        reg.load()
        assert reg.list_skills() == []
