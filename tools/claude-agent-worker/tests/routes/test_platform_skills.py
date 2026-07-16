"""Unit tests for the platform skill reconcile/deployment endpoint."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest

from agent_worker.routes.platform_skills import DeploySkillsRequest, deploy_skills


@pytest.mark.asyncio
class TestDeploySkills:

    async def test_deploys_single_compatible_skill(self, tmp_path):
        req = DeploySkillsRequest(skills={"test-skill": "# Test Skill\nContent here"})
        skills_dir = tmp_path / ".agents" / "skills"
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == ["test-skill"]
        skill_file = skills_dir / "test-skill" / "SKILL.md"
        assert skill_file.read_text(encoding="utf-8") == "# Test Skill\nContent here"

    async def test_deploys_multiple_compatible_skills(self, tmp_path):
        req = DeploySkillsRequest(skills={"skill-a": "# Skill A", "skill-b": "# Skill B"})
        skills_dir = tmp_path / ".agents" / "skills"
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
        ):
            result = await deploy_skills(req)

        assert set(result["deployed"]) == {"skill-a", "skill-b"}

    async def test_overwrites_existing_compatible_skill(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        skill_dir = skills_dir / "existing"
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text("old content", encoding="utf-8")

        req = DeploySkillsRequest(skills={"existing": "new content"})
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == ["existing"]
        assert (skill_dir / "SKILL.md").read_text(encoding="utf-8") == "new content"

    async def test_old_control_plane_cannot_revive_retired_ask_agent(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        ask_dir = skills_dir / "ask-agent"
        ask_dir.mkdir(parents=True)
        (ask_dir / "SKILL.md").write_text(
            "---\nname: ask-agent\n---\n# 定时任务 A2A 调用\n"
            "[NAVIGATOR_SCHEDULED_A2A]\nNAVIGATOR_TOKEN\n/api/v1/agents/a/ask\n",
            encoding="utf-8",
        )
        pushed = "---\nname: ask-agent\n---\n# stale control-plane content\n"

        req = DeploySkillsRequest(skills={"ask-agent": pushed})
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.default_skill_roots",
                return_value=(skills_dir,),
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == []
        assert not ask_dir.exists()
        assert (skills_dir / "navigator-ops" / "SKILL.md").is_file()

    async def test_empty_request_reconciles_worker_local_platform_skills(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        req = DeploySkillsRequest(skills={})
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.default_skill_roots",
                return_value=(skills_dir,),
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == []
        assert (skills_dir / "navigator-ops" / "SKILL.md").is_file()

    async def test_remote_payload_cannot_replace_local_navigator_ops_bundle(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        req = DeploySkillsRequest(skills={"navigator-ops": "remote replacement"})
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.default_skill_roots",
                return_value=(skills_dir,),
            ),
        ):
            result = await deploy_skills(req)

        content = (skills_dir / "navigator-ops" / "SKILL.md").read_text(encoding="utf-8")
        assert result["deployed"] == []
        assert "foggy-navigator-platform-skill:v1" in content
        assert content != "remote replacement"

    async def test_skips_linked_compatible_skill_target(self, tmp_path):
        skills_dir = tmp_path / ".agents" / "skills"
        req = DeploySkillsRequest(skills={"linked-skill": "replacement"})

        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
            patch(
                "agent_worker.platform_skills.deployer._is_link_or_reparse_point",
                return_value=True,
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == []
        assert not (skills_dir / "linked-skill" / "SKILL.md").exists()

    async def test_handles_write_failure_gracefully(self, tmp_path):
        req = DeploySkillsRequest(
            skills={"good-skill": "# Good", "bad-skill": "# Bad"}
        )
        skills_dir = tmp_path / ".agents" / "skills"
        original_write_text = Path.write_text

        def failing_write(self, content, **kwargs):
            if "bad-skill" in str(self):
                raise PermissionError("denied")
            return original_write_text(self, content, **kwargs)

        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
            patch.object(Path, "write_text", failing_write),
        ):
            result = await deploy_skills(req)

        assert "good-skill" in result["deployed"]
        assert "bad-skill" not in result["deployed"]

    async def test_reconcile_and_writes_use_to_thread(self, tmp_path):
        req = DeploySkillsRequest(skills={"threaded-skill": "# Threaded"})
        to_thread_calls: list[tuple] = []

        async def fake_to_thread(func, *args, **kwargs):
            to_thread_calls.append((func, args, kwargs))
            return func(*args, **kwargs)

        skills_dir = tmp_path / ".agents" / "skills"
        with (
            patch("agent_worker.routes.platform_skills.user_skills_dir", return_value=skills_dir),
            patch(
                "agent_worker.routes.platform_skills.platform_skill_deployer.reconcile_platform_skills"
            ),
            patch(
                "agent_worker.routes.platform_skills.asyncio.to_thread",
                side_effect=fake_to_thread,
            ),
        ):
            result = await deploy_skills(req)

        assert result["deployed"] == ["threaded-skill"]
        assert len(to_thread_calls) == 2
        assert (skills_dir / "threaded-skill" / "SKILL.md").exists()
