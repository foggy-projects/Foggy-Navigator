"""Company Skill Marketplace setup module.

This module configures the company-skill-marketplace in Claude's settings.json
on Worker startup, enabling Claude Code to load skills from the company's
internal GitLab repository.
"""

from __future__ import annotations

import logging

from ..config import settings
from .config import DEFAULT_MARKETPLACE_URL, SETTINGS_FILE
from .git_access import check_repo_access, configure_credential_helper
from .settings_manager import (
    configure_marketplace,
    is_marketplace_configured,
    read_settings,
    write_settings,
)
from .setup import prompt_for_credentials

logger = logging.getLogger(__name__)


async def setup_marketplace() -> bool:
    """
    Set up company-skill-marketplace in Claude settings.

    This function:
    1. Checks if marketplace is already configured
    2. Tests repository access
    3. Prompts for credentials if needed (interactive mode only)
    4. Writes configuration to settings.json

    Returns:
        True if marketplace is configured (either already or newly),
        False if configuration failed
    """
    # Skip if marketplace is disabled
    if not getattr(settings, "marketplace_enabled", True):
        logger.debug("Marketplace setup disabled by configuration")
        return False

    # Get marketplace URL from settings or use default
    marketplace_url = getattr(settings, "marketplace_url", DEFAULT_MARKETPLACE_URL)

    # 1. Check if already configured
    current_settings = read_settings()
    if is_marketplace_configured(current_settings, marketplace_url):
        logger.debug("Marketplace already configured in settings.json")
        return True

    logger.info("Setting up company-skill-marketplace from %s", marketplace_url)

    # 2. Test repository access
    has_access, error = await check_repo_access(marketplace_url)

    if not has_access:
        logger.info("Repository access check: %s", error)

        # 3. Prompt for credentials if authentication required
        if error == "Authentication required":
            creds = prompt_for_credentials()

            if creds:
                username, password = creds

                # Configure git credential helper
                if configure_credential_helper(marketplace_url, username, password):
                    # Retry access check
                    has_access, error = await check_repo_access(marketplace_url)

                    if not has_access:
                        logger.warning("Credentials accepted but access still denied: %s", error)
                else:
                    logger.warning("Failed to store credentials")

        elif error == "Repository not found":
            logger.error("Marketplace repository not found: %s", marketplace_url)
            return False

    if not has_access:
        logger.warning(
            "Failed to access company-skill-marketplace. "
            "Skills from marketplace will not be available. "
            "Error: %s",
            error
        )
        return False

    # 4. Write configuration to settings.json
    try:
        current_settings = configure_marketplace(current_settings, marketplace_url)
        write_settings(current_settings)
        logger.info("Marketplace configured successfully in %s", SETTINGS_FILE)
        return True
    except Exception as e:
        logger.warning("Failed to write settings.json: %s", e)
        return False


__all__ = ["setup_marketplace"]