"""Tests for guarded LLM calls."""

from __future__ import annotations

import time

import pytest

from langgraph_biz_worker.runtime.llm_call_guard import (
    LlmCircuitOpenError,
    LlmConcurrencyLimitError,
    invoke_chat_model,
    reset_llm_call_guard_state_for_tests,
)


class AlwaysTimeoutModel:
    def __init__(self) -> None:
        self.calls = 0

    def invoke(self, messages):
        self.calls += 1
        raise TimeoutError("provider timed out")


class SlowModel:
    def __init__(self, sleep_seconds: float = 0.2) -> None:
        self.calls = 0
        self.sleep_seconds = sleep_seconds

    def invoke(self, messages):
        self.calls += 1
        time.sleep(self.sleep_seconds)
        return "ok"


class RetryThenSuccessModel:
    def __init__(self) -> None:
        self.calls = 0

    def invoke(self, messages):
        self.calls += 1
        if self.calls == 1:
            raise TimeoutError("provider timed out")
        return "ok"


def test_circuit_opens_after_retryable_failure_threshold():
    reset_llm_call_guard_state_for_tests()
    model = AlwaysTimeoutModel()
    context = {
        "llm_request_timeout_seconds": 1,
        "llm_max_retries": 0,
        "llm_circuit_failure_threshold": 1,
        "llm_circuit_open_seconds": 30,
    }

    with pytest.raises(TimeoutError):
        invoke_chat_model(
            model,
            [],
            runtime_context=context,
            operation="test.invoke",
            task_id="task-circuit",
        )

    with pytest.raises(LlmCircuitOpenError) as exc:
        invoke_chat_model(
            model,
            [],
            runtime_context=context,
            operation="test.invoke",
            task_id="task-circuit",
        )

    assert "LLM_CIRCUIT_OPEN" in str(exc.value)
    assert model.calls == 1


def test_timed_out_inflight_call_keeps_concurrency_slot_until_done():
    reset_llm_call_guard_state_for_tests()
    model = SlowModel()
    context = {
        "llm_request_timeout_seconds": 0.02,
        "llm_max_retries": 0,
        "llm_circuit_failure_threshold": 99,
        "llm_max_concurrent_requests": 1,
    }

    with pytest.raises(TimeoutError):
        invoke_chat_model(
            model,
            [],
            runtime_context=context,
            operation="test.invoke",
            task_id="task-timeout-slot",
        )

    with pytest.raises(LlmConcurrencyLimitError) as exc:
        invoke_chat_model(
            model,
            [],
            runtime_context=context,
            operation="test.invoke",
            task_id="task-timeout-slot",
        )

    assert "LLM_CONCURRENCY_LIMIT" in str(exc.value)
    assert model.calls == 1

    time.sleep(0.25)
    context["llm_request_timeout_seconds"] = 0.5
    assert invoke_chat_model(
        model,
        [],
        runtime_context=context,
        operation="test.invoke",
        task_id="task-timeout-slot",
    ) == "ok"
    assert model.calls == 2


def test_retry_progress_event_is_emitted_before_retry():
    reset_llm_call_guard_state_for_tests()
    model = RetryThenSuccessModel()
    events = []
    context = {
        "llm_request_timeout_seconds": 1,
        "llm_max_retries": 1,
        "llm_retry_backoff_seconds": 0,
        "llm_circuit_failure_threshold": 99,
        "_progress_event_sink": events.append,
    }

    assert invoke_chat_model(
        model,
        [],
        runtime_context=context,
        operation="test.invoke",
        task_id="task-retry",
        frame_id="frm-retry",
    ) == "ok"

    assert model.calls == 2
    assert len(events) == 1
    event = events[0]
    assert event.type == "task_progress"
    assert event.progress_type == "llm_retrying"
    assert event.reason == "LLM_REQUEST_TIMEOUT"
    assert event.attempt == 1
    assert event.max_attempts == 2
    assert event.task_id == "task-retry"
    assert event.skill_frame_id == "frm-retry"
