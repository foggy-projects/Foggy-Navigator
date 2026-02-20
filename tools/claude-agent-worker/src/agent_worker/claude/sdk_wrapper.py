"""Wraps the ``claude-agent-sdk`` (or legacy ``claude-code-sdk``) package.

The wrapper gracefully handles the case where the SDK package is not
installed -- calls to :meth:`SdkWrapper.run_query` will yield a single
error event instead of crashing the service.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncGenerator

from ..config import settings
from . import event_mapper

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# SDK availability detection
# ---------------------------------------------------------------------------
# Prefer ``claude-agent-sdk`` (actively maintained, bundles its own CLI).
# Fall back to the deprecated ``claude-code-sdk`` for backwards compat.
# ---------------------------------------------------------------------------

_sdk_available = False
_use_agent_sdk = False  # True when the newer claude-agent-sdk is loaded
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

# Error types -- for structured error reporting.
_ProcessError = None
_CLIConnectionError = None
_CLIJSONDecodeError = None
_CLINotFoundError = None

# Permission result types -- only available in claude-agent-sdk.
_PermissionResultAllow = None
_PermissionResultDeny = None

# Agent Teams type -- only available in claude-agent-sdk.
_AgentDefinition = None

# --- Try claude-agent-sdk first (recommended) ---
try:
    from claude_agent_sdk import query as _q, ClaudeAgentOptions as _opts  # type: ignore[import-untyped]
    from claude_agent_sdk import (  # type: ignore[import-untyped]
        AssistantMessage as _AM,
        UserMessage as _UM,
        SystemMessage as _SM,
        ResultMessage as _RM,
        TextBlock as _TB,
        ToolUseBlock as _TUB,
        ToolResultBlock as _TRB,
    )
    from claude_agent_sdk import (  # type: ignore[import-untyped]
        ProcessError as _PE,
        CLIConnectionError as _CCE,
        CLIJSONDecodeError as _CJDE,
        CLINotFoundError as _CNFE,
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
    _ProcessError = _PE
    _CLIConnectionError = _CCE
    _CLIJSONDecodeError = _CJDE
    _CLINotFoundError = _CNFE
    _sdk_available = True
    _use_agent_sdk = True

    # Permission result types for can_use_tool callback
    try:
        from claude_agent_sdk import (  # type: ignore[import-untyped]
            PermissionResultAllow as _PRA,
            PermissionResultDeny as _PRD,
        )
        _PermissionResultAllow = _PRA
        _PermissionResultDeny = _PRD
    except ImportError:
        pass

    # AgentDefinition for first-class Agent Teams support
    try:
        from claude_agent_sdk import AgentDefinition as _AD  # type: ignore[import-untyped]
        _AgentDefinition = _AD
    except ImportError:
        pass

    logger.info("Loaded claude-agent-sdk successfully")
except ImportError:
    # --- Fall back to deprecated claude-code-sdk ---
    try:
        from claude_code_sdk import query as _q2, ClaudeCodeOptions as _opts2  # type: ignore[import-untyped]
        from claude_code_sdk import (  # type: ignore[import-untyped]
            AssistantMessage as _AM2,
            UserMessage as _UM2,
            SystemMessage as _SM2,
            ResultMessage as _RM2,
            TextBlock as _TB2,
            ToolUseBlock as _TUB2,
            ToolResultBlock as _TRB2,
        )
        from claude_code_sdk import (  # type: ignore[import-untyped]
            ProcessError as _PE2,
            CLIConnectionError as _CCE2,
            CLIJSONDecodeError as _CJDE2,
            CLINotFoundError as _CNFE2,
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
        _ProcessError = _PE2
        _CLIConnectionError = _CCE2
        _CLIJSONDecodeError = _CJDE2
        _CLINotFoundError = _CNFE2
        _sdk_available = True
        logger.info(
            "Loaded claude-code-sdk (deprecated). "
            "Consider upgrading: pip install claude-agent-sdk"
        )
    except ImportError:
        logger.warning(
            "Neither claude-agent-sdk nor claude-code-sdk is installed. "
            "The /api/v1/query endpoint will return an error until the SDK is available."
        )

# ---------------------------------------------------------------------------
# Global registries -- imported by route modules
# ---------------------------------------------------------------------------

task_registry: dict[str, dict[str, Any]] = {}
"""Mapping of *task_id* -> metadata dict for active tasks."""

session_store: dict[str, dict[str, Any]] = {}
"""Mapping of *session_id* -> metadata dict for tracked sessions."""

permission_pending: dict[str, dict[str, Any]] = {}
"""Mapping of *permission_id* -> {event: asyncio.Event, result: str, deny_message: str | None, task_id: str}.

Used by the ``can_use_tool`` callback to await user approval, and by
the ``/respond`` endpoint to deliver the decision.
"""

PERMISSION_TIMEOUT_SECONDS = 300  # 5 minutes


# ---------------------------------------------------------------------------
# Error detail extraction
# ---------------------------------------------------------------------------

def _extract_error_detail(exc: Exception, task_id: str) -> str:
    """Build a detailed error message from an SDK exception.

    Inspects known SDK exception types (``ProcessError``,
    ``CLINotFoundError``, ``CLIJSONDecodeError``) to extract structured
    information (exit code, stderr, malformed output) that would otherwise
    be buried in the generic ``str(exc)`` representation.

    NOTE: The SDK sometimes wraps ProcessError in a generic Exception,
    losing the structured attributes. We parse the string message as
    a fallback to extract exit code information.
    """

    exc_type = type(exc).__name__
    exc_str = str(exc)

    # -- ProcessError: CLI process exited with non-zero code ----------------
    if _ProcessError is not None and isinstance(exc, _ProcessError):
        exit_code = getattr(exc, "exit_code", None)
        stderr = getattr(exc, "stderr", None)

        parts = [f"[{exc_type}] Claude CLI process failed"]
        if exit_code is not None:
            parts.append(f"exit_code={exit_code}")
        if stderr:
            parts.append(f"stderr:\n{stderr}")
        else:
            parts.append("(no stderr captured)")

        detail = " | ".join(parts[:2])
        if stderr:
            detail += f"\n{parts[2]}"
        else:
            detail += f" | {parts[2]}"

        logger.error(
            "Task %s ProcessError: exit_code=%s, stderr=%s",
            task_id, exit_code, stderr[:500] if stderr else None,
        )
        return detail

    # -- CLINotFoundError: claude binary not on PATH ------------------------
    if _CLINotFoundError is not None and isinstance(exc, _CLINotFoundError):
        logger.error("Task %s CLINotFoundError: %s", task_id, exc)
        return (
            f"[{exc_type}] Claude Code CLI not found. "
            "Ensure 'claude' is installed and available on the system PATH."
        )

    # -- CLIJSONDecodeError: malformed CLI output ---------------------------
    if _CLIJSONDecodeError is not None and isinstance(exc, _CLIJSONDecodeError):
        line = getattr(exc, "line", "")
        original = getattr(exc, "original_error", None)
        logger.error(
            "Task %s CLIJSONDecodeError: line=%s, original=%s",
            task_id, line[:200] if line else None, original,
        )
        return (
            f"[{exc_type}] Failed to parse CLI output. "
            f"Malformed line: {line[:200]}"
        )

    # -- CLIConnectionError: generic connection failure ---------------------
    if _CLIConnectionError is not None and isinstance(exc, _CLIConnectionError):
        logger.error("Task %s CLIConnectionError: %s", task_id, exc)
        return f"[{exc_type}] {exc}"

    # -- Generic Exception with ProcessError-like message -------------------
    # The SDK sometimes raises Exception("Command failed...") instead of
    # ProcessError, so we parse the message string to extract exit code.
    if "Command failed with exit code" in exc_str:
        import re
        # Extract exit code from message like "Command failed with exit code 1 (exit code: 1)"
        match = re.search(r"exit code[:\s]+(\d+)", exc_str)
        exit_code_str = match.group(1) if match else "unknown"

        detail = (
            f"[CLI Process Failed] Claude Code CLI terminated with exit_code={exit_code_str}\n"
            f"Note: The SDK wrapped this error, stderr details are not available.\n"
            f"Original error: {exc_str}"
        )
        logger.error("Task %s CLI process failed: exit_code=%s, raw_message=%s",
                    task_id, exit_code_str, exc_str)
        return detail

    # -- Fallback: unknown exception ----------------------------------------
    logger.exception("Task %s unexpected error (%s)", task_id, exc_type)
    return f"[{exc_type}] {exc}"


# ---------------------------------------------------------------------------
# Wrapper
# ---------------------------------------------------------------------------

class SdkWrapper:
    """Thin facade around the Claude Code SDK ``query()`` function."""

    # -- Environment ---------------------------------------------------------

    @staticmethod
    def _build_env(
        api_key: str | None = None,
        auth_token: str | None = None,
        base_url: str | None = None,
    ) -> dict[str, str]:
        """Build an environment-variable dict to inject into the CLI subprocess.

        Per-request overrides take priority over global settings.
        Only non-empty values are included so that the CLI falls back to its
        own defaults when the worker has no explicit configuration.
        """

        env: dict[str, str] = {}

        # Prevent "nested session" detection when the Worker itself runs
        # inside a Claude Code terminal (CLAUDECODE env var inherited).
        if os.environ.get("CLAUDECODE"):
            env["CLAUDECODE"] = ""

        key = api_key or settings.anthropic_api_key
        token = auth_token or settings.anthropic_auth_token
        url = base_url or settings.anthropic_base_url
        if key:
            env["ANTHROPIC_API_KEY"] = key
        if token:
            env["ANTHROPIC_AUTH_TOKEN"] = token
        if url:
            env["ANTHROPIC_BASE_URL"] = url
        return env

    # -- Agent Teams ---------------------------------------------------------

    @staticmethod
    def _apply_agents_config(
        extra_args: dict | None,
        options_kwargs: dict[str, Any],
    ) -> None:
        """Extract Agent Teams config from *extra_args* and set the
        first-class ``agents`` parameter on *options_kwargs*.

        The new ``claude-agent-sdk`` accepts ``agents`` as a
        ``dict[str, AgentDefinition]`` directly, which is more robust
        than passing a JSON string via ``extra_args``.

        If parsing fails, the raw value is kept in ``extra_args`` as a
        fallback so the CLI can still attempt to interpret it.
        """

        if not extra_args or "agents" not in extra_args:
            if extra_args:
                options_kwargs["extra_args"] = extra_args
            return

        agents_value = extra_args.pop("agents")

        if _AgentDefinition is not None and agents_value:
            try:
                # agents_value is a JSON string from the Java backend
                agents_raw: dict = json.loads(agents_value) if isinstance(agents_value, str) else agents_value
                valid_fields = {"description", "prompt", "tools", "model"}
                agents = {
                    name: _AgentDefinition(**{k: v for k, v in defn.items() if k in valid_fields})
                    for name, defn in agents_raw.items()
                }
                options_kwargs["agents"] = agents
                logger.info("Agent Teams configured: %s", list(agents.keys()))
            except (json.JSONDecodeError, TypeError, KeyError) as exc:
                logger.warning("Failed to parse agents config, using extra_args fallback: %s", exc)
                extra_args["agents"] = agents_value
        else:
            # Old SDK or no AgentDefinition — keep in extra_args
            extra_args["agents"] = agents_value

        # Pass remaining extra_args (if any) through
        if extra_args:
            options_kwargs["extra_args"] = extra_args

    # -- Image attachments ---------------------------------------------------

    @staticmethod
    def _save_images(
        cwd: str,
        images: list[dict[str, str]],
    ) -> list[str]:
        """Save base64-encoded images to ``cwd/.foggy-attachments/``.

        Returns relative paths (from *cwd*) of the saved files.
        """

        attach_dir = Path(cwd) / ".foggy-attachments"
        attach_dir.mkdir(parents=True, exist_ok=True)

        saved: list[str] = []
        max_image_bytes = 20 * 1024 * 1024  # 20MB decoded limit per image

        for img in images:
            name = img.get("name", "image.png")
            data_b64 = img.get("data", "")
            if not data_b64:
                continue

            # Sanitize filename: strip path components to prevent traversal
            name = Path(name).name
            if not name or name.startswith("."):
                name = "image.png"

            # Size check on base64 payload (decoded ~= len*3/4)
            if len(data_b64) > max_image_bytes * 4 // 3:
                logger.warning("Image %s exceeds size limit (%d bytes b64), skipping", name, len(data_b64))
                continue

            file_path = attach_dir / name
            file_path.write_bytes(base64.b64decode(data_b64))
            saved.append(f".foggy-attachments/{name}")
            logger.info("Saved image attachment: %s (%d bytes)", file_path, file_path.stat().st_size)

        return saved

    @staticmethod
    def _augment_prompt_with_images(prompt: str, image_paths: list[str]) -> str:
        """Prepend image reading instructions to the user prompt."""

        if not image_paths:
            return prompt

        paths_list = "\n".join(f"- {p}" for p in image_paths)
        return (
            f"[Attached images — use the Read tool to view them before responding]\n"
            f"{paths_list}\n\n"
            f"{prompt}"
        )

    # -- Query ---------------------------------------------------------------

    async def run_query(
        self,
        task_id: str,
        prompt: str,
        cwd: str,
        session_id: str | None = None,
        max_turns: int | None = None,
        model: str | None = None,
        extra_args: dict | None = None,
        images: list[dict[str, str]] | None = None,
        api_key: str | None = None,
        auth_token: str | None = None,
        base_url: str | None = None,
        permission_mode: str | None = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        """Run a Claude Code query and yield mapped SSE event dicts.

        The generator registers itself in :data:`task_registry` on entry and
        removes itself on exit so that other endpoints (health, abort) can
        inspect and cancel running tasks.

        Timeout strategy:
        - **Hard timeout**: absolute max duration (default 4h, configurable)
        - **Heartbeat timeout**: if no SDK event arrives within N seconds
          (default 10min), the task is considered hung and cancelled
        """

        if not _sdk_available:
            yield event_mapper.map_error(
                task_id=task_id,
                error=(
                    "Claude Code SDK is not installed. "
                    "Install claude-agent-sdk to enable queries."
                ),
            )
            return

        # Save attached images and augment prompt with file paths.
        if images:
            try:
                saved_paths = self._save_images(cwd, images)
                if saved_paths:
                    prompt = self._augment_prompt_with_images(prompt, saved_paths)
                    logger.info("Task %s: saved %d image(s), prompt augmented", task_id, len(saved_paths))
            except Exception as exc:
                logger.warning("Task %s: failed to save images: %s", task_id, exc)

        # Register the task so that it can be observed / cancelled.
        task_registry[task_id] = {
            "prompt": prompt,
            "cwd": cwd,
            "session_id": session_id,
            "started_at": datetime.now(timezone.utc).isoformat(),
            "asyncio_task": asyncio.current_task(),
        }

        current_session_id: str | None = session_id
        hard_timeout = settings.task_hard_timeout_seconds
        heartbeat_timeout = settings.task_heartbeat_timeout_seconds

        try:
            env = self._build_env(api_key=api_key, auth_token=auth_token, base_url=base_url)

            # Build SDK options.  We use keyword arguments so that the call
            # works with both ``ClaudeAgentOptions`` and ``ClaudeCodeOptions``.
            options_kwargs: dict[str, Any] = {
                "cwd": cwd,
            }
            if env:
                options_kwargs["env"] = env
            if max_turns is not None:
                options_kwargs["max_turns"] = max_turns
            if model is not None:
                options_kwargs["model"] = model
            if session_id is not None:
                options_kwargs["resume"] = session_id

            # claude-agent-sdk: load filesystem settings (CLAUDE.md, etc.)
            # and use first-class agents parameter instead of extra_args.
            if _use_agent_sdk:
                options_kwargs["setting_sources"] = ["user", "project", "local"]
                self._apply_agents_config(extra_args, options_kwargs)

                # Permission mode: set SDK permission_mode and can_use_tool callback
                if permission_mode and permission_mode != "bypassPermissions":
                    options_kwargs["permission_mode"] = permission_mode
                elif permission_mode == "bypassPermissions":
                    options_kwargs["permission_mode"] = "bypassPermissions"
            elif extra_args:
                options_kwargs["extra_args"] = extra_args

            # Determine auth mode for logging
            eff_key = api_key or settings.anthropic_api_key
            eff_token = auth_token or settings.anthropic_auth_token
            eff_url = base_url or settings.anthropic_base_url
            if eff_key:
                auth_mode = "API_KEY"
                auth_hint = eff_key[:8] + "..." + eff_key[-4:] if len(eff_key) > 12 else "***"
            elif eff_token:
                auth_mode = "CUSTOM_ENDPOINT"
                auth_hint = eff_token[:8] + "..." + eff_token[-4:] if len(eff_token) > 12 else "***"
            else:
                auth_mode = "SUBSCRIPTION"
                auth_hint = "(claude login)"

            logger.info(
                "Task %s SDK call: prompt=%s, cwd=%s, session_id=%s, model=%s, "
                "auth_mode=%s, auth_hint=%s, base_url=%s, "
                "has_agents=%s, has_env=%s, hard_timeout=%ss, heartbeat_timeout=%ss",
                task_id,
                repr(prompt[:80]) if prompt else None,
                cwd,
                session_id,
                model,
                auth_mode,
                auth_hint,
                eff_url or "(default)",
                "agents" in options_kwargs,
                bool(env),
                hard_timeout,
                heartbeat_timeout,
            )

            # -- can_use_tool callback (Producer/Queue pattern) -----------------
            # When permission_mode is interactive ("default" or "acceptEdits"),
            # we install a can_use_tool callback that pushes permission_request
            # events into the Queue and awaits user response.
            #
            # The Producer/Queue architecture:
            #   Producer task: iterates SDK query(), pushes events into Queue
            #   Consumer: run_query() reads from Queue and yields (SSE stream)
            # This lets the SSE stream stay alive while can_use_tool blocks.

            queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()
            use_queue = (
                _use_agent_sdk
                and _PermissionResultAllow is not None
                and permission_mode in ("default", "acceptEdits")
            )

            if use_queue:
                async def _can_use_tool(
                    tool_name: str,
                    tool_input: dict[str, Any],
                    _ctx: Any,
                ) -> Any:
                    """Callback invoked by the SDK when a tool needs permission."""
                    import uuid as _uuid
                    pid = str(_uuid.uuid4())[:12]

                    evt = asyncio.Event()
                    permission_pending[pid] = {
                        "event": evt,
                        "result": None,
                        "deny_message": None,
                        "task_id": task_id,
                    }

                    # Push permission_request event into the queue
                    await queue.put(event_mapper.map_permission_request(
                        task_id=task_id,
                        permission_id=pid,
                        tool_name=tool_name,
                        tool_input=tool_input,
                        session_id=current_session_id,
                    ))

                    logger.info(
                        "Task %s awaiting permission: pid=%s, tool=%s",
                        task_id, pid, tool_name,
                    )

                    try:
                        await asyncio.wait_for(evt.wait(), timeout=PERMISSION_TIMEOUT_SECONDS)
                    except asyncio.TimeoutError:
                        logger.warning("Task %s permission timeout: pid=%s", task_id, pid)
                        permission_pending.pop(pid, None)
                        return _PermissionResultDeny(message="Permission request timed out (5 minutes)")

                    entry = permission_pending.pop(pid, None)
                    if entry and entry["result"] == "allow":
                        return _PermissionResultAllow()
                    else:
                        msg = (entry or {}).get("deny_message") or "Permission denied by user"
                        return _PermissionResultDeny(message=msg)

                options_kwargs["can_use_tool"] = _can_use_tool

            options = _options_cls(**options_kwargs)

            current_model: str | None = None
            started_at = asyncio.get_event_loop().time()
            last_event_at = started_at

            # -- Helper: process a single SDK message into event dicts --------

            def _process_message(message: Any) -> list[dict[str, Any]]:
                nonlocal current_model, current_session_id, last_event_at
                events: list[dict[str, Any]] = []

                if _AssistantMessage is not None and isinstance(message, _AssistantMessage):
                    msg_model = getattr(message, "model", None)
                    if msg_model:
                        current_model = msg_model

                    for block in message.content:
                        if _TextBlock is not None and isinstance(block, _TextBlock):
                            events.append(event_mapper.map_assistant_text(
                                task_id=task_id,
                                text=block.text,
                                session_id=current_session_id,
                                model=current_model,
                            ))
                        elif _ToolUseBlock is not None and isinstance(block, _ToolUseBlock):
                            events.append(event_mapper.map_tool_use(
                                task_id=task_id,
                                tool_name=block.name,
                                tool_input=block.input,
                                session_id=current_session_id,
                            ))
                        elif _ToolResultBlock is not None and isinstance(block, _ToolResultBlock):
                            content_str: str | None = None
                            if isinstance(block.content, str):
                                content_str = block.content
                            elif isinstance(block.content, list):
                                parts = []
                                for item in block.content:
                                    if isinstance(item, dict):
                                        parts.append(item.get("text", str(item)))
                                    else:
                                        parts.append(str(item))
                                content_str = "\n".join(parts)

                            events.append(event_mapper.map_tool_result(
                                task_id=task_id,
                                tool_use_id=block.tool_use_id,
                                content=content_str,
                                is_error=bool(block.is_error),
                                session_id=current_session_id,
                            ))

                elif _ResultMessage is not None and isinstance(message, _ResultMessage):
                    current_session_id = getattr(message, "session_id", current_session_id)

                    if current_session_id:
                        now_dt = datetime.now(timezone.utc)
                        if current_session_id in session_store:
                            session_store[current_session_id]["updated_at"] = now_dt
                        else:
                            session_store[current_session_id] = {
                                "cwd": cwd,
                                "created_at": now_dt,
                                "updated_at": now_dt,
                                "slug": None,
                                "git_branch": None,
                            }

                    input_tokens: int | None = None
                    output_tokens: int | None = None
                    usage = getattr(message, "usage", None)
                    if isinstance(usage, dict):
                        input_tokens = usage.get("input_tokens")
                        output_tokens = usage.get("output_tokens")

                    num_turns = getattr(message, "num_turns", None)

                    events.append(event_mapper.map_result(
                        task_id=task_id,
                        result_text=getattr(message, "result", None),
                        cost_usd=getattr(message, "total_cost_usd", None),
                        duration_ms=getattr(message, "duration_ms", None),
                        session_id=current_session_id,
                        input_tokens=input_tokens,
                        output_tokens=output_tokens,
                        num_turns=num_turns,
                        model=current_model,
                    ))

                elif _SystemMessage is not None and isinstance(message, _SystemMessage):
                    data = getattr(message, "data", {}) or {}
                    subtype = getattr(message, "subtype", "system")

                    if subtype == "init" and isinstance(data, dict):
                        init_session_id = data.get("session_id")
                        if init_session_id:
                            current_session_id = init_session_id

                    if subtype in ("hook_started", "hook_response"):
                        return events

                    events.append(event_mapper.map_system(
                        task_id=task_id,
                        subtype=subtype,
                        data=data if subtype == "init" else None,
                        session_id=current_session_id,
                    ))

                return events

            # -- Main iteration: Queue-based (interactive) or direct ----------

            if use_queue:
                # Producer task: iterate SDK query() and push events into Queue
                async def _producer() -> None:
                    try:
                        async for message in _query_fn(prompt=prompt, options=options):
                            for evt in _process_message(message):
                                await queue.put(evt)
                    except asyncio.CancelledError:
                        await queue.put(event_mapper.map_error(
                            task_id=task_id,
                            error="Task was cancelled",
                            session_id=current_session_id,
                        ))
                    except Exception as exc:
                        error_detail = _extract_error_detail(exc, task_id)
                        await queue.put(event_mapper.map_error(
                            task_id=task_id,
                            error=error_detail,
                            session_id=current_session_id,
                        ))
                    finally:
                        await queue.put(None)  # Sentinel: stream is done

                producer_task = asyncio.create_task(_producer())

                try:
                    while True:
                        now = asyncio.get_event_loop().time()
                        if now - started_at > hard_timeout:
                            logger.warning("Task %s exceeded hard timeout (%ss)", task_id, hard_timeout)
                            yield event_mapper.map_error(
                                task_id=task_id,
                                error=f"Task exceeded maximum duration ({hard_timeout // 3600}h)",
                                session_id=current_session_id,
                            )
                            producer_task.cancel()
                            return

                        try:
                            evt = await asyncio.wait_for(queue.get(), timeout=heartbeat_timeout)
                        except asyncio.TimeoutError:
                            logger.warning("Task %s no events for %ss (heartbeat timeout)", task_id, heartbeat_timeout)
                            yield event_mapper.map_error(
                                task_id=task_id,
                                error=f"Task stalled (no events for {heartbeat_timeout // 60}min)",
                                session_id=current_session_id,
                            )
                            producer_task.cancel()
                            return

                        if evt is None:
                            break  # Producer finished

                        last_event_at = asyncio.get_event_loop().time()
                        yield evt
                finally:
                    if not producer_task.done():
                        producer_task.cancel()
                        try:
                            await producer_task
                        except asyncio.CancelledError:
                            pass

            else:
                # Direct iteration (no can_use_tool callback needed)
                async for message in _query_fn(prompt=prompt, options=options):
                    now = asyncio.get_event_loop().time()

                    if now - started_at > hard_timeout:
                        logger.warning("Task %s exceeded hard timeout (%ss)", task_id, hard_timeout)
                        yield event_mapper.map_error(
                            task_id=task_id,
                            error=f"Task exceeded maximum duration ({hard_timeout // 3600}h)",
                            session_id=current_session_id,
                        )
                        return

                    if now - last_event_at > heartbeat_timeout:
                        logger.warning("Task %s no events for %ss (heartbeat timeout)", task_id, heartbeat_timeout)
                        yield event_mapper.map_error(
                            task_id=task_id,
                            error=f"Task stalled (no events for {heartbeat_timeout // 60}min)",
                            session_id=current_session_id,
                        )
                        return

                    last_event_at = now

                    for evt in _process_message(message):
                        yield evt

        except asyncio.CancelledError:
            logger.info("Task %s was cancelled", task_id)
            yield event_mapper.map_error(
                task_id=task_id,
                error="Task was cancelled",
                session_id=current_session_id,
            )

        except Exception as exc:
            error_detail = _extract_error_detail(exc, task_id)
            yield event_mapper.map_error(
                task_id=task_id,
                error=error_detail,
                session_id=current_session_id,
            )

        finally:
            task_registry.pop(task_id, None)
            # Clean up any pending permissions for this task
            for pid in list(permission_pending):
                if permission_pending[pid].get("task_id") == task_id:
                    permission_pending.pop(pid, None)
