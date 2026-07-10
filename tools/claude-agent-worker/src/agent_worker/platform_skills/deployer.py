"""Deploy platform skills to ~/.agents/skills/ on Worker startup.

Templates may contain ``{{NAVIGATOR_API_BASE}}`` which is replaced with the
actual Navigator backend URL at deploy time.
"""

from __future__ import annotations

import logging
import stat
from pathlib import Path
from typing import Callable, Iterable

from ..config import settings
from ..marketplace.config import DEFAULT_MARKETPLACE_URL
from ..skill_paths import user_skills_dir

logger = logging.getLogger(__name__)

_SKILLS_DIR = Path(__file__).parent

# Mapping: target directory name -> source template file in this package
_SKILL_TEMPLATES = {
    "ask-agent": "ask_agent.md",
    "company-skill-marketplace": "company_skill_marketplace.md",
    "navigator-admin": "navigator_admin.md",
    "scheduled-task": "scheduled_task.md",
}

_RETIRED_SKILL_NAME = "cross-project-task"
_RETIRED_SKILL_SIGNATURES = (
    "name: cross-project-task",
    "/api/v1/cross-project-tasks",
)

_ASK_AGENT_MANAGED_SIGNATURES = (
    (
        "name: ask-agent",
        "# 咨询协作 Agent",
        "NAVIGATOR_TOKEN",
        "/api/v1/agents/",
    ),
    (
        "name: ask-agent",
        "# 定时任务 A2A 调用",
        "[NAVIGATOR_SCHEDULED_A2A]",
        "/api/v1/agents/",
    ),
)

_TEMPLATE_VARS = {
    "{{NAVIGATOR_API_BASE}}": lambda: settings.navigator_api_base,
    "{{MARKETPLACE_URL}}": lambda: getattr(settings, "marketplace_url", DEFAULT_MARKETPLACE_URL),
}


def deploy_platform_skills() -> None:
    """Read bundled skill templates, substitute placeholders, and write to ~/.agents/skills/<name>/SKILL.md."""
    remove_retired_platform_skills()
    skills_dir = user_skills_dir()
    for skill_name, template_file in _SKILL_TEMPLATES.items():
        try:
            source = _SKILLS_DIR / template_file
            if not source.exists():
                logger.warning("Skill template not found: %s", source)
                continue

            content = source.read_text(encoding="utf-8")
            for placeholder, value_fn in _TEMPLATE_VARS.items():
                content = content.replace(placeholder, value_fn())

            target = deploy_skill(skills_dir, skill_name, content)
            if target is None:
                continue
            logger.info("Deployed platform skill: %s -> %s (apiBase=%s)", skill_name, target, settings.navigator_api_base)
        except Exception:
            logger.warning("Failed to deploy platform skill: %s", skill_name, exc_info=True)

    remove_legacy_ask_agent_copies()


def deploy_skill(skills_dir: Path, skill_name: str, content: str) -> Path | None:
    target_dir = skills_dir / skill_name
    target = target_dir / "SKILL.md"

    if skill_name == "ask-agent" and not _can_manage_ask_agent(target_dir, target):
        return None

    target_dir.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def _can_manage_ask_agent(target_dir: Path, target: Path) -> bool:
    if _is_link_or_reparse_point(target_dir) or _is_link_or_reparse_point(target):
        logger.warning("Skipped linked ask-agent target: %s", target)
        return False
    if not target.exists():
        return True
    if not target.is_file():
        logger.warning("Skipped non-file ask-agent target: %s", target)
        return False

    content = target.read_text(encoding="utf-8")
    if _is_managed_ask_agent(content):
        return True

    logger.warning("Skipped unrecognized ask-agent file: %s", target)
    return False


def remove_legacy_ask_agent_copies(skill_dirs: Iterable[Path] | None = None) -> None:
    """Remove recognized Navigator ask-agent copies from obsolete skill roots."""
    if skill_dirs is None:
        home = Path.home()
        skill_dirs = (
            home / ".agent" / "skills" / "ask-agent",
            home / ".claude" / "skills" / "ask-agent",
        )

    for skill_dir in skill_dirs:
        try:
            _remove_managed_skill_file(skill_dir, _is_managed_ask_agent, "legacy ask-agent")
        except Exception:
            logger.warning("Failed to remove legacy ask-agent at %s", skill_dir, exc_info=True)


def remove_retired_platform_skills(skill_dirs: Iterable[Path] | None = None) -> None:
    """Remove retired Navigator-managed Skill files without deleting user content."""
    if skill_dirs is None:
        home = Path.home()
        skill_dirs = (
            home / ".agents" / "skills" / _RETIRED_SKILL_NAME,
            home / ".agent" / "skills" / _RETIRED_SKILL_NAME,
            home / ".claude" / "skills" / _RETIRED_SKILL_NAME,
        )

    for skill_dir in skill_dirs:
        try:
            _remove_retired_skill_file(skill_dir)
        except Exception:
            logger.warning("Failed to retire platform skill at %s", skill_dir, exc_info=True)


def _remove_retired_skill_file(skill_dir: Path) -> None:
    _remove_managed_skill_file(
        skill_dir,
        lambda content: all(signature in content for signature in _RETIRED_SKILL_SIGNATURES),
        "retired platform skill",
    )


def _remove_managed_skill_file(
    skill_dir: Path,
    is_managed: Callable[[str], bool],
    label: str,
) -> None:
    if _is_link_or_reparse_point(skill_dir) or not skill_dir.exists():
        return

    skill_file = skill_dir / "SKILL.md"
    if _is_link_or_reparse_point(skill_file) or not skill_file.is_file():
        return

    content = skill_file.read_text(encoding="utf-8")
    if not is_managed(content):
        logger.warning("Skipped unrecognized %s file: %s", label, skill_file)
        return

    skill_file.unlink()
    try:
        skill_dir.rmdir()
    except OSError:
        # Preserve the directory when it contains user-managed files.
        pass
    logger.info("Removed %s: %s", label, skill_file)


def _is_managed_ask_agent(content: str) -> bool:
    return any(
        all(signature in content for signature in signatures)
        for signatures in _ASK_AGENT_MANAGED_SIGNATURES
    )


def _is_link_or_reparse_point(path: Path) -> bool:
    if path.is_symlink():
        return True
    try:
        file_attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except FileNotFoundError:
        return False
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return bool(reparse_flag and file_attributes & reparse_flag)
