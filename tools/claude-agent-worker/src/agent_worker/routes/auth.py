"""Auth configuration endpoint — exposes the Worker's current Anthropic auth settings."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from ..auth import verify_token
from ..config import settings

router = APIRouter(prefix="/api/v1", tags=["auth"], dependencies=[Depends(verify_token)])


@router.get("/auth-config")
async def get_auth_config():
    """Return the Worker's currently effective Anthropic authentication configuration."""

    mode = "SUBSCRIPTION"
    if settings.anthropic_api_key:
        mode = "API_KEY"
    if settings.anthropic_base_url:
        mode = "CUSTOM_ENDPOINT"
    if settings.anthropic_auth_token and not settings.anthropic_api_key:
        mode = "SUBSCRIPTION"

    return {
        "auth_mode": mode,
        "api_key": settings.anthropic_api_key or None,
        "auth_token": settings.anthropic_auth_token or None,
        "base_url": settings.anthropic_base_url or None,
    }
