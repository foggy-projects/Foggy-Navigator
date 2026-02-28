"""Git log / history endpoints."""

from __future__ import annotations

import logging
import os
import re

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..models import (
    CommitDetailResponse,
    CommitFileEntry,
    CommitFileDiffResponse,
    GitLogEntry,
    GitLogResponse,
)
from .utils import run_git, validate_path

# Reuse language detection from files module
from .files import _detect_language

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["git-log"], dependencies=[Depends(verify_token)])

_HASH_RE = re.compile(r"[0-9a-fA-F]{4,40}")

# Record separator / field separator for git log parsing
_RS = "\x1e"  # record separator
_FS = "\x1f"  # field separator

_MAX_FILE_SIZE = 1 * 1024 * 1024  # 1 MB — same as files module


def _validate_hash(h: str) -> str:
    """Validate a commit hash (4-40 hex characters)."""
    if not _HASH_RE.fullmatch(h):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid commit hash — must be 4-40 hex characters",
        )
    return h


def _validate_file_param(f: str) -> str:
    """Prevent path traversal and absolute paths in file parameter."""
    # Reject absolute paths
    if f.startswith("/") or (len(f) > 1 and f[1] == ":"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File path must be relative",
        )
    # Normalize and check for traversal
    normalized = os.path.normpath(f.replace("\\", "/"))
    if ".." in normalized.split(os.sep):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Path traversal ('..') is not allowed in file parameter",
        )
    return f


# ---------------------------------------------------------------------------
# GET /api/v1/git-log
# ---------------------------------------------------------------------------

@router.get("/git-log", response_model=GitLogResponse)
async def get_git_log(
    path: str = Query(..., description="Absolute path to a git repo"),
    limit: int = Query(50, ge=1, le=200),
    skip: int = Query(0, ge=0),
) -> GitLogResponse:
    """Return paginated git log with branch ahead/behind info."""
    resolved = validate_path(path)

    # Current branch
    rc, branch = await run_git(resolved, "rev-parse", "--abbrev-ref", "HEAD")
    if rc != 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Not a git repository")

    # Upstream branch (may fail if no upstream configured)
    upstream: str | None = None
    ahead = 0
    behind = 0
    rc_up, upstream_ref = await run_git(resolved, "rev-parse", "--abbrev-ref", "@{u}")
    if rc_up == 0 and upstream_ref:
        upstream = upstream_ref
        # Ahead count
        rc_a, out_a = await run_git(resolved, "rev-list", "--count", f"{upstream_ref}..HEAD")
        if rc_a == 0 and out_a.strip().isdigit():
            ahead = int(out_a.strip())
        # Behind count
        rc_b, out_b = await run_git(resolved, "rev-list", "--count", f"HEAD..{upstream_ref}")
        if rc_b == 0 and out_b.strip().isdigit():
            behind = int(out_b.strip())

    # Git log — request limit+1 to determine has_more
    fmt = f"%x1e%H%x1f%h%x1f%an%x1f%ae%x1f%aI%x1f%ar%x1f%s%x1f%b%x1f%D"
    rc_log, log_output = await run_git(
        resolved, "log", f"--format={fmt}", f"-n{limit + 1}", f"--skip={skip}",
    )

    commits: list[GitLogEntry] = []
    has_more = False

    if rc_log == 0 and log_output:
        records = log_output.split(_RS)
        for record in records:
            record = record.strip()
            if not record:
                continue
            fields = record.split(_FS)
            if len(fields) < 7:
                continue
            commits.append(GitLogEntry(
                hash=fields[0],
                short_hash=fields[1],
                author_name=fields[2],
                author_email=fields[3],
                author_date=fields[4],
                relative_date=fields[5],
                subject=fields[6],
                body=fields[7].strip() if len(fields) > 7 else "",
                refs=fields[8].strip() if len(fields) > 8 else "",
            ))

        if len(commits) > limit:
            has_more = True
            commits = commits[:limit]

    return GitLogResponse(
        branch=branch,
        upstream=upstream,
        ahead=ahead,
        behind=behind,
        commits=commits,
        has_more=has_more,
    )


# ---------------------------------------------------------------------------
# GET /api/v1/git-log/commit
# ---------------------------------------------------------------------------

@router.get("/git-log/commit", response_model=CommitDetailResponse)
async def get_commit_detail(
    path: str = Query(..., description="Absolute path to a git repo"),
    hash: str = Query(..., description="Commit hash"),
) -> CommitDetailResponse:
    """Return commit metadata + changed files with stats."""
    resolved = validate_path(path)
    commit_hash = _validate_hash(hash)

    # Commit metadata
    fmt = f"%H%x1f%h%x1f%an%x1f%ae%x1f%aI%x1f%ar%x1f%s%x1f%b"
    rc_meta, meta_out = await run_git(resolved, "show", f"--format={fmt}", "--no-patch", commit_hash)
    if rc_meta != 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Commit not found: {hash}")

    fields = meta_out.strip().split(_FS)
    if len(fields) < 7:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail="Failed to parse commit metadata")

    # File statuses via diff-tree
    rc_dt, dt_out = await run_git(resolved, "diff-tree", "--no-commit-id", "-r", "--name-status", commit_hash)
    status_map: dict[str, str] = {}
    if rc_dt == 0 and dt_out:
        for line in dt_out.splitlines():
            parts = line.split("\t", 1)
            if len(parts) == 2:
                status_map[parts[1]] = parts[0]

    # Numstat for insertions/deletions
    rc_ns, ns_out = await run_git(resolved, "show", "--format=", "--numstat", commit_hash)
    files: list[CommitFileEntry] = []
    total_ins = 0
    total_dels = 0
    if rc_ns == 0 and ns_out:
        for line in ns_out.splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                ins = int(parts[0]) if parts[0] != "-" else 0
                dels = int(parts[1]) if parts[1] != "-" else 0
                fname = parts[2]
                # Handle renames: "old => new" or "{old => new}/path"
                if " => " in fname:
                    # Try to extract the new name
                    if "{" in fname:
                        # e.g., "src/{old.js => new.js}"
                        import re as _re
                        m = _re.search(r"\{[^}]* => ([^}]*)\}", fname)
                        if m:
                            fname = fname[:fname.index("{")] + m.group(1) + fname[fname.index("}") + 1:]
                    else:
                        fname = fname.split(" => ", 1)[1]
                file_status = status_map.get(fname, "M")
                files.append(CommitFileEntry(file=fname, status=file_status, insertions=ins, deletions=dels))
                total_ins += ins
                total_dels += dels

    return CommitDetailResponse(
        hash=fields[0],
        short_hash=fields[1],
        author_name=fields[2],
        author_email=fields[3],
        author_date=fields[4],
        relative_date=fields[5],
        subject=fields[6],
        body=fields[7].strip() if len(fields) > 7 else "",
        files=files,
        total_insertions=total_ins,
        total_deletions=total_dels,
    )


# ---------------------------------------------------------------------------
# GET /api/v1/git-log/commit/file-diff
# ---------------------------------------------------------------------------

@router.get("/git-log/commit/file-diff", response_model=CommitFileDiffResponse)
async def get_commit_file_diff(
    path: str = Query(..., description="Absolute path to a git repo"),
    hash: str = Query(..., description="Commit hash"),
    file: str = Query(..., description="Relative file path within the repo"),
) -> CommitFileDiffResponse:
    """Return parent vs commit content for a single file."""
    resolved = validate_path(path)
    commit_hash = _validate_hash(hash)
    safe_file = _validate_file_param(file)

    language = _detect_language(safe_file)

    # Determine file status in this commit
    rc_dt, dt_out = await run_git(resolved, "diff-tree", "--no-commit-id", "-r", "--name-status", commit_hash, "--", safe_file)
    file_status = "M"
    if rc_dt == 0 and dt_out:
        parts = dt_out.strip().split("\t", 1)
        if parts:
            file_status = parts[0]

    # Commit version (modified)
    modified: str | None = None
    if file_status != "D":
        rc_new, content_new = await run_git(resolved, "show", f"{commit_hash}:{safe_file}")
        if rc_new == 0:
            if len(content_new.encode("utf-8", errors="replace")) > _MAX_FILE_SIZE:
                return CommitFileDiffResponse(file=safe_file, status=file_status, language=language)
            modified = content_new

    # Parent version (original)
    original: str | None = None
    if file_status != "A":
        rc_old, content_old = await run_git(resolved, "show", f"{commit_hash}^:{safe_file}")
        if rc_old == 0:
            if len(content_old.encode("utf-8", errors="replace")) > _MAX_FILE_SIZE:
                return CommitFileDiffResponse(file=safe_file, status=file_status, language=language)
            original = content_old

    return CommitFileDiffResponse(
        file=safe_file,
        status=file_status,
        original=original,
        modified=modified,
        language=language,
    )
