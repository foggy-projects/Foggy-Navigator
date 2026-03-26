"""FastAPI application entry-point for the Claude Agent Worker."""

from __future__ import annotations

import asyncio
import logging
import logging.handlers
import queue
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from . import __version__
from .config import settings
from .routes import auth, files, git_info, git_log, health, init_directory, platform_skills, processes, query, sessions, skills, ssh, worktree
from .marketplace import setup_marketplace
from .platform_skills.deployer import deploy_platform_skills
from .ssh.session_manager import start_cleanup_task, stop_cleanup_and_close_all

import sys

# -- Windows: isolate child processes from the worker's console signals ------
# Problem: The Claude Agent SDK spawns claude.exe via anyio.open_process
# without CREATE_NEW_PROCESS_GROUP.  When the CLI's child (bash/git) triggers
# a CTRL_C_EVENT it propagates to every process in the group — including this
# worker — causing an unrecoverable shutdown.
#
# Fix (two layers):
#   1. SetConsoleCtrlHandler(None, True) — makes the worker itself ignore
#      CTRL_C_EVENT (CTRL_CLOSE / LOGOFF / SHUTDOWN still work; stop.ps1
#      uses taskkill which sends SIGTERM, unaffected).
#   2. Monkey-patch asyncio.create_subprocess_exec to inject
#      CREATE_NEW_PROCESS_GROUP into every subprocess call.  This isolates
#      the CLI (and its children) in a separate process group so their
#      console signals never reach the worker in the first place.
if sys.platform == "win32":
    import asyncio
    import ctypes
    import subprocess

    ctypes.windll.kernel32.SetConsoleCtrlHandler(None, True)

    _orig_create_subprocess_exec = asyncio.create_subprocess_exec

    async def _patched_create_subprocess_exec(*args, **kwargs):
        flags = kwargs.get("creationflags", 0)
        flags |= subprocess.CREATE_NEW_PROCESS_GROUP
        kwargs["creationflags"] = flags
        return await _orig_create_subprocess_exec(*args, **kwargs)

    asyncio.create_subprocess_exec = _patched_create_subprocess_exec

# -- Logging ----------------------------------------------------------------
# __file__ = src/agent_worker/main.py → 3 parents = worker root (claude-agent-worker/)
_LOG_DIR = Path(__file__).resolve().parent.parent.parent / "logs"
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
_log_queue: queue.Queue[logging.LogRecord] = queue.Queue()
_queue_handler = logging.handlers.QueueHandler(_log_queue)
_log_listener: logging.handlers.QueueListener | None = None


def _configure_logging() -> None:
    """Route application logs through a background listener thread."""
    global _log_listener
    if _log_listener is not None:
        return

    _console.setFormatter(logging.Formatter(_fmt))
    _file.setFormatter(logging.Formatter(_fmt))

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.setLevel(logging.INFO)
    root_logger.addHandler(_queue_handler)

    _log_listener = logging.handlers.QueueListener(
        _log_queue,
        _console,
        _file,
        respect_handler_level=True,
    )
    _log_listener.start()


_configure_logging()
logger = logging.getLogger(__name__)

# -- Foggy Monitor (optional RabbitMQ log forwarding) ----------------------
_monitor_publisher = None
try:
    from foggy_monitor import setup_monitoring
    _monitor_publisher = setup_monitoring("worker", instance_id=f"worker-{Path(__file__).resolve().parent.parent.parent.name}")
except ImportError:
    logger.debug("foggy-monitor not installed, skipping RabbitMQ log forwarding")
except Exception as _exc:
    logger.debug("foggy-monitor setup failed: %s", _exc)


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

    # Deploy platform skills to ~/.claude/skills/
    await asyncio.to_thread(deploy_platform_skills)

    # Setup company-skill-marketplace in settings.json
    await setup_marketplace()

    # SSH idle-cleanup background task
    start_cleanup_task()

    yield

    # Shutdown: close all SSH sessions
    await stop_cleanup_and_close_all()
    if _monitor_publisher:
        _monitor_publisher.close()
    logger.info("Claude Agent Worker stopped")
    if _log_listener is not None:
        _log_listener.stop()


app = FastAPI(
    title="Claude Agent Worker",
    description="REST/SSE bridge between Foggy Navigator and the Claude Code SDK",
    version=__version__,
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
app.include_router(git_log.router)
app.include_router(ssh.router)
app.include_router(processes.router)
app.include_router(init_directory.router)
app.include_router(platform_skills.router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port)
