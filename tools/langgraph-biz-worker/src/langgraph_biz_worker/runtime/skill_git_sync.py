"""Git-based public Skill synchronization.

Clones or pulls a GitLab repository into ``skills/public/``.
Designed for a single-repo-all-skills layout (Doc 34 §4.1).
"""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from .subprocess_env import sanitized_worker_subprocess_env

logger = logging.getLogger(__name__)

_ASKPASS_SCRIPT = """#!/bin/sh
case "$1" in
  *Username*) printf '%s\\n' "${FOGGY_SKILL_GIT_USERNAME:-oauth2}" ;;
  *Password*) printf '%s\\n' "$FOGGY_SKILL_GIT_TOKEN" ;;
  *) exit 1 ;;
esac
"""


@dataclass
class SyncResult:
    """Outcome of a git sync operation."""

    success: bool
    message: str
    skills_found: list[str] = field(default_factory=list)


def _run_git(args: list[str], cwd: Path | None = None, token: str = "") -> subprocess.CompletedProcess:
    """Run Git with ambient credentials disabled and optional askpass auth."""

    env = sanitized_worker_subprocess_env()
    env["GIT_TERMINAL_PROMPT"] = "0"
    argv = ["git", "-c", "credential.helper=", *args]
    helper_dir: Path | None = None

    try:
        if token:
            helper_dir = Path(tempfile.mkdtemp(prefix="foggy-skill-git-askpass-"))
            helper = helper_dir / "askpass.sh"
            helper.write_text(_ASKPASS_SCRIPT, encoding="utf-8")
            helper.chmod(0o700)
            env["GIT_ASKPASS"] = str(helper)
            env["FOGGY_SKILL_GIT_TOKEN"] = token
            env["FOGGY_SKILL_GIT_USERNAME"] = "oauth2"

        return subprocess.run(
            argv,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=120,
            env=env,
        )
    finally:
        if helper_dir is not None:
            shutil.rmtree(helper_dir, ignore_errors=True)


def _build_repo_url(repo: str, token: str) -> str:
    """Return a credential-free repository URL suitable for argv and logs."""

    parsed = urlsplit(repo)
    if parsed.scheme.lower() in {"http", "https"} and parsed.hostname:
        host = parsed.hostname
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        if parsed.port is not None:
            host = f"{host}:{parsed.port}"
        repo = urlunsplit((parsed.scheme, host, parsed.path, "", ""))
    if token and token in repo:
        raise ValueError("skill Git repository URL must not contain the configured token")
    return repo


def _sanitize_git_error(message: str, token: str = "") -> str:
    """Remove configured and URL-embedded credentials from Git diagnostics."""

    sanitized = message
    if token:
        sanitized = sanitized.replace(token, "[git-token-redacted]")
    sanitized = re.sub(
        r"(?i)(https?://)[^/@\s]+@",
        r"\1[git-credentials-redacted]@",
        sanitized,
    )
    sanitized = re.sub(
        r"(?i)\b(authorization\s*:\s*(?:bearer|basic)\s+)[^\s]+",
        r"\1[git-credentials-redacted]",
        sanitized,
    )
    return sanitized


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

    try:
        remote_url = _build_repo_url(repo_url, token)
        if (target_dir / ".git").is_dir():
            # Already cloned — fetch + reset to track branch
            logger.info("Pulling public skills from %s (branch: %s)", remote_url, branch)
            result = _run_git(["remote", "set-url", "origin", remote_url], cwd=target_dir)
            if result.returncode != 0:
                detail = _sanitize_git_error(result.stderr.strip(), token)
                return SyncResult(success=False, message=f"git remote sanitization failed: {detail}")
            result = _run_git(["fetch", "origin", branch], cwd=target_dir, token=token)
            if result.returncode != 0:
                detail = _sanitize_git_error(result.stderr.strip(), token)
                return SyncResult(success=False, message=f"git fetch failed: {detail}")
            result = _run_git(["reset", "--hard", f"origin/{branch}"], cwd=target_dir)
            if result.returncode != 0:
                detail = _sanitize_git_error(result.stderr.strip(), token)
                return SyncResult(success=False, message=f"git reset failed: {detail}")
        else:
            # First time — clone
            logger.info("Cloning public skills from %s (branch: %s)", remote_url, branch)
            target_dir.mkdir(parents=True, exist_ok=True)
            result = _run_git(
                ["clone", "--branch", branch, "--single-branch", remote_url, str(target_dir)],
                token=token,
            )
            if result.returncode != 0:
                detail = _sanitize_git_error(result.stderr.strip(), token)
                return SyncResult(success=False, message=f"git clone failed: {detail}")

        # Discover skills (directories containing SKILL.md)
        skills = _discover_skills(target_dir)
        logger.info("Public skills synced: %d skills found", len(skills))
        return SyncResult(success=True, message=f"Synced {len(skills)} skills", skills_found=skills)

    except subprocess.TimeoutExpired:
        return SyncResult(success=False, message="Git operation timed out (120s)")
    except FileNotFoundError:
        return SyncResult(success=False, message="git command not found — is git installed?")
    except Exception as exc:
        detail = _sanitize_git_error(str(exc), token)
        logger.warning("Skill sync failed: %s", detail)
        return SyncResult(success=False, message=detail)


def _discover_skills(target_dir: Path) -> list[str]:
    """List Skill directories (those containing SKILL.md)."""
    skills = []
    if not target_dir.is_dir():
        return skills
    for entry in sorted(target_dir.iterdir()):
        if entry.is_dir() and (entry / "SKILL.md").is_file():
            skills.append(entry.name)
    return skills
