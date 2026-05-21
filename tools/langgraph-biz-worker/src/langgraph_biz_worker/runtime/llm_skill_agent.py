"""LLM-driven Skill executor.

This runner is intentionally small and runtime-owned: the model may request
tools, but only SkillRuntime can validate and complete a Frame.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, ToolMessage

from ..models import FrameStatus, QueryEvent, SkillManifest
from ..tools.business_function_tools import (
    get_business_function_schema,
    invoke_business_function,
    list_business_functions,
)
from .account_file_tools import AccountFileTools
from .account_context_files import build_account_context_prompt
from .artifact_store import ArtifactStore
from .llm_call_guard import invoke_chat_model
from .llm_agent_prompts import (
    _active_plan_context,
    model_visible_context,
    _recoverable_interruption_context,
)
from .llm_message_builder import build_initial_llm_messages
from .llm_tool_call_codec import (
    _SCRUB_TEMPLATE,
    _execution_report_payload_from_frame,
    _execution_report_payload_from_result,
    _extract_tool_calls,
    _safe_content,
    _safe_tool_call_args,
    _scrub_create_artifact_content,
)
from .llm_child_recovery import (
    _invoke_business_skill_tool,
    _resume_recoverable_child_skill_tool,
)
from .llm_business_function_adapter import (
    _business_function_approval_summary,
    _business_function_result_is_suspended,
    _business_function_suspend_id,
    _business_function_timeout_at,
)
from .llm_tool_dispatcher import (
    _TOOL_UNHANDLED,
    LlmToolDispatchContext,
    LlmToolDispatcher,
    _append_tool_audit,
    _emit_progress_event,
    _runtime_client_app_id,
    _tool_function_id,
)
from .llm_tool_schemas import (
    _RUNTIME_ALWAYS_ALLOWED_TOOL_NAMES,
    _bind_tools,
    _tool_specs,
)
from .runtime_message_event_log import (
    record_assistant_runtime_message,
    record_checkpoint_runtime_event,
    record_initial_runtime_messages,
    record_runtime_message_event,
    record_tool_result_runtime_message,
)
from .execution_policy import ExecutionPolicy
from .file_layout import date_parts_for_frame
from .public_skill_resource_tools import PublicSkillResourceTools
from .skill_runtime import SkillRuntime
from .tool_provider import ToolProvider, ToolResult

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Runtime-local constants
# ---------------------------------------------------------------------------


class LlmSkillAgent:
    """Run a Skill frame by repeatedly processing model tool calls."""

    def __init__(
        self,
        chat_model: BaseChatModel,
        runtime: SkillRuntime,
        max_iterations: int = 6,
        data_root: Path | None = None,
        tool_provider: ToolProvider | None = None,
    ) -> None:
        self._model = chat_model
        self._runtime = runtime
        self._max_iterations = max_iterations
        self._data_root = data_root
        self._tool_provider = tool_provider
        self._tool_dispatcher = LlmToolDispatcher(runtime, data_root=data_root)

    def run(
        self,
        task_id: str,
        frame_id: str,
        prompt: str,
        account_id: str | None = None,
        runtime_context: dict[str, Any] | None = None,
        persistent_frame: bool = False,
    ) -> list[QueryEvent]:
        """Execute a Skill frame or a persistent conversation-root turn."""
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
        else:
            runtime_context.pop("_persistent_frame", None)
            runtime_context.pop("_recoverable_interruption", None)
            runtime_context.pop("_active_plan", None)
        event_skill_id = None if persistent_frame else frame.skill_id
        event_presentation_hint = "root_frame" if persistent_frame else None

        manifest = _manifest_for_frame(self._runtime, frame)
        if manifest is None:
            self._runtime.fail_frame(frame_id, f"Skill manifest not found: {frame.skill_id}")
            return [QueryEvent(type="error", task_id=task_id, error="Skill manifest not found")]
        try:
            execution_policy = ExecutionPolicy.from_context(runtime_context)
        except ValueError as exc:
            self._runtime.fail_frame(frame_id, str(exc))
            return [QueryEvent(
                type="error",
                task_id=task_id,
                skill_frame_id=frame_id,
                skill_id=event_skill_id,
                presentation_hint=event_presentation_hint,
                error=str(exc),
            )]

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

        runtime_context["_skill_name"] = frame.skill_id
        runtime_context["_llm_submission_skill_id"] = (
            "conversation.root" if persistent_frame else frame.skill_id
        )
        runtime_context["_llm_submission_session_id"] = (
            frame.conversation_id or frame.session_id or task_id
        )
        runtime_context["_llm_submission_require_standard_context"] = bool(frame.conversation_id)
        runtime_context["_llm_submission_date_parts"] = date_parts_for_frame(frame)
        if self._data_root:
            runtime_context["_llm_submission_data_root"] = str(self._data_root)
        recovery = runtime_context.get("_runtime_protocol_recovery")
        if isinstance(recovery, dict) and recovery.get("enabled") and not recovery.get("frame_id"):
            recovery["frame_id"] = frame_id
        messages = build_initial_llm_messages(
            manifest=manifest,
            prompt=prompt,
            skill_input=frame.input,
            account_context_prompt=account_context_prompt,
            runtime_context=runtime_context,
        )
        record_initial_runtime_messages(
            messages,
            runtime_context,
            task_id=task_id,
            frame_id=frame_id,
        )
        events: list[QueryEvent] = []
        provider_tool_specs = self._provider_tool_specs(
            manifest,
            frame.skill_id,
            runtime_context,
            execution_policy=execution_policy,
        )
        if self._tool_provider:
            runtime_context["_provider_allowed_tools"] = {
                spec["function"]["name"] for spec in provider_tool_specs
            }
        runtime_context["_llm_submission_tools"] = _tool_specs(
            manifest,
            persistent_frame=persistent_frame,
            extra_tool_specs=provider_tool_specs,
            enabled_tool_names=execution_policy.allowed_tools,
        )
        model = _bind_tools(
            self._model,
            manifest,
            persistent_frame=persistent_frame,
            extra_tool_specs=provider_tool_specs,
            enabled_tool_names=execution_policy.allowed_tools,
        )

        for iteration in range(1, self._max_iterations + 1):
            runtime_context["_llm_submission_iteration"] = iteration
            self._append_runtime_memory_checkpoint_messages(
                runtime_context,
                messages=messages,
                frame_id=frame_id,
                task_id=task_id,
            )
            record_checkpoint_runtime_event(
                runtime_context,
                task_id=task_id,
                frame_id=frame_id,
                checkpoint="before_model_call",
            )
            try:
                response = invoke_chat_model(
                    model,
                    messages,
                    runtime_context=runtime_context,
                    operation="skill_agent.invoke",
                    task_id=task_id,
                    frame_id=frame_id,
                )
            except Exception as exc:
                interruption_reason = _model_exception_interruption_reason(exc)
                if persistent_frame:
                    self._runtime.record_recoverable_interruption(
                        frame_id,
                        reason=interruption_reason,
                        error=str(exc),
                        task_id=task_id,
                    )
                else:
                    self._runtime.fail_frame(frame_id, str(exc))
                return [QueryEvent(
                    type="error",
                    task_id=task_id,
                    skill_frame_id=frame_id,
                    reason=interruption_reason,
                    error=str(exc),
                )]
            tool_calls = _extract_tool_calls(response)
            record_assistant_runtime_message(
                response,
                runtime_context,
                task_id=task_id,
                frame_id=frame_id,
            )
            if tool_calls and _has_runtime_memory_terminal_tool_call(tool_calls):
                queued_messages = _runtime_memory_checkpoint_messages(runtime_context)
                if queued_messages:
                    self._append_private_message(frame_id, "assistant", _safe_content(response.content))
                    self._append_runtime_memory_checkpoint_messages(
                        runtime_context,
                        messages=messages,
                        frame_id=frame_id,
                        queued_messages=queued_messages,
                        task_id=task_id,
                    )
                    continue

            messages.append(response)
            self._append_private_message(frame_id, "assistant", _safe_content(response.content))

            if not tool_calls:
                if persistent_frame:
                    queued_messages = _runtime_memory_checkpoint_messages(runtime_context)
                    if queued_messages:
                        self._append_runtime_memory_checkpoint_messages(
                            runtime_context,
                            messages=messages,
                            frame_id=frame_id,
                            queued_messages=queued_messages,
                            task_id=task_id,
                        )
                        continue
                    final_text = _assistant_response_text(response)
                    if final_text:
                        _mark_runtime_memory_finalizing(runtime_context)
                        validation = self._runtime.submit_persistent_turn_result(
                            frame_id=frame_id,
                            summary=final_text,
                            structured_output={
                                "turn_status": "FINAL_FOR_USER",
                                "message": final_text,
                                "completion_mode": "assistant_message",
                            },
                        )
                        if validation.ok:
                            record_checkpoint_runtime_event(
                                runtime_context,
                                task_id=task_id,
                                frame_id=frame_id,
                                checkpoint="persistent_turn_completed",
                            )
                            return events
                        _mark_runtime_memory_running(runtime_context)
                        error = "Root assistant response failed output contract: " + "; ".join(validation.errors)
                    else:
                        error = "Root assistant returned no tool call and no final content"
                    self._runtime.record_recoverable_interruption(
                        frame_id,
                        reason="model_error",
                        error=error,
                        task_id=task_id,
                    )
                    events.append(QueryEvent(
                        type="error",
                        task_id=task_id,
                        skill_frame_id=frame_id,
                        skill_id=event_skill_id,
                        presentation_hint=event_presentation_hint,
                        error=error,
                    ))
                    return events

                error = (
                    "Child skill returned a final assistant message without "
                    "submit_skill_result or handoff_to_parent"
                )
                self._runtime.fail_frame(frame_id, error)
                events.append(QueryEvent(
                    type="error",
                    task_id=task_id,
                    skill_frame_id=frame_id,
                    skill_id=event_skill_id,
                    presentation_hint=event_presentation_hint,
                    error=error,
                ))
                return events

            for call in tool_calls:
                terminal_tool_call = _is_runtime_memory_terminal_tool_call(call)
                if terminal_tool_call:
                    _mark_runtime_memory_finalizing(runtime_context)
                event = self._execute_tool_call(
                    task_id, frame_id, manifest, call,
                    account_id=account_id,
                    runtime_context=runtime_context,
                    artifact_store=artifact_store,
                    file_tools=file_tools,
                    public_resource_tools=public_resource_tools,
                    persistent_frame=persistent_frame,
                    execution_policy=execution_policy,
                )
                events.extend(event["events"])
                tool_result = event["tool_result"]

                # --- Context governance: scrub create_artifact content ---
                scrub_placeholder = event.get("scrub_placeholder")
                if scrub_placeholder:
                    # Scrub the tool-call args in the messages list
                    _scrub_create_artifact_content(messages, call["id"], scrub_placeholder)

                tool_message = ToolMessage(
                    content=json.dumps(model_visible_context(tool_result), ensure_ascii=False),
                    tool_call_id=call["id"],
                )
                messages.append(tool_message)
                record_tool_result_runtime_message(
                    tool_message,
                    tool_result,
                    runtime_context,
                    task_id=task_id,
                    frame_id=frame_id,
                )
                record_checkpoint_runtime_event(
                    runtime_context,
                    task_id=task_id,
                    frame_id=frame_id,
                    checkpoint="after_tool_call",
                )
                self._append_private_message(frame_id, "tool", tool_result)

                current = self._runtime.get_frame(frame_id)
                if terminal_tool_call and not (
                    (current and current.status == FrameStatus.COMPLETED)
                    or event.get("persistent_turn_completed")
                    or event.get("suspended")
                ):
                    _mark_runtime_memory_running(runtime_context)
                if current and current.status == FrameStatus.COMPLETED:
                    record_checkpoint_runtime_event(
                        runtime_context,
                        task_id=task_id,
                        frame_id=frame_id,
                        checkpoint="frame_completed",
                    )
                    return events
                if event.get("persistent_turn_completed"):
                    record_checkpoint_runtime_event(
                        runtime_context,
                        task_id=task_id,
                        frame_id=frame_id,
                        checkpoint="persistent_turn_completed",
                    )
                    return events
                if event.get("suspended"):
                    record_checkpoint_runtime_event(
                        runtime_context,
                        task_id=task_id,
                        frame_id=frame_id,
                        checkpoint="suspended",
                    )
                    return events
                if _is_non_recoverable_tool_error(tool_result):
                    error = tool_result.get("user_message") or tool_result.get("error") or "Non-recoverable tool error"
                    if persistent_frame:
                        self._runtime.submit_persistent_turn_result(
                            frame_id=frame_id,
                            summary=error,
                            structured_output={
                                "status": "ERROR",
                                "message": error,
                                "error_category": tool_result.get("error_category") or "NON_RECOVERABLE_TOOL_ERROR",
                                "recoverable": False,
                                "llm_retry_allowed": False,
                                "function_frame_id": tool_result.get("function_frame_id"),
                            },
                        )
                    else:
                        self._runtime.fail_frame(frame_id, error)
                    events.append(QueryEvent(
                        type="error",
                        task_id=task_id,
                        skill_frame_id=frame_id,
                        skill_id=event_skill_id,
                        presentation_hint=event_presentation_hint,
                        reason="non_recoverable_tool_error",
                        error=error,
                    ))
                    return events
                self._append_runtime_memory_checkpoint_messages(
                    runtime_context,
                    messages=messages,
                    frame_id=frame_id,
                    task_id=task_id,
                )

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
            skill_id=event_skill_id,
            presentation_hint=event_presentation_hint,
            error=error,
        ))
        return events

    def _append_runtime_memory_checkpoint_messages(
        self,
        runtime_context: dict[str, Any] | None,
        *,
        messages: list[Any],
        frame_id: str,
        queued_messages: list[HumanMessage] | None = None,
        task_id: str = "",
    ) -> None:
        for queued_message in queued_messages or _runtime_memory_checkpoint_messages(runtime_context):
            messages.append(queued_message)
            record_runtime_message_event(
                "message",
                runtime_context,
                task_id=task_id,
                frame_id=frame_id,
                message=queued_message,
                phase="queued_user_input",
            )
            self._append_private_message(frame_id, "user", queued_message.content)

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
        execution_policy: ExecutionPolicy | None = None,
    ) -> dict[str, Any]:
        name = call["name"]
        args = call["args"]
        safe_args = _safe_tool_call_args(args)
        frame = self._runtime.get_frame(frame_id)
        parent_frame_id = frame.parent_frame_id if frame else None
        session_key = (frame.conversation_id or frame.session_id) if frame else None
        event_skill_id = None if persistent_frame else manifest.id
        event_presentation_hint = "root_frame" if persistent_frame else None
        tool_use_event = QueryEvent(
            type="tool_use",
            task_id=task_id,
            skill_frame_id=frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=event_skill_id,
            presentation_hint=event_presentation_hint,
            content=name,
            tool_call_id=call.get("id"),
            tool_name=name,
            function_id=_tool_function_id(name, safe_args),
            args=safe_args,
        )
        events: list[QueryEvent] = [tool_use_event]
        _emit_progress_event(runtime_context, tool_use_event)
        self._append_tool_call_message(frame_id, name, safe_args)
        _append_tool_audit(
            self._data_root,
            task_id,
            frame_id,
            manifest.id,
            name,
            safe_args,
            phase="request",
            session_id=session_key,
        )

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
                execution_policy=execution_policy,
            )
        except Exception as exc:
            logger.exception("LLM skill tool call failed: %s", name)
            result = {"ok": False, "error": str(exc)}
        extra_events = result.pop("_events", []) if isinstance(result, dict) else []
        suspended = bool(result.pop("_suspended", False)) if isinstance(result, dict) else False
        _append_tool_audit(
            self._data_root,
            task_id,
            frame_id,
            manifest.id,
            name,
            safe_args,
            phase="response",
            session_id=session_key,
            result=result,
        )

        event_type = "skill_result_submit" if name == "submit_skill_result" and result.get("ok") else "tool_result"
        if name == "submit_skill_result" and not result.get("ok"):
            event_type = "skill_result_reject"

        report_payload = _execution_report_payload_from_result(result)
        result_event = QueryEvent(
            type=event_type,
            task_id=task_id,
            skill_frame_id=frame_id,
            parent_frame_id=parent_frame_id,
            skill_id=event_skill_id,
            presentation_hint=event_presentation_hint,
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
        execution_policy: ExecutionPolicy | None = None,
    ) -> dict[str, Any]:
        if not _tool_authorized(name, execution_policy):
            return {"ok": False, "error": _tool_not_authorized_error(name)}

        dispatch_context = LlmToolDispatchContext(
            frame_id=frame_id,
            task_id=task_id,
            account_id=account_id,
            runtime_context=runtime_context,
            artifact_store=artifact_store,
            file_tools=file_tools,
            public_resource_tools=public_resource_tools,
            persistent_frame=persistent_frame,
        )
        low_risk_result = self._tool_dispatcher.dispatch_low_risk(name, args, dispatch_context)
        if low_risk_result is not _TOOL_UNHANDLED:
            return low_risk_result

        business_result = self._tool_dispatcher.dispatch_business_function(
            name,
            args,
            dispatch_context,
            self._finalize_business_function_call,
            list_business_functions_fn=list_business_functions,
            get_business_function_schema_fn=get_business_function_schema,
            invoke_business_function_fn=invoke_business_function,
        )
        if business_result is not _TOOL_UNHANDLED:
            return business_result

        if name == "invoke_business_skill":
            return _invoke_business_skill_tool(
                self._runtime,
                frame_id=frame_id,
                args=args,
                task_id=task_id,
                account_id=account_id,
                runtime_context=runtime_context,
                persistent_frame=persistent_frame,
                run_child_frame=self.run,
            )

        if name == "resume_recoverable_child_skill":
            return _resume_recoverable_child_skill_tool(
                self._runtime,
                frame_id=frame_id,
                args=args,
                task_id=task_id,
                account_id=account_id,
                runtime_context=runtime_context,
                persistent_frame=persistent_frame,
                run_child_frame=self.run,
            )

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

        if name == "handoff_to_parent":
            if persistent_frame:
                return {"ok": False, "error": "handoff_to_parent is only available on child frames"}
            structured_output = args.get("structured_output")
            if not isinstance(structured_output, dict):
                structured_output = {}
            requires_parent_synthesis = args.get("requires_parent_synthesis")
            if not isinstance(requires_parent_synthesis, bool):
                requires_parent_synthesis = None
            validation = self._runtime.handoff_to_parent(
                frame_id=frame_id,
                summary=args.get("summary", ""),
                reason=args.get("reason") or "OTHER",
                intent_resolution=args.get("intent_resolution") or "RETURN_TO_PARENT",
                parent_instruction=args.get("parent_instruction"),
                requires_parent_synthesis=requires_parent_synthesis,
                structured_output=structured_output,
                artifact_refs=args.get("artifact_refs"),
                evidence_refs=args.get("evidence_refs"),
            )
            frame = self._runtime.get_frame(frame_id)
            return {
                "ok": validation.ok,
                "errors": validation.errors,
                "handoff_to_parent": validation.ok,
                "structured_output": frame.output if frame else structured_output,
                **_execution_report_payload_from_frame(frame),
            }

        if name == "submit_skill_result":
            structured_output = args.get("structured_output") or {}
            summary = args.get("summary", "")
            if not isinstance(summary, str):
                summary = str(summary or "")
            summary = summary.strip()
            if not persistent_frame and _is_waiting_for_user_input_output(structured_output):
                validation = self._runtime.submit_user_input_request(
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
                    "turn_status": "WAITING_FOR_USER_INPUT",
                    "paused": True,
                    "structured_output": frame.output if frame else structured_output,
                    **_execution_report_payload_from_frame(frame),
                    "_suspended": True,
                }
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

        provider_result = self._call_provider_tool(name, args, runtime_context, execution_policy)
        if provider_result is not _TOOL_UNHANDLED:
            return provider_result

        return {"ok": False, "error": f"Unknown tool: {name}"}

    def _provider_tool_specs(
        self,
        manifest: SkillManifest,
        skill_name: str,
        runtime_context: dict[str, Any],
        *,
        execution_policy: ExecutionPolicy,
    ) -> list[dict[str, Any]]:
        if not self._tool_provider:
            return []
        allowed = set(manifest.allowed_tools or [])
        specs = []
        for spec in self._tool_provider.list_tools(skill_name, runtime_context):
            if allowed and spec.name not in allowed:
                continue
            if not execution_policy.allows_tool(spec.name):
                continue
            specs.append(spec.to_openai_tool_schema())
        return specs

    def _call_provider_tool(
        self,
        name: str,
        args: dict[str, Any],
        runtime_context: dict[str, Any] | None,
        execution_policy: ExecutionPolicy | None,
    ) -> dict[str, Any] | object:
        if not self._tool_provider:
            return _TOOL_UNHANDLED
        context = runtime_context or {}
        allowed = context.get("_provider_allowed_tools")
        if isinstance(allowed, set) and name not in allowed:
            return _TOOL_UNHANDLED
        if isinstance(allowed, list) and name not in allowed:
            return _TOOL_UNHANDLED
        provider_context = {
            key: value
            for key, value in context.items()
            if not key.startswith("_")
        }
        skill_name = context.get("_skill_name")
        if isinstance(skill_name, str) and skill_name:
            provider_context.setdefault("skill_name", skill_name)
        if execution_policy is not None:
            provider_context.update(execution_policy.to_context())
        try:
            result = self._tool_provider.call_tool(name, args, provider_context)
        except Exception as exc:
            return {"ok": False, "error": str(exc)}
        if isinstance(result, ToolResult):
            return result.to_dict()
        if isinstance(result, dict):
            if "ok" in result:
                return result
            return {"ok": True, "result": result}
        return {"ok": True, "result": result}

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


def _model_exception_interruption_reason(exc: Exception) -> str:
    text = str(exc)
    if "LLM_REQUEST_TIMEOUT" in text:
        return "llm_retry_exhausted"
    if "LLM_CONCURRENCY_LIMIT" in text:
        return "llm_concurrency_limit"
    if "LLM_CIRCUIT_OPEN" in text:
        return "llm_circuit_open"
    return "model_error"


def _tool_authorized(name: str, execution_policy: ExecutionPolicy | None) -> bool:
    if execution_policy is None:
        return True
    return execution_policy.allows_tool(name) or name in _RUNTIME_ALWAYS_ALLOWED_TOOL_NAMES


def _is_waiting_for_user_input_output(structured_output: Any) -> bool:
    if not isinstance(structured_output, dict):
        return False
    candidates = [
        structured_output.get("turn_status"),
        structured_output.get("turnStatus"),
        structured_output.get("next_step"),
        structured_output.get("nextStep"),
        structured_output.get("status"),
    ]
    normalized = {
        str(value).strip().upper()
        for value in candidates
        if value is not None
    }
    return bool(normalized & {
        "WAITING_FOR_USER_INPUT",
        "AWAITING_USER_INPUT",
        "WAITING_USER",
        "AWAITING_USER",
        "PENDING_INFO",
    })


def _is_non_recoverable_tool_error(result: Any) -> bool:
    if not isinstance(result, dict) or result.get("ok") is not False:
        return False
    return (
        result.get("llm_retry_allowed") is False
        or result.get("recoverable") is False
    )


def _has_runtime_memory_terminal_tool_call(tool_calls: list[dict[str, Any]]) -> bool:
    return any(_is_runtime_memory_terminal_tool_call(call) for call in tool_calls)


def _is_runtime_memory_terminal_tool_call(call: dict[str, Any]) -> bool:
    return call.get("name") in {"submit_skill_result", "shelve_interrupted_frame", "handoff_to_parent"}


def _assistant_response_text(response: Any) -> str:
    content = _safe_content(getattr(response, "content", ""))
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                text = item.get("text") or item.get("content")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(part.strip() for part in parts if part and part.strip()).strip()
    if content in (None, "", [], {}):
        return ""
    try:
        return json.dumps(content, ensure_ascii=False)
    except Exception:
        return str(content).strip()


def _mark_runtime_memory_finalizing(runtime_context: dict[str, Any] | None) -> None:
    _call_runtime_memory_marker(runtime_context, "_runtime_memory_mark_finalizing")


def _mark_runtime_memory_running(runtime_context: dict[str, Any] | None) -> None:
    _call_runtime_memory_marker(runtime_context, "_runtime_memory_mark_running")


def _call_runtime_memory_marker(runtime_context: dict[str, Any] | None, key: str) -> None:
    if not runtime_context:
        return
    marker = runtime_context.get(key)
    if not callable(marker):
        return
    try:
        marker()
    except Exception:
        logger.warning("Runtime memory marker failed: %s", key, exc_info=True)


def _runtime_memory_checkpoint_messages(runtime_context: dict[str, Any] | None) -> list[HumanMessage]:
    if not runtime_context:
        return []
    checkpoint = runtime_context.get("_runtime_memory_checkpoint")
    if not callable(checkpoint):
        return []
    try:
        queued_inputs = checkpoint()
    except Exception:
        logger.warning("Runtime memory checkpoint failed", exc_info=True)
        return []
    messages: list[HumanMessage] = []
    for item in queued_inputs:
        if not isinstance(item, dict):
            continue
        content = item.get("content")
        if isinstance(content, str) and content.strip():
            messages.append(HumanMessage(content=(
                "Additional user message received while this turn was running:\n"
                f"{content.strip()}"
            )))
    return messages


def _tool_not_authorized_error(name: str) -> str:
    return f"TOOL_NOT_AUTHORIZED: tool '{name}' is not allowed by upstream execution_policy"


# ---------------------------------------------------------------------------
# Tool schema registry
# ---------------------------------------------------------------------------


def _manifest_for_frame(runtime: SkillRuntime, frame: Any) -> SkillManifest | None:
    """Use the frame-frozen manifest so account registry reloads cannot affect execution."""
    snapshot = frame.private_working_state.get("_skill_manifest")
    if isinstance(snapshot, dict):
        try:
            return SkillManifest(**snapshot)
        except Exception:
            logger.warning("Invalid manifest snapshot in frame=%s", frame.frame_id, exc_info=True)
    return runtime.registry.get_manifest(frame.skill_id)
