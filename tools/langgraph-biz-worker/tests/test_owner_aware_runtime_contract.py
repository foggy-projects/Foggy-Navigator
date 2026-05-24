"""Regression coverage for the owner-aware Agent runtime contract."""

from __future__ import annotations

from pathlib import Path

from langchain_core.messages import AIMessage

from langgraph_biz_worker.graphs import root_graph as root_module
from langgraph_biz_worker.models import FrameStatus
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_skill_agent import LlmSkillAgent
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


class _FakeToolCallModel:
    def __init__(self, responses: list[AIMessage]) -> None:
        self.responses = responses
        self.calls = 0
        self.seen_messages = []
        self.bound_tools = []

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    def invoke(self, messages):
        self.seen_messages.append(list(messages))
        response = self.responses[self.calls]
        self.calls += 1
        return response


def _write_skill(path: Path, *, name: str, description: str, body: str) -> None:
    path.mkdir(parents=True)
    (path / "SKILL.md").write_text(
        "\n".join([
            "---",
            f"name: {name}",
            f"description: {description}",
            "allowed-tools:",
            "  - submit_frame_result",
            "metadata:",
            f"  display_name: {name}",
            "  output-schema:",
            "    type: object",
            "  promote-to-parent:",
            "    - result_summary",
            "    - structured_output",
            "---",
            "",
            body,
        ]),
        encoding="utf-8",
    )


def test_owner_aware_runtime_loads_private_skill_and_account_context(tmp_path, monkeypatch):
    skills_root = tmp_path / "skills"
    data_root = tmp_path / "data"
    client_app_id = "school-sim"
    account_id = "school-pm-001"
    skill_name = "school-sim.actor.pm.m2.v1"

    _write_skill(
        skills_root / "public" / "apps" / client_app_id / "school-sim-actor-pm",
        name=skill_name,
        description="public PM actor",
        body="PUBLIC_SHARED_ROOT material should lose to the private actor workspace.",
    )
    _write_skill(
        data_root / "accounts" / account_id / "agent" / "skills" / "school-sim-actor-pm",
        name=skill_name,
        description="private PM actor",
        body="PRIVATE_ACTOR_WORKSPACE with PM responsibility from account skill.",
    )
    account_root = data_root / "accounts" / account_id / "agent"
    account_root.mkdir(parents=True, exist_ok=True)
    (account_root / "ACCOUNT_POLICY.md").write_text(
        "PM_POLICY: answer from School Sim owner-aware context.",
        encoding="utf-8",
    )

    registry = SkillRegistry(skills_root=skills_root, data_root=data_root)
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(tmp_path / "frames"),
    )
    monkeypatch.setattr(root_module, "_skill_registry", registry)
    monkeypatch.setattr(root_module, "_runtime", runtime)
    monkeypatch.setattr(root_module.settings, "llm_execute_skills", False)

    result = root_module.route_skill({
        "task_id": "task-owner-aware-contract",
        "session_id": None,
        "prompt": "run PM actor smoke",
        "model": None,
        "context": {
            "skill": skill_name,
            "clientAppId": client_app_id,
            "upstreamUserId": account_id,
        },
        "user_id": "navigator-task-owner",
        "tenant_id": None,
        "events": [],
        "started_at": 0.0,
        "active_frame_id": None,
        "skill_results": [],
    })

    frame = runtime.get_frame(result["active_frame_id"])
    manifest = frame.private_working_state["_skill_manifest"]
    assert manifest["description"] == "private PM actor"
    assert "PRIVATE_ACTOR_WORKSPACE" in manifest["markdown_body"]
    assert "PUBLIC_SHARED_ROOT" not in manifest["markdown_body"]

    model = _FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "submit_owner_aware",
            "name": "submit_frame_result",
            "args": {
                "summary": "Owner-aware runtime contract verified.",
                "structured_output": {"ok": True},
            },
        }]),
    ])
    LlmSkillAgent(model, runtime, data_root=data_root).run(
        task_id="task-owner-aware-contract",
        frame_id=frame.frame_id,
        prompt="run PM actor smoke",
        account_id=account_id,
        runtime_context={"client_app_id": client_app_id},
    )

    system_prompt = model.seen_messages[0][0].content
    assert system_prompt.index("### ACCOUNT_POLICY.md") < system_prompt.index("技能说明:")
    assert "PM_POLICY" in system_prompt
    assert "PRIVATE_ACTOR_WORKSPACE" in system_prompt
    assert "PUBLIC_SHARED_ROOT" not in system_prompt
    assert runtime.get_frame(frame.frame_id).status == FrameStatus.COMPLETED
