from __future__ import annotations

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

from langgraph_biz_worker.config import settings
from langgraph_biz_worker.models import SkillManifest
from langgraph_biz_worker.runtime.llm_message_builder import build_initial_llm_messages
from langgraph_biz_worker.runtime.runtime_message_event_log import (
    record_assistant_runtime_message,
    record_checkpoint_runtime_event,
    record_initial_runtime_messages,
    record_runtime_message_event,
    record_tool_result_runtime_message,
)


def _manifest() -> SkillManifest:
    return SkillManifest(
        id="system.root",
        name="system.root",
        description="Root agent.",
        markdown_body="根 Agent 指令。",
        output_schema={"type": "object"},
        allowed_tools=["submit_skill_result"],
        promote_to_parent=["result_summary", "structured_output"],
    )


def test_build_initial_llm_messages_keeps_runtime_history_as_role_messages():
    messages = build_initial_llm_messages(
        manifest=_manifest(),
        prompt="第二轮问题",
        skill_input={},
        runtime_context={
            "_persistent_frame": True,
            "_runtime_visible_conversation": [
                {"role": "user", "content": "第一轮问题"},
                {
                    "role": "assistant",
                    "content": "",
                    "toolCalls": [{
                        "id": "call_list",
                        "name": "list_skill_resources",
                        "args": {"skill_name": "tms-ticket-agent"},
                    }],
                },
                {
                    "role": "tool",
                    "content": '{"resources": ["SKILL.md"]}',
                    "toolCallId": "call_list",
                },
                {"role": "assistant", "content": "第一轮回答"},
            ],
        },
    )

    assert [message.type for message in messages] == ["system", "human", "ai", "tool", "ai", "human"]
    assert messages[1].content == "第一轮问题"
    assert messages[2].tool_calls[0]["id"] == "call_list"
    assert messages[3].tool_call_id == "call_list"
    assert messages[4].content == "第一轮回答"
    assert messages[5].content == "第二轮问题"
    assert "第一轮问题" not in messages[0].content
    assert "第一轮回答" not in messages[0].content


def test_build_initial_llm_messages_renders_allowed_skills_as_markdown():
    messages = build_initial_llm_messages(
        manifest=_manifest(),
        prompt="hi",
        skill_input={
            "allowed_skills": [
                {
                    "id": "tms-ticket-agent",
                    "name": "TMS 工单 Agent",
                    "description": "创建和查询 TMS 工单。",
                }
            ],
            "business_order_no": "ORD-1",
        },
        runtime_context={"_persistent_frame": True},
    )

    system_prompt = messages[0].content
    assert "可用业务技能:" in system_prompt
    assert "- `tms-ticket-agent`（TMS 工单 Agent）: 创建和查询 TMS 工单。" in system_prompt
    assert '"allowed_skills"' not in system_prompt
    assert "业务上下文:" in system_prompt
    assert "ORD-1" in system_prompt


def test_build_initial_llm_messages_restores_runtime_protocol_messages(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "runtime_message_event_log_enabled", True)
    data_root = tmp_path / "data"
    context_id = "bctx_20260521_ab_restore"
    frame_id = "frm_restore"
    task_id = "lgt_restore_001"
    runtime_context = {
        "_llm_submission_data_root": str(data_root),
        "_llm_submission_session_id": context_id,
        "_llm_submission_require_standard_context": True,
        "_llm_submission_date_parts": ("2026", "05", "21"),
    }

    record_initial_runtime_messages(
        [SystemMessage(content="旧 system"), HumanMessage(content="U1")],
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
    )
    record_assistant_runtime_message(
        AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {"summary": "A1"},
        }]),
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
    )
    record_tool_result_runtime_message(
        ToolMessage(content='{"ok": true}', tool_call_id="call_submit"),
        {"ok": True},
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
    )
    record_checkpoint_runtime_event(
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
        checkpoint="suspended",
    )

    messages = build_initial_llm_messages(
        manifest=_manifest(),
        prompt="U2",
        skill_input={},
        runtime_context={
            **runtime_context,
            "_runtime_protocol_recovery": {"enabled": True, "frame_id": frame_id},
        },
    )

    assert [message.type for message in messages] == ["system", "human", "ai", "tool", "human"]
    assert messages[0].content != "旧 system"
    assert messages[1].content == "U1"
    assert messages[2].tool_calls[0]["id"] == "call_submit"
    assert messages[3].tool_call_id == "call_submit"
    assert messages[4].content == "U2"


def test_build_initial_llm_messages_ignores_invalid_open_tool_call_recovery(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "runtime_message_event_log_enabled", True)
    data_root = tmp_path / "data"
    context_id = "bctx_20260521_ab_invalid"
    frame_id = "frm_invalid"
    task_id = "lgt_invalid_001"
    runtime_context = {
        "_llm_submission_data_root": str(data_root),
        "_llm_submission_session_id": context_id,
        "_llm_submission_require_standard_context": True,
        "_llm_submission_date_parts": ("2026", "05", "21"),
    }

    record_initial_runtime_messages(
        [SystemMessage(content="旧 system"), HumanMessage(content="U1")],
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
    )
    record_assistant_runtime_message(
        AIMessage(content="", tool_calls=[{
            "id": "call_open",
            "name": "submit_skill_result",
            "args": {"summary": "A1"},
        }]),
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
    )
    record_checkpoint_runtime_event(
        runtime_context,
        task_id=task_id,
        frame_id=frame_id,
        checkpoint="suspended",
    )

    messages = build_initial_llm_messages(
        manifest=_manifest(),
        prompt="U2",
        skill_input={},
        runtime_context={
            **runtime_context,
            "_runtime_protocol_recovery": {"enabled": True, "frame_id": frame_id},
        },
    )

    assert [message.type for message in messages] == ["system", "human"]
    assert messages[1].content == "U2"


def test_runtime_message_event_log_requires_standard_context_id(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "runtime_message_event_log_enabled", True)

    path = record_runtime_message_event(
        "message",
        {
            "_llm_submission_data_root": str(tmp_path / "data"),
            "_llm_submission_session_id": "legacy-session",
            "_llm_submission_date_parts": ("2026", "05", "21"),
        },
        task_id="lgt_legacy",
        frame_id="frm_legacy",
        message=HumanMessage(content="hello"),
    )

    assert path is None
    assert not (tmp_path / "data" / "runtime" / "sessions" / "by-date").exists()
