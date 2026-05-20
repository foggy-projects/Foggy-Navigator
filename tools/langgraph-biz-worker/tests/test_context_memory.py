"""Tests for BizWorker-owned runtime conversation memory."""

from __future__ import annotations

import pytest

from langgraph_biz_worker.models import SkillFrameState
from langgraph_biz_worker.runtime.context_memory import (
    RUNTIME_CONTEXT_MEMORY_KEY,
    ContextRuntimeBusy,
    ContextRuntimeMemory,
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


def test_same_context_running_loop_raises_busy_before_queue_phase():
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
