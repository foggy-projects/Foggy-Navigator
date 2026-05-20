"""Navigator worker-gateway business function tools."""

from __future__ import annotations

import json
from typing import Any
from urllib import parse, request
from urllib.error import HTTPError, URLError

from ..config import settings


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

    Skill markdown often lists functions as ``domain.name@v1`` while gateway
    APIs expect ``function_id=domain.name`` and ``version=v1`` separately.
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
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        message = f"HTTP {exc.code}: {detail}"
        raise _classified_gateway_error(message) from exc
    except URLError as exc:
        raise BusinessFunctionToolError(str(exc.reason)) from exc


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
