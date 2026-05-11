"""Tests for the LLM-driven Skill tool-call loop."""

from __future__ import annotations

import json

from langchain_core.messages import AIMessage

from langgraph_biz_worker.models import FrameStatus, SkillManifest
from langgraph_biz_worker.runtime.frame_store import FrameStore
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
