"""Health check endpoint (no authentication required)."""

import platform

from fastapi import APIRouter

from .. import __version__
from ..config import settings
from ..external_mode import resolve_external_mode
from ..models import HealthResponse
from ..runtime.agent_capabilities import build_worker_capabilities

router = APIRouter(tags=["health"])

# Active task tracking — imported by query route to register/unregister
active_tasks: set[str] = set()


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    external = resolve_external_mode(settings.external_enabled, settings.worker_token)
    ready = not external.external_enabled or external.external_ready
    return HealthResponse(
        hostname=platform.node(),
        version=__version__,
        active_tasks=len(active_tasks),
        worker_name=settings.worker_name,
        capabilities=build_worker_capabilities(settings.max_agent_nesting_depth),
        status="ok" if ready else "degraded",
        ready=ready,
        mode=external.mode,
        external_enabled=external.external_enabled,
        external_ready=external.external_ready,
        auth_configured=external.auth_configured,
        reasons=list(external.reasons),
    )
