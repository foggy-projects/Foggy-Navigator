"""Tests for the BizWorker root graph bootstrap."""

from __future__ import annotations

import time
from typing import Any

from langgraph_biz_worker.graphs import root_graph as root_graph_module
from langgraph_biz_worker.runtime.file_frame_journal import FileFrameJournal
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime


def _state(task_id: str) -> dict[str, Any]:
    return {
        "task_id": task_id,
        "session_id": "sess_root",
        "prompt": "handle the request",
        "model": "test-model",
        "model_config_id": None,
        "llm_config": None,
        "context": {"client_app_id": "capp_001"},
        "runtime_context": {},
        "attachments": None,
        "user_id": "user_001",
        "tenant_id": "tenant_001",
        "events": [],
        "started_at": time.time(),
        "active_frame_id": None,
        "skill_results": [],
    }


def _install_isolated_runtime(monkeypatch, tmp_path) -> SkillRuntime:
    registry = SkillRegistry(skills_root=tmp_path / "skills", data_root=tmp_path / "data")
    journal = FileFrameJournal(tmp_path / "data")
    runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=registry,
        journal=journal,
    )
    monkeypatch.setattr(root_graph_module, "_skill_registry", registry)
    monkeypatch.setattr(root_graph_module, "_journal", journal)
    monkeypatch.setattr(root_graph_module, "_runtime", runtime)
    monkeypatch.setattr(root_graph_module.settings, "llm_execute_skills", True)
    monkeypatch.setattr(root_graph_module, "_chat_model_for_state", lambda state: object())
    return runtime


def test_route_skill_creates_and_reuses_persistent_system_root_frame(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_reuse_001")

    first = root_graph_module.route_skill(state)
    second = root_graph_module.route_skill(state)

    assert first["active_frame_id"] == second["active_frame_id"]
    assert first["events"][-1].content == "Opening frame for skill: system.root"
    assert second["events"][-1].content == "Reusing frame for skill: system.root"

    root_frames = [
        frame for frame in runtime.get_frames_by_task("task_root_reuse_001")
        if frame.skill_id == root_graph_module.ROOT_SKILL_ID
    ]
    assert len(root_frames) == 1
    assert root_frames[0].status.value == "RUNNING"


def test_route_skill_restores_persistent_system_root_frame_from_journal(monkeypatch, tmp_path):
    runtime = _install_isolated_runtime(monkeypatch, tmp_path)
    state = _state("task_root_restore_001")
    first = root_graph_module.route_skill(state)

    restored_runtime = SkillRuntime(
        frame_store=FrameStore(),
        skill_registry=runtime.registry,
        journal=root_graph_module._journal,
    )
    monkeypatch.setattr(root_graph_module, "_runtime", restored_runtime)

    second = root_graph_module.route_skill(state)

    assert second["active_frame_id"] == first["active_frame_id"]
    assert second["events"][-1].content == "Reusing frame for skill: system.root"
    assert restored_runtime.get_frame(first["active_frame_id"]) is not None
