"""Tests for low-level LLM tool dispatch behavior."""

from __future__ import annotations

from typing import Any

from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.llm_tool_dispatcher import (
    LlmToolDispatchContext,
    LlmToolDispatcher,
)
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


def test_business_function_auto_idempotency_key_is_stable_for_same_call() -> None:
    runtime = SkillRuntime(frame_store=FrameStore(), skill_registry=SkillRegistry())
    root_frame_id = runtime.invoke_skill(
        task_id="task-idempotency-001",
        skill_id="system.root",
        skill_input={"request": "create ticket"},
    )
    dispatcher = LlmToolDispatcher(runtime)
    context = LlmToolDispatchContext(
        frame_id=root_frame_id,
        task_id="task-idempotency-001",
        runtime_context={"task_scoped_token": "runtime-token"},
    )
    args = {
        "function_id": "tms.ticket.create",
        "version": "v1",
        "input": {
            "orderNo": "ORD-001",
            "priority": "HIGH",
        },
    }
    gateway_idempotency_keys: list[str | None] = []
    finalized_call_args: list[dict[str, Any]] = []

    def fake_invoke(
        task_scoped_token: str,
        function_id: str | None = None,
        version: str | None = None,
        input_data: dict[str, Any] | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        gateway_idempotency_keys.append(idempotency_key)
        return {
            "functionId": function_id,
            "version": version,
            "status": "SUCCESS",
            "input": input_data,
        }

    def finalize_business_function_call(**kwargs: Any) -> dict[str, Any]:
        finalized_call_args.append(kwargs["call_args"])
        runtime.complete_function_call(
            kwargs["function_frame_id"],
            result=kwargs["result"]["result"],
        )
        return kwargs["result"]

    first = dispatcher.dispatch_business_function(
        "invoke_business_function",
        dict(args),
        context,
        finalize_business_function_call,
        invoke_business_function_fn=fake_invoke,
    )
    second = dispatcher.dispatch_business_function(
        "invoke_business_function",
        dict(args),
        context,
        finalize_business_function_call,
        invoke_business_function_fn=fake_invoke,
    )

    function_frames = [
        frame for frame in runtime.get_frames_by_task("task-idempotency-001")
        if frame.frame_id.startswith("fn_")
    ]

    assert first["ok"] is True
    assert second["ok"] is True
    assert len(gateway_idempotency_keys) == 2
    assert gateway_idempotency_keys[0] == gateway_idempotency_keys[1]
    assert gateway_idempotency_keys[0].startswith(
        f"navigator:{root_frame_id}:tms.ticket.create:"
    )
    assert [frame.input["idempotency_key"] for frame in function_frames] == [
        gateway_idempotency_keys[0],
        gateway_idempotency_keys[0],
    ]
    assert [call_args["idempotency_key"] for call_args in finalized_call_args] == [
        gateway_idempotency_keys[0],
        gateway_idempotency_keys[0],
    ]
