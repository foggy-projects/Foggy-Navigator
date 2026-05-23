"""Git-based public Skill synchronization.

Clones or pulls a GitLab repository into ``skills/public/``.
Designed for a single-repo-all-skills layout (Doc 34 §4.1).
"""

from __future__ import annotations

import logging
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)


@dataclass
class SyncResult:
    """Outcome of a git sync operation."""

    success: bool
    message: str
    skills_found: list[str] = field(default_factory=list)


def _run_git(args: list[str], cwd: Path | None = None, token: str = "") -> subprocess.CompletedProcess:
    """Run a git command, injecting token into HTTPS URLs via env."""
    env = None
    if token:
        # Use GIT_ASKPASS trick to inject token non-interactively
        import os
        import tempfile
        helper = tempfile.NamedTemporaryFile(mode="w", suffix=".sh", delete=False)
        helper.write(f"#!/bin/sh\necho {token}\n")
        helper.close()
        os.chmod(helper.name, 0o700)
        env = {**os.environ, "GIT_ASKPASS": helper.name}

    return subprocess.run(
        ["git"] + args,
        cwd=cwd,
        capture_output=True,
        text=True,
        timeout=120,
        env=env,
    )


def _build_repo_url(repo: str, token: str) -> str:
    """Inject token into HTTPS repo URL for clone/fetch authentication."""
    if token and repo.startswith("https://"):
        # https://gitlab.example.com/... → https://oauth2:TOKEN@gitlab.example.com/...
        return repo.replace("https://", f"https://oauth2:{token}@", 1)
    return repo


def sync_public_skills(
    repo_url: str,
    target_dir: Path,
    branch: str = "main",
    token: str = "",
) -> SyncResult:
    """Clone or pull the public Skill repository.

    Parameters
    ----------
    repo_url:
        Git remote URL (HTTPS or SSH).
    target_dir:
        Local directory to clone/pull into (typically ``skills/public/``).
    branch:
        Branch to track.
    token:
        GitLab access token for private repositories.

    Returns
    -------
    SyncResult with success status and list of discovered Skill directories.
    """
    if not repo_url:
        return SyncResult(success=False, message="No skill_git_repo configured")

    auth_url = _build_repo_url(repo_url, token)

    try:
        if (target_dir / ".git").is_dir():
            # Already cloned — fetch + reset to track branch
            logger.info("Pulling public skills from %s (branch: %s)", repo_url, branch)
            result = _run_git(["fetch", "origin", branch], cwd=target_dir)
            if result.returncode != 0:
                return SyncResult(success=False, message=f"git fetch failed: {result.stderr.strip()}")
            result = _run_git(["reset", "--hard", f"origin/{branch}"], cwd=target_dir)
            if result.returncode != 0:
                return SyncResult(success=False, message=f"git reset failed: {result.stderr.strip()}")
        else:
            # First time — clone
            logger.info("Cloning public skills from %s (branch: %s)", repo_url, branch)
            target_dir.mkdir(parents=True, exist_ok=True)
            result = _run_git(["clone", "--branch", branch, "--single-branch", auth_url, str(target_dir)])
            if result.returncode != 0:
                return SyncResult(success=False, message=f"git clone failed: {result.stderr.strip()}")

        # Discover skills (directories containing SKILL.md)
        skills = _discover_skills(target_dir)
        logger.info("Public skills synced: %d skills found", len(skills))
        return SyncResult(success=True, message=f"Synced {len(skills)} skills", skills_found=skills)

    except subprocess.TimeoutExpired:
        return SyncResult(success=False, message="Git operation timed out (120s)")
    except FileNotFoundError:
        return SyncResult(success=False, message="git command not found — is git installed?")
    except Exception as e:
        logger.warning("Skill sync failed", exc_info=True)
        return SyncResult(success=False, message=str(e))


def _discover_skills(target_dir: Path) -> list[str]:
    """List Skill directories (those containing SKILL.md)."""
    skills = []
    if not target_dir.is_dir():
        return skills
    for entry in sorted(target_dir.iterdir()):
        if entry.is_dir() and (entry / "SKILL.md").is_file():
            skills.append(entry.name)
    return skills
