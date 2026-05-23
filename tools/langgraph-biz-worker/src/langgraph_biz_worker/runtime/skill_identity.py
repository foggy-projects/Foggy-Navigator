"""Skill identity normalization for external BizWorker callers."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

SKILL_NAME_ALIASES = ("skill_name", "skillName", "skill_id", "skillId", "businessSkillName", "businessSkillId")
_SAFE_SKILL_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]+$")


class SkillNameValidationError(ValueError):
    """Raised when a skill name or its aliases are invalid."""


def validate_skill_name(value: Any, field_name: str = "skill_name") -> str:
    """Return a safe single-segment skill name."""
    if not isinstance(value, str):
        raise SkillNameValidationError(f"{field_name} must be a string")
    if not value or value.strip() != value:
        raise SkillNameValidationError(f"{field_name} must be non-empty and trimmed")
    if value in {".", ".."} or ".." in value:
        raise SkillNameValidationError(f"{field_name} must not contain path traversal")
    if "/" in value or "\\" in value:
        raise SkillNameValidationError(f"{field_name} must be a single path segment")
    if ":" in value:
        raise SkillNameValidationError(f"{field_name} must not contain drive or scheme separators")
    if not _SAFE_SKILL_NAME_RE.match(value):
        raise SkillNameValidationError(
            f"{field_name} must contain only letters, numbers, dot, underscore, or hyphen"
        )
    return value


def normalize_skill_name(source: Mapping[str, Any], *, required: bool = False) -> str | None:
    """Normalize skill identity aliases into one external ``skill_name`` value.

    Multiple aliases are accepted only when every non-empty value is identical.
    """
    values: dict[str, str] = {}
    for alias in SKILL_NAME_ALIASES:
        if alias not in source:
            continue
        value = source[alias]
        normalized = validate_skill_name(value, alias)
        values[alias] = normalized

    if not values:
        if required:
            raise SkillNameValidationError("skill_name is required")
        return None

    unique = set(values.values())
    if len(unique) > 1:
        fields = ", ".join(sorted(values))
        raise SkillNameValidationError(f"Conflicting skill name aliases: {fields}")
    return next(iter(unique))
