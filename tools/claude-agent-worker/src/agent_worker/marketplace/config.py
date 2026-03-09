"""Configuration constants for company skill marketplace setup."""

from __future__ import annotations

from pathlib import Path

# Default marketplace URL (can be overridden via AGENT_WORKER_MARKETPLACE_URL)
DEFAULT_MARKETPLACE_URL = "http://gitlab.foggysource.com/foggy-tools/company-skill-marketplace.git"

# settings.json key for marketplace configuration
MARKETPLACE_KEY = "company-skill-marketplace"

# Claude user-level configuration directory
CLAUDE_DIR = Path.home() / ".claude"
SETTINGS_FILE = CLAUDE_DIR / "settings.json"