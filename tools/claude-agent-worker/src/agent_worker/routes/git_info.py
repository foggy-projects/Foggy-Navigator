"""Git repository info endpoint."""

from __future__ import annotations

import asyncio
import logging
import os

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..config import settings
from ..models import GitInfoResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["git"], dependencies=[Depends(verify_token)])


def _validate_path(path: str) -> str:
    """Ensure *path* is inside one of the ``allowed_cwds``.

    Reuses the same security logic as ``query._validate_cwd``.
    """
    resolved = os.path.realpath(path)

    if not settings.allowed_cwds:
        return resolved

    for allowed in settings.allowed_cwds:
        allowed_resolved = os.path.realpath(allowed)
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved + os.sep):
            return resolved

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"Path '{path}' is not in the allowed list",
    )


async def _run_git(cwd: str, *args: str) -> tuple[int, str]:
    """Run a git command and return ``(returncode, stdout)``."""
    proc = await asyncio.create_subprocess_exec(
        "git", *args,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, _ = await proc.communicate()
    return proc.returncode, stdout.decode("utf-8", errors="replace").strip()


def _infer_provider(remote_url: str | None) -> str:
    if not remote_url:
        return "OTHER"
    url = remote_url.lower()
    if "github.com" in url:
        return "GITHUB"
    if "gitlab" in url:
        return "GITLAB"
    if "gitee.com" in url:
        return "GITEE"
    return "OTHER"


@router.get("/git-info", response_model=GitInfoResponse)
async def get_git_info(path: str = Query(..., description="Absolute path to check")) -> GitInfoResponse:
    """Return Git repository information for the given path."""
    resolved = _validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    # Check if it's a git repo
    rc, _ = await _run_git(resolved, "rev-parse", "--is-inside-work-tree")
    if rc != 0:
        return GitInfoResponse(path=resolved, is_git_repo=False)

    # Get branch
    _, branch = await _run_git(resolved, "rev-parse", "--abbrev-ref", "HEAD")

    # Get remote URL
    _, remote_url = await _run_git(resolved, "config", "--get", "remote.origin.url")
    if not remote_url:
        remote_url = None

    # Get status
    rc_status, porcelain = await _run_git(resolved, "status", "--porcelain")
    if rc_status == 0:
        git_status = "clean" if not porcelain else "dirty"
    else:
        git_status = "unknown"

    return GitInfoResponse(
        path=resolved,
        is_git_repo=True,
        branch=branch or None,
        remote_url=remote_url,
        status=git_status,
        provider=_infer_provider(remote_url),
    )
