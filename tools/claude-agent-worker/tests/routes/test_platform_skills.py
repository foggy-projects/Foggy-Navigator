"""Unit tests for routes/platform_skills.py — skill deployment logic."""

from __future__ import annotations

from unittest.mock import patch

import pytest

from agent_worker.routes.platform_skills import deploy_skills, DeploySkillsRequest


@pytest.mark.asyncio
class TestDeploySkills:
    """POST /api/v1/platform-skills/deploy endpoint logic."""

    async def test_deploys_single_skill(self, tmp_path):
        req = DeploySkillsRequest(skills={"test-skill": "# Test Skill\nContent here"})
        skills_dir = tmp_path / ".agents" / "skills"
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            result = await deploy_skills(req)

        assert "test-skill" in result["deployed"]
        skill_file = skills_dir / "test-skill" / "SKILL.md"
        assert skill_file.exists()
        assert skill_file.read_text(encoding="utf-8") == "# Test Skill\nContent here"

    async def test_deploys_multiple_skills(self, tmp_path):
        req = DeploySkillsRequest(skills={
            "skill-a": "# Skill A",
            "skill-b": "# Skill B",
        })
        skills_dir = tmp_path / ".agents" / "skills"
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            result = await deploy_skills(req)

        assert set(result["deployed"]) == {"skill-a", "skill-b"}

    async def test_overwrites_existing_skill(self, tmp_path):
        # Pre-create the skill
        skills_dir = tmp_path / ".agents" / "skills"
        skill_dir = skills_dir / "existing"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text("old content")

        req = DeploySkillsRequest(skills={"existing": "new content"})
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            result = await deploy_skills(req)

        assert "existing" in result["deployed"]
        assert (skill_dir / "SKILL.md").read_text() == "new content"

    async def test_overwrites_managed_ask_agent(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        skill_dir = skills_dir / "ask-agent"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text(
            "---\nname: ask-agent\n---\n# 咨询协作 Agent\nNAVIGATOR_TOKEN\n/api/v1/agents/{id}/ask\n",
            encoding="utf-8",
        )
        narrowed = (
            "---\nname: ask-agent\n---\n# 定时任务 A2A 调用\n"
            "[NAVIGATOR_SCHEDULED_A2A]\n/api/v1/agents/$TARGET_AGENT_ID/ask\n"
        )

        req = DeploySkillsRequest(skills={"ask-agent": narrowed})
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.remove_legacy_ask_agent_copies"
            ) as remove_legacy,
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == ["ask-agent"]
        assert (skill_dir / "SKILL.md").read_text(encoding="utf-8") == narrowed
        remove_legacy.assert_called_once_with()

    async def test_preserves_unrecognized_ask_agent(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        skill_dir = skills_dir / "ask-agent"
        skill_dir.mkdir(parents=True)
        custom = "---\nname: ask-agent\n---\n# User-managed routing\n"
        (skill_dir / "SKILL.md").write_text(custom, encoding="utf-8")

        req = DeploySkillsRequest(skills={"ask-agent": "platform replacement"})
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            result = await deploy_skills(req)

        assert result["deployed"] == []
        assert (skill_dir / "SKILL.md").read_text(encoding="utf-8") == custom

    async def test_skips_linked_ask_agent_target(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        req = DeploySkillsRequest(skills={"ask-agent": "platform replacement"})

        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.platform_skills.deployer._is_link_or_reparse_point",
                return_value=True,
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == []
        assert not (skills_dir / "ask-agent" / "SKILL.md").exists()

    async def test_empty_skills_returns_empty(self, tmp_path):
        req = DeploySkillsRequest(skills={})
        skills_dir = tmp_path / ".agents" / "skills"
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            result = await deploy_skills(req)

        assert result["deployed"] == []

    async def test_handles_write_failure_gracefully(self, tmp_path):
        """Failed skill deployment should not crash, others should still deploy."""
        from pathlib import Path

        req = DeploySkillsRequest(skills={
            "good-skill": "# Good",
            "bad-skill": "# Bad",
        })
        skills_dir = tmp_path / ".agents" / "skills"

        original_write_text = Path.write_text

        def failing_write(self, content, **kwargs):
            if "bad-skill" in str(self):
                raise PermissionError("denied")
            return original_write_text(self, content, **kwargs)

        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
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

        skills_dir = tmp_path / ".agents" / "skills"
        with patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir):
            with patch("agent_worker.routes.platform_skills.asyncio.to_thread", side_effect=fake_to_thread):
                result = await deploy_skills(req)

        assert result["deployed"] == ["threaded-skill"]
        assert len(to_thread_calls) == 1
        assert (skills_dir / "threaded-skill" / "SKILL.md").exists()
