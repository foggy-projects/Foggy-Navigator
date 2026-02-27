"""Deploy platform skills to ~/.claude/skills/ on Worker startup."""

from __future__ import annotations

import logging
import shutil
from pathlib import Path

logger = logging.getLogger(__name__)

_SKILLS_DIR = Path(__file__).parent

# Mapping: target directory name -> source template file in this package
_SKILL_TEMPLATES = {
    "cross-project-task": "cross_project_task.md",
}


def deploy_platform_skills() -> None:
    """Copy bundled skill templates to ~/.claude/skills/<name>/SKILL.md."""
    claude_skills_dir = Path.home() / ".claude" / "skills"

    for skill_name, template_file in _SKILL_TEMPLATES.items():
        try:
            source = _SKILLS_DIR / template_file
            if not source.exists():
                logger.warning("Skill template not found: %s", source)
                continue

            target_dir = claude_skills_dir / skill_name
            target_dir.mkdir(parents=True, exist_ok=True)

            target = target_dir / "SKILL.md"
            shutil.copy2(source, target)
            logger.info("Deployed platform skill: %s -> %s", skill_name, target)
        except Exception:
            logger.warning("Failed to deploy platform skill: %s", skill_name, exc_info=True)
