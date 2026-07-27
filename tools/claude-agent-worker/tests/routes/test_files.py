"""Unit tests for routes/files.py — helper functions and private logic."""

from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import MagicMock, mock_open, patch

import pytest
from fastapi import HTTPException

import agent_worker.routes.files as files_routes
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


def _symlink_or_skip(target: Path, link: Path, *, target_is_directory: bool = False) -> None:
    try:
        os.symlink(str(target), str(link), target_is_directory=target_is_directory)
    except (OSError, NotImplementedError) as exc:
        pytest.skip(f"symlink creation is not available: {exc}")


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
# read_file_content
# ---------------------------------------------------------------------------

class TestReadFileContent:
    """File content preview size limits."""

    @pytest.mark.asyncio
    async def test_allows_text_file_up_to_10mb(self, tmp_path, monkeypatch):
        file_path = tmp_path / "large.txt"
        file_path.write_bytes(b"a" * files_routes._MAX_FILE_SIZE)
        monkeypatch.setattr(files_routes, "validate_path", lambda path: path)

        result = await files_routes.read_file_content(str(file_path))

        assert result.too_large is False
        assert result.is_binary is False
        assert result.size == files_routes._MAX_FILE_SIZE
        assert len(result.content or "") == files_routes._MAX_FILE_SIZE

    @pytest.mark.asyncio
    async def test_marks_text_file_over_10mb_as_too_large(self, tmp_path, monkeypatch):
        file_path = tmp_path / "too-large.txt"
        file_path.write_bytes(b"a" * (files_routes._MAX_FILE_SIZE + 1))
        monkeypatch.setattr(files_routes, "validate_path", lambda path: path)

        result = await files_routes.read_file_content(str(file_path))

        assert result.too_large is True
        assert result.content is None
        assert result.size == files_routes._MAX_FILE_SIZE + 1


# ---------------------------------------------------------------------------
# list_directory
# ---------------------------------------------------------------------------

class TestListDirectoryLinks:
    """Symlink and junction handling in directory listings."""

    @pytest.mark.asyncio
    async def test_resolved_directory_keeps_requested_logical_child_paths(self, tmp_path, monkeypatch):
        target = tmp_path / "target"
        logical = tmp_path / "linked-dir"
        target.mkdir()
        (target / "child.txt").write_text("hello", encoding="utf-8")

        def fake_validate_path(path: str) -> str:
            if os.path.normcase(os.path.normpath(path)) == os.path.normcase(os.path.normpath(str(logical))):
                return str(target)
            return os.path.realpath(path)

        monkeypatch.setattr(files_routes, "validate_path", fake_validate_path)

        listing = await files_routes.list_directory(str(logical), show_hidden=False)
        child = next(entry for entry in listing.entries if entry.name == "child.txt")

        assert listing.path == str(logical).replace("\\", "/")
        assert child.path == str(logical / "child.txt").replace("\\", "/")

    @pytest.mark.asyncio
    async def test_link_metadata_uses_allowed_target_without_real_symlink(self, tmp_path, monkeypatch):
        root = tmp_path / "root"
        target = tmp_path / "target"
        link_marker = root / "linked-dir"
        root.mkdir()
        target.mkdir()
        link_marker.mkdir()

        link_marker_resolved = os.path.normcase(os.path.realpath(link_marker))

        def fake_is_link(path: str) -> bool:
            return os.path.normcase(os.path.realpath(path)) == link_marker_resolved

        def fake_validate_path(path: str) -> str:
            if fake_is_link(path):
                return str(target)
            return os.path.realpath(path)

        monkeypatch.setattr(files_routes, "_is_link_path", fake_is_link)
        monkeypatch.setattr(files_routes, "validate_path", fake_validate_path)

        listing = await files_routes.list_directory(str(root), show_hidden=False)
        node = next(entry for entry in listing.entries if entry.name == "linked-dir")

        assert node.is_symlink is True
        assert node.is_dir is True
        assert node.target_exists is True
        assert node.target_is_dir is True
        assert node.target_allowed is True
        assert node.link_target == str(target).replace("\\", "/")

    @pytest.mark.asyncio
    async def test_disallowed_link_target_is_not_expandable_without_real_symlink(self, tmp_path, monkeypatch):
        root = tmp_path / "root"
        link_marker = root / "outside-link"
        root.mkdir()
        link_marker.mkdir()

        link_marker_resolved = os.path.normcase(os.path.realpath(link_marker))

        def fake_is_link(path: str) -> bool:
            return os.path.normcase(os.path.realpath(path)) == link_marker_resolved

        def fake_validate_path(path: str) -> str:
            if fake_is_link(path):
                raise HTTPException(status_code=403, detail="blocked")
            return os.path.realpath(path)

        monkeypatch.setattr(files_routes, "_is_link_path", fake_is_link)
        monkeypatch.setattr(files_routes, "validate_path", fake_validate_path)

        listing = await files_routes.list_directory(str(root), show_hidden=False)
        node = next(entry for entry in listing.entries if entry.name == "outside-link")

        assert node.is_symlink is True
        assert node.is_dir is False
        assert node.target_exists is True
        assert node.target_is_dir is False
        assert node.target_allowed is False
        assert node.link_target is None

    @pytest.mark.asyncio
    async def test_symlink_directory_is_expandable_with_logical_child_paths(self, tmp_path, monkeypatch):
        root = tmp_path / "root"
        target = root / "target"
        link = root / "linked-dir"
        root.mkdir()
        target.mkdir()
        (target / "child.txt").write_text("hello", encoding="utf-8")
        _symlink_or_skip(target, link, target_is_directory=True)

        monkeypatch.setattr(files_routes, "validate_path", lambda path: os.path.realpath(path))

        listing = await files_routes.list_directory(str(root), show_hidden=False)
        node = next(entry for entry in listing.entries if entry.name == "linked-dir")

        assert node.is_symlink is True
        assert node.is_dir is True
        assert node.target_exists is True
        assert node.target_is_dir is True
        assert node.target_allowed is True
        assert node.link_target == os.path.realpath(link).replace("\\", "/")
        assert node.path == str(link).replace("\\", "/")

        linked_listing = await files_routes.list_directory(str(link), show_hidden=False)
        child = next(entry for entry in linked_listing.entries if entry.name == "child.txt")

        assert linked_listing.path == str(link).replace("\\", "/")
        assert child.path == str(link / "child.txt").replace("\\", "/")
        assert child.is_symlink is False

    @pytest.mark.asyncio
    async def test_disallowed_symlink_target_is_visible_but_not_expandable(self, tmp_path, monkeypatch):
        root = tmp_path / "root"
        outside = tmp_path / "outside"
        link = root / "outside-link"
        root.mkdir()
        outside.mkdir()
        _symlink_or_skip(outside, link, target_is_directory=True)

        outside_resolved = os.path.normcase(os.path.realpath(outside))

        def fake_validate_path(path: str) -> str:
            resolved = os.path.realpath(path)
            if os.path.normcase(resolved) == outside_resolved:
                raise HTTPException(status_code=403, detail="blocked")
            return resolved

        monkeypatch.setattr(files_routes, "validate_path", fake_validate_path)

        listing = await files_routes.list_directory(str(root), show_hidden=False)
        node = next(entry for entry in listing.entries if entry.name == "outside-link")

        assert node.is_symlink is True
        assert node.is_dir is False
        assert node.target_exists is True
        assert node.target_is_dir is False
        assert node.target_allowed is False
        assert node.link_target is None


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
# searchable file discovery
# ---------------------------------------------------------------------------

class TestSearchableFileDiscovery:
    """Git-backed search must include files owned by nested repositories."""

    @pytest.mark.asyncio
    async def test_search_finds_untracked_file_in_nested_git_repository(self, tmp_path, monkeypatch):
        nested_repo = tmp_path / "foggy-data-mcp-bridge"
        target = nested_repo / "docs" / "9.5.2" / "prototype" / "runtime-console-prototype.html"
        (nested_repo / ".git").mkdir(parents=True)
        target.parent.mkdir(parents=True)
        target.write_text("prototype", encoding="utf-8")
        (tmp_path / "README.md").write_text("root", encoding="utf-8")

        async def fake_run_git(cwd: str, *args: str) -> tuple[int, str]:
            assert args[:4] == ("ls-files", "--cached", "--others", "--exclude-standard")
            if Path(cwd) == tmp_path:
                return 0, "README.md"
            if Path(cwd) == nested_repo:
                return 0, "docs/9.5.2/prototype/runtime-console-prototype.html"
            raise AssertionError(f"unexpected git cwd: {cwd}")

        monkeypatch.setattr(files_routes, "validate_path", lambda path: path)
        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes.search_files(
            path=str(tmp_path),
            query="runtime-console-prototype.html",
            max_results=80,
        )

        assert result.total == 1
        assert result.results[0].relative_path == (
            "foggy-data-mcp-bridge/docs/9.5.2/prototype/runtime-console-prototype.html"
        )

    @pytest.mark.asyncio
    async def test_foggy_ignore_can_exclude_nested_git_repository(self, tmp_path, monkeypatch):
        nested_repo = tmp_path / "ignored-repo"
        (nested_repo / ".git").mkdir(parents=True)
        (nested_repo / "hidden.txt").write_text("hidden", encoding="utf-8")
        (tmp_path / ".foggy-ignore").write_text("ignored-repo/\n", encoding="utf-8")

        calls: list[Path] = []

        async def fake_run_git(cwd: str, *_args: str) -> tuple[int, str]:
            calls.append(Path(cwd))
            return 0, ""

        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes._collect_searchable_files(
            str(tmp_path),
            _get_exclude_patterns(str(tmp_path)),
        )

        assert result == []
        assert calls == [tmp_path]

    @pytest.mark.asyncio
    async def test_nested_path_foggy_ignore_excludes_repository(self, tmp_path, monkeypatch):
        nested_repo = tmp_path / "groups" / "private-repo"
        (nested_repo / ".git").mkdir(parents=True)
        (nested_repo / "hidden.txt").write_text("hidden", encoding="utf-8")
        (tmp_path / ".foggy-ignore").write_text("groups/private-repo/\n", encoding="utf-8")

        calls: list[Path] = []

        async def fake_run_git(cwd: str, *_args: str) -> tuple[int, str]:
            calls.append(Path(cwd))
            return 0, ""

        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes._collect_searchable_files(
            str(tmp_path),
            _get_exclude_patterns(str(tmp_path)),
        )

        assert result == []
        assert calls == [tmp_path]

    @pytest.mark.asyncio
    async def test_nested_file_foggy_ignore_uses_project_relative_path(self, tmp_path, monkeypatch):
        nested_repo = tmp_path / "groups" / "private-repo"
        (nested_repo / ".git").mkdir(parents=True)
        (nested_repo / "docs").mkdir()
        (nested_repo / "docs" / "secret.md").write_text("secret", encoding="utf-8")
        (nested_repo / "docs" / "visible.md").write_text("visible", encoding="utf-8")
        (tmp_path / ".foggy-ignore").write_text(
            "groups/private-repo/docs/secret.md\n",
            encoding="utf-8",
        )

        async def fake_run_git(cwd: str, *_args: str) -> tuple[int, str]:
            if Path(cwd) == tmp_path:
                return 0, ""
            if Path(cwd) == nested_repo:
                return 0, "docs/secret.md\ndocs/visible.md"
            raise AssertionError(f"unexpected git cwd: {cwd}")

        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes._collect_searchable_files(
            str(tmp_path),
            _get_exclude_patterns(str(tmp_path)),
        )

        assert result == ["groups/private-repo/docs/visible.md"]

    @pytest.mark.asyncio
    async def test_nested_git_discovery_prunes_junction_like_directories(self, tmp_path, monkeypatch):
        nested_repo = tmp_path / "junction-repo"
        (nested_repo / ".git").mkdir(parents=True)
        (nested_repo / "outside.txt").write_text("outside", encoding="utf-8")

        calls: list[Path] = []

        async def fake_run_git(cwd: str, *_args: str) -> tuple[int, str]:
            calls.append(Path(cwd))
            if Path(cwd) == tmp_path:
                return 0, ""
            raise AssertionError(f"junction-like path must not be searched: {cwd}")

        original_is_link_path = files_routes._is_link_path
        monkeypatch.setattr(
            files_routes,
            "_is_link_path",
            lambda path: Path(path) == nested_repo or original_is_link_path(path),
        )
        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes._collect_searchable_files(
            str(tmp_path),
            _get_exclude_patterns(str(tmp_path)),
        )

        assert result == []
        assert calls == [tmp_path]


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


# ---------------------------------------------------------------------------
# search_content
# ---------------------------------------------------------------------------

class TestSearchContentEncodingRegression:
    """Regression coverage for mixed-encoding content search."""

    @pytest.mark.asyncio
    async def test_decodes_each_git_file_independently(self, tmp_path, monkeypatch):
        utf8_file = tmp_path / "utf8-file.txt"
        gbk_file = tmp_path / "gbk文件.txt"
        utf8_file.write_text("全球有哪些国家在“猛推”HPV疫苗？\n第二行\n", encoding="utf-8")
        gbk_file.write_bytes("全球有哪些国家在“猛推”HPV疫苗？\n第二行\n".encode("gbk"))

        async def fake_run_git(cwd: str, *args: str) -> tuple[int, str]:
            assert Path(cwd) == tmp_path
            assert args[:4] == ("ls-files", "--cached", "--others", "--exclude-standard")
            return 0, "gbk文件.txt\nutf8-file.txt"

        monkeypatch.setattr(files_routes, "validate_path", lambda path: path)
        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes.search_content(
            path=str(tmp_path),
            query="HPV",
            max_results=10,
            context_lines=1,
            case_sensitive=False,
            file_pattern=None,
        )

        assert result.total_matches == 2
        assert result.total_files == 2
        assert [match.file for match in result.matches] == ["gbk文件.txt", "utf8-file.txt"]
        assert result.matches[0].line_content == "全球有哪些国家在“猛推”HPV疫苗？"
        assert result.matches[0].context_after == ["第二行"]
        assert result.matches[1].line_content == "全球有哪些国家在“猛推”HPV疫苗？"

    @pytest.mark.asyncio
    async def test_respects_file_pattern_for_relative_paths(self, tmp_path, monkeypatch):
        java_file = tmp_path / "src" / "Main.java"
        py_file = tmp_path / "scripts" / "task.py"
        java_file.parent.mkdir()
        py_file.parent.mkdir()
        java_file.write_text("String value = \"HPV\";\n", encoding="utf-8")
        py_file.write_text("HPV = 'value'\n", encoding="utf-8")

        async def fake_run_git(_cwd: str, *_args: str) -> tuple[int, str]:
            return 0, "src/Main.java\nscripts/task.py"

        monkeypatch.setattr(files_routes, "validate_path", lambda path: path)
        monkeypatch.setattr(files_routes, "run_git", fake_run_git)

        result = await files_routes.search_content(
            path=str(tmp_path),
            query="HPV",
            max_results=10,
            context_lines=0,
            case_sensitive=False,
            file_pattern="src/*.java",
        )

        assert result.total_matches == 1
        assert result.matches[0].file == "src/Main.java"
