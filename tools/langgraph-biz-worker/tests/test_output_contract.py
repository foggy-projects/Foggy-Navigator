"""Tests for OutputContract validation."""

import pytest

from langgraph_biz_worker.models import SkillFrameState, SkillManifest
from langgraph_biz_worker.runtime.output_contract import validate_output_contract


@pytest.fixture
def manifest() -> SkillManifest:
    return SkillManifest(
        id="test",
        name="Test",
        output_schema={
            "type": "object",
            "required": ["classification", "confidence"],
            "properties": {
                "classification": {
                    "type": "string",
                    "enum": ["vehicle_delay", "weather", "other"],
                },
                "confidence": {
                    "type": "number",
                    "minimum": 0,
                    "maximum": 1,
                },
            },
        },
        business_rules={
            "require_evidence": True,
            "min_evidence_count": 1,
        },
    )


@pytest.fixture
def frame() -> SkillFrameState:
    return SkillFrameState(
        frame_id="frm_test",
        task_id="task_test",
        skill_id="test",
        evidence_refs=["order:123"],
    )


class TestSchemaValidation:
    def test_valid_output(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "vehicle_delay", "confidence": 0.85},
        )
        assert result.ok

    def test_missing_required_field(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "vehicle_delay"},
        )
        assert not result.ok
        assert any("confidence" in e for e in result.errors)

    def test_invalid_enum_value(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "unknown_type", "confidence": 0.5},
        )
        assert not result.ok
        assert any("must be one of" in e for e in result.errors)

    def test_number_out_of_range(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 1.5},
        )
        assert not result.ok
        assert any("<=" in e for e in result.errors)

    def test_number_below_minimum(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": -0.1},
        )
        assert not result.ok
        assert any(">=" in e for e in result.errors)


class TestBusinessRules:
    def test_missing_evidence(self, manifest):
        frame = SkillFrameState(
            frame_id="frm_test",
            task_id="task_test",
            skill_id="test",
            evidence_refs=[],  # No evidence
        )
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 0.9},
        )
        assert not result.ok
        assert any("evidence_refs" in e for e in result.errors)

    def test_sufficient_evidence(self, frame, manifest):
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 0.9},
        )
        assert result.ok


class TestStateConsistency:
    def test_pending_tool_calls_block_completion(self, frame, manifest):
        frame.tool_calls = [{"name": "some_tool", "status": "pending"}]
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 0.9},
        )
        assert not result.ok
        assert any("pending tool" in e for e in result.errors)

    def test_completed_tool_calls_ok(self, frame, manifest):
        frame.tool_calls = [{"name": "some_tool", "status": "done"}]
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 0.9},
        )
        assert result.ok

    def test_unresolved_approval_blocks(self, frame, manifest):
        frame.approval_request = {"type": "manual_dispatch", "resolved": False}
        result = validate_output_contract(
            frame, manifest,
            {"classification": "weather", "confidence": 0.9},
        )
        assert not result.ok
        assert any("approval" in e for e in result.errors)
