"""Company Skill Marketplace setup module.

This module configures the company-skill-marketplace in Claude's settings.json
on Worker startup, enabling Claude Code to load skills from the company's
internal GitLab repository.
"""

from __future__ import annotations

import asyncio
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
    2. Writes configuration to settings.json (regardless of access)
    3. Tests repository access and logs warning if unavailable

    Returns:
        True if marketplace is configured in settings.json
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

    # 2. Write configuration to settings.json first
    try:
        current_settings = configure_marketplace(current_settings, marketplace_url)
        await asyncio.to_thread(write_settings, current_settings)
        logger.info("Marketplace configured in %s", SETTINGS_FILE)
    except Exception as e:
        logger.warning("Failed to write settings.json: %s", e)
        return False

    # 3. Test repository access (best effort, don't block on failure)
    has_access, error = await check_repo_access(marketplace_url)

    if not has_access:
        if error == "Authentication required":
            # Try to prompt for credentials in interactive mode
            creds = prompt_for_credentials()
            if creds:
                username, password = creds
                if configure_credential_helper(marketplace_url, username, password):
                    has_access, _ = await check_repo_access(marketplace_url)

        if not has_access:
            logger.warning(
                "Could not verify marketplace access (%s). "
                "Skills may not load until credentials are configured.",
                error
            )
    else:
        logger.info("Marketplace repository accessible")

    return True


__all__ = ["setup_marketplace"]
