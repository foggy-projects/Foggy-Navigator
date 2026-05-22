"""Tool schema registry for the LLM skill agent."""

from __future__ import annotations

from typing import Any

from langchain_core.language_models import BaseChatModel

from ..models import SkillManifest


def _tool_specs(
    manifest: SkillManifest,
    persistent_frame: bool = False,
    extra_tool_specs: list[dict[str, Any]] | None = None,
    enabled_tool_names: set[str] | frozenset[str] | None = None,
) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    for name in [*_GLOBAL_TOOL_NAMES, *manifest.allowed_tools]:
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            continue
        if not _tool_enabled(name, enabled_tool_names):
            continue
        if name in _KNOWN_TOOL_SCHEMAS:
            specs.append(_KNOWN_TOOL_SCHEMAS[name])
    specs.extend(
        spec
        for spec in (extra_tool_specs or [])
        if _tool_enabled(spec["function"]["name"], enabled_tool_names)
    )
    if "submit_skill_result" not in {s["function"]["name"] for s in specs}:
        specs.append(_KNOWN_TOOL_SCHEMAS["submit_skill_result"])
    if persistent_frame:
        if _tool_enabled("resume_recoverable_child_skill", enabled_tool_names):
            specs.append(_KNOWN_TOOL_SCHEMAS["resume_recoverable_child_skill"])
        if _tool_enabled("shelve_interrupted_frame", enabled_tool_names):
            specs.append(_KNOWN_TOOL_SCHEMAS["shelve_interrupted_frame"])
    elif _tool_enabled("handoff_to_parent", enabled_tool_names):
        specs.append(_KNOWN_TOOL_SCHEMAS["handoff_to_parent"])
    return _dedupe_tool_specs(specs)


def _bind_tools(
    model: BaseChatModel,
    manifest: SkillManifest,
    persistent_frame: bool = False,
    extra_tool_specs: list[dict[str, Any]] | None = None,
    enabled_tool_names: set[str] | frozenset[str] | None = None,
) -> BaseChatModel:
    if not hasattr(model, "bind_tools"):
        return model
    return model.bind_tools(_tool_specs(
        manifest,
        persistent_frame=persistent_frame,
        extra_tool_specs=extra_tool_specs,
        enabled_tool_names=enabled_tool_names,
    ))


_GLOBAL_TOOL_NAMES = [
    "invoke_business_function",
    "list_skill_resources",
    "read_skill_resource",
    "read_frame_execution_report",
]

_DEFAULT_FILE_TOOL_NAMES = (
    "list_files",
    "read_file",
    "write_file",
    "patch_file",
)

_HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES = {
    "list_business_functions",
    "get_business_function_schema",
}

_RUNTIME_ALWAYS_ALLOWED_TOOL_NAMES = frozenset({
    "submit_skill_result",
    "handoff_to_parent",
    "resume_recoverable_child_skill",
    "shelve_interrupted_frame",
})
_SKILL_DISCOVERY_TOOL_NAMES = frozenset({
    "list_skill_resources",
    "read_skill_resource",
})
_SKILL_MATERIAL_TOOL_NAMES = frozenset({
    "invoke_business_skill",
    "invoke_business_agent",
})


def _tool_enabled(
    name: str,
    enabled_tool_names: set[str] | frozenset[str] | None,
) -> bool:
    if enabled_tool_names is None:
        return True
    if name == "invoke_business_agent" and "invoke_business_skill" in enabled_tool_names:
        return True
    if name in _SKILL_DISCOVERY_TOOL_NAMES and enabled_tool_names & _SKILL_MATERIAL_TOOL_NAMES:
        return True
    return name in enabled_tool_names or name in _RUNTIME_ALWAYS_ALLOWED_TOOL_NAMES


def _dedupe_tool_specs(specs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[str] = set()
    for spec in specs:
        name = spec["function"]["name"]
        if name in seen:
            continue
        seen.add(name)
        deduped.append(spec)
    return deduped


_KNOWN_TOOL_SCHEMAS: dict[str, dict[str, Any]] = {
    "mock_get_order": {
        "type": "function",
        "function": {
            "name": "mock_get_order",
            "description": "Fetch mock order details.",
            "parameters": {
                "type": "object",
                "properties": {"order_id": {"type": "string"}},
                "required": ["order_id"],
            },
        },
    },
    "mock_get_vehicle_status": {
        "type": "function",
        "function": {
            "name": "mock_get_vehicle_status",
            "description": "Fetch mock vehicle status.",
            "parameters": {
                "type": "object",
                "properties": {"vehicle_id": {"type": "string"}},
                "required": ["vehicle_id"],
            },
        },
    },
    "mock_search_incidents": {
        "type": "function",
        "function": {
            "name": "mock_search_incidents",
            "description": "Search mock incident records.",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        },
    },
    "list_business_functions": {
        "type": "function",
        "function": {
            "name": "list_business_functions",
            "description": "List business functions available to this task's app/user/skill scope.",
            "parameters": {
                "type": "object",
                "properties": {
                    "domain": {"type": "string"},
                    "risk_level": {"type": "string"},
                },
                "required": [],
            },
        },
    },
    "get_business_function_schema": {
        "type": "function",
        "function": {
            "name": "get_business_function_schema",
            "description": "Get the input/output schema for a business function.",
            "parameters": {
                "type": "object",
                "properties": {
                    "function_id": {"type": "string"},
                    "version": {"type": "string"},
                },
                "required": ["function_id", "version"],
            },
        },
    },
    "invoke_business_function": {
        "type": "function",
        "function": {
            "name": "invoke_business_function",
            "description": "Invoke an allowlisted business function through Navigator Java.",
            "parameters": {
                "type": "object",
                "properties": {
                    "function_id": {"type": "string"},
                    "version": {"type": "string"},
                    "input": {"type": "object"},
                    "idempotency_key": {"type": "string"},
                },
                "required": ["function_id", "version", "input"],
            },
        },
    },
    "invoke_business_skill": {
        "type": "function",
        "function": {
            "name": "invoke_business_skill",
            "description": (
                "Load a business Skill's instructions and resources into the "
                "current frame. This is the default tool for ordinary business "
                "skill requests. It does not create a child frame. After the "
                "tool returns, continue reasoning in the same frame and call "
                "business functions or other tools directly if needed. Use "
                "invoke_business_agent only when the user explicitly asks for a "
                "sub-agent/independent agent, or when the work truly needs an "
                "isolated lifecycle, separate report, long-running wait, or "
                "multi-agent delegation."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_name": {
                        "type": "string",
                        "description": "The skill folder name to invoke.",
                    },
                    "skill_id": {
                        "type": "string",
                        "description": "Legacy alias for skill_name.",
                    },
                    "instruction": {
                        "type": "string",
                        "description": (
                            "Optional reason for loading this skill. The skill "
                            "will not run in a child frame."
                        ),
                    },
                    "input": {
                        "type": "object",
                        "description": (
                            "Optional structured business inputs for the child "
                            "skill, such as document numbers, type hints, "
                            "attachments, or pre-extracted fields."
                        ),
                    },
                },
                "required": ["skill_name", "instruction"],
            },
        },
    },
    "invoke_business_agent": {
        "type": "function",
        "function": {
            "name": "invoke_business_agent",
            "description": (
                "Delegate work to a business Agent and open a child Agent frame. "
                "Do not use this for ordinary business skill routing or merely "
                "because a skill/bundle name ends with '-agent'. For ordinary "
                "business requests, load the Skill in the current frame with "
                "invoke_business_skill and continue there. Use this only when "
                "the user explicitly asks for a sub-agent/independent agent, or "
                "the work truly needs an isolated loop, separate report, "
                "long-running wait, handoff, or multi-agent delegation. Inside "
                "the Agent frame, the Agent may load Skill materials and call "
                "business functions."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "agent_id": {
                        "type": "string",
                        "description": "Agent id or agent-like bundle id to invoke.",
                    },
                    "agent_name": {
                        "type": "string",
                        "description": "Legacy alias for agent_id.",
                    },
                    "skill_name": {
                        "type": "string",
                        "description": "Compatibility alias when an existing skill bundle acts as an Agent.",
                    },
                    "instruction": {
                        "type": "string",
                        "description": (
                            "Natural-language work order for the child Agent. "
                            "Include the user's business goal, known fields, and constraints."
                        ),
                    },
                    "input": {
                        "type": "object",
                        "description": "Optional structured business inputs for the child Agent.",
                    },
                },
                "required": ["instruction"],
            },
        },
    },
    "analyze_attachment": {
        "type": "function",
        "function": {
            "name": "analyze_attachment",
            "description": (
                "Analyze a user-provided attachment on demand using the configured "
                "vision model. Use this only when the user asks to inspect image/file "
                "contents or when attachment-derived fields are required before a "
                "business function call. Do not use it when the user only asks to "
                "submit or attach the original file."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "attachment_id": {
                        "type": "string",
                        "description": "Attachment id from the upstream attachment metadata.",
                    },
                    "purpose": {
                        "type": "string",
                        "description": "Business reason for analysis, e.g. extract exception type or read damaged cargo text.",
                    },
                    "expected_fields": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Optional field names to extract from the attachment.",
                    },
                },
                "required": ["attachment_id", "purpose"],
            },
        },
    },
    "analyze_spreadsheet": {
        "type": "function",
        "function": {
            "name": "analyze_spreadsheet",
            "description": (
                "Read a user-provided spreadsheet attachment on demand using a deterministic parser. "
                "Use this for Excel/CSV content requests such as sheet summary, preview, exact range "
                "reading, or row extraction. Do not use it when the user only asks to submit or attach "
                "the original file. Do not use analyze_attachment or a vision model for spreadsheet files."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "attachment_id": {
                        "type": "string",
                        "description": "Attachment id from the upstream attachment metadata.",
                    },
                    "operation": {
                        "type": "string",
                        "enum": ["summary", "preview", "read_range", "extract_rows"],
                        "description": "summary lists sheets; preview returns first rows; read_range reads A1:F30 style ranges; extract_rows maps rows by header.",
                    },
                    "sheet": {
                        "type": "string",
                        "description": "Sheet name. Omit to use the first sheet.",
                    },
                    "range": {
                        "type": "string",
                        "description": "A1-style range, required for read_range, e.g. A1:F30.",
                    },
                    "offset": {
                        "type": "integer",
                        "description": "Zero-based row page offset for extract_rows.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum rows to return. The tool enforces a server-side cap.",
                    },
                    "header_row": {
                        "type": "integer",
                        "description": "Header row number for extract_rows. Defaults to 1.",
                    },
                    "options": {
                        "type": "object",
                        "description": "Optional parser flags, e.g. include_formulas=false.",
                    },
                },
                "required": ["attachment_id", "operation"],
            },
        },
    },
    "list_skill_resources": {
        "type": "function",
        "function": {
            "name": "list_skill_resources",
            "description": "List public skill bundle resources for the current ClientApp.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string", "description": "Skill id under the current ClientApp public directory. Omit to list skills."},
                    "relative_path": {"type": "string", "description": "Directory under the skill, usually references or assets."},
                    "recursive": {"type": "boolean"},
                    "max_entries": {"type": "integer"},
                },
                "required": [],
            },
        },
    },
    "read_skill_resource": {
        "type": "function",
        "function": {
            "name": "read_skill_resource",
            "description": "Read SKILL.md, references/**, or assets/** from a public skill bundle for the current ClientApp.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string"},
                    "relative_path": {"type": "string", "description": "SKILL.md, references/<file>, or assets/<file>."},
                    "start_line": {"type": "integer"},
                    "max_lines": {"type": "integer"},
                },
                "required": ["skill_id", "relative_path"],
            },
        },
    },
    "read_frame_execution_report": {
        "type": "function",
        "function": {
            "name": "read_frame_execution_report",
                "description": (
                    "Read a persisted Frame execution report by report_ref or by "
                    "task_id/frame_id. Use this for user-requested explanations, "
                    "debugging, audit, or fallback recovery when the promoted child "
                    "result/continuation summary is missing a field required for the "
                    "next business decision. Do not call it by default after "
                    "invoke_business_agent or resume_recoverable_child_skill "
                    "completes normally."
                ),
            "parameters": {
                "type": "object",
                "properties": {
                    "report_ref": {
                        "type": "string",
                        "description": (
                            "Frame report reference, e.g. "
                            "frame-report://task_id/frame_id. Prefer this when "
                            "the user asks to inspect execution details."
                        ),
                    },
                    "task_id": {"type": "string"},
                    "frame_id": {"type": "string"},
                    "mode": {
                        "type": "string",
                        "enum": ["summary", "metadata", "markdown"],
                        "description": (
                            "summary is compact; metadata returns report identity "
                            "and digest metadata; markdown returns capped "
                            "human-readable execution details."
                        ),
                    },
                    "max_chars": {"type": "integer"},
                },
                "required": [],
            },
        },
    },
    "submit_skill_result": {
        "type": "function",
        "function": {
            "name": "submit_skill_result",
            "description": (
                "Submit a frame result to the runtime. For ordinary child Agent "
                "completion, return the final business result. If this child "
                "needs more user input, set structured_output.turn_status or "
                "next_step to WAITING_FOR_USER_INPUT and include a user-facing "
                "summary/message; the runtime will pause this same frame instead "
                "of closing it. Persistent root should not use this tool for "
                "ordinary greetings, simple Q&A, or natural-language answers; "
                "use it only when preserving structured state such as active_plan, "
                "artifact_refs, evidence_refs, or structured_output."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "structured_output": {
                        "type": "object",
                        "description": (
                            "Frame result payload. Persistent root may include "
                            "active_plan for compact multi-turn working state "
                            "and intent_resolution for interruption handling."
                        ),
                    },
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "structured_output"],
            },
        },
    },
    "handoff_to_parent": {
        "type": "function",
        "function": {
            "name": "handoff_to_parent",
            "description": (
                "Child-frame only tool. Exit the current child Agent without "
                "continuing the old task when the user clearly cancels, stops, "
                "switches topic, or asks to return to the parent conversation. "
                "Use requires_parent_synthesis=true when the parent/root agent "
                "must decide or handle a new unrelated request; otherwise the "
                "handoff summary can be returned directly to the user."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {
                        "type": "string",
                        "description": "User-facing concise summary of why this child frame is exiting.",
                    },
                    "reason": {
                        "type": "string",
                        "enum": [
                            "USER_CANCELLED",
                            "USER_STOPPED",
                            "CHANGE_TOPIC",
                            "RETURN_TO_PARENT",
                            "UNSUPPORTED_BY_CHILD",
                            "OTHER",
                        ],
                    },
                    "intent_resolution": {
                        "type": "string",
                        "enum": [
                            "ABANDON_CURRENT_FRAME",
                            "START_UNRELATED_NEW_TASK",
                            "RETURN_TO_PARENT",
                            "ASK_PARENT_TO_DECIDE",
                        ],
                    },
                    "parent_instruction": {
                        "type": "string",
                        "description": (
                            "Optional instruction for the parent/root agent when "
                            "requires_parent_synthesis is true."
                        ),
                    },
                    "requires_parent_synthesis": {
                        "type": "boolean",
                        "description": (
                            "Set true when the parent/root agent must synthesize "
                            "or handle a new task; set false for a simple cancel/return acknowledgement."
                        ),
                    },
                    "structured_output": {
                        "type": "object",
                        "description": "Optional child-visible business state to promote with the handoff.",
                    },
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "reason", "intent_resolution"],
            },
        },
    },
    "shelve_interrupted_frame": {
        "type": "function",
        "function": {
            "name": "shelve_interrupted_frame",
            "description": (
                "Root-only tool. Shelve the previous recoverable interruption "
                "when the user stops it or asks for an unrelated new task."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "decision": {
                        "type": "string",
                        "enum": ["ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"],
                    },
                    "intent_resolution": {
                        "type": "string",
                        "enum": ["ABANDON_PREVIOUS", "START_UNRELATED_NEW_TASK"],
                        "description": (
                            "Normalized intent after comparing the user's new "
                            "instruction with the interrupted focus."
                        ),
                    },
                    "abandoned_interruption": {
                        "type": "object",
                        "description": "Compact summary of the interrupted work being shelved.",
                    },
                    "new_task": {
                        "type": "object",
                        "description": "Optional compact description of the unrelated new task.",
                    },
                    "artifact_refs": {"type": "array", "items": {"type": "string"}},
                    "evidence_refs": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["summary", "decision", "abandoned_interruption"],
            },
        },
    },
    "resume_recoverable_child_skill": {
        "type": "function",
        "function": {
            "name": "resume_recoverable_child_skill",
            "description": (
                "Root-only tool. Continue the pending recoverable child skill "
                "frame after an interrupted child-skill execution."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "instruction": {
                        "type": "string",
                        "description": "Natural language instruction for continuing the child skill.",
                    },
                },
                "required": ["instruction"],
            },
        },
    },
    # --- Artifact tools ---
    "create_artifact": {
        "type": "function",
        "function": {
            "name": "create_artifact",
            "description": "Externalize long content into an artifact. Returns artifact_id for future reference.",
            "parameters": {
                "type": "object",
                "properties": {
                    "name": {"type": "string", "description": "Short name for the artifact"},
                    "content": {"type": "string", "description": "Text content to externalize"},
                    "mime_type": {"type": "string", "description": "MIME type (default text/plain)"},
                    "encoding": {"type": "string", "description": "Encoding (default utf-8)"},
                    "scope": {"type": "string", "enum": ["task", "account"], "description": "Scope: task or account"},
                    "summary": {"type": "string", "description": "Short summary of the content"},
                },
                "required": ["name", "content"],
            },
        },
    },
    "read_artifact": {
        "type": "function",
        "function": {
            "name": "read_artifact",
            "description": "Read a previously created artifact. Default returns summary only; use mode=content for full text.",
            "parameters": {
                "type": "object",
                "properties": {
                    "artifact_id": {"type": "string"},
                    "mode": {"type": "string", "enum": ["summary", "metadata", "content"]},
                },
                "required": ["artifact_id"],
            },
        },
    },
    # --- Account file tools ---
    "list_files": {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": (
                "List files under the current account/workspace file scope. "
                "When no explicit execution policy is supplied, this is limited "
                "to the account skill directory."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {
                        "type": "string",
                        "description": "Directory to list, relative to the current file scope.",
                    },
                    "recursive": {"type": "boolean"},
                    "max_entries": {"type": "integer"},
                },
                "required": ["relative_path"],
            },
        },
    },
    "read_file": {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": (
                "Read a text file from the current account/workspace file scope "
                "with line and byte limits."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "start_line": {"type": "integer"},
                    "max_lines": {"type": "integer"},
                },
                "required": ["relative_path"],
            },
        },
    },
    "write_file": {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": (
                "Create or overwrite a text file in the current account/workspace "
                "file scope. Use mode=create by default; use mode=overwrite with "
                "expected_sha256 when replacing known existing content."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "content": {"type": "string"},
                    "encoding": {"type": "string"},
                    "mode": {"type": "string", "enum": ["create", "overwrite"]},
                    "expected_sha256": {"type": "string"},
                },
                "required": ["relative_path", "content"],
            },
        },
    },
    "str_replace": {
        "type": "function",
        "function": {
            "name": "str_replace",
            "description": "Replace a unique text fragment in a file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "old_str": {"type": "string"},
                    "new_str": {"type": "string"},
                },
                "required": ["relative_path", "old_str", "new_str"],
            },
        },
    },
    "edit_file": {
        "type": "function",
        "function": {
            "name": "edit_file",
            "description": "Section-level edit: replace content under a heading anchor.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "operation": {"type": "string", "enum": ["replace_section"]},
                    "anchor": {"type": "string", "description": "Markdown heading to anchor on"},
                    "content": {"type": "string"},
                },
                "required": ["relative_path", "operation", "anchor", "content"],
            },
        },
    },
    "patch_file": {
        "type": "function",
        "function": {
            "name": "patch_file",
            "description": (
                "Apply a single-file unified diff patch in the current "
                "account/workspace file scope. Conflicts cause full rejection."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string"},
                    "patch": {"type": "string", "description": "Unified diff content"},
                    "expected_sha256": {"type": "string"},
                },
                "required": ["relative_path", "patch"],
            },
        },
    },
}
