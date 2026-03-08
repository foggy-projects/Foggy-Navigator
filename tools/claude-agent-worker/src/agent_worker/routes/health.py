import platform
import shutil

from fastapi import APIRouter

from ..config import settings
from ..models import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Return basic service health information.

    Reports the hostname, package version, number of currently running tasks,
    whether the ``claude`` CLI binary is available on ``$PATH``, and the
    configured worker display name.
    """

    from ..claude.sdk_wrapper import task_registry
    from .. import __version__

    return HealthResponse(
        hostname=platform.node(),
        version=__version__,
        active_tasks=len(task_registry),
        claude_cli_available=shutil.which("claude") is not None,
        worker_name=settings.worker_name,
    )
