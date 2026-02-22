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
        if resolved == allowed_resolved or resolved.startswith(allowed_resolved + os.sep):
            return resolved

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=f"Path '{path}' is not in the allowed list",
    )


async def run_git(cwd: str, *args: str) -> tuple[int, str]:
    """Run a git command and return ``(returncode, stdout)``."""
    proc = await asyncio.create_subprocess_exec(
        "git", *args,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, _ = await asyncio.wait_for(
            proc.communicate(), timeout=settings.git_timeout_seconds,
        )
    except asyncio.TimeoutError:
        proc.kill()
        logger.warning(
            "git %s timed out after %ss in %s",
            args[0] if args else "?", settings.git_timeout_seconds, cwd,
        )
        return -1, f"git command timed out after {settings.git_timeout_seconds}s"
    return proc.returncode, stdout.decode("utf-8", errors="replace").rstrip()
