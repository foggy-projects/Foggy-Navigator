"""Unit tests for routes/platform_skills.py — skill deployment logic."""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest

from agent_worker.routes.platform_skills import deploy_skills, DeploySkillsRequest


@pytest.mark.asyncio
class TestDeploySkills:
    """POST /api/v1/platform-skills/deploy endpoint logic."""

    async def test_deploys_single_skill(self, tmp_path):
        from pathlib import Path
        req = DeploySkillsRequest(skills={"test-skill": "# Test Skill\nContent here"})
        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            result = await deploy_skills(req)

        assert "test-skill" in result["deployed"]
        skill_file = tmp_path / ".claude" / "skills" / "test-skill" / "SKILL.md"
        assert skill_file.exists()
        assert skill_file.read_text(encoding="utf-8") == "# Test Skill\nContent here"

    async def test_deploys_multiple_skills(self, tmp_path):
        from pathlib import Path
        req = DeploySkillsRequest(skills={
            "skill-a": "# Skill A",
            "skill-b": "# Skill B",
        })
        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            result = await deploy_skills(req)

        assert set(result["deployed"]) == {"skill-a", "skill-b"}

    async def test_overwrites_existing_skill(self, tmp_path):
        from pathlib import Path

        # Pre-create the skill
        skill_dir = tmp_path / ".claude" / "skills" / "existing"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text("old content")

        req = DeploySkillsRequest(skills={"existing": "new content"})
        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            result = await deploy_skills(req)

        assert "existing" in result["deployed"]
        assert (skill_dir / "SKILL.md").read_text() == "new content"

    async def test_empty_skills_returns_empty(self, tmp_path):
        from pathlib import Path
        req = DeploySkillsRequest(skills={})
        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            result = await deploy_skills(req)

        assert result["deployed"] == []

    async def test_handles_write_failure_gracefully(self, tmp_path):
        """Failed skill deployment should not crash, others should still deploy."""
        from pathlib import Path

        req = DeploySkillsRequest(skills={
            "good-skill": "# Good",
            "bad-skill": "# Bad",
        })

        original_write_text = Path.write_text

        def failing_write(self, content, **kwargs):
            if "bad-skill" in str(self):
                raise PermissionError("denied")
            return original_write_text(self, content, **kwargs)

        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            with patch.object(Path, "write_text", failing_write):
                result = await deploy_skills(req)

        assert "good-skill" in result["deployed"]
        assert "bad-skill" not in result["deployed"]

    async def test_deploy_uses_to_thread(self, tmp_path):
        from pathlib import Path

        req = DeploySkillsRequest(skills={"threaded-skill": "# Threaded"})
        to_thread_calls: list[tuple] = []

        async def fake_to_thread(func, *args, **kwargs):
            to_thread_calls.append((func, args, kwargs))
            return func(*args, **kwargs)

        with patch("agent_worker.routes.platform_skills.Path", wraps=Path) as WrappedPath:
            WrappedPath.home = lambda: tmp_path
            with patch("agent_worker.routes.platform_skills.asyncio.to_thread", side_effect=fake_to_thread):
                result = await deploy_skills(req)

        assert result["deployed"] == ["threaded-skill"]
        assert len(to_thread_calls) == 1
        assert (tmp_path / ".claude" / "skills" / "threaded-skill" / "SKILL.md").exists()
