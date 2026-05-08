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
    assert "mock_get_order" not in {t["function"]["name"] for t in model.bound_tools}
    assert "frozen manifest" in model.seen_messages[0][0].content
