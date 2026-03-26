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

# Known CLI help/error text signatures.  When a ``ResultMessage`` contains
# one of these strings *and* consumed zero tokens, the prompt was likely
# misinterpreted as a CLI slash-command rather than sent to the LLM.
_CLI_ERROR_SIGNATURES = (
    "Commands are in the form",
    "Available commands:",
    "Unknown command",
)

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
    _PermissionUpdate = None
    try:
        from claude_agent_sdk import (  # type: ignore[import-untyped]
            PermissionResultAllow as _PRA,
            PermissionResultDeny as _PRD,
            PermissionUpdate as _PU,
        )
        _PermissionResultAllow = _PRA
        _PermissionResultDeny = _PRD
        _PermissionUpdate = _PU
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

PERMISSION_TIMEOUT_SECONDS = 300  # 5 minutes (regular tool permissions)
INTERACTIVE_TIMEOUT_SECONDS = 1800  # 30 minutes (plan review & user questions)

# Grace period before killing CLI after SSE consumer disconnects.
# Default 1 hour — Java backend restart / network recovery may take time,
# and idle CLI processes are lightweight.  Configurable via env var.
DISCONNECT_GRACE_SECONDS = int(os.environ.get("DISCONNECT_GRACE_SECONDS", "3600"))


# ---------------------------------------------------------------------------
# Grace period watcher — runs as independent asyncio.Task
# ---------------------------------------------------------------------------

async def _grace_period_watcher(
    task_id: str,
    producer_task: asyncio.Task,
    grace_seconds: int,
) -> None:
    """Background task: keeps CLI alive while waiting for SSE subscriber reconnection.

    Spawned as an independent ``asyncio.Task`` when the original SSE consumer
    disconnects but the CLI process is still alive.  This avoids the problem
    where ``await asyncio.sleep()`` inside a cancelled generator's ``finally``
    block raises ``CancelledError`` immediately, defeating the grace period.

    Lifecycle:
      1. Polls ``task_registry[task_id]["connected"]`` every 30s.
      2. If a new subscriber reconnects (via ``/subscribe``), exits cleanly
         — the new subscriber's generator now owns the task lifecycle.
      3. If ``producer_task`` finishes on its own (CLI exited), exits cleanly.
      4. If grace period expires with no reconnection, cancels ``producer_task``
         and cleans up the registry entry.
    """
    check_interval = 30
    grace_remaining = grace_seconds

    try:
        while grace_remaining > 0 and not producer_task.done():
            await asyncio.sleep(min(check_interval, grace_remaining))
            grace_remaining -= check_interval

            entry = task_registry.get(task_id)
            if entry is None:
                # Registry entry was removed externally (e.g. abort) — stop watching
                logger.info(
                    "Grace watcher: task %s registry entry gone, stopping", task_id
                )
                return

            if entry.get("connected"):
                # A new subscriber reconnected via /subscribe — exit cleanly
                logger.info(
                    "Grace watcher: task %s reconnected during grace period! "
                    "CLI kept alive.",
                    task_id,
                )
                return

        # Grace period expired or producer finished
        if not producer_task.done():
            # Rule 2: Only user can kill CLI — do NOT cancel producer_task.
            # Just clean up registry to release memory; CLI continues running.
            logger.warning(
                "Grace watcher: task %s grace period expired with no reconnection. "
                "Cleaning up registry only — CLI continues running (Rule 2).",
                task_id,
            )
            entry = task_registry.get(task_id)
            if entry:
                entry["orphaned"] = True  # Mark for status endpoint reporting
        else:
            logger.info(
                "Grace watcher: task %s producer finished during grace period",
                task_id,
            )

    except asyncio.CancelledError:
        logger.info("Grace watcher: task %s watcher cancelled", task_id)
    except Exception as exc:
        logger.warning("Grace watcher: task %s unexpected error: %s", task_id, exc)
    finally:
        # Cleanup — but only if no subscriber took over
        entry = task_registry.get(task_id)
        if entry and not entry.get("has_external_subscriber"):
            task_registry.pop(task_id, None)
            # Clean up any pending permissions for this task
            for pid in list(permission_pending):
                if permission_pending[pid].get("task_id") == task_id:
                    permission_pending.pop(pid, None)
            logger.info(
                "Grace watcher: task %s cleaned up registry (no subscriber took over)",
                task_id,
            )
            # Log surviving CLI processes for diagnostics
            try:
                surviving = _find_sdk_cli_pids()
                if surviving:
                    logger.info(
                        "Grace watcher: task %s ended — %d CLI process(es) still alive: %s",
                        task_id, len(surviving), surviving,
                    )
            except Exception:
                pass
        elif entry and entry.get("has_external_subscriber"):
            logger.info(
                "Grace watcher: task %s subscriber active, skipping cleanup",
                task_id,
            )


# ---------------------------------------------------------------------------
# Event broadcast — replaces single asyncio.Queue for multi-subscriber SSE
# ---------------------------------------------------------------------------

class EventBroadcast:
    """Fan-out event distributor for SSE reconnection with ESN (Event Sequence Number).

    The producer pushes events via :meth:`put`.  Each SSE connection calls
    :meth:`subscribe` to get its own ``asyncio.Queue`` that receives a copy
    of every event.  Events are also stored in a replay buffer so that new
    subscribers can catch up from a given sequence number.

    Every event is assigned a monotonically increasing ``seq`` field (starting
    at 1) before it enters the history buffer.  The Java backend uses this
    seq to implement precise ACK-based replay on reconnection.

    An optional :class:`~agent_worker.persistence.protocol.EventStore` backend
    can be injected for durable event persistence (file / DB / etc.).
    """

    def __init__(
        self,
        task_id: str | None = None,
        event_store: Any | None = None,
    ) -> None:
        self._subscribers: list[asyncio.Queue] = []
        self._history: list[dict[str, Any]] = []
        self._closed = False
        self._seq_counter: int = 0
        self._task_id = task_id
        self._event_store = event_store  # EventStore protocol (persistence layer)

    def subscribe(self, ack_seq: int = 0) -> asyncio.Queue:
        """Create a new subscriber queue.

        Replays all events whose ``seq > ack_seq`` so the subscriber can
        catch up on missed events.  Use ``ack_seq=0`` (default) to replay
        everything from the beginning (since seq starts at 1).

        For backward compatibility, passing the old index-based value still
        works because the seq values are sequential starting from 1.
        """
        q: asyncio.Queue = asyncio.Queue()
        # Replay events with seq > ack_seq
        for evt in self._history:
            if evt.get("seq", 0) > ack_seq:
                q.put_nowait(evt)
        if self._closed:
            q.put_nowait(None)  # Stream already done
        self._subscribers.append(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        try:
            self._subscribers.remove(q)
        except ValueError:
            pass

    async def _persist_event(self, item: dict[str, Any]) -> None:
        """Offload durable persistence to a worker thread."""
        if not (self._event_store and self._task_id):
            return
        try:
            await asyncio.to_thread(self._event_store.append, self._task_id, item)
        except Exception:
            logger.warning(
                "Event persistence failed for task %s seq %d",
                self._task_id, self._seq_counter, exc_info=True,
            )

    async def _mark_closed(self) -> None:
        """Offload stream-close persistence to a worker thread."""
        if not (self._event_store and self._task_id):
            return
        try:
            await asyncio.to_thread(self._event_store.mark_closed, self._task_id)
        except Exception:
            logger.warning(
                "Failed to mark task %s as closed in event store",
                self._task_id, exc_info=True,
            )

    async def put(self, item: dict[str, Any] | None) -> None:
        if item is not None:
            self._seq_counter += 1
            item["seq"] = self._seq_counter
            self._history.append(item)
            await self._persist_event(item)
        else:
            self._closed = True
            await self._mark_closed()
        for q in list(self._subscribers):  # snapshot to avoid mutation during iteration
            await q.put(item)

    @property
    def event_count(self) -> int:
        return len(self._history)

    @property
    def latest_seq(self) -> int:
        """Return the sequence number of the most recent event, or 0 if empty."""
        return self._seq_counter

    @property
    def closed(self) -> bool:
        return self._closed


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
    # Try to extract details from chained exceptions (__cause__ / __context__)
    chained = exc.__cause__ or exc.__context__
    if chained is not None:
        chained_type = type(chained).__name__
        chained_exit_code = getattr(chained, "exit_code", None)
        chained_stderr = getattr(chained, "stderr", None)
        if chained_exit_code is not None or chained_stderr:
            logger.error(
                "Task %s %s (wrapping %s): exit_code=%s, stderr=%s",
                task_id, exc_type, chained_type,
                chained_exit_code,
                chained_stderr[:500] if chained_stderr else None,
            )
            parts = [f"[{exc_type} wrapping {chained_type}]"]
            if chained_exit_code is not None:
                parts.append(f"exit_code={chained_exit_code}")
            if chained_stderr:
                return " | ".join(parts) + f"\nstderr:\n{chained_stderr}"
            else:
                parts.append("(no stderr captured)")
                return " | ".join(parts)

    logger.exception("Task %s unexpected error (%s)", task_id, exc_type)
    return f"[{exc_type}] {exc}"


# ---------------------------------------------------------------------------
# Wrapper
# ---------------------------------------------------------------------------

def _find_sdk_cli_pids() -> set[int]:
    """Return PIDs of Claude CLI processes spawned by the SDK.

    Dual-layer architecture:
      Layer 1 (primary): tracked PIDs registered at launch time — O(1) lookup.
      Layer 2 (fallback): platform-specific detector (process-tree, pgrep, etc.).
    Results are merged (union) with dead processes pruned.
    """
    from .process_detection import get_detector, get_tracked_pids
    tracked = get_tracked_pids()           # Layer 1: proactive registry
    detected = get_detector().find_pids()  # Layer 2: platform detection fallback
    return tracked | detected


def _capture_child_pids(task_id: str) -> None:
    """Scan current process's children for CLI processes and register them."""
    try:
        import psutil
        from .process_detection import register_pid
        me = psutil.Process(os.getpid())
        for child in me.children(recursive=True):
            try:
                name = child.name()
                # On Windows psutil returns "node.exe" / "claude.exe"
                basename = name.rsplit(".", 1)[0] if name.endswith(".exe") else name
                if basename in ("node", "claude"):
                    register_pid(child.pid, task_id)
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                pass
    except ImportError:
        pass
    except Exception:
        pass



class SdkWrapper:
    """Thin facade around the Claude Code SDK ``query()`` function."""

    # -- Environment ---------------------------------------------------------

    @staticmethod
    def _build_env(
        api_key: str | None = None,
        auth_token: str | None = None,
        base_url: str | None = None,
        navigator_api_key: str | None = None,
        navigator_api_base: str | None = None,
        extra_env_vars: dict[str, str] | None = None,
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
        # Navigator service token — allows CLI skills to call Navigator API
        if navigator_api_key:
            env["NAVIGATOR_TOKEN"] = navigator_api_key
        # Navigator platform base URL — allows CLI skills to discover Navigator API
        nav_base = navigator_api_base or settings.navigator_api_base
        if nav_base:
            env["NAVIGATOR_API_BASE"] = nav_base
        # Extra env vars from LLM model config (e.g. CLAUDE_AUTOCOMPACT_PCT_OVERRIDE)
        if extra_env_vars:
            env.update(extra_env_vars)
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
                # Auto-enable the experimental Agent Teams feature flag
                # so the CLI spawns teammates without requiring manual env setup.
                env = options_kwargs.get("env")
                if isinstance(env, dict):
                    env["CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS"] = "1"
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

    # -- File / image attachments ----------------------------------------------

    @staticmethod
    def _save_images(
        cwd: str,
        images: list[dict[str, str]],
    ) -> list[str]:
        """Save base64-encoded attachments to ``cwd/.foggy-attachments/``.

        Accepts both image and non-image files (same JSON format).
        Returns relative paths (from *cwd*) of the saved files.
        """

        attach_dir = Path(cwd) / ".foggy-attachments"
        attach_dir.mkdir(parents=True, exist_ok=True)

        saved: list[str] = []
        max_file_bytes = 20 * 1024 * 1024  # 20MB decoded limit per file

        for img in images:
            name = img.get("name", "attachment")
            data_b64 = img.get("data", "")
            if not data_b64:
                continue

            # Sanitize filename: strip path components to prevent traversal
            name = Path(name).name
            if not name or name.startswith("."):
                name = "attachment"

            # Size check on base64 payload (decoded ~= len*3/4)
            if len(data_b64) > max_file_bytes * 4 // 3:
                logger.warning("Attachment %s exceeds size limit (%d bytes b64), skipping", name, len(data_b64))
                continue

            file_path = attach_dir / name
            file_path.write_bytes(base64.b64decode(data_b64))
            saved.append(f".foggy-attachments/{name}")
            logger.info("Saved attachment: %s (%d bytes)", file_path, file_path.stat().st_size)

        return saved

    @staticmethod
    async def _save_images_async(
        cwd: str,
        images: list[dict[str, str]],
    ) -> list[str]:
        """Offload attachment writes to a worker thread."""
        return await asyncio.to_thread(SdkWrapper._save_images, cwd, images)

    @staticmethod
    def _augment_prompt_with_images(prompt: str, image_paths: list[str]) -> str:
        """Prepend file reading instructions to the user prompt."""

        if not image_paths:
            return prompt

        paths_list = "\n".join(f"- {p}" for p in image_paths)
        return (
            f"[Attached files — use the Read tool to view them before responding]\n"
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
        navigator_api_key: str | None = None,
        navigator_api_base: str | None = None,
        disallowed_tools: list[str] | None = None,
        foggy_task_id: str | None = None,
        foggy_session_id: str | None = None,
        extra_env_vars: dict[str, str] | None = None,
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
                saved_paths = await self._save_images_async(cwd, images)
                if saved_paths:
                    prompt = self._augment_prompt_with_images(prompt, saved_paths)
                    logger.info("Task %s: saved %d image(s), prompt augmented", task_id, len(saved_paths))
            except Exception as exc:
                logger.warning("Task %s: failed to save images: %s", task_id, exc)

        # Escape prompts starting with "/" to prevent the CLI from
        # interpreting them as slash commands (e.g. "/help", "/clear").
        # A leading newline breaks the pattern without altering semantics.
        if prompt.startswith("/"):
            prompt = "\n" + prompt
            logger.info("Task %s: escaped leading '/' in prompt to avoid CLI slash-command interpretation", task_id)

        # Register the task so that it can be observed / cancelled.
        task_registry[task_id] = {
            "prompt": prompt,
            "cwd": cwd,
            "session_id": session_id,
            "started_at": datetime.now(timezone.utc).isoformat(),
            "asyncio_task": asyncio.current_task(),
            "foggy_task_id": foggy_task_id,
            "foggy_session_id": foggy_session_id,
        }

        current_session_id: str | None = session_id
        hard_timeout = settings.task_hard_timeout_seconds
        heartbeat_timeout = settings.task_heartbeat_timeout_seconds

        try:
            env = self._build_env(api_key=api_key, auth_token=auth_token, base_url=base_url,
                                  navigator_api_key=navigator_api_key,
                                  navigator_api_base=navigator_api_base,
                                  extra_env_vars=extra_env_vars)

            # Inject Foggy platform tracking IDs as environment variables.
            # These don't affect CLI behavior but appear in process listings
            # (ps/wmic) and logs, making it easy to correlate Worker tasks
            # with Foggy platform entities.
            if foggy_task_id:
                env["FOGGY_TASK_ID"] = foggy_task_id
            if foggy_session_id:
                env["FOGGY_SESSION_ID"] = foggy_session_id

            # Build SDK options.  We use keyword arguments so that the call
            # works with both ``ClaudeAgentOptions`` and ``ClaudeCodeOptions``.
            options_kwargs: dict[str, Any] = {
                "cwd": cwd,
            }
            if env:
                options_kwargs["env"] = env
            if max_turns is not None:
                options_kwargs["max_turns"] = max_turns
            options_kwargs["model"] = model if model is not None else "opus[1m]"
            if session_id is not None:
                options_kwargs["resume"] = session_id

            # claude-agent-sdk: load filesystem settings (CLAUDE.md, etc.)
            # and use first-class agents parameter instead of extra_args.
            if _use_agent_sdk:
                options_kwargs["setting_sources"] = ["user", "project", "local"]
                # File checkpointing — always enable when using agent SDK
                options_kwargs["enable_file_checkpointing"] = True
                base_env = options_kwargs.get("env") or env or {}
                base_env["CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING"] = "1"
                # Extend stream-close timeout so stdin stays open for long
                # interactive sessions (matches reference implementation).
                base_env["CLAUDE_CODE_STREAM_CLOSE_TIMEOUT"] = "300000"
                options_kwargs["env"] = base_env
                self._apply_agents_config(extra_args, options_kwargs)
                if disallowed_tools:
                    options_kwargs["disallowed_tools"] = disallowed_tools
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
                "agents=%s, has_env=%s, disallowed_tools=%s, "
                "hard_timeout=%ss, heartbeat_timeout=%ss, "
                "foggy_task_id=%s, foggy_session_id=%s",
                task_id,
                repr(prompt[:80]) if prompt else None,
                cwd,
                session_id,
                model,
                auth_mode,
                auth_hint,
                eff_url or "(default)",
                list(options_kwargs["agents"].keys()) if "agents" in options_kwargs else "disabled",
                bool(env),
                disallowed_tools or "(none)",
                hard_timeout,
                heartbeat_timeout,
                foggy_task_id or "(none)",
                foggy_session_id or "(none)",
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

            from ..persistence.factory import get_event_store as _get_event_store
            _event_store = _get_event_store()
            broadcast = EventBroadcast(
                task_id=task_id,
                event_store=_event_store,
            )
            # Register foggy_task_id → worker task_id alias so that
            # persistence lookups by foggy_task_id work even after
            # the in-memory registry has been cleaned up.
            if foggy_task_id and hasattr(_event_store, "register_alias"):
                _event_store.register_alias(foggy_task_id, task_id)
            queue: asyncio.Queue[dict[str, Any] | None] = broadcast.subscribe()

            # Store broadcast in task_registry so the /subscribe endpoint
            # can create additional subscribers for SSE reconnection.
            task_registry[task_id]["broadcast"] = broadcast
            task_registry[task_id]["connected"] = True
            task_registry[task_id]["has_external_subscriber"] = False

            use_queue = (
                _use_agent_sdk
                and _PermissionResultAllow is not None
            )

            if use_queue:
                # Track effective permission mode — may change after plan review
                effective_permission_mode = permission_mode or "bypassPermissions"

                async def _can_use_tool(
                    tool_name: str,
                    tool_input: dict[str, Any],
                    ctx: Any,
                ) -> Any:
                    """Callback invoked by the SDK when a tool needs permission."""
                    nonlocal effective_permission_mode
                    import uuid as _uuid
                    pid = str(_uuid.uuid4())[:12]

                    logger.info(
                        "Task %s can_use_tool called: tool=%s, mode=%s, pid=%s, input_keys=%s",
                        task_id, tool_name, effective_permission_mode, pid,
                        list(tool_input.keys()) if tool_input else [],
                    )

                    is_question = (tool_name == "AskUserQuestion")
                    is_plan_review = (tool_name == "ExitPlanMode")

                    # Fast path: auto-allow ordinary tools based on permission mode
                    if not is_question and not is_plan_review:
                        if effective_permission_mode == "bypassPermissions":
                            logger.info(
                                "Task %s auto-approve (bypass): tool=%s, pid=%s",
                                task_id, tool_name, pid,
                            )
                            return _PermissionResultAllow()
                        if effective_permission_mode == "acceptEdits" and tool_name != "Bash":
                            logger.info(
                                "Task %s auto-approve (acceptEdits): tool=%s, pid=%s",
                                task_id, tool_name, pid,
                            )
                            return _PermissionResultAllow()

                    # Extract suggestions from ToolPermissionContext
                    suggestions = getattr(ctx, "suggestions", None) or []

                    evt = asyncio.Event()
                    permission_pending[pid] = {
                        "event": evt,
                        "result": None,
                        "deny_message": None,
                        "scope": "once",
                        "task_id": task_id,
                        "suggestions": suggestions,
                        "is_question": is_question,
                        "is_plan_review": is_plan_review,
                        "answers": None,
                        "questions": tool_input.get("questions") if is_question else None,
                        "tool_input": tool_input,
                    }

                    if is_question:
                        # Push user_question event with structured questions
                        await broadcast.put(event_mapper.map_user_question(
                            task_id=task_id,
                            permission_id=pid,
                            questions=tool_input.get("questions", []),
                            session_id=current_session_id,
                        ))
                        logger.info(
                            "Task %s awaiting user question: pid=%s, questions=%d",
                            task_id, pid, len(tool_input.get("questions", [])),
                        )
                    elif is_plan_review:
                        # Push plan_review event for ExitPlanMode
                        await broadcast.put(event_mapper.map_plan_review(
                            task_id=task_id,
                            permission_id=pid,
                            allowed_prompts=tool_input.get("allowedPrompts"),
                            plan=tool_input.get("plan"),
                            session_id=current_session_id,
                        ))
                        logger.info(
                            "Task %s awaiting plan review: pid=%s",
                            task_id, pid,
                        )
                    else:
                        # Push permission_request event into the queue
                        await broadcast.put(event_mapper.map_permission_request(
                            task_id=task_id,
                            permission_id=pid,
                            tool_name=tool_name,
                            tool_input=tool_input,
                            session_id=current_session_id,
                            has_suggestions=len(suggestions) > 0,
                        ))
                        logger.info(
                            "Task %s awaiting permission: pid=%s, tool=%s, suggestions=%d",
                            task_id, pid, tool_name, len(suggestions),
                        )

                    # Use longer timeout for interactive events (plan review, user questions)
                    timeout = INTERACTIVE_TIMEOUT_SECONDS if (is_plan_review or is_question) else PERMISSION_TIMEOUT_SECONDS
                    try:
                        await asyncio.wait_for(evt.wait(), timeout=timeout)
                    except asyncio.TimeoutError:
                        logger.warning("Task %s permission timeout (%ds): pid=%s", task_id, timeout, pid)
                        permission_pending.pop(pid, None)
                        # Ask Claude to pause gracefully so the user can resume later.
                        # This produces a clean "waiting" response rather than an error,
                        # letting the user simply send "continue" in a resume task.
                        return _PermissionResultDeny(
                            message=(
                                f"The user did not respond to the permission request within "
                                f"{timeout // 60} minutes. "
                                "Please stop what you are doing, write a brief summary of "
                                "your progress so far and exactly what you were about to do "
                                "next (tool, arguments, reason), then politely let the user "
                                "know they can reply 'continue' to resume from this point."
                            )
                        )

                    entry = permission_pending.pop(pid, None)
                    if entry and entry["result"] == "allow":
                        # AskUserQuestion: return PermissionResultAllow with answers in updated_input
                        if entry.get("is_question"):
                            answers = entry.get("answers") or {}
                            logger.info(
                                "Task %s question answered: pid=%s, answers=%s",
                                task_id, pid, answers,
                            )
                            updated = {
                                "questions": entry.get("questions") or [],
                                "answers": answers,
                            }
                            logger.info(
                                "Task %s returning updated_input to SDK: %s",
                                task_id, updated,
                            )
                            return _PermissionResultAllow(
                                updated_input=updated,
                            )

                        # ExitPlanMode: allow with original input, update permission mode
                        if entry.get("is_plan_review"):
                            plan_action = entry.get("plan_action")
                            if plan_action == "acceptEdits":
                                effective_permission_mode = "acceptEdits"
                            # "bypass" / "clearAndBypass" → keep bypassPermissions
                            logger.info(
                                "Task %s plan approved: pid=%s, plan_action=%s, new_mode=%s",
                                task_id, pid, plan_action, effective_permission_mode,
                            )
                            return _PermissionResultAllow(
                                updated_input=entry.get("tool_input") or tool_input,
                            )

                        scope = entry.get("scope", "once")
                        updated_permissions = None

                        # Build updated_permissions based on scope
                        if scope in ("session", "always") and _PermissionUpdate is not None:
                            dest = "session" if scope == "session" else "userSettings"
                            raw_suggestions = entry.get("suggestions") or []
                            if raw_suggestions:
                                # Use SDK's suggested rules, override destination
                                updated_permissions = []
                                for s in raw_suggestions:
                                    updated_permissions.append(_PermissionUpdate(
                                        type=getattr(s, "type", "addRules"),
                                        rules=getattr(s, "rules", None),
                                        behavior=getattr(s, "behavior", "allow"),
                                        destination=dest,
                                    ))
                            else:
                                # No suggestions: create a generic rule for this tool
                                try:
                                    from claude_agent_sdk.types import PermissionRuleValue  # type: ignore
                                    updated_permissions = [_PermissionUpdate(
                                        type="addRules",
                                        rules=[PermissionRuleValue(tool_name=tool_name)],
                                        behavior="allow",
                                        destination=dest,
                                    )]
                                except ImportError:
                                    pass

                        logger.info(
                            "Task %s permission allowed: pid=%s, scope=%s, rules=%d",
                            task_id, pid, scope,
                            len(updated_permissions) if updated_permissions else 0,
                        )
                        return _PermissionResultAllow(updated_permissions=updated_permissions)
                    else:
                        msg = (entry or {}).get("deny_message") or "Permission denied by user"
                        return _PermissionResultDeny(message=msg)

                options_kwargs["can_use_tool"] = _can_use_tool
                # Force "default" mode on the CLI so ALL tool calls route
                # through the control protocol (can_use_tool callback).
                # With "bypassPermissions" the CLI auto-approves internally
                # and never sends control requests — our callback would be
                # useless.  Our callback handles auto-approval based on
                # effective_permission_mode, so regular tools are still
                # instant while ExitPlanMode / AskUserQuestion are blocked.
                options_kwargs["permission_mode"] = "default"

                # Capture CLI stderr so we can diagnose startup/runtime
                # issues that are invisible without it.
                def _stderr_handler(line: str, _tid: str = task_id) -> None:
                    logger.info("Task %s CLI stderr: %s", _tid, line.rstrip())
                options_kwargs["stderr"] = _stderr_handler
            else:
                # No can_use_tool — set permission_mode directly on CLI
                if permission_mode:
                    options_kwargs["permission_mode"] = permission_mode

            logger.info(
                "Task %s SDK options: use_queue=%s, permission_mode=%s, "
                "has_can_use_tool=%s",
                task_id, use_queue,
                options_kwargs.get("permission_mode", "(not set)"),
                "can_use_tool" in options_kwargs,
            )
            options = _options_cls(**options_kwargs)

            current_model: str | None = None
            started_at = asyncio.get_event_loop().time()
            last_event_at = started_at

            # -- Helper: process a single SDK message into event dicts --------

            # Lookup table: tool_use_id → tool name (for correlating results)
            tool_id_to_name: dict[str, str] = {}

            def _process_message(message: Any) -> list[dict[str, Any]]:
                nonlocal current_model, current_session_id, last_event_at
                events: list[dict[str, Any]] = []

                if _AssistantMessage is not None and isinstance(message, _AssistantMessage):
                    msg_model = getattr(message, "model", None)
                    if msg_model:
                        current_model = msg_model
                        # Sync model to task_registry for process list enrichment
                        if task_id in task_registry:
                            task_registry[task_id]["model"] = msg_model

                    for block in message.content:
                        if _TextBlock is not None and isinstance(block, _TextBlock):
                            events.append(event_mapper.map_assistant_text(
                                task_id=task_id,
                                text=block.text,
                                session_id=current_session_id,
                                model=current_model,
                            ))
                        elif _ToolUseBlock is not None and isinstance(block, _ToolUseBlock):
                            block_id = getattr(block, "id", None)
                            if block_id:
                                tool_id_to_name[block_id] = block.name
                            events.append(event_mapper.map_tool_use(
                                task_id=task_id,
                                tool_name=block.name,
                                tool_input=block.input,
                                session_id=current_session_id,
                                tool_use_id=block_id,
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

                            resolved_name = tool_id_to_name.get(block.tool_use_id)
                            events.append(event_mapper.map_tool_result(
                                task_id=task_id,
                                tool_use_id=block.tool_use_id,
                                content=content_str,
                                is_error=bool(block.is_error),
                                session_id=current_session_id,
                                tool_name=resolved_name,
                            ))

                elif _ResultMessage is not None and isinstance(message, _ResultMessage):
                    current_session_id = getattr(message, "session_id", current_session_id)

                    # Sync session_id back to task_registry so that process
                    # listings can correlate this CLI process with its session
                    # even for newly created sessions (where the initial
                    # session_id was None).
                    if current_session_id and task_id in task_registry:
                        task_registry[task_id]["session_id"] = current_session_id

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
                    result_text = getattr(message, "result", None)

                    # Detect CLI help/error responses that indicate the prompt
                    # was misinterpreted (e.g. treated as a slash command).
                    # Zero tokens + known CLI help text → emit an error event
                    # *instead of* a result so Java treats this as a failure
                    # (emitting both would cause double cleanup in WorkerStreamRelay).
                    _is_cli_error = (
                        result_text
                        and input_tokens in (None, 0)
                        and output_tokens in (None, 0)
                        and any(sig in result_text for sig in _CLI_ERROR_SIGNATURES)
                    )
                    if _is_cli_error:
                        logger.warning(
                            "Task %s: CLI returned help/error text instead of LLM response: %s",
                            task_id, result_text[:200],
                        )
                        events.append(event_mapper.map_error(
                            task_id=task_id,
                            error=(
                                f"CLI 未执行任务，返回了帮助文本: \"{result_text[:120]}\"\n"
                                "可能原因: 提示词以 / 开头被 CLI 误解为斜杠命令。请重试。"
                            ),
                            session_id=current_session_id,
                        ))
                    else:
                        events.append(event_mapper.map_result(
                            task_id=task_id,
                            result_text=result_text,
                            cost_usd=getattr(message, "total_cost_usd", None),
                            duration_ms=getattr(message, "duration_ms", None),
                            session_id=current_session_id,
                            input_tokens=input_tokens,
                            output_tokens=output_tokens,
                            num_turns=num_turns,
                            model=current_model,
                        ))

                elif _UserMessage is not None and isinstance(message, _UserMessage):
                    msg_uuid = getattr(message, "uuid", None)
                    logger.info(
                        "Task %s UserMessage received: uuid=%s, has_content=%s, parent_tool_use_id=%s",
                        task_id, msg_uuid,
                        bool(getattr(message, "content", None)),
                        getattr(message, "parent_tool_use_id", None),
                    )
                    if msg_uuid:
                        events.append(event_mapper.map_checkpoint(
                            task_id=task_id,
                            checkpoint_id=msg_uuid,
                            session_id=current_session_id,
                        ))

                elif _SystemMessage is not None and isinstance(message, _SystemMessage):
                    data = getattr(message, "data", {}) or {}
                    subtype = getattr(message, "subtype", "system")

                    if subtype == "init" and isinstance(data, dict):
                        init_session_id = data.get("session_id")
                        if init_session_id:
                            current_session_id = init_session_id
                            # Sync to task_registry (same rationale as ResultMessage above)
                            if task_id in task_registry:
                                task_registry[task_id]["session_id"] = init_session_id

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
                # can_use_tool requires streaming mode (AsyncIterable prompt).
                # IMPORTANT: The SDK's stream_input() calls end_input() (closes
                # stdin) when the async iterable exhausts. But the control
                # protocol (can_use_tool responses) is sent via stdin. If stdin
                # closes, the CLI never receives our permission responses →
                # "connection error". Solution: keep the generator alive by
                # blocking on an Event that only fires when the task completes.
                stream_done_event = asyncio.Event()

                async def _wrap_prompt_as_stream(text: str):
                    yield {
                        "type": "user",
                        "session_id": session_id or "",
                        "message": {"role": "user", "content": text},
                        "parent_tool_use_id": None,
                    }
                    # Block here to keep stdin open for control protocol
                    await stream_done_event.wait()

                streaming_prompt = _wrap_prompt_as_stream(prompt)

                # Producer task: iterate SDK query() and push events into Queue
                #
                # NOTE: producer_task.cancel() causes CancelledError inside
                # ``async for``.  The generator cleanup triggers
                # ``athrow(GeneratorExit)`` → SDK ``query.close()`` →
                # anyio ``_tg.__aexit__``.  Because the athrow runs in a
                # different internal task context, anyio raises RuntimeError
                # ("cancel scope in a different task").  We must catch
                # *both* CancelledError and RuntimeError to prevent
                # "Task exception was never retrieved" log spam and ensure
                # the sentinel is always pushed.
                async def _producer() -> None:
                    _msg_count = 0
                    _last_pid_capture = asyncio.get_event_loop().time()
                    _PID_CAPTURE_INTERVAL = 60  # re-scan children every 60s for Agent Teams sub-CLIs
                    try:
                        logger.info("Task %s producer: starting SDK iteration", task_id)
                        _capture_child_pids(task_id)  # snapshot before first message
                        async for message in _query_fn(prompt=streaming_prompt, options=options):
                            _msg_count += 1
                            if _msg_count == 1:
                                _capture_child_pids(task_id)  # capture after first message
                            elif (asyncio.get_event_loop().time() - _last_pid_capture) >= _PID_CAPTURE_INTERVAL:
                                _capture_child_pids(task_id)  # periodic re-scan for late-spawned sub-CLIs
                                _last_pid_capture = asyncio.get_event_loop().time()
                            msg_type = type(message).__name__
                            logger.info(
                                "Task %s producer msg #%d: %s",
                                task_id, _msg_count, msg_type,
                            )
                            for evt in _process_message(message):
                                await broadcast.put(evt)
                            # When ResultMessage arrives the query is complete.
                            # Release the prompt stream so stdin closes and the
                            # CLI can exit cleanly (otherwise it waits for more
                            # input in stream-json mode).
                            if _ResultMessage is not None and isinstance(message, _ResultMessage):
                                # Emit sync_checkpoint so Java can verify it received all events.
                                # The checkpoint's own seq becomes the final "proof" that the
                                # stream completed, enabling Java to detect missed result events.
                                await broadcast.put(event_mapper.map_sync_checkpoint(
                                    task_id=task_id,
                                    latest_seq=broadcast.latest_seq,
                                    event_count=broadcast.event_count,
                                    session_id=current_session_id,
                                ))
                                stream_done_event.set()
                    except asyncio.CancelledError:
                        logger.info("Task %s producer: cancelled after %d msgs", task_id, _msg_count)
                        await broadcast.put(event_mapper.map_error(
                            task_id=task_id,
                            error="Task was cancelled",
                            session_id=current_session_id,
                        ))
                    except RuntimeError as exc:
                        # anyio cancel-scope mismatch during generator cleanup
                        if "cancel scope" in str(exc):
                            logger.debug(
                                "Task %s suppressed anyio cancel-scope error "
                                "during SDK generator cleanup", task_id,
                            )
                        else:
                            logger.error("Task %s producer RuntimeError after %d msgs: %s", task_id, _msg_count, exc)
                            error_detail = _extract_error_detail(exc, task_id)
                            await broadcast.put(event_mapper.map_error(
                                task_id=task_id,
                                error=error_detail,
                                session_id=current_session_id,
                            ))
                    except Exception as exc:
                        logger.error("Task %s producer %s after %d msgs: %s", task_id, type(exc).__name__, _msg_count, exc)
                        error_detail = _extract_error_detail(exc, task_id)
                        await broadcast.put(event_mapper.map_error(
                            task_id=task_id,
                            error=error_detail,
                            session_id=current_session_id,
                        ))
                    finally:
                        logger.info("Task %s producer: finished (%d msgs total)", task_id, _msg_count)
                        stream_done_event.set()  # Release prompt stream → stdin closes
                        await broadcast.put(None)  # Sentinel: stream is done

                producer_task = asyncio.create_task(_producer())

                try:
                    # Use shorter polling interval so we can emit "waiting" hints
                    poll_interval = 60  # seconds
                    silence_elapsed = 0
                    _warned_hard_timeout = False
                    while True:
                        now = asyncio.get_event_loop().time()
                        if now - started_at > hard_timeout and not _warned_hard_timeout:
                            # Advisory warning only — do NOT cancel CLI (Rule 2: only user can kill CLI)
                            _warned_hard_timeout = True
                            logger.warning("Task %s exceeded hard timeout (%ss) — advisory only, CLI continues",
                                           task_id, hard_timeout)
                            yield event_mapper.map_system(
                                task_id=task_id,
                                subtype="hard_timeout_warning",
                                data={"elapsed_seconds": int(now - started_at),
                                      "timeout_seconds": hard_timeout},
                                session_id=current_session_id,
                            )

                        try:
                            evt = await asyncio.wait_for(queue.get(), timeout=poll_interval)
                        except asyncio.TimeoutError:
                            # If there is a pending permission request for this task,
                            # the SDK is intentionally paused waiting for user input —
                            # do NOT count silence against the heartbeat timeout.
                            has_pending_permission = any(
                                e.get("task_id") == task_id
                                for e in permission_pending.values()
                            )
                            if has_pending_permission:
                                logger.debug(
                                    "Task %s has pending permission, skipping heartbeat count (silence=%ss)",
                                    task_id, silence_elapsed,
                                )
                                continue

                            silence_elapsed += poll_interval
                            if silence_elapsed >= heartbeat_timeout:
                                # Advisory warning only — do NOT cancel CLI (Rule 2: only user can kill CLI)
                                logger.warning("Task %s no events for %ss (heartbeat threshold) — advisory only, CLI continues",
                                               task_id, heartbeat_timeout)
                                yield event_mapper.map_system(
                                    task_id=task_id,
                                    subtype="heartbeat_warning",
                                    data={"silence_seconds": silence_elapsed,
                                          "threshold_seconds": heartbeat_timeout},
                                    session_id=current_session_id,
                                )
                                silence_elapsed = 0  # Reset — will warn again after another full interval
                                continue
                            # Emit a "waiting" hint so the UI knows we're still alive
                            logger.info("Task %s no events for %ss, emitting waiting hint", task_id, silence_elapsed)
                            yield event_mapper.map_system(
                                task_id=task_id,
                                subtype="waiting",
                                data={"elapsed_seconds": silence_elapsed,
                                      "timeout_seconds": heartbeat_timeout},
                                session_id=current_session_id,
                            )
                            continue

                        if evt is None:
                            break  # Producer finished

                        silence_elapsed = 0  # Reset on any real event
                        last_event_at = asyncio.get_event_loop().time()
                        yield evt
                finally:
                    # Unsubscribe *this* consumer from the broadcast.
                    broadcast.unsubscribe(queue)

                    if producer_task.done() or broadcast.closed:
                        # Producer already finished — normal exit, no grace period needed.
                        if not producer_task.done():
                            producer_task.cancel()
                        try:
                            await producer_task
                        except (asyncio.CancelledError, RuntimeError, Exception):
                            pass
                    else:
                        # SSE consumer disconnected but producer (CLI) still alive.
                        # Spawn an independent asyncio.Task for the grace period
                        # because this finally block is being unwound by CancelledError
                        # — any `await` here would immediately re-raise CancelledError,
                        # defeating the grace period entirely.
                        logger.warning(
                            "Task %s SSE consumer disconnected, CLI still alive. "
                            "Spawning grace period watcher (%ds)...",
                            task_id, DISCONNECT_GRACE_SECONDS,
                        )
                        task_registry_entry = task_registry.get(task_id)
                        if task_registry_entry:
                            task_registry_entry["connected"] = False
                            task_registry_entry["grace_period_active"] = True

                        asyncio.create_task(
                            _grace_period_watcher(
                                task_id, producer_task, DISCONNECT_GRACE_SECONDS,
                            ),
                            name=f"grace-period-{task_id[:8]}",
                        )

            else:
                # Direct iteration (no can_use_tool callback needed)
                _capture_child_pids(task_id)  # snapshot before first message
                _direct_msg_count = 0
                _direct_warned_hard_timeout = False
                _direct_warned_heartbeat = False
                _direct_last_pid_capture = asyncio.get_event_loop().time()
                _DIRECT_PID_CAPTURE_INTERVAL = 60  # re-scan for Agent Teams sub-CLIs
                async for message in _query_fn(prompt=prompt, options=options):
                    _direct_msg_count += 1
                    if _direct_msg_count == 1:
                        _capture_child_pids(task_id)  # capture after first message
                    elif (asyncio.get_event_loop().time() - _direct_last_pid_capture) >= _DIRECT_PID_CAPTURE_INTERVAL:
                        _capture_child_pids(task_id)  # periodic re-scan for late-spawned sub-CLIs
                        _direct_last_pid_capture = asyncio.get_event_loop().time()
                    now = asyncio.get_event_loop().time()

                    if now - started_at > hard_timeout and not _direct_warned_hard_timeout:
                        # Advisory warning only — do NOT kill CLI (Rule 2)
                        _direct_warned_hard_timeout = True
                        logger.warning("Task %s exceeded hard timeout (%ss) — advisory only, CLI continues",
                                       task_id, hard_timeout)
                        yield event_mapper.map_system(
                            task_id=task_id,
                            subtype="hard_timeout_warning",
                            data={"elapsed_seconds": int(now - started_at),
                                  "timeout_seconds": hard_timeout},
                            session_id=current_session_id,
                        )

                    if now - last_event_at > heartbeat_timeout and not _direct_warned_heartbeat:
                        # Advisory warning only — do NOT kill CLI (Rule 2)
                        _direct_warned_heartbeat = True
                        logger.warning("Task %s no events for %ss (heartbeat threshold) — advisory only, CLI continues",
                                       task_id, heartbeat_timeout)
                        yield event_mapper.map_system(
                            task_id=task_id,
                            subtype="heartbeat_warning",
                            data={"silence_seconds": int(now - last_event_at),
                                  "threshold_seconds": heartbeat_timeout},
                            session_id=current_session_id,
                        )

                    last_event_at = now
                    _direct_warned_heartbeat = False  # Reset on each real event

                    for evt in _process_message(message):
                        yield evt

        except asyncio.CancelledError:
            # If grace period is active, the CancelledError is from SSE disconnect
            # (not a real cancellation) — don't emit error event.
            _grace_entry = task_registry.get(task_id)
            if _grace_entry and _grace_entry.get("grace_period_active"):
                logger.info(
                    "Task %s CancelledError during grace period — "
                    "suppressing error event (grace watcher handles lifecycle)",
                    task_id,
                )
            else:
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
            # Unregister tracked PIDs for this task (Layer 1 cleanup).
            # Safe to do even during grace period — process enrichment
            # falls back to environment variable reading (Layer 2).
            from .process_detection import unregister_pids_for_task
            unregister_pids_for_task(task_id)

            entry = task_registry.get(task_id)
            if entry and entry.get("grace_period_active"):
                # Grace period watcher is running as an independent asyncio.Task.
                # It will handle registry cleanup when it finishes.
                logger.info(
                    "Task %s exiting generator — grace period watcher active, "
                    "deferring cleanup",
                    task_id,
                )
            elif entry and entry.get("has_external_subscriber"):
                # A subscriber reconnected via /subscribe — it owns the lifecycle.
                logger.info(
                    "Task %s exiting original generator — reconnected subscriber "
                    "active, skipping cleanup",
                    task_id,
                )
            else:
                task_registry.pop(task_id, None)
                # Clean up any pending permissions for this task
                for pid in list(permission_pending):
                    if permission_pending[pid].get("task_id") == task_id:
                        permission_pending.pop(pid, None)
                # Log surviving CLI processes for diagnostics.  Active abort
                # kills processes via abort_query(); any survivors here are
                # true orphans manageable via the UI process list.
                try:
                    surviving = _find_sdk_cli_pids()
                    if surviving:
                        logger.info(
                            "Task %s ended — %d CLI process(es) still alive: %s",
                            task_id, len(surviving), surviving,
                        )
                except Exception:
                    pass
