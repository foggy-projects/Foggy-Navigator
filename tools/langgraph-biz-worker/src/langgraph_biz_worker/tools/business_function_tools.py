"""Navigator worker-gateway business function tools."""

from __future__ import annotations

import json
import re
from contextlib import contextmanager
from contextvars import ContextVar
from collections.abc import Iterator, Mapping
from typing import Any
from urllib import parse, request
from urllib.error import HTTPError, URLError

from ..config import settings


_WORKER_GATEWAY_RUNTIME_CONTEXT: ContextVar[Mapping[str, Any] | None] = ContextVar(
    "worker_gateway_runtime_context",
    default=None,
)


class BusinessFunctionToolError(RuntimeError):
    """Raised when Navigator worker-gateway rejects a business function call."""

    def __init__(
        self,
        message: str,
        *,
        error_category: str = "GATEWAY",
        recoverable: bool = True,
        llm_retry_allowed: bool = True,
        user_message: str | None = None,
    ) -> None:
        super().__init__(message)
        self.error_category = error_category
        self.recoverable = recoverable
        self.llm_retry_allowed = llm_retry_allowed
        self.user_message = user_message or message

    def to_tool_result(self) -> dict[str, Any]:
        return {
            "ok": False,
            "error": str(self),
            "error_category": self.error_category,
            "recoverable": self.recoverable,
            "llm_retry_allowed": self.llm_retry_allowed,
            "user_message": self.user_message,
        }


@contextmanager
def worker_gateway_runtime_context(
    runtime_context: Mapping[str, Any] | None,
) -> Iterator[None]:
    """Bind trusted per-task Worker identity without changing tool arguments."""

    reset_token = _WORKER_GATEWAY_RUNTIME_CONTEXT.set(runtime_context)
    try:
        yield
    finally:
        _WORKER_GATEWAY_RUNTIME_CONTEXT.reset(reset_token)


def list_business_functions(
    task_scoped_token: str,
    domain: str | None = None,
    risk_level: str | None = None,
) -> dict[str, Any]:
    params: dict[str, str] = {}
    if domain:
        params["domain"] = domain
    if risk_level:
        params["riskLevel"] = risk_level
    path = "/internal/worker-gateway/v1/business-functions"
    if params:
        path += "?" + parse.urlencode(params)
    return _request_json("GET", path, task_scoped_token)


def get_business_function_schema(
    task_scoped_token: str,
    function_id: str,
    version: str | None = None,
) -> dict[str, Any]:
    if not function_id:
        raise BusinessFunctionToolError("function_id is required")
    function_id, version = _normalize_function_ref(function_id, version)
    path = f"/internal/worker-gateway/v1/business-functions/{parse.quote(function_id, safe='')}/schema"
    if version:
        path += "?" + parse.urlencode({"version": version})
    return _request_json("GET", path, task_scoped_token)


def invoke_business_function(
    task_scoped_token: str,
    function_id: str,
    version: str | None,
    input_data: dict[str, Any] | None,
    idempotency_key: str | None = None,
) -> dict[str, Any]:
    if not function_id:
        raise BusinessFunctionToolError("function_id is required")
    function_id, version = _normalize_function_ref(function_id, version)
    body: dict[str, Any] = {
        "version": version,
        "input": input_data or {},
    }
    if idempotency_key:
        body["idempotencyKey"] = idempotency_key
    path = f"/internal/worker-gateway/v1/business-functions/{parse.quote(function_id, safe='')}/invoke"
    return _request_json("POST", path, task_scoped_token, body)


def _normalize_function_ref(function_id: str, version: str | None) -> tuple[str, str | None]:
    """Accept both split and compact business function refs.

    New contracts use the exact Navigator ``function_id`` and an optional
    ``version``. Older materialized markdown may still contain
    ``domain.name@v1``; keep accepting that compact form for compatibility.
    """
    if "@" not in function_id:
        return function_id, version
    base_function_id, inline_version = function_id.rsplit("@", 1)
    return base_function_id, version or inline_version or None


def _request_json(
    method: str,
    path: str,
    task_scoped_token: str,
    body: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if not task_scoped_token:
        raise BusinessFunctionToolError("task_scoped_token is required")

    base = settings.navigator_api_base.rstrip("/")
    data = None
    headers = {
        "X-Task-Scoped-Token": task_scoped_token,
        "Accept": "application/json",
    }
    headers.update(_worker_identity_headers(_WORKER_GATEWAY_RUNTIME_CONTEXT.get()))
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except HTTPError as exc:
        detail = _sanitize_gateway_error_message(exc.read().decode("utf-8", errors="replace"))
        message = f"HTTP {exc.code}: {detail}"
        raise _classified_gateway_error(message) from exc
    except URLError as exc:
        raise BusinessFunctionToolError(_sanitize_gateway_error_message(str(exc.reason))) from exc


def _worker_identity_headers(runtime_context: Mapping[str, Any] | None) -> dict[str, str]:
    """Build strict Worker headers from local secret plus trusted task lease.

    An entirely unconfigured local identity preserves token-only internal-dev
    behavior. Once either local identity value is configured, every required
    value must be present and consistent before any network request is made.
    """

    local_worker_id = settings.navigator_worker_id.strip()
    credential = settings.navigator_worker_credential.strip()
    if not local_worker_id and not credential:
        return {}
    if not local_worker_id or not credential:
        raise _worker_identity_configuration_error(
            "Navigator Worker ID and credential must be configured together"
        )

    context = runtime_context if isinstance(runtime_context, Mapping) else {}
    runtime_worker_id = _non_empty_context_text(context, "worker_id")
    worker_lease_id = _non_empty_context_text(context, "worker_lease_id")
    if not runtime_worker_id:
        raise _worker_identity_configuration_error(
            "Trusted runtime worker_id is required for authenticated Worker Gateway calls"
        )
    if runtime_worker_id != local_worker_id:
        raise _worker_identity_configuration_error(
            "Local Worker ID does not match trusted runtime worker_id"
        )
    if not worker_lease_id:
        raise _worker_identity_configuration_error(
            "Trusted runtime worker_lease_id is required for authenticated Worker Gateway calls"
        )

    return {
        "X-Navigator-Worker-Id": local_worker_id,
        "X-Navigator-Worker-Credential": credential,
        "X-Navigator-Worker-Lease-Id": worker_lease_id,
    }


def _non_empty_context_text(context: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _worker_identity_configuration_error(message: str) -> BusinessFunctionToolError:
    return BusinessFunctionToolError(
        message,
        error_category="CONFIGURATION",
        recoverable=False,
        llm_retry_allowed=False,
        user_message="Worker Gateway 身份或任务租约配置不完整，请联系平台管理员。",
    )


def _sanitize_gateway_error_message(message: str) -> str:
    sanitized = re.sub(
        r"\bbwc_[A-Za-z0-9._-]+\b",
        "[worker-credential-redacted]",
        message,
    )
    return re.sub(
        r"\bbtt_[A-Za-z0-9._-]+\b",
        "[task-token-redacted]",
        sanitized,
    )


def _classified_gateway_error(message: str) -> BusinessFunctionToolError:
    if _is_configuration_error(message):
        return BusinessFunctionToolError(
            message,
            error_category="CONFIGURATION",
            recoverable=False,
            llm_retry_allowed=False,
            user_message=(
                "业务函数配置错误：adapter upstream_ref 不合法或未配置，"
                "需检查 ClientApp upstream route / function adapter config。"
            ),
        )
    return BusinessFunctionToolError(message)


def _is_configuration_error(message: str) -> bool:
    text = message or ""
    return any(marker in text for marker in (
        "upstreamRef must match [A-Za-z0-9._-]{1,128}",
        "Unauthorized or unconfigured upstream_ref",
        "Rest adapter requires 'upstream_ref'",
        "Adapter config is missing or blank",
    ))
