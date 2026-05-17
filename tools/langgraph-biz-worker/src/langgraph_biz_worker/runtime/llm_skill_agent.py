"""LLM-driven Skill executor.

This runner is intentionally small and runtime-owned: the model may request
tools, but only SkillRuntime can validate and complete a Frame.
"""

from __future__ import annotations

import json
import logging
import datetime
import re
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
from .account_context_files import build_account_context_prompt
from .artifact_store import ArtifactError, ArtifactStore
from .frame_execution_report import read_frame_execution_report
from .public_skill_resource_tools import PublicSkillResourceTools
from .skill_runtime import SkillRuntime

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Content-governance placeholder template
# ---------------------------------------------------------------------------

_SCRUB_TEMPLATE = "[externalized: {artifact_id}, size={size}, summary={summary}]"
_PROGRESS_EVENT_SINK_KEY = "_progress_event_sink"
_BUSINESS_IDENTIFIER_FIELDS = frozenset({
    "orderNo",
    "order_no",
    "orderIdentifier",
    "order_identifier",
    "waybillNo",
    "waybill_no",
    "businessOrderNo",
    "business_order_no",
})
_NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS = frozenset({
    "skillId",
    "skill_id",
    "functionId",
    "function_id",
    "objectId",
    "object_id",
    "routeName",
    "route_name",
    "frameId",
    "frame_id",
    "skillFrameId",
    "skill_frame_id",
    "functionFrameId",
    "function_frame_id",
    "taskId",
    "task_id",
    "sessionId",
    "session_id",
    "messageId",
    "message_id",
})
_NAVIGATOR_RUNTIME_IDENTIFIER_PATTERN = re.compile(r"\b(?:frm|lgt|msg|sess)_[A-Za-z0-9_-]+\b")
_BUSINESS_ID_CLAIM_PATTERN = re.compile(
    r"(订单号|运单号|业务单号|单号|order\s*(?:no|number|id)|waybill\s*(?:no|number|id))",
    re.IGNORECASE,
)
_FORMAL_ORDER_SUCCESS_PATTERN = re.compile(
    r"(订单创建成功|创建订单成功|已创建订单|已下单|下单成功|order\s+created)",
    re.IGNORECASE,
)


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
        persistent_frame: bool = False,
    ) -> list[QueryEvent]:
        """Execute a Skill until ``submit_skill_result`` completes the frame."""
        frame = self._runtime.get_frame(frame_id)
        if frame is None:
            return [QueryEvent(type="error", task_id=task_id, error=f"Frame not found: {frame_id}")]
        runtime_context = dict(runtime_context or {})
        if persistent_frame:
            runtime_context["_persistent_frame"] = True
            interruption_context = _recoverable_interruption_context(frame.private_working_state)
            if interruption_context:
                runtime_context["_recoverable_interruption"] = interruption_context
            active_plan = _active_plan_context(frame.private_working_state)
            if active_plan:
                runtime_context["_active_plan"] = active_plan

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
        account_context_prompt = (
            build_account_context_prompt(self._data_root, account_id)
            if self._data_root and account_id
            else ""
        )

        messages: list[Any] = [
            SystemMessage(content=self._build_system_prompt(manifest, account_context_prompt)),
            HumanMessage(content=self._build_user_prompt(
                prompt,
                frame.input,
                manifest.id,
                runtime_context,
            )),
        ]
        events: list[QueryEvent] = []
        model = self._bind_tools(self._model, manifest, persistent_frame=persistent_frame)

        for _ in range(self._max_iterations):
            try:
                response = model.invoke(messages)
            except Exception as exc:
                if persistent_frame:
                    self._runtime.record_recoverable_interruption(
                        frame_id,
                        reason="model_error",
                        error=str(exc),
                        task_id=task_id,
                    )
                else:
                    self._runtime.fail_frame(frame_id, str(exc))
                return [QueryEvent(
                    type="error",
                    task_id=task_id,
                    skill_frame_id=frame_id,
                    error=str(exc),
                )]
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
                    persistent_frame=persistent_frame,
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
                if event.get("persistent_turn_completed"):
                    return events
                if event.get("suspended"):
                    return events

        error = "LLM skill agent reached max iterations without valid submit"
        if persistent_frame:
            self._runtime.record_recoverable_interruption(
                frame_id,
                reason="model_error",
                error=error,
                task_id=task_id,
            )
        else:
            self._runtime.fail_frame(frame_id, error)
        events.append(QueryEvent(
            type="error",
            task_id=task_id,
            skill_frame_id=frame_id,
            skill_id=manifest.id,
            error=error,
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
        persistent_frame: bool = False,
    ) -> dict[str, Any]:
        name = call["name"]
        args = call["args"]
        safe_args = _safe_tool_call_args(args)
        frame = self._runtime.get_frame(frame_id)
        parent_frame_id = frame.parent_frame_id if frame else None
        tool_use_event = QueryEvent(
            type="tool_use",
            task_id=task_id,
            skill_frame_id=frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=manifest.id,
            content=name,
            tool_call_id=call.get("id"),
            tool_name=name,
            function_id=_tool_function_id(name, safe_args),
            args=safe_args,
        )
        events: list[QueryEvent] = [tool_use_event]
        _emit_progress_event(runtime_context, tool_use_event)
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
                persistent_frame=persistent_frame,
            )
        except Exception as exc:
            logger.exception("LLM skill tool call failed: %s", name)
            result = {"ok": False, "error": str(exc)}
        extra_events = result.pop("_events", []) if isinstance(result, dict) else []
        suspended = bool(result.pop("_suspended", False)) if isinstance(result, dict) else False
        self._append_tool_audit(task_id, frame_id, manifest.id, name, safe_args, phase="response", result=result)

        event_type = "skill_result_submit" if name == "submit_skill_result" and result.get("ok") else "tool_result"
        if name == "submit_skill_result" and not result.get("ok"):
            event_type = "skill_result_reject"

        report_payload = _execution_report_payload_from_result(result)
        result_event = QueryEvent(
            type=event_type,
            task_id=task_id,
            skill_frame_id=frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=manifest.id,
            content=json.dumps(result, ensure_ascii=False),
            error=result.get("error"),
            tool_call_id=call.get("id"),
            tool_name=name,
            function_id=_tool_function_id(name, safe_args, result),
            args=safe_args,
            execution_report_ref=report_payload.get("execution_report_ref"),
            execution_report_digest=report_payload.get("execution_report_digest"),
        )
        events.append(result_event)
        events.extend(extra_events)
        _emit_progress_event(runtime_context, result_event)
        for extra_event in extra_events:
            _emit_progress_event(runtime_context, extra_event)

        ret: dict[str, Any] = {"events": events, "tool_result": result}
        if name in {"submit_skill_result", "shelve_interrupted_frame"} and persistent_frame and result.get("ok"):
            ret["persistent_turn_completed"] = True
        if suspended:
            ret["suspended"] = True

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
        persistent_frame: bool = False,
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
            function_id = args.get("function_id", "")
            version = args.get("version")
            function_frame_id = self._runtime.invoke_function_call(
                parent_frame_id=frame_id,
                function_id=function_id,
                version=version,
                arguments=args.get("input") if isinstance(args.get("input"), dict) else {},
                idempotency_key=args.get("idempotency_key"),
                tool_call_id=args.get("tool_call_id"),
            )
            try:
                gateway_result = invoke_business_function(
                    token,
                    function_id=function_id,
                    version=version,
                    input_data=args.get("input"),
                    idempotency_key=args.get("idempotency_key"),
                )
                result = {"ok": True, "result": gateway_result}
                return self._finalize_business_function_call(
                    task_id=task_id,
                    caller_frame_id=frame_id,
                    function_frame_id=function_frame_id,
                    function_id=function_id,
                    version=version,
                    call_args=args,
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
            token = _runtime_task_scoped_token(runtime_context)
            if not token:
                return {"ok": False, "error": "MISSING_TOKEN: task_scoped_token is required (runtime context)"}
            function_id, version = _split_business_function_tool_name(name)
            input_data = args.get("input") if isinstance(args.get("input"), dict) else {
                key: value for key, value in args.items()
                if key not in {"version", "idempotency_key"}
            }
            resolved_version = args.get("version") or version
            function_frame_id = self._runtime.invoke_function_call(
                parent_frame_id=frame_id,
                function_id=function_id,
                version=resolved_version,
                arguments=input_data,
                idempotency_key=args.get("idempotency_key"),
                tool_call_id=args.get("tool_call_id"),
            )
            try:
                gateway_result = invoke_business_function(
                    token,
                    function_id=function_id,
                    version=resolved_version,
                    input_data=input_data,
                    idempotency_key=args.get("idempotency_key"),
                )
                result = {"ok": True, "result": gateway_result}
                return self._finalize_business_function_call(
                    task_id=task_id,
                    caller_frame_id=frame_id,
                    function_frame_id=function_frame_id,
                    function_id=function_id,
                    version=resolved_version,
                    call_args={**args, "input": input_data},
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

        if name == "invoke_business_skill":
            child_skill_id = args.get("skill_id") or args.get("skillId")
            if not child_skill_id:
                return {"ok": False, "error": "skill_id is required"}
            if child_skill_id == self._runtime.get_frame(frame_id).skill_id:
                return {"ok": False, "error": "A skill cannot invoke itself"}
            child_manifest = self._runtime.registry.get_manifest(child_skill_id)
            if not child_manifest:
                return {"ok": False, "error": f"Skill manifest not found: {child_skill_id}"}

            instruction = args.get("instruction") or args.get("prompt") or ""
            child_input = args.get("input") if isinstance(args.get("input"), dict) else {}
            child_runtime_context = _runtime_context_for_child_skill(
                runtime_context,
                self._runtime.context_summary_for_frame(frame_id),
                child_manifest,
            )
            child_frame_id = self._runtime.invoke_child_skill(
                parent_frame_id=frame_id,
                child_skill_id=child_skill_id,
                child_input=child_input,
            )
            child_events = [
                QueryEvent(
                    type="skill_frame_open",
                    task_id=task_id,
                    skill_frame_id=child_frame_id,
                    parent_frame_id=frame_id,
                    skill_id=child_skill_id,
                    content=f"Opening frame for skill: {child_skill_id}",
                )
            ]
            child_events.extend(self.run(
                task_id=task_id,
                frame_id=child_frame_id,
                prompt=instruction,
                account_id=account_id,
                runtime_context=child_runtime_context,
            ))

            child = self._runtime.get_frame(child_frame_id)
            if child and child.status == FrameStatus.AWAITING_APPROVAL:
                approval_request = child.approval_request
                if not isinstance(approval_request, dict):
                    return {
                        "ok": False,
                        "error": "Child skill is awaiting approval without approval_request",
                        "_events": child_events,
                    }
                self._runtime.mark_child_awaiting_approval(
                    frame_id,
                    child_frame_id,
                    approval_request,
                )
                report_payload = _child_approval_report_payload(child)
                return {
                    "ok": True,
                    "approval_wait": True,
                    "child_frame_id": child_frame_id,
                    **report_payload,
                    "_events": child_events,
                    "_suspended": True,
                }
            if not child or child.status != FrameStatus.COMPLETED:
                return {
                    "ok": False,
                    "error": f"Child skill ended in {child.status.value if child else 'MISSING'}",
                    "_events": child_events,
                }

            promoted = self._runtime.complete_child_and_resume_parent(child_frame_id)
            child_events.append(QueryEvent(
                type="skill_frame_close",
                task_id=task_id,
                skill_frame_id=child_frame_id,
                parent_frame_id=frame_id,
                skill_id=child_skill_id,
                content=f"Frame closed: {child_skill_id}",
                execution_report_ref=promoted.get("execution_report_ref"),
                execution_report_digest=promoted.get("execution_report_digest"),
            ))
            return {"ok": True, "result": promoted, "_events": child_events}

        if name == "resume_recoverable_child_skill":
            if not persistent_frame:
                return {"ok": False, "error": "resume_recoverable_child_skill is only available on persistent frames"}
            parent = self._runtime.get_frame(frame_id)
            if not parent:
                return {"ok": False, "error": f"Frame not found: {frame_id}"}
            child = self._runtime.prepare_recoverable_child_resume(frame_id)
            if child is None:
                return {"ok": False, "error": "No recoverable child skill is pending"}
            child_manifest = self._runtime.registry.get_manifest(child.skill_id)
            if not child_manifest:
                return {"ok": False, "error": f"Skill manifest not found: {child.skill_id}"}

            instruction = args.get("instruction") or args.get("prompt") or ""
            child_runtime_context = _runtime_context_for_child_skill(
                runtime_context,
                self._runtime.context_summary_for_frame(frame_id),
                child_manifest,
            )
            child_events = [
                QueryEvent(
                    type="skill_frame_open",
                    task_id=task_id,
                    skill_frame_id=child.frame_id,
                    parent_frame_id=frame_id,
                    skill_id=child.skill_id,
                    content=f"Resuming frame for skill: {child.skill_id}",
                )
            ]
            child_events.extend(self.run(
                task_id=task_id,
                frame_id=child.frame_id,
                prompt=instruction,
                account_id=account_id,
                runtime_context=child_runtime_context,
            ))

            refreshed_child = self._runtime.get_frame(child.frame_id)
            if refreshed_child and refreshed_child.status == FrameStatus.AWAITING_APPROVAL:
                approval_request = refreshed_child.approval_request
                if not isinstance(approval_request, dict):
                    _resume_parent_if_waiting(self._runtime, frame_id)
                    return {
                        "ok": False,
                        "error": "Child skill is awaiting approval without approval_request",
                        "_events": child_events,
                    }
                self._runtime.mark_child_awaiting_approval(
                    frame_id,
                    child.frame_id,
                    approval_request,
                )
                report_payload = _child_approval_report_payload(refreshed_child)
                return {
                    "ok": True,
                    "approval_wait": True,
                    "child_frame_id": child.frame_id,
                    **report_payload,
                    "_events": child_events,
                    "_suspended": True,
                }
            if not refreshed_child or refreshed_child.status != FrameStatus.COMPLETED:
                _resume_parent_if_waiting(self._runtime, frame_id)
                return {
                    "ok": False,
                    "error": f"Child skill ended in {refreshed_child.status.value if refreshed_child else 'MISSING'}",
                    "child_frame_id": child.frame_id,
                    "_events": child_events,
                }

            promoted = self._runtime.complete_child_and_resume_parent(child.frame_id)
            child_events.append(QueryEvent(
                type="skill_frame_close",
                task_id=task_id,
                skill_frame_id=child.frame_id,
                parent_frame_id=frame_id,
                skill_id=child.skill_id,
                content=f"Frame closed: {child.skill_id}",
                execution_report_ref=promoted.get("execution_report_ref"),
                execution_report_digest=promoted.get("execution_report_digest"),
            ))
            return {
                "ok": True,
                "intent_resolution": "CONTINUE_PREVIOUS",
                "result": promoted,
                "child_frame_id": child.frame_id,
                "_events": child_events,
            }

        if name == "shelve_interrupted_frame":
            if not persistent_frame:
                return {"ok": False, "error": "shelve_interrupted_frame is only available on persistent frames"}
            validation = self._runtime.shelve_recoverable_interruption(
                frame_id=frame_id,
                summary=args.get("summary", ""),
                abandoned_interruption=args.get("abandoned_interruption"),
                decision=args.get("decision") or "START_UNRELATED_NEW_TASK",
                intent_resolution=args.get("intent_resolution"),
                new_task=args.get("new_task") if isinstance(args.get("new_task"), dict) else None,
                artifact_refs=args.get("artifact_refs"),
                evidence_refs=args.get("evidence_refs"),
            )
            frame = self._runtime.get_frame(frame_id)
            return {
                "ok": validation.ok,
                "errors": validation.errors,
                "structured_output": frame.output if frame else None,
            }

        if name == "submit_skill_result":
            structured_output = args.get("structured_output") or {}
            summary = _guard_final_summary(
                args.get("summary", ""),
                structured_output,
                self._runtime.get_frame(frame_id),
            )
            submit = (
                self._runtime.submit_persistent_turn_result
                if persistent_frame
                else self._runtime.submit_result
            )
            validation = submit(
                frame_id=frame_id,
                summary=summary,
                structured_output=structured_output,
                artifact_refs=args.get("artifact_refs"),
                evidence_refs=args.get("evidence_refs"),
            )
            frame = self._runtime.get_frame(frame_id)
            return {
                "ok": validation.ok,
                "errors": validation.errors,
                **_execution_report_payload_from_frame(frame),
            }

        return {"ok": False, "error": f"Unknown tool: {name}"}

    def _finalize_business_function_call(
        self,
        *,
        task_id: str,
        caller_frame_id: str,
        function_frame_id: str,
        function_id: str,
        version: str | None,
        call_args: dict[str, Any],
        result: dict[str, Any],
    ) -> dict[str, Any]:
        gateway_result = result.get("result") if isinstance(result.get("result"), dict) else {}
        if _business_function_result_is_suspended(gateway_result):
            suspend_id = _business_function_suspend_id(gateway_result)
            summary = _business_function_approval_summary(gateway_result, function_id)
            approval_request = {
                "approval_type": "business_function",
                "function_id": function_id,
                "version": version,
                "suspend_id": suspend_id,
                "summary": summary,
                "payload": {
                    "function_id": function_id,
                    "version": version,
                    "input": call_args.get("input") if isinstance(call_args.get("input"), dict) else {},
                    "idempotency_key": call_args.get("idempotency_key"),
                    "gateway_result": _safe_content(gateway_result),
                    "function_frame_id": function_frame_id,
                },
                "resolved": False,
            }
            self._runtime.suspend_function_call(function_frame_id, approval_request)
            self._runtime.mark_awaiting_approval(caller_frame_id, approval_request)
            caller_frame = self._runtime.get_frame(caller_frame_id)
            function_frame = self._runtime.get_frame(function_frame_id)
            report_payload = _execution_report_payload_from_frame(function_frame)
            approval_event = QueryEvent(
                type="approval_required",
                task_id=task_id,
                skill_frame_id=function_frame_id,
                parent_frame_id=caller_frame_id,
                skill_id=caller_frame.skill_id if caller_frame else None,
                function_id=function_id,
                content=summary.get("title") or summary.get("message") or "Business function approval required",
                approval_type="business_function",
                payload=approval_request["payload"],
                suspend_id=suspend_id,
                reason=summary.get("reason") or "approval_required",
                summary=summary,
                timeout_at=_business_function_timeout_at(gateway_result),
                execution_report_ref=report_payload.get("execution_report_ref"),
                execution_report_digest=report_payload.get("execution_report_digest"),
            )
            return {
                **result,
                "approval_wait": True,
                "suspend_id": suspend_id,
                "function_frame_id": function_frame_id,
                **report_payload,
                "_events": [approval_event],
                "_suspended": True,
            }

        self._runtime.complete_function_call(
            function_frame_id,
            result=gateway_result,
        )
        function_frame = self._runtime.get_frame(function_frame_id)
        return {
            **result,
            "approval_wait": False,
            "function_frame_id": function_frame_id,
            **_execution_report_payload_from_frame(function_frame),
        }

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
    def _bind_tools(
        model: BaseChatModel,
        manifest: SkillManifest,
        persistent_frame: bool = False,
    ) -> BaseChatModel:
        if not hasattr(model, "bind_tools"):
            return model
        return model.bind_tools(_tool_specs(manifest, persistent_frame=persistent_frame))

    @staticmethod
    def _build_system_prompt(manifest: SkillManifest, account_context_prompt: str = "") -> str:
        prompt = (
            f"You are executing skill {manifest.id}.\n"
            f"Description: {manifest.description}\n"
            f"Output schema: {json.dumps(manifest.output_schema, ensure_ascii=False)}\n"
        )
        if account_context_prompt:
            prompt += f"\n---\n{account_context_prompt}\n---\n\n"
        if manifest.markdown_body:
            prompt += f"\n---\nSkill Instructions:\n{manifest.markdown_body}\n---\n\n"
        
        prompt += (
            "Business functions may be shown as `function_id@version`; when using "
            "invoke_business_function, pass them as `function_id` without the @version "
            "suffix and `version` separately. "
            "Navigator runtime identifiers such as skillId, functionId, frameId, "
            "skillFrameId, function_frame_id, taskId, sessionId, messageId, and "
            "values prefixed with frm_, lgt_, msg_, or sess_ are internal tracing "
            "ids. Use them only when reasoning about execution history or when the "
            "user explicitly asks for trace/debug identifiers; do not expose them "
            "in normal user-facing summaries, and never present them as an order "
            "number, waybill number, business document number, or proof that a "
            "formal order was created. Only call a value an order/waybill/business "
            "id when the tool output schema or result explicitly provides a public "
            "business identifier field such as orderNo, orderIdentifier, or waybillNo. "
            "For page-opening structured outputs, prefer the tool summary and action "
            "label; do not infer business success or business ids from page actions, "
            "buttons, Navigator frame ids, skill ids, or action metadata. "
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
            _build_recoverable_interruption_prompt(runtime_context, prompt),
            _build_active_plan_prompt(runtime_context),
            _build_root_planning_policy_prompt(runtime_context, skill_id),
            f"User request: {prompt}",
            f"Skill input: {json.dumps(skill_input, ensure_ascii=False)}",
            _build_visible_context_prompt(runtime_context),
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


def _runtime_context_for_child_skill(
    runtime_context: dict[str, Any] | None,
    root_context_summary: dict[str, Any] | None,
    child_manifest: SkillManifest,
) -> dict[str, Any] | None:
    child_context = dict(runtime_context or {})
    visibility = _context_visibility_for_child_manifest(child_manifest)
    child_context["_context_visibility"] = visibility
    child_context.pop("_visible_root_context_summary", None)
    if visibility == "summary" and root_context_summary:
        child_context["_visible_root_context_summary"] = root_context_summary
    return child_context


def _resume_parent_if_waiting(runtime: SkillRuntime, parent_frame_id: str) -> None:
    parent = runtime.get_frame(parent_frame_id)
    if parent and parent.status == FrameStatus.WAITING_CHILD:
        runtime.resume_from_child(parent_frame_id)


def _context_visibility_for_child_manifest(manifest: SkillManifest) -> str:
    visibility = _normalize_context_visibility(manifest.context_visibility)
    if visibility != "passthrough":
        return visibility
    if manifest.visibility == "builtin" or manifest.id.startswith("system."):
        return "passthrough"
    return "isolated"


def _normalize_context_visibility(value: str | None) -> str:
    normalized = (value or "isolated").strip().lower()
    if normalized in {"isolated", "summary", "passthrough"}:
        return normalized
    return "isolated"


def _recoverable_interruption_context(working_state: dict[str, Any]) -> dict[str, Any] | None:
    if working_state.get("continuation_state") != "INTERRUPTED":
        return None
    if not working_state.get("recoverable"):
        return None
    context = {
        "reason": working_state.get("interrupt_reason") or "unknown",
        "last_error": working_state.get("last_error") or "",
        "last_task_id": working_state.get("last_task_id") or "",
        "interrupted_at": working_state.get("interrupted_at") or "",
    }
    pending_child = working_state.get("pending_recoverable_child")
    if isinstance(pending_child, dict):
        context["pending_child_skill"] = _safe_content(pending_child)
    recoverable_focus = working_state.get("recoverable_focus_summary")
    if isinstance(recoverable_focus, dict):
        context["recoverable_focus"] = _safe_content(recoverable_focus)
    recoverable_focus_stack = working_state.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        context["recoverable_focus_stack"] = _safe_content(recoverable_focus_stack)
    return context


def _active_plan_context(working_state: dict[str, Any]) -> Any | None:
    active_plan = working_state.get("active_plan")
    if isinstance(active_plan, (dict, list)) and active_plan:
        return _safe_content(active_plan)
    return None


def _build_recoverable_interruption_prompt(
    runtime_context: dict[str, Any] | None,
    prompt: str,
) -> str:
    if not runtime_context:
        return ""
    interruption = runtime_context.get("_recoverable_interruption")
    if not isinstance(interruption, dict):
        return ""
    parts = [
        "Previous execution was interrupted.",
        f"Reason: {interruption.get('reason') or 'unknown'}",
    ]
    last_error = interruption.get("last_error")
    if last_error:
        parts.append(f"Last error: {last_error}")
    last_task_id = interruption.get("last_task_id")
    if last_task_id:
        parts.append(f"Last task: {last_task_id}")
    pending_child = interruption.get("pending_child_skill")
    if isinstance(pending_child, dict):
        parts.append(
            "Pending child skill: "
            f"{json.dumps(pending_child, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus = interruption.get("recoverable_focus")
    if isinstance(recoverable_focus, dict):
        parts.append(
            "Recoverable focus: "
            f"{json.dumps(recoverable_focus, ensure_ascii=False, sort_keys=True)}"
        )
    recoverable_focus_stack = interruption.get("recoverable_focus_stack")
    if isinstance(recoverable_focus_stack, list):
        parts.append(
            "Recoverable focus stack: "
            f"{json.dumps(recoverable_focus_stack, ensure_ascii=False, sort_keys=True)}"
        )
    parts.append(f"User's new instruction: {prompt}")
    parts.append(
        "The interrupted work is a recoverable candidate, not a mandatory "
        "continuation. First resolve intent_resolution as one of "
        "CONTINUE_PREVIOUS, ABANDON_PREVIOUS, START_UNRELATED_NEW_TASK, or "
        "ASK_CLARIFICATION. If the new instruction explicitly continues, "
        "corrects, or supplements the interrupted work, use CONTINUE_PREVIOUS "
        "and continue from the existing frame context. If there is a pending "
        "child skill, use resume_recoverable_child_skill so the same child "
        "frame continues. If the user explicitly stops/cancels it, use "
        "ABANDON_PREVIOUS. If the user asks for an unrelated new task, use "
        "START_UNRELATED_NEW_TASK. For either shelving case, summarize what "
        "is being abandoned, then use shelve_interrupted_frame with decision "
        "set to ABANDON_PREVIOUS or START_UNRELATED_NEW_TASK, include "
        "intent_resolution, and include an abandoned_interruption summary. "
        "If the intent is ambiguous and the interrupted work involves approval "
        "or business side effects, use ASK_CLARIFICATION and ask for "
        "clarification via submit_skill_result."
    )
    return "\n".join(parts)


def _build_active_plan_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    active_plan = runtime_context.get("_active_plan")
    if not isinstance(active_plan, (dict, list)):
        return ""
    return "\n".join([
        "Active task plan:",
        json.dumps(active_plan, ensure_ascii=False, sort_keys=True),
        (
            "Rule: Treat active_plan as the current persistent root working plan. "
            "Before finalizing this turn, compare the intended result against the "
            "plan. If the plan is still useful, preserve or update it in "
            "submit_skill_result.structured_output.active_plan. If the user "
            "explicitly abandons it or starts an unrelated task, set "
            "intent_resolution to ABANDON_PREVIOUS or START_UNRELATED_NEW_TASK "
            "and summarize the abandoned plan."
        ),
    ])


def _build_root_planning_policy_prompt(
    runtime_context: dict[str, Any] | None,
    skill_id: str,
) -> str:
    if skill_id != "system.root":
        return ""
    if not runtime_context or runtime_context.get("_persistent_frame") is not True:
        return ""
    return (
        "Persistent root planning policy: For complex, multi-intent, multi-skill, "
        "or externally coordinated work, maintain an active_plan in "
        "submit_skill_result.structured_output.active_plan. The plan should be "
        "compact, structured, and updated as work progresses; it is working "
        "state for future turns, not user-facing narration."
    )


def _build_visible_context_prompt(runtime_context: dict[str, Any] | None) -> str:
    if not runtime_context:
        return ""
    summary = runtime_context.get("_visible_root_context_summary")
    if not isinstance(summary, dict):
        return ""
    return (
        "Visible parent/root context summary:\n"
        f"{json.dumps(summary, ensure_ascii=False, sort_keys=True)}"
    )


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


def _tool_specs(manifest: SkillManifest, persistent_frame: bool = False) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    for name in [*_GLOBAL_TOOL_NAMES, *manifest.allowed_tools]:
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            continue
        if name in _KNOWN_TOOL_SCHEMAS:
            specs.append(_KNOWN_TOOL_SCHEMAS[name])
    if "submit_skill_result" not in {s["function"]["name"] for s in specs}:
        specs.append(_KNOWN_TOOL_SCHEMAS["submit_skill_result"])
    if persistent_frame and manifest.id == "system.root":
        specs.append(_KNOWN_TOOL_SCHEMAS["resume_recoverable_child_skill"])
        specs.append(_KNOWN_TOOL_SCHEMAS["shelve_interrupted_frame"])
    return _dedupe_tool_specs(specs)


_GLOBAL_TOOL_NAMES = [
    "invoke_business_function",
    "list_skill_resources",
    "read_skill_resource",
    "read_frame_execution_report",
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
    "invoke_business_skill": {
        "type": "function",
        "function": {
            "name": "invoke_business_skill",
            "description": "Invoke a child business skill and return its promoted result.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string"},
                    "instruction": {"type": "string"},
                    "input": {"type": "object"},
                },
                "required": ["skill_id", "instruction"],
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
    "read_frame_execution_report": {
        "type": "function",
        "function": {
            "name": "read_frame_execution_report",
            "description": (
                "Read a persisted Frame execution report by report_ref or by "
                "task_id/frame_id. Use this for debugging or reviewing prior "
                "frame work instead of re-reading raw frame JSON."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "report_ref": {
                        "type": "string",
                        "description": "Frame report reference, e.g. frame-report://task_id/frame_id.",
                    },
                    "task_id": {"type": "string"},
                    "frame_id": {"type": "string"},
                    "mode": {
                        "type": "string",
                        "enum": ["summary", "metadata", "markdown"],
                        "description": "summary is compact; markdown returns capped human-readable report text.",
                    },
                    "max_chars": {"type": "integer"},
                },
                "required": [],
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
                    "structured_output": {
                        "type": "object",
                        "description": (
                            "Skill result payload. Persistent root may include "
                            "active_plan for compact multi-turn working state "
                            "and intent_resolution for interruption handling."
                        ),
                    },
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "structured_output"],
            },
        },
    },
    "shelve_interrupted_frame": {
        "type": "function",
        "function": {
            "name": "shelve_interrupted_frame",
            "description": (
                "Root-only tool. Shelve the previous recoverable interruption "
                "when the user stops it or asks for an unrelated new task."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "decision": {
                        "type": "string",
                        "enum": ["ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"],
                    },
                    "intent_resolution": {
                        "type": "string",
                        "enum": ["ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"],
                        "description": (
                            "Normalized intent after comparing the user's new "
                            "instruction with the interrupted focus."
                        ),
                    },
                    "abandoned_interruption": {
                        "type": "object",
                        "description": "Compact summary of the interrupted work being shelved.",
                    },
                    "new_task": {
                        "type": "object",
                        "description": "Optional compact description of the unrelated new task.",
                    },
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "decision", "abandoned_interruption"],
            },
        },
    },
    "resume_recoverable_child_skill": {
        "type": "function",
        "function": {
            "name": "resume_recoverable_child_skill",
            "description": (
                "Root-only tool. Continue the pending recoverable child skill "
                "frame after an interrupted child-skill execution."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "instruction": {
                        "type": "string",
                        "description": "Natural language instruction for continuing the child skill.",
                    },
                },
                "required": ["instruction"],
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


def _execution_report_payload_from_frame(frame: Any | None) -> dict[str, Any]:
    if frame is None:
        return {}
    state = getattr(frame, "private_working_state", {}) or {}
    if not isinstance(state, dict):
        return {}
    payload: dict[str, Any] = {}
    report_ref = state.get("execution_report_ref")
    if isinstance(report_ref, str) and report_ref:
        payload["execution_report_ref"] = report_ref
    report_digest = state.get("execution_report_digest")
    if isinstance(report_digest, dict) and report_digest:
        payload["execution_report_digest"] = _safe_content(report_digest)
    return payload


def _child_approval_report_payload(child_frame: Any | None) -> dict[str, Any]:
    payload = _execution_report_payload_from_frame(child_frame)
    report_ref = payload.get("execution_report_ref")
    if report_ref:
        payload["child_execution_report_ref"] = report_ref
    report_digest = payload.get("execution_report_digest")
    if report_digest:
        payload["child_execution_report_digest"] = report_digest
    return payload


def _execution_report_payload_from_result(result: Any) -> dict[str, Any]:
    if not isinstance(result, dict):
        return {}
    payload: dict[str, Any] = {}
    report_ref = (
        result.get("execution_report_ref")
        or result.get("executionReportRef")
        or result.get("report_ref")
        or result.get("reportRef")
    )
    if isinstance(report_ref, str) and report_ref:
        payload["execution_report_ref"] = report_ref
    report_digest = result.get("execution_report_digest") or result.get("executionReportDigest")
    if isinstance(report_digest, dict) and report_digest:
        payload["execution_report_digest"] = _safe_content(report_digest)
    nested_result = result.get("result")
    if isinstance(nested_result, dict):
        nested_payload = _execution_report_payload_from_result(nested_result)
        payload.setdefault("execution_report_ref", nested_payload.get("execution_report_ref"))
        payload.setdefault("execution_report_digest", nested_payload.get("execution_report_digest"))
    return {key: value for key, value in payload.items() if value is not None}


def _safe_tool_call_args(args: dict[str, Any]) -> dict[str, Any]:
    safe_args = dict(args)
    for key in list(safe_args):
        lowered = key.lower()
        if "token" in lowered or "secret" in lowered or "password" in lowered:
            safe_args[key] = "<redacted>"
    return _safe_content(safe_args)


def _guard_final_summary(
    summary: Any,
    structured_output: Any,
    frame: Any | None,
) -> str:
    text = summary if isinstance(summary, str) else str(summary or "")
    text = text.strip()
    if not text:
        return _safe_summary_from_context(frame, structured_output)

    claims_business_id = _BUSINESS_ID_CLAIM_PATTERN.search(text) is not None
    claims_formal_order = _FORMAL_ORDER_SUCCESS_PATTERN.search(text) is not None
    if not (claims_business_id or claims_formal_order):
        return text

    allowed_ids = _collect_business_identifier_values(structured_output)
    if frame is not None:
        for message in frame.private_messages:
            allowed_ids.update(_collect_business_identifier_values(message.get("content")))

    if claims_business_id and any(value and value in text for value in allowed_ids):
        return text

    if _is_page_action_without_business_identifier(structured_output) or _contains_runtime_identifier_claim(text):
        return _safe_summary_from_context(frame, structured_output)

    if claims_business_id and not allowed_ids:
        return _safe_summary_from_context(frame, structured_output)

    return text


def _contains_runtime_identifier_claim(text: str) -> bool:
    if _NAVIGATOR_RUNTIME_IDENTIFIER_PATTERN.search(text):
        return True
    return any(field in text for field in _NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS)


def _is_page_action_without_business_identifier(value: Any) -> bool:
    actions = _collect_action_like_outputs(value)
    if not actions:
        return False
    return not _collect_business_identifier_values(value)


def _collect_action_like_outputs(value: Any) -> list[dict[str, Any]]:
    parsed = _parse_maybe_json(value)
    if isinstance(parsed, list):
        actions: list[dict[str, Any]] = []
        for item in parsed:
            actions.extend(_collect_action_like_outputs(item))
        return actions
    if not isinstance(parsed, dict):
        return []
    actions = []
    action_type = parsed.get("type") or parsed.get("actionType") or parsed.get("action")
    if isinstance(action_type, str) and re.search(r"OPEN_.*PAGE|OPEN_TMS_PAGE", action_type, re.IGNORECASE):
        actions.append(parsed)
    for key in ("actions", "structured_output", "structuredOutput", "result", "output", "payload", "data"):
        actions.extend(_collect_action_like_outputs(parsed.get(key)))
    return actions


def _collect_business_identifier_values(value: Any) -> set[str]:
    parsed = _parse_maybe_json(value)
    found: set[str] = set()
    if isinstance(parsed, list):
        for item in parsed:
            found.update(_collect_business_identifier_values(item))
        return found
    if not isinstance(parsed, dict):
        return found
    for key, nested in parsed.items():
        if key in _BUSINESS_IDENTIFIER_FIELDS and isinstance(nested, (str, int, float)):
            text = str(nested).strip()
            if text:
                found.add(text)
        elif key not in _NAVIGATOR_RUNTIME_IDENTIFIER_FIELDS:
            found.update(_collect_business_identifier_values(nested))
    return found


def _safe_summary_from_context(frame: Any | None, structured_output: Any) -> str:
    if frame is not None:
        for message in reversed(frame.private_messages):
            candidate = _extract_safe_summary(message.get("content"))
            if candidate:
                return candidate
    candidate = _extract_safe_summary(structured_output)
    if candidate:
        return candidate
    if _is_page_action_without_business_identifier(structured_output):
        return "已完成操作，可继续处理。"
    return "已完成操作。"


def _extract_safe_summary(value: Any) -> str | None:
    parsed = _parse_maybe_json(value)
    if isinstance(parsed, list):
        for item in reversed(parsed):
            candidate = _extract_safe_summary(item)
            if candidate:
                return candidate
        return None
    if not isinstance(parsed, dict):
        return None
    for key in ("summary", "message", "label"):
        candidate = parsed.get(key)
        if isinstance(candidate, str) and candidate.strip():
            text = candidate.strip()
            if (
                not _contains_runtime_identifier_claim(text)
                and not _BUSINESS_ID_CLAIM_PATTERN.search(text)
                and not _FORMAL_ORDER_SUCCESS_PATTERN.search(text)
            ):
                return text
    for key in ("result", "output", "data", "structured_output", "structuredOutput", "payload"):
        candidate = _extract_safe_summary(parsed.get(key))
        if candidate:
            return candidate
    return None


def _parse_maybe_json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


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


def _business_function_result_is_suspended(result: dict[str, Any]) -> bool:
    status = str(result.get("status") or "").upper()
    return (
        status == "SUSPENDED"
        or result.get("approvalRequired") is True
        or result.get("approval_required") is True
        or result.get("approval_wait") is True
    )


def _business_function_suspend_id(result: dict[str, Any]) -> str | None:
    value = result.get("suspendId") or result.get("suspend_id")
    return str(value) if value is not None else None


def _business_function_timeout_at(result: dict[str, Any]) -> str | None:
    value = result.get("timeoutAt") or result.get("timeout_at")
    return str(value) if value is not None else None


def _business_function_approval_summary(result: dict[str, Any], function_id: str) -> dict[str, Any]:
    raw_summary = result.get("approvalSummary") or result.get("approval_summary") or result.get("summary")
    if isinstance(raw_summary, dict):
        summary = dict(raw_summary)
    else:
        summary = {}
    summary.setdefault("approval_type", "business_function")
    summary.setdefault("function_id", function_id)
    summary.setdefault(
        "title",
        result.get("message") or f"Business function {function_id} requires approval",
    )
    summary.setdefault("reason", "approval_required")
    return _safe_content(summary)


def _manifest_for_frame(runtime: SkillRuntime, frame: Any) -> SkillManifest | None:
    """Use the frame-frozen manifest so account registry reloads cannot affect execution."""
    snapshot = frame.private_working_state.get("_skill_manifest")
    if isinstance(snapshot, dict):
        try:
            return SkillManifest(**snapshot)
        except Exception:
            logger.warning("Invalid manifest snapshot in frame=%s", frame.frame_id, exc_info=True)
    return runtime.registry.get_manifest(frame.skill_id)
