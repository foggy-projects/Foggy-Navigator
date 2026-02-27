"""FastAPI application entry-point for the Claude Agent Worker."""

from __future__ import annotations

import logging
import logging.handlers
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .routes import auth, files, git_info, health, processes, query, sessions, skills, ssh, worktree
from .ssh.session_manager import start_cleanup_task, stop_cleanup_and_close_all

import sys

# -- Logging ----------------------------------------------------------------
_LOG_DIR = Path(__file__).resolve().parent.parent.parent.parent / "logs"
_LOG_DIR.mkdir(exist_ok=True)

_fmt = "%(asctime)s [%(levelname)s] %(name)s - %(message)s"
_console = logging.StreamHandler(
    stream=open(sys.stdout.fileno(), mode='w', encoding='utf-8', closefd=False),
)
_file = logging.handlers.RotatingFileHandler(
    _LOG_DIR / "worker.log",
    maxBytes=10 * 1024 * 1024,  # 10 MB
    backupCount=3,
    encoding="utf-8",
)
logging.basicConfig(
    level=logging.INFO,
    format=_fmt,
    handlers=[_console, _file],
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Log the effective configuration on startup and perform cleanup on shutdown."""

    logger.info("Claude Agent Worker starting")
    logger.info("  host           = %s", settings.host)
    logger.info("  port           = %s", settings.port)
    logger.info("  worker_name    = %s", settings.worker_name or "(not set)")
    logger.info("  auth           = %s", "enabled" if settings.worker_token else "disabled (dev mode)")
    logger.info("  allowed_cwds   = %s", settings.allowed_cwds or "(unrestricted)")
    logger.info("  max_concurrent = %s", settings.max_concurrent_tasks)
    logger.info(
        "  anthropic_key  = %s",
        "configured" if settings.anthropic_api_key else "(not set)",
    )
    logger.info(
        "  anthropic_token= %s",
        "configured" if settings.anthropic_auth_token else "(not set)",
    )
    logger.info(
        "  anthropic_url  = %s",
        settings.anthropic_base_url or "(default)",
    )
    # Determine default auth mode
    if settings.anthropic_api_key:
        default_auth = "API_KEY"
    elif settings.anthropic_auth_token:
        default_auth = "CUSTOM_ENDPOINT"
    else:
        default_auth = "SUBSCRIPTION (claude login)"
    logger.info("  default_auth   = %s", default_auth)

    # SSH idle-cleanup background task
    start_cleanup_task()

    yield

    # Shutdown: close all SSH sessions
    await stop_cleanup_and_close_all()
    logger.info("Claude Agent Worker stopped")


app = FastAPI(
    title="Claude Agent Worker",
    description="REST/SSE bridge between Foggy Navigator and the Claude Code SDK",
    version="0.1.0",
    lifespan=lifespan,
)

# -- CORS -------------------------------------------------------------------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# -- Routes -----------------------------------------------------------------
app.include_router(health.router)
app.include_router(query.router)
app.include_router(sessions.router)
app.include_router(git_info.router)
app.include_router(skills.router)
app.include_router(auth.router)
app.include_router(worktree.router)
app.include_router(files.router)
app.include_router(ssh.router)
app.include_router(processes.router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port)
