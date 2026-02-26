"""Claude Code skill discovery endpoint."""

from __future__ import annotations

import logging
import os
import re
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..config import settings
from ..models import SkillInfo

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["skills"], dependencies=[Depends(verify_token)])


def _validate_path(path: str) -> str:
    """Ensure *path* is inside one of the ``allowed_cwds``."""
    resolved = os.path.realpath(path)

    if not settings.allowed_cwds:
        return resolved

    for allowed in settings.allowed_cwds:
        allowed_resolved = os.path.realpath(allowed)
        # rstrip(os.sep) avoids double-sep when allowed is a drive root like "D:\"
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved.rstrip(os.sep) + os.sep):
            return resolved

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"Path '{path}' is not in the allowed list",
    )


_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---", re.DOTALL)


def _parse_skill(skill_dir: Path, scope: str) -> SkillInfo | None:
    """Parse a SKILL.md file and return a SkillInfo, or None on failure."""
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        return None

    name = skill_dir.name
    description = ""

    try:
        text = skill_md.read_text(encoding="utf-8", errors="replace")
        match = _FRONTMATTER_RE.match(text)
        if match:
            frontmatter = match.group(1)
            for line in frontmatter.splitlines():
                line = line.strip()
                if line.lower().startswith("name:"):
                    name = line.split(":", 1)[1].strip().strip("\"'")
                elif line.lower().startswith("description:"):
                    description = line.split(":", 1)[1].strip().strip("\"'")
        else:
            # No frontmatter — use first non-empty line as description
            for line in text.splitlines():
                stripped = line.strip().lstrip("#").strip()
                if stripped:
                    description = stripped
                    break
    except Exception as e:
        logger.debug("Failed to parse %s: %s", skill_md, e)

    return SkillInfo(name=name, description=description, scope=scope)


def _scan_skills_dir(base: Path, scope: str) -> list[SkillInfo]:
    """Scan ``<base>/*/SKILL.md`` and return a list of skills."""
    if not base.is_dir():
        return []
    results: list[SkillInfo] = []
    for child in sorted(base.iterdir()):
        if child.is_dir():
            info = _parse_skill(child, scope)
            if info:
                results.append(info)
    return results


@router.get("/skills", response_model=list[SkillInfo])
async def list_skills(
    cwd: str = Query(..., description="Project working directory"),
) -> list[SkillInfo]:
    """Return Claude Code skills for the given project directory."""
    resolved = _validate_path(cwd)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {cwd}",
        )

    project_dir = Path(resolved) / ".claude" / "skills"
    user_dir = Path.home() / ".claude" / "skills"

    project_skills = _scan_skills_dir(project_dir, "project")
    user_skills = _scan_skills_dir(user_dir, "user")

    return project_skills + user_skills
