"""Unit tests for routes/files.py — helper functions and private logic."""

from __future__ import annotations

import os
from unittest.mock import MagicMock, mock_open, patch

import pytest
from fastapi import HTTPException

from agent_worker.routes.files import (
    _build_pathspec_excludes,
    _detect_language,
    _detect_media_type,
    _is_binary,
    _load_foggy_ignore,
    _get_exclude_patterns,
    _parse_git_grep_output,
    _safe_subpath,
    _should_skip_file,
    _skip_dirs_from_patterns,
    _DEFAULT_EXCLUDES,
)


# ---------------------------------------------------------------------------
# _detect_language
# ---------------------------------------------------------------------------

class TestDetectLanguage:
    """Language detection from file extension."""

    def test_python(self):
        assert _detect_language("app/main.py") == "python"

    def test_java(self):
        assert _detect_language("src/Main.java") == "java"

    def test_typescript(self):
        assert _detect_language("index.ts") == "typescript"

    def test_tsx(self):
        assert _detect_language("App.tsx") == "typescript"

    def test_javascript(self):
        assert _detect_language("index.js") == "javascript"

    def test_json(self):
        assert _detect_language("config.json") == "json"

    def test_yaml(self):
        assert _detect_language("docker-compose.yml") == "yaml"

    def test_dockerfile_by_name(self):
        assert _detect_language("Dockerfile") == "dockerfile"

    def test_dockerfile_extension(self):
        assert _detect_language("app.dockerfile") == "dockerfile"

    def test_vue(self):
        assert _detect_language("App.vue") == "html"

    def test_shell(self):
        assert _detect_language("start.sh") == "shell"

    def test_powershell(self):
        assert _detect_language("deploy.ps1") == "powershell"

    def test_sql(self):
        assert _detect_language("schema.sql") == "sql"

    def test_unknown_extension_returns_plaintext(self):
        assert _detect_language("readme.xyz") == "plaintext"

    def test_no_extension_returns_plaintext(self):
        assert _detect_language("Makefile") == "plaintext"

    def test_case_insensitive(self):
        assert _detect_language("Main.JAVA") == "java"

    def test_nested_path(self):
        assert _detect_language("a/b/c/deep.rs") == "rust"


# ---------------------------------------------------------------------------
# _detect_media_type
# ---------------------------------------------------------------------------

class TestDetectMediaType:
    """HTTP media type detection from file extension."""

    def test_png(self):
        assert _detect_media_type("screenshots/example.png") == "image/png"

    def test_svg(self):
        assert _detect_media_type("icons/example.svg") == "image/svg+xml"

    def test_unknown_extension_returns_octet_stream(self):
        assert _detect_media_type("archive.unknownext") == "application/octet-stream"


# ---------------------------------------------------------------------------
# _is_binary
# ---------------------------------------------------------------------------

class TestIsBinary:
    """Binary detection via null-byte check."""

    def test_text_content(self):
        assert _is_binary(b"Hello, world!") is False

    def test_binary_content_with_null(self):
        assert _is_binary(b"some\x00binary\x00data") is True

    def test_empty_data(self):
        assert _is_binary(b"") is False

    def test_null_at_start(self):
        assert _is_binary(b"\x00start") is True

    def test_utf8_text(self):
        assert _is_binary("你好世界".encode("utf-8")) is False

    def test_only_checks_first_8kb(self):
        # Null byte beyond 8KB boundary should not be detected
        data = b"x" * 8192 + b"\x00"
        assert _is_binary(data) is False


# ---------------------------------------------------------------------------
# _safe_subpath
# ---------------------------------------------------------------------------

class TestSafeSubpath:
    """Path traversal prevention."""

    def test_normal_subpath(self):
        result = _safe_subpath("/base/dir", "sub/file.txt")
        assert "file.txt" in result

    def test_rejects_double_dot(self):
        with pytest.raises(HTTPException) as exc_info:
            _safe_subpath("/base/dir", "../etc/passwd")
        assert exc_info.value.status_code == 400
        assert "Path traversal" in exc_info.value.detail

    def test_rejects_double_dot_in_middle(self):
        with pytest.raises(HTTPException) as exc_info:
            _safe_subpath("/base/dir", "sub/../../../etc/passwd")
        assert exc_info.value.status_code == 400

    def test_single_dot_allowed(self):
        # "." is not ".." — should be allowed
        result = _safe_subpath("/base/dir", "./file.txt")
        assert "file.txt" in result


# ---------------------------------------------------------------------------
# _should_skip_file
# ---------------------------------------------------------------------------

class TestShouldSkipFile:
    """Glob pattern matching for file exclusion."""

    def test_skip_min_js(self):
        assert _should_skip_file("jquery.min.js", ["*.min.js"]) is True

    def test_skip_min_css(self):
        assert _should_skip_file("style.min.css", ["*.min.css"]) is True

    def test_skip_map(self):
        assert _should_skip_file("bundle.js.map", ["*.map"]) is True

    def test_no_match(self):
        assert _should_skip_file("main.py", ["*.min.js", "*.min.css"]) is False

    def test_plain_dir_name_not_matched(self):
        # Plain names without glob chars should not match
        assert _should_skip_file("node_modules", ["node_modules"]) is False

    def test_question_mark_glob(self):
        assert _should_skip_file("a.txt", ["?.txt"]) is True
        assert _should_skip_file("ab.txt", ["?.txt"]) is False

    def test_empty_patterns(self):
        assert _should_skip_file("anything.js", []) is False


# ---------------------------------------------------------------------------
# _skip_dirs_from_patterns
# ---------------------------------------------------------------------------

class TestSkipDirsFromPatterns:
    """Extract directory names for os.walk pruning."""

    def test_plain_names(self):
        result = _skip_dirs_from_patterns(["node_modules", "__pycache__", ".git"])
        assert "node_modules" in result
        assert "__pycache__" in result
        assert ".git" in result

    def test_glob_patterns_excluded(self):
        result = _skip_dirs_from_patterns(["*.min.js", "*.map"])
        assert len(result) == 0

    def test_path_with_slash_excluded(self):
        result = _skip_dirs_from_patterns(["src/generated"])
        assert len(result) == 0

    def test_trailing_slash_stripped(self):
        result = _skip_dirs_from_patterns(["build/"])
        assert "build" in result

    def test_mixed_patterns(self):
        result = _skip_dirs_from_patterns(["node_modules", "*.min.js", "dist/", "src/gen"])
        assert "node_modules" in result
        assert "dist" in result
        assert len(result) == 2


# ---------------------------------------------------------------------------
# _build_pathspec_excludes
# ---------------------------------------------------------------------------

class TestBuildPathspecExcludes:
    """Convert patterns to git pathspec long-form."""

    def test_single_pattern(self):
        result = _build_pathspec_excludes(["node_modules"])
        assert result == [":(exclude)node_modules"]

    def test_multiple_patterns(self):
        result = _build_pathspec_excludes(["node_modules", "*.min.js"])
        assert result == [":(exclude)node_modules", ":(exclude)*.min.js"]

    def test_empty_list(self):
        assert _build_pathspec_excludes([]) == []


# ---------------------------------------------------------------------------
# _load_foggy_ignore
# ---------------------------------------------------------------------------

class TestLoadFoggyIgnore:
    """Reading .foggy-ignore file."""

    def test_file_not_exists(self, tmp_path):
        result = _load_foggy_ignore(str(tmp_path))
        assert result == []

    def test_reads_patterns(self, tmp_path):
        ignore_file = tmp_path / ".foggy-ignore"
        ignore_file.write_text("logs\n*.bak\ntemp/\n")
        result = _load_foggy_ignore(str(tmp_path))
        assert result == ["logs", "*.bak", "temp/"]

    def test_skips_comments_and_blanks(self, tmp_path):
        ignore_file = tmp_path / ".foggy-ignore"
        ignore_file.write_text("# This is a comment\n\nlogs\n  \n*.bak\n")
        result = _load_foggy_ignore(str(tmp_path))
        assert result == ["logs", "*.bak"]


# ---------------------------------------------------------------------------
# _get_exclude_patterns
# ---------------------------------------------------------------------------

class TestGetExcludePatterns:
    """Merge default excludes with project-level ignores."""

    def test_no_foggy_ignore(self, tmp_path):
        result = _get_exclude_patterns(str(tmp_path))
        assert result == _DEFAULT_EXCLUDES

    def test_with_foggy_ignore(self, tmp_path):
        ignore_file = tmp_path / ".foggy-ignore"
        ignore_file.write_text("custom_dir\n")
        result = _get_exclude_patterns(str(tmp_path))
        assert "custom_dir" in result
        assert "node_modules" in result  # defaults still present
        assert result == _DEFAULT_EXCLUDES + ["custom_dir"]


# ---------------------------------------------------------------------------
# _parse_git_grep_output
# ---------------------------------------------------------------------------

class TestParseGitGrepOutput:
    """Parse git grep output into structured matches."""

    def test_single_match(self):
        output = "src/main.py:10:def hello():"
        result = _parse_git_grep_output("hello", output, max_results=50, context_lines=0)
        assert result.total_matches == 1
        assert result.matches[0].file == "src/main.py"
        assert result.matches[0].line_number == 10
        assert result.matches[0].line_content == "def hello():"

    def test_multiple_matches_different_files(self):
        output = "a.py:1:hello\n--\nb.py:5:hello world"
        result = _parse_git_grep_output("hello", output, max_results=50, context_lines=0)
        assert result.total_matches == 2
        assert result.total_files == 2

    def test_context_lines(self):
        output = (
            "src/main.py-9-before line\n"
            "src/main.py:10:match line\n"
            "src/main.py-11-after line"
        )
        result = _parse_git_grep_output("match", output, max_results=50, context_lines=1)
        assert result.total_matches == 1
        m = result.matches[0]
        assert m.line_content == "match line"
        assert m.context_before == ["before line"]
        assert m.context_after == ["after line"]

    def test_max_results_limit(self):
        lines = [f"file.py:{i}:match{i}" for i in range(1, 20)]
        output = "\n--\n".join(lines)
        result = _parse_git_grep_output("match", output, max_results=3, context_lines=0)
        assert result.total_matches == 3

    def test_empty_output(self):
        result = _parse_git_grep_output("query", "", max_results=50, context_lines=0)
        assert result.total_matches == 0
        assert result.matches == []

    def test_group_separator_handling(self):
        output = "a.py:1:first\n--\na.py:10:second"
        result = _parse_git_grep_output("query", output, max_results=50, context_lines=0)
        assert result.total_matches == 2
