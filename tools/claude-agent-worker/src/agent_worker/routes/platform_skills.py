"""Platform skill reconciliation and backward-compatible deployment endpoint."""

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
    """Write one non-retired, remotely supplied skill to disk."""
    return platform_skill_deployer.deploy_skill(skills_dir, name, content) is not None


@router.post("/platform-skills/deploy")
async def deploy_skills(request: DeploySkillsRequest):
    """Reconcile local platform skills, then accept compatible extra skills.

    Older control planes may still send retired skill names.  Reconciliation
    runs first and the deployer rejects those names, so they cannot be revived.
    An empty ``skills`` map is the current control-plane reconcile request.
    """
    deployed = []
    skills_dir = user_skills_dir()
    skill_roots = platform_skill_deployer.default_skill_roots(skills_dir)

    await asyncio.to_thread(
        platform_skill_deployer.reconcile_platform_skills,
        skills_dir,
        skill_roots,
    )

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
