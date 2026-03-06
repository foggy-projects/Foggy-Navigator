"""Shared fixtures for claude-agent-worker tests."""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import patch

import pytest


@pytest.fixture
def tmp_event_dir(tmp_path: Path) -> Path:
    """Return a temporary directory for event persistence tests."""
    return tmp_path


@pytest.fixture
def patch_allowed_cwds():
    """Factory fixture to patch ``settings.allowed_cwds``."""

    def _patch(allowed: list[str]):
        return patch("agent_worker.config.settings.allowed_cwds", allowed)

    return _patch


@pytest.fixture
def patch_settings():
    """Factory fixture to patch arbitrary settings attributes."""

    def _patch(**kwargs):
        patches = []
        for key, value in kwargs.items():
            p = patch(f"agent_worker.config.settings.{key}", value)
            patches.append(p)
        return patches

    return _patch
