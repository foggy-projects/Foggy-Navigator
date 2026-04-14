"""LangGraph Biz Worker — FastAPI application entry point."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .routes import health, query, resume

logger = logging.getLogger("langgraph_biz_worker")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: startup and shutdown hooks."""
    logger.info("LangGraph Biz Worker v%s starting", __version__)
    yield
    logger.info("LangGraph Biz Worker shutting down")


app = FastAPI(
    title="LangGraph Biz Worker",
    description="Controlled business execution backend with Skill Runtime for Foggy Navigator",
    version=__version__,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routes
app.include_router(health.router)
app.include_router(query.router)
app.include_router(resume.router)


def main() -> None:
    """Run the worker with Uvicorn (for direct ``python -m`` invocation)."""
    import uvicorn
    from .config import settings

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s - %(message)s",
    )

    uvicorn.run(
        "langgraph_biz_worker.main:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
    )


if __name__ == "__main__":
    main()
