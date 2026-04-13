"""Skill manifest registry — loads YAML manifests from the manifests/ directory."""

from __future__ import annotations

import logging
from pathlib import Path

import yaml

from ..models import SkillManifest

logger = logging.getLogger(__name__)

# Default manifests directory (relative to package root)
_DEFAULT_MANIFESTS_DIR = Path(__file__).resolve().parent.parent / "manifests"


class SkillRegistry:
    """Registry of Skill manifests loaded from YAML files."""

    def __init__(self, manifests_dir: Path | None = None) -> None:
        self._manifests: dict[str, SkillManifest] = {}
        self._manifests_dir = manifests_dir or _DEFAULT_MANIFESTS_DIR

    def load(self) -> None:
        """Scan the manifests directory and load all ``.yaml`` files."""
        self._manifests.clear()
        if not self._manifests_dir.is_dir():
            logger.warning("Manifests directory not found: %s", self._manifests_dir)
            return

        for path in sorted(self._manifests_dir.glob("*.yaml")):
            try:
                with open(path, encoding="utf-8") as f:
                    data = yaml.safe_load(f)
                manifest = SkillManifest(**data)
                self._manifests[manifest.id] = manifest
                logger.info("Loaded skill manifest: %s (%s)", manifest.id, path.name)
            except Exception:
                logger.exception("Failed to load manifest: %s", path)

    def get_manifest(self, skill_id: str) -> SkillManifest | None:
        return self._manifests.get(skill_id)

    def list_skills(self) -> list[SkillManifest]:
        return list(self._manifests.values())

    def register(self, manifest: SkillManifest) -> None:
        """Programmatically register a manifest (useful for tests)."""
        self._manifests[manifest.id] = manifest
