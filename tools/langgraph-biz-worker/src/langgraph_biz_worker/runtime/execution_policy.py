"""Upstream execution policy normalization for BizWorker runtime calls."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXECUTION_POLICY_KEYS = ("execution_policy", "executionPolicy")
WORKDIR_KEYS = ("workdir", "workDir", "working_dir", "workingDirectory", "working_directory")
ALLOWED_DIRS_KEYS = (
    "allowed_dirs",
    "allowedDirs",
    "allowed_directories",
    "allowedDirectories",
)
ALLOWED_TOOLS_KEYS = (
    "allowed_tools",
    "allowedTools",
    "authorized_tools",
    "authorizedTools",
    "tool_allowlist",
    "toolAllowlist",
)
READ_ONLY_KEYS = ("read_only", "readOnly")
QUOTA_POLICY_KEYS = ("quota_policy", "quotaPolicy", "quota")
RETENTION_POLICY_KEYS = ("retention_policy", "retentionPolicy", "retention")
CONCURRENCY_POLICY_KEYS = ("concurrency_policy", "concurrencyPolicy", "concurrency")
MAX_WRITE_BYTES_KEYS = (
    "max_write_bytes",
    "maxWriteBytes",
    "max_bytes",
    "maxBytes",
    "max_file_bytes",
    "maxFileBytes",
)

_ALL_POLICY_KEYS = frozenset({
    *EXECUTION_POLICY_KEYS,
    *WORKDIR_KEYS,
    *ALLOWED_DIRS_KEYS,
    *ALLOWED_TOOLS_KEYS,
    *READ_ONLY_KEYS,
    *QUOTA_POLICY_KEYS,
    *RETENTION_POLICY_KEYS,
    *CONCURRENCY_POLICY_KEYS,
})
_SKILL_DISCOVERY_TOOL_NAMES = frozenset({
    "list_skill_resources",
    "read_skill_resource",
})
_SKILL_MATERIAL_TOOL_NAMES = frozenset({
    "invoke_business_skill",
    "invoke_business_agent",
})


@dataclass(frozen=True)
class ExecutionPolicy:
    """Normalized application-level execution boundary supplied by upstream."""

    workdir: Path | None = None
    allowed_dirs: tuple[Path, ...] = ()
    allowed_tools: frozenset[str] | None = None
    read_only: bool = False
    quota_policy: dict[str, Any] | None = None
    retention_policy: dict[str, Any] | None = None
    concurrency_policy: dict[str, Any] | None = None
    configured: bool = False

    @classmethod
    def from_context(
        cls,
        context: Mapping[str, Any] | None,
        *,
        base_dir: str | Path | None = None,
    ) -> "ExecutionPolicy":
        if not isinstance(context, Mapping):
            return cls()

        policy_source = _policy_payload(context)
        configured = bool(policy_source)
        if not configured:
            return cls()

        base_path = Path(base_dir).expanduser().resolve() if base_dir is not None else Path.cwd().resolve()

        workdir_value = _first_value(policy_source, WORKDIR_KEYS)
        workdir = _resolve_path_value(workdir_value, base_path) if workdir_value is not None else None

        allowed_dirs_value = _first_value(policy_source, ALLOWED_DIRS_KEYS)
        allowed_dirs_provided = allowed_dirs_value is not None
        allowed_dir_values = _path_values(allowed_dirs_value)
        if not allowed_dirs_provided and workdir is not None:
            allowed_dirs = (workdir,)
        else:
            allowed_dirs = tuple(_resolve_path_value(value, base_path) for value in allowed_dir_values)

        if workdir is not None and not allowed_dirs:
            raise ValueError("WORKDIR_NOT_AUTHORIZED: allowed_dirs must include workdir")
        if workdir is not None and not any(_is_within(workdir, allowed) for allowed in allowed_dirs):
            raise ValueError("WORKDIR_NOT_AUTHORIZED: workdir must be inside allowed_dirs")

        allowed_tools_value = _first_value(policy_source, ALLOWED_TOOLS_KEYS)
        allowed_tools = _tool_names(allowed_tools_value) if allowed_tools_value is not None else None
        read_only = _bool_value(_first_value(policy_source, READ_ONLY_KEYS), default=False)

        return cls(
            workdir=workdir,
            allowed_dirs=allowed_dirs,
            allowed_tools=allowed_tools,
            read_only=read_only,
            quota_policy=_policy_map(_first_value(policy_source, QUOTA_POLICY_KEYS), "quota_policy"),
            retention_policy=_policy_map(_first_value(policy_source, RETENTION_POLICY_KEYS), "retention_policy"),
            concurrency_policy=_policy_map(_first_value(policy_source, CONCURRENCY_POLICY_KEYS), "concurrency_policy"),
            configured=True,
        )

    def allows_tool(self, tool_name: str) -> bool:
        if self.allowed_tools is None:
            return True
        if tool_name == "invoke_business_agent" and "invoke_business_skill" in self.allowed_tools:
            return True
        if tool_name in _SKILL_DISCOVERY_TOOL_NAMES and self.allowed_tools & _SKILL_MATERIAL_TOOL_NAMES:
            return True
        return tool_name in self.allowed_tools

    def resolve_path(self, path: str | Path) -> Path:
        raw_path = Path(path).expanduser()
        if raw_path.is_absolute():
            resolved = raw_path.resolve()
        elif self.workdir is not None:
            resolved = (self.workdir / raw_path).resolve()
        else:
            resolved = raw_path.resolve()

        if self.allowed_dirs and not any(_is_within(resolved, allowed) for allowed in self.allowed_dirs):
            raise ValueError("PATH_NOT_AUTHORIZED: path must be inside allowed_dirs")
        return resolved

    def max_write_bytes(self, hard_limit: int) -> int:
        """Return the effective per-write byte limit capped by the worker hard limit."""

        if not self.quota_policy:
            return hard_limit
        for key in MAX_WRITE_BYTES_KEYS:
            value = self.quota_policy.get(key)
            if value is None:
                continue
            try:
                parsed = int(value)
            except (TypeError, ValueError):
                raise ValueError("INVALID_EXECUTION_POLICY: quota_policy max write bytes must be an integer")
            if parsed <= 0:
                raise ValueError("INVALID_EXECUTION_POLICY: quota_policy max write bytes must be positive")
            return min(parsed, hard_limit)
        return hard_limit

    def to_context(self) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        policy_payload: dict[str, Any] = {}
        if self.workdir is not None:
            value = str(self.workdir)
            payload["workdir"] = value
            policy_payload["workdir"] = value
        if self.allowed_dirs:
            value = [str(path) for path in self.allowed_dirs]
            payload["allowed_dirs"] = value
            policy_payload["allowed_dirs"] = value
        if self.allowed_tools is not None:
            value = sorted(self.allowed_tools)
            payload["allowed_tools"] = value
            policy_payload["allowed_tools"] = value
        if self.read_only:
            payload["read_only"] = True
            policy_payload["read_only"] = True
        if self.quota_policy is not None:
            payload["quota_policy"] = self.quota_policy
            policy_payload["quota_policy"] = self.quota_policy
        if self.retention_policy is not None:
            payload["retention_policy"] = self.retention_policy
            policy_payload["retention_policy"] = self.retention_policy
        if self.concurrency_policy is not None:
            payload["concurrency_policy"] = self.concurrency_policy
            policy_payload["concurrency_policy"] = self.concurrency_policy
        if policy_payload:
            payload["execution_policy"] = policy_payload
        return payload


def has_execution_policy(context: Mapping[str, Any] | None) -> bool:
    if not isinstance(context, Mapping):
        return False
    return bool(_policy_payload(context))


def copy_execution_policy_from_context(
    runtime_context: Mapping[str, Any] | None,
    visible_context: Mapping[str, Any] | None,
) -> dict[str, Any]:
    """Copy visible policy aliases into hidden runtime context when absent there."""

    merged = dict(runtime_context or {})
    if has_execution_policy(merged) or not has_execution_policy(visible_context):
        return merged
    merged["execution_policy"] = _policy_payload(visible_context)
    return merged


def strip_execution_policy_context(context: Mapping[str, Any] | None) -> dict[str, Any]:
    if not isinstance(context, Mapping):
        return {}
    return {
        key: value
        for key, value in context.items()
        if key not in _ALL_POLICY_KEYS
    }


def _policy_payload(context: Mapping[str, Any]) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    for key in EXECUTION_POLICY_KEYS:
        value = context.get(key)
        if isinstance(value, Mapping):
            payload.update(value)

    for key in (
        *WORKDIR_KEYS,
        *ALLOWED_DIRS_KEYS,
        *ALLOWED_TOOLS_KEYS,
        *READ_ONLY_KEYS,
        *QUOTA_POLICY_KEYS,
        *RETENTION_POLICY_KEYS,
        *CONCURRENCY_POLICY_KEYS,
    ):
        if key in context:
            payload.setdefault(key, context[key])
    return payload


def _first_value(source: Mapping[str, Any], keys: Sequence[str]) -> Any:
    for key in keys:
        value = source.get(key)
        if value is None:
            continue
        if isinstance(value, str) and not value.strip():
            continue
        return value
    return None


def _resolve_path_value(value: Any, base_path: Path) -> Path:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("INVALID_EXECUTION_POLICY: path value must be a non-empty string")
    path = Path(value.strip()).expanduser()
    if not path.is_absolute():
        path = base_path / path
    return path.resolve()


def _path_values(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [item.strip() for item in _split_string_list(value) if item.strip()]
    if isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        values: list[str] = []
        for item in value:
            if not isinstance(item, str) or not item.strip():
                raise ValueError("INVALID_EXECUTION_POLICY: allowed_dirs must contain non-empty strings")
            values.append(item.strip())
        return values
    raise ValueError("INVALID_EXECUTION_POLICY: allowed_dirs must be a string or list of strings")


def _tool_names(value: Any) -> frozenset[str]:
    if isinstance(value, str):
        raw_values = _split_string_list(value)
    elif isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        raw_values = list(value)
    else:
        raise ValueError("INVALID_EXECUTION_POLICY: allowed_tools must be a string or list of strings")

    names: set[str] = set()
    for item in raw_values:
        if not isinstance(item, str) or not item.strip():
            raise ValueError("INVALID_EXECUTION_POLICY: allowed_tools must contain non-empty strings")
        names.add(item.strip())
    return frozenset(names)


def _bool_value(value: Any, *, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True
        if normalized in {"false", "0", "no", "n", "off"}:
            return False
    raise ValueError("INVALID_EXECUTION_POLICY: read_only must be a boolean")


def _policy_map(value: Any, field_name: str) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, Mapping):
        raise ValueError(f"INVALID_EXECUTION_POLICY: {field_name} must be an object")
    return dict(value)


def _split_string_list(value: str) -> list[str]:
    normalized = value.replace(";", ",")
    return [item.strip() for item in normalized.split(",")]


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False
