"""LangGraph Biz Worker — FastAPI application entry point."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .routes import account_context, health, query, resume, skills

logger = logging.getLogger("langgraph_biz_worker")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan: startup and shutdown hooks."""
    logger.info("LangGraph Biz Worker v%s starting", __version__)

    # Wire up resume route with the shared runtime and journal (Doc 31 §16.5)
    from .graphs.root_graph import get_journal, get_runtime, _skill_registry
    from .runtime.skill_registry import _DEFAULT_SKILLS_ROOT
    from .runtime.fsscript_bridge import get_fsscript_bridge
    resume.configure(get_runtime(), get_journal(), get_fsscript_bridge())
    logger.info("Resume endpoint configured with shared runtime and journal")

    # Wire up skills route with sync callback (Doc 34 §4)
    from pathlib import Path
    skills_root = Path(_DEFAULT_SKILLS_ROOT) if isinstance(_DEFAULT_SKILLS_ROOT, str) else _DEFAULT_SKILLS_ROOT
    skills.configure(skills_root, on_sync_complete=_skill_registry.load)
    account_context.configure(skills_root.parent / "data")

    # Auto-sync public skills from GitLab on startup (Doc 34 §4.2)
    from .config import settings as _settings
    if _settings.skill_git_repo and _settings.skill_sync_on_startup:
        from .runtime.skill_git_sync import sync_public_skills
        result = sync_public_skills(
            repo_url=_settings.skill_git_repo,
            target_dir=skills_root / "public",
            branch=_settings.skill_git_branch,
            token=_settings.skill_git_token,
        )
        if result.success:
            _skill_registry.load()
            logger.info("Startup skill sync: %s", result.message)
        else:
            logger.warning("Startup skill sync failed: %s", result.message)

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
app.include_router(skills.router)
app.include_router(account_context.router)


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
