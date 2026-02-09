"""Wraps the ``claude-code-sdk`` (or ``claude-agent-sdk``) package.

The wrapper gracefully handles the case where the SDK package is not
installed -- calls to :meth:`SdkWrapper.run_query` will yield a single
error event instead of crashing the service.
"""

from __future__ import annotations

import asyncio
import logging
import os
from datetime import datetime, timezone
from typing import Any, AsyncGenerator

from ..config import settings
from . import event_mapper

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# SDK availability detection
# ---------------------------------------------------------------------------
# The package was originally published as ``claude-code-sdk`` and later
# renamed to ``claude-agent-sdk``.  We try both import names so that the
# worker can run with whichever the user has installed.
# ---------------------------------------------------------------------------

_sdk_available = False
_query_fn = None
_options_cls = None

# Message types -- assigned dynamically after a successful import.
_AssistantMessage = None
_UserMessage = None
_SystemMessage = None
_ResultMessage = None
_TextBlock = None
_ToolUseBlock = None
_ToolResultBlock = None

try:
    from claude_code_sdk import query as _q, ClaudeCodeOptions as _opts  # type: ignore[import-untyped]
    from claude_code_sdk import (  # type: ignore[import-untyped]
        AssistantMessage as _AM,
        UserMessage as _UM,
        SystemMessage as _SM,
        ResultMessage as _RM,
        TextBlock as _TB,
        ToolUseBlock as _TUB,
        ToolResultBlock as _TRB,
    )

    _query_fn = _q
    _options_cls = _opts
    _AssistantMessage = _AM
    _UserMessage = _UM
    _SystemMessage = _SM
    _ResultMessage = _RM
    _TextBlock = _TB
    _ToolUseBlock = _TUB
    _ToolResultBlock = _TRB
    _sdk_available = True
    logger.info("Loaded claude-code-sdk successfully")
except ImportError:
    try:
        from claude_agent_sdk import query as _q2, ClaudeAgentOptions as _opts2  # type: ignore[import-untyped]
        from claude_agent_sdk import (  # type: ignore[import-untyped]
            AssistantMessage as _AM2,
            UserMessage as _UM2,
            SystemMessage as _SM2,
            ResultMessage as _RM2,
            TextBlock as _TB2,
            ToolUseBlock as _TUB2,
            ToolResultBlock as _TRB2,
        )

        _query_fn = _q2
        _options_cls = _opts2
        _AssistantMessage = _AM2
        _UserMessage = _UM2
        _SystemMessage = _SM2
        _ResultMessage = _RM2
        _TextBlock = _TB2
        _ToolUseBlock = _TUB2
        _ToolResultBlock = _TRB2
        _sdk_available = True
        logger.info("Loaded claude-agent-sdk successfully")
    except ImportError:
        logger.warning(
            "Neither claude-code-sdk nor claude-agent-sdk is installed. "
            "The /api/v1/query endpoint will return an error until the SDK is available."
        )

# ---------------------------------------------------------------------------
# Global registries -- imported by route modules
# ---------------------------------------------------------------------------

task_registry: dict[str, dict[str, Any]] = {}
"""Mapping of *task_id* -> metadata dict for active tasks."""

session_store: dict[str, dict[str, Any]] = {}
"""Mapping of *session_id* -> metadata dict for tracked sessions."""


# ---------------------------------------------------------------------------
# Wrapper
# ---------------------------------------------------------------------------

class SdkWrapper:
    """Thin facade around the Claude Code SDK ``query()`` function."""

    # -- Environment ---------------------------------------------------------

    @staticmethod
    def _build_env() -> dict[str, str]:
        """Build an environment-variable dict to inject into the CLI subprocess.

        Only non-empty values are included so that the CLI falls back to its
        own defaults when the worker has no explicit configuration.
        """

        env: dict[str, str] = {}
        if settings.anthropic_api_key:
            env["ANTHROPIC_API_KEY"] = settings.anthropic_api_key
        if settings.anthropic_auth_token:
            env["ANTHROPIC_AUTH_TOKEN"] = settings.anthropic_auth_token
        if settings.anthropic_base_url:
            env["ANTHROPIC_BASE_URL"] = settings.anthropic_base_url
        return env

    # -- Query ---------------------------------------------------------------

    async def run_query(
        self,
        task_id: str,
        prompt: str,
        cwd: str,
        session_id: str | None = None,
        max_turns: int | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        """Run a Claude Code query and yield mapped SSE event dicts.

        The generator registers itself in :data:`task_registry` on entry and
        removes itself on exit so that other endpoints (health, abort) can
        inspect and cancel running tasks.
        """

        if not _sdk_available:
            yield event_mapper.map_error(
                task_id=task_id,
                error=(
                    "Claude Code SDK is not installed. "
                    "Install claude-code-sdk or claude-agent-sdk to enable queries."
                ),
            )
            return

        # Register the task so that it can be observed / cancelled.
        task_registry[task_id] = {
            "prompt": prompt,
            "cwd": cwd,
            "session_id": session_id,
            "started_at": datetime.now(timezone.utc).isoformat(),
            "asyncio_task": asyncio.current_task(),
        }

        current_session_id: str | None = session_id

        try:
            env = self._build_env()

            # Build SDK options.  We use keyword arguments so that the call
            # works with both ``ClaudeCodeOptions`` and ``ClaudeAgentOptions``.
            options_kwargs: dict[str, Any] = {
                "cwd": cwd,
            }
            if env:
                options_kwargs["env"] = env
            if max_turns is not None:
                options_kwargs["max_turns"] = max_turns
            if session_id is not None:
                options_kwargs["resume"] = session_id

            options = _options_cls(**options_kwargs)

            async for message in _query_fn(prompt=prompt, options=options):
                # -- AssistantMessage (may contain text, tool_use, tool_result blocks)
                if _AssistantMessage is not None and isinstance(message, _AssistantMessage):
                    for block in message.content:
                        if _TextBlock is not None and isinstance(block, _TextBlock):
                            yield event_mapper.map_assistant_text(
                                task_id=task_id,
                                text=block.text,
                                session_id=current_session_id,
                            )
                        elif _ToolUseBlock is not None and isinstance(block, _ToolUseBlock):
                            yield event_mapper.map_tool_use(
                                task_id=task_id,
                                tool_name=block.name,
                                tool_input=block.input,
                                session_id=current_session_id,
                            )
                        elif _ToolResultBlock is not None and isinstance(block, _ToolResultBlock):
                            content_str: str | None = None
                            if isinstance(block.content, str):
                                content_str = block.content
                            elif isinstance(block.content, list):
                                # Flatten list of content dicts to a single string.
                                parts = []
                                for item in block.content:
                                    if isinstance(item, dict):
                                        parts.append(item.get("text", str(item)))
                                    else:
                                        parts.append(str(item))
                                content_str = "\n".join(parts)

                            yield event_mapper.map_tool_result(
                                task_id=task_id,
                                tool_use_id=block.tool_use_id,
                                content=content_str,
                                is_error=bool(block.is_error),
                                session_id=current_session_id,
                            )

                # -- ResultMessage
                elif _ResultMessage is not None and isinstance(message, _ResultMessage):
                    current_session_id = getattr(message, "session_id", current_session_id)

                    # Record / update session tracking store.
                    if current_session_id:
                        now = datetime.now(timezone.utc)
                        if current_session_id in session_store:
                            session_store[current_session_id]["updated_at"] = now
                        else:
                            session_store[current_session_id] = {
                                "cwd": cwd,
                                "created_at": now,
                                "updated_at": now,
                            }

                    yield event_mapper.map_result(
                        task_id=task_id,
                        result_text=getattr(message, "result", None),
                        cost_usd=getattr(message, "total_cost_usd", None),
                        duration_ms=getattr(message, "duration_ms", None),
                        session_id=current_session_id,
                    )

                # -- SystemMessage
                elif _SystemMessage is not None and isinstance(message, _SystemMessage):
                    data = getattr(message, "data", {}) or {}
                    subtype = getattr(message, "subtype", "system")

                    # Extract session_id from init event and propagate to all subsequent events
                    if subtype == "init" and isinstance(data, dict):
                        init_session_id = data.get("session_id")
                        if init_session_id:
                            current_session_id = init_session_id

                    # Skip verbose hook events (hook_started, hook_response)
                    if subtype in ("hook_started", "hook_response"):
                        continue

                    yield event_mapper.map_system(
                        task_id=task_id,
                        subtype=subtype,
                        data=data if subtype == "init" else None,
                        session_id=current_session_id,
                    )

        except asyncio.CancelledError:
            logger.info("Task %s was cancelled", task_id)
            yield event_mapper.map_error(
                task_id=task_id,
                error="Task was cancelled",
                session_id=current_session_id,
            )

        except Exception as exc:
            logger.exception("Error executing task %s", task_id)
            yield event_mapper.map_error(
                task_id=task_id,
                error=str(exc),
                session_id=current_session_id,
            )

        finally:
            task_registry.pop(task_id, None)
