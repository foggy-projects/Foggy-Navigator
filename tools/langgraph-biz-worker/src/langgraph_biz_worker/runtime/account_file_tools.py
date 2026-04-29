"""Account-directory file tools — controlled file operations for LLM Skill agents.

All six tools share :class:`AccountPathGuard` for centralised permission checks.
None of them expose real filesystem paths to the model.
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .account_path_guard import AccountPathGuard, PathGuardError

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

MAX_WRITE_SIZE = 1 * 1024 * 1024  # 1 MB
DEFAULT_MAX_LINES = 200
DEFAULT_MAX_BYTES = 64 * 1024  # 64 KB
DEFAULT_MAX_ENTRIES = 100


# ---------------------------------------------------------------------------
# Error helpers
# ---------------------------------------------------------------------------


class FileToolError(Exception):
    """Raised for file-tool operation failures."""

    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


# ---------------------------------------------------------------------------
# AccountFileTools
# ---------------------------------------------------------------------------


class AccountFileTools:
    """Controlled file operations scoped to a single account directory.

    All methods accept *logical* relative paths (as the model sees them).
    """

    def __init__(self, data_root: Path, account_id: str, task_id: str = "") -> None:
        self._guard = AccountPathGuard(data_root, account_id)
        self._account_id = account_id
        self._task_id = task_id
        self._file_locks: dict[str, threading.Lock] = {}
        self._global_lock = threading.Lock()
        self.audit_records: list[dict[str, Any]] = []

    # -- list_files ----------------------------------------------------------

    def list_files(
        self,
        relative_path: str,
        recursive: bool = False,
        max_entries: int = DEFAULT_MAX_ENTRIES,
    ) -> dict[str, Any]:
        """List entries under an allowed directory."""
        try:
            resolved = self._guard.resolve_list(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        if not resolved.is_dir():
            return {"ok": True, "relative_path": relative_path, "entries": []}

        entries: list[dict[str, Any]] = []
        account_root = self._guard.account_root

        if recursive:
            iterator = sorted(resolved.rglob("*"))
        else:
            iterator = sorted(resolved.iterdir())

        for item in iterator:
            if len(entries) >= max_entries:
                break
            # Skip symlinks
            if item.is_symlink():
                continue
            try:
                rel = str(item.relative_to(account_root.resolve())).replace("\\", "/")
            except ValueError:
                continue
            entry: dict[str, Any] = {"path": rel}
            if item.is_dir():
                entry["type"] = "directory"
            else:
                entry["type"] = "file"
                try:
                    entry["size"] = item.stat().st_size
                except OSError:
                    entry["size"] = 0
            entries.append(entry)

        return {"ok": True, "relative_path": relative_path, "entries": entries}

    # -- read_file -----------------------------------------------------------

    def read_file(
        self,
        relative_path: str,
        start_line: int = 1,
        max_lines: int = DEFAULT_MAX_LINES,
    ) -> dict[str, Any]:
        """Read a file with line/byte limits."""
        try:
            resolved = self._guard.resolve_read(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        if not resolved.is_file():
            raise FileToolError("file_not_found", f"'{relative_path}' does not exist")

        try:
            raw = resolved.read_bytes()
        except OSError as exc:
            raise FileToolError("storage_read_failed", str(exc)) from exc

        truncated = False

        # Byte limit
        if len(raw) > DEFAULT_MAX_BYTES:
            raw = raw[:DEFAULT_MAX_BYTES]
            truncated = True

        text = raw.decode("utf-8", errors="replace")
        lines = text.splitlines(keepends=True)

        # Line window
        start_idx = max(0, start_line - 1)
        end_idx = start_idx + max_lines
        if end_idx < len(lines):
            truncated = True
        selected = lines[start_idx:end_idx]

        content = "".join(selected)
        end_line = start_idx + len(selected)

        return {
            "ok": True,
            "relative_path": relative_path,
            "content": content,
            "start_line": start_line,
            "end_line": end_line,
            "truncated": truncated,
        }

    # -- write_file ----------------------------------------------------------

    def write_file(
        self,
        relative_path: str,
        content: str,
        encoding: str = "utf-8",
        mode: str = "create",
        expected_sha256: str | None = None,
    ) -> dict[str, Any]:
        """Write a file with create/overwrite semantics."""
        if mode not in ("create", "overwrite"):
            raise FileToolError("invalid_mode", "mode must be 'create' or 'overwrite'")

        content_bytes = content.encode(encoding)
        if len(content_bytes) > MAX_WRITE_SIZE:
            raise FileToolError(
                "file_too_large",
                f"content size {len(content_bytes)} exceeds limit {MAX_WRITE_SIZE}",
            )

        try:
            resolved = self._guard.resolve_write(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        lock = self._get_file_lock(str(resolved))

        with lock:
            sha256_before = None

            if resolved.exists():
                if mode == "create":
                    raise FileToolError("file_exists", f"'{relative_path}' already exists; use mode='overwrite'")
                # overwrite mode
                old_bytes = resolved.read_bytes()
                sha256_before = hashlib.sha256(old_bytes).hexdigest()
                if expected_sha256 is not None and sha256_before != expected_sha256:
                    raise FileToolError(
                        "checksum_mismatch",
                        f"expected sha256 '{expected_sha256}' but current is '{sha256_before}'",
                    )
            else:
                if mode == "overwrite" and expected_sha256 is not None:
                    raise FileToolError(
                        "file_not_found",
                        f"'{relative_path}' does not exist for overwrite",
                    )

            # Ensure parent dirs
            resolved.parent.mkdir(parents=True, exist_ok=True)

            # Atomic write
            sha256_after = hashlib.sha256(content_bytes).hexdigest()
            _atomic_write(resolved, content_bytes)

        self._record_audit("write_file", relative_path, sha256_before, sha256_after)

        return {
            "ok": True,
            "relative_path": relative_path,
            "size": len(content_bytes),
            "sha256": sha256_after,
            "summary": f"account file {'overwritten' if mode == 'overwrite' else 'created'}",
        }

    # -- str_replace ---------------------------------------------------------

    def str_replace(
        self,
        relative_path: str,
        old_str: str,
        new_str: str,
    ) -> dict[str, Any]:
        """Replace a unique text fragment in a file."""
        try:
            resolved = self._guard.resolve_write(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        if not resolved.is_file():
            raise FileToolError("file_not_found", f"'{relative_path}' does not exist")

        lock = self._get_file_lock(str(resolved))

        with lock:
            text = resolved.read_text(encoding="utf-8")
            sha256_before = hashlib.sha256(text.encode("utf-8")).hexdigest()

            count = text.count(old_str)
            if count == 0:
                raise FileToolError("no_match", "old_str not found in file")
            if count > 1:
                raise FileToolError("multiple_matches", f"old_str found {count} times; must be unique")

            new_text = text.replace(old_str, new_str, 1)
            new_bytes = new_text.encode("utf-8")
            sha256_after = hashlib.sha256(new_bytes).hexdigest()

            _atomic_write(resolved, new_bytes)

        self._record_audit("str_replace", relative_path, sha256_before, sha256_after)

        return {
            "ok": True,
            "relative_path": relative_path,
            "sha256_before": sha256_before,
            "sha256_after": sha256_after,
            "summary": "text replaced",
        }

    # -- edit_file -----------------------------------------------------------

    def edit_file(
        self,
        relative_path: str,
        operation: str,
        anchor: str,
        content: str,
    ) -> dict[str, Any]:
        """Section-level edit based on a heading anchor.

        Finds the first line matching ``anchor`` (heading) and replaces content
        from that line until the next heading at the same or higher level (or EOF).
        """
        if operation != "replace_section":
            raise FileToolError("unsupported_operation", f"only 'replace_section' is supported; got '{operation}'")

        try:
            resolved = self._guard.resolve_write(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        if not resolved.is_file():
            raise FileToolError("file_not_found", f"'{relative_path}' does not exist")

        lock = self._get_file_lock(str(resolved))

        with lock:
            text = resolved.read_text(encoding="utf-8")
            sha256_before = hashlib.sha256(text.encode("utf-8")).hexdigest()

            lines = text.splitlines(keepends=True)

            # Find anchor line
            anchor_indices = [i for i, line in enumerate(lines) if line.strip() == anchor.strip()]
            if len(anchor_indices) == 0:
                raise FileToolError("anchor_not_found", f"anchor '{anchor}' not found in file")
            if len(anchor_indices) > 1:
                raise FileToolError("multiple_anchors", f"anchor '{anchor}' found {len(anchor_indices)} times")

            anchor_idx = anchor_indices[0]

            # Determine heading level
            anchor_level = _heading_level(lines[anchor_idx])
            if anchor_level == 0:
                raise FileToolError("invalid_anchor", "anchor must be a markdown heading (starts with #)")

            # Find end: next heading at same or higher level
            end_idx = len(lines)
            for i in range(anchor_idx + 1, len(lines)):
                level = _heading_level(lines[i])
                if 0 < level <= anchor_level:
                    end_idx = i
                    break

            # Build new file
            new_lines = lines[:anchor_idx]
            # Include anchor heading itself, then new content
            new_lines.append(lines[anchor_idx])
            if content and not content.endswith("\n"):
                content += "\n"
            if content:
                new_lines.append(content)
            new_lines.extend(lines[end_idx:])

            new_text = "".join(new_lines)
            new_bytes = new_text.encode("utf-8")
            sha256_after = hashlib.sha256(new_bytes).hexdigest()

            _atomic_write(resolved, new_bytes)

        self._record_audit("edit_file", relative_path, sha256_before, sha256_after)

        return {
            "ok": True,
            "relative_path": relative_path,
            "sha256_before": sha256_before,
            "sha256_after": sha256_after,
            "summary": f"section '{anchor}' replaced",
        }

    # -- patch_file ----------------------------------------------------------

    def patch_file(
        self,
        relative_path: str,
        patch: str,
        expected_sha256: str | None = None,
    ) -> dict[str, Any]:
        """Apply a unified diff patch to a single file.

        The diff header paths are ignored — only ``relative_path`` determines the target.
        Conflicts cause full rejection with no partial writes.
        """
        try:
            resolved = self._guard.resolve_write(relative_path)
        except PathGuardError as exc:
            raise FileToolError(exc.code, exc.detail) from exc

        if not resolved.is_file():
            raise FileToolError("file_not_found", f"'{relative_path}' does not exist")

        lock = self._get_file_lock(str(resolved))

        with lock:
            old_bytes = resolved.read_bytes()
            sha256_before = hashlib.sha256(old_bytes).hexdigest()

            if expected_sha256 is not None and sha256_before != expected_sha256:
                raise FileToolError(
                    "checksum_mismatch",
                    f"expected sha256 '{expected_sha256}' but current is '{sha256_before}'",
                )

            old_text = old_bytes.decode("utf-8")

            # Parse and apply patch in memory
            try:
                new_text = _apply_unified_diff(old_text, patch)
            except PatchConflictError as exc:
                raise FileToolError("patch_conflict", str(exc)) from exc

            new_bytes = new_text.encode("utf-8")
            sha256_after = hashlib.sha256(new_bytes).hexdigest()

            # Atomic write: tmp → fsync → os.replace
            _atomic_write(resolved, new_bytes)

        self._record_audit("patch_file", relative_path, sha256_before, sha256_after)

        return {
            "ok": True,
            "relative_path": relative_path,
            "changed": sha256_before != sha256_after,
            "sha256_before": sha256_before,
            "sha256_after": sha256_after,
            "summary": "patch applied",
        }

    # -- Internal helpers ----------------------------------------------------

    def _get_file_lock(self, key: str) -> threading.Lock:
        """Get or create a per-file lock for serialisation."""
        with self._global_lock:
            if key not in self._file_locks:
                self._file_locks[key] = threading.Lock()
            return self._file_locks[key]

    def _record_audit(
        self,
        operation: str,
        relative_path: str,
        sha256_before: str | None,
        sha256_after: str,
    ) -> None:
        record = {
            "operation": operation,
            "account_id": self._account_id,
            "task_id": self._task_id,
            "relative_path": relative_path,
            "sha256_before": sha256_before,
            "sha256_after": sha256_after,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "actor": "llm",
        }
        self.audit_records.append(record)
        logger.info("AUDIT: %s", record)


# ---------------------------------------------------------------------------
# Atomic write helper
# ---------------------------------------------------------------------------


def _atomic_write(target: Path, data: bytes) -> None:
    """Write data atomically via tmp + fsync + os.replace."""
    fd, tmp_path = tempfile.mkstemp(dir=str(target.parent), suffix=".tmp")
    try:
        os.write(fd, data)
        os.fsync(fd)
        os.close(fd)
        fd = -1
        os.replace(tmp_path, str(target))
    except Exception:
        if fd != -1:
            try:
                os.close(fd)
            except OSError:
                pass
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        raise


# ---------------------------------------------------------------------------
# Heading level helper
# ---------------------------------------------------------------------------


def _heading_level(line: str) -> int:
    """Return the Markdown heading level (1-6) or 0 if not a heading."""
    stripped = line.lstrip()
    if not stripped.startswith("#"):
        return 0
    level = 0
    for ch in stripped:
        if ch == "#":
            level += 1
        else:
            break
    if level > 6:
        return 0
    # Must be followed by space or end of line
    if len(stripped) > level and stripped[level] != " ":
        return 0
    return level


# ---------------------------------------------------------------------------
# Unified diff applier
# ---------------------------------------------------------------------------


class PatchConflictError(Exception):
    """Raised when a unified diff cannot be applied cleanly."""


def _apply_unified_diff(original: str, patch_text: str) -> str:
    """Apply a unified diff to the original text.

    Only supports single-file patches.  The ``---``/``+++`` header paths are
    ignored — the caller decides the target file.

    Raises :class:`PatchConflictError` on any conflict.
    """
    hunks = _parse_unified_diff(patch_text)
    if not hunks:
        raise PatchConflictError("no hunks found in patch")

    lines = original.splitlines(keepends=True)
    # Ensure last line has newline for uniform handling
    if lines and not lines[-1].endswith("\n"):
        lines[-1] += "\n"
        trailing_newline = False
    else:
        trailing_newline = True if lines else True

    # Apply hunks in reverse order (by original line number) to avoid offset shifts
    hunks_sorted = sorted(hunks, key=lambda h: h["orig_start"], reverse=True)

    for hunk in hunks_sorted:
        orig_start = hunk["orig_start"] - 1  # 0-indexed
        orig_count = hunk["orig_count"]
        new_lines_hunk = hunk["new_lines"]
        old_lines_hunk = hunk["old_lines"]

        # Verify context: old lines must match
        for i, expected in enumerate(old_lines_hunk):
            actual_idx = orig_start + i
            if actual_idx >= len(lines):
                raise PatchConflictError(
                    f"hunk line {i+1}: expected line {actual_idx+1} but file has only {len(lines)} lines"
                )
            actual = lines[actual_idx]
            # Compare without trailing newline for robustness
            if actual.rstrip("\n\r") != expected.rstrip("\n\r"):
                raise PatchConflictError(
                    f"hunk context mismatch at line {actual_idx+1}: "
                    f"expected {expected.rstrip()!r}, got {actual.rstrip()!r}"
                )

        # Replace
        lines[orig_start : orig_start + orig_count] = [
            ln if ln.endswith("\n") else ln + "\n" for ln in new_lines_hunk
        ]

    result = "".join(lines)
    if not trailing_newline and result.endswith("\n"):
        result = result[:-1]
    return result


def _parse_unified_diff(patch_text: str) -> list[dict[str, Any]]:
    """Parse a unified diff into a list of hunk descriptors.

    Each hunk: ``{orig_start, orig_count, new_start, new_count, old_lines, new_lines}``
    """
    lines = patch_text.splitlines(keepends=True)
    _validate_single_file_diff(lines)
    hunks: list[dict[str, Any]] = []
    i = 0

    # Skip header lines (--- / +++ / any preamble)
    while i < len(lines):
        if lines[i].startswith("@@"):
            break
        i += 1

    while i < len(lines):
        line = lines[i]
        if not line.startswith("@@"):
            i += 1
            continue

        # Parse @@ -orig_start[,orig_count] +new_start[,new_count] @@
        match = re.match(
            r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@",
            line,
        )
        if not match:
            raise PatchConflictError(f"invalid hunk header: {line.rstrip()!r}")

        orig_start = int(match.group(1))
        orig_count = int(match.group(2)) if match.group(2) is not None else 1
        new_start = int(match.group(3))
        new_count = int(match.group(4)) if match.group(4) is not None else 1

        i += 1
        old_lines: list[str] = []
        new_lines: list[str] = []

        while i < len(lines) and not lines[i].startswith("@@"):
            raw = lines[i]
            if raw.startswith("-"):
                old_lines.append(raw[1:])
            elif raw.startswith("+"):
                new_lines.append(raw[1:])
            elif raw.startswith(" "):
                old_lines.append(raw[1:])
                new_lines.append(raw[1:])
            elif raw.startswith("\\"):
                # "\ No newline at end of file" — skip
                pass
            else:
                # Unknown line in hunk — could be next file header
                break
            i += 1

        if len(old_lines) != orig_count:
            raise PatchConflictError(
                f"hunk orig_count mismatch: header says {orig_count}, got {len(old_lines)} lines"
            )
        if len(new_lines) != new_count:
            raise PatchConflictError(
                f"hunk new_count mismatch: header says {new_count}, got {len(new_lines)} lines"
            )

        hunks.append({
            "orig_start": orig_start,
            "orig_count": orig_count,
            "new_start": new_start,
            "new_count": new_count,
            "old_lines": old_lines,
            "new_lines": new_lines,
        })

    return hunks


def _validate_single_file_diff(lines: list[str]) -> None:
    """Reject multi-file unified diffs before hunk application."""
    old_headers = 0
    new_headers = 0

    for line in lines:
        if line.startswith("--- "):
            old_headers += 1
        elif line.startswith("+++ "):
            new_headers += 1

        if old_headers > 1 or new_headers > 1:
            raise PatchConflictError("patch_file accepts a single-file unified diff only")

    if old_headers != new_headers:
        raise PatchConflictError("unified diff header is incomplete")
