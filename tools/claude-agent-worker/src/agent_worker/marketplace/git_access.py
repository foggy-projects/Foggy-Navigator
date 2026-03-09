"""Git repository access check and credential configuration."""

from __future__ import annotations

import asyncio
import logging
import subprocess
from typing import Tuple
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


async def check_repo_access(repo_url: str, timeout: int = 30) -> Tuple[bool, str]:
    """
    Check if the repository URL is accessible.

    Uses `git ls-remote --heads <url>` to test access without cloning.

    Returns:
        (has_access, error_message) - error_message is empty on success
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            "git", "ls-remote", "--heads", repo_url,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=timeout
        )

        if proc.returncode == 0:
            return True, ""

        # Parse error message
        err_output = stderr.decode("utf-8", errors="replace").strip()
        out_output = stdout.decode("utf-8", errors="replace").strip()
        combined = err_output or out_output

        if proc.returncode == 128:
            if "Authentication failed" in combined or "could not read" in combined.lower():
                return False, "Authentication required"
            if "Repository not found" in combined or "not found" in combined.lower():
                return False, "Repository not found"

        return False, combined[:200] if combined else f"Git error (rc={proc.returncode})"

    except asyncio.TimeoutError:
        return False, f"Git command timed out after {timeout}s"
    except FileNotFoundError:
        return False, "Git not found in PATH"
    except Exception as e:
        return False, str(e)[:200]


def configure_credential_helper(repo_url: str, username: str, password: str) -> bool:
    """
    Store credentials using git credential helper.

    Uses `git credential approve` to store credentials in the system's
    credential store (managed by Git, cross-platform).

    Returns:
        True if successful, False otherwise
    """
    try:
        # Parse URL to extract protocol and host
        parsed = urlparse(repo_url)
        protocol = parsed.scheme or "http"
        host = parsed.netloc or parsed.path.split("/")[0]

        # Prepare credential input for git credential approve
        credential_input = f"""protocol={protocol}
host={host}
username={username}
password={password}
"""

        # Call git credential approve to store
        proc = subprocess.run(
            ["git", "credential", "approve"],
            input=credential_input.encode("utf-8"),
            capture_output=True,
            timeout=10,
        )

        if proc.returncode == 0:
            logger.info("Credentials stored via git credential helper for %s", host)
            return True
        else:
            logger.warning(
                "Failed to store credentials: %s",
                proc.stderr.decode("utf-8", errors="replace")
            )
            return False

    except subprocess.TimeoutExpired:
        logger.warning("git credential approve timed out")
        return False
    except FileNotFoundError:
        logger.warning("Git not found in PATH")
        return False
    except Exception as e:
        logger.warning("Failed to configure credential helper: %s", e)
        return False