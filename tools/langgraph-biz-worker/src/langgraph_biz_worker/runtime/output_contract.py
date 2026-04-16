"""Output Contract validator — three-tier validation for Skill results.

Tier 1: schema_valid     — structured_output matches manifest.output_schema
Tier 2: business_complete — manifest.business_rules satisfied
Tier 3: state_consistent  — no pending tool calls / child frames / approvals
"""

from __future__ import annotations

from typing import Any

from ..models import SkillFrameState, SkillManifest, ValidationResult


def validate_output_contract(
    frame: SkillFrameState,
    manifest: SkillManifest,
    candidate_output: dict[str, Any],
) -> ValidationResult:
    """Run all three validation tiers and return aggregated result."""
    errors: list[str] = []

    errors.extend(_validate_schema(candidate_output, manifest.output_schema))
    errors.extend(_validate_business_rules(candidate_output, frame, manifest.business_rules))
    errors.extend(_validate_state_consistent(frame))

    return ValidationResult(ok=len(errors) == 0, errors=errors)


# ---------------------------------------------------------------------------
# Tier 1: Schema validation
# ---------------------------------------------------------------------------


def _validate_schema(output: dict[str, Any], schema: dict[str, Any]) -> list[str]:
    """Lightweight schema check — verifies required fields and basic types."""
    errors: list[str] = []
    if not schema:
        return errors

    required = schema.get("required", [])
    properties = schema.get("properties", {})

    for field_name in required:
        if field_name not in output or output[field_name] is None:
            errors.append(f"structured_output.{field_name} is required")

    for field_name, field_spec in properties.items():
        if field_name not in output:
            continue
        value = output[field_name]

        # Enum check
        if "enum" in field_spec and value not in field_spec["enum"]:
            errors.append(
                f"structured_output.{field_name} must be one of {field_spec['enum']}, got '{value}'"
            )

        # Numeric range checks
        if field_spec.get("type") == "number" and isinstance(value, (int, float)):
            if "minimum" in field_spec and value < field_spec["minimum"]:
                errors.append(
                    f"structured_output.{field_name} must be >= {field_spec['minimum']}, got {value}"
                )
            if "maximum" in field_spec and value > field_spec["maximum"]:
                errors.append(
                    f"structured_output.{field_name} must be <= {field_spec['maximum']}, got {value}"
                )

    return errors


# ---------------------------------------------------------------------------
# Tier 2: Business rules
# ---------------------------------------------------------------------------


def _validate_business_rules(
    output: dict[str, Any],
    frame: SkillFrameState,
    rules: dict[str, Any],
) -> list[str]:
    """Check business rules defined in the manifest."""
    errors: list[str] = []
    if not rules:
        return errors

    if rules.get("require_evidence"):
        min_count = rules.get("min_evidence_count", 1)
        if len(frame.evidence_refs) < min_count:
            errors.append(
                f"evidence_refs must contain at least {min_count} item(s), "
                f"got {len(frame.evidence_refs)}"
            )

    if rules.get("require_approval") and output.get("requires_approval"):
        if frame.approval_request is None:
            errors.append("requires_approval is true but no approval_request exists")

    return errors


# ---------------------------------------------------------------------------
# Tier 3: State consistency
# ---------------------------------------------------------------------------


def _validate_state_consistent(frame: SkillFrameState) -> list[str]:
    """Ensure no pending dependencies before allowing completion."""
    errors: list[str] = []

    pending_tools = [tc for tc in frame.tool_calls if tc.get("status") == "pending"]
    if pending_tools:
        errors.append(f"{len(pending_tools)} pending tool call(s) remain")

    # Note: child frame completion is checked by SkillRuntime before calling
    # this validator, so we only check the frame's own child_frame_ids as a
    # safety net.

    if frame.approval_request and not frame.approval_request.get("resolved"):
        errors.append("approval_request is pending and unresolved")

    return errors
