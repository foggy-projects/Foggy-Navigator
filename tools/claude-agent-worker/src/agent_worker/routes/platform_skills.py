"""Platform skills deployment endpoint — receives skill content from Navigator and writes to ~/.claude/skills/."""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Dict

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..auth import verify_token

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["platform-skills"], dependencies=[Depends(verify_token)])


class DeploySkillsRequest(BaseModel):
    skills: Dict[str, str]  # skill_name -> SKILL.md content


@router.post("/platform-skills/deploy")
async def deploy_skills(request: DeploySkillsRequest):
    """Receive skill content pushed from Navigator and write to ~/.claude/skills/<name>/SKILL.md."""
    deployed = []
    skills_dir = Path.home() / ".claude" / "skills"

    for name, content in request.skills.items():
        try:
            target_dir = skills_dir / name
            target_dir.mkdir(parents=True, exist_ok=True)
            (target_dir / "SKILL.md").write_text(content, encoding="utf-8")
            deployed.append(name)
            logger.info("Deployed platform skill via API: %s -> %s", name, target_dir)
        except Exception:
            logger.warning("Failed to deploy skill: %s", name, exc_info=True)

    return {"deployed": deployed}
