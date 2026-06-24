"""Shared paths for agent-managed skills."""

from __future__ import annotations

from pathlib import Path


def user_skills_dir() -> Path:
    """Return the user-level skill directory managed by Foggy Agent."""
    return Path.home() / ".agent" / "skills"


def project_skills_dir(project_dir: str | Path) -> Path:
    """Return the project-level skill directory managed by Foggy Agent."""
    return Path(project_dir) / ".agent" / "skills"
