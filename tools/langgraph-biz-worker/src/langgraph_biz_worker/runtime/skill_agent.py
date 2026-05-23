"""Standalone SkillAgent facade for Python project integration."""

from __future__ import annotations

import asyncio
import re
import shutil
import uuid
from collections.abc import Callable, Iterable, Mapping
from pathlib import Path, PurePosixPath
from typing import Any

import yaml
from langchain_core.messages import AIMessage

from ..models import FrameStatus, QueryEvent
from .execution_policy import ExecutionPolicy, strip_execution_policy_context
from .file_layout import generate_standard_context_id, require_standard_context_id
from .frame_store import FrameStore
from .llm_skill_agent import LlmSkillAgent
from .skill_identity import SkillNameValidationError, validate_skill_name
from .skill_registry import SkillRegistry
from .skill_runtime import SkillRuntime
from .tool_provider import ToolProvider

_FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---", re.DOTALL)
_RESERVED_SKILL_NAMES = {"builtin", "public", ".manifests"}


class SkillAgent:
    """Small Python-facing facade over the BizWorker SkillRuntime."""

    def __init__(
        self,
        skills_root: str | Path,
        *,
        tool_provider: ToolProvider | None = None,
        model_provider: Any | Callable[[], Any] | None = None,
        runtime: SkillRuntime | None = None,
        data_root: str | Path | None = None,
        max_iterations: int = 6,
    ) -> None:
        self._skills_root = Path(skills_root)
        self._data_root = Path(data_root) if data_root else self._skills_root.parent / "data"
        self._registry = SkillRegistry(
            skills_root=self._skills_root,
            manifests_dir=self._skills_root / ".manifests",
            data_root=self._data_root,
        )
        self._runtime = runtime or SkillRuntime(
            frame_store=FrameStore(),
            skill_registry=self._registry,
        )
        if runtime is not None:
            self._runtime.registry = self._registry
        self._tool_provider = tool_provider
        self._model_provider = model_provider
        self._max_iterations = max_iterations

    def reload_skills(self) -> None:
        """Reload local skill manifests from the configured skills root."""
        self._registry.load(include_standalone=True)

    def list_skills(self) -> list[dict[str, Any]]:
        """List standalone skills managed directly under ``skills_root``."""
        self.reload_skills()
        if not self._skills_root.is_dir():
            return []

        skills: list[dict[str, Any]] = []
        for skill_dir in sorted(self._skills_root.iterdir(), key=lambda path: path.name):
            if not skill_dir.is_dir() or not (skill_dir / "SKILL.md").is_file():
                continue
            try:
                skill_name = _validate_governed_skill_name(skill_dir.name)
            except SkillNameValidationError:
                continue

            validation = self.validate_skill(skill_name)
            manifest = self._registry.get_manifest(skill_name)
            skills.append(_skill_summary(skill_name, manifest, validation))
        return skills

    def get_skill(self, skill_name: str) -> dict[str, Any]:
        """Return a local skill definition and parsed manifest summary."""
        normalized_skill_name = _validate_governed_skill_name(skill_name)
        skill_dir = self._skill_dir(normalized_skill_name)
        skill_md = skill_dir / "SKILL.md"
        if not skill_md.is_file():
            raise FileNotFoundError(f"Skill not found: {normalized_skill_name}")

        self.reload_skills()
        manifest = self._registry.get_manifest(normalized_skill_name)
        validation = self.validate_skill(normalized_skill_name)
        return {
            "skill_name": normalized_skill_name,
            "path": str(skill_dir),
            "content": skill_md.read_text(encoding="utf-8"),
            "manifest": _manifest_to_dict(manifest) if manifest else None,
            "validation": validation,
        }

    def register_skill(
        self,
        skill_name: str,
        *,
        content: str | None = None,
        markdown_body: str | None = None,
        description: str = "",
        instructions: str = "",
        tools: Iterable[str] | str | None = None,
        resources: Mapping[str, str | bytes] | None = None,
        overwrite: bool = False,
    ) -> dict[str, Any]:
        """Create or replace a local skill directory under ``skills_root``."""
        normalized_skill_name = _validate_governed_skill_name(skill_name)
        skill_dir = self._skill_dir(normalized_skill_name)
        if skill_dir.exists() and not skill_dir.is_dir():
            raise FileExistsError(f"Skill path exists and is not a directory: {normalized_skill_name}")
        if skill_dir.exists() and not overwrite:
            raise FileExistsError(f"Skill already exists: {normalized_skill_name}")

        skill_content = _resolve_skill_content(
            skill_name=normalized_skill_name,
            content=content,
            markdown_body=markdown_body,
            description=description,
            instructions=instructions,
            tools=tools,
        )
        _parse_skill_content_metadata(skill_content)
        planned_resources = _plan_resource_writes(skill_dir, resources)

        if skill_dir.exists():
            shutil.rmtree(skill_dir)
        skill_dir.mkdir(parents=True, exist_ok=False)
        (skill_dir / "SKILL.md").write_text(_ensure_trailing_newline(skill_content), encoding="utf-8")

        for target_path, value in planned_resources:
            target_path.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(value, bytes):
                target_path.write_bytes(value)
            else:
                target_path.write_text(value, encoding="utf-8")

        validation = self.validate_skill(normalized_skill_name)
        if not validation["ok"]:
            errors = ", ".join(validation["errors"])
            raise ValueError(f"Registered skill is invalid: {errors}")
        return self.get_skill(normalized_skill_name)

    def delete_skill(self, skill_name: str) -> dict[str, Any]:
        """Delete one local skill directory from ``skills_root``."""
        normalized_skill_name = _validate_governed_skill_name(skill_name)
        skill_dir = self._skill_dir(normalized_skill_name)
        if not skill_dir.is_dir():
            raise FileNotFoundError(f"Skill not found: {normalized_skill_name}")

        root = self._skills_root.resolve()
        if skill_dir.resolve() == root:
            raise ValueError("Refusing to delete skills root")

        shutil.rmtree(skill_dir)
        self.reload_skills()
        return {"deleted": True, "skill_name": normalized_skill_name}

    def validate_skill(self, skill_name: str) -> dict[str, Any]:
        """Validate a local skill without exposing internal ``skill_id`` fields."""
        normalized_skill_name = _validate_governed_skill_name(skill_name)
        skill_dir = self._skill_dir(normalized_skill_name)
        skill_md = skill_dir / "SKILL.md"
        errors: list[str] = []
        warnings: list[str] = []

        if not skill_md.is_file():
            errors.append("SKILL.md not found")
            return {
                "ok": False,
                "skill_name": normalized_skill_name,
                "manifest_id": None,
                "errors": errors,
                "warnings": warnings,
            }

        try:
            _parse_skill_content_metadata(skill_md.read_text(encoding="utf-8"))
        except ValueError as exc:
            errors.append(str(exc))

        self.reload_skills()
        manifest = self._registry.get_manifest(normalized_skill_name)
        if manifest is None:
            errors.append("Skill manifest is not loadable")
            manifest_id = None
        else:
            manifest_id = manifest.id
            if manifest.id != normalized_skill_name:
                warnings.append(
                    f"frontmatter name '{manifest.id}' differs from folder skill_name '{normalized_skill_name}'"
                )

        return {
            "ok": not errors,
            "skill_name": normalized_skill_name,
            "manifest_id": manifest_id,
            "errors": errors,
            "warnings": warnings,
        }

    async def ask(
        self,
        *,
        skill_name: str,
        message: str,
        context: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        normalized_skill_name = validate_skill_name(skill_name)
        self.reload_skills()
        manifest = self._registry.get_manifest(normalized_skill_name)
        if manifest is None:
            raise ValueError(f"Skill manifest not found: {normalized_skill_name}")

        runtime_context = dict(context or {})
        task_id = str(runtime_context.get("task_id") or f"task_{uuid.uuid4().hex[:12]}")
        account_id = _string_or_none(runtime_context.get("account_id") or runtime_context.get("accountId"))
        conversation_id = _conversation_id_from_context(runtime_context) or generate_standard_context_id()
        runtime_context["contextId"] = conversation_id
        ExecutionPolicy.from_context(runtime_context)
        frame_id = self._runtime.invoke_skill(
            task_id=task_id,
            skill_id=normalized_skill_name,
            skill_input=strip_execution_policy_context(runtime_context),
            conversation_id=conversation_id,
            session_id=conversation_id,
            current_task_id=task_id,
            origin_task_id=task_id,
        )
        runtime_context["task_id"] = task_id
        runtime_context["skill_name"] = normalized_skill_name

        runner = LlmSkillAgent(
            self._resolve_model(),
            self._runtime,
            max_iterations=self._max_iterations,
            data_root=self._data_root,
            tool_provider=self._tool_provider,
        )
        events = await asyncio.to_thread(
            runner.run,
            task_id=task_id,
            frame_id=frame_id,
            prompt=message,
            account_id=account_id,
            runtime_context=runtime_context,
        )
        frame = self._runtime.get_frame(frame_id)
        return {
            "ok": bool(frame and frame.status == FrameStatus.COMPLETED),
            "task_id": task_id,
            "frame_id": frame_id,
            "skill_name": normalized_skill_name,
            "summary": frame.result_summary if frame else None,
            "structured_output": frame.output if frame else None,
            "events": [_event_to_dict(event) for event in events],
        }

    def _resolve_model(self) -> Any:
        if self._model_provider is None:
            return _SubmitOnlyModel()
        if hasattr(self._model_provider, "invoke"):
            return self._model_provider
        if callable(self._model_provider):
            return self._model_provider()
        return self._model_provider

    def _skill_dir(self, skill_name: str) -> Path:
        return _resolve_inside_root(self._skills_root, Path(skill_name))


class _SubmitOnlyModel:
    def bind_tools(self, tools: list[dict[str, Any]]) -> "_SubmitOnlyModel":
        self.bound_tools = tools
        return self

    def invoke(self, messages: list[Any]) -> AIMessage:
        return AIMessage(content="", tool_calls=[{
            "id": "call_submit",
            "name": "submit_skill_result",
            "args": {
                "summary": "Skill completed.",
                "structured_output": {},
            },
        }])


def _string_or_none(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _conversation_id_from_context(context: Mapping[str, Any]) -> str | None:
    for key in ("contextId", "context_id", "conversationId", "conversation_id"):
        value = context.get(key)
        if isinstance(value, str) and value.strip():
            return require_standard_context_id(value)
    return None


def _event_to_dict(event: QueryEvent) -> dict[str, Any]:
    return event.model_dump(exclude_none=True)


def _validate_governed_skill_name(value: Any) -> str:
    skill_name = validate_skill_name(value)
    if skill_name.startswith(".") or skill_name in _RESERVED_SKILL_NAMES:
        raise SkillNameValidationError("skill_name is reserved for BizWorker internals")
    return skill_name


def _resolve_inside_root(root: Path, relative_path: Path) -> Path:
    resolved_root = root.resolve()
    target = (resolved_root / relative_path).resolve()
    try:
        target.relative_to(resolved_root)
    except ValueError as exc:
        raise ValueError(f"Path escapes skills root: {relative_path}") from exc
    return target


def _resolve_skill_content(
    *,
    skill_name: str,
    content: str | None,
    markdown_body: str | None,
    description: str,
    instructions: str,
    tools: Iterable[str] | str | None,
) -> str:
    if content is not None and markdown_body is not None:
        raise ValueError("Provide only one of content or markdown_body")
    if content is not None:
        return content
    if markdown_body is not None:
        return markdown_body

    frontmatter: dict[str, Any] = {"name": skill_name}
    if description:
        frontmatter["description"] = description
    tool_list = _normalize_tool_list(tools)
    if tool_list:
        frontmatter["tools"] = tool_list
    yaml_text = yaml.safe_dump(frontmatter, sort_keys=False, allow_unicode=True).strip()
    body = instructions.strip()
    if body:
        body = f"\n\n{body}\n"
    else:
        body = "\n"
    return f"---\n{yaml_text}\n---\n{body}"


def _parse_skill_content_metadata(content: str) -> dict[str, Any]:
    match = _FRONTMATTER_RE.match(content)
    if not match:
        raise ValueError("SKILL.md must start with YAML frontmatter")
    try:
        metadata = yaml.safe_load(match.group(1))
    except Exception as exc:
        raise ValueError("SKILL.md frontmatter is invalid YAML") from exc
    if not isinstance(metadata, dict):
        raise ValueError("SKILL.md frontmatter must be a mapping")
    if not metadata.get("name"):
        raise ValueError("SKILL.md frontmatter must include name")
    return metadata


def _plan_resource_writes(
    skill_dir: Path,
    resources: Mapping[str, str | bytes] | None,
) -> list[tuple[Path, str | bytes]]:
    planned: list[tuple[Path, str | bytes]] = []
    for resource_path, value in (resources or {}).items():
        if not isinstance(value, (str, bytes)):
            raise ValueError("resource value must be str or bytes")
        relative_path = _validate_resource_relative_path(resource_path)
        target_path = _resolve_inside_root(skill_dir, relative_path)
        if target_path.name == "SKILL.md" and target_path.parent == skill_dir.resolve():
            raise ValueError("resources must not overwrite SKILL.md")
        planned.append((target_path, value))
    return planned


def _validate_resource_relative_path(value: str) -> Path:
    if not isinstance(value, str) or not value or value.strip() != value:
        raise ValueError("resource path must be a non-empty trimmed string")
    if "\\" in value or ":" in value:
        raise ValueError("resource path must be a relative POSIX path")
    if value.startswith("/") or "//" in value:
        raise ValueError("resource path must not be absolute or contain repeated separators")

    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ValueError("resource path must not contain empty, current, or parent segments")
    return Path(*path.parts)


def _normalize_tool_list(tools: Iterable[str] | str | None) -> list[str]:
    if tools is None:
        return []
    if isinstance(tools, str):
        return [tools]
    return list(tools)


def _ensure_trailing_newline(value: str) -> str:
    return value if value.endswith("\n") else f"{value}\n"


def _skill_summary(
    skill_name: str,
    manifest: Any,
    validation: Mapping[str, Any],
) -> dict[str, Any]:
    summary = {
        "skill_name": skill_name,
        "valid": validation["ok"],
        "errors": list(validation["errors"]),
        "warnings": list(validation["warnings"]),
    }
    if manifest is not None:
        summary.update(_manifest_to_dict(manifest))
    return summary


def _manifest_to_dict(manifest: Any) -> dict[str, Any]:
    return {
        "manifest_id": manifest.id,
        "name": manifest.name,
        "description": manifest.description,
        "allowed_tools": list(manifest.allowed_tools or []),
        "visibility": manifest.visibility,
        "context_visibility": manifest.context_visibility,
        "client_app_id": manifest.client_app_id,
    }
