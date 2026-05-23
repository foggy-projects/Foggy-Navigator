"""LangGraph Biz Worker — FastAPI application entry point."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .routes import account_context, frame_interruption, frame_reports, health, query, resume, skills, standalone

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
    frame_interruption.configure(get_runtime(), get_journal())
    frame_reports.configure(get_runtime(), get_journal())
    logger.info("Resume endpoint configured with shared runtime and journal")

    # Wire up skills route with sync callback (Doc 34 §4)
    from pathlib import Path
    from .config import settings as _settings
    from .runtime.standalone_provider_config import build_standalone_service_config

    skills_root = Path(_DEFAULT_SKILLS_ROOT) if isinstance(_DEFAULT_SKILLS_ROOT, str) else _DEFAULT_SKILLS_ROOT
    standalone_config = build_standalone_service_config(_settings, default_skills_root=skills_root)
    skills.configure(skills_root, on_sync_complete=_skill_registry.load)
    standalone.configure(
        standalone_config.skills_root,
        data_root=standalone_config.data_root,
        tool_provider=standalone_config.tool_provider,
        model_provider=standalone_config.model_provider,
        tool_modules=standalone_config.tool_modules,
        model_provider_configured=standalone_config.model_provider_configured,
        llm_provider=standalone_config.llm_provider,
        on_change=lambda: _skill_registry.load(include_standalone=True),
    )
    account_context_data_root = Path(_settings.data_root) if _settings.data_root else skills_root.parent / "data"
    account_context.configure(account_context_data_root)

    # Auto-sync public skills from GitLab on startup (Doc 34 §4.2)
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
app.include_router(frame_interruption.router)
app.include_router(frame_reports.router)
app.include_router(skills.router)
app.include_router(standalone.router)
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
