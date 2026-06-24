"""Unit tests for routes/skills.py skill discovery."""

from __future__ import annotations

from unittest.mock import patch

import pytest

from agent_worker.routes.skills import list_skills


@pytest.mark.asyncio
async def test_list_skills_scans_agent_project_and_user_dirs(tmp_path):
    project_dir = tmp_path / "project"
    project_skill = project_dir / ".agents" / "skills" / "project-skill"
    user_skills_dir = tmp_path / "home" / ".agents" / "skills"
    user_skill = user_skills_dir / "user-skill"

    project_skill.mkdir(parents=True)
    user_skill.mkdir(parents=True)
    (project_skill / "SKILL.md").write_text("---\nname: project-skill\ndescription: Project skill\n---\n", encoding="utf-8")
    (user_skill / "SKILL.md").write_text("---\nname: user-skill\ndescription: User skill\n---\n", encoding="utf-8")

    with patch("agent_worker.config.settings.allowed_cwds", []):
        with patch("agent_worker.routes.skills.user_skills_dir", return_value=user_skills_dir):
            result = await list_skills(cwd=str(project_dir))

    assert [(skill.name, skill.scope) for skill in result] == [
        ("project-skill", "project"),
        ("user-skill", "user"),
    ]
