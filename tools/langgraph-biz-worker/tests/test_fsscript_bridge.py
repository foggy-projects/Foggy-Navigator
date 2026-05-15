from __future__ import annotations

import asyncio
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any

from langgraph_biz_worker.runtime.fsscript_bridge import (
    FsscriptRunBridge,
    extract_fsscript_script,
)


class _FakeCommand:
    def __init__(self, **kwargs: Any) -> None:
        self.__dict__.update(kwargs)


class _FakeManager:
    def __init__(self, *, on_suspended=None) -> None:
        self.on_suspended = on_suspended
        self.event = threading.Event()
        self.payload: dict[str, Any] | None = None

    def resume(self, command: _FakeCommand) -> dict[str, Any]:
        self.payload = dict(command.payload)
        self.event.set()
        return dict(command.payload)

    def reject(self, command: _FakeCommand) -> None:
        self.payload = {"approved": False, "reason": command.reason}
        self.event.set()
        raise RuntimeError(command.reason)


class _FakeRegistry:
    def register_object_facade(self, _descriptor: Any, *, target: Any) -> None:
        self.target = target


@dataclass
class _FakeDescriptor:
    def __init__(self, **kwargs: Any) -> None:
        self.__dict__.update(kwargs)


class _FakePolicy:
    def __init__(self, **kwargs: Any) -> None:
        self.__dict__.update(kwargs)


def _fake_runtime_loader():
    def run_script(_script, _ctx, *, suspension_manager, **_kwargs):
        suspension_manager.on_suspended(SimpleNamespace(
            script_run_id="sr_test",
            suspend_id="sp_test",
            reason="order.close_apply.submit",
            summary={
                "approval_type": "order_close_apply",
                "title": "提交关单申请",
                "post_approval_message": {
                    "approved": "审批已通过，FSScript 已继续执行。",
                },
            },
            timeout_at=datetime.now(timezone.utc),
        ))
        suspension_manager.event.wait(timeout=2)
        return SimpleNamespace(value=suspension_manager.payload)

    return SimpleNamespace(
        run_script=run_script,
        SuspensionManager=_FakeManager,
        ResumeCommand=_FakeCommand,
        RejectCommand=_FakeCommand,
        ComposeQueryContext=_FakeCommand,
        Principal=_FakeCommand,
        CapabilityRegistry=_FakeRegistry,
        CapabilityPolicy=_FakePolicy,
        MethodDescriptor=_FakeDescriptor,
        ObjectFacadeDescriptor=_FakeDescriptor,
        compose_pause=lambda **kwargs: kwargs,
    )


def test_extract_fsscript_script_supports_primary_context_shapes():
    assert extract_fsscript_script({"fsscript": "return 1;"}) == "return 1;"
    assert extract_fsscript_script({"fsscript": {"script": "return 2;"}}) == "return 2;"
    assert extract_fsscript_script({"language": "fsscript", "script": "return 3;"}) == "return 3;"
    assert extract_fsscript_script({"script": "return 4;"}) is None


def test_fsscript_bridge_streams_approval_then_result_after_resume():
    async def run() -> list[str]:
        bridge = FsscriptRunBridge(runtime_loader=_fake_runtime_loader)
        seen: list[str] = []
        resume_result: dict[str, Any] | None = None
        async for event in bridge.stream_events(
            task_id="task-1",
            session_id="session-1",
            script="return orderBiz.close_apply_submit({});",
            context={"namespace": "default"},
            user_id="user-1",
            tenant_id="tenant-1",
        ):
            seen.append(event.type)
            if event.type == "approval_required":
                assert event.approval_type == "order_close_apply"
                assert event.script_run_id == "sr_test"
                assert event.suspend_id == "sp_test"
                resume_result = bridge.resume_task("task-1", "approved", "ok")
                assert resume_result["summary"]["post_approval_message"]["approved"] == (
                    "审批已通过，FSScript 已继续执行。"
                )
            if event.type == "result":
                assert event.structured_output == {
                    "value": {
                        "approved": True,
                        "approval_result": "approved",
                        "comment": "ok",
                    }
                }
        return seen

    assert asyncio.run(run()) == ["system", "approval_required", "result"]
