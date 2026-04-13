"""Health check endpoint (no authentication required)."""

import platform

from fastapi import APIRouter

from .. import __version__
from ..config import settings
from ..models import HealthResponse

router = APIRouter(tags=["health"])

# Active task tracking — imported by query route to register/unregister
active_tasks: set[str] = set()


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    return HealthResponse(
        hostname=platform.node(),
        version=__version__,
        active_tasks=len(active_tasks),
        worker_name=settings.worker_name,
    )
