"""Tests for account-private Skill loading during root routing."""

from __future__ import annotations

from pathlib import Path

from langgraph_biz_worker.graphs import root_graph as root_module
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


def _write_skill(path: Path, description: str) -> None:
    path.mkdir(parents=True)
    (path / "SKILL.md").write_text(
        "\n".join([
            "---",
            "name: exception_triage",
            f"description: {description}",
            "metadata:",
            "  display_name: Exception Triage",
            "  output-schema:",
            "    type: object",
            "  promote-to-parent:",
            "    - result_summary",
            "    - structured_output",
            "---",
            "",
            "Test skill.",
        ]),
        encoding="utf-8",
    )


def test_route_skill_loads_account_private_skill_and_snapshots_manifest(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    _write_skill(skills_root / "builtin" / "exception-triage", "builtin version")
    _write_skill(data_root / "accounts" / "user-001" / "skills" / "exception-triage", "account version")

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    registry.load()
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)

    result = root_module.route_skill({
        "task_id": "task-account-skill",
        "session_id": None,
        "prompt": "handle exception",
        "model": None,
        "context": {"skill": "exception_triage", "order_id": "O-1"},
        "user_id": "user-001",
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    frame_id = result["active_frame_id"]
    frame = runtime.get_frame(frame_id)

    assert frame is not None
    assert frame.private_working_state["_skill_manifest"]["description"] == "account version"


# ---------------------------------------------------------------------------
# 1.1.1 Regression: write_file → SkillRegistry reload → skill discovery
# ---------------------------------------------------------------------------


def test_write_skill_then_registry_reload_discovers_new_skill(tmp_path):
    """After writing SKILL.md via AccountFileTools, SkillRegistry.load finds it."""
    from langgraph_biz_worker.runtime.account_file_tools import AccountFileTools

    data_root = tmp_path / "data"
    skills_root = tmp_path / "skills"
    skills_root.mkdir()

    # Pre-create the account skills directory structure
    acct_skills = data_root / "accounts" / "user-001" / "skills" / "new-skill"
    acct_skills.mkdir(parents=True)

    tools = AccountFileTools(data_root, "user-001", "t1")
    tools.write_file(
        "skills/new-skill/SKILL.md",
        content="\n".join([
            "---",
            "name: new_skill",
            "description: dynamically created skill",
            "metadata:",
            "  output-schema:",
            "    type: object",
            "---",
            "",
            "# New Skill",
        ]),
    )

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    registry.load(account_id="user-001")

    manifest = registry.get_manifest("new_skill")
    assert manifest is not None
    assert manifest.description == "dynamically created skill"


def test_query_time_routing_reaches_new_private_skill(tmp_path, monkeypatch):
    """query-time with userId routes to a dynamically created private skill."""
    from langgraph_biz_worker.runtime.account_file_tools import AccountFileTools

    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"
    skills_root.mkdir()

    # Write the skill via file tools
    acct_skills = data_root / "accounts" / "user-001" / "skills" / "my-new-skill"
    acct_skills.mkdir(parents=True)

    tools = AccountFileTools(data_root, "user-001", "t1")
    tools.write_file(
        "skills/my-new-skill/SKILL.md",
        content="\n".join([
            "---",
            "name: my_new_skill",
            "description: user created",
            "metadata:",
            "  display_name: My New Skill",
            "  output-schema:",
            "    type: object",
            "  promote-to-parent:",
            "    - result_summary",
            "    - structured_output",
            "---",
            "",
            "# My New Skill",
        ]),
    )

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)

    result = root_module.route_skill({
        "task_id": "task-new-skill",
        "session_id": None,
        "prompt": "use my new skill",
        "model": None,
        "context": {"skill": "my_new_skill"},
        "user_id": "user-001",
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    frame_id = result["active_frame_id"]
    assert frame_id is not None

    frame = runtime.get_frame(frame_id)
    assert frame is not None
    assert frame.skill_id == "my_new_skill"
    assert frame.private_working_state["_skill_manifest"]["description"] == "user created"


def test_run_skill_passes_account_id_to_llm_agent(tmp_path, monkeypatch):
    """LLM skill execution must receive the same account context used for routing."""
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    _write_skill(skills_root / "builtin" / "exception-triage", "builtin version")

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    registry.load()
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )
    frame_id = runtime.invoke_skill(
        task_id="task-run-skill-account",
        skill_id="exception_triage",
        skill_input={"skill": "exception_triage"},
    )

    class CapturingAgent:
        def __init__(self) -> None:
            self.calls = []

        def run(self, **kwargs):
            self.calls.append(kwargs)
            return []

    agent = CapturingAgent()
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module, "_llm_skill_agent", agent)

    root_module.run_skill({
        "task_id": "task-run-skill-account",
        "session_id": None,
        "prompt": "use private skill",
        "model": None,
        "context": {"accountId": "acct-from-context"},
        "user_id": None,
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": frame_id,
        "skill_results": [],
    })

    assert agent.calls[0]["account_id"] == "acct-from-context"
