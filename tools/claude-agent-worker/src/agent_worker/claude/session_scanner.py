"""Scan Claude Code local JSONL storage to discover historical sessions.

Claude Code stores conversation history as JSONL files under
``~/.claude/projects/{encoded-path}/{session-id}.jsonl``.

Path encoding rules (filesystem path -> directory name):
- Each ``:``  ``\\``  ``/`` is replaced by a single ``-``

Example: ``D:\\foggy-projects\\Foggy-Navigator`` -> ``D--foggy-projects-Foggy-Navigator``
(the ``--`` comes from ``:`` and ``\\`` each producing one ``-``)
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from pathlib import Path

logger = logging.getLogger(__name__)

# Regex for a UUID-shaped session id (the JSONL filename without extension).
_SESSION_FILE_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jsonl$"
)


def encode_path_to_project_dir(path: str) -> str:
    """Encode a filesystem path into the Claude Code project directory name.

    Each ``:``  ``\\``  ``/`` is replaced by a single ``-``.

    >>> encode_path_to_project_dir("D:\\\\foo\\\\bar")
    'D--foo-bar'
    >>> encode_path_to_project_dir("/home/user/project")
    '-home-user-project'
    """
    encoded = path.replace(":", "-").replace("\\", "-").replace("/", "-")
    return encoded


def _claude_projects_dir() -> Path:
    """Return the default Claude Code projects directory."""
    home = Path.home()
    return home / ".claude" / "projects"


def _parse_jsonl_head(filepath: Path, max_lines: int = 20) -> dict | None:
    """Read the first *max_lines* of a JSONL file and extract session metadata.

    Returns a dict with keys: ``session_id``, ``cwd``, ``slug``,
    ``git_branch``, ``timestamp`` — or *None* if parsing fails.
    """
    session_id: str | None = None
    cwd: str | None = None
    slug: str | None = None
    git_branch: str | None = None
    timestamp: str | None = None

    try:
        with open(filepath, "r", encoding="utf-8") as fh:
            for i, line in enumerate(fh):
                if i >= max_lines:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue

                # Skip non-message entries (e.g. file-history-snapshot)
                if obj.get("type") in ("file-history-snapshot",):
                    continue

                if session_id is None:
                    session_id = obj.get("sessionId")
                if cwd is None:
                    cwd = obj.get("cwd")
                if slug is None:
                    slug = obj.get("slug")
                if git_branch is None:
                    git_branch = obj.get("gitBranch")
                if timestamp is None:
                    timestamp = obj.get("timestamp")

                # Once we have all fields, stop early.
                if all((session_id, cwd, slug, git_branch, timestamp)):
                    break
    except OSError as exc:
        logger.warning("Failed to read %s: %s", filepath, exc)
        return None

    if not session_id or not cwd:
        return None

    return {
        "session_id": session_id,
        "cwd": cwd,
        "slug": slug,
        "git_branch": git_branch,
        "timestamp": timestamp,
    }


def scan_sessions_for_cwd(cwd: str) -> list[dict]:
    """Scan the Claude Code project directory for *cwd* and return session metadata.

    Each returned dict is compatible with the ``session_store`` value format:
    ``{"cwd", "created_at", "updated_at", "slug", "git_branch"}``.
    """
    projects_dir = _claude_projects_dir()
    encoded = encode_path_to_project_dir(cwd)
    project_dir = projects_dir / encoded

    if not project_dir.is_dir():
        logger.debug("Project directory not found: %s", project_dir)
        return []

    results: list[dict] = []

    for entry in project_dir.iterdir():
        if not entry.is_file():
            continue
        if not _SESSION_FILE_RE.match(entry.name):
            continue

        meta = _parse_jsonl_head(entry)
        if meta is None:
            continue

        # Use file mtime as updated_at (avoids reading end of large files).
        try:
            mtime = entry.stat().st_mtime
            updated_at = datetime.fromtimestamp(mtime, tz=timezone.utc)
        except OSError:
            updated_at = datetime.now(timezone.utc)

        # Parse timestamp from JSONL for created_at.
        created_at: datetime
        if meta["timestamp"]:
            try:
                created_at = datetime.fromisoformat(
                    meta["timestamp"].replace("Z", "+00:00")
                )
            except (ValueError, TypeError):
                created_at = updated_at
        else:
            created_at = updated_at

        results.append({
            "session_id": meta["session_id"],
            "cwd": meta["cwd"],
            "created_at": created_at,
            "updated_at": updated_at,
            "slug": meta["slug"],
            "git_branch": meta["git_branch"],
        })

    return results


def _find_session_file(session_id: str) -> Path | None:
    """Locate the JSONL file for *session_id* under ``~/.claude/projects/``."""
    projects_dir = _claude_projects_dir()
    if not projects_dir.is_dir():
        return None
    filename = f"{session_id}.jsonl"
    for project_dir in projects_dir.iterdir():
        if not project_dir.is_dir():
            continue
        candidate = project_dir / filename
        if candidate.is_file():
            return candidate
    return None


def read_session_messages(session_id: str) -> list[dict]:
    """Read a session JSONL and return simplified conversation messages.

    Returns a list of ``{"role": "user"|"assistant", "content": str, "timestamp": str}``.
    Only user prompts (string content) and assistant text blocks are included;
    tool_use, tool_result, and thinking blocks are skipped.
    Sidechain (branched) lines are excluded.
    """
    filepath = _find_session_file(session_id)
    if filepath is None:
        return []

    messages: list[dict] = []
    try:
        with open(filepath, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue

                # Skip non-message lines and sidechains
                msg_type = obj.get("type")
                if msg_type not in ("user", "assistant"):
                    continue
                if obj.get("isSidechain"):
                    continue

                msg = obj.get("message")
                if not isinstance(msg, dict):
                    continue

                timestamp = obj.get("timestamp", "")
                content = msg.get("content")

                if msg_type == "user":
                    # User prompt: content is a plain string
                    if isinstance(content, str) and content.strip():
                        messages.append({
                            "role": "user",
                            "content": content,
                            "timestamp": timestamp,
                        })

                elif msg_type == "assistant":
                    # Assistant response: content is a list of blocks
                    if isinstance(content, list):
                        text_parts = []
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "text":
                                text = block.get("text", "")
                                if text.strip():
                                    text_parts.append(text)
                        if text_parts:
                            messages.append({
                                "role": "assistant",
                                "content": "\n".join(text_parts),
                                "timestamp": timestamp,
                            })
    except OSError as exc:
        logger.warning("Failed to read session %s: %s", session_id, exc)
        return []

    return messages


def scan_all_sessions(allowed_cwds: list[str]) -> dict[str, dict]:
    """Scan all *allowed_cwds* and return a ``session_store``-compatible dict.

    Returns ``{session_id: {cwd, created_at, updated_at, slug, git_branch}}``.
    If *allowed_cwds* is empty, returns an empty dict (no auto-discovery).
    """
    if not allowed_cwds:
        return {}

    store: dict[str, dict] = {}

    for cwd in allowed_cwds:
        sessions = scan_sessions_for_cwd(cwd)
        for s in sessions:
            sid = s.pop("session_id")
            store[sid] = s

    return store
