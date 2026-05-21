"""Tests for BizWorker-owned runtime conversation memory."""

from __future__ import annotations

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


def test_runtime_memory_does_not_include_raw_tool_messages():
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
