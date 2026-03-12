"""Unit tests for marketplace/settings_manager.py — settings.json read/write and marketplace config."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from agent_worker.marketplace.settings_manager import (
    configure_marketplace,
    is_marketplace_configured,
    read_settings,
    write_settings,
)
from agent_worker.marketplace.config import MARKETPLACE_KEY


# ---------------------------------------------------------------------------
# read_settings
# ---------------------------------------------------------------------------

class TestReadSettings:
    """Read settings.json from ~/.claude/."""

    def test_returns_empty_when_file_not_exists(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            result = read_settings()
        assert result == {}

    def test_reads_valid_json(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        fake_file.write_text('{"key": "value"}')
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            result = read_settings()
        assert result == {"key": "value"}

    def test_returns_empty_on_invalid_json(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        fake_file.write_text("not valid json {{{")
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            result = read_settings()
        assert result == {}


# ---------------------------------------------------------------------------
# write_settings
# ---------------------------------------------------------------------------

class TestWriteSettings:
    """Atomically write settings.json."""

    def test_writes_json_content(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            write_settings({"hello": "world"})
        result = json.loads(fake_file.read_text(encoding="utf-8"))
        assert result == {"hello": "world"}

    def test_creates_parent_directory(self, tmp_path):
        fake_file = tmp_path / "subdir" / "settings.json"
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            write_settings({"created": True})
        assert fake_file.exists()

    def test_overwrites_existing(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        fake_file.write_text('{"old": true}')
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            write_settings({"new": True})
        result = json.loads(fake_file.read_text(encoding="utf-8"))
        assert result == {"new": True}

    def test_preserves_unicode(self, tmp_path):
        fake_file = tmp_path / "settings.json"
        with patch("agent_worker.marketplace.settings_manager.SETTINGS_FILE", fake_file):
            write_settings({"name": "中文技能"})
        result = json.loads(fake_file.read_text(encoding="utf-8"))
        assert result["name"] == "中文技能"


# ---------------------------------------------------------------------------
# is_marketplace_configured
# ---------------------------------------------------------------------------

class TestIsMarketplaceConfigured:
    """Check if marketplace is already correctly configured."""

    def test_configured_correctly(self):
        settings = {
            MARKETPLACE_KEY: {
                "source": {
                    "source": "git",
                    "url": "https://gitlab.example.com/skills.git",
                }
            }
        }
        assert is_marketplace_configured(settings, "https://gitlab.example.com/skills.git") is True

    def test_different_url(self):
        settings = {
            MARKETPLACE_KEY: {
                "source": {
                    "source": "git",
                    "url": "https://other.com/old.git",
                }
            }
        }
        assert is_marketplace_configured(settings, "https://gitlab.example.com/skills.git") is False

    def test_not_configured(self):
        assert is_marketplace_configured({}, "https://gitlab.example.com/skills.git") is False

    def test_partial_config(self):
        settings = {MARKETPLACE_KEY: {"source": {}}}
        assert is_marketplace_configured(settings, "https://gitlab.example.com/skills.git") is False


# ---------------------------------------------------------------------------
# configure_marketplace
# ---------------------------------------------------------------------------

class TestConfigureMarketplace:
    """Add marketplace configuration to settings."""

    def test_adds_marketplace_key(self):
        settings = {}
        result = configure_marketplace(settings, "https://gitlab.example.com/skills.git")
        assert MARKETPLACE_KEY in result
        assert result[MARKETPLACE_KEY]["source"]["source"] == "git"
        assert result[MARKETPLACE_KEY]["source"]["url"] == "https://gitlab.example.com/skills.git"

    def test_preserves_existing_keys(self):
        settings = {"other_key": {"data": 123}}
        result = configure_marketplace(settings, "https://gitlab.example.com/skills.git")
        assert result["other_key"]["data"] == 123
        assert MARKETPLACE_KEY in result

    def test_overwrites_existing_marketplace(self):
        settings = {MARKETPLACE_KEY: {"source": {"source": "git", "url": "old"}}}
        result = configure_marketplace(settings, "https://new-url.git")
        assert result[MARKETPLACE_KEY]["source"]["url"] == "https://new-url.git"

    def test_mutates_input_dict(self):
        """configure_marketplace modifies and returns the same dict."""
        settings = {}
        result = configure_marketplace(settings, "https://url.git")
        assert result is settings
