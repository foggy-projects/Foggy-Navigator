"""Platform skills deployment endpoint — receives skill content from Navigator and writes to ~/.agents/skills/."""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from typing import Dict

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from ..auth import verify_token
from ..platform_skills import deployer as platform_skill_deployer
from ..skill_paths import user_skills_dir

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["platform-skills"], dependencies=[Depends(verify_token)])


class DeploySkillsRequest(BaseModel):
    skills: Dict[str, str]  # skill_name -> SKILL.md content


def _write_skill_file(skills_dir: Path, name: str, content: str) -> bool:
    """Write one platform skill to disk."""
    if name == "ask-agent":
        target = platform_skill_deployer.deploy_skill(skills_dir, name, content)
        if target is None:
            return False
        platform_skill_deployer.remove_legacy_ask_agent_copies()
        return True

    target_dir = skills_dir / name
    target_dir.mkdir(parents=True, exist_ok=True)
    (target_dir / "SKILL.md").write_text(content, encoding="utf-8")
    return True


@router.post("/platform-skills/deploy")
async def deploy_skills(request: DeploySkillsRequest):
    """Receive skill content pushed from Navigator and write to ~/.agents/skills/<name>/SKILL.md."""
    deployed = []
    skills_dir = user_skills_dir()

    for name, content in request.skills.items():
        try:
            written = await asyncio.to_thread(_write_skill_file, skills_dir, name, content)
            if not written:
                logger.warning("Skipped unmanaged platform skill via API: %s", name)
                continue
            deployed.append(name)
            logger.info("Deployed platform skill via API: %s -> %s", name, skills_dir / name)
        except Exception:
            logger.warning("Failed to deploy skill: %s", name, exc_info=True)

    return {"deployed": deployed}
