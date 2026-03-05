"""Git worktree management endpoints."""

from __future__ import annotations

import logging
import os

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..auth import verify_token
from ..config import settings
from ..models import CreateWorktreeRequest, CreateWorktreeResponse, RemoveWorktreeRequest, WorktreeInfo
from .utils import validate_path, run_git

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1", tags=["worktree"], dependencies=[Depends(verify_token)])


@router.get("/worktrees", response_model=list[WorktreeInfo])
async def list_worktrees(path: str = Query(..., description="Absolute path to the repo")) -> list[WorktreeInfo]:
    """List all git worktrees for the given repository."""
    resolved = validate_path(path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {path}",
        )

    rc, output = await run_git(resolved, "worktree", "list", "--porcelain")
    if rc != 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a git repository or git worktree list failed",
        )

    worktrees: list[WorktreeInfo] = []
    current: dict[str, str] = {}

    for line in output.split("\n"):
        line = line.strip()
        if not line:
            if current:
                wt_path = current.get("worktree", "")
                branch = current.get("branch", "").replace("refs/heads/", "")
                is_main = "bare" not in current and wt_path == resolved
                worktrees.append(WorktreeInfo(path=wt_path, branch=branch, is_main=is_main))
                current = {}
            continue

        if line.startswith("worktree "):
            current["worktree"] = line[len("worktree "):]
        elif line.startswith("branch "):
            current["branch"] = line[len("branch "):]
        elif line == "bare":
            current["bare"] = "true"

    # Handle last entry if no trailing blank line
    if current:
        wt_path = current.get("worktree", "")
        branch = current.get("branch", "").replace("refs/heads/", "")
        is_main = "bare" not in current and wt_path == resolved
        worktrees.append(WorktreeInfo(path=wt_path, branch=branch, is_main=is_main))

    return worktrees


@router.post("/worktrees", response_model=CreateWorktreeResponse)
async def create_worktree(request: CreateWorktreeRequest) -> CreateWorktreeResponse:
    """Create a new git worktree."""
    resolved = validate_path(request.repo_path)

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Path is not a directory: {request.repo_path}",
        )

    # Determine worktree path
    if request.worktree_path:
        wt_path = os.path.realpath(request.worktree_path)
    else:
        # Auto-generate: sibling directory named <repo>-worktree-<branch>
        parent = os.path.dirname(resolved)
        repo_name = os.path.basename(resolved)
        safe_branch = request.branch.replace("/", "-").replace("\\", "-")
        wt_path = os.path.join(parent, f"{repo_name}-wt-{safe_branch}")

    if os.path.exists(wt_path):
        # Check if it's already a valid git worktree for the same branch — reuse it
        rc_check, branch_out = await run_git(wt_path, "rev-parse", "--abbrev-ref", "HEAD")
        if rc_check == 0 and branch_out.strip() == request.branch:
            logger.info("Reusing existing worktree: path=%s, branch=%s", wt_path, request.branch)
            return CreateWorktreeResponse(path=wt_path, branch=request.branch)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Worktree path already exists (branch mismatch or not a git repo): {wt_path}",
        )

    # Strategy:
    # 1. Try checking out as existing local branch first
    # 2. If not found locally, fetch from remote and checkout with tracking
    # 3. If remote doesn't have it either, create a new branch from HEAD

    rc, output = await run_git(resolved, "worktree", "add", wt_path, request.branch)
    if rc != 0:
        # Local branch not found — try to fetch from remote and set up tracking
        remote_ref = f"origin/{request.branch}"
        fetch_rc, fetch_out = await run_git(resolved, "fetch", "origin", request.branch)
        if fetch_rc == 0:
            # Remote branch fetched — create local tracking branch in the worktree
            rc, output = await run_git(
                resolved, "worktree", "add", "--track", "-b", request.branch, wt_path, remote_ref
            )
        if rc != 0:
            # Branch doesn't exist remotely either — create new branch from HEAD
            new_rc, new_out = await run_git(resolved, "worktree", "add", "-b", request.branch, wt_path)
            if new_rc == 0:
                rc, output = new_rc, new_out
            else:
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail=f"git worktree add failed: {output or new_out}",
                )

    logger.info("Created worktree: path=%s, branch=%s", wt_path, request.branch)
    return CreateWorktreeResponse(path=wt_path, branch=request.branch)


@router.delete("/worktrees")
async def remove_worktree(request: RemoveWorktreeRequest) -> dict[str, str]:
    """Remove a git worktree."""
    resolved = os.path.realpath(request.worktree_path)

    # Validate that the worktree path is in allowed_cwds (or unrestricted)
    if settings.allowed_cwds:
        allowed = False
        for cwd in settings.allowed_cwds:
            allowed_resolved = os.path.realpath(cwd)
            # rstrip(os.sep) avoids double-sep when allowed is a drive root like "D:\"
            if resolved == allowed_resolved or resolved.startswith(allowed_resolved.rstrip(os.sep) + os.sep):
                allowed = True
                break
            # Also allow sibling directories (worktrees are typically siblings)
            parent = os.path.dirname(allowed_resolved)
            if resolved.startswith(parent.rstrip(os.sep) + os.sep):
                allowed = True
                break
        if not allowed:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Path '{request.worktree_path}' is not allowed",
            )

    if not os.path.isdir(resolved):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Worktree path does not exist: {request.worktree_path}",
        )

    # Find the main repo to run git worktree remove from
    rc, main_repo = await run_git(resolved, "rev-parse", "--git-common-dir")
    if rc != 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Not a git worktree",
        )

    # The common dir points to .git, go up one level for the main repo
    main_repo_path = os.path.dirname(os.path.realpath(main_repo)) if main_repo.endswith(".git") else os.path.dirname(main_repo)
    # If it's inside a .git directory (e.g. /path/to/repo/.git), use the parent
    if os.path.basename(main_repo_path) == ".git":
        main_repo_path = os.path.dirname(main_repo_path)

    rc, output = await run_git(main_repo_path, "worktree", "remove", "--force", resolved)
    if rc != 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"git worktree remove failed: {output}",
        )

    logger.info("Removed worktree: path=%s", resolved)
    return {"status": "removed", "path": resolved}
