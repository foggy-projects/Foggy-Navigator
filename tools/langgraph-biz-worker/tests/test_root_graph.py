"""Tests for the BizWorker root graph bootstrap."""

from __future__ import annotations

import time
from typing import Any

from langgraph_biz_worker.graphs import root_graph as root_graph_module
from langgraph_biz_worker.models import SkillManifest
from langgraph_biz_worker.runtime.context_memory import ContextRuntimeMemory, save_to_root_frame
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.file_layout import session_data_dir
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime

CTX_RECENT = "bctx_20260520_ab_ctx-recent"
CTX_PROMPT = "bctx_20260520_cd_ctx-001"
CTX_SHARED = "bctx_20260520_ef_ctx-shared"
CTX_RESTORE = "bctx_20260520_12_ctx-restore"


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
        "skill_name": None,
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


def test_llm_skill_max_iterations_uses_runtime_context(monkeypatch):
    monkeypatch.setattr(root_graph_module.settings, "llm_skill_max_iterations", 6)
    state = _state("task_runtime_max_turns_001")
    state["runtime_context"] = {"max_turns": "14"}

    assert root_graph_module._llm_skill_max_iterations_for_state(state) == 14


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


def test_route_skill_explicit_skill_name_uses_named_skill_not_system_root(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    root_graph_module._skill_registry.register(
        SkillManifest(id="order_internal", name="Order Internal", allowed_tools=[]),
        aliases=["order-assistant"],
    )
    monkeypatch.setattr(root_graph_module._skill_registry, "load", lambda **kwargs: None)

    state = _state("task_explicit_skill_name_001")
    state["skill_name"] = "order-assistant"

    routed = root_graph_module.route_skill(state)
    frame = runtime.get_frame(routed["active_frame_id"])

    assert frame is not None
    assert frame.skill_id == "order-assistant"
    assert routed["events"][-1].content == "Opening frame for skill: order-assistant"


def test_route_skill_reuses_system_root_frame_across_tasks_in_same_session(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    first_state = _state("task_root_session_001", "sess_shared")
    first_state["context"]["contextId"] = CTX_SHARED
    second_state = _state("task_root_session_002", "sess_shared")
    second_state["context"]["contextId"] = CTX_SHARED

    first = root_graph_module.route_skill(first_state)
    second = root_graph_module.route_skill(second_state)

    assert first["active_frame_id"] == second["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    frame = runtime.get_frame(first["active_frame_id"])
    assert frame is not None
    assert frame.conversation_id == CTX_SHARED
    assert frame.origin_task_id == "task_root_session_001"
    assert frame.current_task_id == "task_root_session_002"
    assert frame.last_task_ids == ["task_root_session_001", "task_root_session_002"]


def test_route_skill_restores_system_root_frame_by_session_for_new_task(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    first_state = _state("task_root_session_restore_001", "sess_restore")
    first_state["context"]["contextId"] = CTX_RESTORE
    first = root_graph_module.route_skill(first_state)

    restored_runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=runtime.registry,
        journal=root_graph_module._journal,
    )
    monkeypatch.setattr(root_graph_module, "_runtime", restored_runtime)

    second_state = _state("task_root_session_restore_002", "sess_restore")
    second_state["context"]["contextId"] = CTX_RESTORE
    second = root_graph_module.route_skill(second_state)

    assert second["active_frame_id"] == first["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    frame = restored_runtime.get_frame(first["active_frame_id"])
    assert frame is not None
    assert frame.conversation_id == CTX_RESTORE
    assert frame.current_task_id == "task_root_session_restore_002"


def test_route_skill_restores_system_root_frame_from_session_index_without_scan(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    first_state = _state("task_root_index_restore_001", "sess_restore")
    first_state["context"]["contextId"] = CTX_RESTORE
    first = root_graph_module.route_skill(first_state)
    index_path = session_data_dir(tmp_path / "data", ("2026", "01", "01"), CTX_RESTORE) / "session.json"
    assert index_path.is_file()

    restored_runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=runtime.registry,
        journal=root_graph_module._journal,
    )
    monkeypatch.setattr(root_graph_module, "_runtime", restored_runtime)

    def fail_scan(*args, **kwargs):
        raise AssertionError("root frame restore should use session.json")

    monkeypatch.setattr(root_graph_module._journal, "_scan_conversation_frame_paths", fail_scan)

    second_state = _state("task_root_index_restore_002", "sess_restore")
    second_state["context"]["contextId"] = CTX_RESTORE
    second = root_graph_module.route_skill(second_state)

    assert second["active_frame_id"] == first["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"


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
        "contextId": CTX_RECENT,
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

    assert [
        {"role": item["role"], "content": item["content"]}
        for item in captured["runtime_context"]["_runtime_visible_conversation"]
    ] == [
        {"role": "user", "content": "我刚才问了工单 1001"},
        {"role": "assistant", "content": "工单 1001 当前待处理"},
    ]
    assert "_visible_recent_conversation" not in captured["runtime_context"]


def test_root_prompt_uses_bizworker_memory_without_recent_conversation(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    context_id = "bctx_20260521_ab_ctx-memory-root"
    captured: list[list[dict[str, Any]]] = []

    class FakeAgent:
        def run(self, **kwargs):
            captured.append(kwargs["runtime_context"].get("_runtime_visible_conversation", []))
            runtime.submit_persistent_turn_result(
                kwargs["frame_id"],
                "A1",
                {"message": "A1 visible"},
            )
            return []

    monkeypatch.setattr(root_graph_module, "_llm_skill_agent_for_state", lambda current_state: FakeAgent())

    first_state = _state("task_root_memory_001")
    first_state["context"]["contextId"] = context_id
    routed = root_graph_module.route_skill(first_state)
    first_state["active_frame_id"] = routed["active_frame_id"]
    root_graph_module.run_skill(first_state)
    root_graph_module.close_skill_frame(first_state)

    second_state = _state("task_root_memory_002")
    second_state["prompt"] = "U2"
    second_state["context"]["contextId"] = context_id
    routed = root_graph_module.route_skill(second_state)
    second_state["active_frame_id"] = routed["active_frame_id"]
    root_graph_module.run_skill(second_state)

    assert captured[0] == []
    assert [(item["role"], item["content"]) for item in captured[1]] == [
        ("user", "handle the request"),
        ("assistant", "A1 visible"),
    ]


def test_runtime_memory_projection_metadata_uses_direct_child_skill(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    root_graph_module._skill_registry.register(
        SkillManifest(id="ticket_skill", name="Ticket Skill", allowed_tools=[]),
    )
    state = _state("task_root_child_projection_001")
    state["context"]["contextId"] = "bctx_20260521_ab_ctx-child-projection"
    routed = root_graph_module.route_skill(state)
    root_id = routed["active_frame_id"]
    root = runtime.get_frame(root_id)
    assert root is not None

    memory = ContextRuntimeMemory(context_id=root.conversation_id)
    memory.begin_turn(
        task_id=state["task_id"],
        root_frame_id=root.frame_id,
        user_message="U1 create ticket",
    )
    save_to_root_frame(root, memory)
    runtime.save_frame(root)

    child_id = runtime.invoke_child_skill(root_id, "ticket_skill", {"title": "bug"})
    runtime.submit_result(
        child_id,
        "Ticket created.",
        {"status": "FINAL_FOR_USER", "message": "Ticket created."},
    )
    promoted = runtime.complete_child_and_resume_parent(child_id)
    direct_result = root_graph_module._direct_child_result_for_user(promoted)
    assert direct_result is not None

    root = runtime.get_frame(root_id)
    assert root is not None
    runtime.submit_persistent_turn_result(
        root_id,
        direct_result["summary"],
        direct_result["structured_output"],
    )
    root = runtime.get_frame(root_id)
    root_graph_module._commit_root_runtime_memory_turn(
        root,
        state=state,
        assistant_message="Ticket created.",
        structured_output=direct_result["structured_output"],
    )

    restored = ContextRuntimeMemory.load_from_root_frame(runtime.get_frame(root_id))
    assistant = restored.visible_messages[-1]
    assert assistant["content"] == "Ticket created."
    assert assistant["metadata"]["source"] == "skill_result"
    assert assistant["metadata"]["skillId"] == "ticket_skill"
    assert assistant["metadata"]["skillFrameId"] == child_id


def test_runtime_memory_projection_records_non_recoverable_error(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_error_projection_001")
    state["context"]["contextId"] = "bctx_20260521_cd_ctx-error-projection"
    routed = root_graph_module.route_skill(state)
    root = runtime.get_frame(routed["active_frame_id"])
    assert root is not None

    memory = ContextRuntimeMemory(context_id=root.conversation_id)
    memory.begin_turn(
        task_id=state["task_id"],
        root_frame_id=root.frame_id,
        user_message="U1 create ticket",
    )
    save_to_root_frame(root, memory)
    runtime.save_frame(root)

    output = {
        "status": "ERROR",
        "message": "业务函数配置错误：adapter upstream_ref 不合法。",
        "error_category": "CONFIGURATION",
        "recoverable": False,
        "llm_retry_allowed": False,
    }
    runtime.submit_persistent_turn_result(root.frame_id, output["message"], output)
    root = runtime.get_frame(root.frame_id)
    root_graph_module._commit_root_runtime_memory_turn(
        root,
        state=state,
        assistant_message=output["message"],
        structured_output=output,
    )

    restored = ContextRuntimeMemory.load_from_root_frame(runtime.get_frame(root.frame_id))
    assistant = restored.visible_messages[-1]
    assert assistant["content"] == output["message"]
    assert assistant["metadata"]["source"] == "error"
    assert assistant["metadata"]["errorCategory"] == "CONFIGURATION"
    assert assistant["metadata"]["recoverable"] is False


def test_recent_conversation_bootstrap_ignored_after_memory_revision(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    context_id = "bctx_20260521_cd_ctx-bootstrap-root"
    captured: list[list[dict[str, Any]]] = []

    class FakeAgent:
        def run(self, **kwargs):
            captured.append(kwargs["runtime_context"].get("_runtime_visible_conversation", []))
            runtime.submit_persistent_turn_result(
                kwargs["frame_id"],
                "worker-owned A1",
                {"message": "worker-owned A1 visible"},
            )
            return []

    monkeypatch.setattr(root_graph_module, "_llm_skill_agent_for_state", lambda current_state: FakeAgent())

    first_state = _state("task_root_bootstrap_001")
    first_state["context"] = {
        "contextId": context_id,
        "recentConversation": [
            {"role": "user", "content": "external U"},
            {"role": "assistant", "content": "external A"},
        ],
    }
    routed = root_graph_module.route_skill(first_state)
    first_state["active_frame_id"] = routed["active_frame_id"]
    root_graph_module.run_skill(first_state)
    root_graph_module.close_skill_frame(first_state)

    second_state = _state("task_root_bootstrap_002")
    second_state["context"] = {
        "contextId": context_id,
        "recentConversation": [
            {"role": "user", "content": "conflicting external U"},
            {"role": "assistant", "content": "conflicting external A"},
        ],
    }
    routed = root_graph_module.route_skill(second_state)
    second_state["active_frame_id"] = routed["active_frame_id"]
    root_graph_module.run_skill(second_state)

    assert [(item["role"], item["content"]) for item in captured[0]] == [
        ("user", "external U"),
        ("assistant", "external A"),
    ]
    assert "conflicting external U" not in [item["content"] for item in captured[1]]
    assert ("assistant", "worker-owned A1 visible") in [
        (item["role"], item["content"]) for item in captured[1]
    ]


def test_direct_run_skill_running_loop_returns_busy(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_busy_001")
    state["context"]["contextId"] = "bctx_20260521_ef_ctx-busy-root"
    routed = root_graph_module.route_skill(state)
    state["active_frame_id"] = routed["active_frame_id"]
    frame = runtime.get_frame(routed["active_frame_id"])
    assert frame is not None

    memory = ContextRuntimeMemory(context_id=frame.conversation_id)
    memory.begin_turn(
        task_id="task_already_running",
        root_frame_id=frame.frame_id,
        user_message="running",
    )
    save_to_root_frame(frame, memory)
    runtime.save_frame(frame)

    class FakeAgent:
        def run(self, **kwargs):
            raise AssertionError("busy context must not enter the root LLM loop")

    monkeypatch.setattr(root_graph_module, "_llm_skill_agent_for_state", lambda current_state: FakeAgent())

    result = root_graph_module.run_skill(state)

    assert result["events"][0].type == "error"
    assert result["events"][0].payload["code"] == "CONTEXT_RUNTIME_BUSY"


def test_enqueue_pending_user_input_for_context_queues_running_root(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    context_id = "bctx_20260521_ef_ctx-queued-root"
    state = _state("task_root_queue_001")
    state["context"]["contextId"] = context_id
    routed = root_graph_module.route_skill(state)
    frame = runtime.get_frame(routed["active_frame_id"])
    assert frame is not None

    memory = ContextRuntimeMemory(context_id=frame.conversation_id)
    memory.begin_turn(
        task_id="task_root_queue_001",
        root_frame_id=frame.frame_id,
        user_message="U1",
    )
    save_to_root_frame(frame, memory)
    runtime.save_frame(frame)

    event = root_graph_module.enqueue_pending_user_input_for_context(
        context_id,
        task_id="task_root_queue_002",
        prompt="U2 while running",
    )

    restored = ContextRuntimeMemory.load_from_root_frame(runtime.get_frame(frame.frame_id))
    assert event.type == "system"
    assert event.payload["code"] == "CONTEXT_RUNTIME_QUEUED"
    assert [item["content"] for item in restored.pending_user_inputs] == ["U2 while running"]


def test_enqueue_pending_user_input_rejects_idle_memory(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    context_id = "bctx_20260521_ef_ctx-idle-root"
    state = _state("task_root_queue_idle_001")
    state["context"]["contextId"] = context_id
    routed = root_graph_module.route_skill(state)
    frame = runtime.get_frame(routed["active_frame_id"])
    assert frame is not None

    memory = ContextRuntimeMemory(context_id=frame.conversation_id)
    save_to_root_frame(frame, memory)
    runtime.save_frame(frame)

    event = root_graph_module.enqueue_pending_user_input_for_context(
        context_id,
        task_id="task_root_queue_idle_002",
        prompt="U2 after loop closed",
    )

    restored = ContextRuntimeMemory.load_from_root_frame(runtime.get_frame(frame.frame_id))
    assert event.type == "error"
    assert event.payload["code"] == "CONTEXT_RUNTIME_BUSY"
    assert event.payload["retryable"] is True
    assert restored.pending_user_inputs == []


def test_enqueue_pending_user_input_rejects_finalizing_memory(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    context_id = "bctx_20260521_ef_ctx-finalizing-root"
    state = _state("task_root_queue_finalizing_001")
    state["context"]["contextId"] = context_id
    routed = root_graph_module.route_skill(state)
    frame = runtime.get_frame(routed["active_frame_id"])
    assert frame is not None

    memory = ContextRuntimeMemory(context_id=frame.conversation_id)
    memory.begin_turn(
        task_id="task_root_queue_finalizing_001",
        root_frame_id=frame.frame_id,
        user_message="U1",
    )
    assert memory.mark_finalizing()
    save_to_root_frame(frame, memory)
    runtime.save_frame(frame)

    event = root_graph_module.enqueue_pending_user_input_for_context(
        context_id,
        task_id="task_root_queue_finalizing_002",
        prompt="U2 while submit is closing",
    )

    restored = ContextRuntimeMemory.load_from_root_frame(runtime.get_frame(frame.frame_id))
    assert event.type == "error"
    assert event.payload["code"] == "CONTEXT_RUNTIME_BUSY"
    assert event.payload["retryable"] is True
    assert restored.pending_user_inputs == []


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
        "contextId": CTX_PROMPT,
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
    assert f'"contextId": "{CTX_PROMPT}"' in human_prompt
    assert "recentConversation" not in human_prompt.split("\nContext:", 1)[1]
