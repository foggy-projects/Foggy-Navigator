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
import os
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


def read_session_messages(
    session_id: str,
    offset: int = 0,
    limit: int | None = None,
) -> list[dict]:
    """Read a session JSONL and return simplified conversation messages.

    Returns a list of ``{"role": "user"|"assistant", "content": str, "timestamp": str}``.
    Only user prompts (string content) and assistant text blocks are included;
    tool_use, tool_result, and thinking blocks are skipped.
    Sidechain (branched) lines are excluded.

    Args:
        session_id: The Claude session ID.
        offset: Number of messages to skip from the beginning (default 0).
        limit: Maximum number of messages to return (default None = all).
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

    # Apply offset/limit slicing
    if limit is not None:
        return messages[offset : offset + limit]
    elif offset > 0:
        return messages[offset:]
    return messages


def scan_session_checkpoints(session_id: str) -> list[dict]:
    """Scan a session JSONL to extract UserMessage UUIDs as checkpoints.

    Returns a list of ``{"id": uuid, "turnIndex": N, "timestamp": "..."}``.
    Only non-sidechain **user prompt** messages are included.
    Tool-result messages (type "user" but content is tool_result) are excluded
    so that turnIndex aligns with the frontend's user-turn counting.
    """
    filepath = _find_session_file(session_id)
    if filepath is None:
        return []

    checkpoints: list[dict] = []
    turn_index = 0
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

                if obj.get("type") != "user":
                    continue
                if obj.get("isSidechain"):
                    continue

                uuid = obj.get("uuid")
                if not uuid:
                    continue

                # Skip tool_result messages — they have type "user" in the
                # Anthropic API but aren't actual user prompts.  The frontend
                # only counts real user prompts as turns, so including
                # tool_results would cause a turnIndex mismatch.
                message = obj.get("message", {})
                content = message.get("content", "")
                if isinstance(content, list) and any(
                    isinstance(c, dict) and c.get("type") == "tool_result"
                    for c in content
                ):
                    continue

                turn_index += 1
                checkpoints.append({
                    "id": uuid,
                    "turnIndex": turn_index,
                    "timestamp": obj.get("timestamp", ""),
                })
    except OSError as exc:
        logger.warning("Failed to scan checkpoints for session %s: %s", session_id, exc)
        return []

    return checkpoints


def count_session_messages(session_id: str) -> dict:
    """Count user/assistant messages in a session JSONL.

    Returns ``{"user_count": N, "assistant_count": N, "total": N}``.
    Only non-sidechain messages are counted.
    """
    filepath = _find_session_file(session_id)
    if filepath is None:
        return {"user_count": 0, "assistant_count": 0, "total": 0}

    user_count = 0
    assistant_count = 0
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

                if obj.get("isSidechain"):
                    continue

                msg_type = obj.get("type")
                if msg_type == "user":
                    user_count += 1
                elif msg_type == "assistant":
                    assistant_count += 1
    except OSError as exc:
        logger.warning("Failed to count messages for session %s: %s", session_id, exc)
        return {"user_count": 0, "assistant_count": 0, "total": 0}

    return {"user_count": user_count, "assistant_count": assistant_count, "total": user_count + assistant_count}


def rewind_session_conversation(session_id: str, turn_index: int) -> dict:
    """Truncate a session JSONL at the target user turn.

    After rewind, the JSONL contains **only** turns 1..X-1 (everything
    before the target user turn).  Lines from turn X onward are **removed**
    (not marked as sidechain — Claude Code CLI does not support the
    ``isSidechain: true`` flag and treats such files as invalid).

    A ``last-prompt`` entry is appended so the CLI can identify the
    conversation when resuming with ``--resume``.

    A backup of the original file is saved as ``{session_id}.jsonl.rewind-backup.bak``.

    Returns ``{"status": "rewound", "user_prompt": str, "turn_index": int}``
    on success, or ``{"status": "error", "message": str}`` on failure.
    """
    filepath = _find_session_file(session_id)
    if filepath is None:
        return {"status": "error", "message": f"Session file not found: {session_id}"}

    lines: list[str] = []
    try:
        with open(filepath, "r", encoding="utf-8") as fh:
            lines = fh.readlines()
    except OSError as exc:
        return {"status": "error", "message": f"Failed to read session: {exc}"}

    # ── First pass: find the target user turn ──────────────────────
    user_turn = 0
    cutoff_line = -1
    user_prompt = ""

    for i, raw_line in enumerate(lines):
        stripped = raw_line.strip()
        if not stripped:
            continue
        try:
            obj = json.loads(stripped)
        except json.JSONDecodeError:
            continue

        if obj.get("type") != "user":
            continue
        if obj.get("isSidechain"):
            continue

        # Skip tool_result messages — they have type "user" in the
        # Anthropic API but aren't actual user prompts.
        message = obj.get("message", {})
        msg_content = message.get("content", "")
        if isinstance(msg_content, list) and any(
            isinstance(c, dict) and c.get("type") == "tool_result"
            for c in msg_content
        ):
            continue

        user_turn += 1
        if user_turn == turn_index:
            content = msg_content
            if isinstance(content, str):
                user_prompt = content
            cutoff_line = i  # truncate FROM this line (inclusive)
            break

    if cutoff_line == -1:
        return {"status": "error", "message": f"Turn {turn_index} not found (only {user_turn} user turns)"}

    # ── Build the truncated file ──────────────────────────────────
    # Keep only lines before the cutoff that are NOT already sidechain.
    kept_lines: list[str] = []
    for i, raw_line in enumerate(lines):
        if i >= cutoff_line:
            break
        stripped = raw_line.strip()
        if not stripped:
            continue
        try:
            obj = json.loads(stripped)
        except json.JSONDecodeError:
            kept_lines.append(raw_line)
            continue
        # Drop any previously-sidechain lines that happen to appear
        # before the cutoff (from earlier rewinds).
        if obj.get("isSidechain") is True:
            continue
        kept_lines.append(raw_line)

    # When rewinding to the very first turn (cutoff_line == 0), no
    # conversation lines remain — but that is valid: the caller will
    # re-send the extracted user_prompt as a fresh message.  We still
    # need a minimal file so the CLI can accept the session_id.
    # Keep only initial metadata lines (queue-operation, file-history-snapshot, system)
    # that appeared before the first conversation message.
    if not kept_lines:
        for raw_line in lines[:cutoff_line + 5]:
            stripped = raw_line.strip()
            if not stripped:
                continue
            try:
                obj = json.loads(stripped)
            except json.JSONDecodeError:
                continue
            if obj.get("type") in ("queue-operation", "file-history-snapshot", "system"):
                kept_lines.append(raw_line)
            elif obj.get("type") in ("user", "assistant"):
                break  # stop at first conversation message

    # Find the last real user prompt text for last-prompt entry
    last_prompt_text = user_prompt
    if not last_prompt_text:
        for raw_line in reversed(kept_lines):
            try:
                obj = json.loads(raw_line.strip())
            except (json.JSONDecodeError, ValueError):
                continue
            if obj.get("type") != "user":
                continue
            msg = obj.get("message", {})
            content = msg.get("content", "")
            if isinstance(content, str) and content.strip():
                last_prompt_text = content[:200]
                break

    # Remove any stale last-prompt entries, then append a fresh one.
    final_lines: list[str] = []
    for raw_line in kept_lines:
        try:
            obj = json.loads(raw_line.strip())
        except (json.JSONDecodeError, ValueError):
            final_lines.append(raw_line)
            continue
        if obj.get("type") == "last-prompt":
            continue  # drop stale last-prompt
        final_lines.append(raw_line)

    final_lines.append(json.dumps({
        "type": "last-prompt",
        "lastPrompt": last_prompt_text or "",
        "sessionId": session_id,
    }, ensure_ascii=False) + "\n")

    # ── Write back atomically ─────────────────────────────────────
    # Save a backup (only if one doesn't already exist).
    backup_path = filepath.with_suffix(".jsonl.rewind-backup.bak")
    if not backup_path.exists():
        try:
            import shutil
            shutil.copy2(filepath, backup_path)
        except OSError as exc:
            logger.warning("Failed to create rewind backup for %s: %s", session_id, exc)

    tmp_path = filepath.with_suffix(".jsonl.tmp")
    try:
        with open(tmp_path, "w", encoding="utf-8") as fh:
            fh.writelines(final_lines)
        tmp_path.replace(filepath)
    except OSError as exc:
        tmp_path.unlink(missing_ok=True)
        return {"status": "error", "message": f"Failed to write session file: {exc}"}

    removed_count = len(lines) - len(final_lines)

    logger.info(
        "Rewound session %s to turn %d: cutoff_line=%d, original_lines=%d, "
        "kept_lines=%d, removed=%d, user_prompt=%s",
        session_id, turn_index, cutoff_line, len(lines),
        len(final_lines), removed_count,
        repr(user_prompt[:80]) if user_prompt else None,
    )

    return {"status": "rewound", "user_prompt": user_prompt, "turn_index": turn_index}


def _safe_json_parse(s: str) -> dict | None:
    """Parse JSON string, return None on failure."""
    try:
        return json.loads(s)
    except (json.JSONDecodeError, TypeError):
        return None


def scan_all_sessions(allowed_cwds: list[str]) -> dict[str, dict]:
    """Scan all *allowed_cwds* and return a ``session_store``-compatible dict.

    Returns ``{session_id: {cwd, created_at, updated_at, slug, git_branch}}``.
    If *allowed_cwds* is empty, returns an empty dict (no auto-discovery).

    In addition to scanning exact allowed_cwds, also discovers Claude Code
    project directories whose path is a subdirectory of any allowed_cwd.
    This handles the case where a user adds a parent directory (e.g.
    ``D:\\foggy-projects``) and sessions exist in child directories (e.g.
    ``D:\\foggy-projects\\student-analytics``).
    """
    if not allowed_cwds:
        return {}

    store: dict[str, dict] = {}

    # Phase 1: scan exact allowed_cwds
    scanned_dirs: set[str] = set()
    for cwd in allowed_cwds:
        sessions = scan_sessions_for_cwd(cwd)
        scanned_dirs.add(encode_path_to_project_dir(cwd))
        for s in sessions:
            sid = s.pop("session_id")
            store[sid] = s

    # Phase 2: discover child project directories under allowed_cwds
    # Claude Code stores sessions in ~/.claude/projects/{encoded-path}/
    # We scan all project directories and check if their decoded path
    # is a subdirectory of any allowed_cwd.
    projects_dir = _claude_projects_dir()
    if projects_dir.is_dir():
        # Normalize allowed_cwds for prefix matching
        normalized_prefixes = []
        for cwd in allowed_cwds:
            norm = os.path.normpath(cwd).lower()
            normalized_prefixes.append(norm)

        for child in projects_dir.iterdir():
            if not child.is_dir():
                continue
            if child.name in scanned_dirs:
                continue  # already scanned in Phase 1

            # Try to match this project directory against allowed_cwds
            # The encoded name uses '-' for ':', '\', '/' — we can't fully decode,
            # but we can check if the encoded name starts with an allowed prefix.
            for cwd, norm_prefix in zip(allowed_cwds, normalized_prefixes):
                encoded_prefix = encode_path_to_project_dir(cwd) + "-"
                if child.name.startswith(encoded_prefix):
                    sessions = _scan_project_dir(child)
                    for s in sessions:
                        # Validate the actual cwd from the JSONL is under allowed_cwd
                        actual_cwd = s.get("cwd", "")
                        actual_norm = os.path.normpath(actual_cwd).lower()
                        # rstrip(os.sep) avoids double-sep when prefix is a drive root
                        if actual_norm.startswith(norm_prefix.rstrip(os.sep) + os.sep) or actual_norm == norm_prefix:
                            sid = s.pop("session_id")
                            store[sid] = s
                    break

    return store


def _scan_project_dir(project_dir: Path) -> list[dict]:
    """Scan a single Claude project directory for session JSONL files."""
    results: list[dict] = []
    for entry in project_dir.iterdir():
        if not entry.is_file():
            continue
        if not _SESSION_FILE_RE.match(entry.name):
            continue
        meta = _parse_jsonl_head(entry)
        if meta is None:
            continue
        try:
            mtime = entry.stat().st_mtime
            updated_at = datetime.fromtimestamp(mtime, tz=timezone.utc)
        except OSError:
            updated_at = datetime.now(timezone.utc)

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
