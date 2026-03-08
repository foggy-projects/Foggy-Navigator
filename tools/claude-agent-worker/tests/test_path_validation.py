"""Tests for allowed_cwds path validation logic.

Covers the drive-root prefix matching fix: configuring "D:\\" should
authorize all directories under D:.
"""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest
from fastapi import HTTPException

from agent_worker.routes.utils import validate_path
from agent_worker.routes.query import _validate_cwd
from agent_worker.routes.skills import _validate_path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _patch_allowed(allowed: list[str]):
    """Patch settings.allowed_cwds for a single test."""
    return patch("agent_worker.config.settings.allowed_cwds", allowed)


# Use non-raw strings so "\\" is a single backslash (matching os.path.realpath output).
# raw r"D:\\" would be two backslashes — not what we want.

# ---------------------------------------------------------------------------
# validate_path (utils.py)
# ---------------------------------------------------------------------------

class TestValidatePath:
    """Tests for routes/utils.py validate_path()."""

    def test_drive_root_allows_subdir(self):
        """D:\\ should authorize D:\\projects."""
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("D:\\projects")
                assert result == "D:\\projects"

    def test_drive_root_allows_deep_subdir(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("D:\\a\\b\\c")
                assert result == "D:\\a\\b\\c"

    def test_drive_root_exact_match(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("D:\\")
                assert result == "D:\\"

    def test_drive_root_rejects_other_drive(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException) as exc_info:
                    validate_path("E:\\projects")
                assert exc_info.value.status_code == 403

    def test_normal_dir_allows_subdir(self):
        with _patch_allowed(["D:\\projects"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("D:\\projects\\foo")
                assert result == "D:\\projects\\foo"

    def test_normal_dir_rejects_sibling(self):
        """D:\\projects should NOT match D:\\projects-backup."""
        with _patch_allowed(["D:\\projects"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException) as exc_info:
                    validate_path("D:\\projects-backup")
                assert exc_info.value.status_code == 403

    def test_empty_allowed_permits_all(self):
        with _patch_allowed([]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("Z:\\anything")
                assert result == "Z:\\anything"

    def test_multiple_allowed(self):
        with _patch_allowed(["D:\\", "E:\\tmp"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                assert validate_path("D:\\foo") == "D:\\foo"
                assert validate_path("E:\\tmp\\bar") == "E:\\tmp\\bar"
                with pytest.raises(HTTPException):
                    validate_path("E:\\other")

    @pytest.mark.skipif(os.name != "posix", reason="Unix paths")
    def test_unix_root_allows_subdir(self):
        with _patch_allowed(["/"]):
            with patch("agent_worker.routes.utils.os.path.realpath", side_effect=lambda p: p):
                result = validate_path("/home/user/projects")
                assert result == "/home/user/projects"


# ---------------------------------------------------------------------------
# _validate_cwd (query.py)
# ---------------------------------------------------------------------------

class TestValidateCwd:
    """Tests for routes/query.py _validate_cwd()."""

    def test_drive_root_allows_subdir(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                result = _validate_cwd("D:\\projects")
                assert result == "D:\\projects"

    def test_drive_root_rejects_other_drive(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException) as exc_info:
                    _validate_cwd("E:\\projects")
                assert exc_info.value.status_code == 403

    def test_none_cwd_uses_getcwd(self):
        with _patch_allowed([]):
            with patch("agent_worker.routes.query.os.path.realpath", side_effect=lambda p: p):
                with patch("agent_worker.routes.query.os.getcwd", return_value="D:\\default"):
                    result = _validate_cwd(None)
                    assert result == "D:\\default"


# ---------------------------------------------------------------------------
# _validate_path (skills.py)
# ---------------------------------------------------------------------------

class TestSkillsValidatePath:
    """Tests for routes/skills.py _validate_path()."""

    def test_drive_root_allows_subdir(self):
        with _patch_allowed(["D:\\"]):
            with patch("agent_worker.routes.skills.os.path.realpath", side_effect=lambda p: p):
                result = _validate_path("D:\\projects\\skill")
                assert result == "D:\\projects\\skill"

    def test_normal_dir_rejects_sibling(self):
        with _patch_allowed(["D:\\projects"]):
            with patch("agent_worker.routes.skills.os.path.realpath", side_effect=lambda p: p):
                with pytest.raises(HTTPException):
                    _validate_path("D:\\projects-other")
