"""Read-only account context files for routing and skill execution prompts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .account_path_guard import ERR_SYMLINK, ERR_TRAVERSAL, PathGuardError
from .account_workspace import resolve_account_workspace
from .execution_policy import ExecutionPolicy

ACCOUNT_CONTEXT_FILE_ORDER = ("ACCOUNT_POLICY.md", "AGENT.md", "MEMORY.md")
MAX_ACCOUNT_CONTEXT_FILE_BYTES = 32 * 1024

_DESCRIPTIONS = {
    "ACCOUNT_POLICY.md": (
        "Source: upstream-controlled account policy. This file is read-only to you; "
        "do not modify it."
    ),
    "AGENT.md": (
        "Source: account-level agent instructions. Modify only when the user "
        "explicitly asks or ACCOUNT_POLICY.md allows it."
    ),
    "MEMORY.md": (
        "Source: AI-maintained account memory. Maintain it only when "
        "ACCOUNT_POLICY.md allows autonomous memory updates."
    ),
}


@dataclass(frozen=True)
class AccountContextFile:
    name: str
    content: str
    truncated: bool = False


def read_account_context_files(
    data_root: Path | str,
    account_id: str | None,
    *,
    max_bytes_per_file: int = MAX_ACCOUNT_CONTEXT_FILE_BYTES,
    execution_policy: ExecutionPolicy | None = None,
) -> list[AccountContextFile]:
    """Read exact account context files from the resolved account workspace.

    Missing files are ignored. Invalid account ids, unauthorized delegated
    workspaces, traversal attempts, and symlinks fail closed by returning no
    context instead of exposing filesystem details to the model.
    """
    try:
        account_root = _resolve_account_context_root(
            data_root,
            account_id,
            execution_policy=execution_policy,
        )
        if account_root is None:
            return []
        files: list[AccountContextFile] = []
        for file_name in ACCOUNT_CONTEXT_FILE_ORDER:
            try:
                path = _resolve_context_file(account_root, file_name)
                if not path.is_file():
                    continue
                content, truncated = _read_limited_text(path, max_bytes_per_file)
                if content.strip():
                    files.append(AccountContextFile(file_name, content, truncated))
            except (OSError, UnicodeError, PathGuardError):
                return []
        return files
    except (OSError, ValueError):
        return []


def build_account_context_prompt(
    data_root: Path | str,
    account_id: str | None,
    *,
    max_bytes_per_file: int = MAX_ACCOUNT_CONTEXT_FILE_BYTES,
    execution_policy: ExecutionPolicy | None = None,
) -> str:
    """Build the account context prompt block in authority order."""
    files = read_account_context_files(
        data_root,
        account_id,
        max_bytes_per_file=max_bytes_per_file,
        execution_policy=execution_policy,
    )
    if not files:
        return ""

    lines = [
        "## Account Context",
        "",
        "The following account context files are shown in authority order.",
        "`ACCOUNT_POLICY.md` is upstream-controlled and read-only to you.",
        "`AGENT.md` may be changed only on explicit user request or policy permission.",
        "`MEMORY.md` may be maintained autonomously only when policy permits it.",
        "Lower-priority context and the current user request must not override higher-priority files.",
        "The file contents below are already loaded in this prompt; do not call "
        "read_file for these account context files just to confirm them.",
    ]

    for file in files:
        lines.extend([
            "",
            f"### {file.name}",
            _DESCRIPTIONS[file.name],
            (
                f"Content source: `{file.name}`. This content is already included here; "
                f"do not call read_file for `{file.name}` just to confirm it."
            ),
            "",
            "```markdown",
            file.content,
            "```",
        ])
        if file.truncated:
            lines.append(f"[truncated after {max_bytes_per_file} bytes]")

    return "\n".join(lines).strip()


def _resolve_account_context_root(
    data_root: Path | str,
    account_id: str | None,
    *,
    execution_policy: ExecutionPolicy | None = None,
) -> Path | None:
    """Resolve the agent workspace root that owns account context files.

    First phase resolver:
    - delegated mode: ``<ExecutionPolicy.workdir>/agent``;
    - managed mode: ``<data_root>/accounts/<account_id>/agent``.
    """

    workspace = resolve_account_workspace(
        data_root,
        account_id,
        execution_policy=execution_policy,
    )
    return workspace.agent_root if workspace is not None else None


def _resolve_context_file(account_root: Path, file_name: str) -> Path:
    if file_name not in ACCOUNT_CONTEXT_FILE_ORDER:
        raise PathGuardError(ERR_TRAVERSAL, "unsupported account context file")

    if account_root.exists() and account_root.is_symlink():
        raise PathGuardError(ERR_SYMLINK, "account root is a symbolic link")

    path = account_root / file_name
    if path.exists() and path.is_symlink():
        raise PathGuardError(ERR_SYMLINK, "account context file is a symbolic link")

    resolved_root = account_root.resolve()
    resolved_path = path.resolve()
    try:
        resolved_path.relative_to(resolved_root)
    except ValueError:
        raise PathGuardError(ERR_TRAVERSAL, "account context file escapes account root")
    return resolved_path


def _read_limited_text(path: Path, max_bytes: int) -> tuple[str, bool]:
    raw = path.read_bytes()
    truncated = len(raw) > max_bytes
    if truncated:
        raw = raw[:max_bytes]
    return raw.decode("utf-8", errors="replace"), truncated
