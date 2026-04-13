"""Unit tests for individual Skill subgraph functions and mock tools (P2)."""

import pytest

from langgraph_biz_worker.models import SkillManifest
from langgraph_biz_worker.runtime.frame_store import FrameStore
from langgraph_biz_worker.runtime.skill_registry import SkillRegistry
from langgraph_biz_worker.runtime.skill_runtime import SkillRuntime
from langgraph_biz_worker.tools.mock_biz_tools import (
    mock_get_order,
    mock_get_vehicle_status,
    mock_search_incidents,
)
from langgraph_biz_worker.graphs.skills.exception_triage import analyze, TriageState


# ---------------------------------------------------------------------------
# Mock business tools
# ---------------------------------------------------------------------------


class TestMockBizTools:
    def test_mock_get_order_structure(self):
        result = mock_get_order("ORD-001")
        assert result["order_id"] == "ORD-001"
        assert "status" in result
        assert "delay_minutes" in result
        assert "vehicle_id" in result

    def test_mock_get_vehicle_status_structure(self):
        result = mock_get_vehicle_status("V01")
        assert result["vehicle_id"] == "V01"
        assert "status" in result
        assert "error_code" in result

    def test_mock_search_incidents_returns_list(self):
        result = mock_search_incidents("order:123")
        assert isinstance(result, list)
        assert len(result) >= 1
        assert "incident_id" in result[0]


# ---------------------------------------------------------------------------
# Analyze function (simulated LLM reasoning)
# ---------------------------------------------------------------------------


def _make_runtime() -> SkillRuntime:
    registry = SkillRegistry()
    registry.register(SkillManifest(id="exception_triage", name="Triage"))
    return SkillRuntime(frame_store=FrameStore(), skill_registry=registry)


class TestAnalyzeFunction:
    def test_vehicle_breakdown_classified_as_vehicle_delay(self):
        state: TriageState = {
            "task_id": "t1", "frame_id": "f1",
            "skill_input": {}, "events": [],
            "runtime": _make_runtime(),
            "child_results": {},
            "evidence": {
                "vehicle": {"status": "breakdown"},
                "order": {"delay_minutes": 30},
            },
            "analysis": {},
        }
        result = analyze(state)
        assert result["analysis"]["classification"] == "vehicle_delay"
        assert result["analysis"]["recommended_action"] == "manual_dispatch"
        assert result["analysis"]["confidence"] > 0.9

    def test_long_delay_classified_as_system_error(self):
        state: TriageState = {
            "task_id": "t1", "frame_id": "f1",
            "skill_input": {}, "events": [],
            "runtime": _make_runtime(),
            "child_results": {},
            "evidence": {
                "vehicle": {"status": "running"},
                "order": {"delay_minutes": 90},
            },
            "analysis": {},
        }
        result = analyze(state)
        assert result["analysis"]["classification"] == "system_error"
        assert result["analysis"]["recommended_action"] == "escalate"

    def test_normal_classified_as_other(self):
        state: TriageState = {
            "task_id": "t1", "frame_id": "f1",
            "skill_input": {}, "events": [],
            "runtime": _make_runtime(),
            "child_results": {},
            "evidence": {
                "vehicle": {"status": "running"},
                "order": {"delay_minutes": 10},
            },
            "analysis": {},
        }
        result = analyze(state)
        assert result["analysis"]["classification"] == "other"
        assert result["analysis"]["recommended_action"] == "auto_retry"

    def test_empty_evidence_fallback(self):
        state: TriageState = {
            "task_id": "t1", "frame_id": "f1",
            "skill_input": {}, "events": [],
            "runtime": _make_runtime(),
            "child_results": {},
            "evidence": {},
            "analysis": {},
        }
        result = analyze(state)
        # With empty evidence, should fall to "other"
        assert result["analysis"]["classification"] == "other"


# ---------------------------------------------------------------------------
# Rule check violation detection
# ---------------------------------------------------------------------------


class TestRuleCheckLogic:
    def test_vehicle_breakdown_auto_retry_violation(self):
        """rule_check should flag auto_retry for vehicle breakdown."""
        from langgraph_biz_worker.graphs.skills.rule_check import check_and_submit, RuleCheckState

        runtime = _make_runtime()
        runtime.registry.register(SkillManifest(
            id="rule_check", name="Rule Check",
            output_schema={
                "type": "object",
                "required": ["rule_passed", "checked_rules"],
                "properties": {
                    "rule_passed": {"type": "boolean"},
                    "checked_rules": {"type": "array"},
                },
            },
        ))
        frame_id = runtime.invoke_skill("t1", "rule_check")

        state: RuleCheckState = {
            "task_id": "t1",
            "frame_id": frame_id,
            "skill_input": {
                "order_id": "123",
                "classification": "vehicle_delay",
                "recommended_action": "auto_retry",
            },
            "events": [],
            "runtime": runtime,
        }
        check_and_submit(state)

        frame = runtime.get_frame(frame_id)
        assert frame.output["rule_passed"] is False
        assert len(frame.output.get("violations", [])) > 0

    def test_valid_action_passes_rules(self):
        """manual_dispatch for vehicle breakdown should pass rules."""
        from langgraph_biz_worker.graphs.skills.rule_check import check_and_submit, RuleCheckState

        runtime = _make_runtime()
        runtime.registry.register(SkillManifest(
            id="rule_check", name="Rule Check",
            output_schema={
                "type": "object",
                "required": ["rule_passed", "checked_rules"],
                "properties": {
                    "rule_passed": {"type": "boolean"},
                    "checked_rules": {"type": "array"},
                },
            },
        ))
        frame_id = runtime.invoke_skill("t1", "rule_check")

        state: RuleCheckState = {
            "task_id": "t1",
            "frame_id": frame_id,
            "skill_input": {
                "order_id": "123",
                "classification": "vehicle_delay",
                "recommended_action": "manual_dispatch",
            },
            "events": [],
            "runtime": runtime,
        }
        check_and_submit(state)

        frame = runtime.get_frame(frame_id)
        assert frame.output["rule_passed"] is True
