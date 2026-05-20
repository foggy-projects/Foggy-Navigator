"""Tests for LLM tool schema wording that affects model behavior."""

from langgraph_biz_worker.runtime.llm_tool_schemas import _KNOWN_TOOL_SCHEMAS


def test_invoke_business_skill_schema_declares_promoted_result_contract():
    schema = _KNOWN_TOOL_SCHEMAS["invoke_business_skill"]["function"]
    description = schema["description"]
    instruction_description = schema["parameters"]["properties"]["instruction"]["description"]
    input_description = schema["parameters"]["properties"]["input"]["description"]

    assert "promoted result" in description
    assert "primary business-decision context" in description
    assert "Do not call read_frame_execution_report after normal completion" in description
    assert "complete promoted result" in instruction_description
    assert "structured business inputs" in input_description


def test_read_frame_execution_report_schema_is_not_default_business_flow():
    schema = _KNOWN_TOOL_SCHEMAS["read_frame_execution_report"]["function"]
    description = schema["description"]
    mode_description = schema["parameters"]["properties"]["mode"]["description"]

    assert "user-requested explanations" in description
    assert "fallback recovery" in description
    assert "Do not call it by default" in description
    assert "markdown returns capped human-readable execution details" in mode_description
