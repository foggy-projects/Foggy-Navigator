from __future__ import annotations

import pytest

from langgraph_biz_worker.runtime.account_file_tools import FileToolError
from langgraph_biz_worker.runtime.public_skill_resource_tools import PublicSkillResourceTools


def test_reads_only_current_client_app_public_skill_resources(tmp_path):
    skills_root = tmp_path / "skills"
    own = skills_root / "public" / "apps" / "app_01" / "query-skill"
    other = skills_root / "public" / "apps" / "app_02" / "query-skill"
    (own / "references").mkdir(parents=True)
    (other / "references").mkdir(parents=True)
    (own / "references" / "payload.md").write_text("own payload", encoding="utf-8")
    (other / "references" / "payload.md").write_text("other payload", encoding="utf-8")

    tools = PublicSkillResourceTools(skills_root, "app_01")

    result = tools.read_resource("query-skill", "references/payload.md")

    assert result["ok"] is True
    assert result["content"] == "own payload"


def test_lists_client_app_public_skills(tmp_path):
    skills_root = tmp_path / "skills"
    (skills_root / "public" / "apps" / "app_01" / "skill-a").mkdir(parents=True)
    (skills_root / "public" / "apps" / "app_01" / "skill-b").mkdir(parents=True)

    tools = PublicSkillResourceTools(skills_root, "app_01")

    result = tools.list_resources()

    assert {entry["path"] for entry in result["entries"]} == {"skills/skill-a", "skills/skill-b"}


def test_rejects_resource_path_escape(tmp_path):
    tools = PublicSkillResourceTools(tmp_path / "skills", "app_01")

    with pytest.raises(FileToolError):
        tools.read_resource("query-skill", "../secret.md")
