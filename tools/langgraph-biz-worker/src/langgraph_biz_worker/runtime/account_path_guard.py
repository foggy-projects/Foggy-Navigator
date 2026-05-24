"""Account-directory path guard — centralised permission enforcement.

All account file tools and artifact storage share this guard.
Worker only runs on Linux; all path validation assumes POSIX semantics.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Sequence

from .account_workspace import (
    AccountWorkspace,
    _validate_account_id as _validate_workspace_account_id,
    resolve_account_workspace,
)
from .execution_policy import ExecutionPolicy

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Error codes (stable strings used in tool result dicts)
# ---------------------------------------------------------------------------

ERR_INVALID_PATH = "invalid_relative_path"
ERR_TRAVERSAL = "path_traversal_rejected"
ERR_FORBIDDEN = "forbidden_target"
ERR_FILE_TYPE = "unsupported_file_type"
ERR_SYMLINK = "symlink_rejected"
ERR_READ_ONLY = "workspace_read_only"

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# File extensions allowed inside agent/skills/<name>/references/** and assets/**
_ALLOWED_EXTENSIONS: frozenset[str] = frozenset({
    ".md", ".txt", ".json", ".yaml", ".yml", ".fsscript",
})

# Forbidden skill-name values (reserved directory names)
_FORBIDDEN_SKILL_NAMES: frozenset[str] = frozenset({
    "public", "builtin", ".", "..",
})

# Regex for a safe single path segment (skill-name or filename component)
_SAFE_SEGMENT_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")


class PathGuardError(Exception):
    """Raised when a path fails validation."""

    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


class AccountPathGuard:
    """Centralised path resolver and permission guard for account directories.

    All methods accept a *logical* relative path (as the model would send it)
    and return a resolved absolute ``Path`` for internal use.  The resolved
    path is never exposed to the model.
    """

    def __init__(
        self,
        data_root: Path,
        account_id: str | None,
        execution_policy: ExecutionPolicy | None = None,
        workspace: AccountWorkspace | None = None,
    ) -> None:
        self._data_root = Path(data_root).resolve()
        self._execution_policy = execution_policy
        resolved_workspace = workspace or resolve_account_workspace(
            self._data_root,
            account_id,
            execution_policy=execution_policy,
        )
        if resolved_workspace is None:
            raise ValueError("account_id or delegated workspace is required")
        self._account_id = resolved_workspace.storage_account_id
        self._account_root = resolved_workspace.root

    @property
    def account_root(self) -> Path:
        return self._account_root

    @property
    def account_id(self) -> str:
        return self._account_id

    # -- Public API ----------------------------------------------------------

    def resolve_read(self, relative_path: str) -> Path:
        """Resolve a relative path for read operations (read_file, str_replace source)."""
        if self._execution_policy and self._execution_policy.configured:
            try:
                resolved = self._execution_policy.resolve_path(relative_path)
                return resolved
            except ValueError as exc:
                raise PathGuardError(ERR_FORBIDDEN, str(exc))

        segments = self._validate_and_split(relative_path)
        self._check_allowed_file_path(segments)
        resolved = self._join_and_resolve(segments)
        self._check_symlinks(resolved)
        return resolved

    def resolve_write(self, relative_path: str) -> Path:
        """Resolve a relative path for write operations (write_file, str_replace, edit_file, patch_file)."""
        if self._execution_policy and self._execution_policy.configured:
            if self._execution_policy.read_only:
                raise PathGuardError(ERR_READ_ONLY, "workspace is read-only")
            try:
                resolved = self._execution_policy.resolve_path(relative_path)
                return resolved
            except ValueError as exc:
                raise PathGuardError(ERR_FORBIDDEN, str(exc))

        segments = self._validate_and_split(relative_path)
        self._check_allowed_file_path(segments)
        resolved = self._join_and_resolve(segments)
        # For writes, also verify that existing parent dirs are not symlinks
        self._check_parent_symlinks(resolved)
        # Check the target itself if it already exists
        if resolved.exists():
            self._check_symlinks(resolved)
        return resolved

    def resolve_list(self, relative_path: str) -> Path:
        """Resolve a relative path for list_files — allows directory targets.

        Allowed targets:
        - ``agent/skills/``
        - ``agent/skills/<skill-name>/``
        - ``agent/skills/<skill-name>/references/``
        - ``agent/skills/<skill-name>/assets/``
        """
        if self._execution_policy and self._execution_policy.configured:
            try:
                resolved = self._execution_policy.resolve_path(relative_path)
                return resolved
            except ValueError as exc:
                raise PathGuardError(ERR_FORBIDDEN, str(exc))

        segments = self._validate_and_split(relative_path)
        self._check_allowed_list_path(segments)
        resolved = self._join_and_resolve(segments)
        self._check_symlinks(resolved)
        return resolved

    # -- Segment validation --------------------------------------------------

    def _validate_and_split(self, relative_path: str) -> list[str]:
        """Split and validate a relative path into segments."""
        if not relative_path or not relative_path.strip():
            raise PathGuardError(ERR_INVALID_PATH, "path is empty")

        # Reject absolute paths
        if relative_path.startswith("/") or relative_path.startswith("\\"):
            raise PathGuardError(ERR_TRAVERSAL, "absolute paths are rejected")

        # On Windows-like inputs: reject drive letters
        if len(relative_path) >= 2 and relative_path[1] == ":":
            raise PathGuardError(ERR_TRAVERSAL, "absolute paths are rejected")

        # Normalise to forward-slash and split
        normalised = relative_path.replace("\\", "/")
        segments = normalised.split("/")

        for seg in segments:
            if not seg:
                raise PathGuardError(ERR_INVALID_PATH, "empty path segment")
            if seg == ".":
                raise PathGuardError(ERR_TRAVERSAL, "dot segment '.' is rejected")
            if seg == "..":
                raise PathGuardError(ERR_TRAVERSAL, "dot-dot segment '..' is rejected")

        return segments

    # -- Allowlist checks ----------------------------------------------------

    def _check_allowed_file_path(self, segments: list[str]) -> None:
        """Check that segments match the allowed file paths for skills."""
        # Expected patterns:
        #   agent/skills/<name>/SKILL.md
        #   agent/skills/<name>/references/<...path...>
        #   agent/skills/<name>/assets/<...path...>
        if len(segments) < 4 or segments[0] != "agent" or segments[1] != "skills":
            raise PathGuardError(
                ERR_FORBIDDEN,
                f"path must start with 'agent/skills/<skill-name>/': got '{'/'.join(segments)}'",
            )

        skill_name = segments[2]
        self._validate_skill_name(skill_name)

        sub = segments[3]

        if sub == "SKILL.md" and len(segments) == 4:
            # agent/skills/<name>/SKILL.md — always allowed
            return

        if sub in ("references", "assets"):
            if len(segments) < 5:
                raise PathGuardError(
                    ERR_FORBIDDEN,
                    f"'{sub}/' requires a file target beneath it",
                )
            # Validate all remaining sub-segments are safe
            for s in segments[4:]:
                if not _SAFE_SEGMENT_RE.match(s):
                    raise PathGuardError(ERR_INVALID_PATH, f"unsafe path segment: '{s}'")
            # Check file extension on the last segment
            filename = segments[-1]
            ext = _get_extension(filename)
            if ext not in _ALLOWED_EXTENSIONS:
                raise PathGuardError(
                    ERR_FILE_TYPE,
                    f"file type '{ext}' not allowed; allowed: {sorted(_ALLOWED_EXTENSIONS)}",
                )
            return

        raise PathGuardError(
            ERR_FORBIDDEN,
            f"only 'SKILL.md', 'references/**', or 'assets/**' allowed under skill dir; "
            f"got '{sub}'",
        )

    def _check_allowed_list_path(self, segments: list[str]) -> None:
        """Check that segments are valid list-directory targets."""
        if len(segments) < 2 or segments[0] != "agent" or segments[1] != "skills":
            raise PathGuardError(ERR_FORBIDDEN, "list_files only allowed under 'agent/skills/'")

        if len(segments) == 2:
            # agent/skills/
            return

        skill_name = segments[2]
        self._validate_skill_name(skill_name)

        if len(segments) == 3:
            # agent/skills/<name>/
            return

        if len(segments) == 4 and segments[3] in ("references", "assets"):
            # agent/skills/<name>/references/ or assets/
            return

        raise PathGuardError(
            ERR_FORBIDDEN,
            "list_files only allowed at: agent/skills/, agent/skills/<name>/, "
            "agent/skills/<name>/references/, agent/skills/<name>/assets/",
        )

    def _validate_skill_name(self, name: str) -> None:
        """Ensure skill name is a single safe segment and not reserved."""
        if name in _FORBIDDEN_SKILL_NAMES:
            raise PathGuardError(
                ERR_FORBIDDEN, f"skill name '{name}' is reserved/forbidden"
            )
        if not _SAFE_SEGMENT_RE.match(name):
            raise PathGuardError(
                ERR_INVALID_PATH,
                f"skill name must be a safe identifier: '{name}'",
            )

    # -- Filesystem resolution -----------------------------------------------

    def _join_and_resolve(self, segments: list[str]) -> Path:
        """Join segments onto account_root and ensure resolved path stays inside."""
        candidate = self._account_root
        for seg in segments:
            candidate = candidate / seg

        self._check_original_path_symlinks(segments)
        resolved = candidate.resolve()

        # Final boundary check: resolved must be strictly under account_root
        try:
            resolved.relative_to(self._account_root.resolve())
        except ValueError:
            raise PathGuardError(
                ERR_TRAVERSAL,
                "resolved path escapes account directory boundary",
            )

        return resolved

    def _check_original_path_symlinks(self, segments: Sequence[str]) -> None:
        """Reject symlinks in the caller-provided path before following them."""
        current = self._account_root
        for part in segments:
            current = current / part
            if current.exists() or current.is_symlink():
                if current.is_symlink():
                    raise PathGuardError(
                        ERR_SYMLINK,
                        f"symlink detected in path chain: '{part}'",
                    )

    def _check_symlinks(self, resolved: Path) -> None:
        """Reject if the resolved path or any ancestor within account_root is a symlink."""
        self._check_parent_symlinks(resolved)
        if resolved.exists() and resolved.is_symlink():
            raise PathGuardError(ERR_SYMLINK, "target is a symbolic link")

    def _check_parent_symlinks(self, resolved: Path) -> None:
        """Walk from account_root down to target, rejecting any symlink component."""
        account_resolved = self._account_root.resolve()
        try:
            rel = resolved.relative_to(account_resolved)
        except ValueError:
            raise PathGuardError(ERR_TRAVERSAL, "path escapes account directory")

        current = account_resolved
        for part in rel.parts:
            current = current / part
            if current.is_symlink():
                raise PathGuardError(
                    ERR_SYMLINK,
                    f"symlink detected in path chain: '{part}'",
                )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _validate_account_id(account_id: str) -> None:
    """Reject path traversal in account IDs."""
    _validate_workspace_account_id(account_id)


def _get_extension(filename: str) -> str:
    """Return lowercase extension including the dot."""
    idx = filename.rfind(".")
    if idx < 0:
        return ""
    return filename[idx:].lower()
