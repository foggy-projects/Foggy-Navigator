"""Navigator worker-gateway business function tools."""

from __future__ import annotations

import json
from typing import Any
from urllib import parse, request
from urllib.error import HTTPError, URLError

from ..config import settings


class BusinessFunctionToolError(RuntimeError):
    """Raised when Navigator worker-gateway rejects a business function call."""


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
    body: dict[str, Any] = {
        "version": version,
        "input": input_data or {},
    }
    if idempotency_key:
        body["idempotencyKey"] = idempotency_key
    path = f"/internal/worker-gateway/v1/business-functions/{parse.quote(function_id, safe='')}/invoke"
    return _request_json("POST", path, task_scoped_token, body)


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
        raise BusinessFunctionToolError(f"HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise BusinessFunctionToolError(str(exc.reason)) from exc
