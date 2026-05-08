"""Tests for SkillRegistry v2 — SKILL.md loading and priority resolution."""

from __future__ import annotations

from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.skill_registry import SkillRegistry


def _write_skill_md(base: Path, scope: str, skill_name: str, content: str) -> Path:
    """Helper to create a SKILL.md in the right directory."""
    skill_dir = base / "skills" / scope / skill_name
    skill_dir.mkdir(parents=True, exist_ok=True)
    path = skill_dir / "SKILL.md"
    path.write_text(content, encoding="utf-8")
    return path


MINIMAL_SKILL_MD = """\
---
name: test_skill
description: A test skill
metadata:
  output-schema:
    type: object
    required: [result]
    properties:
      result:
        type: string
  subgraph: test_skill
allowed-tools: tool_a tool_b
---

# Test Skill

## Instructions
Just call submit_skill_result.
"""


class TestSkillMdLoading:
    def test_load_from_builtin(self, tmp_path):
        _write_skill_md(tmp_path, "builtin", "test-skill", MINIMAL_SKILL_MD)
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()

        m = registry.get_manifest("test_skill")
        assert m is not None
        assert m.id == "test_skill"
        assert m.description == "A test skill"
        assert m.allowed_tools == ["tool_a", "tool_b"]
        assert m.output_schema["required"] == ["result"]
        assert m.subgraph == "test_skill"

    def test_load_from_public(self, tmp_path):
        _write_skill_md(tmp_path, "public", "pub-skill", MINIMAL_SKILL_MD.replace("test_skill", "pub_skill"))
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()

        assert registry.get_manifest("pub_skill") is not None

    def test_public_overrides_builtin(self, tmp_path):
        """Public skills (from GitLab) take priority over builtin (shipped with Worker)."""
        public_md = MINIMAL_SKILL_MD.replace("A test skill", "Public version")
        builtin_md = MINIMAL_SKILL_MD.replace("A test skill", "Builtin version")

        _write_skill_md(tmp_path, "public", "test-skill", public_md)
        _write_skill_md(tmp_path, "builtin", "test-skill", builtin_md)

        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()

        m = registry.get_manifest("test_skill")
        assert m is not None
        assert m.description == "Public version"

    def test_builtin_overrides_legacy(self, tmp_path):
        """SKILL.md in builtin/ should override legacy YAML manifest."""
        # Create legacy yaml
        legacy_dir = tmp_path / "manifests"
        legacy_dir.mkdir()
        (legacy_dir / "test_skill.yaml").write_text(
            "id: test_skill\nname: legacy\ndescription: Legacy version\n",
            encoding="utf-8",
        )

        # Create builtin SKILL.md
        _write_skill_md(tmp_path, "builtin", "test-skill", MINIMAL_SKILL_MD)

        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=legacy_dir)
        registry.load()

        m = registry.get_manifest("test_skill")
        assert m is not None
        assert m.description == "A test skill"  # builtin wins

    def test_empty_skills_dir_no_error(self, tmp_path):
        registry = SkillRegistry(skills_root=tmp_path / "nonexistent", manifests_dir=tmp_path / "also_nonexistent")
        registry.load()
        assert registry.list_skills() == []

    def test_missing_name_skipped(self, tmp_path):
        bad_md = "---\ndescription: no name field\n---\n# No name\n"
        _write_skill_md(tmp_path, "builtin", "bad-skill", bad_md)
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()
        assert registry.list_skills() == []

    def test_no_frontmatter_skipped(self, tmp_path):
        _write_skill_md(tmp_path, "builtin", "no-fm", "# Just markdown\nNo frontmatter here.")
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()
        assert registry.list_skills() == []

    def test_allowed_tools_as_list(self, tmp_path):
        md = MINIMAL_SKILL_MD.replace(
            "allowed-tools: tool_a tool_b",
            "allowed-tools:\n  - tool_x\n  - tool_y",
        )
        _write_skill_md(tmp_path, "builtin", "list-tools", md)
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()

        m = registry.get_manifest("test_skill")
        assert m is not None
        assert m.allowed_tools == ["tool_x", "tool_y"]

    def test_markdown_body_and_metadata_visibility(self, tmp_path):
        md = """---
name: tms_skill
description: TMS public skill
metadata:
  display_name: TMS Skill
  visibility: public
---

Use this skill for TMS operations.
"""
        _write_skill_md(tmp_path, "public", "tms-skill", md)
        registry = SkillRegistry(skills_root=tmp_path / "skills", manifests_dir=tmp_path / "empty")
        registry.load()

        m = registry.get_manifest("tms_skill")

        assert m is not None
        assert m.name == "TMS Skill"
        assert m.visibility == "public"
        assert m.markdown_body == "Use this skill for TMS operations."


class TestDefaultSkillsLoad:
    """Test that the actual skills/builtin/ directory loads correctly."""

    def test_real_builtin_skills_load(self):
        real_skills_root = Path(__file__).resolve().parent.parent / "skills"
        if not real_skills_root.is_dir():
            pytest.skip("skills/ directory not found at project root")

        registry = SkillRegistry(skills_root=real_skills_root)
        registry.load()

        skills = registry.list_skills()
        # Should load at least the 3 builtin skills (may also load legacy)
        skill_ids = {s.id for s in skills}
        assert "exception_triage" in skill_ids
        assert "order_evidence_collect" in skill_ids
        assert "rule_check" in skill_ids
