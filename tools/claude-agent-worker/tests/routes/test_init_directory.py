"""Unit tests for routes/init_directory.py — directory creation and file writing."""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest
import pytest_asyncio
from fastapi import HTTPException

from agent_worker.routes.init_directory import init_directory, InitDirectoryRequest


@pytest.mark.asyncio
class TestInitDirectory:
    """POST /api/v1/init-directory endpoint logic."""

    async def test_creates_directory_and_files(self, tmp_path):
        target = str(tmp_path / "new-project")
        req = InitDirectoryRequest(
            path=target,
            files={
                "CLAUDE.md": "# Guide\nHello",
                "settings.json": '{"key": "value"}',
            },
        )
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = []
            result = await init_directory(req)

        assert os.path.isdir(target)
        assert set(result.files_created) == {"CLAUDE.md", "settings.json"}
        assert (tmp_path / "new-project" / "CLAUDE.md").read_text() == "# Guide\nHello"
        assert (tmp_path / "new-project" / "settings.json").read_text() == '{"key": "value"}'

    async def test_creates_nested_file_paths(self, tmp_path):
        target = str(tmp_path / "project")
        req = InitDirectoryRequest(
            path=target,
            files={"sub/dir/file.txt": "nested content"},
        )
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = []
            result = await init_directory(req)

        assert "sub/dir/file.txt" in result.files_created
        assert (tmp_path / "project" / "sub" / "dir" / "file.txt").read_text() == "nested content"

    async def test_existing_directory_ok(self, tmp_path):
        target = str(tmp_path / "existing")
        os.makedirs(target)
        req = InitDirectoryRequest(path=target, files={"a.txt": "content"})
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = []
            result = await init_directory(req)

        assert result.files_created == ["a.txt"]

    async def test_registers_path_in_allowed_cwds(self, tmp_path):
        target = str(tmp_path / "new-dir")
        req = InitDirectoryRequest(path=target, files={"a.txt": "x"})
        mock_cwds = ["/some/other/path"]
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = mock_cwds
            await init_directory(req)

        resolved = os.path.realpath(target)
        assert resolved in mock_cwds or any(
            resolved == os.path.realpath(a) for a in mock_cwds
        )

    async def test_does_not_duplicate_allowed_cwd(self, tmp_path):
        target = str(tmp_path / "dir")
        os.makedirs(target)
        resolved = os.path.realpath(target)
        req = InitDirectoryRequest(path=target, files={"a.txt": "x"})
        mock_cwds = [resolved]
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = mock_cwds
            await init_directory(req)

        assert mock_cwds.count(resolved) == 1

    async def test_permission_error_returns_403(self, tmp_path):
        req = InitDirectoryRequest(path="/root/forbidden", files={"a.txt": "x"})
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = []
            with patch("agent_worker.routes.init_directory.os.makedirs", side_effect=PermissionError("denied")):
                with pytest.raises(HTTPException) as exc_info:
                    await init_directory(req)
                assert exc_info.value.status_code == 403

    async def test_expands_tilde_in_path(self, tmp_path):
        # Use a path with ~, mock expanduser to resolve to tmp_path
        req = InitDirectoryRequest(path="~/my-project", files={"a.txt": "hello"})
        with patch("agent_worker.routes.init_directory.settings") as mock_settings:
            mock_settings.allowed_cwds = []
            with patch("agent_worker.routes.init_directory.os.path.expanduser",
                        return_value=str(tmp_path / "my-project")):
                result = await init_directory(req)

        assert os.path.isfile(tmp_path / "my-project" / "a.txt")
