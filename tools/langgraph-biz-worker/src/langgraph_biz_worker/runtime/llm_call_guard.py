"""Guarded LangChain model calls for Biz Worker runtime.

This module keeps provider calls bounded and observable. It deliberately avoids
recording prompts, API keys, or full endpoint URLs in raised errors.
"""

from __future__ import annotations

import concurrent.futures
import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import urlsplit

from ..config import settings
from ..models import QueryEvent

logger = logging.getLogger(__name__)

_DEADLINE_KEY = "_llm_deadline_monotonic"
_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"


class LlmRequestTimeoutError(TimeoutError):
    """Raised when a guarded model call exceeds its request timeout."""


class LlmCircuitOpenError(RuntimeError):
    """Raised when repeated provider failures open the local circuit."""


class LlmConcurrencyLimitError(RuntimeError):
    """Raised when local LLM request concurrency is saturated."""


@dataclass(frozen=True)
class LlmCallPolicy:
    request_timeout_seconds: float
    execution_deadline_seconds: float
    max_retries: int
    retry_backoff_seconds: float
    circuit_failure_threshold: int
    circuit_open_seconds: float
    max_concurrent_requests: int


@dataclass
class _CircuitState:
    failures: int = 0
    open_until: float = 0.0


_circuit_lock = threading.Lock()
_circuit_states: dict[str, _CircuitState] = {}
_semaphore_lock = threading.Lock()
_semaphore_capacity = 0
_semaphore: threading.BoundedSemaphore | None = None


class _ReleaseOnce:
    def __init__(self, release: Callable[[], None]) -> None:
        self._release = release
        self._released = False
        self._lock = threading.Lock()

    def __call__(self) -> None:
        with self._lock:
            if self._released:
                return
            self._released = True
        self._release()


def invoke_chat_model(
    model: Any,
    messages: list[Any],
    *,
    runtime_context: dict[str, Any] | None = None,
    operation: str,
    task_id: str = "",
    frame_id: str = "",
) -> Any:
    """Invoke a sync LangChain chat model with timeout, retry, and circuit guard."""
    context = runtime_context if isinstance(runtime_context, dict) else {}
    policy = _policy_from_context(context)
    deadline_at = _ensure_deadline(context, policy)
    provider_key = _provider_key(model)
    attempts = policy.max_retries + 1
    last_exc: BaseException | None = None

    for attempt in range(1, attempts + 1):
        remaining = _remaining_seconds(deadline_at)
        if remaining <= 0:
            raise LlmRequestTimeoutError(_timeout_message(
                operation,
                provider_key,
                task_id,
                frame_id,
                "execution deadline exceeded",
                policy.execution_deadline_seconds,
            ))

        _raise_if_circuit_open(provider_key, operation, task_id, frame_id)
        request_timeout = min(policy.request_timeout_seconds, remaining)
        semaphore = _request_semaphore(policy.max_concurrent_requests)
        acquired = semaphore.acquire(timeout=max(0.001, min(request_timeout, remaining)))
        if not acquired:
            raise LlmConcurrencyLimitError(_guard_message(
                "LLM_CONCURRENCY_LIMIT",
                operation,
                provider_key,
                task_id,
                frame_id,
                f"max_concurrent_requests={policy.max_concurrent_requests}",
            ))
        release_slot = _ReleaseOnce(semaphore.release)

        try:
            result = _call_with_timeout(
                lambda: model.invoke(messages),
                timeout_seconds=request_timeout,
                operation=operation,
                provider_key=provider_key,
                task_id=task_id,
                frame_id=frame_id,
                on_complete=release_slot,
            )
            _record_success(provider_key)
            return result
        except BaseException as exc:
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            last_exc = exc
            retryable = _is_retryable_exception(exc)
            if retryable:
                _record_failure(provider_key, policy)
            if not retryable or attempt >= attempts:
                raise

            sleep_seconds = min(policy.retry_backoff_seconds * attempt, max(0.0, _remaining_seconds(deadline_at)))
            logger.warning(
                "Retrying guarded LLM call: operation=%s task_id=%s frame_id=%s attempt=%s/%s error=%s",
                operation,
                task_id,
                frame_id,
                attempt,
                attempts,
                _safe_error(exc),
            )
            _emit_retry_progress(
                context,
                operation=operation,
                task_id=task_id,
                frame_id=frame_id,
                exc=exc,
                attempt=attempt,
                attempts=attempts,
                sleep_seconds=sleep_seconds,
                deadline_at=deadline_at,
            )
            if sleep_seconds > 0:
                time.sleep(sleep_seconds)

    if last_exc is not None:
        raise last_exc
    raise RuntimeError("LLM call failed without an exception")


def reset_llm_call_guard_state_for_tests() -> None:
    """Reset circuit state between targeted tests."""
    global _semaphore, _semaphore_capacity
    with _circuit_lock:
        _circuit_states.clear()
    with _semaphore_lock:
        _semaphore = None
        _semaphore_capacity = 0


def _emit_retry_progress(
    context: dict[str, Any],
    *,
    operation: str,
    task_id: str,
    frame_id: str,
    exc: BaseException,
    attempt: int,
    attempts: int,
    sleep_seconds: float,
    deadline_at: float,
) -> None:
    sink = context.get(_PROGRESS_EVENT_SINK_KEY)
    if not callable(sink):
        return
    reason = _retry_progress_reason(exc)
    retry_after_ms = int(max(0.0, sleep_seconds) * 1000)
    remaining_ms = int(_remaining_seconds(deadline_at) * 1000)
    payload = {
        "progressType": "llm_retrying",
        "reason": reason,
        "operation": operation,
        "taskId": task_id,
        "frameId": frame_id,
        "attempt": attempt,
        "maxAttempts": attempts,
        "nextRetryAfterMs": retry_after_ms,
        "remainingMs": remaining_ms,
        "presentationHint": "debug_detail",
    }
    try:
        sink(QueryEvent(
            type="task_progress",
            content=f"LLM call retrying after {reason} ({attempt}/{attempts})",
            task_id=task_id,
            skill_frame_id=frame_id or None,
            reason=reason,
            progress_type="llm_retrying",
            attempt=attempt,
            max_attempts=attempts,
            next_retry_after_ms=retry_after_ms,
            remaining_ms=remaining_ms,
            presentation_hint="debug_detail",
            payload=payload,
        ))
    except Exception:
        logger.debug("Failed to emit LLM retry progress event", exc_info=True)


def _call_with_timeout(
    func: Callable[[], Any],
    *,
    timeout_seconds: float,
    operation: str,
    provider_key: str,
    task_id: str,
    frame_id: str,
    on_complete: Callable[[], None],
) -> Any:
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="llm-call")
    try:
        future = executor.submit(func)
    except BaseException:
        on_complete()
        executor.shutdown(wait=False, cancel_futures=True)
        raise

    def _cleanup(_future: concurrent.futures.Future[Any]) -> None:
        try:
            on_complete()
        finally:
            executor.shutdown(wait=False, cancel_futures=True)

    future.add_done_callback(_cleanup)
    try:
        return future.result(timeout=timeout_seconds)
    except concurrent.futures.TimeoutError as exc:
        future.cancel()
        raise LlmRequestTimeoutError(_timeout_message(
            operation,
            provider_key,
            task_id,
            frame_id,
            "request timeout exceeded",
            timeout_seconds,
        )) from exc


def _policy_from_context(context: dict[str, Any]) -> LlmCallPolicy:
    llm_config = context.get("llm_config")
    if not isinstance(llm_config, dict):
        llm_config = {}
    request_timeout = _positive_float(
        _coalesce(
            _first_present(context, "llm_request_timeout_seconds", "request_timeout_seconds", "timeout_seconds", "timeout"),
            _first_present(llm_config, "request_timeout_seconds", "timeout_seconds", "timeout"),
        ),
        settings.llm_request_timeout_seconds,
    )
    execution_deadline = _positive_float(
        _coalesce(
            _first_present(context, "llm_execution_deadline_seconds", "execution_deadline_seconds", "deadline_seconds"),
            _first_present(llm_config, "execution_deadline_seconds", "deadline_seconds"),
        ),
        settings.llm_execution_deadline_seconds,
    )
    return LlmCallPolicy(
        request_timeout_seconds=request_timeout,
        execution_deadline_seconds=execution_deadline,
        max_retries=_non_negative_int(
            _coalesce(
                _first_present(context, "llm_max_retries", "max_retries"),
                _first_present(llm_config, "max_retries"),
            ),
            settings.llm_max_retries,
        ),
        retry_backoff_seconds=_non_negative_float(
            _coalesce(
                _first_present(context, "llm_retry_backoff_seconds", "retry_backoff_seconds"),
                _first_present(llm_config, "retry_backoff_seconds"),
            ),
            settings.llm_retry_backoff_seconds,
        ),
        circuit_failure_threshold=max(1, _non_negative_int(
            _coalesce(
                _first_present(context, "llm_circuit_failure_threshold", "circuit_failure_threshold"),
                _first_present(llm_config, "circuit_failure_threshold"),
            ),
            settings.llm_circuit_failure_threshold,
        )),
        circuit_open_seconds=_positive_float(
            _coalesce(
                _first_present(context, "llm_circuit_open_seconds", "circuit_open_seconds"),
                _first_present(llm_config, "circuit_open_seconds"),
            ),
            settings.llm_circuit_open_seconds,
        ),
        max_concurrent_requests=max(1, _non_negative_int(
            _first_present(context, "llm_max_concurrent_requests", "max_concurrent_requests"),
            settings.llm_max_concurrent_requests,
        )),
    )


def _ensure_deadline(context: dict[str, Any], policy: LlmCallPolicy) -> float:
    existing = context.get(_DEADLINE_KEY)
    if isinstance(existing, (int, float)) and existing > time.monotonic():
        return float(existing)
    deadline_at = time.monotonic() + policy.execution_deadline_seconds
    task_deadline_at = _task_deadline_from_context(context)
    if task_deadline_at is not None:
        deadline_at = min(deadline_at, task_deadline_at)
    context[_DEADLINE_KEY] = deadline_at
    return deadline_at


def _task_deadline_from_context(context: dict[str, Any]) -> float | None:
    timeout_ms = _non_negative_float(
        _first_present(context, "task_timeout_ms", "taskTimeoutMs"),
        0.0,
    )
    if timeout_ms > 0:
        return time.monotonic() + timeout_ms / 1000.0

    raw_deadline = _first_present(context, "task_deadline_at", "taskDeadlineAt")
    if not isinstance(raw_deadline, str) or not raw_deadline.strip():
        return None
    deadline_text = raw_deadline.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(deadline_text)
    except ValueError:
        logger.debug("Ignoring invalid task deadline: %s", raw_deadline)
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    remaining = parsed.timestamp() - time.time()
    return time.monotonic() + max(0.0, remaining)


def _remaining_seconds(deadline_at: float) -> float:
    return max(0.0, deadline_at - time.monotonic())


def _request_semaphore(capacity: int) -> threading.BoundedSemaphore:
    global _semaphore, _semaphore_capacity
    with _semaphore_lock:
        if _semaphore is None or _semaphore_capacity != capacity:
            _semaphore_capacity = capacity
            _semaphore = threading.BoundedSemaphore(capacity)
        return _semaphore


def _raise_if_circuit_open(provider_key: str, operation: str, task_id: str, frame_id: str) -> None:
    now = time.monotonic()
    with _circuit_lock:
        state = _circuit_states.get(provider_key)
        if state is None or state.open_until <= 0:
            return
        if state.open_until <= now:
            state.failures = 0
            state.open_until = 0.0
            return
        remaining = round(state.open_until - now, 3)
    raise LlmCircuitOpenError(_guard_message(
        "LLM_CIRCUIT_OPEN",
        operation,
        provider_key,
        task_id,
        frame_id,
        f"retry_after_seconds={remaining}",
    ))


def _record_success(provider_key: str) -> None:
    with _circuit_lock:
        _circuit_states.pop(provider_key, None)


def _record_failure(provider_key: str, policy: LlmCallPolicy) -> None:
    with _circuit_lock:
        state = _circuit_states.setdefault(provider_key, _CircuitState())
        state.failures += 1
        if state.failures >= policy.circuit_failure_threshold:
            state.open_until = time.monotonic() + policy.circuit_open_seconds


def _is_retryable_exception(exc: BaseException) -> bool:
    if isinstance(exc, (LlmRequestTimeoutError, TimeoutError, LlmConcurrencyLimitError)):
        return True
    name = exc.__class__.__name__.lower()
    text = str(exc).lower()
    retryable_names = (
        "timeout",
        "readtimeout",
        "connecttimeout",
        "apiconnectionerror",
        "rate limit",
        "ratelimit",
        "serviceunavailable",
        "internalserver",
        "connectionerror",
    )
    retryable_text = (
        "timed out",
        "timeout",
        "temporarily unavailable",
        "connection reset",
        "connection aborted",
        "503",
        "502",
        "504",
        "429",
    )
    return any(part in name for part in retryable_names) or any(part in text for part in retryable_text)


def _retry_progress_reason(exc: BaseException) -> str:
    if isinstance(exc, LlmRequestTimeoutError):
        return "LLM_REQUEST_TIMEOUT"
    if isinstance(exc, LlmConcurrencyLimitError):
        return "LLM_CONCURRENCY_LIMIT"
    if isinstance(exc, LlmCircuitOpenError):
        return "LLM_CIRCUIT_OPEN"
    code = str(exc).split(";", 1)[0].strip()
    if code.startswith("LLM_"):
        return code[:80]
    if isinstance(exc, TimeoutError):
        return "LLM_PROVIDER_TIMEOUT"
    return "LLM_RETRYABLE_ERROR"


def _provider_key(model: Any) -> str:
    class_name = model.__class__.__name__
    model_name = _first_attr(model, "model_name", "model", "deployment_name", "model_id") or "unknown-model"
    endpoint = _safe_endpoint(_first_attr(model, "base_url", "openai_api_base", "anthropic_api_url") or "")
    return "|".join(part for part in (class_name, str(model_name), endpoint) if part)


def _first_attr(obj: Any, *names: str) -> Any:
    for name in names:
        value = getattr(obj, name, None)
        if value:
            return value
    return None


def _safe_endpoint(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        parts = urlsplit(text)
        if parts.netloc:
            return parts.netloc
    except ValueError:
        pass
    return text.split("?", 1)[0][:80]


def _timeout_message(
    operation: str,
    provider_key: str,
    task_id: str,
    frame_id: str,
    reason: str,
    seconds: float,
) -> str:
    return _guard_message(
        "LLM_REQUEST_TIMEOUT",
        operation,
        provider_key,
        task_id,
        frame_id,
        f"{reason}; timeout_seconds={round(seconds, 3)}",
    )


def _guard_message(
    code: str,
    operation: str,
    provider_key: str,
    task_id: str,
    frame_id: str,
    detail: str,
) -> str:
    parts = [
        code,
        f"operation={operation}",
        f"provider={provider_key}",
    ]
    if task_id:
        parts.append(f"task_id={task_id}")
    if frame_id:
        parts.append(f"frame_id={frame_id}")
    if detail:
        parts.append(detail)
    return "; ".join(parts)


def _safe_error(exc: BaseException) -> str:
    return str(exc).splitlines()[0][:300]


def _first_present(source: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = source.get(key)
        if value not in (None, ""):
            return value
    return None


def _coalesce(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _positive_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = float(default)
    if parsed <= 0:
        return float(default if default > 0 else 1.0)
    return parsed


def _non_negative_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = float(default)
    return max(0.0, parsed)


def _non_negative_int(value: Any, default: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = int(default)
    return max(0, parsed)
