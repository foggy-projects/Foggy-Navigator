from pathlib import Path
from unittest.mock import patch

import pytest

from agent_worker.platform_skills import deployer


RETIRED_SKILLS = {
    "cross-project-task": """---
name: cross-project-task
---
/api/v1/cross-project-tasks
""",
    "ask-agent": """---
name: ask-agent
---
# 定时任务 A2A 调用
[NAVIGATOR_SCHEDULED_A2A]
NAVIGATOR_TOKEN
/api/v1/agents/agent-1/ask
""",
    "navigator-admin": """---
name: navigator-admin
---
# Foggy Navigator 平台管理
NAVIGATOR_TOKEN
/api/v1/claude-workers
""",
    "scheduled-task": """---
name: scheduled-task
---
# AI 定时任务配置向导
NAVIGATOR_TOKEN
/api/v1/sharing-keys
""",
    "sharing-key": """---
name: sharing-key
---
# Sharing Key 管理向导
NAVIGATOR_TOKEN
/api/v1/sharing-keys
""",
}

KNOWN_ASK_AGENT_GENERATIONS = (
    RETIRED_SKILLS["ask-agent"],
    """---
name: ask-agent
---
# 咨询协作 Agent
{{AGENT_TABLE}}
NAVIGATOR_TOKEN
/api/v1/agents/agent-1/ask
""",
)


def _skill_roots(home: Path) -> tuple[Path, ...]:
    return (
        home / ".agents" / "skills",
        home / ".agent" / "skills",
        home / ".claude" / "skills",
    )


def test_retired_skills_are_not_bundled() -> None:
    assert set(deployer._SKILL_TEMPLATES) == {"company-skill-marketplace"}
    assert set(deployer._SKILL_BUNDLES) == {"navigator-ops"}
    for old_template in ("ask_agent.md", "navigator_admin.md", "scheduled_task.md"):
        assert not (Path(deployer.__file__).parent / old_template).exists()


def test_reconcile_deploys_navigator_ops_bundle_and_substitutes_placeholders(
    tmp_path: Path,
) -> None:
    roots = _skill_roots(tmp_path)

    deployer.reconcile_platform_skills(roots[0], roots)

    skill_dir = roots[0] / "navigator-ops"
    assert (skill_dir / "SKILL.md").is_file()
    assert (skill_dir / "agents" / "openai.yaml").is_file()
    assert (skill_dir / "references" / "platform-admin.md").is_file()
    assert (skill_dir / "references" / "scheduled-task.md").is_file()
    assert (skill_dir / "references" / "scheduled-a2a.md").is_file()
    assert (skill_dir / "references" / "sharing-key.md").is_file()

    combined = "\n".join(
        path.read_text(encoding="utf-8")
        for path in skill_dir.rglob("*")
        if path.is_file()
    )
    assert "{{NAVIGATOR_API_BASE}}" not in combined
    assert "allow_implicit_invocation: false" in combined
    assert "`ask-agent` Skill" not in combined


def test_reconcile_is_idempotent_and_updates_recognized_bundle(tmp_path: Path) -> None:
    roots = _skill_roots(tmp_path)
    deployer.reconcile_platform_skills(roots[0], roots)

    skill_dir = roots[0] / "navigator-ops"
    reference = skill_dir / "references" / "platform-admin.md"
    reference.write_text("stale", encoding="utf-8")
    (skill_dir / "notes.txt").write_text("keep", encoding="utf-8")

    deployer.reconcile_platform_skills(roots[0], roots)

    assert reference.read_text(encoding="utf-8") != "stale"
    assert (skill_dir / "notes.txt").read_text(encoding="utf-8") == "keep"


def test_reconcile_preserves_unrecognized_navigator_ops(tmp_path: Path) -> None:
    roots = _skill_roots(tmp_path)
    custom = "---\nname: navigator-ops\n---\n# User-managed operations\n"
    skill_file = roots[0] / "navigator-ops" / "SKILL.md"
    skill_file.parent.mkdir(parents=True)
    skill_file.write_text(custom, encoding="utf-8")

    deployer.reconcile_platform_skills(roots[0], roots)

    assert skill_file.read_text(encoding="utf-8") == custom
    assert not (skill_file.parent / "references").exists()


@pytest.mark.parametrize("skill_name", tuple(RETIRED_SKILLS))
def test_removes_recognized_retired_skills_from_all_roots(
    tmp_path: Path,
    skill_name: str,
) -> None:
    roots = _skill_roots(tmp_path)
    for root in roots:
        skill_dir = root / skill_name
        skill_dir.mkdir(parents=True)
        (skill_dir / "SKILL.md").write_text(RETIRED_SKILLS[skill_name], encoding="utf-8")

    deployer.remove_retired_platform_skill(skill_name, roots)
    deployer.remove_retired_platform_skill(skill_name, roots)

    for root in roots:
        assert not (root / skill_name).exists()


def test_retirement_preserves_user_files_and_unrecognized_content(tmp_path: Path) -> None:
    roots = _skill_roots(tmp_path)
    managed_dir = roots[0] / "sharing-key"
    managed_dir.mkdir(parents=True)
    (managed_dir / "SKILL.md").write_text(RETIRED_SKILLS["sharing-key"], encoding="utf-8")
    (managed_dir / "notes.txt").write_text("keep", encoding="utf-8")

    custom_dir = roots[1] / "sharing-key"
    custom_dir.mkdir(parents=True)
    custom = "---\nname: sharing-key\n---\n# User-managed key helper\n"
    (custom_dir / "SKILL.md").write_text(custom, encoding="utf-8")

    deployer.remove_retired_platform_skill("sharing-key", roots)

    assert not (managed_dir / "SKILL.md").exists()
    assert (managed_dir / "notes.txt").read_text(encoding="utf-8") == "keep"
    assert (custom_dir / "SKILL.md").read_text(encoding="utf-8") == custom


@pytest.mark.parametrize("content", KNOWN_ASK_AGENT_GENERATIONS)
def test_retirement_recognizes_known_ask_agent_generations(
    tmp_path: Path,
    content: str,
) -> None:
    roots = _skill_roots(tmp_path)
    skill_dir = roots[0] / "ask-agent"
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(content, encoding="utf-8")

    deployer.remove_retired_platform_skill("ask-agent", roots)

    assert not skill_dir.exists()


def test_retirement_skips_linked_skill_directory(tmp_path: Path) -> None:
    roots = _skill_roots(tmp_path)
    external = tmp_path / "external"
    external.mkdir()
    skill_file = external / "SKILL.md"
    skill_file.write_text(RETIRED_SKILLS["ask-agent"], encoding="utf-8")

    roots[0].mkdir(parents=True)
    (roots[0] / "ask-agent").symlink_to(external, target_is_directory=True)

    deployer.remove_retired_platform_skill("ask-agent", roots)

    assert skill_file.exists()
    assert (roots[0] / "ask-agent").is_symlink()


def test_reconcile_cleans_old_worker_outputs_before_deploying_router(tmp_path: Path) -> None:
    roots = _skill_roots(tmp_path)
    for root in roots:
        for skill_name, content in RETIRED_SKILLS.items():
            skill_dir = root / skill_name
            skill_dir.mkdir(parents=True)
            (skill_dir / "SKILL.md").write_text(content, encoding="utf-8")

    with (
        patch.object(deployer, "user_skills_dir", return_value=roots[0]),
        patch.object(deployer.Path, "home", return_value=tmp_path),
    ):
        deployer.deploy_platform_skills()

    for root in roots:
        for skill_name in RETIRED_SKILLS:
            assert not (root / skill_name).exists()
    assert (roots[0] / "navigator-ops" / "SKILL.md").is_file()
