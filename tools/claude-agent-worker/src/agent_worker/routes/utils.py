"""Shared utilities for route modules."""

from __future__ import annotations

import asyncio
import logging
import os

from fastapi import HTTPException, status

from ..config import settings

logger = logging.getLogger(__name__)


def validate_path(path: str) -> str:
    """Ensure *path* is inside one of the ``allowed_cwds``.

    Returns the resolved absolute path.
    Raises HTTP 403 if not allowed.
    """
    resolved = os.path.realpath(path)

    if not settings.allowed_cwds:
        return resolved

    for allowed in settings.allowed_cwds:
        allowed_resolved = os.path.realpath(allowed)
        # rstrip(os.sep) avoids double-sep when allowed is a drive root like "D:\"
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved.rstrip(os.sep) + os.sep):
            return resolved

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"Path '{path}' is not in the allowed list",
    )


async def run_git(cwd: str, *args: str) -> tuple[int, str]:
    """Run a git command and return ``(returncode, combined_output)``.

    On success (rc == 0) returns stdout; on failure returns stderr + stdout
    so that error messages from git are not lost.
    """
    proc = await asyncio.create_subprocess_exec(
        "git", *args,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=settings.git_timeout_seconds,
        )
    except asyncio.TimeoutError:
        proc.kill()
        logger.warning(
            "git %s timed out after %ss in %s",
            args[0] if args else "?", settings.git_timeout_seconds, cwd,
        )
        return -1, f"git command timed out after {settings.git_timeout_seconds}s"
    out = stdout.decode("utf-8", errors="replace").rstrip()
    if proc.returncode != 0:
        err = stderr.decode("utf-8", errors="replace").rstrip()
        # Prefer stderr (where git usually writes errors), fall back to stdout
        return proc.returncode, err or out
    return proc.returncode, out
