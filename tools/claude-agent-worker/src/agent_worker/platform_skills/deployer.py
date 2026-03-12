"""Deploy platform skills to ~/.claude/skills/ on Worker startup.

Templates may contain ``{{NAVIGATOR_API_BASE}}`` which is replaced with the
actual Navigator backend URL at deploy time.
"""

from __future__ import annotations

import logging
from pathlib import Path

from ..config import settings
from ..marketplace.config import DEFAULT_MARKETPLACE_URL

logger = logging.getLogger(__name__)

_SKILLS_DIR = Path(__file__).parent

# Mapping: target directory name -> source template file in this package
_SKILL_TEMPLATES = {
    "cross-project-task": "cross_project_task.md",
    "company-skill-marketplace": "company_skill_marketplace.md",
    "navigator-admin": "navigator_admin.md",
    "scheduled-task": "scheduled_task.md",
}

_TEMPLATE_VARS = {
    "{{NAVIGATOR_API_BASE}}": lambda: settings.navigator_api_base,
    "{{MARKETPLACE_URL}}": lambda: getattr(settings, "marketplace_url", DEFAULT_MARKETPLACE_URL),
}


def deploy_platform_skills() -> None:
    """Read bundled skill templates, substitute placeholders, and write to ~/.claude/skills/<name>/SKILL.md."""
    claude_skills_dir = Path.home() / ".claude" / "skills"

    for skill_name, template_file in _SKILL_TEMPLATES.items():
        try:
            source = _SKILLS_DIR / template_file
            if not source.exists():
                logger.warning("Skill template not found: %s", source)
                continue

            content = source.read_text(encoding="utf-8")
            for placeholder, value_fn in _TEMPLATE_VARS.items():
                content = content.replace(placeholder, value_fn())

            target_dir = claude_skills_dir / skill_name
            target_dir.mkdir(parents=True, exist_ok=True)

            target = target_dir / "SKILL.md"
            target.write_text(content, encoding="utf-8")
            logger.info("Deployed platform skill: %s -> %s (apiBase=%s)", skill_name, target, settings.navigator_api_base)
        except Exception:
            logger.warning("Failed to deploy platform skill: %s", skill_name, exc_info=True)
