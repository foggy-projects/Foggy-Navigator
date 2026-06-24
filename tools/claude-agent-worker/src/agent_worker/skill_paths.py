"""Shared paths for agent-managed skills."""

from __future__ import annotations

from pathlib import Path

AGENTS_DIRNAME = ".agents"


def user_agent_dir() -> Path:
    """Return the user-level agent metadata directory managed by Foggy Agent."""
    return Path.home() / AGENTS_DIRNAME


def project_agent_dir(project_dir: str | Path) -> Path:
    """Return the project-level agent metadata directory managed by Foggy Agent."""
    return Path(project_dir) / AGENTS_DIRNAME


def user_skills_dir() -> Path:
    """Return the user-level skill directory managed by Foggy Agent."""
    return user_agent_dir() / "skills"


def project_skills_dir(project_dir: str | Path) -> Path:
    """Return the project-level skill directory managed by Foggy Agent."""
    return project_agent_dir(project_dir) / "skills"
