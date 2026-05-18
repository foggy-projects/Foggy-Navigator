"""Tests for the BizWorker root graph bootstrap."""

from __future__ import annotations

import time
from typing import Any

from langgraph_biz_worker.graphs import root_graph as root_graph_module
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


def _state(task_id: str, session_id: str = "sess_root") -> dict[str, Any]:
    return {
        "task_id": task_id,
        "session_id": session_id,
        "prompt": "handle the request",
        "model": "test-model",
        "model_config_id": None,
        "llm_config": None,
        "vision_llm_config": None,
        "context": {"client_app_id": "capp_001"},
        "runtime_context": {},
        "attachments": None,
        "user_id": "user_001",
        "tenant_id": "tenant_001",
        "events": [],
        "started_at": time.time(),
        "active_frame_id": None,
        "skill_results": [],
    }


def _install_isolated_runtime(monkeypatch, tmp_path) -> SkillRuntime:
    registry = SkillRegistry(skills_root=tmp_path / "skills", data_root=tmp_path / "data")
    journal = FileFrameJournal(tmp_path / "data")
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=journal,
    )
    monkeypatch.setattr(root_graph_module, "_skill_registry", registry)
    monkeypatch.setattr(root_graph_module, "_journal", journal)
    monkeypatch.setattr(root_graph_module, "_runtime", runtime)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module, "_chat_model_for_state", lambda state: object())
    return runtime


def test_route_skill_creates_and_reuses_persistent_system_root_frame(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_reuse_001")

    first = root_graph_module.route_skill(state)
    second = root_graph_module.route_skill(state)

    assert first["active_frame_id"] == second["active_frame_id"]
    assert first["events"][-1].content == "Opening frame for skill: system.root"
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    root_frames = [
        frame for frame in runtime.get_frames_by_task("task_root_reuse_001")
        if frame.skill_id == root_graph_module.ROOT_SKILL_ID
    ]
    assert len(root_frames) == 1
    assert root_frames[0].status.value == "RUNNING"


def test_route_skill_restores_persistent_system_root_frame_from_journal(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_restore_001")
    first = root_graph_module.route_skill(state)

    restored_runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=runtime.registry,
        journal=root_graph_module._journal,
    )
    monkeypatch.setattr(root_graph_module, "_runtime", restored_runtime)

    second = root_graph_module.route_skill(state)

    assert second["active_frame_id"] == first["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"
    assert restored_runtime.get_frame(first["active_frame_id"]) is not None


def test_route_skill_reuses_system_root_frame_across_tasks_in_same_session(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)

    first = root_graph_module.route_skill(_state("task_root_session_001", "sess_shared"))
    second = root_graph_module.route_skill(_state("task_root_session_002", "sess_shared"))

    assert first["active_frame_id"] == second["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    frame = runtime.get_frame(first["active_frame_id"])
    assert frame is not None
    assert frame.conversation_id == "sess_shared"
    assert frame.origin_task_id == "task_root_session_001"
    assert frame.current_task_id == "task_root_session_002"
    assert frame.last_task_ids == ["task_root_session_001", "task_root_session_002"]


def test_route_skill_restores_system_root_frame_by_session_for_new_task(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    first = root_graph_module.route_skill(_state("task_root_session_restore_001", "sess_restore"))

    restored_runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=runtime.registry,
        journal=root_graph_module._journal,
    )
    monkeypatch.setattr(root_graph_module, "_runtime", restored_runtime)

    second = root_graph_module.route_skill(_state("task_root_session_restore_002", "sess_restore"))

    assert second["active_frame_id"] == first["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    frame = restored_runtime.get_frame(first["active_frame_id"])
    assert frame is not None
    assert frame.conversation_id == "sess_restore"
    assert frame.current_task_id == "task_root_session_restore_002"


def test_run_skill_passes_raw_prompt_and_runtime_attachments_to_llm_agent(monkeypatch, tmp_path):
    _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_attachment_prompt_001")
    state["attachments"] = [{
        "id": "att-1",
        "name": "photo.png",
        "url": "https://example.test/photo.png?token=secret",
        "kind": "image",
    }]
    routed = root_graph_module.route_skill(state)
    state["active_frame_id"] = routed["active_frame_id"]

    captured = {}

    class FakeAgent:
        def run(self, **kwargs):
            captured.update(kwargs)
            return []

    monkeypatch.setattr(root_graph_module, "_llm_skill_agent_for_state", lambda current_state: FakeAgent())

    root_graph_module.run_skill(state)

    assert captured["prompt"] == "handle the request"
    assert captured["runtime_context"]["attachments"] == state["attachments"]


def test_run_skill_passes_recent_conversation_to_persistent_root_agent(monkeypatch, tmp_path):
    _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_recent_prompt_001")
    state["context"] = {
        "contextId": "ctx-recent",
        "recentConversation": [
            {"role": "user", "content": "我刚才问了工单 1001"},
            {"role": "assistant", "content": "工单 1001 当前待处理"},
        ],
    }
    routed = root_graph_module.route_skill(state)
    state["active_frame_id"] = routed["active_frame_id"]

    captured = {}

    class FakeAgent:
        def run(self, **kwargs):
            captured.update(kwargs)
            return []

    monkeypatch.setattr(root_graph_module, "_llm_skill_agent_for_state", lambda current_state: FakeAgent())

    root_graph_module.run_skill(state)

    assert captured["runtime_context"]["_visible_recent_conversation"] == [
        {"role": "user", "content": "我刚才问了工单 1001"},
        {"role": "assistant", "content": "工单 1001 当前待处理"},
    ]


def test_agentic_routing_prompt_includes_recent_conversation(monkeypatch, tmp_path):
    registry = SkillRegistry(skills_root=tmp_path / "skills", data_root=tmp_path / "data")
    journal = FileFrameJournal(tmp_path / "data")
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=journal,
    )
    captured: dict[str, Any] = {}

    class FakeChunk:
        content = "No skill needed"
        tool_calls = None

        def __add__(self, other):
            return self

    class FakeChatModel:
        def bind_tools(self, tools):
            captured["tools"] = tools
            return self

        def stream(self, messages):
            captured["messages"] = messages
            yield FakeChunk()

    monkeypatch.setattr(root_graph_module, "_skill_registry", registry)
    monkeypatch.setattr(root_graph_module, "_journal", journal)
    monkeypatch.setattr(root_graph_module, "_runtime", runtime)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", False)
    monkeypatch.setattr(root_graph_module.settings, "llm_agentic_routing", True)
    monkeypatch.setattr(root_graph_module, "_chat_model_for_state", lambda state: FakeChatModel())

    state = _state("task_recent_conversation_prompt_001")
    state["prompt"] = "continue handling it"
    state["context"] = {
        "contextId": "ctx-001",
        "recentConversation": [
            {"role": "user", "content": "look up ticket A"},
            {"role": "assistant", "content": "ticket A is available"},
        ],
    }

    result = root_graph_module.route_skill(state)

    assert result["events"][-1].content == "No skill needed"
    human_prompt = captured["messages"][1].content
    assert "Recent conversation before the current user message:" in human_prompt
    assert "user: look up ticket A" in human_prompt
    assert "assistant: ticket A is available" in human_prompt
    assert "Current user message:\ncontinue handling it" in human_prompt
    assert '"contextId": "ctx-001"' in human_prompt
    assert "recentConversation" not in human_prompt.split("\nContext:", 1)[1]
