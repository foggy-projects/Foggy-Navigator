"""File browsing and git diff endpoints."""

from __future__ import annotations

import logging
import os
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..models import (
    DiffFileEntry,
    DirectoryListingResponse,
    FileContentResponse,
    FileEntry,
    FileDiffResponse,
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
