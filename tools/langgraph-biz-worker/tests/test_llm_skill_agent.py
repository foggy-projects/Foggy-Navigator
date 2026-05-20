"""Tests for the LLM-driven Skill tool-call loop."""

from __future__ import annotations

import json
import time

from langchain_core.messages import AIMessage

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillManifest
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_call_guard import reset_llm_call_guard_state_for_tests
from langgraph_biz_worker.runtime.llm_skill_agent import LlmSkillAgent
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


class FakeToolCallModel:
    def __init__(self, responses: list[AIMessage]) -> None:
        self.responses = responses
        self.calls = 0
        self.bound_tools = []
        self.seen_messages = []

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    def invoke(self, messages):
        self.seen_messages.append(list(messages))
        response = self.responses[self.calls]
        self.calls += 1
        return response


class FailingModel:
    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        raise RuntimeError("upstream model timed out")


class HangingModel:
    def __init__(self, sleep_seconds: float = 0.2) -> None:
        self.sleep_seconds = sleep_seconds

    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        time.sleep(self.sleep_seconds)
        return AIMessage(content="")


class TransientTimeoutModel:
    def __init__(self) -> None:
        self.calls = 0

    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        self.calls += 1
        if self.calls == 1:
            raise TimeoutError("temporary model timeout")
        return AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Recovered after retry.",
                "structured_output": {
                    "classification": "other",
                    "recommended_action": "ignore",
                    "confidence": 0.5,
                },
                "evidence_refs": ["retry:test"],
            },
        }])


class RootChildTimeoutModel:
    def __init__(self) -> None:
        self.calls = 0

    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        self.calls += 1
        if self.calls == 1:
            return AIMessage(content="", tool_calls=[{
                "id": "call_child",
                "name": "invoke_business_skill",
                "args": {
                    "skill_id": "child_skill",
                    "instruction": "Handle the ticket request.",
                },
            }])
        time.sleep(0.2)
        return AIMessage(content="")


class RecoverableChildTimeoutThenContinueModel:
    def __init__(self) -> None:
        self.calls = 0

    def bind_tools(self, tools):
        return self

    def invoke(self, messages):
        self.calls += 1
        if self.calls == 1:
            return AIMessage(content="", tool_calls=[{
                "id": "call_child",
                "name": "invoke_business_skill",
                "args": {
                    "skill_id": "child_skill",
                    "instruction": "Handle the ticket request.",
                },
            }])
        if self.calls == 2:
            time.sleep(0.2)
            return AIMessage(content="")
        if self.calls == 3:
            return AIMessage(content="", tool_calls=[{
                "id": "call_resume_child",
                "name": "resume_recoverable_child_skill",
                "args": {"instruction": "继续完成失败的子技能"},
            }])
        if self.calls == 4:
            return AIMessage(content="", tool_calls=[{
                "id": "call_child_submit",
                "name": "submit_skill_result",
                "args": {
                    "summary": "Child completed after timeout retry.",
                    "structured_output": {"child_continued": True},
                },
            }])
        return AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Root completed after failed child resume.",
                "structured_output": {"root_continued_child": True},
            },
        }])


def _manifest() -> SkillManifest:
    return SkillManifest(
        id="exception_triage",
        name="exception_triage",
        description="Analyze order exception.",
        output_schema={
            "type": "object",
            "required": ["classification", "recommended_action", "confidence"],
            "properties": {
                "classification": {
                    "type": "string",
                    "enum": ["vehicle_delay", "weather", "system_error", "other"],
                },
                "recommended_action": {
                    "type": "string",
                    "enum": ["manual_dispatch", "auto_retry", "escalate", "ignore"],
                },
                "confidence": {"type": "number", "minimum": 0, "maximum": 1},
            },
        },
        allowed_tools=["mock_get_order", "mock_get_vehicle_status", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output", "evidence_refs"],
        business_rules={"require_evidence": True, "min_evidence_count": 1},
    )


def _runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(_manifest())
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


def _artifact_runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="artifact_skill",
        name="artifact_skill",
        description="Create an artifact.",
        output_schema={"type": "object"},
        allowed_tools=["create_artifact", "read_artifact", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output", "artifact_refs"],
    ))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


def _attachment_runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="attachment_skill",
        name="attachment_skill",
        description="Analyze an attachment.",
        output_schema={"type": "object"},
        allowed_tools=["analyze_attachment", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output", "evidence_refs"],
    ))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


def _root_runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="system.root",
        name="system.root",
        description="Persistent root skill.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        visibility="builtin",
    ))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


def _root_with_child_runtime(child_context_visibility: str = "isolated", data_root=None) -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="system.root",
        name="system.root",
        description="Persistent root skill.",
        output_schema={"type": "object"},
        allowed_tools=["invoke_business_skill", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        visibility="builtin",
        context_visibility="passthrough",
    ))
    registry.register(SkillManifest(
        id="child_skill",
        name="child_skill",
        description="Child skill.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        context_visibility=child_context_visibility,
    ))
    return SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=FileFrameJournal(data_root) if data_root is not None else None,
    )


def test_llm_agent_completes_skill_via_submit_tool():
    runtime = _runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_llm_agent_001",
        skill_id="exception_triage",
        skill_input={"order_id": "ORD-LLM-001"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_order",
            "name": "mock_get_order",
            "args": {"order_id": "ORD-LLM-001"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_vehicle",
            "name": "mock_get_vehicle_status",
            "args": {"vehicle_id": "V09"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Order diagnosed as vehicle_delay.",
                "structured_output": {
                    "classification": "vehicle_delay",
                    "recommended_action": "manual_dispatch",
                    "confidence": 0.92,
                },
                "evidence_refs": ["order:ORD-LLM-001", "vehicle:V09"],
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_llm_agent_001",
        frame_id=frame_id,
        prompt="分析订单异常",
    )

    frame = runtime.get_frame(frame_id)
    assert frame.status == FrameStatus.COMPLETED
    assert frame.output["classification"] == "vehicle_delay"
    assert "submit_skill_result" in {t["function"]["name"] for t in model.bound_tools}
    assert events[-1].type == "skill_result_submit"

    promoted = runtime.close_frame(frame_id)
    assert promoted["structured_output"]["recommended_action"] == "manual_dispatch"
    assert runtime.get_frame(frame_id).private_messages == []


def test_llm_agent_times_out_hung_model_and_fails_frame():
    reset_llm_call_guard_state_for_tests()
    runtime = _runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_llm_timeout_001",
        skill_id="exception_triage",
        skill_input={"order_id": "ORD-TIMEOUT-001"},
    )

    events = LlmSkillAgent(HangingModel(), runtime).run(
        task_id="task_llm_timeout_001",
        frame_id=frame_id,
        prompt="analyze order",
        runtime_context={
            "llm_request_timeout_seconds": 0.02,
            "llm_max_retries": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    frame = runtime.get_frame(frame_id)
    assert frame.status == FrameStatus.FAILED
    assert "LLM_REQUEST_TIMEOUT" in frame.private_working_state["fail_reason"]
    assert events[0].type == "error"
    assert "LLM_REQUEST_TIMEOUT" in events[0].error


def test_llm_agent_retries_transient_timeout_and_completes_frame():
    reset_llm_call_guard_state_for_tests()
    runtime = _runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_llm_retry_001",
        skill_id="exception_triage",
        skill_input={"order_id": "ORD-RETRY-001"},
    )
    model = TransientTimeoutModel()

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_llm_retry_001",
        frame_id=frame_id,
        prompt="analyze order",
        runtime_context={
            "llm_request_timeout_seconds": 1,
            "llm_max_retries": 1,
            "llm_retry_backoff_seconds": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    frame = runtime.get_frame(frame_id)
    assert model.calls == 2
    assert frame.status == FrameStatus.COMPLETED
    assert events[-1].type == "skill_result_submit"


def test_persistent_root_child_timeout_records_recoverable_interruption(tmp_path):
    reset_llm_call_guard_state_for_tests()
    runtime = _root_with_child_runtime(data_root=tmp_path / "data")
    frame_id = runtime.invoke_skill(
        task_id="task_root_child_timeout_001",
        skill_id="system.root",
        skill_input={},
        conversation_id="bctx_20260520_ab_sess_child_timeout",
        session_id="sess_child_timeout",
    )

    events = LlmSkillAgent(RootChildTimeoutModel(), runtime).run(
        task_id="task_root_child_timeout_001",
        frame_id=frame_id,
        prompt="create a ticket",
        persistent_frame=True,
        runtime_context={
            "llm_request_timeout_seconds": 0.02,
            "llm_max_retries": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    root = runtime.get_frame(frame_id)
    child = runtime.get_frame(root.child_frame_ids[-1])
    assert child.status == FrameStatus.FAILED
    assert root.status == FrameStatus.RUNNING
    assert root.private_working_state["continuation_state"] == "INTERRUPTED"
    assert root.private_working_state["interrupt_reason"] == "child_skill_failed"
    assert root.private_working_state["pending_recoverable_child_frame_id"] == child.frame_id
    assert child.private_working_state["continuation_state"] == "INTERRUPTED"
    assert any(event.type == "error" and "LLM_REQUEST_TIMEOUT" in event.error for event in events)


def test_persistent_root_continue_reopens_failed_child_frame_after_timeout(tmp_path):
    reset_llm_call_guard_state_for_tests()
    runtime = _root_with_child_runtime(data_root=tmp_path / "data")
    root_frame_id = runtime.invoke_skill(
        task_id="task_root_failed_child_continue_001",
        skill_id="system.root",
        skill_input={},
        conversation_id="bctx_20260520_cd_sess_failed_child_continue",
        session_id="sess_failed_child_continue",
    )
    model = RecoverableChildTimeoutThenContinueModel()
    agent = LlmSkillAgent(model, runtime)

    first_events = agent.run(
        task_id="task_root_failed_child_continue_001",
        frame_id=root_frame_id,
        prompt="create a ticket",
        persistent_frame=True,
        runtime_context={
            "llm_request_timeout_seconds": 0.02,
            "llm_max_retries": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    root = runtime.get_frame(root_frame_id)
    child_frame_id = root.child_frame_ids[-1]
    child = runtime.get_frame(child_frame_id)
    assert child.status == FrameStatus.FAILED
    assert root.status == FrameStatus.RUNNING
    assert root.private_working_state["pending_recoverable_child_frame_id"] == child_frame_id
    assert child.private_working_state["recoverable"] is True
    assert any(event.type == "error" and "LLM_REQUEST_TIMEOUT" in event.error for event in first_events)

    time.sleep(0.25)
    second_events = agent.run(
        task_id="task_root_failed_child_continue_002",
        frame_id=root_frame_id,
        prompt="继续",
        persistent_frame=True,
        runtime_context={
            "llm_request_timeout_seconds": 1,
            "llm_max_retries": 0,
            "llm_circuit_failure_threshold": 99,
        },
    )

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    assert child.status == FrameStatus.COMPLETED
    assert root.status == FrameStatus.RUNNING
    assert root.output == {"root_continued_child": True}
    assert child_frame_id in root.private_working_state["child_results"]
    assert "pending_recoverable_child_frame_id" not in root.private_working_state
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert any(
        event.type == "skill_frame_open"
        and event.skill_frame_id == child_frame_id
        and event.content == "Resuming frame for skill: child_skill"
        for event in second_events
    )
    assert any(
        event.tool_name == "resume_recoverable_child_skill"
        and '"intent_resolution": "CONTINUE_PREVIOUS"' in event.content
        for event in second_events
    )


def test_llm_agent_analyzes_attachment_with_vision_config(monkeypatch):
    runtime = _attachment_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_attachment_001",
        skill_id="attachment_skill",
        skill_input={},
    )

    class FakeVisionModel:
        def invoke(self, messages):
            return AIMessage(content=json.dumps({
                "summary": "damaged pallet visible",
                "extracted_text": "FRAGILE",
                "extracted_fields": {"exception_type": "cargo_damage"},
                "confidence": 0.88,
                "warnings": [],
            }))

    monkeypatch.setattr(
        "langgraph_biz_worker.tools.attachment_analysis.create_chat_model_from_config",
        lambda config: FakeVisionModel(),
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_analyze",
            "name": "analyze_attachment",
            "args": {
                "attachment_id": "att-1",
                "purpose": "Extract exception details.",
                "expected_fields": ["exception_type"],
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Attachment analyzed.",
                "structured_output": {"exception_type": "cargo_damage"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_attachment_001",
        frame_id=frame_id,
        prompt="Look at the image and create an exception summary.",
        runtime_context={
            "vision_llm_config": {"provider": "openai", "model": "vision-model"},
            "attachments": [{
                "id": "att-1",
                "kind": "image",
                "mimeType": "image/png",
                "url": "https://example.test/files/att-1.png?signature=secret",
            }],
        },
    )

    analyze_result = next(
        event for event in events
        if event.type == "tool_result" and event.tool_name == "analyze_attachment"
    )
    payload = json.loads(analyze_result.content)
    assert payload["ok"] is True
    assert payload["summary"] == "damaged pallet visible"
    assert payload["extracted_fields"]["exception_type"] == "cargo_damage"
    assert runtime.get_frame(frame_id).status == FrameStatus.COMPLETED


def test_llm_agent_attachment_analysis_falls_back_to_reasoning_config(monkeypatch):
    runtime = _attachment_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_attachment_reasoning_fallback_001",
        skill_id="attachment_skill",
        skill_input={},
    )

    class FakeReasoningVisionModel:
        def invoke(self, messages):
            return AIMessage(content=json.dumps({
                "summary": "fallback model saw the image",
                "extracted_text": "",
                "extracted_fields": {"has_exception": True},
                "confidence": 0.7,
                "warnings": [],
            }))

    monkeypatch.setattr(
        "langgraph_biz_worker.tools.attachment_analysis.create_chat_model_from_config",
        lambda config: FakeReasoningVisionModel(),
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_analyze",
            "name": "analyze_attachment",
            "args": {
                "attachment_id": "att-1",
                "purpose": "Inspect the image.",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Image analyzed.",
                "structured_output": {"has_exception": True},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_attachment_reasoning_fallback_001",
        frame_id=frame_id,
        prompt="Look at the image.",
        runtime_context={
            "llm_config": {"provider": "openai", "model": "multimodal-reasoning-model"},
            "attachments": [{
                "id": "att-1",
                "kind": "image",
                "mimeType": "image/png",
                "url": "https://example.test/files/att-1.png",
            }],
        },
    )

    analyze_result = next(
        event for event in events
        if event.type == "tool_result" and event.tool_name == "analyze_attachment"
    )
    payload = json.loads(analyze_result.content)
    assert payload["ok"] is True
    assert payload["model_source"] == "reasoning_fallback"
    assert payload["summary"] == "fallback model saw the image"


def test_llm_agent_attachment_analysis_requires_some_model_config():
    runtime = _attachment_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_attachment_no_model_001",
        skill_id="attachment_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_analyze",
            "name": "analyze_attachment",
            "args": {
                "attachment_id": "att-1",
                "purpose": "Inspect the image.",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Model missing.",
                "structured_output": {"status": "missing_model"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_attachment_no_model_001",
        frame_id=frame_id,
        prompt="Look at the image.",
        runtime_context={
            "attachments": [{
                "id": "att-1",
                "kind": "image",
                "mimeType": "image/png",
                "url": "https://example.test/files/att-1.png",
            }],
        },
    )

    analyze_result = next(
        event for event in events
        if event.type == "tool_result" and event.tool_name == "analyze_attachment"
    )
    payload = json.loads(analyze_result.content)
    assert payload["ok"] is False
    assert "MODEL_NOT_CONFIGURED" in payload["error"]


def test_llm_agent_persistent_frame_submit_keeps_frame_running():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_agent_001",
        skill_id="system.root",
        skill_input={"request": "answer"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Root turn done.",
                "structured_output": {"answer": "ok"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_agent_001",
        frame_id=frame_id,
        prompt="answer",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.status == FrameStatus.RUNNING
    assert frame.result_summary == "Root turn done."
    assert frame.output == {"answer": "ok"}
    assert frame.private_working_state["turn_results"][0]["summary"] == "Root turn done."
    assert events[-1].type == "skill_result_submit"


def test_llm_agent_persistent_frame_submit_persists_active_plan():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_plan_001",
        skill_id="system.root",
        skill_input={"request": "multi-step work"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Plan created.",
                "structured_output": {
                    "answer": "ok",
                    "active_plan": {
                        "goal": "handle multi-step work",
                        "status": "IN_PROGRESS",
                        "steps": [
                            {"id": "1", "status": "DONE", "summary": "triage"},
                            {"id": "2", "status": "PENDING", "summary": "execute"},
                        ],
                    },
                },
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_plan_001",
        frame_id=frame_id,
        prompt="multi-step work",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.private_working_state["active_plan"]["goal"] == "handle multi-step work"
    assert frame.private_working_state["root_context_summary"]["active_plan"]["status"] == "IN_PROGRESS"


def test_llm_agent_persistent_frame_prompt_includes_active_plan():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_plan_prompt_001",
        skill_id="system.root",
        skill_input={"request": "continue plan"},
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state["active_plan"] = {
        "goal": "deliver complex task",
        "status": "IN_PROGRESS",
        "steps": [{"id": "step-1", "status": "DONE"}],
    }
    runtime.store.save(frame)
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Plan continued.",
                "structured_output": {"answer": "ok"},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_plan_prompt_002",
        frame_id=frame_id,
        prompt="继续",
        persistent_frame=True,
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Active task plan:" in user_prompt
    assert "deliver complex task" in user_prompt
    assert "step-1" in user_prompt
    assert "Persistent root planning policy:" in user_prompt
    assert "submit_skill_result.structured_output.active_plan" in user_prompt
    assert "intent_resolution" in user_prompt


def test_llm_agent_prompt_includes_visible_recent_conversation():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_recent_prompt_001",
        skill_id="system.root",
        skill_input={"contextId": "bctx_20260520_ef_ctx-recent"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Used recent conversation.",
                "structured_output": {"answer": "ok"},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_recent_prompt_002",
        frame_id=frame_id,
        prompt="我上一个消息是什么",
        runtime_context={
            "_visible_recent_conversation": [
                {"role": "user", "content": "我刚才问了工单 1001"},
                {"role": "assistant", "content": "工单 1001 当前待处理"},
            ],
        },
        persistent_frame=True,
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Recent conversation before this turn:" in user_prompt
    assert "user: 我刚才问了工单 1001" in user_prompt
    assert "assistant: 工单 1001 当前待处理" in user_prompt
    assert "User request: 我上一个消息是什么" in user_prompt


def test_llm_agent_persistent_frame_prompt_includes_recoverable_interruption_context():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_continue_001",
        skill_id="system.root",
        skill_input={"request": "create vehicle"},
        conversation_id="bctx_20260520_12_sess_continue",
        session_id="sess_continue",
    )
    runtime.record_recoverable_interruption(
        frame_id,
        reason="model_error",
        error="upstream model timed out",
        task_id="task_root_continue_001",
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state.setdefault("root_context_summary", {})["latest_child_result_summary"] = {
        "frame_id": "frm_child_waiting",
        "skill_id": "ticket_skill",
        "frame_status": "COMPLETED",
        "status": "WAITING_USER",
        "next_step": "请回复工单类型、标题及详细描述。",
        "missing_fields": ["ticket_type", "title", "description"],
    }
    runtime.store.save(frame)
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Continued from interruption.",
                "structured_output": {"continued": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_continue_002",
        frame_id=frame_id,
        prompt="继续",
        persistent_frame=True,
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Previous execution was interrupted." in user_prompt
    assert "Reason: model_error" in user_prompt
    assert "upstream model timed out" in user_prompt
    assert "Continuation summary from promoted child result:" in user_prompt
    assert "WAITING_USER" in user_prompt
    assert "请回复工单类型、标题及详细描述。" in user_prompt
    assert "User's new instruction: 继续" in user_prompt
    assert "recoverable candidate, not a mandatory continuation" in user_prompt
    assert "Recoverable focus:" in user_prompt
    assert "Recoverable focus stack:" in user_prompt
    assert "CONTINUE_PREVIOUS" in user_prompt
    assert "ASK_CLARIFICATION" in user_prompt
    assert "START_UNRELATED_NEW_TASK" in user_prompt
    assert "intent_resolution" in user_prompt
    assert "abandoned_interruption" in user_prompt
    assert "shelve_interrupted_frame" in user_prompt


def test_llm_agent_prompt_includes_frame_result_contract():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_contract_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_34_sess_root_contract",
        session_id="sess_root_contract",
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_contract_001",
        frame_id=frame_id,
        prompt="继续",
        persistent_frame=True,
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Frame result contract:" in user_prompt
    assert "primary business-decision context" in user_prompt
    assert "Do not call read_frame_execution_report after a normal child completion" in user_prompt


def test_llm_agent_persistent_frame_prompt_includes_pending_recoverable_child():
    runtime = _root_with_child_runtime()
    root_frame_id = runtime.invoke_skill(
        task_id="task_root_child_prompt_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_56_sess_child_prompt",
        session_id="sess_child_prompt",
    )
    child_frame_id = runtime.invoke_child_skill(root_frame_id, "child_skill", {"order_id": "ORD-1"})
    runtime.record_recoverable_child_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_child_prompt_001",
    )
    runtime.record_recoverable_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_child_prompt_001",
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Asked for clarification.",
                "structured_output": {"needs_clarification": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_child_prompt_002",
        frame_id=root_frame_id,
        prompt="继续",
        persistent_frame=True,
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Pending child skill:" in user_prompt
    assert "Recoverable focus:" in user_prompt
    assert "Recoverable focus stack:" in user_prompt
    assert child_frame_id in user_prompt
    assert "child_skill" in user_prompt
    assert "resume_recoverable_child_skill" in user_prompt


def test_llm_agent_persistent_root_exposes_shelve_interrupted_frame_tool():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_shelve_tools_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_78_sess_root_shelve_tools",
        session_id="sess_root_shelve_tools",
    )
    runtime.record_recoverable_interruption(
        frame_id,
        reason="user_cancelled",
        error="Cancelled by user",
        task_id="task_root_shelve_tools_001",
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state["active_plan"] = {
        "goal": "create vehicle",
        "status": "IN_PROGRESS",
    }
    runtime.store.save(frame)
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_shelve",
            "name": "shelve_interrupted_frame",
            "args": {
                "summary": "Previous vehicle task was shelved.",
                "decision": "START_UNRELATED_NEW_TASK",
                "abandoned_interruption": {
                    "summary": "Vehicle creation was cancelled before completion.",
                },
                "new_task": {"type": "lookup"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_shelve_tools_002",
        frame_id=frame_id,
        prompt="帮我查订单列表",
        persistent_frame=True,
    )

    bound_tool_names = {tool["function"]["name"] for tool in model.bound_tools}
    frame = runtime.get_frame(frame_id)
    assert "shelve_interrupted_frame" in bound_tool_names
    assert "resume_recoverable_child_skill" in bound_tool_names
    assert events[-1].tool_name == "shelve_interrupted_frame"
    assert frame.status == FrameStatus.RUNNING
    assert frame.result_summary == "Previous vehicle task was shelved."
    assert frame.output["continuation_decision"] == "START_UNRELATED_NEW_TASK"
    assert frame.output["intent_resolution"] == "START_UNRELATED_NEW_TASK"
    assert "continuation_state" not in frame.private_working_state
    history = frame.private_working_state["root_context_summary"]["interruption_history"]
    assert history[-1]["resolution"] == "START_UNRELATED_NEW_TASK"
    assert history[-1]["abandoned_interruption"] == {
        "summary": "Vehicle creation was cancelled before completion.",
    }
    assert "active_plan" not in frame.private_working_state
    plan_history = frame.private_working_state["root_context_summary"]["plan_history"]
    assert plan_history[-1]["resolution"] == "START_UNRELATED_NEW_TASK"
    assert plan_history[-1]["plan"]["goal"] == "create vehicle"


def test_llm_agent_root_resumes_pending_recoverable_child_frame():
    runtime = _root_with_child_runtime()
    root_frame_id = runtime.invoke_skill(
        task_id="task_root_resume_child_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_9a_sess_resume_child",
        session_id="sess_resume_child",
    )
    child_frame_id = runtime.invoke_child_skill(
        root_frame_id,
        "child_skill",
        {"order_id": "ORD-1"},
    )
    runtime.record_recoverable_child_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_resume_child_001",
    )
    runtime.record_recoverable_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_resume_child_001",
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_resume_child",
            "name": "resume_recoverable_child_skill",
            "args": {"instruction": "继续完成子技能"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Child completed after resume.",
                "structured_output": {"child_continued": True},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Root completed after child resume.",
                "structured_output": {"root_continued_child": True},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_resume_child_002",
        frame_id=root_frame_id,
        prompt="继续",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    assert root is not None
    assert child is not None
    assert root.status == FrameStatus.RUNNING
    assert child.status == FrameStatus.COMPLETED
    assert root.output == {"root_continued_child": True}
    assert child_frame_id in root.private_working_state["child_results"]
    assert "pending_recoverable_child_frame_id" not in root.private_working_state
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert any(event.tool_name == "resume_recoverable_child_skill" for event in events)
    assert any(
        event.tool_name == "resume_recoverable_child_skill"
        and '"intent_resolution": "CONTINUE_PREVIOUS"' in event.content
        for event in events
    )
    assert any(
        event.type == "skill_frame_open"
        and event.skill_frame_id == child_frame_id
        and event.content == "Resuming frame for skill: child_skill"
        for event in events
    )


def test_llm_agent_shelve_clears_pending_recoverable_child_frame():
    runtime = _root_with_child_runtime()
    root_frame_id = runtime.invoke_skill(
        task_id="task_root_shelve_child_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_bc_sess_shelve_child",
        session_id="sess_shelve_child",
    )
    child_frame_id = runtime.invoke_child_skill(root_frame_id, "child_skill", {"order_id": "ORD-2"})
    runtime.record_recoverable_child_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_shelve_child_001",
    )
    runtime.record_recoverable_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_shelve_child_001",
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_shelve",
            "name": "shelve_interrupted_frame",
            "args": {
                "summary": "Shelved child work.",
                "decision": "START_UNRELATED_NEW_TASK",
                "abandoned_interruption": {"summary": "Child work was cancelled."},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_shelve_child_002",
        frame_id=root_frame_id,
        prompt="查新订单",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_frame_id)
    child = runtime.get_frame(child_frame_id)
    assert root is not None
    assert child is not None
    assert "pending_recoverable_child_frame_id" not in root.private_working_state
    assert "pending_recoverable_child" not in root.private_working_state
    assert "recoverable_focus_frame_id" not in root.private_working_state
    assert child.status == FrameStatus.CANCELLED
    assert child.private_working_state["continuation_state"] == "SHELVED"


def test_llm_agent_ask_clarification_keeps_recoverable_focus():
    runtime = _root_with_child_runtime()
    root_frame_id = runtime.invoke_skill(
        task_id="task_root_clarify_child_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_de_sess_clarify_child",
        session_id="sess_clarify_child",
    )
    child_frame_id = runtime.invoke_child_skill(root_frame_id, "child_skill", {"order_id": "ORD-3"})
    runtime.record_recoverable_child_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_clarify_child_001",
    )
    runtime.record_recoverable_interruption(
        root_frame_id,
        reason="user_cancelled",
        error="Cancelled during child",
        task_id="task_root_clarify_child_001",
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Need clarification before continuing.",
                "structured_output": {"intent_resolution": "ASK_CLARIFICATION"},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_root_clarify_child_002",
        frame_id=root_frame_id,
        prompt="随便吧",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_frame_id)
    assert root is not None
    assert root.private_working_state["continuation_state"] == "INTERRUPTED"
    assert root.private_working_state["pending_recoverable_child_frame_id"] == child_frame_id
    assert root.private_working_state["recoverable_focus_frame_id"] == child_frame_id


def test_llm_agent_non_persistent_frame_does_not_expose_shelve_tool():
    runtime = _runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_regular_skill_001",
        skill_id="exception_triage",
        skill_input={"order_id": "ORD-LLM-001"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "done",
                "structured_output": {
                    "classification": "other",
                    "recommended_action": "ignore",
                    "confidence": 0.9,
                },
                "evidence_refs": ["ev_1"],
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_regular_skill_001",
        frame_id=frame_id,
        prompt="triage",
    )

    bound_tool_names = {tool["function"]["name"] for tool in model.bound_tools}
    assert "shelve_interrupted_frame" not in bound_tool_names


def test_llm_agent_persistent_frame_model_error_records_recoverable_interruption():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_model_error_001",
        skill_id="system.root",
        skill_input={"request": "create vehicle"},
        conversation_id="bctx_20260520_f0_sess_model_error",
        session_id="sess_model_error",
    )

    events = LlmSkillAgent(FailingModel(), runtime).run(
        task_id="task_root_model_error_001",
        frame_id=frame_id,
        prompt="创建车辆",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.status == FrameStatus.RUNNING
    assert events[0].type == "error"
    assert frame.private_working_state["continuation_state"] == "INTERRUPTED"
    assert frame.private_working_state["interrupt_reason"] == "model_error"
    assert frame.private_working_state["last_error"] == "upstream model timed out"
    assert frame.private_working_state["recoverable"] is True


def test_llm_agent_root_skill_can_invoke_business_function_directly(monkeypatch):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_function_001",
        skill_id="system.root",
        skill_input={"request": "close order"},
    )

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        return {
            "functionId": function_id,
            "version": version,
            "status": "SUCCESS",
            "outputCode": "OK",
            "input": input_data,
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_function",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.order.close",
                "version": "v1",
                "input": {"orderNo": "ORD-ROOT-001"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Root function turn done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_function_001",
        frame_id=frame_id,
        prompt="close order",
        runtime_context={"task_scoped_token": "runtime-token"},
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    function_frames = [
        child for child in runtime.get_frames_by_task("task_root_function_001")
        if child.frame_kind == FrameKind.FUNCTION_CALL
    ]
    bound_tool_names = {tool["function"]["name"] for tool in model.bound_tools}

    assert frame.status == FrameStatus.RUNNING
    assert frame.result_summary == "Root function turn done."
    assert "invoke_business_function" in bound_tool_names
    assert len(function_frames) == 1
    assert function_frames[0].parent_frame_id == frame_id
    assert function_frames[0].output["functionId"] == "tms.order.close"
    assert any(event.tool_name == "invoke_business_function" for event in events)


def test_llm_agent_records_submitted_summary_without_backend_rewrite(monkeypatch):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_tms_draft_001",
        skill_id="system.root",
        skill_input={"request": "create opening draft"},
    )
    draft_id = "afd_9eadcfa5c92b492a88be6f5e583b1271"
    structured_output = {
        "type": "OPEN_TMS_PAGE",
        "label": "去下单",
        "routeName": "OrderWorkbench",
        "query": {"aiDraftId": draft_id},
    }

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        return {
            "summary": "已生成开单草稿，可点击打开补齐并确认。",
            "draftId": draft_id,
            "structured_output": structured_output,
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_function",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.order.createOpeningDraft",
                "version": "v1",
                "input": {"phone": "18911897361", "weight": "100KG"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "订单创建成功！订单号：frm_622f67035042",
                "structured_output": structured_output,
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_tms_draft_001",
        frame_id=frame_id,
        prompt="创建开单草稿",
        runtime_context={"task_scoped_token": "runtime-token"},
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.result_summary == "订单创建成功！订单号：frm_622f67035042"
    assert draft_id not in frame.result_summary
    assert frame.output == structured_output


def test_llm_agent_allows_explicit_order_number_field_in_final_summary():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_order_no_001",
        skill_id="system.root",
        skill_input={"request": "query order"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "订单号 EO202605160001，当前状态：待揽收。",
                "structured_output": {
                    "orderNo": "EO202605160001",
                    "orderStatusText": "待揽收",
                },
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_order_no_001",
        frame_id=frame_id,
        prompt="查询订单",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.result_summary == "订单号 EO202605160001，当前状态：待揽收。"


def test_llm_agent_keeps_pending_info_summary_that_requests_order_number():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_ticket_pending_info_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    expected_summary = (
        "已收到您的工单提交请求。为了帮您准确创建工单，请补充以下信息：\n"
        "1. **工单类型**：请选择“运单异常件”或“平台反馈”。\n"
        "2. **工单标题**：请简要概括问题。\n"
        "3. **问题描述**：请详细说明具体情况。\n"
        "4. **运单号**：如果是运单异常件，请提供对应的运单号。"
    )
    model = FakeToolCallModel([
        AIMessage(content="我可以帮您提交工单，请提供工单类型、标题、问题描述和运单号。"),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": expected_summary,
                "structured_output": {
                    "next_step": "WAITING_FOR_USER_INPUT",
                    "required_fields": [
                        "ticket_type",
                        "title",
                        "summary",
                        "orderIdentifier (if order exception)",
                    ],
                    "status": "PENDING_INFO",
                },
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_ticket_pending_info_001",
        frame_id=frame_id,
        prompt="你可以帮我提交工单吗",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert [event.type for event in events] == ["tool_use", "skill_result_submit"]
    assert model.calls == 2
    assert frame.result_summary == expected_summary
    assert frame.private_working_state["turn_results"][-1]["summary"] == expected_summary


def test_llm_agent_child_waiting_for_user_input_keeps_frame_open():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_waiting_user_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    child_id = runtime.invoke_child_skill(root_id, "child_skill")
    prompt = "请选择工单类型。"
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit_wait",
            "name": "submit_skill_result",
            "args": {
                "summary": prompt,
                "structured_output": {
                    "turn_status": "WAITING_FOR_USER_INPUT",
                    "user_message": prompt,
                    "required_fields": ["ticket_type"],
                },
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_waiting_user_001",
        frame_id=child_id,
        prompt="创建工单",
    )

    child = runtime.get_frame(child_id)
    assert [event.type for event in events] == ["tool_use", "skill_result_submit"]
    assert child.status == FrameStatus.AWAITING_USER
    assert child.result_summary == prompt
    assert child.private_working_state["turn_status"] == "WAITING_FOR_USER_INPUT"
    assert child.private_working_state["awaiting_user_input"]["user_message"] == prompt
    assert any(
        message.get("synthetic") is True and message.get("content") == prompt
        for message in child.private_messages
    )


def test_llm_agent_invoke_business_skill_bubbles_waiting_user_focus():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_waiting_user_002",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    prompt = "请回复 1 或 2 选择工单类型。"
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {
                "skill_id": "child_skill",
                "instruction": "引导用户选择工单类型。",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_wait",
            "name": "submit_skill_result",
            "args": {
                "summary": prompt,
                "structured_output": {
                    "turn_status": "WAITING_FOR_USER_INPUT",
                    "user_message": prompt,
                    "required_fields": ["ticket_type"],
                },
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_waiting_user_002",
        frame_id=root_id,
        prompt="你可以帮我提交工单吗",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_id)
    child_id = root.child_frame_ids[-1]
    child = runtime.get_frame(child_id)
    assert model.calls == 2
    assert any(event.tool_name == "invoke_business_skill" for event in events)
    assert root.status == FrameStatus.WAITING_CHILD
    assert root.result_summary == prompt
    assert root.private_working_state["pending_awaiting_user_child_frame_id"] == child_id
    assert root.private_working_state["active_focus_frame_id"] == child_id
    assert root.private_working_state["active_focus_status"] == "AWAITING_USER"
    assert child.status == FrameStatus.AWAITING_USER


def test_llm_agent_invoke_business_skill_direct_returns_final_for_user():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_final_direct_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    final_summary = "工单已创建成功，编号 TKT-DIRECT-001。"
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {
                "skill_id": "child_skill",
                "instruction": "创建工单。",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_done",
            "name": "submit_skill_result",
            "args": {
                "summary": final_summary,
                "structured_output": {
                    "turn_status": "FINAL_FOR_USER",
                    "requires_parent_synthesis": False,
                    "remaining_work": [],
                    "ticket_id": "TKT-DIRECT-001",
                },
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_final_direct_001",
        frame_id=root_id,
        prompt="帮我创建工单",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_id)
    child = runtime.get_frame(root.child_frame_ids[-1])
    assert model.calls == 2
    assert root.status == FrameStatus.RUNNING
    assert root.result_summary == final_summary
    assert root.output["turn_status"] == "FINAL_FOR_USER"
    assert child.status == FrameStatus.COMPLETED
    assert any(
        event.tool_name == "invoke_business_skill" and event.type == "tool_result"
        for event in events
    )
    assert not any(
        event.tool_name == "submit_skill_result" and event.skill_id == "system.root"
        for event in events
    )


def test_llm_agent_resumed_awaiting_user_child_prompt_includes_prior_prompt_and_reply():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_waiting_user_003",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    child_id = runtime.invoke_child_skill(root_id, "child_skill")
    prior_prompt = "请回复 1 或 2 选择工单类型。"
    runtime.submit_user_input_request(
        child_id,
        prior_prompt,
        {
            "turn_status": "WAITING_FOR_USER_INPUT",
            "user_message": prior_prompt,
            "required_fields": ["ticket_type"],
        },
    )
    runtime.mark_child_awaiting_user(root_id, child_id)

    focus = runtime.prepare_active_focus_resume(root_id, task_id="task_child_waiting_user_003b")
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child_wait_again",
            "name": "submit_skill_result",
            "args": {
                "summary": "请继续补充工单标题。",
                "structured_output": {
                    "turn_status": "WAITING_FOR_USER_INPUT",
                    "user_message": "请继续补充工单标题。",
                    "required_fields": ["title"],
                },
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_waiting_user_003b",
        frame_id=focus.frame_id,
        prompt="1",
        runtime_context={
            "_awaiting_user_input": {
                "frame_id": child_id,
                "skill_id": "child_skill",
                "status": "RUNNING",
                "awaiting_user_input": focus.private_working_state["awaiting_user_input"],
            },
        },
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Previous child skill turn is waiting for user input." in user_prompt
    assert prior_prompt in user_prompt
    assert "Current user reply: 1" in user_prompt
    assert runtime.get_frame(child_id).status == FrameStatus.AWAITING_USER


def test_llm_agent_keeps_page_action_summary_without_backend_rewrite():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_page_action_001",
        skill_id="system.root",
        skill_input={"request": "open page"},
    )
    structured_output = {
        "type": "OPEN_TMS_PAGE",
        "label": "去下单",
        "routeName": "OrderWorkbench",
        "query": {"aiDraftId": "afd_9eadcfa5c92b492a88be6f5e583b1271"},
    }
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "订单创建成功！订单号：OrderWorkbench",
                "structured_output": structured_output,
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_page_action_001",
        frame_id=frame_id,
        prompt="打开页面",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert frame.result_summary == "订单创建成功！订单号：OrderWorkbench"


def test_llm_agent_child_skill_summary_visibility_receives_root_context_summary():
    runtime = _root_with_child_runtime(child_context_visibility="summary")
    frame_id = runtime.invoke_skill(
        task_id="task_child_summary_context_001",
        skill_id="system.root",
        skill_input={},
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state["root_context_summary"] = {
        "turn_count": 2,
        "latest_summary": "Order ORD-001 was inspected.",
        "artifact_refs": ["art_1"],
    }
    runtime.store.save(frame)

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {
                "skill_id": "child_skill",
                "instruction": "handle child work",
                "input": {"orderNo": "ORD-001"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Child done.",
                "structured_output": {"ok": True},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Root done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_summary_context_001",
        frame_id=frame_id,
        prompt="delegate",
        persistent_frame=True,
    )

    child_user_prompt = model.seen_messages[1][1].content
    assert "Visible parent/root context summary:" in child_user_prompt
    assert "Order ORD-001 was inspected." in child_user_prompt
    assert "art_1" in child_user_prompt


def test_llm_agent_child_skill_isolated_visibility_hides_root_context_summary():
    runtime = _root_with_child_runtime(child_context_visibility="isolated")
    frame_id = runtime.invoke_skill(
        task_id="task_child_isolated_context_001",
        skill_id="system.root",
        skill_input={},
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state["root_context_summary"] = {
        "latest_summary": "This should not be visible.",
    }
    runtime.store.save(frame)

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {"skill_id": "child_skill", "instruction": "handle child work"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Child done.", "structured_output": {"ok": True}},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Root done.", "structured_output": {"ok": True}},
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_isolated_context_001",
        frame_id=frame_id,
        prompt="delegate",
        persistent_frame=True,
    )

    child_user_prompt = model.seen_messages[1][1].content
    assert "Visible parent/root context summary:" not in child_user_prompt
    assert "This should not be visible." not in child_user_prompt


def test_llm_agent_child_skill_receives_sanitized_attachment_context():
    runtime = _root_with_child_runtime(child_context_visibility="isolated")
    frame_id = runtime.invoke_skill(
        task_id="task_child_attachment_context_001",
        skill_id="system.root",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {"skill_id": "child_skill", "instruction": "create a TMS ticket"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Child saw attachment.", "structured_output": {"ok": True}},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Root done.", "structured_output": {"ok": True}},
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_attachment_context_001",
        frame_id=frame_id,
        prompt="你可以帮我提个工单吗",
        runtime_context={
            "attachments": [{
                "id": "att-1",
                "name": "image.png",
                "mimeType": "image/png",
                "size": 33485,
                "kind": "image",
                "provider": "tms-bff",
                "url": "https://tms.example.com/files/image.png?token=secret",
                "metadata": {"traceId": "trace-1", "accessToken": "hidden"},
            }],
        },
        persistent_frame=True,
    )

    child_user_prompt = model.seen_messages[1][1].content
    assert "Attachments provided by upstream system:" in child_user_prompt
    assert "att-1" in child_user_prompt
    assert "image.png" in child_user_prompt
    assert "tms-bff" in child_user_prompt
    assert "https://tms.example.com/files/image.png" in child_user_prompt
    assert "trace-1" in child_user_prompt
    assert "token=secret" not in child_user_prompt
    assert "accessToken" not in child_user_prompt
    assert "hidden" not in child_user_prompt


def test_llm_agent_child_skill_passthrough_visibility_is_system_only():
    runtime = _root_with_child_runtime(child_context_visibility="passthrough")
    frame_id = runtime.invoke_skill(
        task_id="task_child_passthrough_context_001",
        skill_id="system.root",
        skill_input={},
    )
    frame = runtime.get_frame(frame_id)
    frame.private_working_state["root_context_summary"] = {
        "latest_summary": "User skill should not see passthrough context.",
    }
    runtime.store.save(frame)

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {"skill_id": "child_skill", "instruction": "handle child work"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Child done.", "structured_output": {"ok": True}},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_root_submit",
            "name": "submit_skill_result",
            "args": {"summary": "Root done.", "structured_output": {"ok": True}},
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_passthrough_context_001",
        frame_id=frame_id,
        prompt="delegate",
        persistent_frame=True,
    )

    child_user_prompt = model.seen_messages[1][1].content
    assert "Visible parent/root context summary:" not in child_user_prompt
    assert "User skill should not see passthrough context." not in child_user_prompt


def test_llm_agent_retries_when_submit_contract_rejected():
    runtime = _runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_llm_agent_002",
        skill_id="exception_triage",
        skill_input={"order_id": "ORD-LLM-002"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "bad_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Incomplete.",
                "structured_output": {"classification": "vehicle_delay"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "good_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Order diagnosed as vehicle_delay.",
                "structured_output": {
                    "classification": "vehicle_delay",
                    "recommended_action": "manual_dispatch",
                    "confidence": 0.9,
                },
                "evidence_refs": ["order:ORD-LLM-002"],
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_llm_agent_002",
        frame_id=frame_id,
        prompt="分析订单异常",
    )

    assert runtime.get_frame(frame_id).status == FrameStatus.COMPLETED
    assert [e.type for e in events].count("skill_result_reject") == 1
    assert events[-1].type == "skill_result_submit"


def test_llm_agent_artifact_tool_available_with_account_context(tmp_path):
    runtime = _artifact_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_artifact_agent_001",
        skill_id="artifact_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_artifact",
            "name": "create_artifact",
            "args": {
                "name": "payload",
                "content": "long payload",
                "scope": "task",
                "summary": "payload summary",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Artifact created.",
                "structured_output": {"ok": True},
                "artifact_refs": [],
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_artifact_agent_001",
        frame_id=frame_id,
        prompt="create artifact",
        account_id="acct-001",
    )

    tool_results = [json.loads(e.content) for e in events if e.type == "tool_result"]
    assert tool_results[0]["artifact_id"].startswith("art_")
    assert "content_ref" not in tool_results[0]
    assert runtime.get_frame(frame_id).status == FrameStatus.COMPLETED


def test_llm_agent_uses_frame_manifest_snapshot_after_registry_reload():
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="snapshot_skill",
        name="snapshot_skill",
        description="frozen manifest",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_snapshot_agent_001",
        skill_id="snapshot_skill",
        skill_input={},
    )

    registry.register(SkillManifest(
        id="snapshot_skill",
        name="snapshot_skill",
        description="reloaded manifest",
        output_schema={
            "type": "object",
            "required": ["unexpected"],
            "properties": {"unexpected": {"type": "string"}},
        },
        allowed_tools=["mock_get_order"],
        promote_to_parent=["result_summary", "structured_output"],
    ))

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Used frozen manifest.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_snapshot_agent_001",
        frame_id=frame_id,
        prompt="run snapshot skill",
    )

    assert runtime.get_frame(frame_id).status == FrameStatus.COMPLETED
    assert events[-1].type == "skill_result_submit"
    assert "frozen manifest" in model.seen_messages[0][0].content


def test_llm_agent_injects_account_context_before_skill_instructions(tmp_path):
    data_root = tmp_path / "data"
    account_root = data_root / "accounts" / "acct-001"
    account_root.mkdir(parents=True)
    (account_root / "ACCOUNT_POLICY.md").write_text("policy rule", encoding="utf-8")
    (account_root / "AGENT.md").write_text("agent rule", encoding="utf-8")
    (account_root / "MEMORY.md").write_text("memory note", encoding="utf-8")

    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="context_skill",
        name="context_skill",
        description="Uses account context.",
        markdown_body="skill instruction",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_context_agent_001",
        skill_id="context_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime, data_root=data_root).run(
        task_id="task_context_agent_001",
        frame_id=frame_id,
        prompt="run",
        account_id="acct-001",
    )

    system_prompt = model.seen_messages[0][0].content
    assert system_prompt.index("### ACCOUNT_POLICY.md") < system_prompt.index("### AGENT.md")
    assert system_prompt.index("### AGENT.md") < system_prompt.index("### MEMORY.md")
    assert system_prompt.index("### MEMORY.md") < system_prompt.index("Skill Instructions:")
    assert "policy rule" in system_prompt
    assert "agent rule" in system_prompt
    assert "memory note" in system_prompt


def test_llm_agent_injects_runtime_time_context_into_user_prompt():
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="query_skill",
        name="query_skill",
        description="Query business data.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_time_context_001",
        skill_id="query_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_time_context_001",
        frame_id=frame_id,
        prompt="本月运费",
        runtime_context={
            "current_time": "2026-05-11T10:37:10+08:00",
            "timezone": "Asia/Shanghai",
        },
    )

    user_prompt = model.seen_messages[0][1].content
    assert "Runtime context:" in user_prompt
    assert "- current_time: 2026-05-11T10:37:10+08:00" in user_prompt
    assert "- timezone: Asia/Shanghai" in user_prompt
    assert "- business_date: 2026-05-11" in user_prompt
    assert "- current_month_range: [2026-05-01, 2026-06-01)" in user_prompt
    assert "Resolve relative dates such as 本月" in user_prompt


def test_llm_agent_keeps_runtime_time_out_of_system_prompt():
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="query_skill",
        name="query_skill",
        description="Query business data.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_time_context_002",
        skill_id="query_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_time_context_002",
        frame_id=frame_id,
        prompt="本月运费",
        runtime_context={
            "current_time": "2026-05-11T10:37:10+08:00",
            "timezone": "Asia/Shanghai",
        },
    )

    system_prompt = model.seen_messages[0][0].content
    assert "current_time" not in system_prompt
    assert "current_month_range" not in system_prompt
    assert "2026-05-11T10:37:10+08:00" not in system_prompt


def test_llm_agent_exposes_only_invoke_business_function_tool_without_skill_allowlist():
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="business_skill",
        name="business_skill",
        description="Uses business functions.",
        output_schema={"type": "object"},
        allowed_tools=["list_business_functions", "get_business_function_schema"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_business_agent_001",
        skill_id="business_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_invoke",
            "name": "invoke_business_function",
            "args": {"function_id": "tms.dataset.listModels", "version": "v1", "input": {}},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Reported gateway error to user.",
                "structured_output": {"ok": False},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_business_agent_001",
        frame_id=frame_id,
        prompt="list models",
    )

    bound_tool_names = {t["function"]["name"] for t in model.bound_tools}
    assert "list_business_functions" not in bound_tool_names
    assert "get_business_function_schema" not in bound_tool_names
    assert "invoke_business_function" in bound_tool_names
    tool_result = next(e for e in events if e.type == "tool_result")
    assert "MISSING_TOKEN" in tool_result.error
    assert "Tool not allowed" not in tool_result.error
    assert runtime.get_frame(frame_id).status == FrameStatus.COMPLETED


def test_llm_agent_records_tool_call_args_for_debugging(monkeypatch):
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="business_skill",
        name="business_skill",
        description="Uses business functions.",
        output_schema={"type": "object"},
        allowed_tools=[],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_business_agent_002",
        skill_id="business_skill",
        skill_input={},
    )

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        return {"function_id": function_id, "version": version, "input": input_data}

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_invoke",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.dataset.listModels",
                "version": "v1",
                "input": {},
                "access_token": "should-not-be-logged",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Done.",
                "structured_output": {"ok": True},
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_business_agent_002",
        frame_id=frame_id,
        prompt="list models",
        runtime_context={"task_scoped_token": "runtime-token"},
    )

    tool_call_messages = [
        message for message in runtime.get_frame(frame_id).private_messages
        if message["role"] == "tool_call"
    ]
    assert tool_call_messages[0]["content"]["name"] == "invoke_business_function"
    assert tool_call_messages[0]["content"]["args"]["function_id"] == "tms.dataset.listModels"
    assert tool_call_messages[0]["content"]["args"]["access_token"] == "<redacted>"


def test_llm_agent_suspends_business_function_call(monkeypatch):
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="business_skill",
        name="business_skill",
        description="Uses business functions.",
        output_schema={"type": "object"},
        allowed_tools=[],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_business_suspend_001",
        skill_id="business_skill",
        skill_input={},
    )

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        return {
            "functionId": function_id,
            "version": version,
            "status": "SUSPENDED",
            "approvalRequired": True,
            "suspendId": "sus_123",
            "message": "Approval required",
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_invoke",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.order.close",
                "version": "v1",
                "input": {"orderNo": "ORD-001"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_business_suspend_001",
        frame_id=frame_id,
        prompt="close order",
        runtime_context={"task_scoped_token": "runtime-token"},
    )

    caller = runtime.get_frame(frame_id)
    assert caller.status == FrameStatus.AWAITING_APPROVAL
    assert caller.approval_request["suspend_id"] == "sus_123"
    function_frame_id = caller.private_working_state["pending_function_call_frame_id"]
    function_frame = runtime.get_frame(function_frame_id)
    assert function_frame.frame_kind == FrameKind.FUNCTION_CALL
    assert function_frame.status == FrameStatus.AWAITING_APPROVAL
    assert function_frame.input["function_id"] == "tms.order.close"
    assert any(event.type == "approval_required" for event in events)
    approval_event = next(event for event in events if event.type == "approval_required")
    assert approval_event.suspend_id == "sus_123"
    assert approval_event.skill_frame_id == function_frame_id
    assert approval_event.parent_frame_id == frame_id


def test_llm_agent_bubbles_child_business_function_approval_to_root(monkeypatch, tmp_path):
    runtime = _root_with_child_runtime(child_context_visibility="summary", data_root=tmp_path)
    root_frame_id = runtime.invoke_skill(
        task_id="task_child_business_suspend_001",
        skill_id="system.root",
        skill_input={"request": "create vehicle"},
    )

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        return {
            "functionId": function_id,
            "version": version,
            "status": "SUSPENDED",
            "approvalRequired": True,
            "suspendId": "sus_child_123",
            "message": "Approval required",
        }

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_skill",
            "args": {
                "skill_id": "child_skill",
                "instruction": "create vehicle",
                "input": {"plateNo": "A-001"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_function",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.vehicle.create",
                "version": "v1",
                "input": {"plateNo": "A-001"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_business_suspend_001",
        frame_id=root_frame_id,
        prompt="create vehicle",
        runtime_context={"task_scoped_token": "runtime-token"},
        persistent_frame=True,
    )

    root = runtime.get_frame(root_frame_id)
    child_frame_id = root.child_frame_ids[0]
    child = runtime.get_frame(child_frame_id)
    function_frame_id = child.private_working_state["pending_function_call_frame_id"]
    function_frame = runtime.get_frame(function_frame_id)

    assert root.status == FrameStatus.AWAITING_APPROVAL
    assert root.approval_request["suspend_id"] == "sus_child_123"
    assert root.private_working_state["pending_child_approval_frame_id"] == child_frame_id
    assert child.status == FrameStatus.AWAITING_APPROVAL
    assert function_frame.status == FrameStatus.AWAITING_APPROVAL
    assert any(event.type == "approval_required" for event in events)
    skill_tool_result = next(
        event
        for event in events
        if event.type == "tool_result" and event.tool_name == "invoke_business_skill"
    )
    skill_tool_payload = json.loads(skill_tool_result.content)
    assert skill_tool_payload["approval_wait"] is True
    assert skill_tool_payload["execution_report_ref"] == (
        f"frame-report://task_child_business_suspend_001/{child_frame_id}"
    )
    assert skill_tool_payload["execution_report_digest"]["status"] == "AWAITING_APPROVAL"
    assert skill_tool_result.execution_report_ref == (
        f"frame-report://task_child_business_suspend_001/{child_frame_id}"
    )
    assert skill_tool_result.execution_report_digest["status"] == "AWAITING_APPROVAL"
    assert not any(event.type == "error" for event in events)
    assert model.calls == 2


def test_resume_approval_completes_pending_function_frame(monkeypatch):
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=SkillRegistry())
    caller_frame_id = runtime.invoke_skill(
        task_id="task_business_resume_001",
        skill_id="system.root",
        skill_input={},
    )
    function_frame_id = runtime.invoke_function_call(
        parent_frame_id=caller_frame_id,
        function_id="tms.order.close",
        version="v1",
        arguments={"orderNo": "ORD-001"},
        idempotency_key="idem-1",
    )
    approval_request = {
        "approval_type": "business_function",
        "function_id": "tms.order.close",
        "version": "v1",
        "suspend_id": "sus_123",
        "summary": {"title": "Approval required"},
        "payload": {"function_frame_id": function_frame_id},
        "resolved": False,
    }
    runtime.suspend_function_call(function_frame_id, approval_request)
    runtime.mark_awaiting_approval(caller_frame_id, approval_request)

    runtime.resume_from_approval(caller_frame_id, "approved", "ok")

    caller = runtime.get_frame(caller_frame_id)
    function_frame = runtime.get_frame(function_frame_id)
    assert caller.status == FrameStatus.RUNNING
    assert "pending_function_call_frame_id" not in caller.private_working_state
    assert function_frame.status == FrameStatus.COMPLETED
    assert function_frame.output["status"] == "RESUME_DISPATCHED"
    assert function_frame.output["approval_result"] == "approved"
