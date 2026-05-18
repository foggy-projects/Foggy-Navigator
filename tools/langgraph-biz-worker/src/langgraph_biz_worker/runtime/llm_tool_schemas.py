"""Tool schema registry for the LLM skill agent."""

from __future__ import annotations

from typing import Any

from ..models import SkillManifest


def _tool_specs(manifest: SkillManifest, persistent_frame: bool = False) -> list[dict[str, Any]]:
    specs: list[dict[str, Any]] = []
    for name in [*_GLOBAL_TOOL_NAMES, *manifest.allowed_tools]:
        if name in _HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES:
            continue
        if name in _KNOWN_TOOL_SCHEMAS:
            specs.append(_KNOWN_TOOL_SCHEMAS[name])
    if "submit_skill_result" not in {s["function"]["name"] for s in specs}:
        specs.append(_KNOWN_TOOL_SCHEMAS["submit_skill_result"])
    if persistent_frame and manifest.id == "system.root":
        specs.append(_KNOWN_TOOL_SCHEMAS["resume_recoverable_child_skill"])
        specs.append(_KNOWN_TOOL_SCHEMAS["shelve_interrupted_frame"])
    return _dedupe_tool_specs(specs)


_GLOBAL_TOOL_NAMES = [
    "invoke_business_function",
    "list_skill_resources",
    "read_skill_resource",
    "read_frame_execution_report",
]

_HIDDEN_BUSINESS_DISCOVERY_TOOL_NAMES = {
    "list_business_functions",
    "get_business_function_schema",
}


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
            "description": "Invoke a child business skill and return its promoted result.",
            "parameters": {
                "type": "object",
                "properties": {
                    "skill_id": {"type": "string"},
                    "instruction": {"type": "string"},
                    "input": {"type": "object"},
                },
                "required": ["skill_id", "instruction"],
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
                "task_id/frame_id. Use this for debugging or reviewing prior "
                "frame work instead of re-reading raw frame JSON."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "report_ref": {
                        "type": "string",
                        "description": "Frame report reference, e.g. frame-report://task_id/frame_id.",
                    },
                    "task_id": {"type": "string"},
                    "frame_id": {"type": "string"},
                    "mode": {
                        "type": "string",
                        "enum": ["summary", "metadata", "markdown"],
                        "description": "summary is compact; markdown returns capped human-readable report text.",
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
            "description": "Submit final skill result to the runtime.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "structured_output": {
                        "type": "object",
                        "description": (
                            "Skill result payload. Persistent root may include "
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
            "description": "List files in the account's skill directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "relative_path": {"type": "string", "description": "Directory to list (e.g. skills/my-skill)"},
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
            "description": "Read a file from the account's skill directory.",
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
            "description": "Write a file to the account's skill directory. mode=create (default) or mode=overwrite.",
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
            "description": "Apply a unified diff patch to a file. Conflicts cause full rejection.",
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
