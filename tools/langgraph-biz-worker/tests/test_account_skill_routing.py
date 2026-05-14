"""Tests for account-private Skill loading during root routing."""

from __future__ import annotations

import json
from pathlib import Path

from langgraph_biz_worker.graphs import root_graph as root_module
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


class _FakeChunk:
    content = "ok"
    tool_calls = None

    def __add__(self, other):
        self.content += getattr(other, "content", "")
        return self


class _FakeChatModel:
    def __init__(self):
        self.messages = None

    def bind_tools(self, tools):
        return self

    def stream(self, messages):
        self.messages = messages
        yield _FakeChunk()


def _write_skill(path: Path, description: str) -> None:
    _write_named_skill(path, "exception_triage", "Exception Triage", description)


def _write_named_skill(path: Path, name: str, display_name: str, description: str) -> None:
    path.mkdir(parents=True)
    (path / "SKILL.md").write_text(
        "\n".join([
            "---",
            f"name: {name}",
            f"description: {description}",
            "metadata:",
            f"  display_name: {display_name}",
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


def test_route_skill_prefers_upstream_user_context_over_task_owner(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    _write_skill(skills_root / "builtin" / "exception-triage", "builtin version")
    _write_skill(data_root / "accounts" / "upstream-001" / "skills" / "exception-triage", "upstream version")

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)

    result = root_module.route_skill({
        "task_id": "task-upstream-skill",
        "session_id": None,
        "prompt": "handle exception",
        "model": None,
        "context": {"skill": "exception_triage", "upstreamUserId": "upstream-001"},
        "user_id": "navigator-actor",
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    frame = runtime.get_frame(result["active_frame_id"])

    assert frame is not None
    assert frame.private_working_state["_skill_manifest"]["description"] == "upstream version"


def test_route_skill_loads_client_app_public_skill_and_snapshots_manifest(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    _write_named_skill(skills_root / "public" / "shared-skill", "shared_skill", "Shared Skill", "global version")
    _write_named_skill(
        skills_root / "public" / "apps" / "tms_app" / "shared-skill",
        "shared_skill",
        "TMS Shared Skill",
        "tms app version",
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
        "task_id": "task-app-public-skill",
        "session_id": None,
        "prompt": "use shared skill",
        "model": None,
        "context": {"skill": "shared_skill", "clientAppId": "tms_app"},
        "user_id": None,
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    frame_id = result["active_frame_id"]
    frame = runtime.get_frame(frame_id)

    assert frame is not None
    assert frame.private_working_state["_skill_manifest"]["description"] == "tms app version"


def test_route_skill_auto_injects_only_client_app_public_skills(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    _write_named_skill(skills_root / "public" / "global-skill", "global_skill", "Global Skill", "global version")
    _write_named_skill(
        skills_root / "public" / "apps" / "tms_app" / "app-skill",
        "app_skill",
        "App Skill",
        "tms app version",
    )

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )
    fake_chat = _FakeChatModel()

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module, "_chat_model", fake_chat)
    monkeypatch.setattr(root_module.settings, "llm_agentic_routing", True)
    monkeypatch.setattr(root_module, "_data_root", str(data_root))

    root_module.route_skill({
        "task_id": "task-app-public-auto-inject",
        "session_id": None,
        "prompt": "what can you do",
        "model": None,
        "context": {"clientAppId": "tms_app"},
        "user_id": None,
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    system_prompt = fake_chat.messages[0].content
    assert "- app_skill: tms app version" in system_prompt
    assert "global_skill" not in system_prompt


def test_route_skill_uses_request_llm_config_for_agentic_routing(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )
    fake_chat = _FakeChatModel()
    seen_configs = []

    def fake_create_chat_model(config):
        seen_configs.append(config)
        return fake_chat

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module, "_chat_model", None)
    monkeypatch.setattr(root_module, "create_chat_model_from_config", fake_create_chat_model)
    monkeypatch.setattr(root_module.settings, "llm_agentic_routing", True)
    monkeypatch.setattr(root_module, "_data_root", str(data_root))

    root_module.route_skill({
        "task_id": "task-request-llm-config",
        "session_id": None,
        "prompt": "say hello",
        "model": None,
        "llm_config": {
            "provider": "openai",
            "base_url": "http://mock-llm:8000",
            "model": "navigator-e2e-scripted",
            "api_key": "mock-key",
        },
        "context": {},
        "user_id": None,
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    assert seen_configs[0]["base_url"] == "http://mock-llm:8000"
    assert fake_chat.messages is not None


def test_route_skill_injects_account_context_for_agentic_routing(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"
    account_root = data_root / "accounts" / "user-001"
    account_root.mkdir(parents=True)
    (account_root / "ACCOUNT_POLICY.md").write_text("routing policy", encoding="utf-8")
    (account_root / "AGENT.md").write_text("routing agent", encoding="utf-8")
    (account_root / "MEMORY.md").write_text("routing memory", encoding="utf-8")

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )
    fake_chat = _FakeChatModel()

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module, "_chat_model", fake_chat)
    monkeypatch.setattr(root_module.settings, "llm_agentic_routing", True)
    monkeypatch.setattr(root_module, "_data_root", str(data_root))

    root_module.route_skill({
        "task_id": "task-account-context-routing",
        "session_id": "session-account-context",
        "prompt": "what should I do",
        "model": None,
        "context": {},
        "user_id": "user-001",
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    system_prompt = fake_chat.messages[0].content
    assert system_prompt.index("### ACCOUNT_POLICY.md") < system_prompt.index("### AGENT.md")
    assert system_prompt.index("### AGENT.md") < system_prompt.index("### MEMORY.md")
    assert "routing policy" in system_prompt
    assert "routing agent" in system_prompt
    assert "routing memory" in system_prompt

    log_file = data_root / "logs" / "llm-conversations" / "session-account-context" / "0001_task-account-context-routing.jsonl"
    request_entry = json.loads(log_file.read_text(encoding="utf-8").splitlines()[0])
    logged_system_prompt = request_entry["messages"][0]["content"]
    assert "[account_context_files: omitted from conversation log]" in logged_system_prompt
    assert "routing policy" not in logged_system_prompt
    assert "routing agent" not in logged_system_prompt
    assert "routing memory" not in logged_system_prompt


def test_llm_conversation_log_groups_tasks_by_session_directory(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"
    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )

    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module, "_chat_model", _FakeChatModel())
    monkeypatch.setattr(root_module.settings, "llm_agentic_routing", True)
    monkeypatch.setattr(root_module, "_data_root", str(data_root))

    for task_id, prompt in (("task-1", "first"), ("task-2", "second")):
        root_module.route_skill({
            "task_id": task_id,
            "session_id": "session-123",
            "prompt": prompt,
            "model": None,
            "context": {},
            "runtime_context": {
                "current_time": "2026-05-11T10:37:10+08:00",
                "timezone": "Asia/Shanghai",
                "business_date": "2026-05-11",
                "task_scoped_token": "secret-token",
            },
            "user_id": None,
            "tenant_id": None,
            "events": [],
            "started_at": 0.0,
            "active_frame_id": None,
            "skill_results": [],
        })

    session_dir = data_root / "logs" / "llm-conversations" / "session-123"
    files = sorted(session_dir.glob("*.jsonl"))

    assert [p.name for p in files] == ["0001_task-1.jsonl", "0002_task-2.jsonl"]
    assert not (data_root / "logs" / "llm-conversations" / "session-123.jsonl").exists()

    first_entries = [json.loads(line) for line in files[0].read_text(encoding="utf-8").splitlines()]
    second_entries = [json.loads(line) for line in files[1].read_text(encoding="utf-8").splitlines()]

    assert first_entries[0]["event"] == "llm_request"
    assert [message["role"] for message in first_entries[0]["messages"]] == ["system", "user"]
    assert first_entries[0]["runtime_time_context"] == {
        "current_time": "2026-05-11T10:37:10+08:00",
        "timezone": "Asia/Shanghai",
        "business_date": "2026-05-11",
    }
    assert "task_scoped_token" not in first_entries[0]["runtime_time_context"]
    assert {entry["task_id"] for entry in first_entries} == {"task-1"}
    assert {entry["task_id"] for entry in second_entries} == {"task-2"}
    assert {entry["session_id"] for entry in first_entries + second_entries} == {"session-123"}


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
