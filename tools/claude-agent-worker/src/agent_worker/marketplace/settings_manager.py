"""Manage settings.json for company skill marketplace configuration."""

from __future__ import annotations

import json
import logging
import tempfile
from pathlib import Path
from typing import Any

from .config import MARKETPLACE_KEY, SETTINGS_FILE

logger = logging.getLogger(__name__)


def read_settings() -> dict[str, Any]:
    """Read settings.json, return empty dict if not exists or invalid."""
    if not SETTINGS_FILE.exists():
        return {}
    try:
        return json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, IOError) as e:
        logger.warning("Failed to read settings.json: %s", e)
        return {}


def write_settings(settings: dict[str, Any]) -> None:
    """Atomically write settings.json."""
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)

    # Write to temp file first, then rename for atomicity
    content = json.dumps(settings, indent=2, ensure_ascii=False)

    # Use temp file in same directory for atomic rename
    temp_fd, temp_path = tempfile.mkstemp(
        dir=SETTINGS_FILE.parent,
        prefix="settings.",
        suffix=".tmp"
    )
    try:
        with open(temp_fd, mode="w", encoding="utf-8") as f:
            f.write(content)
        Path(temp_path).replace(SETTINGS_FILE)
    except Exception:
        Path(temp_path).unlink(missing_ok=True)
        raise


def is_marketplace_configured(settings: dict[str, Any], marketplace_url: str) -> bool:
    """Check if marketplace is already correctly configured."""
    mp = settings.get(MARKETPLACE_KEY, {})
    source = mp.get("source", {})
    return (
        source.get("source") == "git"
        and source.get("url") == marketplace_url
    )


def configure_marketplace(settings: dict[str, Any], marketplace_url: str) -> dict[str, Any]:
    """Add marketplace configuration to settings (preserves existing config)."""
    settings[MARKETPLACE_KEY] = {
        "source": {
            "source": "git",
            "url": marketplace_url
        }
    }
    return settings