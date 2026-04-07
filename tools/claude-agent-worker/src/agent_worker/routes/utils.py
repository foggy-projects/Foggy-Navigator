"""Shared utilities for route modules."""

from __future__ import annotations

import asyncio
import locale
import logging
import os

from fastapi import HTTPException, status

from ..config import settings

logger = logging.getLogger(__name__)


def decode_text_bytes(raw: bytes) -> str:
    """Decode text bytes with UTF-8 first, then common local fallbacks.

    File-browser endpoints frequently encounter legacy Chinese encodings on
    Windows workspaces.  We prefer UTF-8, but fall back to the process locale
    and GB18030 before finally replacing undecodable bytes.
    """
    encodings = [
        "utf-8-sig",
        "utf-8",
        locale.getpreferredencoding(False),
        "gb18030",
        "gbk",
    ]
    tried: set[str] = set()
    for encoding in encodings:
        if not encoding:
            continue
        key = encoding.lower()
        if key in tried:
            continue
        tried.add(key)
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


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
        "git", "-c", "core.quotepath=false", *args,
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
    out = decode_text_bytes(stdout).rstrip()
    if proc.returncode != 0:
        err = decode_text_bytes(stderr).rstrip()
        # Prefer stderr (where git usually writes errors), fall back to stdout
        return proc.returncode, err or out
    return proc.returncode, out
