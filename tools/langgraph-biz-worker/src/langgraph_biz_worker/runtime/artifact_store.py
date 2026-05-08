"""Artifact Store — file-system backed artifact persistence.

Artifacts live under ``<data_root>/accounts/<account_id>/artifacts/``.
Each artifact has a metadata JSON file and a content file.

Directory layout::

    artifacts/
      task/<task_id>/
        meta/<artifact_id>.json
        content/<artifact_id>.bin
      account/
        meta/<artifact_id>.json
        content/<artifact_id>.bin

``content_ref`` is internal and MUST NOT be exposed to the model.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import secrets
import string
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

MAX_CONTENT_SIZE = 1 * 1024 * 1024  # 1 MB
_VALID_SCOPES = frozenset({"task", "account"})
_VALID_READ_MODES = frozenset({"summary", "metadata", "content"})
_ID_ALPHABET = string.ascii_lowercase + string.digits
_ID_LENGTH = 20
_SAFE_SEGMENT_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")


# ---------------------------------------------------------------------------
# Error helpers
# ---------------------------------------------------------------------------


class ArtifactError(Exception):
    """Raised for artifact operation failures."""

    def __init__(self, code: str, detail: str) -> None:
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


# ---------------------------------------------------------------------------
# ArtifactStore
# ---------------------------------------------------------------------------


class ArtifactStore:
    """File-system backed artifact store scoped to an account."""

    def __init__(self, data_root: Path) -> None:
        self._data_root = Path(data_root).resolve()

    # -- Public API ----------------------------------------------------------

    def create(
        self,
        *,
        account_id: str,
        task_id: str | None,
        scope: str,
        name: str,
        content: str,
        mime_type: str = "text/plain",
        encoding: str = "utf-8",
        summary: str = "",
    ) -> dict[str, Any]:
        """Create a new artifact and persist to disk.

        Returns a model-safe dict (no ``content_ref``).
        """
        # --- Validate inputs ---
        if scope not in _VALID_SCOPES:
            raise ArtifactError("invalid_scope", f"scope must be one of {sorted(_VALID_SCOPES)}")

        if not account_id:
            raise ArtifactError("account_context_required", "account_id is required")
        account_id = _validate_segment(account_id, "account_id")

        if scope == "task" and not task_id:
            raise ArtifactError("missing_task_context", "task_id required for task-scope artifacts")
        if scope == "task":
            task_id = _validate_segment(task_id or "", "task_id")

        if not isinstance(content, str):
            raise ArtifactError("invalid_content_type", "only text content is supported")

        content_bytes = content.encode(encoding)
        if len(content_bytes) > MAX_CONTENT_SIZE:
            raise ArtifactError(
                "content_too_large",
                f"content size {len(content_bytes)} exceeds limit {MAX_CONTENT_SIZE}",
            )

        # --- Generate ID ---
        artifact_id = f"art_{''.join(secrets.choice(_ID_ALPHABET) for _ in range(_ID_LENGTH))}"

        # --- Compute paths ---
        base_dir = self._scope_dir(account_id, scope, task_id)
        meta_dir = base_dir / "meta"
        content_dir = base_dir / "content"
        meta_dir.mkdir(parents=True, exist_ok=True)
        content_dir.mkdir(parents=True, exist_ok=True)

        meta_path = meta_dir / f"{artifact_id}.json"
        content_path = content_dir / f"{artifact_id}.bin"

        # --- Compute hash ---
        sha256 = hashlib.sha256(content_bytes).hexdigest()

        # --- Build content_ref (internal only) ---
        content_ref = content_path.relative_to(self._data_root).as_posix()

        # --- Build metadata ---
        now = datetime.now(timezone.utc).isoformat()
        auto_summary = summary or f"Artifact '{name}' ({mime_type}, {len(content_bytes)} bytes)"

        metadata: dict[str, Any] = {
            "artifact_id": artifact_id,
            "account_id": account_id,
            "scope": scope,
            "task_id": task_id,
            "name": name,
            "mime_type": mime_type,
            "encoding": encoding,
            "size": len(content_bytes),
            "summary": auto_summary,
            "created_by": "llm",
            "created_at": now,
            "content_ref": content_ref,
            "sha256": sha256,
        }

        # --- Persist ---
        try:
            # Write content atomically
            self._atomic_write(content_path, content_bytes)

            # Write metadata atomically
            meta_bytes = json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8")
            self._atomic_write(meta_path, meta_bytes)
        except Exception as exc:
            logger.exception("Failed to write artifact %s", artifact_id)
            raise ArtifactError("storage_write_failed", str(exc)) from exc

        # --- Return model-safe result (no content_ref) ---
        return {
            "artifact_id": artifact_id,
            "name": name,
            "scope": scope,
            "mime_type": mime_type,
            "size": len(content_bytes),
            "summary": auto_summary,
        }

    def read(
        self,
        *,
        account_id: str,
        task_id: str | None,
        artifact_id: str,
        mode: str = "summary",
    ) -> dict[str, Any]:
        """Read an artifact by ID.

        ``mode`` can be ``summary`` (default), ``metadata``, or ``content``.
        Returns a model-safe dict (no ``content_ref``).
        """
        if mode not in _VALID_READ_MODES:
            raise ArtifactError("invalid_mode", f"mode must be one of {sorted(_VALID_READ_MODES)}")
        if not account_id:
            raise ArtifactError("account_context_required", "account_id is required")
        account_id = _validate_segment(account_id, "account_id")
        artifact_id = _validate_segment(artifact_id, "artifact_id")
        if task_id:
            task_id = _validate_segment(task_id, "task_id")

        metadata = self._find_metadata(account_id, artifact_id)
        if metadata is None:
            raise ArtifactError("artifact_not_found", f"artifact {artifact_id} not found")

        # --- Access control ---
        if metadata["account_id"] != account_id:
            raise ArtifactError("access_denied", "cross-account access is not allowed")

        if metadata["scope"] == "task":
            if metadata["task_id"] != task_id:
                raise ArtifactError("access_denied", "cross-task access is not allowed for task-scope artifacts")

        # --- Build response (never include content_ref) ---
        result: dict[str, Any] = {
            "artifact_id": metadata["artifact_id"],
            "name": metadata["name"],
            "scope": metadata["scope"],
            "mime_type": metadata["mime_type"],
            "size": metadata["size"],
            "summary": metadata["summary"],
        }

        if mode == "metadata":
            result["sha256"] = metadata["sha256"]
            result["created_by"] = metadata["created_by"]
            result["created_at"] = metadata["created_at"]
            result["encoding"] = metadata["encoding"]

        if mode == "content":
            content_ref = metadata["content_ref"]
            content_path = self._resolve_content_ref(account_id, content_ref)
            try:
                content_bytes = content_path.read_bytes()
                result["content"] = content_bytes.decode(metadata.get("encoding", "utf-8"))
            except Exception as exc:
                raise ArtifactError("storage_read_failed", str(exc)) from exc

        return result

    # -- Internal helpers ----------------------------------------------------

    def _scope_dir(self, account_id: str, scope: str, task_id: str | None) -> Path:
        """Return the base directory for a scope."""
        base = self._account_artifact_root(account_id)
        if scope == "task":
            return base / "task" / (task_id or "_unknown")
        return base / "account"

    def _find_metadata(self, account_id: str, artifact_id: str) -> dict[str, Any] | None:
        """Search for an artifact's metadata across scopes."""
        account_base = self._account_artifact_root(account_id)

        # Search account scope
        account_meta = account_base / "account" / "meta" / f"{artifact_id}.json"
        if account_meta.is_file():
            return self._load_meta(account_meta)

        # Search task scopes
        task_base = account_base / "task"
        if task_base.is_dir():
            for task_dir in task_base.iterdir():
                if not task_dir.is_dir():
                    continue
                meta_path = task_dir / "meta" / f"{artifact_id}.json"
                if meta_path.is_file():
                    return self._load_meta(meta_path)

        return None

    @staticmethod
    def _load_meta(path: Path) -> dict[str, Any]:
        """Load and parse a metadata JSON file."""
        return json.loads(path.read_text(encoding="utf-8"))

    def _account_artifact_root(self, account_id: str) -> Path:
        """Return and boundary-check the artifact root for an account."""
        root = (self._data_root / "accounts" / account_id / "artifacts").resolve()
        expected_parent = (self._data_root / "accounts" / account_id).resolve()
        try:
            root.relative_to(expected_parent)
        except ValueError as exc:
            raise ArtifactError("access_denied", "artifact root escapes account directory") from exc
        return root

    def _resolve_content_ref(self, account_id: str, content_ref: str) -> Path:
        """Resolve an internal content_ref and ensure it stays under this account."""
        content_path = (self._data_root / content_ref).resolve()
        account_base = self._account_artifact_root(account_id)
        try:
            content_path.relative_to(account_base)
        except ValueError as exc:
            raise ArtifactError("access_denied", "artifact content_ref escapes account boundary") from exc
        return content_path

    @staticmethod
    def _atomic_write(target: Path, data: bytes) -> None:
        """Write data to target atomically via tmp + fsync + rename."""
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


def _validate_segment(value: str, field: str) -> str:
    """Validate account/task/artifact identifiers before filesystem use."""
    if not value or value.strip() != value:
        raise ArtifactError(f"invalid_{field}", f"{field} must be non-empty and trimmed")
    if value in {".", ".."} or "/" in value or "\\" in value:
        raise ArtifactError(f"invalid_{field}", f"{field} must be a single path segment")
    if not _SAFE_SEGMENT_RE.match(value):
        raise ArtifactError(f"invalid_{field}", f"{field} contains unsupported characters")
    return value
