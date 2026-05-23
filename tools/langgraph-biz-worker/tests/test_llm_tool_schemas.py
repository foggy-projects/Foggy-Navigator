"""Tests for LLM tool schema wording that affects model behavior."""

from langgraph_biz_worker.models import SkillManifest
from langgraph_biz_worker.runtime.llm_tool_schemas import (
    _DEFAULT_FILE_TOOL_NAMES,
    _KNOWN_TOOL_SCHEMAS,
    _tool_specs,
)


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


def test_command_schema_declares_linux_workspace_contract():
    schema = _KNOWN_TOOL_SCHEMAS["command"]["function"]
    description = schema["description"]
    properties = schema["parameters"]["properties"]

    assert "non-interactive Linux command" in description
    assert "authorized workspace" in description
    assert "git" in description
    assert "curl" in description
    assert "tests" in description
    assert "build commands" in description
    assert schema["parameters"]["required"] == ["command"]
    assert "hard cap" in properties["timeout_seconds"]["description"]
    assert "hard cap" in properties["max_output_chars"]["description"]


def test_analyze_spreadsheet_schema_keeps_one_tool_entry():
    schema = _KNOWN_TOOL_SCHEMAS["analyze_spreadsheet"]["function"]
    description = schema["description"]
    operation = schema["parameters"]["properties"]["operation"]

    assert "deterministic parser" in description
    assert "Do not use it when the user only asks to submit or attach" in description
    assert "Do not use analyze_attachment or a vision model" in description
    assert operation["enum"] == ["summary", "preview", "read_range", "extract_rows"]


def test_default_file_tool_set_is_minimal():
    assert _DEFAULT_FILE_TOOL_NAMES == (
        "list_files",
        "read_file",
        "write_file",
        "patch_file",
    )
    assert "str_replace" not in _DEFAULT_FILE_TOOL_NAMES
    assert "edit_file" not in _DEFAULT_FILE_TOOL_NAMES


def test_tool_specs_enable_skill_discovery_when_business_skill_allowed():
    manifest = SkillManifest(
        id="delegated-agent",
        name="Delegated Agent",
        allowed_tools=["invoke_business_skill", "invoke_business_agent"],
    )

    names = {spec["function"]["name"] for spec in _tool_specs(
        manifest,
        enabled_tool_names=frozenset({"invoke_business_skill"}),
    )}

    assert "invoke_business_skill" in names
    assert "invoke_business_agent" in names
    assert "list_skill_resources" in names
    assert "read_skill_resource" in names
    assert "invoke_business_function" not in names


def test_tool_specs_enable_skill_discovery_when_business_agent_allowed():
    manifest = SkillManifest(
        id="delegated-agent",
        name="Delegated Agent",
        allowed_tools=["invoke_business_agent"],
    )

    names = {spec["function"]["name"] for spec in _tool_specs(
        manifest,
        enabled_tool_names=frozenset({"invoke_business_agent"}),
    )}

    assert "invoke_business_agent" in names
    assert "list_skill_resources" in names
    assert "read_skill_resource" in names
    assert "invoke_business_skill" not in names


def test_tool_specs_do_not_enable_skill_discovery_for_unrelated_tools():
    manifest = SkillManifest(
        id="function-agent",
        name="Function Agent",
        allowed_tools=["invoke_business_function"],
    )

    names = {spec["function"]["name"] for spec in _tool_specs(
        manifest,
        enabled_tool_names=frozenset({"invoke_business_function"}),
    )}

    assert "invoke_business_function" in names
    assert "list_skill_resources" not in names
    assert "read_skill_resource" not in names
