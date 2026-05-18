"""Low-level tool dispatch helpers for the LLM skill agent."""

from __future__ import annotations

import logging
from typing import Any

from ..models import QueryEvent
from .account_file_tools import AccountFileTools, FileToolError
from .public_skill_resource_tools import PublicSkillResourceTools

logger = logging.getLogger(__name__)

_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"


def _emit_progress_event(runtime_context: dict[str, Any] | None, event: QueryEvent) -> None:
    if not runtime_context:
        return
    sink = runtime_context.get(_PROGRESS_EVENT_SINK_KEY)
    if not callable(sink):
        return
    try:
        sink(event)
    except Exception:
        logger.debug("Failed to emit progress event", exc_info=True)


def _dispatch_file_tool(
    tools: AccountFileTools,
    name: str,
    args: dict[str, Any],
) -> dict[str, Any]:
    """Dispatch a file tool call, translating FileToolError to error dicts."""
    try:
        if name == "list_files":
            return tools.list_files(
                args.get("relative_path", ""),
                recursive=args.get("recursive", False),
                max_entries=args.get("max_entries", 100),
            )
        if name == "read_file":
            return tools.read_file(
                args.get("relative_path", ""),
                start_line=args.get("start_line", 1),
                max_lines=args.get("max_lines", 200),
            )
        if name == "write_file":
            return tools.write_file(
                args.get("relative_path", ""),
                content=args.get("content", ""),
                encoding=args.get("encoding", "utf-8"),
                mode=args.get("mode", "create"),
                expected_sha256=args.get("expected_sha256"),
            )
        if name == "str_replace":
            return tools.str_replace(
                args.get("relative_path", ""),
                old_str=args.get("old_str", ""),
                new_str=args.get("new_str", ""),
            )
        if name == "edit_file":
            return tools.edit_file(
                args.get("relative_path", ""),
                operation=args.get("operation", "replace_section"),
                anchor=args.get("anchor", ""),
                content=args.get("content", ""),
            )
        if name == "patch_file":
            return tools.patch_file(
                args.get("relative_path", ""),
                patch=args.get("patch", ""),
                expected_sha256=args.get("expected_sha256"),
            )
        return {"ok": False, "error": f"Unknown file tool: {name}"}
    except FileToolError as exc:
        return {"ok": False, "error": f"{exc.code}: {exc.detail}"}


def _dispatch_public_resource_tool(
    tools: PublicSkillResourceTools,
    name: str,
    args: dict[str, Any],
) -> dict[str, Any]:
    try:
        if name == "list_skill_resources":
            return tools.list_resources(
                skill_id=args.get("skill_id"),
                relative_path=args.get("relative_path", ""),
                recursive=args.get("recursive", False),
                max_entries=args.get("max_entries", 100),
            )
        if name == "read_skill_resource":
            return tools.read_resource(
                skill_id=args.get("skill_id", ""),
                relative_path=args.get("relative_path", ""),
                start_line=args.get("start_line", 1),
                max_lines=args.get("max_lines", 200),
            )
        return {"ok": False, "error": f"Unknown public skill resource tool: {name}"}
    except FileToolError as exc:
        return {"ok": False, "error": f"{exc.code}: {exc.detail}"}


def _tool_function_id(
    tool_name: str,
    args: dict[str, Any],
    result: dict[str, Any] | None = None,
) -> str | None:
    if tool_name == "invoke_business_function":
        return args.get("function_id") or args.get("functionId")
    if isinstance(result, dict):
        nested_result = result.get("result")
        if isinstance(nested_result, dict):
            return nested_result.get("functionId") or nested_result.get("function_id")
        return result.get("functionId") or result.get("function_id")
    return None
