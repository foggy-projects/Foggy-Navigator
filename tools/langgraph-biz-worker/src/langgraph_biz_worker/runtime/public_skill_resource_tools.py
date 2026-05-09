"""Read-only access to the current ClientApp public skill bundle resources."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .account_file_tools import DEFAULT_MAX_BYTES, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_LINES, FileToolError
from .skill_registry import _validate_path_segment

_ALLOWED_EXTENSIONS = {".md", ".txt", ".json", ".yaml", ".yml", ".fsscript"}


class PublicSkillResourceTools:
    """Controlled read/list access under skills/public/apps/<client-app-id>/."""

    def __init__(self, skills_root: Path, client_app_id: str) -> None:
        self._skills_root = Path(skills_root).resolve()
        self._client_app_id = _validate_path_segment(client_app_id, "client_app_id")
        self._app_root = (self._skills_root / "public" / "apps" / self._client_app_id).resolve()

    def list_resources(
        self,
        skill_id: str | None = None,
        relative_path: str = "",
        recursive: bool = False,
        max_entries: int = DEFAULT_MAX_ENTRIES,
    ) -> dict[str, Any]:
        base, logical_base = self._resolve_list(skill_id, relative_path)
        if not base.is_dir():
            return {"ok": True, "client_app_id": self._client_app_id, "entries": []}

        iterator = sorted(base.rglob("*")) if recursive else sorted(base.iterdir())
        entries: list[dict[str, Any]] = []
        for item in iterator:
            if len(entries) >= max_entries:
                break
            if item.is_symlink():
                continue
            try:
                rel = str(item.relative_to(self._app_root)).replace("\\", "/")
            except ValueError:
                continue
            entry: dict[str, Any] = {"path": f"skills/{rel}"}
            if item.is_dir():
                entry["type"] = "directory"
            else:
                entry["type"] = "file"
                try:
                    entry["size"] = item.stat().st_size
                except OSError:
                    entry["size"] = 0
            entries.append(entry)
        return {
            "ok": True,
            "client_app_id": self._client_app_id,
            "relative_path": logical_base,
            "entries": entries,
        }

    def read_resource(
        self,
        skill_id: str,
        relative_path: str,
        start_line: int = 1,
        max_lines: int = DEFAULT_MAX_LINES,
    ) -> dict[str, Any]:
        resolved, logical_path = self._resolve_read(skill_id, relative_path)
        if not resolved.is_file():
            raise FileToolError("file_not_found", f"'{logical_path}' does not exist")
        try:
            raw = resolved.read_bytes()
        except OSError as exc:
            raise FileToolError("storage_read_failed", str(exc)) from exc

        truncated = False
        if len(raw) > DEFAULT_MAX_BYTES:
            raw = raw[:DEFAULT_MAX_BYTES]
            truncated = True

        text = raw.decode("utf-8", errors="replace")
        lines = text.splitlines(keepends=True)
        start_idx = max(0, start_line - 1)
        end_idx = start_idx + max_lines
        if end_idx < len(lines):
            truncated = True
        selected = lines[start_idx:end_idx]

        return {
            "ok": True,
            "client_app_id": self._client_app_id,
            "relative_path": logical_path,
            "content": "".join(selected),
            "start_line": start_line,
            "end_line": start_idx + len(selected),
            "truncated": truncated,
        }

    def _resolve_list(self, skill_id: str | None, relative_path: str) -> tuple[Path, str]:
        if not skill_id:
            if relative_path:
                raise FileToolError("invalid_relative_path", "relative_path requires skill_id")
            return self._checked(self._app_root), "skills/"
        skill_id = _validate_path_segment(skill_id, "skill_id")
        rel = _normalize_relative_path(relative_path, allow_empty=True)
        if rel and not (rel == "references" or rel == "assets" or rel.startswith("references/") or rel.startswith("assets/")):
            raise FileToolError("forbidden_target", "list path must be references/ or assets/ under the skill")
        return self._checked(self._app_root / skill_id / rel), f"skills/{skill_id}/{rel}".rstrip("/")

    def _resolve_read(self, skill_id: str, relative_path: str) -> tuple[Path, str]:
        skill_id = _validate_path_segment(skill_id, "skill_id")
        rel = _normalize_relative_path(relative_path, allow_empty=False)
        if rel != "SKILL.md" and not (rel.startswith("references/") or rel.startswith("assets/")):
            raise FileToolError("forbidden_target", "read path must be SKILL.md, references/**, or assets/**")
        if rel != "SKILL.md" and Path(rel).suffix not in _ALLOWED_EXTENSIONS:
            raise FileToolError("unsupported_file_type", f"unsupported resource file type: {rel}")
        resolved = self._checked(self._app_root / skill_id / rel)
        return resolved, f"skills/{skill_id}/{rel}"

    def _checked(self, path: Path) -> Path:
        resolved = path.resolve()
        if self._app_root not in [resolved, *resolved.parents]:
            raise FileToolError("path_traversal_rejected", "path escapes ClientApp public skill directory")
        if _contains_symlink_between(self._app_root, resolved):
            raise FileToolError("symlink_rejected", "symlinks are not allowed in public skill resources")
        return resolved


def _normalize_relative_path(value: str, *, allow_empty: bool) -> str:
    if value is None:
        value = ""
    if value.startswith("/") or value.startswith("\\") or "\\" in value:
        raise FileToolError("path_traversal_rejected", "absolute paths and backslashes are rejected")
    if len(value) >= 2 and value[1] == ":":
        raise FileToolError("path_traversal_rejected", "absolute paths are rejected")
    normalized = value.strip("/")
    if not normalized:
        if allow_empty:
            return ""
        raise FileToolError("invalid_relative_path", "relative_path is required")
    parts = normalized.split("/")
    for part in parts:
        if not part or part in {".", ".."}:
            raise FileToolError("path_traversal_rejected", "dot path segments are rejected")
        _validate_path_segment(part, "resource_path_segment")
    return normalized


def _contains_symlink_between(root: Path, target: Path) -> bool:
    try:
        relative = target.relative_to(root)
    except ValueError:
        return True
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            return True
    return False
