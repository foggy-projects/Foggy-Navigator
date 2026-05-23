"""Skill manifest registry — loads from SKILL.md (YAML frontmatter + Markdown body).

Supports a layered directory layout with priority:
legacy < builtin < public < app-public < account.
Also retains backward-compatible loading from legacy YAML manifests.

Directory layout (Doc 31 §6.2)::

    <worker-root>/
      skills/
        builtin/<skill-name>/SKILL.md
        public/<skill-name>/SKILL.md
        public/apps/<client-app-id>/<skill-name>/SKILL.md
      data/
        accounts/<account-id>/skills/<skill-name>/SKILL.md

Load priority: later layers overwrite earlier layers.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

import yaml

from ..models import SkillManifest
from .account_workspace import AccountWorkspace, resolve_account_workspace

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

    data["markdown_body"] = text[match.end():].strip()
    return data


def _frontmatter_to_manifest(
    data: dict[str, Any],
    source_path: Path,
    client_app_id: str | None = None,
) -> SkillManifest | None:
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
    raw_tools = data.get("allowed-tools", data.get("tools", ""))
    if raw_tools is None:
        allowed_tools = []
    elif isinstance(raw_tools, str):
        allowed_tools = raw_tools.split()
    else:
        allowed_tools = list(raw_tools)

    return SkillManifest(
        id=name,
        name=metadata.get("display_name", name),
        description=data.get("description", ""),
        markdown_body=data.get("markdown_body", ""),
        input_schema=metadata.get("input-schema", {}),
        output_schema=metadata.get("output-schema", {}),
        allowed_tools=allowed_tools,
        promote_to_parent=metadata.get("promote-to-parent", []),
        business_rules=metadata.get("business-rules", {}),
        subgraph=metadata.get("subgraph"),
        visibility=metadata.get("visibility", data.get("visibility", "public")),
        context_visibility=metadata.get(
            "context-visibility",
            metadata.get("context_visibility", data.get("context_visibility", "isolated")),
        ),
        client_app_id=metadata.get("client_app_id", data.get("client_app_id", client_app_id)),
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
        data_root: Path | None = None,
    ) -> None:
        self._manifests: dict[str, SkillManifest] = {}
        self._aliases: dict[str, str] = {}
        self._skills_root = skills_root or _DEFAULT_SKILLS_ROOT
        self._legacy_dir = manifests_dir or _LEGACY_MANIFESTS_DIR
        self._data_root = data_root or self._skills_root.parent / "data"

    @property
    def data_root(self) -> Path:
        return self._data_root

    def load(
        self,
        account_id: str | None = None,
        client_app_id: str | None = None,
        include_standalone: bool = False,
        account_workspace: AccountWorkspace | None = None,
    ) -> None:
        """Load skills from all sources.

        Priority (later overwrites earlier): legacy < builtin < public < app-public < account.
        If ``client_app_id`` is provided, also loads that app's public skills.
        If ``account_id`` is provided, also loads account-private skills.
        """
        self._manifests.clear()
        self._aliases.clear()

        # 1. Load legacy YAML manifests (lowest priority)
        self._load_legacy_yaml()

        if include_standalone:
            self._load_skill_md_dir(self._skills_root, "standalone")

        # 2. Load SKILL.md from skills/builtin/
        builtin_dir = self._skills_root / "builtin"
        self._load_skill_md_dir(builtin_dir, "builtin")

        # 3. Load SKILL.md from skills/public/ (overwrites builtin)
        public_dir = self._skills_root / "public"
        self._load_skill_md_dir(public_dir, "public")

        # 4. Load app-scoped public skills (overwrites global public)
        if client_app_id:
            self.load_client_app_public_skills(client_app_id)

        # 5. Load account-private skills (highest priority, Doc 34 §6)
        if account_id or account_workspace:
            self.load_account_skills(account_id, account_workspace=account_workspace)

    def load_client_app_public_skills(self, client_app_id: str) -> None:
        """Load public skills granted to a single client app."""
        client_app_id = _validate_path_segment(client_app_id, "client_app_id")
        app_dir = self._skills_root / "public" / "apps" / client_app_id
        self._load_skill_md_dir(app_dir, f"public-app:{client_app_id}", client_app_id=client_app_id)

    def load_account_skills(
        self,
        account_id: str | None,
        *,
        account_workspace: AccountWorkspace | None = None,
    ) -> None:
        """Load skills from an account's private directory (overwrites all lower layers)."""
        workspace = account_workspace or resolve_account_workspace(self._data_root, account_id)
        if workspace is None:
            return
        account_dir = workspace.skills_root
        self._load_skill_md_dir(account_dir, f"account:{workspace.storage_account_id}")

    def _load_skill_md_dir(self, base_dir: Path, scope: str, client_app_id: str | None = None) -> None:
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

            manifest = _frontmatter_to_manifest(data, skill_md, client_app_id=client_app_id)
            if manifest is None:
                continue

            self.register(manifest, aliases=[skill_dir.name])
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
                self.register(manifest)
                logger.info("Loaded legacy manifest: %s (%s)", manifest.id, path.name)
            except Exception:
                logger.exception("Failed to load legacy manifest: %s", path)

    def get_manifest(self, skill_id: str) -> SkillManifest | None:
        manifest = self._manifests.get(skill_id)
        if manifest is not None:
            return manifest
        canonical = self._aliases.get(skill_id)
        if canonical:
            return self._manifests.get(canonical)
        return None

    def list_skills(self) -> list[SkillManifest]:
        return list(self._manifests.values())

    def register(self, manifest: SkillManifest, aliases: list[str] | None = None) -> None:
        """Programmatically register a manifest (useful for tests)."""
        self._manifests[manifest.id] = manifest
        for alias in aliases or []:
            if alias and alias != manifest.id:
                self._aliases[alias] = manifest.id


def _validate_account_id(account_id: str) -> str:
    """Reject path traversal in account IDs before touching the filesystem."""
    return _validate_path_segment(account_id, "account_id")


def _validate_path_segment(value: str, field_name: str) -> str:
    """Reject path traversal before touching the filesystem."""
    if not value or value.strip() != value:
        raise ValueError(f"{field_name} must be non-empty and trimmed")
    if value in {".", ".."} or "/" in value or "\\" in value:
        raise ValueError(f"{field_name} must be a single path segment")
    return value
