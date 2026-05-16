"""Tests for the LLM-driven Skill tool-call loop."""

from __future__ import annotations

import json

from langchain_core.messages import AIMessage

from langgraph_biz_worker.models import FrameKind, FrameStatus, SkillManifest
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


def _root_with_child_runtime(child_context_visibility: str = "isolated") -> SkillRuntime:
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


def test_llm_agent_bubbles_child_business_function_approval_to_root(monkeypatch):
    runtime = _root_with_child_runtime(child_context_visibility="summary")
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
