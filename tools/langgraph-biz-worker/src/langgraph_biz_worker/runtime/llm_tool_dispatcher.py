"""Low-level tool dispatch helpers for the LLM skill agent."""

from __future__ import annotations

from dataclasses import dataclass
import datetime
import hashlib
import json
import logging
from pathlib import Path
from typing import Any

from ..models import QueryEvent
from ..tools.attachment_analysis import analyze_attachment
from ..tools.spreadsheet_analysis import analyze_spreadsheet
from ..tools.business_function_tools import (
    BusinessFunctionToolError,
    get_business_function_schema,
    invoke_business_function,
    list_business_functions,
)
from ..tools.mock_biz_tools import (
    mock_get_order,
    mock_get_vehicle_status,
    mock_search_incidents,
)
from .account_file_tools import AccountFileTools, FileToolError
from .artifact_store import ArtifactError, ArtifactStore
from .frame_execution_report import read_frame_execution_report
from .llm_business_function_adapter import (
    _looks_like_business_function_id,
    _split_business_function_tool_name,
)
from .llm_tool_call_codec import _execution_report_payload_from_frame, _safe_content
from .llm_tool_schemas import _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES
from .public_skill_resource_tools import PublicSkillResourceTools
from .skill_runtime import SkillRuntime

logger = logging.getLogger(__name__)

_TOOL_UNHANDLED: object = object()
_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"
_FILE_TOOL_NAMES = frozenset({
    "list_files", "read_file", "write_file", "str_replace", "edit_file", "patch_file",
})
_PUBLIC_RESOURCE_TOOL_NAMES = frozenset({
    "list_skill_resources", "read_skill_resource",
})


@dataclass(frozen=True)
class LlmToolDispatchContext:
    frame_id: str
    task_id: str
    account_id: str | None = None
    runtime_context: dict[str, Any] | None = None
    artifact_store: ArtifactStore | None = None
    file_tools: AccountFileTools | None = None
    public_resource_tools: PublicSkillResourceTools | None = None
    persistent_frame: bool = False


class LlmToolDispatcher:
    """Dispatch side-effecting tool groups behind a narrow runtime adapter."""

    def __init__(
        self,
        runtime: SkillRuntime,
        *,
        data_root: Path | None = None,
    ) -> None:
        self._runtime = runtime
        self._data_root = data_root

    def dispatch_low_risk(
        self,
        name: str,
        args: dict[str, Any],
        context: LlmToolDispatchContext,
    ) -> dict[str, Any] | object:
        if name == "mock_get_order":
            frame = self._runtime.get_frame(context.frame_id)
            order_id = args.get("order_id") or (frame.input.get("order_id") if frame else None)
            return {"ok": True, "result": mock_get_order(order_id)}

        if name == "mock_get_vehicle_status":
            return {"ok": True, "result": mock_get_vehicle_status(args["vehicle_id"])}

        if name == "mock_search_incidents":
            return {"ok": True, "result": mock_search_incidents(args.get("query", ""))}

        if name == "analyze_attachment":
            return analyze_attachment(args, context.runtime_context)

        if name == "analyze_spreadsheet":
            return analyze_spreadsheet(
                args,
                context.runtime_context,
                artifact_store=context.artifact_store,
                account_id=context.account_id,
                task_id=context.task_id,
            )

        if name == "read_frame_execution_report":
            if self._data_root is None:
                return {"ok": False, "error": "Frame execution report data root is not configured"}
            return read_frame_execution_report(
                self._data_root,
                report_ref=args.get("report_ref") or args.get("reportRef"),
                task_id=args.get("task_id") or args.get("taskId"),
                frame_id=args.get("frame_id") or args.get("frameId"),
                mode=args.get("mode", "summary"),
                max_chars=args.get("max_chars") or args.get("maxChars") or 6000,
            )

        if name == "create_artifact":
            if not context.artifact_store or not context.account_id:
                return {"ok": False, "error": "artifact store not configured"}
            try:
                return context.artifact_store.create(
                    account_id=context.account_id,
                    task_id=context.task_id,
                    scope=args.get("scope", "task"),
                    name=args.get("name", "untitled"),
                    content=args.get("content", ""),
                    mime_type=args.get("mime_type", "text/plain"),
                    encoding=args.get("encoding", "utf-8"),
                    summary=args.get("summary", ""),
                )
            except ArtifactError as exc:
                return {"ok": False, "error": f"{exc.code}: {exc.detail}"}

        if name == "read_artifact":
            if not context.artifact_store or not context.account_id:
                return {"ok": False, "error": "artifact store not configured"}
            try:
                return context.artifact_store.read(
                    account_id=context.account_id,
                    task_id=context.task_id,
                    artifact_id=args.get("artifact_id", ""),
                    mode=args.get("mode", "summary"),
                )
            except ArtifactError as exc:
                return {"ok": False, "error": f"{exc.code}: {exc.detail}"}

        if name in _FILE_TOOL_NAMES and context.file_tools:
            return _dispatch_file_tool(context.file_tools, name, args)

        if name in _PUBLIC_RESOURCE_TOOL_NAMES:
            if not context.public_resource_tools:
                return {"ok": False, "error": "ClientApp public skill resources are not configured"}
            return _dispatch_public_resource_tool(context.public_resource_tools, name, args)

        return _TOOL_UNHANDLED

    def dispatch_business_function(
        self,
        name: str,
        args: dict[str, Any],
        context: LlmToolDispatchContext,
        finalize_business_function_call: Any,
        *,
        list_business_functions_fn: Any = list_business_functions,
        get_business_function_schema_fn: Any = get_business_function_schema,
        invoke_business_function_fn: Any = invoke_business_function,
    ) -> dict[str, Any] | object:
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            return {"ok": False, "error": f"Tool not available: {name}"}

        if name == "list_business_functions":
            token = _runtime_task_scoped_token(context.runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            try:
                return {
                    "ok": True,
                    "result": list_business_functions_fn(
                        token,
                        domain=args.get("domain"),
                        risk_level=args.get("risk_level"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        if name == "get_business_function_schema":
            token = _runtime_task_scoped_token(context.runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            try:
                return {
                    "ok": True,
                    "result": get_business_function_schema_fn(
                        token,
                        function_id=args.get("function_id", ""),
                        version=args.get("version"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        if name == "invoke_business_function":
            token = _runtime_task_scoped_token(context.runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            function_id = args.get("function_id", "")
            version = args.get("version")
            input_data = args.get("input") if isinstance(args.get("input"), dict) else {}
            idempotency_key = args.get("idempotency_key") or _auto_business_function_idempotency_key(
                context.frame_id,
                function_id,
                input_data,
            )
            function_frame_id = self._runtime.invoke_function_call(
                parent_frame_id=context.frame_id,
                function_id=function_id,
                version=version,
                arguments=input_data,
                idempotency_key=idempotency_key,
                tool_call_id=args.get("tool_call_id"),
            )
            try:
                gateway_result = invoke_business_function_fn(
                    token,
                    function_id=function_id,
                    version=version,
                    input_data=input_data,
                    idempotency_key=idempotency_key,
                )
                result = {"ok": True, "result": gateway_result}
                return finalize_business_function_call(
                    task_id=context.task_id,
                    caller_frame_id=context.frame_id,
                    function_frame_id=function_frame_id,
                    function_id=function_id,
                    version=version,
                    call_args={**args, "input": input_data, "idempotency_key": idempotency_key},
                    result=result,
                )
            except BusinessFunctionToolError as exc:
                self._runtime.fail_frame(function_frame_id, str(exc))
                function_frame = self._runtime.get_frame(function_frame_id)
                return {
                    "ok": False,
                    "error": str(exc),
                    "function_frame_id": function_frame_id,
                    **_execution_report_payload_from_frame(function_frame),
                }

        if _looks_like_business_function_id(name):
            token = _runtime_task_scoped_token(context.runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            function_id, version = _split_business_function_tool_name(name)
            input_data = args.get("input") if isinstance(args.get("input"), dict) else {
                key: value for key, value in args.items()
                if key not in {"version", "idempotency_key"}
            }
            resolved_version = args.get("version") or version
            idempotency_key = args.get("idempotency_key") or _auto_business_function_idempotency_key(
                context.frame_id,
                function_id,
                input_data,
            )
            function_frame_id = self._runtime.invoke_function_call(
                parent_frame_id=context.frame_id,
                function_id=function_id,
                version=resolved_version,
                arguments=input_data,
                idempotency_key=idempotency_key,
                tool_call_id=args.get("tool_call_id"),
            )
            try:
                gateway_result = invoke_business_function_fn(
                    token,
                    function_id=function_id,
                    version=resolved_version,
                    input_data=input_data,
                    idempotency_key=idempotency_key,
                )
                result = {"ok": True, "result": gateway_result}
                return finalize_business_function_call(
                    task_id=context.task_id,
                    caller_frame_id=context.frame_id,
                    function_frame_id=function_frame_id,
                    function_id=function_id,
                    version=resolved_version,
                    call_args={**args, "input": input_data, "idempotency_key": idempotency_key},
                    result=result,
                )
            except BusinessFunctionToolError as exc:
                self._runtime.fail_frame(function_frame_id, str(exc))
                function_frame = self._runtime.get_frame(function_frame_id)
                return {
                    "ok": False,
                    "error": str(exc),
                    "function_frame_id": function_frame_id,
                    **_execution_report_payload_from_frame(function_frame),
                }

        return _TOOL_UNHANDLED


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


def _append_tool_audit(
    data_root: Path | None,
    task_id: str,
    frame_id: str,
    skill_id: str,
    name: str,
    args: dict[str, Any],
    *,
    phase: str,
    result: dict[str, Any] | None = None,
) -> None:
    if data_root is None:
        return
    try:
        log_dir = Path(data_root) / "logs" / "skill-tool-calls"
        log_dir.mkdir(parents=True, exist_ok=True)
        entry = {
            "ts": datetime.datetime.now().isoformat(),
            "task_id": task_id,
            "frame_id": frame_id,
            "skill_id": skill_id,
            "tool": name,
            "phase": phase,
            "args": args,
        }
        if result is not None:
            entry["result"] = _safe_content(result)
        with open(log_dir / f"{task_id}.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        logger.debug("Failed to write skill tool audit log", exc_info=True)


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


def _runtime_task_scoped_token(runtime_context: dict[str, Any] | None) -> str | None:
    if not runtime_context:
        return None
    token = runtime_context.get("task_scoped_token")
    return token if isinstance(token, str) and token else None


def _runtime_client_app_id(runtime_context: dict[str, Any] | None) -> str | None:
    if not runtime_context:
        return None
    value = runtime_context.get("client_app_id") or runtime_context.get("clientAppId")
    return value if isinstance(value, str) and value else None


def _auto_business_function_idempotency_key(
    frame_id: str,
    function_id: str,
    input_data: dict[str, Any],
) -> str:
    canonical = json.dumps(input_data or {}, ensure_ascii=False, sort_keys=True, default=str)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
    return f"navigator:{frame_id}:{function_id}:{digest}"


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
