from pathlib import Path
from unittest.mock import patch

from agent_worker.platform_skills import deployer


RETIRED_SKILL = """---
name: cross-project-task
---

curl -s http://localhost:8112/api/v1/cross-project-tasks
"""

LEGACY_ASK_AGENT = """---
name: ask-agent
---

# 咨询协作 Agent

NAVIGATOR_TOKEN
curl http://localhost:8112/api/v1/agents/{agentId}/ask
"""


def _legacy_skill_dirs(home: Path) -> tuple[Path, ...]:
    return (
        home / ".agents" / "skills" / "cross-project-task",
        home / ".agent" / "skills" / "cross-project-task",
        home / ".claude" / "skills" / "cross-project-task",
    )


def test_cross_project_skill_is_not_bundled() -> None:
    assert "cross-project-task" not in deployer._SKILL_TEMPLATES
    assert not (Path(deployer.__file__).parent / "cross_project_task.md").exists()


def test_removes_retired_skill_from_all_legacy_paths(tmp_path: Path) -> None:
    skill_dirs = _legacy_skill_dirs(tmp_path)
    for skill_dir in skill_dirs:
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text(RETIRED_SKILL, encoding="utf-8")

    deployer.remove_retired_platform_skills(skill_dirs)
    deployer.remove_retired_platform_skills(skill_dirs)

    for skill_dir in skill_dirs:
        assert not (skill_dir / "SKILL.md").exists()
        assert not skill_dir.exists()


def test_preserves_user_files_and_unrecognized_skill(tmp_path: Path) -> None:
    managed_dir = tmp_path / "managed" / "cross-project-task"
    managed_dir.mkdir(parents=True)
    (managed_dir / "SKILL.md").write_text(RETIRED_SKILL, encoding="utf-8")
    (managed_dir / "notes.txt").write_text("keep", encoding="utf-8")

    unrecognized_dir = tmp_path / "unrecognized" / "cross-project-task"
    unrecognized_dir.mkdir(parents=True)
    (unrecognized_dir / "SKILL.md").write_text("name: user-skill", encoding="utf-8")

    deployer.remove_retired_platform_skills((managed_dir, unrecognized_dir))

    assert managed_dir.exists()
    assert not (managed_dir / "SKILL.md").exists()
    assert (managed_dir / "notes.txt").read_text(encoding="utf-8") == "keep"
    assert (unrecognized_dir / "SKILL.md").read_text(encoding="utf-8") == "name: user-skill"


def test_scheduled_task_uses_explicit_a2a_marker() -> None:
    content = (Path(deployer.__file__).parent / "scheduled_task.md").read_text(encoding="utf-8")

    assert "[NAVIGATOR_SCHEDULED_A2A]" in content
    assert "targetAgentId: agent-xxx" in content
    assert "targetAgentName" not in content
    assert "agentId-or-agentName" not in content
    assert "@otherAgentName" not in content


def test_worker_startup_deploys_narrowed_ask_agent_and_removes_legacy_copies(
    tmp_path: Path,
) -> None:
    canonical_dir = tmp_path / ".agents" / "skills" / "ask-agent"
    canonical_dir.mkdir(parents=True)
    (canonical_dir / "SKILL.md").write_text(LEGACY_ASK_AGENT, encoding="utf-8")

    legacy_dirs = (
        tmp_path / ".agent" / "skills" / "ask-agent",
        tmp_path / ".claude" / "skills" / "ask-agent",
    )
    for legacy_dir in legacy_dirs:
        legacy_dir.mkdir(parents=True)
        (legacy_dir / "SKILL.md").write_text(LEGACY_ASK_AGENT, encoding="utf-8")
    (legacy_dirs[0] / "notes.txt").write_text("keep", encoding="utf-8")

    with (
        patch.object(deployer, "user_skills_dir", return_value=tmp_path / ".agents" / "skills"),
        patch.object(deployer.Path, "home", return_value=tmp_path),
    ):
        deployer.deploy_platform_skills()

    content = (canonical_dir / "SKILL.md").read_text(encoding="utf-8")
    assert "[NAVIGATOR_SCHEDULED_A2A]" in content
    assert "targetAgentId: agent-xxx" in content
    assert "TARGET_AGENT_ID" in content
    assert "targetAgentName" not in content
    assert "agentId-or-agentName" not in content
    assert "{{NAVIGATOR_API_BASE}}" not in content

    assert not (legacy_dirs[0] / "SKILL.md").exists()
    assert (legacy_dirs[0] / "notes.txt").read_text(encoding="utf-8") == "keep"
    assert not legacy_dirs[1].exists()


def test_worker_startup_preserves_unrecognized_ask_agent_copies(tmp_path: Path) -> None:
    custom_content = "---\nname: ask-agent\n---\n\n# User-managed routing\n"
    canonical_dir = tmp_path / ".agents" / "skills" / "ask-agent"
    legacy_dir = tmp_path / ".claude" / "skills" / "ask-agent"
    for skill_dir in (canonical_dir, legacy_dir):
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text(custom_content, encoding="utf-8")

    managed_legacy_dir = tmp_path / ".agent" / "skills" / "ask-agent"
    managed_legacy_dir.mkdir(parents=True)
    (managed_legacy_dir / "SKILL.md").write_text(LEGACY_ASK_AGENT, encoding="utf-8")

    with (
        patch.object(deployer, "user_skills_dir", return_value=tmp_path / ".agents" / "skills"),
        patch.object(deployer.Path, "home", return_value=tmp_path),
    ):
        deployer.deploy_platform_skills()

    assert (canonical_dir / "SKILL.md").read_text(encoding="utf-8") == custom_content
    assert (legacy_dir / "SKILL.md").read_text(encoding="utf-8") == custom_content
    assert not managed_legacy_dir.exists()
