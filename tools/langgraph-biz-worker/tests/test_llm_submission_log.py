"""Tests for exact LLM submission snapshot logging."""

from __future__ import annotations

import json

from langchain_core.messages import HumanMessage, SystemMessage

from langgraph_biz_worker.config import settings
from langgraph_biz_worker.runtime.file_layout import session_data_dir
from langgraph_biz_worker.runtime.llm_submission_log import record_llm_submission

CTX = "bctx_20260521_ab_llmlog1"


class FakeModel:
    model_name = "unit-test-model"
    temperature = 0.2


def _context(tmp_path, *, max_files: int = 100) -> dict:
    return {
        "_llm_submission_data_root": str(tmp_path),
        "_llm_submission_session_id": CTX,
        "_llm_submission_require_standard_context": True,
        "_llm_submission_date_parts": ("2026", "05", "21"),
        "_llm_submission_skill_id": "system.root",
        "_llm_submission_iteration": 1,
        "_llm_submission_tools": [{
            "type": "function",
            "function": {
                "name": "submit_frame_result",
                "parameters": {"type": "object"},
            },
        }],
        "_runtime_context_revision": 4,
        "_runtime_budget": {
            "preset_key": "generic.128k",
            "max_input_tokens": 100000,
            "token_estimator": "heuristic-v1",
        },
        "llm_submission_log_max_files": max_files,
    }


def test_record_llm_submission_writes_numbered_json(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(settings, "llm_submission_log_max_files", 100)

    path = record_llm_submission(
        FakeModel(),
        [SystemMessage(content="system rules"), HumanMessage(content="hello")],
        _context(tmp_path),
        operation="skill_agent.invoke",
        task_id="lgt_001",
        frame_id="frm_001",
        attempt=1,
    )

    assert path is not None
    assert path.name.startswith("000001_system.root_lgt_001_frm_001_iter01_attempt01")
    log_dir = session_data_dir(tmp_path, ("2026", "05", "21"), CTX) / "logs" / "llm-submissions"
    assert path.parent == log_dir
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["meta"]["contextId"] == CTX
    assert payload["meta"]["taskId"] == "lgt_001"
    assert payload["meta"]["runtimeRevision"] == 4
    assert payload["meta"]["runtimeWarnings"] == []
    assert payload["meta"]["runtimeBudget"]["preset_key"] == "generic.128k"
    assert payload["meta"]["runtimeBudget"]["token_estimator"] == "heuristic-v1"
    assert payload["body"]["model"]["model_name"] == "unit-test-model"
    assert "hello" in json.dumps(payload["body"]["messages"], ensure_ascii=False)
    assert payload["body"]["messages"][0]["type"] == "system"
    assert payload["body"]["messages"][1]["type"] == "human"
    assert payload["body"]["tools"][0]["function"]["name"] == "submit_frame_result"


def test_record_llm_submission_includes_runtime_warnings(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "llm_submission_log_enabled", True)

    context = _context(tmp_path)
    context["_runtime_context_warnings"] = [{
        "code": "PROMPT_BUDGET_PRE_COMPACTION",
        "severity": "info",
        "message": "compacted",
    }]
    path = record_llm_submission(
        FakeModel(),
        [HumanMessage(content="hello")],
        context,
        operation="skill_agent.invoke",
        task_id="lgt_001",
        frame_id="frm_001",
        attempt=1,
    )

    assert path is not None
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["meta"]["runtimeWarnings"][0]["code"] == "PROMPT_BUDGET_PRE_COMPACTION"


def test_record_llm_submission_prunes_old_files(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "llm_submission_log_enabled", True)
    monkeypatch.setattr(settings, "llm_submission_log_max_files", 3)

    for index in range(5):
        context = _context(tmp_path)
        context["_llm_submission_iteration"] = index + 1
        record_llm_submission(
            FakeModel(),
            [HumanMessage(content=f"message {index}")],
            context,
            operation="skill_agent.invoke",
            task_id="lgt_001",
            frame_id="frm_001",
            attempt=1,
        )

    log_dir = session_data_dir(tmp_path, ("2026", "05", "21"), CTX) / "logs" / "llm-submissions"
    files = sorted(file_path.name for file_path in log_dir.glob("*.json"))
    assert len(files) == 3
    assert files[0].startswith("000003_")
    assert files[-1].startswith("000005_")


def test_record_llm_submission_disabled(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "llm_submission_log_enabled", False)

    path = record_llm_submission(
        FakeModel(),
        [HumanMessage(content="hello")],
        _context(tmp_path),
        operation="skill_agent.invoke",
        task_id="lgt_001",
        frame_id="frm_001",
        attempt=1,
    )

    assert path is None


def test_record_llm_submission_requires_standard_context_id(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "llm_submission_log_enabled", True)
    context = _context(tmp_path)
    context["_llm_submission_session_id"] = "legacy-session"

    path = record_llm_submission(
        FakeModel(),
        [HumanMessage(content="hello")],
        context,
        operation="skill_agent.invoke",
        task_id="lgt_001",
        frame_id="frm_001",
        attempt=1,
    )

    assert path is None
    assert not (tmp_path / "runtime" / "sessions" / "by-date").exists()
