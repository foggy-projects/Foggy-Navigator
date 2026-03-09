"""User interaction for marketplace setup."""

from __future__ import annotations

import getpass
import logging
import os
import sys
from typing import Optional

logger = logging.getLogger(__name__)


def is_interactive() -> bool:
    """Check if we're running in an interactive terminal that can accept input."""
    # Check if stdin is a tty
    if not sys.stdin.isatty():
        return False

    # On Windows, check if we're in a hidden/background process
    # When started with Start-Process -WindowStyle Hidden, stdin may still
    # appear as tty but cannot actually receive input
    if sys.platform == "win32":
        try:
            # Check if we have a console window
            import ctypes
            hwnd = ctypes.windll.kernel32.GetConsoleWindow()
            if hwnd == 0:
                return False
            # Check if console is visible
            SW_HIDE = 0
            GWL_STYLE = -16
            style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_STYLE)
            WS_VISIBLE = 0x10000000
            if not (style & WS_VISIBLE):
                return False
        except Exception:
            pass

    # Additional check: if stdout/stderr are redirected, likely non-interactive
    if not sys.stdout.isatty() or not sys.stderr.isatty():
        return False

    return True


def prompt_for_credentials() -> Optional[tuple[str, str]]:
    """
    Prompt user for GitLab credentials interactively.

    Returns:
        (username, password) tuple or None if cancelled/not interactive
    """
    # Check if running in interactive terminal
    if not is_interactive():
        logger.warning(
            "GitLab authentication required but running in non-interactive mode. "
            "Please run the worker in an interactive terminal to configure credentials, "
            "or manually configure git credentials for the repository."
        )
        return None

    print("\n" + "=" * 60)
    print("Company Skill Marketplace Configuration")
    print("=" * 60)
    print("GitLab credentials are required to access the company skill marketplace.")
    print("Credentials will be stored securely via Git credential helper.")
    print()

    try:
        username = input("GitLab Username: ").strip()
        if not username:
            print("Operation cancelled.")
            return None

        password = getpass.getpass("GitLab Password/Access Token: ").strip()
        if not password:
            print("Operation cancelled.")
            return None

        return username, password

    except (EOFError, KeyboardInterrupt):
        print("\nOperation cancelled.")
        return None