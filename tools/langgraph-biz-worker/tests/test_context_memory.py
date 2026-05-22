"""Tests for BizWorker-owned runtime conversation memory."""

from __future__ import annotations

import json

import pytest

from langgraph_biz_worker.models import SkillFrameState
from langgraph_biz_worker.runtime.context_memory import (
    RUNTIME_CONTEXT_MEMORY_KEY,
    ContextRuntimeBusy,
    ContextRuntimeMemory,
    _redact_sensitive_text,
    assistant_visible_content,
    load_from_root_frame,
    save_to_root_frame,
)


def test_context_memory_begin_commit_turn():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-memory")

    memory.begin_turn(
        task_id="task_001",
        root_frame_id="frm_root",
        user_message="U1",
        now="2026-05-21T00:00:00Z",
    )

    assert memory.pending_turn is not None
    assert memory.loop_status == "RUNNING"
    assert memory.running_task_id == "task_001"

    committed = memory.commit_turn(
        assistant_message="A1",
        now="2026-05-21T00:00:01Z",
    )

    assert committed is True
    assert memory.pending_turn is None
    assert memory.loop_status == "IDLE"
    assert memory.revision == 1
    assert [(item["role"], item["content"]) for item in memory.visible_messages] == [
        ("user", "U1"),
        ("assistant", "A1"),
    ]


def test_context_memory_drains_queued_user_input_into_committed_turn():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-queue")
    memory.begin_turn(
        task_id="task_001",
        root_frame_id="frm_root",
        user_message="U1",
        now="2026-05-21T00:00:00Z",
    )
    queued = memory.enqueue_user_input(
        task_id="task_002",
        root_frame_id="frm_root",
        user_message="U2",
        now="2026-05-21T00:00:01Z",
    )

    assert queued is not None
    assert [item["content"] for item in memory.pending_user_inputs] == ["U2"]

    drained = memory.drain_pending_user_inputs(now="2026-05-21T00:00:02Z")
    committed = memory.commit_turn(
        assistant_message="A after queued input",
        now="2026-05-21T00:00:03Z",
    )

    assert [item["content"] for item in drained] == ["U2"]
    assert committed is True
    assert memory.pending_user_inputs == []
    assert [(item["role"], item["content"]) for item in memory.visible_messages] == [
        ("user", "U1"),
        ("user", "U2"),
        ("assistant", "A after queued input"),
    ]
    assert memory.visible_messages[1]["metadata"]["queueStatus"] == "IN_FLIGHT"


def test_context_memory_commit_rejection_clears_pending_turn():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-commit-reject")
    memory.begin_turn(
        task_id="task_001",
        root_frame_id="frm_root",
        user_message="U1",
        now="2026-05-21T00:00:00Z",
    )

    committed = memory.commit_turn(
        assistant_message="   ",
        now="2026-05-21T00:00:01Z",
    )

    assert committed is False
    assert memory.pending_turn is None
    assert memory.loop_status == "IDLE"
    assert memory.running_task_id is None

    memory.begin_turn(
        task_id="task_002",
        root_frame_id="frm_root",
        user_message="U2",
        now="2026-05-21T00:00:02Z",
    )
    assert memory.pending_turn["taskId"] == "task_002"


def test_recent_conversation_bootstrap_only_when_memory_empty():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-bootstrap")

    assert memory.bootstrap_from_external_recent_conversation(
        [
            {"role": "user", "content": "old U"},
            {"role": "assistant", "content": "old A"},
        ],
        task_id="task_bootstrap",
        root_frame_id="frm_root",
        now="2026-05-21T00:00:00Z",
    )

    assert memory.revision == 1
    assert [item["content"] for item in memory.visible_messages] == ["old U", "old A"]

    assert not memory.bootstrap_from_external_recent_conversation(
        [{"role": "user", "content": "should not override"}],
        task_id="task_bootstrap_2",
        root_frame_id="frm_root",
        now="2026-05-21T00:00:02Z",
    )
    assert [item["content"] for item in memory.visible_messages] == ["old U", "old A"]


def test_external_recent_conversation_bootstrap_does_not_import_raw_tool_messages():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-tool")

    memory.bootstrap_from_external_recent_conversation(
        [
            {"role": "user", "content": "create ticket"},
            {"role": "tool", "content": "{\"secret\":\"raw\"}"},
            {"role": "assistant", "content": "ticket created"},
        ],
        task_id="task_tool",
        root_frame_id="frm_root",
    )

    assert [(item["role"], item["content"]) for item in memory.build_prompt_view()] == [
        ("user", "create ticket"),
        ("assistant", "ticket created"),
    ]


def test_context_memory_commits_root_visible_tool_protocol():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-tool-protocol")
    memory.begin_turn(
        task_id="task_tool_protocol",
        root_frame_id="frm_root",
        user_message="create ticket",
        now="2026-05-21T00:00:00Z",
    )

    committed = memory.commit_turn(
        assistant_message="ticket created",
        protocol_messages=[
            {"role": "user", "content": "create ticket"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{
                    "id": "call_ticket",
                    "name": "invoke_business_skill",
                    "args": {"skill_name": "tms-ticket-agent"},
                }],
            },
            {
                "role": "tool",
                "content": '{"ok": true, "result": "ticket created"}',
                "toolCallId": "call_ticket",
            },
            {"role": "assistant", "content": "ticket created"},
        ],
        now="2026-05-21T00:00:01Z",
    )

    assert committed is True
    prompt = memory.build_prompt_view()
    assert [item["role"] for item in prompt] == ["user", "assistant", "tool", "assistant"]
    assert prompt[1]["toolCalls"][0]["name"] == "invoke_business_skill"
    assert prompt[2]["toolCallId"] == "call_ticket"
    assert prompt[3]["content"] == "ticket created"


def test_context_memory_prompt_tail_is_semantic_turn_aware():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-turn-tail")
    memory.limits.update({
        "maxPromptMessages": 3,
        "maxPromptChars": 10000,
        "maxVisibleMessages": 100,
        "maxVisibleChars": 10000,
    })

    for index in range(1, 4):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=f"U{index}",
        )
        assert memory.commit_turn(assistant_message=f"A{index}")

    prompt = memory.build_prompt_view()

    assert [(item["role"], item["content"]) for item in prompt] == [
        ("user", "U3"),
        ("assistant", "A3"),
    ]


def test_context_memory_default_prompt_keeps_small_history_before_budget():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-default-window")

    for index in range(1, 8):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=f"U{index}",
        )
        assert memory.commit_turn(assistant_message=f"A{index}")

    prompt = memory.build_prompt_view()

    assert [(item["role"], item["content"]) for item in prompt] == [
        ("user", "U1"),
        ("assistant", "A1"),
        ("user", "U2"),
        ("assistant", "A2"),
        ("user", "U3"),
        ("assistant", "A3"),
        ("user", "U4"),
        ("assistant", "A4"),
        ("user", "U5"),
        ("assistant", "A5"),
        ("user", "U6"),
        ("assistant", "A6"),
        ("user", "U7"),
        ("assistant", "A7"),
    ]


def test_context_memory_prompt_tail_keeps_tool_protocol_group_together():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-tool-tail")
    memory.limits.update({
        "maxPromptMessages": 3,
        "maxPromptChars": 10000,
        "maxVisibleMessages": 100,
        "maxVisibleChars": 10000,
    })

    memory.begin_turn(
        task_id="task_001",
        root_frame_id="frm_root",
        user_message="U1",
    )
    assert memory.commit_turn(assistant_message="A1")
    memory.begin_turn(
        task_id="task_002",
        root_frame_id="frm_root",
        user_message="call tool",
    )
    assert memory.commit_turn(
        assistant_message="tool done",
        protocol_messages=[
            {"role": "user", "content": "call tool", "taskId": "task_002"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{"id": "call_1", "name": "list_skill_resources", "args": {}}],
            },
            {"role": "tool", "content": '{"ok": true}', "toolCallId": "call_1"},
            {"role": "assistant", "content": "tool done"},
        ],
    )

    prompt = memory.build_prompt_view()

    assert [item["role"] for item in prompt] == ["user", "assistant", "tool", "assistant"]
    assert prompt[1]["toolCalls"][0]["id"] == "call_1"
    assert prompt[2]["toolCallId"] == "call_1"


def test_context_memory_uses_independent_tool_result_and_args_limits():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-tool-budget")
    memory.limits.update({
        "maxMessageChars": 20,
        "maxToolResultChars": 50,
        "maxToolCallArgsChars": 30,
    })
    memory.begin_turn(
        task_id="task_tool_budget",
        root_frame_id="frm_root",
        user_message="call large tool",
    )

    assert memory.commit_turn(
        assistant_message="done",
        protocol_messages=[
            {"role": "user", "content": "call large tool", "taskId": "task_tool_budget"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{
                    "id": "call_large",
                    "name": "read_big_file",
                    "args": {"path": "a" * 200},
                }],
            },
            {"role": "tool", "content": "x" * 200, "toolCallId": "call_large"},
            {"role": "assistant", "content": "done"},
        ],
    )

    prompt = memory.build_prompt_view()

    assert prompt[2]["content"] == "x" * 200
    assert prompt[1]["toolCalls"][0]["args"]["_truncated"] is True
    assert prompt[1]["toolCalls"][0]["args"]["_original_chars"] > 30


def test_context_memory_projects_only_historical_large_tool_results():
    memory = ContextRuntimeMemory(context_id="bctx_20260523_ab_ctx-tool-projection")
    memory.limits.update({
        "maxPromptMessages": 100,
        "maxPromptChars": 10000,
        "maxVisibleMessages": 100,
        "maxVisibleChars": 10000,
        "maxMessageChars": 80,
        "maxToolResultChars": 512,
        "rawToolResultTailTurnCount": 1,
    })
    first_tool_result = json.dumps({
        "ok": True,
        "ticketId": "TICKET-OLD",
        "execution_report_ref": "frame-report://task_old/frm_old",
        "payload": "x" * 1000,
    }, ensure_ascii=False)
    second_tool_result = json.dumps({
        "ok": True,
        "ticketId": "TICKET-NEW",
        "execution_report_ref": "frame-report://task_new/frm_new",
        "payload": "y" * 1000,
    }, ensure_ascii=False)

    memory.begin_turn(
        task_id="task_old",
        root_frame_id="frm_root",
        user_message="call old tool",
    )
    assert memory.commit_turn(
        assistant_message="old done",
        protocol_messages=[
            {"role": "user", "content": "call old tool", "taskId": "task_old"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{"id": "call_old", "name": "query_ticket", "args": {}}],
            },
            {"role": "tool", "content": first_tool_result, "toolCallId": "call_old"},
            {"role": "assistant", "content": "old done"},
        ],
    )
    memory.begin_turn(
        task_id="task_new",
        root_frame_id="frm_root",
        user_message="call new tool",
    )
    assert memory.commit_turn(
        assistant_message="new done",
        protocol_messages=[
            {"role": "user", "content": "call new tool", "taskId": "task_new"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{"id": "call_new", "name": "query_ticket", "args": {}}],
            },
            {"role": "tool", "content": second_tool_result, "toolCallId": "call_new"},
            {"role": "assistant", "content": "new done"},
        ],
    )

    prompt = memory.build_prompt_view()
    tool_messages = [item for item in prompt if item["role"] == "tool"]

    assert len(tool_messages) == 2
    old_projection = json.loads(tool_messages[0]["content"])
    assert old_projection["projection"] == "historical_large_tool_result"
    assert old_projection["digest"].startswith("sha256:")
    assert old_projection["selectedFields"]["ticketId"] == "TICKET-OLD"
    assert old_projection["refs"] == ["frame-report://task_old/frm_old"]
    assert "x" * 200 not in tool_messages[0]["content"]
    assert json.loads(tool_messages[1]["content"])["payload"] == "y" * 1000


def test_context_memory_can_disable_historical_tool_result_projection():
    memory = ContextRuntimeMemory(context_id="bctx_20260523_ab_ctx-tool-projection-off")
    memory.limits.update({
        "maxPromptMessages": 100,
        "maxPromptChars": 10000,
        "maxVisibleMessages": 100,
        "maxVisibleChars": 10000,
        "maxMessageChars": 80,
        "maxToolResultChars": 256,
        "projectHistoricalToolResults": False,
        "rawToolResultTailTurnCount": 0,
    })
    tool_result = json.dumps({
        "ok": True,
        "ticketId": "TICKET-RAW",
        "execution_report_ref": "frame-report://task_raw/frm_raw",
        "payload": "z" * 1000,
    }, ensure_ascii=False)

    memory.begin_turn(
        task_id="task_raw",
        root_frame_id="frm_root",
        user_message="call raw tool",
    )
    assert memory.commit_turn(
        assistant_message="raw done",
        protocol_messages=[
            {"role": "user", "content": "call raw tool", "taskId": "task_raw"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{"id": "call_raw", "name": "query_ticket", "args": {}}],
            },
            {"role": "tool", "content": tool_result, "toolCallId": "call_raw"},
            {"role": "assistant", "content": "raw done"},
        ],
    )

    prompt = memory.build_prompt_view()
    tool_messages = [item for item in prompt if item["role"] == "tool"]

    assert len(tool_messages) == 1
    assert tool_messages[0]["content"] == tool_result


def test_context_memory_accepts_submitted_user_prompt_with_runtime_time_block():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-user-prompt")
    memory.begin_turn(
        task_id="task_user_prompt",
        root_frame_id="frm_root",
        user_message="hi",
        now="2026-05-21T00:00:00Z",
    )

    assert memory.commit_turn(
        assistant_message="hello",
        protocol_messages=[
            {
                "role": "user",
                "content": "hi\n\n---\n运行时上下文:\n- 当前时间: 2026-05-21T00:00:00+08:00",
                "taskId": "task_user_prompt",
            },
            {"role": "assistant", "content": "hello"},
        ],
    )

    assert memory.visible_messages[0]["content"].startswith("hi\n\n---")
    assert memory.visible_messages[1]["content"] == "hello"


def test_assistant_visible_content_prefers_user_facing_output():
    assert assistant_visible_content(
        {"message": "user-facing message", "debug": "internal"},
        "internal summary",
    ) == "user-facing message"
    assert assistant_visible_content({"debug": "internal"}, "fallback summary") == "fallback summary"


def test_context_memory_persists_in_root_frame():
    frame = SkillFrameState(
        frame_id="frm_root",
        task_id="task_frame",
        skill_id="system.root",
        conversation_id="bctx_20260521_ab_ctx-frame",
    )
    memory = ContextRuntimeMemory(context_id=frame.conversation_id)
    memory.begin_turn(
        task_id="task_frame",
        root_frame_id=frame.frame_id,
        user_message="hello",
    )
    memory.commit_turn(assistant_message="hi")

    save_to_root_frame(frame, memory)
    restored = load_from_root_frame(frame)

    assert RUNTIME_CONTEXT_MEMORY_KEY in frame.private_working_state
    assert restored.revision == 1
    assert [item["content"] for item in restored.visible_messages] == ["hello", "hi"]


def test_context_memory_lazy_compaction_does_not_call_summarizer_under_budget():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-lazy")
    memory.limits.update({
        "maxVisibleMessages": 10,
        "maxVisibleChars": 10000,
    })

    memory.begin_turn(
        task_id="task_001",
        root_frame_id="frm_root",
        user_message="U1",
    )

    def fail_summarizer(_payload: dict[str, object]) -> dict[str, object]:
        raise AssertionError("summarizer should not run under budget")

    assert memory.commit_turn(
        assistant_message="A1",
        summarizer=fail_summarizer,
    )
    assert memory.compacted_summary is None
    assert [item["content"] for item in memory.visible_messages] == ["U1", "A1"]


def test_context_memory_compaction_keeps_head_summary_and_tail():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-compact")
    memory.limits.update({
        "maxVisibleMessages": 6,
        "maxVisibleChars": 10000,
        "headTurnCount": 1,
        "tailTurnCount": 1,
        "maxPromptMessages": 3,
    })

    for index in range(1, 5):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=f"U{index}",
        )
        assert memory.commit_turn(assistant_message=f"A{index}")

    prompt = memory.build_prompt_view()

    assert [item["content"] for item in memory.pinned_head_messages] == ["U1", "A1"]
    assert [item["content"] for item in memory.visible_messages] == ["U4", "A4"]
    assert memory.compacted_summary is not None
    assert [item["content"] for item in prompt[:2]] == ["U1", "A1"]
    assert prompt[2]["messageId"] == "rtm_compacted_summary"
    assert [item["content"] for item in prompt[-2:]] == ["U4", "A4"]
    assert {item.get("taskId") for item in prompt if item.get("taskId")} == {
        "task_001",
        "task_004",
    }


def test_context_memory_prompt_budget_triggers_compaction_before_tail_cut():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-prompt-budget")
    memory.limits.update({
        "maxVisibleMessages": 100,
        "maxVisibleChars": 10000,
        "maxPromptMessages": 6,
        "maxPromptChars": 10000,
        "headTurnCount": 1,
        "tailTurnCount": 1,
    })

    for index in range(1, 5):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=f"U{index}",
        )
        assert memory.commit_turn(assistant_message=f"A{index}")

    assert memory.compacted_summary is None
    status = memory.prompt_budget_status()
    assert status["wouldClip"] is True
    assert status["projectedVisibleMessageCount"] == 8
    assert status["remainingMessages"] == 6
    assert [item["content"] for item in memory.build_prompt_view()] == [
        "U2",
        "A2",
        "U3",
        "A3",
        "U4",
        "A4",
    ]

    summarizer_payloads: list[dict[str, object]] = []

    def summarizer(payload: dict[str, object]) -> dict[str, object]:
        summarizer_payloads.append(payload)
        return {
            "durableUserIntent": "compressed middle turns",
            "pendingActions": ["continue latest task"],
        }

    assert memory.compact_for_prompt_budget(summarizer=summarizer) is True

    assert summarizer_payloads
    assert summarizer_payloads[0]["reason"] == "prompt_budget"
    assert [item["content"] for item in memory.pinned_head_messages] == ["U1", "A1"]
    assert [item["content"] for item in memory.visible_messages] == ["U4", "A4"]
    prompt = memory.build_prompt_view()
    prompt_text = "\n".join(item["content"] for item in prompt)
    assert "compressed middle turns" in prompt_text
    assert "U2" not in [item["content"] for item in prompt]
    assert [item["content"] for item in prompt[-2:]] == ["U4", "A4"]


def test_context_memory_visible_char_budget_triggers_compaction():
    memory = ContextRuntimeMemory(context_id="bctx_20260522_ab_ctx-char-budget")
    memory.limits.update({
        "maxVisibleMessages": 100,
        "maxVisibleChars": 30,
        "maxPromptMessages": 100,
        "headTurnCount": 1,
        "tailTurnCount": 1,
    })
    summarizer_payloads: list[dict[str, object]] = []

    def summarizer(payload: dict[str, object]) -> dict[str, object]:
        summarizer_payloads.append(payload)
        return {"durableUserIntent": "char budget summary"}

    for index in range(1, 4):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=f"U{index}-long-message",
        )
        assert memory.commit_turn(
            assistant_message=f"A{index}-long-answer",
            summarizer=summarizer if index == 3 else None,
        )

    assert summarizer_payloads
    assert summarizer_payloads[0]["reason"] == "visible_window"
    assert memory.compacted_summary is not None
    assert memory.compacted_summary["durableUserIntent"] == "char budget summary"
    assert [item["content"] for item in memory.visible_messages] == [
        "U3-long-message",
        "A3-long-answer",
    ]


def test_context_memory_compaction_fallback_redacts_sensitive_text():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-redact")
    memory.limits.update({
        "maxVisibleMessages": 6,
        "maxVisibleChars": 10000,
        "headTurnCount": 1,
        "tailTurnCount": 1,
    })

    def fail_summarizer(_payload: dict[str, object]) -> dict[str, object]:
        raise RuntimeError("llm unavailable")

    for index in range(1, 5):
        user_message = (
            "retry with token=topsecret and Bearer abc123"
            if index == 2
            else f"U{index}"
        )
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=user_message,
        )
        summarizer = fail_summarizer if index == 4 else None
        assert memory.commit_turn(assistant_message=f"A{index}", summarizer=summarizer)

    assert memory.compacted_summary is not None
    assert memory.compacted_summary["summaryQuality"] == "fallback"
    prompt_text = "\n".join(item["content"] for item in memory.build_prompt_view())
    assert "topsecret" not in prompt_text
    assert "abc123" not in prompt_text
    assert "[REDACTED]" in prompt_text


def test_context_memory_redacts_json_style_secrets():
    redacted = _redact_sensitive_text(
        '{"token":"abc123", "password":"pw123", "secret":"sec123", '
        '"api_key":"sk123", "nested":{"access_token":"access123"}}'
    )

    for leaked in ("abc123", "pw123", "sec123", "sk123", "access123"):
        assert leaked not in redacted
    assert redacted.count("[REDACTED]") == 5


def test_context_memory_compaction_uses_summarizer_output_when_available():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-llm-summary")
    memory.limits.update({
        "maxVisibleMessages": 6,
        "maxVisibleChars": 10000,
        "headTurnCount": 1,
        "tailTurnCount": 1,
    })
    summarizer_payloads: list[dict[str, object]] = []

    for index in range(1, 5):
        memory.begin_turn(
            task_id=f"task_{index:03d}",
            root_frame_id="frm_root",
            user_message=(
                'U2 {"token":"secret123", "password":"pw123"} Bearer bearer123'
                if index == 2
                else f"U{index}"
            ),
        )

        def summarizer(payload: dict[str, object]) -> dict[str, object]:
            summarizer_payloads.append(payload)
            return {
                "durableUserIntent": "LLM summary: user wants a TMS ticket",
                "pendingActions": ["collect missing ticket title"],
            }

        assert memory.commit_turn(
            assistant_message=f"A{index}",
            summarizer=summarizer if index == 4 else None,
        )

    assert memory.compacted_summary is not None
    assert memory.compacted_summary["summaryQuality"] == "llm"
    assert summarizer_payloads
    summarizer_input = str(summarizer_payloads[0]["messages"])
    assert "secret123" not in summarizer_input
    assert "pw123" not in summarizer_input
    assert "bearer123" not in summarizer_input
    assert "[REDACTED]" in summarizer_input
    prompt_text = "\n".join(item["content"] for item in memory.build_prompt_view())
    assert "LLM summary: user wants a TMS ticket" in prompt_text
    assert "collect missing ticket title" in prompt_text


def test_begin_turn_still_rejects_second_physical_loop_for_same_context():
    memory = ContextRuntimeMemory(context_id="bctx_20260521_ab_ctx-busy")
    memory.begin_turn(
        task_id="task_running",
        root_frame_id="frm_root",
        user_message="first",
    )

    with pytest.raises(ContextRuntimeBusy):
        memory.begin_turn(
            task_id="task_second",
            root_frame_id="frm_root",
            user_message="second",
        )
