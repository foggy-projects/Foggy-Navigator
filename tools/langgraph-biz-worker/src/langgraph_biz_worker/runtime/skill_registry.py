"""Skill manifest registry — loads from SKILL.md (YAML frontmatter + Markdown body).

Supports a two-tier directory layout with priority: builtin → public.
Also retains backward-compatible loading from legacy YAML manifests.

Directory layout (Doc 31 §6.2)::

    <worker-root>/
      skills/
        builtin/<skill-name>/SKILL.md
        public/<skill-name>/SKILL.md

Load priority: builtin first, public second.  If same ``name`` appears in both,
builtin wins.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

import yaml

from ..models import SkillManifest

logger = logging.getLogger(__name__)

# Default skills root (relative to worker root, not package root)
_DEFAULT_SKILLS_ROOT = Path(__file__).resolve().parent.parent.parent.parent / "skills"

# Legacy manifests directory (for backward compatibility during migration)
_LEGACY_MANIFESTS_DIR = Path(__file__).resolve().parent.parent / "manifests"


def _parse_skill_md(path: Path) -> dict[str, Any] | None:
    """Parse a SKILL.md file: extract YAML frontmatter as dict."""
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        logger.warning("Failed to read SKILL.md: %s", path, exc_info=True)
        return None

    # Extract YAML frontmatter between --- ... ---
    match = re.match(r"^---\s*\n(.*?)\n---", text, re.DOTALL)
    if not match:
        logger.warning("No YAML frontmatter found in %s", path)
        return None

    try:
        data: dict[str, Any] = yaml.safe_load(match.group(1))
    except Exception:
        logger.warning("Invalid YAML in frontmatter: %s", path, exc_info=True)
        return None

    if not isinstance(data, dict):
        logger.warning("Frontmatter is not a dict: %s", path)
        return None

    return data


def _frontmatter_to_manifest(data: dict[str, Any], source_path: Path) -> SkillManifest | None:
    """Convert SKILL.md frontmatter to SkillManifest.

    Maps the open-standard field names (``metadata.*``, ``allowed-tools``)
    to internal SkillManifest fields (Doc 31 §6.5).
    """
    name = data.get("name")
    if not name:
        logger.warning("SKILL.md missing 'name' field: %s", source_path)
        return None

    metadata = data.get("metadata", {})

    # Parse allowed-tools: space-separated string → list
    raw_tools = data.get("allowed-tools", "")
    allowed_tools = raw_tools.split() if isinstance(raw_tools, str) else list(raw_tools)

    return SkillManifest(
        id=name,
        name=metadata.get("display_name", name),
        description=data.get("description", ""),
        input_schema=metadata.get("input-schema", {}),
        output_schema=metadata.get("output-schema", {}),
        allowed_tools=allowed_tools,
        promote_to_parent=metadata.get("promote-to-parent", []),
        business_rules=metadata.get("business-rules", {}),
        subgraph=metadata.get("subgraph"),
    )


class SkillRegistry:
    """Registry of Skill manifests.

    Supports two loading modes:
    1. **SKILL.md** from ``skills/builtin/`` and ``skills/public/`` (preferred)
    2. **Legacy YAML** from ``manifests/`` (backward compatible)
    """

    def __init__(
        self,
        skills_root: Path | None = None,
        manifests_dir: Path | None = None,
    ) -> None:
        self._manifests: dict[str, SkillManifest] = {}
        self._skills_root = skills_root or _DEFAULT_SKILLS_ROOT
        self._legacy_dir = manifests_dir or _LEGACY_MANIFESTS_DIR

    def load(self) -> None:
        """Load skills from all sources.  Priority: builtin > public > legacy."""
        self._manifests.clear()

        # 1. Load legacy YAML manifests (lowest priority)
        self._load_legacy_yaml()

        # 2. Load SKILL.md from skills/public/ (overwrites legacy)
        public_dir = self._skills_root / "public"
        self._load_skill_md_dir(public_dir, "public")

        # 3. Load SKILL.md from skills/builtin/ (highest priority)
        builtin_dir = self._skills_root / "builtin"
        self._load_skill_md_dir(builtin_dir, "builtin")

    def _load_skill_md_dir(self, base_dir: Path, scope: str) -> None:
        """Scan ``<base_dir>/<skill-name>/SKILL.md`` entries."""
        if not base_dir.is_dir():
            logger.debug("Skills directory not found (ok): %s", base_dir)
            return

        for skill_dir in sorted(base_dir.iterdir()):
            if not skill_dir.is_dir():
                continue
            skill_md = skill_dir / "SKILL.md"
            if not skill_md.is_file():
                continue

            data = _parse_skill_md(skill_md)
            if data is None:
                continue

            manifest = _frontmatter_to_manifest(data, skill_md)
            if manifest is None:
                continue

            self._manifests[manifest.id] = manifest
            logger.info("Loaded skill [%s] %s from %s", scope, manifest.id, skill_md)

    def _load_legacy_yaml(self) -> None:
        """Load legacy ``*.yaml`` manifests for backward compatibility."""
        if not self._legacy_dir.is_dir():
            return

        for path in sorted(self._legacy_dir.glob("*.yaml")):
            try:
                with open(path, encoding="utf-8") as f:
                    data = yaml.safe_load(f)
                manifest = SkillManifest(**data)
                self._manifests[manifest.id] = manifest
                logger.info("Loaded legacy manifest: %s (%s)", manifest.id, path.name)
            except Exception:
                logger.exception("Failed to load legacy manifest: %s", path)

    def get_manifest(self, skill_id: str) -> SkillManifest | None:
        return self._manifests.get(skill_id)

    def list_skills(self) -> list[SkillManifest]:
        return list(self._manifests.values())

    def register(self, manifest: SkillManifest) -> None:
        """Programmatically register a manifest (useful for tests)."""
        self._manifests[manifest.id] = manifest
