"""LLM-driven Skill executor.

This runner is intentionally small and runtime-owned: the model may request
tools, but only SkillRuntime can validate and complete a Frame.
"""

from __future__ import annotations

import json
import logging
import datetime
from pathlib import Path
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage

from ..models import FrameStatus, QueryEvent, SkillManifest
from ..tools.mock_biz_tools import (
    mock_get_order,
    mock_get_vehicle_status,
    mock_search_incidents,
)
from ..tools.business_function_tools import (
    BusinessFunctionToolError,
    get_business_function_schema,
    invoke_business_function,
    list_business_functions,
)
from .account_file_tools import AccountFileTools, FileToolError
from .artifact_store import ArtifactError, ArtifactStore
from .public_skill_resource_tools import PublicSkillResourceTools
from .skill_runtime import SkillRuntime

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Content-governance placeholder template
# ---------------------------------------------------------------------------

_SCRUB_TEMPLATE = "[externalized: {artifact_id}, size={size}, summary={summary}]"


class LlmSkillAgent:
    """Run a Skill frame by repeatedly processing model tool calls."""

    def __init__(
        self,
        chat_model: BaseChatModel,
        runtime: SkillRuntime,
        max_iterations: int = 6,
        data_root: Path | None = None,
    ) -> None:
        self._model = chat_model
        self._runtime = runtime
        self._max_iterations = max_iterations
        self._data_root = data_root

    def run(
        self,
        task_id: str,
        frame_id: str,
        prompt: str,
        account_id: str | None = None,
        runtime_context: dict[str, Any] | None = None,
    ) -> list[QueryEvent]:
        """Execute a Skill until ``submit_skill_result`` completes the frame."""
        frame = self._runtime.get_frame(frame_id)
        if frame is None:
            return [QueryEvent(type="error", task_id=task_id, error=f"Frame not found: {frame_id}")]

        manifest = _manifest_for_frame(self._runtime, frame)
        if manifest is None:
            self._runtime.fail_frame(frame_id, f"Skill manifest not found: {frame.skill_id}")
            return [QueryEvent(type="error", task_id=task_id, error="Skill manifest not found")]

        # Prepare runtime-injected tool context
        artifact_store: ArtifactStore | None = None
        file_tools: AccountFileTools | None = None
        public_resource_tools: PublicSkillResourceTools | None = None
        if self._data_root and account_id:
            artifact_store = ArtifactStore(self._data_root)
            file_tools = AccountFileTools(self._data_root, account_id, task_id)
        client_app_id = _runtime_client_app_id(runtime_context)
        if self._data_root and client_app_id:
            public_resource_tools = PublicSkillResourceTools(Path(self._data_root).parent / "skills", client_app_id)

        messages: list[Any] = [
            SystemMessage(content=self._build_system_prompt(manifest)),
            HumanMessage(content=self._build_user_prompt(
                prompt,
                frame.input,
                manifest.id,
                runtime_context,
            )),
        ]
        events: list[QueryEvent] = []
        model = self._bind_tools(self._model, manifest)

        for _ in range(self._max_iterations):
            response = model.invoke(messages)
            messages.append(response)
            self._append_private_message(frame_id, "assistant", _safe_content(response.content))

            tool_calls = _extract_tool_calls(response)
            if not tool_calls:
                messages.append(HumanMessage(content=(
                    "No tool call was produced. If the skill is complete, call "
                    "submit_skill_result with summary, structured_output, and refs."
                )))
                continue

            for call in tool_calls:
                event = self._execute_tool_call(
                    task_id, frame_id, manifest, call,
                    account_id=account_id,
                    runtime_context=runtime_context,
                    artifact_store=artifact_store,
                    file_tools=file_tools,
                    public_resource_tools=public_resource_tools,
                )
                events.extend(event["events"])
                tool_result = event["tool_result"]

                # --- Context governance: scrub create_artifact content ---
                scrub_placeholder = event.get("scrub_placeholder")
                if scrub_placeholder:
                    # Scrub the tool-call args in the messages list
                    _scrub_create_artifact_content(messages, call["id"], scrub_placeholder)

                messages.append(ToolMessage(
                    content=json.dumps(tool_result, ensure_ascii=False),
                    tool_call_id=call["id"],
                ))
                self._append_private_message(frame_id, "tool", tool_result)

                current = self._runtime.get_frame(frame_id)
                if current and current.status == FrameStatus.COMPLETED:
                    return events

        self._runtime.fail_frame(frame_id, "LLM skill agent reached max iterations without valid submit")
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=manifest.id,
            error="LLM skill agent reached max iterations without valid submit",
        ))
        return events

    def _execute_tool_call(
        self,
        task_id: str,
        frame_id: str,
        manifest: SkillManifest,
        call: dict[str, Any],
        *,
        account_id: str | None = None,
        runtime_context: dict[str, Any] | None = None,
        artifact_store: ArtifactStore | None = None,
        file_tools: AccountFileTools | None = None,
        public_resource_tools: PublicSkillResourceTools | None = None,
    ) -> dict[str, Any]:
        name = call["name"]
        args = call["args"]
        safe_args = _safe_tool_call_args(args)
        events: list[QueryEvent] = [
            QueryEvent(
                type="tool_use",
                task_id=task_id,
                skill_frame_id=frame_id,
                skill_id=manifest.id,
                content=name,
                tool_call_id=call.get("id"),
                tool_name=name,
                function_id=_tool_function_id(name, safe_args),
                args=safe_args,
            )
        ]
        self._append_tool_call_message(frame_id, name, safe_args)
        self._append_tool_audit(task_id, frame_id, manifest.id, name, safe_args, phase="request")

        try:
            result = self._call_tool(
                frame_id, name, args,
                task_id=task_id,
                account_id=account_id,
                runtime_context=runtime_context,
                artifact_store=artifact_store,
                file_tools=file_tools,
                public_resource_tools=public_resource_tools,
            )
        except Exception as exc:
            logger.exception("LLM skill tool call failed: %s", name)
            result = {"ok": False, "error": str(exc)}
        self._append_tool_audit(task_id, frame_id, manifest.id, name, safe_args, phase="response", result=result)

        event_type = "skill_result_submit" if name == "submit_skill_result" and result.get("ok") else "tool_result"
        if name == "submit_skill_result" and not result.get("ok"):
            event_type = "skill_result_reject"

        events.append(QueryEvent(
            type=event_type,
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=manifest.id,
            content=json.dumps(result, ensure_ascii=False),
            error=result.get("error"),
            tool_call_id=call.get("id"),
            tool_name=name,
            function_id=_tool_function_id(name, safe_args, result),
            args=safe_args,
        ))

        ret: dict[str, Any] = {"events": events, "tool_result": result}

        # If create_artifact succeeded, attach scrub placeholder
        if name == "create_artifact" and result.get("ok") is not False and "artifact_id" in result:
            ret["scrub_placeholder"] = _SCRUB_TEMPLATE.format(
                artifact_id=result["artifact_id"],
                size=result.get("size", "?"),
                summary=result.get("summary", ""),
            )

        return ret

    def _call_tool(
        self,
        frame_id: str,
        name: str,
        args: dict[str, Any],
        *,
        task_id: str = "",
        account_id: str | None = None,
        runtime_context: dict[str, Any] | None = None,
        artifact_store: ArtifactStore | None = None,
        file_tools: AccountFileTools | None = None,
        public_resource_tools: PublicSkillResourceTools | None = None,
    ) -> dict[str, Any]:
        # --- Mock biz tools ---
        if name == "mock_get_order":
            order_id = args.get("order_id") or self._runtime.get_frame(frame_id).input.get("order_id")
            return {"ok": True, "result": mock_get_order(order_id)}

        if name == "mock_get_vehicle_status":
            return {"ok": True, "result": mock_get_vehicle_status(args["vehicle_id"])}

        if name == "mock_search_incidents":
            return {"ok": True, "result": mock_search_incidents(args.get("query", ""))}

        # --- Navigator worker-gateway business function tools ---
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            return {"ok": False, "error": f"Tool not available: {name}"}

        if name == "list_business_functions":
            token = _runtime_task_scoped_token(runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            try:
                return {
                    "ok": True,
                    "result": list_business_functions(
                        token,
                        domain=args.get("domain"),
                        risk_level=args.get("risk_level"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        if name == "get_business_function_schema":
            token = _runtime_task_scoped_token(runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            try:
                return {
                    "ok": True,
                    "result": get_business_function_schema(
                        token,
                        function_id=args.get("function_id", ""),
                        version=args.get("version"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        if name == "invoke_business_function":
            token = _runtime_task_scoped_token(runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            try:
                return {
                    "ok": True,
                    "result": invoke_business_function(
                        token,
                        function_id=args.get("function_id", ""),
                        version=args.get("version"),
                        input_data=args.get("input"),
                        idempotency_key=args.get("idempotency_key"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        if _looks_like_business_function_id(name):
            token = _runtime_task_scoped_token(runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            function_id, version = _split_business_function_tool_name(name)
            input_data = args.get("input") if isinstance(args.get("input"), dict) else {
                key: value for key, value in args.items()
                if key not in {"version", "idempotency_key"}
            }
            try:
                return {
                    "ok": True,
                    "result": invoke_business_function(
                        token,
                        function_id=function_id,
                        version=args.get("version") or version,
                        input_data=input_data,
                        idempotency_key=args.get("idempotency_key"),
                    ),
                }
            except BusinessFunctionToolError as exc:
                return {"ok": False, "error": str(exc)}

        # --- Artifact tools ---
        if name == "create_artifact":
            if not artifact_store or not account_id:
                return {"ok": False, "error": "artifact store not configured"}
            try:
                return artifact_store.create(
                    account_id=account_id,
                    task_id=task_id,
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
            if not artifact_store or not account_id:
                return {"ok": False, "error": "artifact store not configured"}
            try:
                return artifact_store.read(
                    account_id=account_id,
                    task_id=task_id,
                    artifact_id=args.get("artifact_id", ""),
                    mode=args.get("mode", "summary"),
                )
            except ArtifactError as exc:
                return {"ok": False, "error": f"{exc.code}: {exc.detail}"}

        # --- Account file tools ---
        if name in _FILE_TOOL_NAMES and file_tools:
            return _dispatch_file_tool(file_tools, name, args)

        if name in _PUBLIC_RESOURCE_TOOL_NAMES:
            if not public_resource_tools:
                return {"ok": False, "error": "ClientApp public skill resources are not configured"}
            return _dispatch_public_resource_tool(public_resource_tools, name, args)

        if name == "submit_skill_result":
            validation = self._runtime.submit_result(
                frame_id=frame_id,
                summary=args.get("summary", ""),
                structured_output=args.get("structured_output") or {},
                artifact_refs=args.get("artifact_refs"),
                evidence_refs=args.get("evidence_refs"),
            )
            return {"ok": validation.ok, "errors": validation.errors}

        return {"ok": False, "error": f"Unknown tool: {name}"}

    def _append_private_message(self, frame_id: str, role: str, content: Any) -> None:
        frame = self._runtime.get_frame(frame_id)
        if frame is None:
            return
        frame.private_messages.append({"role": role, "content": content})
        self._runtime.store.save(frame)

    def _append_tool_call_message(self, frame_id: str, name: str, args: dict[str, Any]) -> None:
        self._append_private_message(frame_id, "tool_call", {
            "name": name,
            "args": args,
        })

    def _append_tool_audit(
        self,
        task_id: str,
        frame_id: str,
        skill_id: str,
        name: str,
        args: dict[str, Any],
        *,
        phase: str,
        result: dict[str, Any] | None = None,
    ) -> None:
        if self._data_root is None:
            return
        try:
            log_dir = Path(self._data_root) / "logs" / "skill-tool-calls"
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

    @staticmethod
    def _bind_tools(model: BaseChatModel, manifest: SkillManifest) -> BaseChatModel:
        if not hasattr(model, "bind_tools"):
            return model
        return model.bind_tools(_tool_specs(manifest))

    @staticmethod
    def _build_system_prompt(manifest: SkillManifest) -> str:
        prompt = (
            f"You are executing skill {manifest.id}.\n"
            f"Description: {manifest.description}\n"
            f"Output schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
        )
        if manifest.markdown_body:
            prompt += f"\n---\nSkill Instructions:\n{manifest.markdown_body}\n---\n\n"
        
        prompt += (
            "Business functions may be shown as `function_id@version`; when using "
            "invoke_business_function, pass them as `function_id` without the @version "
            "suffix and `version` separately. "
            "If the skill references files under its bundle, use list_skill_resources "
            "or read_skill_resource; those tools only expose the current ClientApp's "
            "public skill resources. "
            "Use only the provided tools. When the skill is complete, call "
            "submit_skill_result. Natural-language completion is not accepted."
        )
        return prompt

    @staticmethod
    def _build_user_prompt(
        prompt: str,
        skill_input: dict[str, Any],
        skill_id: str,
        runtime_context: dict[str, Any] | None = None,
    ) -> str:
        parts = [
            f"SKILL_AGENT_START {skill_id}",
            _build_runtime_time_context_prompt(runtime_context),
            f"User request: {prompt}",
            f"Skill input: {json.dumps(skill_input, ensure_ascii=False)}",
        ]
        return "\n".join(part for part in parts if part)


# ---------------------------------------------------------------------------
# File tool dispatch
# ---------------------------------------------------------------------------

_FILE_TOOL_NAMES = frozenset({
    "list_files", "read_file", "write_file", "str_replace", "edit_file", "patch_file",
})

_PUBLIC_RESOURCE_TOOL_NAMES = frozenset({
    "list_skill_resources", "read_skill_resource",
})


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



def _build_runtime_time_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    time_context = _runtime_time_context(runtime_context)
    return (
        "Runtime context:\n"
        f"- current_time: {time_context['current_time']}\n"
        f"- timezone: {time_context['timezone']}\n"
        f"- business_date: {time_context['business_date']}\n"
        f"- current_month_range: [{time_context['current_month_start']}, {time_context['next_month_start']})\n"
        "Rule: Resolve relative dates such as 本月, 今天, 昨日, 近7天 using this runtime context."
    )


def _runtime_time_context(runtime_context: dict[str, Any] | None) -> dict[str, str]:
    context = runtime_context or {}
    current_time = _runtime_context_str(context, "current_time", "currentTime")
    timezone_name = _runtime_context_str(context, "timezone", "timeZone", "tz") or _local_timezone_name()

    if current_time:
        now = _parse_runtime_datetime(current_time) or datetime.datetime.now().astimezone()
    else:
        now = datetime.datetime.now().astimezone()
        current_time = now.isoformat()

    business_date = _runtime_context_str(context, "business_date", "businessDate")
    if not business_date:
        business_date = now.date().isoformat()

    business_day = _parse_runtime_date(business_date) or now.date()
    current_month_start, next_month_start = _month_range_for(business_day)

    return {
        "current_time": current_time,
        "timezone": timezone_name,
        "business_date": business_day.isoformat(),
        "current_month_start": current_month_start.isoformat(),
        "next_month_start": next_month_start.isoformat(),
    }


def _runtime_context_str(context: dict[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _local_timezone_name() -> str:
    tzinfo = datetime.datetime.now().astimezone().tzinfo
    if tzinfo is None:
        return "local"
    return getattr(tzinfo, "key", None) or tzinfo.tzname(None) or str(tzinfo)


def _parse_runtime_datetime(value: str) -> datetime.datetime | None:
    try:
        normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
        return datetime.datetime.fromisoformat(normalized)
    except ValueError:
        return None


def _parse_runtime_date(value: str) -> datetime.date | None:
    try:
        return datetime.date.fromisoformat(value[:10])
    except ValueError:
        return None


def _month_range_for(day: datetime.date) -> tuple[datetime.date, datetime.date]:
    current_month_start = day.replace(day=1)
    if current_month_start.month == 12:
        next_month_start = current_month_start.replace(
            year=current_month_start.year + 1,
            month=1,
        )
    else:
        next_month_start = current_month_start.replace(month=current_month_start.month + 1)
    return current_month_start, next_month_start


def _looks_like_business_function_id(name: str) -> bool:
    return "." in name and not name.startswith(".") and not name.endswith(".")


def _split_business_function_tool_name(name: str) -> tuple[str, str | None]:
    if "@" not in name:
        return name, None
    function_id, version = name.rsplit("@", 1)
    return function_id, version or None


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


# ---------------------------------------------------------------------------
# Tool schema registry
# ---------------------------------------------------------------------------


def _tool_specs(manifest: SkillManifest) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    for name in [*_GLOBAL_TOOL_NAMES, *manifest.allowed_tools]:
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            continue
        if name in _KNOWN_TOOL_SCHEMAS:
            specs.append(_KNOWN_TOOL_SCHEMAS[name])
    if "submit_skill_result" not in {s["function"]["name"] for s in specs}:
        specs.append(_KNOWN_TOOL_SCHEMAS["submit_skill_result"])
    return _dedupe_tool_specs(specs)


_GLOBAL_TOOL_NAMES = [
    "invoke_business_function",
    "list_skill_resources",
    "read_skill_resource",
]

_HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES = {
    "list_business_functions",
    "get_business_function_schema",
}


def _dedupe_tool_specs(specs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[str] = set()
    for spec in specs:
        name = spec["function"]["name"]
        if name in seen:
            continue
        seen.add(name)
        deduped.append(spec)
    return deduped


_KNOWN_TOOL_SCHEMAS: dict[str, dict[str, Any]] = {
    "mock_get_order": {
        "type": "function",
        "function": {
            "name": "mock_get_order",
            "description": "Fetch mock order details.",
            "parameters": {
                "type": "object",
                "properties": {"order_id": {"type": "string"}},
                "required": ["order_id"],
            },
        },
    },
    "mock_get_vehicle_status": {
        "type": "function",
        "function": {
            "name": "mock_get_vehicle_status",
            "description": "Fetch mock vehicle status.",
            "parameters": {
                "type": "object",
                "properties": {"vehicle_id": {"type": "string"}},
                "required": ["vehicle_id"],
            },
        },
    },
    "mock_search_incidents": {
        "type": "function",
        "function": {
            "name": "mock_search_incidents",
            "description": "Search mock incident records.",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
    "list_business_functions": {
        "type": "function",
        "function": {
            "name": "list_business_functions",
            "description": "List business functions available to this task's app/user/skill scope.",
            "parameters": {
                "type": "object",
                "properties": {
                    "domain": {"type": "string"},
                    "risk_level": {"type": "string"},
                },
                "required": [],
            },
        },
    },
    "get_business_function_schema": {
        "type": "function",
        "function": {
            "name": "get_business_function_schema",
            "description": "Get the input/output schema for a business function.",
            "parameters": {
                "type": "object",
                "properties": {
                    "function_id": {"type": "string"},
                    "version": {"type": "string"},
                },
                "required": ["function_id", "version"],
            },
        },
    },
    "invoke_business_function": {
        "type": "function",
        "function": {
            "name": "invoke_business_function",
            "description": "Invoke an allowlisted business function through Navigator Java.",
            "parameters": {
                "type": "object",
                "properties": {
                    "function_id": {"type": "string"},
                    "version": {"type": "string"},
                    "input": {"type": "object"},
                    "idempotency_key": {"type": "string"},
                },
                "required": ["function_id", "version", "input"],
            },
        },
    },
    "list_skill_resources": {
        "type": "function",
        "function": {
            "name": "list_skill_resources",
            "description": "List public skill bundle resources for the current ClientApp.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string", "description": "Skill id under the current ClientApp public directory. Omit to list skills."},
                    "relative_path": {"type": "string", "description": "Directory under the skill, usually references or assets."},
                    "recursive": {"type": "boolean"},
                    "max_entries": {"type": "integer"},
                },
                "required": [],
            },
        },
    },
    "read_skill_resource": {
        "type": "function",
        "function": {
            "name": "read_skill_resource",
            "description": "Read SKILL.md, references/**, or assets/** from a public skill bundle for the current ClientApp.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string"},
                    "relative_path": {"type": "string", "description": "SKILL.md, references/<file>, or assets/<file>."},
                    "start_line": {"type": "integer"},
                    "max_lines": {"type": "integer"},
                },
                "required": ["skill_id", "relative_path"],
            },
        },
    },
    "submit_skill_result": {
        "type": "function",
        "function": {
            "name": "submit_skill_result",
            "description": "Submit final skill result to the runtime.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "structured_output": {"type": "object"},
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "structured_output"],
            },
        },
    },
    # --- Artifact tools ---
    "create_artifact": {
        "type": "function",
        "function": {
            "name": "create_artifact",
            "description": "Externalize long content into an artifact. Returns artifact_id for future reference.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Short name for the artifact"},
                    "content": {"type": "string", "description": "Text content to externalize"},
                    "mime_type": {"type": "string", "description": "MIME type (default text/plain)"},
                    "encoding": {"type": "string", "description": "Encoding (default utf-8)"},
                    "scope": {"type": "string", "enum": ["task", "account"], "description": "Scope: task or account"},
                    "summary": {"type": "string", "description": "Short summary of the content"},
                },
                "required": ["name", "content"],
            },
        },
    },
    "read_artifact": {
        "type": "function",
        "function": {
            "name": "read_artifact",
            "description": "Read a previously created artifact. Default returns summary only; use mode=content for full text.",
            "parameters": {
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string"},
                    "mode": {"type": "string", "enum": ["summary", "metadata", "content"]},
                },
                "required": ["artifact_id"],
            },
        },
    },
    # --- Account file tools ---
    "list_files": {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files in the account's skill directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string", "description": "Directory to list (e.g. skills/my-skill)"},
                    "recursive": {"type": "boolean"},
                    "max_entries": {"type": "integer"},
                },
                "required": ["relative_path"],
            },
        },
    },
    "read_file": {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a file from the account's skill directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "start_line": {"type": "integer"},
                    "max_lines": {"type": "integer"},
                },
                "required": ["relative_path"],
            },
        },
    },
    "write_file": {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Write a file to the account's skill directory. mode=create (default) or mode=overwrite.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "content": {"type": "string"},
                    "encoding": {"type": "string"},
                    "mode": {"type": "string", "enum": ["create", "overwrite"]},
                    "expected_sha256": {"type": "string"},
                },
                "required": ["relative_path", "content"],
            },
        },
    },
    "str_replace": {
        "type": "function",
        "function": {
            "name": "str_replace",
            "description": "Replace a unique text fragment in a file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "old_str": {"type": "string"},
                    "new_str": {"type": "string"},
                },
                "required": ["relative_path", "old_str", "new_str"],
            },
        },
    },
    "edit_file": {
        "type": "function",
        "function": {
            "name": "edit_file",
            "description": "Section-level edit: replace content under a heading anchor.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "operation": {"type": "string", "enum": ["replace_section"]},
                    "anchor": {"type": "string", "description": "Markdown heading to anchor on"},
                    "content": {"type": "string"},
                },
                "required": ["relative_path", "operation", "anchor", "content"],
            },
        },
    },
    "patch_file": {
        "type": "function",
        "function": {
            "name": "patch_file",
            "description": "Apply a unified diff patch to a file. Conflicts cause full rejection.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "patch": {"type": "string", "description": "Unified diff content"},
                    "expected_sha256": {"type": "string"},
                },
                "required": ["relative_path", "patch"],
            },
        },
    },
}


# ---------------------------------------------------------------------------
# Context governance: scrub create_artifact content from messages
# ---------------------------------------------------------------------------


def _scrub_create_artifact_content(
    messages: list[Any],
    tool_call_id: str,
    placeholder: str,
) -> None:
    """Walk the messages list and replace create_artifact's raw content arg
    with a lightweight placeholder.

    This ensures the original long content is NOT retained in the active
    conversation context that is sent back to the LLM on subsequent turns.
    """
    for msg in messages:
        # LangChain AIMessage with tool_calls
        raw_calls = getattr(msg, "tool_calls", None)
        if raw_calls:
            for tc in raw_calls:
                tc_dict = tc if isinstance(tc, dict) else {}
                if not tc_dict:
                    continue
                if tc_dict.get("id") == tool_call_id and tc_dict.get("name") == "create_artifact":
                    args = tc_dict.get("args")
                    if isinstance(args, dict) and "content" in args:
                        args["content"] = placeholder

        # Also check additional_kwargs for OpenAI-style
        additional = getattr(msg, "additional_kwargs", None)
        if isinstance(additional, dict):
            for tc in additional.get("tool_calls", []):
                if tc.get("id") == tool_call_id:
                    func = tc.get("function", {})
                    if func.get("name") == "create_artifact":
                        try:
                            parsed = json.loads(func.get("arguments", "{}"))
                            if "content" in parsed:
                                parsed["content"] = placeholder
                                func["arguments"] = json.dumps(parsed, ensure_ascii=False)
                        except (json.JSONDecodeError, TypeError):
                            pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _extract_tool_calls(response: Any) -> list[dict[str, Any]]:
    raw_calls = getattr(response, "tool_calls", None)
    if raw_calls:
        return [_normalize_tool_call(c) for c in raw_calls]

    additional_kwargs = getattr(response, "additional_kwargs", {}) or {}
    raw_calls = additional_kwargs.get("tool_calls") or []
    return [_normalize_openai_tool_call(c) for c in raw_calls]


def _normalize_tool_call(call: Any) -> dict[str, Any]:
    if isinstance(call, dict):
        return {
            "id": call.get("id") or "call_unknown",
            "name": call.get("name"),
            "args": call.get("args") or {},
        }
    return {
        "id": getattr(call, "id", "call_unknown"),
        "name": getattr(call, "name", None),
        "args": getattr(call, "args", {}) or {},
    }


def _normalize_openai_tool_call(call: dict[str, Any]) -> dict[str, Any]:
    function = call.get("function", {})
    arguments = function.get("arguments") or "{}"
    if isinstance(arguments, str):
        args = json.loads(arguments)
    else:
        args = arguments
    return {
        "id": call.get("id") or "call_unknown",
        "name": function.get("name"),
        "args": args,
    }


def _safe_content(content: Any) -> Any:
    if isinstance(content, str):
        return content
    try:
        return json.loads(json.dumps(content))
    except Exception:
        return str(content)


def _safe_tool_call_args(args: dict[str, Any]) -> dict[str, Any]:
    safe_args = dict(args)
    for key in list(safe_args):
        lowered = key.lower()
        if "token" in lowered or "secret" in lowered or "password" in lowered:
            safe_args[key] = "<redacted>"
    return _safe_content(safe_args)


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


def _manifest_for_frame(runtime: SkillRuntime, frame: Any) -> SkillManifest | None:
    """Use the frame-frozen manifest so account registry reloads cannot affect execution."""
    snapshot = frame.private_working_state.get("_skill_manifest")
    if isinstance(snapshot, dict):
        try:
            return SkillManifest(**snapshot)
        except Exception:
            logger.warning("Invalid manifest snapshot in frame=%s", frame.frame_id, exc_info=True)
    return runtime.registry.get_manifest(frame.skill_id)
