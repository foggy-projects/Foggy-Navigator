"""Unit tests for routes/git_log.py — hash validation, file param validation, and parsing."""

from __future__ import annotations

import pytest
from fastapi import HTTPException

from agent_worker.routes.git_log import _validate_hash, _validate_file_param


# ---------------------------------------------------------------------------
# _validate_hash
# ---------------------------------------------------------------------------

class TestValidateHash:
    """Validate commit hash format (4-40 hex chars)."""

    def test_full_sha(self):
        h = "a" * 40
        assert _validate_hash(h) == h

    def test_short_sha(self):
        assert _validate_hash("abcd1234") == "abcd1234"

    def test_minimum_length(self):
        assert _validate_hash("abcd") == "abcd"

    def test_mixed_case(self):
        assert _validate_hash("aAbBcCdD") == "aAbBcCdD"

    def test_too_short_rejects(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_hash("abc")
        assert exc_info.value.status_code == 400
        assert "hex characters" in exc_info.value.detail

    def test_too_long_rejects(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_hash("a" * 41)
        assert exc_info.value.status_code == 400

    def test_non_hex_rejects(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_hash("ghijklmn")
        assert exc_info.value.status_code == 400

    def test_empty_string_rejects(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_hash("")
        assert exc_info.value.status_code == 400

    def test_special_chars_reject(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_hash("abcd-1234")
        assert exc_info.value.status_code == 400


# ---------------------------------------------------------------------------
# _validate_file_param
# ---------------------------------------------------------------------------

class TestValidateFileParam:
    """Prevent path traversal and absolute paths in file parameter."""

    def test_relative_path_allowed(self):
        assert _validate_file_param("src/main.py") == "src/main.py"

    def test_simple_filename_allowed(self):
        assert _validate_file_param("README.md") == "README.md"

    def test_nested_path_allowed(self):
        assert _validate_file_param("a/b/c/d.txt") == "a/b/c/d.txt"

    def test_rejects_absolute_unix_path(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_file_param("/etc/passwd")
        assert exc_info.value.status_code == 400
        assert "relative" in exc_info.value.detail

    def test_rejects_absolute_windows_path(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_file_param("C:\\Windows\\system32")
        assert exc_info.value.status_code == 400

    def test_rejects_path_traversal(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_file_param("../../../etc/passwd")
        assert exc_info.value.status_code == 400
        assert "Path traversal" in exc_info.value.detail

    def test_rejects_traversal_in_middle(self):
        with pytest.raises(HTTPException) as exc_info:
            _validate_file_param("src/../../secret")
        assert exc_info.value.status_code == 400
