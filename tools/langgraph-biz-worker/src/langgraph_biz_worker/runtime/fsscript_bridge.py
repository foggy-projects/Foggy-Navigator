"""FSScript execution bridge with in-process suspension resume support."""

from __future__ import annotations

import asyncio
import logging
import queue
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from threading import Lock, Thread
from typing import Any, AsyncGenerator, Callable

from ..config import settings
from ..models import QueryEvent
from ..tools.order_biz_facade import OrderBizFacade

logger = logging.getLogger(__name__)


class FsscriptBridgeUnavailable(RuntimeError):
    """Raised when the configured FSScript runtime cannot be imported."""


class FsscriptRunNotFound(RuntimeError):
    """Raised when no active FSScript run is bound to a task."""


class _NoopSemanticService:
    def execute_sql(self, *_args: Any, **_kwargs: Any) -> Any:
        raise RuntimeError(
            "FSScript SQL execution is not configured in LangGraph Biz Worker"
        )


class _AllowAllAuthorityResolver:
    def resolve(self, _request: Any) -> Any:
        return True


@dataclass
class _RuntimeImports:
    run_script: Callable[..., Any]
    SuspensionManager: type
    ResumeCommand: type
    RejectCommand: type
    ComposeQueryContext: type
    Principal: type
    CapabilityRegistry: type
    CapabilityPolicy: type
    MethodDescriptor: type
    ObjectFacadeDescriptor: type
    compose_pause: Callable[..., dict[str, Any]]


@dataclass
class _RunHandle:
    task_id: str
    manager: Any
    events: "queue.Queue[QueryEvent | None]"
    started_at: float
    script_run_id: str | None = None
    suspend_id: str | None = None


_runtime_imports: _RuntimeImports | None = None
_runtime_lock = Lock()


def _prepare_import_path() -> None:
    configured = settings.fsscript_python_path.strip()
    if not configured:
        return
    root = Path(configured)
    candidate = root / "src" if (root / "src").exists() else root
    text = str(candidate)
    if text not in sys.path:
        sys.path.insert(0, text)


def _load_runtime() -> _RuntimeImports:
    global _runtime_imports
    with _runtime_lock:
        if _runtime_imports is not None:
            return _runtime_imports
        _prepare_import_path()
        try:
            from foggy.dataset_model.engine.compose.capability.descriptors import (
                MethodDescriptor,
                ObjectFacadeDescriptor,
            )
            from foggy.dataset_model.engine.compose.capability.policy import CapabilityPolicy
            from foggy.dataset_model.engine.compose.capability.registry import CapabilityRegistry
            from foggy.dataset_model.engine.compose.context.compose_query_context import (
                ComposeQueryContext,
            )
            from foggy.dataset_model.engine.compose.context.principal import Principal
            from foggy.dataset_model.engine.compose.runtime.pause_primitive import compose_pause
            from foggy.dataset_model.engine.compose.runtime.script_runtime import run_script
            from foggy.dataset_model.engine.compose.runtime.suspension import (
                RejectCommand,
                ResumeCommand,
            )
            from foggy.dataset_model.engine.compose.runtime.suspension_manager import (
                SuspensionManager,
            )
        except Exception as exc:  # pragma: no cover - import environment specific
            raise FsscriptBridgeUnavailable(
                "FSScript runtime is not available. Set BIZ_WORKER_FSSCRIPT_PYTHON_PATH "
                "to foggy-data-mcp-bridge-python."
            ) from exc

        _runtime_imports = _RuntimeImports(
            run_script=run_script,
            SuspensionManager=SuspensionManager,
            ResumeCommand=ResumeCommand,
            RejectCommand=RejectCommand,
            ComposeQueryContext=ComposeQueryContext,
            Principal=Principal,
            CapabilityRegistry=CapabilityRegistry,
            CapabilityPolicy=CapabilityPolicy,
            MethodDescriptor=MethodDescriptor,
            ObjectFacadeDescriptor=ObjectFacadeDescriptor,
            compose_pause=compose_pause,
        )
        return _runtime_imports


class FsscriptRunBridge:
    """Runs FSScript in a worker thread and exposes pause as SSE approval."""

    def __init__(
        self,
        runtime_loader: Callable[[], _RuntimeImports] = _load_runtime,
    ) -> None:
        self._runtime_loader = runtime_loader
        self._runs: dict[str, _RunHandle] = {}
        self._lock = Lock()

    async def stream_events(
        self,
        *,
        task_id: str,
        session_id: str | None,
        script: str,
        context: dict[str, Any] | None,
        user_id: str | None,
        tenant_id: str | None,
    ) -> AsyncGenerator[QueryEvent, None]:
        runtime = self._runtime_loader()
        events: "queue.Queue[QueryEvent | None]" = queue.Queue()
        handle = _RunHandle(
            task_id=task_id,
            manager=None,
            events=events,
            started_at=time.time(),
        )

        def on_suspended(result: Any) -> None:
            summary = dict(getattr(result, "summary", {}) or {})
            handle.script_run_id = getattr(result, "script_run_id")
            handle.suspend_id = getattr(result, "suspend_id")
            payload = {
                "script_run_id": handle.script_run_id,
                "suspend_id": handle.suspend_id,
                "reason": getattr(result, "reason", ""),
                "summary": summary,
                "timeout_at": getattr(result, "timeout_at").isoformat(),
            }
            events.put(
                QueryEvent(
                    type="skill_approval_request",
                    task_id=task_id,
                    session_id=session_id,
                    content=(
                        summary.get("title")
                        or summary.get("message")
                        or getattr(result, "reason", "")
                    ),
                    approval_type=(
                        summary.get("approval_type")
                        or getattr(result, "reason", "")
                    ),
                    payload=payload,
                    script_run_id=handle.script_run_id,
                    suspend_id=handle.suspend_id,
                    reason=getattr(result, "reason", ""),
                    summary=summary,
                    timeout_at=payload["timeout_at"],
                )
            )

        manager = runtime.SuspensionManager(on_suspended=on_suspended)
        handle.manager = manager
        with self._lock:
            self._runs[task_id] = handle

        thread = Thread(
            target=self._run_script_thread,
            args=(
                runtime,
                handle,
                script,
                context or {},
                user_id,
                tenant_id,
                session_id,
            ),
            daemon=True,
            name=f"fsscript-{task_id[:12]}",
        )
        thread.start()

        yield QueryEvent(
            type="system",
            task_id=task_id,
            session_id=session_id,
            content="FSScript started",
        )
        try:
            while True:
                event = await asyncio.to_thread(events.get)
                if event is None:
                    break
                yield event
        finally:
            with self._lock:
                current = self._runs.get(task_id)
                if current is handle and handle.script_run_id is None:
                    self._runs.pop(task_id, None)

    def resume_task(self, task_id: str, approval_result: str, comment: str = "") -> dict[str, Any]:
        with self._lock:
            handle = self._runs.get(task_id)
        if handle is None or not handle.script_run_id or not handle.suspend_id:
            raise FsscriptRunNotFound(
                f"No active FSScript suspension found for task {task_id}"
            )

        runtime = self._runtime_loader()
        normalized = (approval_result or "").lower()
        approved = normalized in {"approved", "approve", "true", "yes", "ok"}
        if approved:
            payload = {
                "approved": True,
                "approval_result": approval_result,
                "comment": comment,
            }
            handle.manager.resume(
                runtime.ResumeCommand(
                    script_run_id=handle.script_run_id,
                    suspend_id=handle.suspend_id,
                    payload=payload,
                )
            )
        else:
            try:
                handle.manager.reject(
                    runtime.RejectCommand(
                        script_run_id=handle.script_run_id,
                        suspend_id=handle.suspend_id,
                        reason=comment or approval_result or "rejected",
                    )
                )
            except Exception:
                # The FSScript manager intentionally raises after waking the
                # blocked script thread; the route response should still be 200.
                logger.info("FSScript task %s rejected by approval result", task_id)

        return {
            "task_id": task_id,
            "script_run_id": handle.script_run_id,
            "suspend_id": handle.suspend_id,
            "status": "RUNNING",
        }

    def _run_script_thread(
        self,
        runtime: _RuntimeImports,
        handle: _RunHandle,
        script: str,
        context: dict[str, Any],
        user_id: str | None,
        tenant_id: str | None,
        session_id: str | None,
    ) -> None:
        try:
            registry, policy = self._build_order_biz_capability(runtime)
            ctx = runtime.ComposeQueryContext(
                principal=runtime.Principal(
                    user_id=user_id or str(context.get("user_id") or "anonymous"),
                    tenant_id=tenant_id or context.get("tenant_id"),
                ),
                namespace=str(context.get("namespace") or tenant_id or "default"),
                authority_resolver=_AllowAllAuthorityResolver(),
                params=context.get("params") if isinstance(context.get("params"), dict) else None,
            )
            result = runtime.run_script(
                script,
                ctx,
                semantic_service=_NoopSemanticService(),
                capability_registry=registry,
                capability_policy=policy,
                suspension_manager=handle.manager,
            )
            duration_ms = int((time.time() - handle.started_at) * 1000)
            handle.events.put(
                QueryEvent(
                    type="result",
                    task_id=handle.task_id,
                    session_id=session_id,
                    content="FSScript completed",
                    structured_output={"value": result.value},
                    duration_ms=duration_ms,
                )
            )
        except Exception as exc:
            logger.exception("FSScript task %s failed", handle.task_id)
            handle.events.put(
                QueryEvent(
                    type="error",
                    task_id=handle.task_id,
                    session_id=session_id,
                    error=str(exc),
                )
            )
        finally:
            with self._lock:
                self._runs.pop(handle.task_id, None)
            handle.events.put(None)

    def _build_order_biz_capability(self, runtime: _RuntimeImports) -> tuple[Any, Any]:
        registry = runtime.CapabilityRegistry()
        descriptor = runtime.ObjectFacadeDescriptor(
            object_name="orderBiz",
            methods=[
                runtime.MethodDescriptor(
                    name="get_order",
                    args_schema=[
                        {"name": "query", "type": "dict", "required": False}
                    ],
                    return_type="dict",
                    side_effect="none",
                    auth_scope="biz.order.read",
                    timeout_ms=10_000,
                    audit_tag="order.get",
                ),
                runtime.MethodDescriptor(
                    name="close_apply_draft",
                    args_schema=[
                        {"name": "application", "type": "dict", "required": True}
                    ],
                    return_type="dict",
                    side_effect="none",
                    auth_scope="biz.order.close_apply",
                    timeout_ms=10_000,
                    audit_tag="order.close_apply.draft",
                ),
                runtime.MethodDescriptor(
                    name="close_apply_submit",
                    args_schema=[
                        {"name": "application", "type": "dict", "required": True}
                    ],
                    return_type="dict",
                    side_effect="none",
                    auth_scope="biz.order.close_apply",
                    timeout_ms=300_000,
                    audit_tag="order.close_apply.submit",
                ),
            ],
        )
        registry.register_object_facade(
            descriptor,
            target=OrderBizFacade(compose_pause=runtime.compose_pause),
        )
        policy = runtime.CapabilityPolicy(
            allowed_objects={"orderBiz": frozenset({
                "get_order",
                "close_apply_draft",
                "close_apply_submit",
            })},
            allowed_scopes=frozenset({"biz.order.read", "biz.order.close_apply"}),
        )
        return registry, policy


_bridge = FsscriptRunBridge()


def get_fsscript_bridge() -> FsscriptRunBridge:
    return _bridge


def extract_fsscript_script(context: dict[str, Any] | None) -> str | None:
    if not context:
        return None
    fsscript = context.get("fsscript")
    if isinstance(fsscript, str):
        return fsscript
    if isinstance(fsscript, dict) and isinstance(fsscript.get("script"), str):
        return fsscript["script"]
    if context.get("language") == "fsscript" and isinstance(context.get("script"), str):
        return str(context["script"])
    return None
