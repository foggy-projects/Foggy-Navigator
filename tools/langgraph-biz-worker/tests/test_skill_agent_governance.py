from __future__ import annotations

import pytest

from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.runtime.skill_identity import SkillNameValidationError


def test_register_list_get_and_validate_skill(tmp_path):
    agent = SkillAgent(tmp_path / "skills")

    created = agent.register_skill(
        "order-assistant",
        description="Order helper",
        instructions="Use query_order, then submit a concise result.",
        tools="query_order",
        resources={"resources/example.txt": "order O-1001"},
    )

    assert created["skill_name"] == "order-assistant"
    assert created["manifest"]["manifest_id"] == "order-assistant"
    assert created["manifest"]["allowed_tools"] == ["query_order"]
    assert created["validation"]["ok"] is True

    skills = agent.list_skills()
    assert [skill["skill_name"] for skill in skills] == ["order-assistant"]
    assert skills[0]["valid"] is True

    loaded = agent.get_skill("order-assistant")
    assert "Use query_order" in loaded["content"]
    assert (tmp_path / "skills" / "order-assistant" / "resources" / "example.txt").read_text(
        encoding="utf-8"
    ) == "order O-1001"

    validation = agent.validate_skill("order-assistant")
    assert validation == {
        "ok": True,
        "skill_name": "order-assistant",
        "manifest_id": "order-assistant",
        "errors": [],
        "warnings": [],
    }


def test_register_accepts_full_skill_content_and_warns_on_manifest_id_mismatch(tmp_path):
    agent = SkillAgent(tmp_path / "skills")

    created = agent.register_skill(
        "order-assistant",
        content="""---
name: order_internal
description: Order helper
tools:
  - query_order
---

Use query_order.
""",
    )

    assert created["manifest"]["manifest_id"] == "order_internal"
    assert created["validation"]["ok"] is True
    assert created["validation"]["warnings"] == [
        "frontmatter name 'order_internal' differs from folder skill_name 'order-assistant'"
    ]


def test_register_rejects_existing_without_overwrite(tmp_path):
    agent = SkillAgent(tmp_path / "skills")
    agent.register_skill("order-assistant", instructions="First version.")

    with pytest.raises(FileExistsError, match="Skill already exists"):
        agent.register_skill("order-assistant", instructions="Second version.")


def test_register_overwrite_replaces_same_skill_only(tmp_path):
    agent = SkillAgent(tmp_path / "skills")
    agent.register_skill("order-assistant", instructions="First version.")
    agent.register_skill("billing-assistant", instructions="Keep this skill.")

    updated = agent.register_skill(
        "order-assistant",
        instructions="Second version.",
        overwrite=True,
    )

    assert "Second version." in updated["content"]
    assert agent.get_skill("billing-assistant")["skill_name"] == "billing-assistant"


def test_delete_skill_removes_only_named_directory(tmp_path):
    agent = SkillAgent(tmp_path / "skills")
    agent.register_skill("order-assistant", instructions="Delete me.")
    agent.register_skill("billing-assistant", instructions="Keep me.")

    result = agent.delete_skill("order-assistant")

    assert result == {"deleted": True, "skill_name": "order-assistant"}
    with pytest.raises(FileNotFoundError):
        agent.get_skill("order-assistant")
    assert agent.get_skill("billing-assistant")["skill_name"] == "billing-assistant"


@pytest.mark.parametrize(
    "skill_name",
    ["", " order", "order/assistant", "order\\assistant", "../order", "C:order", "public", ".hidden"],
)
def test_governance_rejects_unsafe_skill_names(tmp_path, skill_name):
    agent = SkillAgent(tmp_path / "skills")

    with pytest.raises(SkillNameValidationError):
        agent.register_skill(skill_name, instructions="Unsafe.")

    with pytest.raises(SkillNameValidationError):
        agent.delete_skill(skill_name)


@pytest.mark.parametrize(
    "resource_path",
    ["../escape.txt", "/absolute.txt", "C:escape.txt", "nested/../escape.txt", "nested//file.txt", "SKILL.md"],
)
def test_register_rejects_unsafe_resource_paths(tmp_path, resource_path):
    agent = SkillAgent(tmp_path / "skills")

    with pytest.raises(ValueError):
        agent.register_skill(
            "order-assistant",
            instructions="Resource path should fail.",
            resources={resource_path: "x"},
        )

    assert not (tmp_path / "escape.txt").exists()
    assert not (tmp_path / "skills" / "order-assistant").exists()


def test_validate_reports_missing_or_invalid_skill(tmp_path):
    agent = SkillAgent(tmp_path / "skills")

    missing = agent.validate_skill("missing-skill")
    assert missing["ok"] is False
    assert missing["errors"] == ["SKILL.md not found"]

    bad_dir = tmp_path / "skills" / "bad-skill"
    bad_dir.mkdir(parents=True)
    (bad_dir / "SKILL.md").write_text("# Missing frontmatter\n", encoding="utf-8")

    invalid = agent.validate_skill("bad-skill")
    assert invalid["ok"] is False
    assert "SKILL.md must start with YAML frontmatter" in invalid["errors"]
    assert "Skill manifest is not loadable" in invalid["errors"]
