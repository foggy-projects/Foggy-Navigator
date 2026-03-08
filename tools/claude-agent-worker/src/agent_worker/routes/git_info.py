"""Git repository info endpoint."""

from __future__ import annotations

import os

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..models import GitInfoResponse
from .utils import validate_path, run_git

router = APIRouter(prefix="/api/v1", tags=["git"], dependencies=[Depends(verify_token)])


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
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    # Check if it's a git repo
    rc, _ = await run_git(resolved, "rev-parse", "--is-inside-work-tree")
    if rc != 0:
        return GitInfoResponse(path=resolved, is_git_repo=False)

    # Get branch
    _, branch = await run_git(resolved, "rev-parse", "--abbrev-ref", "HEAD")

    # Get remote URL
    _, remote_url = await run_git(resolved, "config", "--get", "remote.origin.url")
    if not remote_url:
        remote_url = None

    # Get status
    rc_status, porcelain = await run_git(resolved, "status", "--porcelain")
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
