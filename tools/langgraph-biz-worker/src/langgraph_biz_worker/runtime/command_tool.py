"""Subprocess-backed command tool for delegated BizWorker workspaces."""

from __future__ import annotations

import platform
import subprocess
import time
from pathlib import Path
from typing import Any

from ..config import settings
from .execution_policy import ExecutionPolicy

DEFAULT_TIMEOUT_SECONDS = 120.0
MAX_TIMEOUT_SECONDS = 600.0
DEFAULT_MAX_OUTPUT_CHARS = 30_000
MAX_OUTPUT_CHARS = 200_000


def command_tool_available(execution_policy: ExecutionPolicy | None) -> bool:
    """Return whether the real command tool should be exposed to the model."""

    return (
        bool(settings.enable_command)
        and platform.system() == "Linux"
        and execution_policy is not None
        and not execution_policy.read_only
        and execution_policy.workdir is not None
    )


def run_command_tool(
    args: dict[str, Any],
    execution_policy: ExecutionPolicy | None,
) -> dict[str, Any]:
    """Execute a non-interactive command in an authorized workdir."""

    if not settings.enable_command:
        return _error("COMMAND_DISABLED", "command tool is disabled by worker configuration")
    if platform.system() != "Linux":
        return _error("COMMAND_UNSUPPORTED_OS", "command tool is only available on Linux workers")
    if execution_policy is not None and execution_policy.read_only:
        return _error("COMMAND_READ_ONLY", "command tool is disabled for read-only workspaces")
    if execution_policy is None or execution_policy.workdir is None:
        return _error("COMMAND_WORKDIR_REQUIRED", "execution_policy.workdir is required")

    command = args.get("command")
    if not isinstance(command, str) or not command.strip():
        return _error("INVALID_COMMAND", "command must be a non-empty string")
    command = command.strip()

    try:
        cwd = _resolve_cwd(args.get("workdir"), execution_policy)
    except ValueError as exc:
        return _error("COMMAND_WORKDIR_NOT_AUTHORIZED", str(exc))
    if not cwd.exists() or not cwd.is_dir():
        return _error("COMMAND_WORKDIR_NOT_FOUND", f"workdir does not exist or is not a directory: {cwd}")

    timeout_seconds = _bounded_float(
        args.get("timeout_seconds", DEFAULT_TIMEOUT_SECONDS),
        default=DEFAULT_TIMEOUT_SECONDS,
        minimum=1.0,
        maximum=MAX_TIMEOUT_SECONDS,
    )
    max_output_chars = _bounded_int(
        args.get("max_output_chars", DEFAULT_MAX_OUTPUT_CHARS),
        default=DEFAULT_MAX_OUTPUT_CHARS,
        minimum=1_000,
        maximum=MAX_OUTPUT_CHARS,
    )

    started = time.monotonic()
    try:
        completed = subprocess.run(
            ["/bin/bash", "-lc", command],
            cwd=str(cwd),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_seconds,
            check=False,
        )
        duration_ms = int((time.monotonic() - started) * 1000)
        stdout, stdout_truncated = _truncate_text(completed.stdout or "", max_output_chars)
        stderr, stderr_truncated = _truncate_text(completed.stderr or "", max_output_chars)
        return {
            "ok": completed.returncode == 0,
            "exit_code": completed.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "timed_out": False,
            "truncated": stdout_truncated or stderr_truncated,
            "duration_ms": duration_ms,
            "workdir": str(cwd),
            "command": command,
        }
    except subprocess.TimeoutExpired as exc:
        duration_ms = int((time.monotonic() - started) * 1000)
        stdout, stdout_truncated = _truncate_text(_text_or_empty(exc.stdout), max_output_chars)
        stderr_text = _text_or_empty(exc.stderr)
        timeout_message = f"Command timed out after {timeout_seconds:g} seconds."
        stderr, stderr_truncated = _truncate_text(
            f"{stderr_text}\n{timeout_message}" if stderr_text else timeout_message,
            max_output_chars,
        )
        return {
            "ok": False,
            "exit_code": 124,
            "stdout": stdout,
            "stderr": stderr,
            "timed_out": True,
            "truncated": stdout_truncated or stderr_truncated,
            "duration_ms": duration_ms,
            "workdir": str(cwd),
            "command": command,
        }
    except OSError as exc:
        duration_ms = int((time.monotonic() - started) * 1000)
        return {
            "ok": False,
            "exit_code": 127,
            "stdout": "",
            "stderr": str(exc),
            "timed_out": False,
            "truncated": False,
            "duration_ms": duration_ms,
            "workdir": str(cwd),
            "command": command,
        }


def _resolve_cwd(value: Any, execution_policy: ExecutionPolicy) -> Path:
    raw = value if isinstance(value, str) and value.strip() else "."
    resolved = execution_policy.resolve_path(raw)
    if execution_policy.allowed_dirs and not any(
        _is_within(resolved, allowed)
        for allowed in execution_policy.allowed_dirs
    ):
        raise ValueError("workdir must be inside allowed_dirs")
    return resolved


def _bounded_float(value: Any, *, default: float, minimum: float, maximum: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        parsed = default
    return min(max(parsed, minimum), maximum)


def _bounded_int(value: Any, *, default: int, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return min(max(parsed, minimum), maximum)


def _truncate_text(value: str, max_chars: int) -> tuple[str, bool]:
    if len(value) <= max_chars:
        return value, False
    return value[:max_chars], True


def _text_or_empty(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _error(code: str, message: str) -> dict[str, Any]:
    return {
        "ok": False,
        "exit_code": 126,
        "error_code": code,
        "stderr": message,
        "stdout": "",
        "timed_out": False,
        "truncated": False,
    }
