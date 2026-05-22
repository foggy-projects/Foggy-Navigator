"""Tests for LLM tool schema wording that affects model behavior."""

from langgraph_biz_worker.runtime.llm_tool_schemas import _KNOWN_TOOL_SCHEMAS


def test_invoke_business_skill_schema_declares_current_frame_material_contract():
    schema = _KNOWN_TOOL_SCHEMAS["invoke_business_skill"]["function"]
    description = schema["description"]
    instruction_description = schema["parameters"]["properties"]["instruction"]["description"]
    input_description = schema["parameters"]["properties"]["input"]["description"]

    assert "current frame" in description
    assert "default tool for ordinary business skill requests" in description
    assert "does not create a child frame" in description
    assert "invoke_business_agent" in description
    assert "user explicitly asks for a sub-agent" in description
    assert "will not run in a child frame" in instruction_description
    assert "structured business inputs" in input_description


def test_invoke_business_agent_schema_declares_child_agent_frame_contract():
    schema = _KNOWN_TOOL_SCHEMAS["invoke_business_agent"]["function"]
    description = schema["description"]
    instruction_description = schema["parameters"]["properties"]["instruction"]["description"]
    input_description = schema["parameters"]["properties"]["input"]["description"]

    assert "Delegate work to a business Agent" in description
    assert "open a child Agent frame" in description
    assert "Do not use this for ordinary business skill routing" in description
    assert "skill/bundle name ends with '-agent'" in description
    assert "invoke_business_skill and continue there" in description
    assert "long-running wait" in description
    assert "Natural-language work order" in instruction_description
    assert "structured business inputs" in input_description


def test_read_frame_execution_report_schema_is_not_default_business_flow():
    schema = _KNOWN_TOOL_SCHEMAS["read_frame_execution_report"]["function"]
    description = schema["description"]
    mode_description = schema["parameters"]["properties"]["mode"]["description"]

    assert "user-requested explanations" in description
    assert "fallback recovery" in description
    assert "Do not call it by default" in description
    assert "markdown returns capped human-readable execution details" in mode_description


def test_analyze_spreadsheet_schema_keeps_one_tool_entry():
    schema = _KNOWN_TOOL_SCHEMAS["analyze_spreadsheet"]["function"]
    description = schema["description"]
    operation = schema["parameters"]["properties"]["operation"]

    assert "deterministic parser" in description
    assert "Do not use it when the user only asks to submit or attach" in description
    assert "Do not use analyze_attachment or a vision model" in description
    assert operation["enum"] == ["summary", "preview", "read_range", "extract_rows"]
