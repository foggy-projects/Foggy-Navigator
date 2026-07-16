"""Reconcile Navigator-managed skills under the user's agent skill roots.

The Worker package is the source of truth for current platform skills.  On
startup it deploys the current bundles and removes only legacy files that can
be identified as Navigator-generated content.
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

# Simple one-file skills retained outside the three-router consolidation.
_SKILL_TEMPLATES = {
    "company-skill-marketplace": "company_skill_marketplace.md",
}

# Current multi-file platform skills.  These directories are copied as skill
# bundles so references and product metadata remain available on demand.
_SKILL_BUNDLES = {
    "navigator-ops": "navigator_ops",
}

_MANAGED_SKILL_MARKERS = {
    "navigator-ops": "<!-- foggy-navigator-platform-skill:v1; name=navigator-ops -->",
}

# Each tuple is one recognized historical shape.  Matching is intentionally
# strict: every signature in a shape must be present before a file is removed.
_RETIRED_SKILL_SIGNATURES: dict[str, tuple[tuple[str, ...], ...]] = {
    "cross-project-task": (
        (
            "name: cross-project-task",
            "/api/v1/cross-project-tasks",
        ),
    ),
    "ask-agent": (
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
    ),
    "navigator-admin": (
        (
            "name: navigator-admin",
            "# Foggy Navigator 平台管理",
            "NAVIGATOR_TOKEN",
            "/api/v1/claude-workers",
        ),
    ),
    "scheduled-task": (
        (
            "name: scheduled-task",
            "# AI 定时任务配置向导",
            "NAVIGATOR_TOKEN",
            "/api/v1/sharing-keys",
        ),
    ),
    "sharing-key": (
        (
            "name: sharing-key",
            "# Sharing Key 管理向导",
            "NAVIGATOR_TOKEN",
            "/api/v1/sharing-keys",
        ),
    ),
}

_TEMPLATE_VARS = {
    "{{NAVIGATOR_API_BASE}}": lambda: settings.navigator_api_base,
    "{{MARKETPLACE_URL}}": lambda: getattr(settings, "marketplace_url", DEFAULT_MARKETPLACE_URL),
}


def deploy_platform_skills() -> None:
    """Backward-compatible startup entry point for platform skill reconciliation."""
    reconcile_platform_skills()


def reconcile_platform_skills(
    skills_dir: Path | None = None,
    skill_roots: Iterable[Path] | None = None,
) -> None:
    """Deploy current platform skills and retire recognized legacy copies."""
    resolved_skills_dir = skills_dir or user_skills_dir()
    remove_retired_platform_skills(skill_roots or default_skill_roots(resolved_skills_dir))

    for skill_name, template_file in _SKILL_TEMPLATES.items():
        try:
            source = _SKILLS_DIR / template_file
            if not source.exists():
                logger.warning("Skill template not found: %s", source)
                continue

            content = _render_template(source.read_text(encoding="utf-8"))
            target = deploy_skill(resolved_skills_dir, skill_name, content)
            if target is not None:
                logger.info("Deployed platform skill: %s -> %s", skill_name, target)
        except Exception:
            logger.warning("Failed to deploy platform skill: %s", skill_name, exc_info=True)

    for skill_name, bundle_dir in _SKILL_BUNDLES.items():
        try:
            source_dir = _SKILLS_DIR / bundle_dir
            target = deploy_skill_bundle(resolved_skills_dir, skill_name, source_dir)
            if target is not None:
                logger.info("Deployed platform skill bundle: %s -> %s", skill_name, target.parent)
        except Exception:
            logger.warning("Failed to deploy platform skill bundle: %s", skill_name, exc_info=True)


def default_skill_roots(canonical_skills_dir: Path | None = None) -> tuple[Path, ...]:
    """Return the canonical and historical user-level skill roots once each."""
    home = Path.home()
    candidates = (
        canonical_skills_dir or user_skills_dir(),
        home / ".agent" / "skills",
        home / ".claude" / "skills",
    )
    return tuple(dict.fromkeys(candidates))


def deploy_skill(skills_dir: Path, skill_name: str, content: str) -> Path | None:
    """Deploy a non-bundled skill unless its name is retired or locally owned."""
    if is_retired_platform_skill_name(skill_name):
        logger.warning("Skipped retired platform skill deployment: %s", skill_name)
        return None
    if skill_name in _SKILL_BUNDLES:
        logger.warning("Skipped remote replacement of locally bundled platform skill: %s", skill_name)
        return None

    target_dir = skills_dir / skill_name
    target = target_dir / "SKILL.md"
    if _is_link_or_reparse_point(target_dir) or _is_link_or_reparse_point(target):
        logger.warning("Skipped linked platform skill target: %s", target)
        return None
    if target.exists() and not target.is_file():
        logger.warning("Skipped non-file platform skill target: %s", target)
        return None

    target_dir.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def deploy_skill_bundle(
    skills_dir: Path,
    skill_name: str,
    source_dir: Path,
) -> Path | None:
    """Deploy one managed multi-file skill without overwriting unknown content."""
    source_skill = source_dir / "SKILL.md"
    if not source_skill.is_file():
        logger.warning("Skill bundle is missing SKILL.md: %s", source_dir)
        return None

    target_dir = skills_dir / skill_name
    target_skill = target_dir / "SKILL.md"
    marker = _MANAGED_SKILL_MARKERS[skill_name]

    if not _can_manage_bundle(target_dir, target_skill, marker):
        return None

    source_files = tuple(path for path in source_dir.rglob("*") if path.is_file())
    for source in source_files:
        relative = source.relative_to(source_dir)
        target = target_dir / relative
        if _has_linked_or_non_directory_parent(target_dir, relative.parent):
            logger.warning("Skipped unsafe platform skill bundle target: %s", target)
            return None
        if _is_link_or_reparse_point(target) or (target.exists() and not target.is_file()):
            logger.warning("Skipped unsafe platform skill bundle file: %s", target)
            return None

    for source in source_files:
        relative = source.relative_to(source_dir)
        target = target_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(_render_template(source.read_text(encoding="utf-8")), encoding="utf-8")

    return target_skill


def _can_manage_bundle(target_dir: Path, target_skill: Path, marker: str) -> bool:
    if _is_link_or_reparse_point(target_dir) or _is_link_or_reparse_point(target_skill):
        logger.warning("Skipped linked platform skill bundle: %s", target_skill)
        return False
    if not target_skill.exists():
        return True
    if not target_skill.is_file():
        logger.warning("Skipped non-file platform skill bundle: %s", target_skill)
        return False

    content = target_skill.read_text(encoding="utf-8")
    if marker in content:
        return True

    logger.warning("Skipped unrecognized platform skill bundle: %s", target_skill)
    return False


def _has_linked_or_non_directory_parent(target_dir: Path, relative_parent: Path) -> bool:
    current = target_dir
    if _is_link_or_reparse_point(current) or (current.exists() and not current.is_dir()):
        return True
    for part in relative_parent.parts:
        current = current / part
        if _is_link_or_reparse_point(current) or (current.exists() and not current.is_dir()):
            return True
    return False


def _render_template(content: str) -> str:
    for placeholder, value_fn in _TEMPLATE_VARS.items():
        content = content.replace(placeholder, value_fn())
    return content


def is_retired_platform_skill_name(skill_name: str) -> bool:
    return skill_name in _RETIRED_SKILL_SIGNATURES


def remove_retired_platform_skills(skill_roots: Iterable[Path] | None = None) -> None:
    """Remove every recognized retired Navigator skill from all supplied roots."""
    roots = tuple(skill_roots or default_skill_roots())
    for skill_name in _RETIRED_SKILL_SIGNATURES:
        remove_retired_platform_skill(skill_name, roots)


def remove_retired_platform_skill(
    skill_name: str,
    skill_roots: Iterable[Path] | None = None,
) -> None:
    """Remove one retired Navigator skill only when its historical shape is recognized."""
    signatures = _RETIRED_SKILL_SIGNATURES.get(skill_name)
    if signatures is None:
        return

    for root in tuple(skill_roots or default_skill_roots()):
        skill_dir = root / skill_name
        try:
            _remove_managed_skill_file(
                skill_dir,
                lambda content, variants=signatures: _matches_any_signature(content, variants),
                f"retired platform skill {skill_name}",
            )
        except Exception:
            logger.warning("Failed to retire platform skill at %s", skill_dir, exc_info=True)


def _matches_any_signature(content: str, variants: tuple[tuple[str, ...], ...]) -> bool:
    return any(all(signature in content for signature in variant) for variant in variants)


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


def _is_link_or_reparse_point(path: Path) -> bool:
    if path.is_symlink():
        return True
    try:
        file_attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except FileNotFoundError:
        return False
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return bool(reparse_flag and file_attributes & reparse_flag)
