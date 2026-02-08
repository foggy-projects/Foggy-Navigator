"""FastAPI application entry-point for the Claude Agent Worker."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .routes import health, query, sessions

import sys

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    handlers=[logging.StreamHandler(stream=open(sys.stdout.fileno(), mode='w', encoding='utf-8', closefd=False))],
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
        "  anthropic_url  = %s",
        settings.anthropic_base_url or "(default)",
    )
    yield
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


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port)
