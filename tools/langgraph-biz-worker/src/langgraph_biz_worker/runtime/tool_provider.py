"""Standalone Python tool provider contract for SkillAgent."""

from __future__ import annotations

import asyncio
import inspect
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any, Protocol

_RESERVED_CONTEXT_PARAMETER = "tool_context"


@dataclass(frozen=True)
class ToolSpec:
    """JSON-schema backed tool description exposed to the LLM runtime."""

    name: str
    description: str = ""
    parameters: dict[str, Any] = field(default_factory=lambda: {
        "type": "object",
        "properties": {},
        "required": [],
    })

    def to_openai_tool_schema(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


@dataclass(frozen=True)
class ToolResult:
    ok: bool
    result: Any = None
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"ok": self.ok}
        if self.result is not None:
            payload["result"] = self.result
        if self.error:
            payload["error"] = self.error
        return payload


class ToolProvider(Protocol):
    """Provider API used by SkillAgent and LlmSkillAgent."""

    def list_tools(self, skill_name: str, context: Mapping[str, Any] | None = None) -> list[ToolSpec]:
        ...

    def call_tool(
        self,
        tool_name: str,
        arguments: Mapping[str, Any] | None = None,
        context: Mapping[str, Any] | None = None,
    ) -> ToolResult | dict[str, Any] | Any:
        ...


class MockToolProvider:
    """Small deterministic provider for tests and local smoke checks."""

    def __init__(self, specs: list[ToolSpec] | None = None, results: dict[str, Any] | None = None) -> None:
        self._specs = list(specs or [])
        self._results = dict(results or {})
        self.calls: list[dict[str, Any]] = []

    def list_tools(self, skill_name: str, context: Mapping[str, Any] | None = None) -> list[ToolSpec]:
        return list(self._specs)

    def call_tool(
        self,
        tool_name: str,
        arguments: Mapping[str, Any] | None = None,
        context: Mapping[str, Any] | None = None,
    ) -> ToolResult | dict[str, Any] | Any:
        self.calls.append({
            "tool_name": tool_name,
            "arguments": dict(arguments or {}),
            "context": dict(context or {}),
        })
        if tool_name not in self._results:
            return ToolResult(ok=False, error=f"Unknown tool: {tool_name}")
        return self._results[tool_name]


class LocalPythonToolProvider:
    """Register plain Python callables as local skill tools."""

    def __init__(self) -> None:
        self._tools: dict[str, Callable[..., Any]] = {}
        self._specs: dict[str, ToolSpec] = {}
        self.calls: list[dict[str, Any]] = []

    def tool(
        self,
        name: str | None = None,
        *,
        description: str = "",
        parameters: dict[str, Any] | None = None,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            tool_name = name or func.__name__
            self.register(
                tool_name,
                func,
                description=description or inspect.getdoc(func) or "",
                parameters=parameters,
            )
            return func

        return decorator

    def register(
        self,
        name: str,
        func: Callable[..., Any],
        *,
        description: str = "",
        parameters: dict[str, Any] | None = None,
    ) -> None:
        self._tools[name] = func
        self._specs[name] = ToolSpec(
            name=name,
            description=description,
            parameters=parameters or _schema_from_signature(func),
        )

    def list_tools(self, skill_name: str, context: Mapping[str, Any] | None = None) -> list[ToolSpec]:
        return list(self._specs.values())

    def call_tool(
        self,
        tool_name: str,
        arguments: Mapping[str, Any] | None = None,
        context: Mapping[str, Any] | None = None,
    ) -> ToolResult | dict[str, Any] | Any:
        func = self._tools.get(tool_name)
        args = dict(arguments or {})
        self.calls.append({
            "tool_name": tool_name,
            "arguments": args,
            "context": dict(context or {}),
        })
        if func is None:
            return ToolResult(ok=False, error=f"Unknown tool: {tool_name}")
        args = _inject_tool_context(func, args, context)
        result = func(**args)
        if inspect.isawaitable(result):
            result = _run_awaitable(result)
        return result


def _run_awaitable(awaitable: Any) -> Any:
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(awaitable)
    raise RuntimeError("Async local tools must be executed from a sync worker thread")


def _schema_from_signature(func: Callable[..., Any]) -> dict[str, Any]:
    signature = inspect.signature(func)
    properties: dict[str, Any] = {}
    required: list[str] = []
    for name, parameter in signature.parameters.items():
        if name == _RESERVED_CONTEXT_PARAMETER:
            continue
        if parameter.kind in {parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD}:
            continue
        properties[name] = _schema_for_annotation(parameter.annotation)
        if parameter.default is parameter.empty:
            required.append(name)
    return {"type": "object", "properties": properties, "required": required}


def _inject_tool_context(
    func: Callable[..., Any],
    args: dict[str, Any],
    context: Mapping[str, Any] | None,
) -> dict[str, Any]:
    signature = inspect.signature(func)
    parameter = signature.parameters.get(_RESERVED_CONTEXT_PARAMETER)
    if parameter is None or _RESERVED_CONTEXT_PARAMETER in args:
        return args
    if parameter.kind not in {parameter.POSITIONAL_OR_KEYWORD, parameter.KEYWORD_ONLY}:
        return args
    updated = dict(args)
    updated[_RESERVED_CONTEXT_PARAMETER] = dict(context or {})
    return updated


def _schema_for_annotation(annotation: Any) -> dict[str, Any]:
    if annotation is str:
        return {"type": "string"}
    if annotation is int:
        return {"type": "integer"}
    if annotation is float:
        return {"type": "number"}
    if annotation is bool:
        return {"type": "boolean"}
    if annotation is dict:
        return {"type": "object"}
    if annotation is list:
        return {"type": "array"}
    return {"type": "string"}
