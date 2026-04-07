"""Unit tests for routes/utils.py."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from agent_worker.routes.utils import decode_text_bytes, run_git


class _FakeProcess:
    def __init__(self, returncode: int, stdout: bytes, stderr: bytes):
        self.returncode = returncode
        self._stdout = stdout
        self._stderr = stderr

    async def communicate(self) -> tuple[bytes, bytes]:
        return self._stdout, self._stderr


class TestDecodeTextBytes:
    def test_prefers_utf8(self):
        raw = "中文文件名.txt".encode("utf-8")
        assert decode_text_bytes(raw) == "中文文件名.txt"

    def test_falls_back_to_gbk_family(self):
        raw = "全球有哪些国家在“猛推”HPV疫苗？".encode("gbk")
        assert decode_text_bytes(raw) == "全球有哪些国家在“猛推”HPV疫苗？"


@pytest.mark.asyncio
async def test_run_git_disables_quote_path_and_decodes_output():
    proc = _FakeProcess(
        returncode=0,
        stdout="中文文件名.txt\n".encode("utf-8"),
        stderr=b"",
    )

    create_mock = AsyncMock(return_value=proc)
    with patch("agent_worker.routes.utils.asyncio.create_subprocess_exec", create_mock):
        rc, out = await run_git("D:/repo", "ls-files", "--cached")

    assert rc == 0
    assert out == "中文文件名.txt"

    args = create_mock.await_args.args
    assert args[:3] == ("git", "-c", "core.quotepath=false")
    assert args[3:] == ("ls-files", "--cached")


@pytest.mark.asyncio
async def test_run_git_decodes_legacy_windows_output():
    proc = _FakeProcess(
        returncode=0,
        stdout="全球有哪些国家在“猛推”HPV疫苗？".encode("gbk"),
        stderr=b"",
    )

    create_mock = AsyncMock(return_value=proc)
    with patch("agent_worker.routes.utils.asyncio.create_subprocess_exec", create_mock):
        rc, out = await run_git("D:/repo", "grep", "HPV")

    assert rc == 0
    assert out == "全球有哪些国家在“猛推”HPV疫苗？"
