"""Tests for SkillRegistry manifest loading."""

import pytest
from pathlib import Path

from langgraph_biz_worker.runtime.skill_registry import SkillRegistry


@pytest.fixture
def registry(tmp_path) -> SkillRegistry:
    """Registry pointed at the real manifests/ directory (legacy mode only)."""
    manifests_dir = Path(__file__).resolve().parent.parent / "src" / "langgraph_biz_worker" / "manifests"
    reg = SkillRegistry(skills_root=tmp_path / "no_skills", manifests_dir=manifests_dir)
    reg.load()
    return reg


class TestSkillRegistry:
    def test_loads_yaml_manifests(self, registry: SkillRegistry):
        skills = registry.list_skills()
        assert len(skills) >= 1

    def test_get_existing_manifest(self, registry: SkillRegistry):
        manifest = registry.get_manifest("exception_triage")
        assert manifest is not None
        assert manifest.id == "exception_triage"
        assert manifest.name == "异常分诊"
        assert "classification" in manifest.output_schema.get("required", [])

    def test_get_nonexistent_manifest(self, registry: SkillRegistry):
        assert registry.get_manifest("does_not_exist") is None

    def test_manifest_fields(self, registry: SkillRegistry):
        manifest = registry.get_manifest("exception_triage")
        assert "mock_get_order" in manifest.allowed_tools
        assert "result_summary" in manifest.promote_to_parent
        assert manifest.business_rules.get("require_evidence") is True

    def test_programmatic_register(self):
        from langgraph_biz_worker.models import SkillManifest

        reg = SkillRegistry()
        reg.register(SkillManifest(id="dynamic", name="Dynamic Skill"))
        assert reg.get_manifest("dynamic") is not None
