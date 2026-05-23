"""Tests for the LLM-driven Skill tool-call loop."""

from __future__ import annotations

import json
import time

from langchain_core.messages import AIMessage

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillManifest
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.file_layout import session_data_dir
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_call_guard import reset_llm_call_guard_state_for_tests
from langgraph_biz_worker.runtime import command_tool
from langgraph_biz_worker.runtime.llm_skill_agent import LlmSkillAgent
from langgraph_biz_worker.runtime.context_memory import PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY
from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime
from langgraph_biz_worker.config import settings
from langgraph_biz_worker.tools.business_function_tools import BusinessFunctionToolError


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
                "name": "invoke_business_agent",
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
                "name": "invoke_business_agent",
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
        markdown_body="根 Agent 负责当前用户回合的业务编排。",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        visibility="builtin",
    ))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


def _bound_tool_names(model: FakeToolCallModel) -> set[str]:
    return {tool["function"]["name"] for tool in model.bound_tools}


def test_llm_agent_exposes_default_file_tools_when_account_scope_available(tmp_path):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_default_file_tools_001",
        skill_id="system.root",
        skill_input={},
    )
    model = FakeToolCallModel([AIMessage(content="ok")])

    LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_default_file_tools_001",
        frame_id=frame_id,
        prompt="hi",
        account_id="acct-file-tools",
        persistent_frame=True,
    )

    tool_names = _bound_tool_names(model)
    assert {"list_files", "read_file", "write_file", "patch_file"} <= tool_names
    assert "str_replace" not in tool_names
    assert "edit_file" not in tool_names


def test_llm_agent_does_not_expose_default_file_tools_without_account_scope(tmp_path):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_no_file_tools_without_account_001",
        skill_id="system.root",
        skill_input={},
    )
    model = FakeToolCallModel([AIMessage(content="ok")])

    LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_no_file_tools_without_account_001",
        frame_id=frame_id,
        prompt="hi",
        persistent_frame=True,
    )

    tool_names = _bound_tool_names(model)
    assert not ({"list_files", "read_file", "write_file", "patch_file"} & tool_names)


def test_llm_agent_exposes_command_when_linux_enabled_and_explicitly_allowed(tmp_path, monkeypatch):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_command_tool_001",
        skill_id="system.root",
        skill_input={},
    )
    workdir = tmp_path / "delegated-workspace"
    workdir.mkdir()
    model = FakeToolCallModel([AIMessage(content="ok")])
    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")

    LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_command_tool_001",
        frame_id=frame_id,
        prompt="hi",
        runtime_context={
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_tools": ["command", "submit_skill_result"],
            },
        },
        persistent_frame=True,
    )

    tool_names = _bound_tool_names(model)
    assert "command" in tool_names


def test_llm_agent_hides_command_on_windows_or_without_explicit_allowlist(tmp_path, monkeypatch):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_command_tool_hidden_001",
        skill_id="system.root",
        skill_input={},
    )
    workdir = tmp_path / "delegated-workspace"
    workdir.mkdir()
    model = FakeToolCallModel([AIMessage(content="ok")])
    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Windows")

    LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_command_tool_hidden_001",
        frame_id=frame_id,
        prompt="hi",
        runtime_context={
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_tools": ["command", "submit_skill_result"],
            },
        },
        persistent_frame=True,
    )

    assert "command" not in _bound_tool_names(model)

    model = FakeToolCallModel([AIMessage(content="ok")])
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    LlmSkillAgent(model, runtime, data_root=tmp_path).run(
        task_id="task_command_tool_hidden_002",
        frame_id=frame_id,
        prompt="hi",
        runtime_context={
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_tools": ["read_file", "submit_skill_result"],
            },
        },
        persistent_frame=True,
    )

    assert "command" not in _bound_tool_names(model)


def _root_with_child_runtime(child_context_visibility: str = "isolated", data_root=None) -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="system.root",
        name="system.root",
        description="Persistent root skill.",
        output_schema={"type": "object"},
        allowed_tools=["invoke_business_agent", "submit_skill_result"],
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


def test_llm_agent_loads_business_skill_material_without_child_frame():
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
        id="ticket_helper",
        name="ticket_helper",
        description="Ticket helper material.",
        markdown_body="# Ticket helper\n\nAsk for ticket type and description.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    root_id = runtime.invoke_skill(
        task_id="task_skill_material_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_skill_material",
            "name": "invoke_business_skill",
            "args": {
                "skill_name": "ticket_helper",
                "instruction": "Read ticket helper material.",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Need ticket fields.",
                "structured_output": {"message": "Need ticket fields."},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_skill_material_001",
        frame_id=root_id,
        prompt="Help me create a ticket",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_id)
    skill_result_event = next(
        event for event in events
        if event.type == "tool_result" and event.tool_name == "invoke_business_skill"
    )
    skill_payload = json.loads(skill_result_event.content)
    assert root.child_frame_ids == []
    assert skill_payload["frame_created"] is False
    assert skill_payload["skill_id"] == "ticket_helper"
    assert "Ask for ticket type" in skill_payload["markdown_body"]
    assert root.output["message"] == "Need ticket fields."


def test_llm_agent_invokes_client_app_public_skill_from_runtime_context(tmp_path):
    client_app_id = "capp_test_public_skill"
    skills_root = tmp_path / "skills"
    skill_dir = skills_root / "public" / "apps" / client_app_id / "tms-ticket-agent"
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(
        """
---
name: tms-ticket-agent
description: Ticket skill.
allowed-tools: submit_skill_result
metadata:
  display_name: TMS Ticket Agent
  visibility: public
  context-visibility: isolated
  client_app_id: capp_test_public_skill
  promote-to-parent:
    - result_summary
    - structured_output
---

# Ticket skill
""".strip(),
        encoding="utf-8",
    )
    registry = SkillRegistry(skills_root=skills_root, data_root=tmp_path / "data")
    registry.register(SkillManifest(
        id="system.root",
        name="system.root",
        description="Persistent root skill.",
        output_schema={"type": "object"},
        allowed_tools=["invoke_business_agent", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        visibility="builtin",
        context_visibility="passthrough",
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    root_id = runtime.invoke_skill(
        task_id="task_client_app_public_skill_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_public_skill",
            "name": "invoke_business_agent",
            "args": {
                "skill_name": "tms-ticket-agent",
                "instruction": "Collect ticket fields.",
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_done",
            "name": "submit_skill_result",
            "args": {
                "summary": "Ticket fields are required.",
                "structured_output": {
                    "turn_status": "FINAL_FOR_USER",
                    "message": "Ticket fields are required.",
                },
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_client_app_public_skill_001",
        frame_id=root_id,
        prompt="Help me create a ticket",
        runtime_context={"client_app_id": client_app_id},
        persistent_frame=True,
    )

    root = runtime.get_frame(root_id)
    child = runtime.get_frame(root.child_frame_ids[-1])
    assert model.calls == 2
    assert child.skill_id == "tms-ticket-agent"
    assert child.status == FrameStatus.COMPLETED
    assert root.output["message"] == "Ticket fields are required."
    assert not any("Skill manifest not found" in (event.error or "") for event in events)


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
    assert root.private_working_state["interrupt_reason"] == "child_agent_failed"
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
        and event.content == "Resuming frame for agent: child_skill"
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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "继续"
    assert "当前活动任务计划:" in system_prompt
    assert "deliver complex task" in system_prompt
    assert "step-1" in system_prompt
    assert "持久根计划策略:" in system_prompt
    assert "主动调用 submit_skill_result" in system_prompt
    assert "structured_output.active_plan" in system_prompt
    assert "intent_resolution" in system_prompt


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

    messages = model.seen_messages[0]
    system_prompt = messages[0].content
    assert "本回合前的最近对话:" not in system_prompt
    assert messages[1].type == "human"
    assert messages[1].content == "我刚才问了工单 1001"
    assert messages[2].type == "ai"
    assert messages[2].content == "工单 1001 当前待处理"
    assert messages[3].type == "human"
    assert messages[3].content == "我上一个消息是什么"


def test_llm_agent_root_prompt_hides_runtime_private_identifiers():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_private_prompt_001",
        skill_id="system.root",
        skill_input={
            "contextId": "bctx_20260521_ab_private",
            "session_id": "sess-private",
            "task_id": "task-private",
            "frame_id": "frm-private",
            "execution_report_ref": "frame-report://task-private/frm-private",
            "report_ref": "frame-report://task-private/frm-private",
            "skill_id": "system.root",
            "child_skill_id": "tms-ticket-agent",
            "businessSkillId": "tms-root-router-agent",
            "businessSkillName": "tms-root-router-agent",
            "credentialId": "cred-private",
            "apiToken": "secret-token",
            "allowed_skills": [
                {
                    "id": "tms-ticket-agent",
                    "name": "TMS Ticket Agent",
                    "description": "Create and inspect TMS tickets.",
                    "credentialId": "cred-skill",
                }
            ],
            "business_order_no": "ORD-1",
        },
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
        task_id="task_root_private_prompt_001",
        frame_id=frame_id,
        prompt="hi",
        persistent_frame=True,
    )

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    combined = system_prompt + "\n" + user_prompt
    assert "system.root" not in combined
    assert "tms-root-router-agent" not in combined
    assert "businessSkillId" not in combined
    assert "businessSkillName" not in combined
    assert "SKILL_AGENT_START" not in combined
    assert "bctx_20260521_ab_private" not in combined
    assert "sess-private" not in combined
    assert "cred-private" not in combined
    assert "cred-skill" not in combined
    assert "secret-token" not in combined
    assert "task-private" in combined
    assert "frm-private" in combined
    assert "frame-report://task-private/frm-private" in combined
    assert "tms-ticket-agent" in combined
    assert user_prompt == "hi"
    assert "当前根回合上下文:" in system_prompt
    assert "可用业务技能:" in system_prompt
    assert "- `tms-ticket-agent`（TMS Ticket Agent）: Create and inspect TMS tickets." in system_prompt
    assert '"allowed_skills"' not in system_prompt
    assert "业务上下文:" in system_prompt
    assert "Create and inspect TMS tickets." in system_prompt
    assert "ORD-1" in system_prompt


def test_llm_agent_root_system_prompt_is_chinese_and_includes_skill_instructions():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_cn_prompt_001",
        skill_id="system.root",
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
        task_id="task_root_cn_prompt_001",
        frame_id=frame_id,
        prompt="hi",
        persistent_frame=True,
    )

    system_prompt = model.seen_messages[0][0].content
    assert system_prompt.startswith("你是当前业务会话的根编排 Agent。")
    assert "技能说明:" in system_prompt
    assert "You are the root orchestration agent" not in system_prompt
    assert "Use only the provided tools" not in system_prompt
    assert "只能使用已提供的工具" in system_prompt


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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "继续"
    assert "上一次执行被中断。" in system_prompt
    assert "原因: model_error" in system_prompt
    assert "upstream model timed out" in system_prompt
    assert "来自子 Agent 提升结果的续跑摘要:" in system_prompt
    assert "WAITING_USER" in system_prompt
    assert "请回复工单类型、标题及详细描述。" in system_prompt
    assert "当前用户消息见下一条 human message。" in system_prompt
    assert "中断工作只是可恢复候选" in system_prompt
    assert "可恢复焦点:" in system_prompt
    assert "可恢复焦点栈:" in system_prompt
    assert "CONTINUE_PREVIOUS" in system_prompt
    assert "ASK_CLARIFICATION" in system_prompt
    assert "START_UNRELATED_NEW_TASK" in system_prompt
    assert "intent_resolution" in system_prompt
    assert "abandoned_interruption" in system_prompt
    assert "shelve_interrupted_frame" in system_prompt


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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "继续"
    assert "Frame 结果契约:" in system_prompt
    assert "主要业务决策上下文" in system_prompt
    assert "正常子 Agent 完成后，不要仅为了恢复这些字段而调用 read_frame_execution_report" in system_prompt


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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "继续"
    assert "待恢复子 Agent:" in system_prompt
    assert "可恢复焦点:" in system_prompt
    assert "可恢复焦点栈:" in system_prompt
    assert child_frame_id in system_prompt
    assert '"skill_id": "child_skill"' in system_prompt
    assert "task_root_child_prompt_001" in system_prompt
    assert "ORD-1" in system_prompt
    assert "resume_recoverable_child_skill" in system_prompt


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
    assert "handoff_to_parent" not in bound_tool_names
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
        and event.content == "Resuming frame for agent: child_skill"
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


def test_llm_agent_child_can_handoff_to_parent_without_business_output_validation():
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="system.root",
        name="system.root",
        description="Persistent root skill.",
        output_schema={"type": "object"},
        allowed_tools=["invoke_business_agent", "submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
        visibility="builtin",
        context_visibility="passthrough",
    ))
    registry.register(SkillManifest(
        id="strict_child",
        name="strict_child",
        description="Strict child skill.",
        output_schema={
            "type": "object",
            "required": ["business_result"],
            "properties": {"business_result": {"type": "string"}},
        },
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output", "evidence_refs"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    root_frame_id = runtime.invoke_skill(
        task_id="task_child_handoff_001",
        skill_id="system.root",
        conversation_id="bctx_20260520_3c_sess_child_handoff",
        session_id="sess_child_handoff",
    )
    child_frame_id = runtime.invoke_child_skill(
        root_frame_id,
        "strict_child",
        {"request": "collect ticket fields"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_handoff",
            "name": "handoff_to_parent",
            "args": {
                "summary": "已取消当前子任务，回到主对话。",
                "reason": "USER_CANCELLED",
                "intent_resolution": "RETURN_TO_PARENT",
                "requires_parent_synthesis": False,
                "structured_output": {"note": "missing strict business_result on purpose"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_handoff_002",
        frame_id=child_frame_id,
        prompt="取消这个任务，回到主对话",
    )

    child = runtime.get_frame(child_frame_id)
    bound_tool_names = {tool["function"]["name"] for tool in model.bound_tools}
    assert "handoff_to_parent" in bound_tool_names
    assert "shelve_interrupted_frame" not in bound_tool_names
    assert child.status == FrameStatus.COMPLETED
    assert child.result_summary == "已取消当前子任务，回到主对话。"
    assert child.output["status"] == "HANDOFF_TO_PARENT"
    assert child.output["handoff_to_parent"] is True
    assert child.output["requires_parent_synthesis"] is False
    assert child.output["intent_resolution"] == "RETURN_TO_PARENT"
    assert events[-1].tool_name == "handoff_to_parent"
    assert json.loads(events[-1].content)["handoff_to_parent"] is True


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


def test_llm_agent_stops_on_non_recoverable_business_function_configuration_error(monkeypatch):
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_function_config_error_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )

    def fake_invoke(task_scoped_token, function_id=None, version=None, input_data=None, idempotency_key=None):
        raise BusinessFunctionToolError(
            'HTTP 400: {"msg":"upstreamRef must match [A-Za-z0-9._-]{1,128}"}',
            error_category="CONFIGURATION",
            recoverable=False,
            llm_retry_allowed=False,
            user_message="业务函数配置错误：adapter upstream_ref 不合法。",
        )

    monkeypatch.setattr(
        "langgraph_biz_worker.runtime.llm_skill_agent.invoke_business_function",
        fake_invoke,
    )

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_function",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.ticket.createPlatformFeedback",
                "version": "v1",
                "input": {"title": "bug"},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_retry",
            "name": "invoke_business_function",
            "args": {
                "function_id": "tms.ticket.createPlatformFeedback",
                "version": "v1",
                "input": {"title": "bug", "upstreamRef": "TMS-3"},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_function_config_error_001",
        frame_id=frame_id,
        prompt="create ticket",
        runtime_context={"task_scoped_token": "runtime-token"},
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert model.calls == 1
    assert frame.status == FrameStatus.RUNNING
    assert frame.result_summary == "业务函数配置错误：adapter upstream_ref 不合法。"
    assert frame.output["error_category"] == "CONFIGURATION"
    assert "continuation_state" not in frame.private_working_state
    assert any(
        event.type == "error"
        and event.reason == "non_recoverable_tool_error"
        and "业务函数配置错误" in event.error
        for event in events
    )
    assert not any(
        event.type == "error"
        and event.error == "LLM skill agent reached max iterations without valid submit"
        for event in events
    )


def test_llm_agent_inserts_queued_user_input_at_checkpoint():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_queue_checkpoint_001",
        skill_id="system.root",
        skill_input={"request": "start"},
    )
    checkpoint_calls = 0

    def checkpoint():
        nonlocal checkpoint_calls
        checkpoint_calls += 1
        if checkpoint_calls == 2:
            return [{
                "role": "user",
                "content": "U2 while root loop is still running",
            }]
        return []

    model = FakeToolCallModel([
        AIMessage(content="Answer for U1."),
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Handled queued input.",
                "structured_output": {"message": "Handled queued input."},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime, max_iterations=3).run(
        task_id="task_root_queue_checkpoint_001",
        frame_id=frame_id,
        prompt="U1",
        runtime_context={"_runtime_memory_checkpoint": checkpoint},
        persistent_frame=True,
    )

    assert model.calls == 2
    assert any(event.type == "skill_result_submit" for event in events)
    second_prompt = "\n".join(
        getattr(message, "content", "")
        for message in model.seen_messages[1]
    )
    assert "Additional user message received while this turn was running" in second_prompt
    assert "U2 while root loop is still running" in second_prompt
    assert "Answer for U1." in second_prompt


def test_llm_agent_reconsiders_terminal_submit_when_queued_input_arrives_before_tool_execution():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_queue_terminal_001",
        skill_id="system.root",
        skill_input={"request": "start"},
    )
    checkpoint_calls = 0
    markers: list[str] = []

    def checkpoint():
        nonlocal checkpoint_calls
        checkpoint_calls += 1
        if checkpoint_calls == 2:
            return [{
                "role": "user",
                "content": "U2 arrived before stale submit ran",
            }]
        return []

    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_stale_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Stale answer.",
                "structured_output": {"message": "Stale answer."},
            },
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_final_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Handled latest input.",
                "structured_output": {"message": "Handled latest input."},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime, max_iterations=3).run(
        task_id="task_root_queue_terminal_001",
        frame_id=frame_id,
        prompt="U1",
        runtime_context={
            "_runtime_memory_checkpoint": checkpoint,
            "_runtime_memory_mark_finalizing": lambda: markers.append("finalizing"),
            "_runtime_memory_mark_running": lambda: markers.append("running"),
        },
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert model.calls == 2
    assert frame.result_summary == "Handled latest input."
    assert [event.tool_call_id for event in events if event.type == "skill_result_submit"] == [
        "call_final_submit"
    ]
    assert markers == ["finalizing"]
    second_prompt = "\n".join(
        getattr(message, "content", "")
        for message in model.seen_messages[1]
    )
    assert "U2 arrived before stale submit ran" in second_prompt
    assert "Stale answer." not in second_prompt


def test_llm_agent_persistent_frame_stores_root_turn_protocol():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_protocol_store_001",
        skill_id="system.root",
        skill_input={"request": "start"},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Protocol stored.",
                "structured_output": {"message": "Protocol stored."},
            },
        }]),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_protocol_store_001",
        frame_id=frame_id,
        prompt="U1",
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    protocol = frame.private_working_state[PENDING_ROOT_TURN_PROTOCOL_MESSAGES_KEY]
    assert any(event.type == "skill_result_submit" for event in events)
    assert [item["role"] for item in protocol] == ["user", "assistant", "tool"]
    assert protocol[1]["toolCalls"][0]["id"] == "call_submit"
    assert protocol[2]["toolCallId"] == "call_submit"


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
    assert model.calls == 1
    assert frame.result_summary == expected_summary
    assert frame.private_working_state["turn_results"][-1]["summary"] == expected_summary


def test_llm_agent_persistent_root_accepts_plain_assistant_final_answer():
    runtime = _root_runtime()
    frame_id = runtime.invoke_skill(
        task_id="task_root_plain_final_001",
        skill_id="system.root",
        skill_input={"request": "say hi"},
    )
    model = FakeToolCallModel([
        AIMessage(content="你好，我可以帮你处理运输和工单相关问题。"),
    ])
    markers: list[str] = []

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_root_plain_final_001",
        frame_id=frame_id,
        prompt="hi",
        runtime_context={
            "_runtime_memory_mark_finalizing": lambda: markers.append("finalizing"),
            "_runtime_memory_mark_running": lambda: markers.append("running"),
        },
        persistent_frame=True,
    )

    frame = runtime.get_frame(frame_id)
    assert events == []
    assert model.calls == 1
    assert frame.status == FrameStatus.RUNNING
    assert frame.result_summary == "你好，我可以帮你处理运输和工单相关问题。"
    assert frame.output["completion_mode"] == "assistant_message"
    assert markers == ["finalizing"]


def test_llm_agent_child_plain_assistant_answer_completes_as_subagent_result():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_plain_final_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    child_id = runtime.invoke_child_skill(root_id, "child_skill")
    model = FakeToolCallModel([
        AIMessage(content="我已经完成了。"),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_plain_final_001",
        frame_id=child_id,
        prompt="继续",
    )

    child = runtime.get_frame(child_id)
    assert model.calls == 1
    assert child.status == FrameStatus.COMPLETED
    assert child.result_summary == "我已经完成了。"
    assert child.output["turn_status"] == "FINAL_FOR_USER"
    assert child.output["completion_mode"] == "assistant_message"
    assert [event.type for event in events] == ["skill_result_submit"]
    assert events[0].tool_name == "assistant_message"


def test_llm_agent_child_plain_assistant_clarification_pauses_for_user():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_plain_waiting_user_001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    child_id = runtime.invoke_child_skill(root_id, "child_skill")
    prompt = "请告诉我您想创建哪种类型的工单？"
    model = FakeToolCallModel([
        AIMessage(content=prompt),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_plain_waiting_user_001",
        frame_id=child_id,
        prompt="继续",
    )

    child = runtime.get_frame(child_id)
    assert model.calls == 1
    assert child.status == FrameStatus.AWAITING_USER
    assert child.result_summary == prompt
    assert child.output["turn_status"] == "WAITING_FOR_USER_INPUT"
    assert child.private_working_state["turn_status"] == "WAITING_FOR_USER_INPUT"
    assert child.private_working_state["awaiting_user_input"]["user_message"] == prompt
    assert [event.type for event in events] == ["skill_result_submit"]
    assert events[0].tool_name == "assistant_message"


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


def test_llm_agent_invoke_business_agent_bubbles_waiting_user_focus():
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
            "name": "invoke_business_agent",
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
    assert any(event.tool_name == "invoke_business_agent" for event in events)
    assert root.status == FrameStatus.WAITING_CHILD
    assert root.result_summary == prompt
    assert root.private_working_state["pending_awaiting_user_child_frame_id"] == child_id
    assert root.private_working_state["active_focus_frame_id"] == child_id
    assert root.private_working_state["active_focus_status"] == "AWAITING_USER"
    assert child.status == FrameStatus.AWAITING_USER


def test_llm_agent_invoke_business_agent_plain_clarification_bubbles_waiting_user_focus():
    runtime = _root_with_child_runtime()
    root_id = runtime.invoke_skill(
        task_id="task_child_plain_waiting_user_002",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    prompt = "请告诉我您想创建哪种类型的工单？"
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_agent",
            "args": {
                "skill_id": "child_skill",
                "instruction": "引导用户选择工单类型。",
            },
        }]),
        AIMessage(content=prompt),
    ])

    events = LlmSkillAgent(model, runtime).run(
        task_id="task_child_plain_waiting_user_002",
        frame_id=root_id,
        prompt="你可以帮我提交工单吗",
        persistent_frame=True,
    )

    root = runtime.get_frame(root_id)
    child_id = root.child_frame_ids[-1]
    child = runtime.get_frame(child_id)
    assert model.calls == 2
    assert root.status == FrameStatus.WAITING_CHILD
    assert root.result_summary == prompt
    assert root.private_working_state["active_focus_frame_id"] == child_id
    assert root.private_working_state["active_focus_status"] == "AWAITING_USER"
    assert child.status == FrameStatus.AWAITING_USER
    assert child.private_working_state["awaiting_user_input"]["user_message"] == prompt
    assert any(event.tool_name == "assistant_message" for event in events)


def test_llm_agent_invoke_business_agent_direct_returns_final_for_user():
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
            "name": "invoke_business_agent",
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
        event.tool_name == "invoke_business_agent" and event.type == "tool_result"
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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "1"
    assert "上一个子 Agent frame 正在等待用户输入。" in system_prompt
    assert prior_prompt in system_prompt
    assert "当前 human message 是用户对上次提示的回复。" in system_prompt
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
            "name": "invoke_business_agent",
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

    child_system_prompt = model.seen_messages[1][0].content
    child_user_prompt = model.seen_messages[1][1].content
    assert child_user_prompt == "handle child work"
    assert "可见父级/根上下文摘要:" in child_system_prompt
    assert "Order ORD-001 was inspected." in child_system_prompt
    assert "art_1" in child_system_prompt


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
            "name": "invoke_business_agent",
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

    child_system_prompt = model.seen_messages[1][0].content
    child_user_prompt = model.seen_messages[1][1].content
    assert child_user_prompt == "handle child work"
    assert "可见父级/根上下文摘要:" not in child_system_prompt
    assert "This should not be visible." not in child_system_prompt


def test_llm_agent_child_agent_does_not_inherit_root_visible_protocol_or_skill_catalog():
    runtime = _root_with_child_runtime(child_context_visibility="isolated")
    frame_id = runtime.invoke_skill(
        task_id="task_child_isolated_protocol_001",
        skill_id="system.root",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_child",
            "name": "invoke_business_agent",
            "args": {"skill_id": "child_skill", "instruction": "handle child work"},
        }]),
        AIMessage(content="", tool_calls=[{
            "id": "call_child_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Child done.",
                "structured_output": {
                    "turn_status": "FINAL_FOR_USER",
                    "requires_parent_synthesis": False,
                    "message": "Child done.",
                },
            },
        }]),
    ])

    LlmSkillAgent(model, runtime).run(
        task_id="task_child_isolated_protocol_001",
        frame_id=frame_id,
        prompt="delegate from root",
        runtime_context={
            "_runtime_visible_conversation": [
                {"role": "user", "content": "ROOT_ONLY_USER_MESSAGE"},
                {"role": "assistant", "content": "ROOT_ONLY_ASSISTANT_MESSAGE"},
            ],
            "_model_visible_business_context": {
                "allowed_skills": [{
                    "name": "ROOT_ONLY_ALLOWED_SKILL",
                    "description": "root-only skill catalog entry",
                }],
            },
        },
        persistent_frame=True,
    )

    assert model.seen_messages[0][1].content == "ROOT_ONLY_USER_MESSAGE"
    child_messages = model.seen_messages[1]
    child_text = "\n".join(str(message.content) for message in child_messages)
    assert [message.type for message in child_messages] == ["system", "human"]
    assert child_messages[1].content == "handle child work"
    assert "子 Agent 默认工作方式" in child_text
    assert "你不会默认看到 Root 完整历史" in child_text
    assert "list_skill_resources" in child_text
    assert "read_skill_resource" in child_text
    assert "invoke_business_skill 只在当前 Agent frame 内加载材料" in child_text
    assert "ROOT_ONLY_USER_MESSAGE" not in child_text
    assert "ROOT_ONLY_ASSISTANT_MESSAGE" not in child_text
    assert "ROOT_ONLY_ALLOWED_SKILL" not in child_text


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
            "name": "invoke_business_agent",
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

    child_system_prompt = model.seen_messages[1][0].content
    child_user_prompt = model.seen_messages[1][1].content
    assert child_user_prompt == "create a TMS ticket"
    assert "上游系统提供的附件:" in child_system_prompt
    assert "att-1" in child_system_prompt
    assert "image.png" in child_system_prompt
    assert "tms-bff" in child_system_prompt
    assert "https://tms.example.com/files/image.png" in child_system_prompt
    assert "trace-1" in child_system_prompt
    assert "token=secret" not in child_system_prompt
    assert "accessToken" not in child_system_prompt
    assert "hidden" not in child_system_prompt


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
            "name": "invoke_business_agent",
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

    child_system_prompt = model.seen_messages[1][0].content
    child_user_prompt = model.seen_messages[1][1].content
    assert child_user_prompt == "handle child work"
    assert "可见父级/根上下文摘要:" not in child_system_prompt
    assert "User skill should not see passthrough context." not in child_system_prompt


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


def test_llm_agent_writes_runtime_message_event_jsonl(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "runtime_message_event_log_enabled", True)
    data_root = tmp_path / "data"
    context_id = "bctx_20260521_aa_eventlog"
    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="event_log_skill",
        name="event_log_skill",
        description="Writes event log.",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="lgt_event_log_001",
        skill_id="event_log_skill",
        skill_input={},
        conversation_id=context_id,
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
        task_id="lgt_event_log_001",
        frame_id=frame_id,
        prompt="hi",
    )

    log_path = (
        session_data_dir(data_root, ("2026", "05", "21"), context_id)
        / "logs" / "runtime-message-events"
        / f"lgt_event_log_001_{frame_id}.jsonl"
    )
    assert log_path.exists()
    events = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
    event_types = [event["eventType"] for event in events]
    assert event_types[:2] == ["message", "message"]
    assert "assistant" in event_types
    assert "assistant_tool_call" in event_types
    assert "tool_result" in event_types
    assert event_types[-1] == "checkpoint"
    assert events[0]["message"]["role"] == "system"
    assert events[1]["message"]["role"] == "user"
    assert events[1]["message"]["content"] == "hi"
    tool_call_event = next(event for event in events if event["eventType"] == "assistant_tool_call")
    assert tool_call_event["toolCall"]["name"] == "submit_skill_result"
    tool_result_event = next(event for event in events if event["eventType"] == "tool_result")
    assert tool_result_event["message"]["role"] == "tool"
    assert tool_result_event["message"]["toolCallId"] == "call_submit"
    assert tool_result_event["toolResult"]["ok"] is True


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
    assert system_prompt.index("### MEMORY.md") < system_prompt.index("技能说明:")
    assert "policy rule" in system_prompt
    assert "agent rule" in system_prompt
    assert "memory note" in system_prompt


def test_llm_agent_injects_delegated_workspace_context_without_account_id(tmp_path):
    data_root = tmp_path / "data"
    workspace = tmp_path / "delegated" / "user-001"
    workspace.mkdir(parents=True)
    (workspace / "ACCOUNT_POLICY.md").write_text("delegated policy", encoding="utf-8")
    (workspace / "MEMORY.md").write_text("delegated memory", encoding="utf-8")

    registry = SkillRegistry()
    registry.register(SkillManifest(
        id="delegated_context_skill",
        name="delegated_context_skill",
        description="Uses delegated workspace context.",
        markdown_body="skill instruction",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    ))
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=registry)
    frame_id = runtime.invoke_skill(
        task_id="task_delegated_context_agent_001",
        skill_id="delegated_context_skill",
        skill_input={},
    )
    model = FakeToolCallModel([
        AIMessage(content="", tool_calls=[{
            "id": "call_list_files",
            "name": "list_files",
            "args": {
                "relative_path": ".",
                "recursive": False,
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
    policy = ExecutionPolicy.from_context({
        "execution_policy": {
            "workdir": str(workspace),
            "allowed_dirs": [str(workspace)],
        },
    })

    LlmSkillAgent(model, runtime, data_root=data_root).run(
        task_id="task_delegated_context_agent_001",
        frame_id=frame_id,
        prompt="run",
        runtime_context=policy.to_context(),
    )

    system_prompt = model.seen_messages[0][0].content
    bound_tool_names = {tool["function"]["name"] for tool in model.bound_tools}
    assert "delegated policy" in system_prompt
    assert "delegated memory" in system_prompt
    assert system_prompt.index("### MEMORY.md") < system_prompt.index("技能说明:")
    assert {"list_files", "read_file", "write_file", "patch_file"}.issubset(bound_tool_names)
    assert "ACCOUNT_POLICY.md" in model.seen_messages[1][-1].content


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

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt.startswith("本月运费")
    assert "当前请求时间:" in user_prompt
    assert "- 当前时间: 2026-05-11T10:37:10+08:00" in user_prompt
    assert "- 时区: Asia/Shanghai" in user_prompt
    assert "运行时日期上下文:" in system_prompt
    assert "2026-05-11T10:37:10+08:00" not in system_prompt
    assert "- 时区: Asia/Shanghai" in system_prompt
    assert "- 业务日期: 2026-05-11" in system_prompt
    assert "- 当前月份范围: [2026-05-01, 2026-06-01)" in system_prompt
    assert "解析“本月、今天、昨日、近7天”等相对日期" in system_prompt


def test_llm_agent_keeps_precise_runtime_time_out_of_system_prompt():
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
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt.startswith("本月运费")
    assert "当前请求时间" in user_prompt
    assert "当前时间" in user_prompt
    assert "当前时间" not in system_prompt
    assert "当前月份范围" in system_prompt
    assert "2026-05-11T10:37:10+08:00" not in system_prompt


def test_llm_agent_does_not_generate_request_time_footer_without_current_time():
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
        task_id="task_time_context_003",
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
        task_id="task_time_context_003",
        frame_id=frame_id,
        prompt="本月运费",
        runtime_context={
            "business_date": "2026-05-11",
            "timezone": "Asia/Shanghai",
        },
    )

    system_prompt = model.seen_messages[0][0].content
    user_prompt = model.seen_messages[0][1].content
    assert user_prompt == "本月运费"
    assert "当前请求时间" not in user_prompt
    assert "运行时日期上下文:" in system_prompt
    assert "- 业务日期: 2026-05-11" in system_prompt
    assert "- 当前月份范围: [2026-05-01, 2026-06-01)" in system_prompt
    assert "当前时间" not in system_prompt


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
            "name": "invoke_business_agent",
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
        if event.type == "tool_result" and event.tool_name == "invoke_business_agent"
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
