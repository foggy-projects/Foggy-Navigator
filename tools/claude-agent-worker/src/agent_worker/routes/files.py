"""File browsing and git diff endpoints."""

from __future__ import annotations

import fnmatch
import logging
import os
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..models import (
    ContentMatch,
    ContentSearchResponse,
    DiffFileEntry,
    DirectoryListingResponse,
    FileContentResponse,
    FileEntry,
    FileDiffResponse,
    FileSearchResponse,
    FileSearchResult,
    FoggyIgnoreRequest,
    FoggyIgnoreResponse,
    GitDiffSummaryResponse,
)
from .utils import run_git, validate_path

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["files"], dependencies=[Depends(verify_token)])

_MAX_FILE_SIZE = 1 * 1024 * 1024  # 1 MB
_MAX_DIR_ENTRIES = 2000
_BINARY_CHECK_SIZE = 8 * 1024  # 8 KB

# Extension → Monaco language mapping
_LANG_MAP: dict[str, str] = {
    ".py": "python",
    ".java": "java",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".js": "javascript",
    ".jsx": "javascript",
    ".json": "json",
    ".html": "html",
    ".htm": "html",
    ".css": "css",
    ".scss": "scss",
    ".less": "less",
    ".xml": "xml",
    ".yaml": "yaml",
    ".yml": "yaml",
    ".md": "markdown",
    ".sql": "sql",
    ".sh": "shell",
    ".bash": "shell",
    ".ps1": "powershell",
    ".bat": "bat",
    ".cmd": "bat",
    ".c": "c",
    ".cpp": "cpp",
    ".h": "c",
    ".hpp": "cpp",
    ".cs": "csharp",
    ".go": "go",
    ".rs": "rust",
    ".rb": "ruby",
    ".php": "php",
    ".swift": "swift",
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".scala": "scala",
    ".r": "r",
    ".lua": "lua",
    ".dockerfile": "dockerfile",
    ".toml": "ini",
    ".ini": "ini",
    ".cfg": "ini",
    ".properties": "ini",
    ".vue": "html",
    ".svelte": "html",
    ".graphql": "graphql",
    ".gql": "graphql",
    ".proto": "protobuf",
}


def _detect_language(filepath: str) -> str:
    """Detect Monaco editor language from file extension."""
    name = os.path.basename(filepath).lower()
    if name == "dockerfile":
        return "dockerfile"
    _, ext = os.path.splitext(name)
    return _LANG_MAP.get(ext, "plaintext")


def _is_binary(data: bytes) -> bool:
    """Check if the first chunk of data looks binary (contains null bytes)."""
    return b"\x00" in data[:_BINARY_CHECK_SIZE]


def _safe_subpath(base: str, sub: str) -> str:
    """Join base + sub and prevent path traversal."""
    if ".." in sub.replace("\\", "/").split("/"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Path traversal ('..') is not allowed",
        )
    joined = os.path.realpath(os.path.join(base, sub))
    base_resolved = os.path.realpath(base)
    if not (joined == base_resolved or joined.startswith(base_resolved + os.sep)):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Resolved path is outside the base directory",
        )
    return joined


# ---- Endpoints -----------------------------------------------------------


@router.get("/files", response_model=DirectoryListingResponse)
async def list_directory(
    path: str = Query(..., description="Absolute path to list"),
    show_hidden: bool = Query(False, description="Include hidden files/dirs"),
) -> DirectoryListingResponse:
    """List files and directories at the given path."""
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    entries: list[FileEntry] = []
    try:
        with os.scandir(resolved) as it:
            for entry in it:
                if not show_hidden and entry.name.startswith("."):
                    continue
                try:
                    stat = entry.stat(follow_symlinks=False)
                    modified = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat()
                    entries.append(FileEntry(
                        name=entry.name,
                        path=entry.path.replace("\\", "/"),
                        is_dir=entry.is_dir(follow_symlinks=False),
                        size=stat.st_size if not entry.is_dir(follow_symlinks=False) else 0,
                        modified=modified,
                    ))
                except OSError:
                    continue
                if len(entries) >= _MAX_DIR_ENTRIES:
                    break
    except PermissionError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Permission denied: {path}",
        )

    # Sort: directories first, then alphabetical
    entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))

    return DirectoryListingResponse(
        path=resolved.replace("\\", "/"),
        entries=entries,
        total=len(entries),
    )


@router.get("/files/content", response_model=FileContentResponse)
async def read_file_content(
    path: str = Query(..., description="Absolute path to the file"),
) -> FileContentResponse:
    """Read file content with binary detection and size limits."""
    resolved = validate_path(path)

    if not os.path.isfile(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a file: {path}",
        )

    file_size = os.path.getsize(resolved)
    language = _detect_language(resolved)

    if file_size > _MAX_FILE_SIZE:
        return FileContentResponse(
            path=resolved.replace("\\", "/"),
            language=language,
            size=file_size,
            too_large=True,
        )

    try:
        with open(resolved, "rb") as f:
            raw = f.read()
    except PermissionError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Permission denied: {path}",
        )

    if _is_binary(raw):
        return FileContentResponse(
            path=resolved.replace("\\", "/"),
            language=language,
            size=file_size,
            is_binary=True,
        )

    content = raw.decode("utf-8", errors="replace")
    line_count = content.count("\n") + (1 if content and not content.endswith("\n") else 0)

    return FileContentResponse(
        path=resolved.replace("\\", "/"),
        content=content,
        language=language,
        size=file_size,
        line_count=line_count,
    )


_MAX_SEARCH_RESULTS = 200

# Default exclude patterns applied to all projects
_DEFAULT_EXCLUDES: list[str] = [
    "node_modules",
    "__pycache__",
    ".venv",
    "venv",
    "target",
    "build",
    "dist",
    ".git",
    ".idea",
    ".vscode",
    "*.min.js",
    "*.min.css",
    "*.map",
]


def _load_foggy_ignore(cwd: str) -> list[str]:
    """Read .foggy-ignore from the project root and return exclude patterns."""
    ignore_path = os.path.join(cwd, ".foggy-ignore")
    if not os.path.isfile(ignore_path):
        return []
    patterns: list[str] = []
    with open(ignore_path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                patterns.append(line)
    return patterns


def _get_exclude_patterns(cwd: str) -> list[str]:
    """Merge default excludes with project .foggy-ignore rules."""
    return _DEFAULT_EXCLUDES + _load_foggy_ignore(cwd)


def _build_pathspec_excludes(patterns: list[str]) -> list[str]:
    """Convert exclude patterns to git pathspec long-form ``:(exclude)pattern``.

    The short form ``:!pattern`` breaks on Git ≤ 2.37 (Windows) when the
    pattern starts with characters that git misinterprets as pathspec magic
    letters (e.g. ``_`` in ``__pycache__``).
    """
    return [f":(exclude){p}" for p in patterns]


def _should_skip_file(filename: str, patterns: list[str]) -> bool:
    """Check if a filename matches any glob-style exclude pattern (e.g. *.min.js)."""
    for p in patterns:
        if "*" in p or "?" in p:
            if fnmatch.fnmatch(filename, p):
                return True
    return False


def _skip_dirs_from_patterns(patterns: list[str]) -> set[str]:
    """Extract plain directory names (no glob chars) from patterns for os.walk pruning."""
    dirs: set[str] = set()
    for p in patterns:
        # Strip trailing slash
        name = p.rstrip("/")
        if "*" not in name and "?" not in name and "/" not in name:
            dirs.add(name)
    return dirs


@router.get("/files/search", response_model=FileSearchResponse)
async def search_files(
    path: str = Query(..., description="Absolute path to a git repo (or directory)"),
    query: str = Query(..., min_length=1, max_length=200, description="Filename substring to search for"),
    max_results: int = Query(50, ge=1, le=_MAX_SEARCH_RESULTS),
) -> FileSearchResponse:
    """Search for files and directories by name.

    Uses ``git ls-files`` in git repos for file discovery, falls back to
    ``os.walk``.  Directories are always collected via ``os.walk`` (git does
    not track directories).  Matching directories are listed before files.
    """
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    excludes = _get_exclude_patterns(resolved)
    skip_dirs = _skip_dirs_from_patterns(excludes)

    # ------------------------------------------------------------------
    # Collect file paths (git ls-files or os.walk fallback)
    # ------------------------------------------------------------------
    git_args = ["ls-files", "--cached", "--others", "--exclude-standard", "--"]
    git_args.extend(_build_pathspec_excludes(excludes))
    rc, output = await run_git(resolved, *git_args)
    if rc == 0 and output:
        all_files = output.splitlines()
    else:
        # Fallback: os.walk (limited to 10k files to avoid hanging)
        all_files = []
        for root, dirs, files in os.walk(resolved):
            dirs[:] = [d for d in dirs if d not in skip_dirs and not d.startswith('.')]
            for fname in files:
                if _should_skip_file(fname, excludes):
                    continue
                try:
                    rel = os.path.relpath(os.path.join(root, fname), resolved).replace("\\", "/")
                except ValueError:
                    # On Windows, reserved device names (nul, con, prn, aux …)
                    # resolve to a different mount (\\.\nul) causing relpath to
                    # raise ValueError.  Skip these entries silently.
                    continue
                all_files.append(rel)
                if len(all_files) >= 10000:
                    break
            if len(all_files) >= 10000:
                break

    # ------------------------------------------------------------------
    # Collect directory paths via os.walk (git does not track dirs)
    # ------------------------------------------------------------------
    all_dirs: list[str] = []
    for root, dirs, _files in os.walk(resolved):
        dirs[:] = [d for d in dirs if d not in skip_dirs and not d.startswith('.')]
        for d in dirs:
            try:
                rel = os.path.relpath(os.path.join(root, d), resolved).replace("\\", "/")
            except ValueError:
                continue
            all_dirs.append(rel)
            if len(all_dirs) >= 10000:
                break
        if len(all_dirs) >= 10000:
            break

    # ------------------------------------------------------------------
    # Match: directories first, then files
    # ------------------------------------------------------------------
    q_lower = query.lower()
    results: list[FileSearchResult] = []

    # Directories
    for rel_path in all_dirs:
        if len(results) >= max_results:
            break
        name = rel_path.rsplit("/", 1)[-1] if "/" in rel_path else rel_path
        if q_lower in name.lower():
            abs_path = os.path.join(resolved, rel_path.replace("/", os.sep))
            modified = ""
            try:
                st = os.stat(abs_path)
                modified = datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).isoformat()
            except OSError:
                pass
            results.append(FileSearchResult(
                name=name,
                relative_path=rel_path,
                size=0,
                modified=modified,
                type="directory",
            ))

    # Files
    for rel_path in all_files:
        if len(results) >= max_results:
            break
        name = rel_path.rsplit("/", 1)[-1] if "/" in rel_path else rel_path
        if q_lower in name.lower():
            abs_path = os.path.join(resolved, rel_path.replace("/", os.sep))
            size = 0
            modified = ""
            try:
                st = os.stat(abs_path)
                size = st.st_size
                modified = datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).isoformat()
            except OSError:
                pass
            results.append(FileSearchResult(
                name=name,
                relative_path=rel_path,
                size=size,
                modified=modified,
                type="file",
            ))

    return FileSearchResponse(query=query, results=results, total=len(results))


@router.get("/files/search-content", response_model=ContentSearchResponse)
async def search_content(
    path: str = Query(..., description="Absolute path to a git repo (or directory)"),
    query: str = Query(..., min_length=1, max_length=200, description="Text to search for"),
    max_results: int = Query(50, ge=1, le=_MAX_SEARCH_RESULTS),
    context_lines: int = Query(2, ge=0, le=5),
    case_sensitive: bool = Query(False),
    file_pattern: str | None = Query(None, description="Glob pattern to filter files, e.g. '*.java'"),
) -> ContentSearchResponse:
    """Full-text search in file contents. Uses ``git grep`` in git repos, falls back to Python."""
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    # Normalize file_pattern
    fp = file_pattern.strip() if file_pattern and file_pattern.strip() else None

    # Try git grep
    excludes = _get_exclude_patterns(resolved)
    git_args = ["grep", "-n", "--no-color", "-F", f"-C{context_lines}"]
    if not case_sensitive:
        git_args.append("-i")
    git_args.append("--")
    git_args.append(query)
    if fp:
        git_args.append(fp)
    git_args.extend(_build_pathspec_excludes(excludes))

    rc, output = await run_git(resolved, *git_args)

    if rc not in (0, 1):
        # rc==1 means no matches in git grep, which is fine
        # Non-git repo or other error — fallback
        return _fallback_content_search(resolved, query, max_results, context_lines, case_sensitive, fp)

    if rc == 1 or not output:
        return ContentSearchResponse(query=query, matches=[], total_matches=0, total_files=0)

    return _parse_git_grep_output(query, output, max_results, context_lines)


def _parse_git_grep_output(
    query: str, output: str, max_results: int, context_lines: int,
) -> ContentSearchResponse:
    """Parse ``git grep -n -C<N>`` output into structured matches."""
    matches: list[ContentMatch] = []
    files_seen: set[str] = set()

    # git grep -C output uses "--" as separator between groups
    # Each line: file:linenum:content  OR  file-linenum-content (context)
    current_before: list[str] = []
    current_match: ContentMatch | None = None

    for line in output.splitlines():
        if line == "--":
            # Group separator — flush pending match
            if current_match is not None:
                matches.append(current_match)
                current_match = None
                current_before = []
                if len(matches) >= max_results:
                    break
            continue

        # Try to parse as match line (file:num:content) or context line (file-num-content)
        # Use a careful split — filename may contain colons on Windows (C:\...)
        # git grep output on Windows still uses forward-slash paths for tracked files
        parts = line.split(":", 2) if ":" in line else None
        parts_ctx = line.split("-", 2) if parts is None or len(parts) < 3 else None

        is_match_line = False
        file_path = ""
        line_num = 0
        content = ""

        if parts and len(parts) >= 3:
            try:
                line_num = int(parts[1])
                file_path = parts[0]
                content = parts[2]
                is_match_line = True
            except ValueError:
                pass

        if not is_match_line and parts_ctx and len(parts_ctx) >= 3:
            try:
                line_num = int(parts_ctx[1])
                file_path = parts_ctx[0]
                content = parts_ctx[2]
            except ValueError:
                continue

        if not file_path:
            continue

        files_seen.add(file_path)

        if is_match_line:
            if current_match is not None:
                matches.append(current_match)
                if len(matches) >= max_results:
                    break
            current_match = ContentMatch(
                file=file_path,
                line_number=line_num,
                line_content=content,
                context_before=list(current_before),
            )
            current_before = []
        else:
            # Context line
            if current_match is not None:
                current_match.context_after.append(content)
            else:
                current_before.append(content)
                if len(current_before) > context_lines:
                    current_before.pop(0)

    # Flush last match
    if current_match is not None and len(matches) < max_results:
        matches.append(current_match)

    return ContentSearchResponse(
        query=query,
        matches=matches,
        total_matches=len(matches),
        total_files=len(files_seen),
    )


def _fallback_content_search(
    base_dir: str, query: str, max_results: int, context_lines: int, case_sensitive: bool,
    file_pattern: str | None = None,
) -> ContentSearchResponse:
    """Pure-Python fallback for non-git repos."""
    matches: list[ContentMatch] = []
    files_seen: set[str] = set()
    q = query if case_sensitive else query.lower()
    excludes = _get_exclude_patterns(base_dir)
    skip_dirs = _skip_dirs_from_patterns(excludes)

    for root, dirs, files in os.walk(base_dir):
        dirs[:] = [d for d in dirs if d not in skip_dirs and not d.startswith('.')]
        for fname in files:
            if _should_skip_file(fname, excludes):
                continue
            if file_pattern and not fnmatch.fnmatch(fname, file_pattern):
                continue
            abs_path = os.path.join(root, fname)
            try:
                rel_path = os.path.relpath(abs_path, base_dir).replace("\\", "/")
            except ValueError:
                # Windows reserved device names (nul, con, …) → skip
                continue

            try:
                with open(abs_path, "r", encoding="utf-8", errors="ignore") as fh:
                    lines = fh.readlines()
            except (OSError, UnicodeDecodeError):
                continue

            for i, line in enumerate(lines):
                cmp_line = line if case_sensitive else line.lower()
                if q in cmp_line:
                    files_seen.add(rel_path)
                    before = [l.rstrip("\n\r") for l in lines[max(0, i - context_lines):i]]
                    after = [l.rstrip("\n\r") for l in lines[i + 1:i + 1 + context_lines]]
                    matches.append(ContentMatch(
                        file=rel_path,
                        line_number=i + 1,
                        line_content=line.rstrip("\n\r"),
                        context_before=before,
                        context_after=after,
                    ))
                    if len(matches) >= max_results:
                        return ContentSearchResponse(
                            query=query, matches=matches,
                            total_matches=len(matches), total_files=len(files_seen),
                        )

    return ContentSearchResponse(
        query=query, matches=matches,
        total_matches=len(matches), total_files=len(files_seen),
    )


@router.get("/files/ignore", response_model=FoggyIgnoreResponse)
async def get_foggy_ignore(
    path: str = Query(..., description="Absolute path to the project root"),
) -> FoggyIgnoreResponse:
    """Return current custom patterns from .foggy-ignore."""
    resolved = validate_path(path)
    patterns = _load_foggy_ignore(resolved)
    return FoggyIgnoreResponse(patterns=patterns)


@router.post("/files/ignore", response_model=FoggyIgnoreResponse)
async def add_foggy_ignore(req: FoggyIgnoreRequest) -> FoggyIgnoreResponse:
    """Add a pattern to .foggy-ignore."""
    resolved = validate_path(req.path)
    ignore_path = os.path.join(resolved, ".foggy-ignore")

    existing = _load_foggy_ignore(resolved)
    if req.pattern in existing:
        return FoggyIgnoreResponse(patterns=existing)

    # Append the new pattern
    with open(ignore_path, "a", encoding="utf-8") as f:
        # Ensure we start on a new line
        if os.path.isfile(ignore_path) and os.path.getsize(ignore_path) > 0:
            with open(ignore_path, "rb") as check:
                check.seek(-1, 2)
                if check.read(1) != b"\n":
                    f.write("\n")
        f.write(req.pattern + "\n")

    return FoggyIgnoreResponse(patterns=_load_foggy_ignore(resolved))


@router.delete("/files/ignore", response_model=FoggyIgnoreResponse)
async def remove_foggy_ignore(req: FoggyIgnoreRequest) -> FoggyIgnoreResponse:
    """Remove a pattern from .foggy-ignore."""
    resolved = validate_path(req.path)
    ignore_path = os.path.join(resolved, ".foggy-ignore")

    if not os.path.isfile(ignore_path):
        return FoggyIgnoreResponse(patterns=[])

    # Read all lines, preserving comments and blanks
    with open(ignore_path, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()

    # Filter out the matching pattern
    new_lines = [line for line in lines if line.strip() != req.pattern]

    # Check if there are any non-blank, non-comment lines left
    has_patterns = any(l.strip() and not l.strip().startswith("#") for l in new_lines)
    if not has_patterns:
        os.remove(ignore_path)
        return FoggyIgnoreResponse(patterns=[])

    with open(ignore_path, "w", encoding="utf-8") as f:
        f.writelines(new_lines)

    return FoggyIgnoreResponse(patterns=_load_foggy_ignore(resolved))


@router.get("/git-diff", response_model=GitDiffSummaryResponse)
async def get_git_diff_summary(
    path: str = Query(..., description="Absolute path to a git repo"),
) -> GitDiffSummaryResponse:
    """Return a summary of changed files (staged + unstaged + untracked)."""
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    # Check git repo
    rc, _ = await run_git(resolved, "rev-parse", "--is-inside-work-tree")
    if rc != 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a git repository",
        )

    # Get branch
    _, branch = await run_git(resolved, "rev-parse", "--abbrev-ref", "HEAD")

    # git status --porcelain for file list + status
    rc_status, porcelain = await run_git(resolved, "status", "--porcelain")
    if rc_status != 0:
        return GitDiffSummaryResponse(path=resolved.replace("\\", "/"), branch=branch or None, files=[])

    files: list[DiffFileEntry] = []
    status_files: set[str] = set()

    for line in porcelain.splitlines():
        if len(line) < 4:
            continue
        st = line[:2].strip()
        filepath = line[3:].strip()
        # Handle renames: "R  old -> new"
        if " -> " in filepath:
            filepath = filepath.split(" -> ", 1)[1]
        status_files.add(filepath)
        files.append(DiffFileEntry(file=filepath, status=st))

    # git diff --numstat for insertion/deletion counts
    rc_num, numstat = await run_git(resolved, "diff", "--numstat")
    if rc_num == 0 and numstat:
        for line in numstat.splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                ins = int(parts[0]) if parts[0] != "-" else 0
                dels = int(parts[1]) if parts[1] != "-" else 0
                fname = parts[2]
                for f in files:
                    if f.file == fname:
                        f.insertions = ins
                        f.deletions = dels
                        break

    # Also get staged numstat
    rc_staged, staged_numstat = await run_git(resolved, "diff", "--cached", "--numstat")
    if rc_staged == 0 and staged_numstat:
        for line in staged_numstat.splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                ins = int(parts[0]) if parts[0] != "-" else 0
                dels = int(parts[1]) if parts[1] != "-" else 0
                fname = parts[2]
                for f in files:
                    if f.file == fname:
                        f.insertions += ins
                        f.deletions += dels
                        break

    total_ins = sum(f.insertions for f in files)
    total_dels = sum(f.deletions for f in files)

    return GitDiffSummaryResponse(
        path=resolved.replace("\\", "/"),
        branch=branch or None,
        files=files,
        total_insertions=total_ins,
        total_deletions=total_dels,
    )


@router.get("/git-diff/file", response_model=FileDiffResponse)
async def get_file_diff(
    path: str = Query(..., description="Absolute path to a git repo"),
    file: str = Query(..., description="Relative file path within the repo"),
) -> FileDiffResponse:
    """Return original (HEAD) and modified (working tree) versions of a file."""
    resolved = validate_path(path)

    # Prevent path traversal in file param
    if ".." in file.replace("\\", "/").split("/"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Path traversal ('..') is not allowed in file parameter",
        )

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    language = _detect_language(file)

    # Determine file status
    rc_st, porcelain = await run_git(resolved, "status", "--porcelain", "--", file)
    file_status = "M"
    if rc_st == 0 and porcelain:
        file_status = porcelain[:2].strip()

    # Get HEAD version
    original: str | None = None
    if file_status != "??" and file_status != "A":
        rc_show, content = await run_git(resolved, "show", f"HEAD:{file}")
        if rc_show == 0:
            original = content

    # Get working tree version
    modified: str | None = None
    abs_file = os.path.join(resolved, file.replace("/", os.sep))
    if os.path.isfile(abs_file):
        try:
            file_size = os.path.getsize(abs_file)
            if file_size <= _MAX_FILE_SIZE:
                with open(abs_file, "rb") as f:
                    raw = f.read()
                if not _is_binary(raw):
                    modified = raw.decode("utf-8", errors="replace").rstrip()
        except OSError:
            pass

    return FileDiffResponse(
        file=file,
        status=file_status,
        original=original,
        modified=modified,
        language=language,
    )
