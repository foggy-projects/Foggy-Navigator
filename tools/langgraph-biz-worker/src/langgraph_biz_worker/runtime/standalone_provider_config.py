"""Standalone SkillAgent provider assembly from settings."""

from __future__ import annotations

import importlib
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..config import Settings
from .llm_skill_router import create_chat_model
from .tool_provider import LocalPythonToolProvider


@dataclass(frozen=True)
class StandaloneServiceConfig:
    skills_root: Path
    data_root: Path
    tool_provider: LocalPythonToolProvider | None = None
    model_provider: Any | Callable[[], Any] | None = None
    tool_modules: list[str] = field(default_factory=list)
    model_provider_configured: bool = False
    llm_provider: str = ""


def build_standalone_service_config(
    settings: Settings,
    *,
    default_skills_root: Path,
) -> StandaloneServiceConfig:
    """Build service dependencies for the standalone SkillAgent route."""
    skills_root = _configured_path(settings.standalone_skills_root) or default_skills_root
    data_root = (
        _configured_path(settings.standalone_data_root)
        or _configured_path(settings.data_root)
        or skills_root.parent / "data"
    )
    tool_modules = split_tool_module_specs(settings.standalone_tool_modules)
    tool_provider = load_tool_provider_from_specs(tool_modules)
    model_provider = load_model_provider(settings.standalone_model_provider)
    if model_provider is None:
        model_provider = create_chat_model(settings)
    return StandaloneServiceConfig(
        skills_root=skills_root,
        data_root=data_root,
        tool_provider=tool_provider,
        model_provider=model_provider,
        tool_modules=tool_modules,
        model_provider_configured=model_provider is not None,
        llm_provider=settings.llm_provider.strip().lower(),
    )


def load_tool_provider(module_specs: str) -> LocalPythonToolProvider | None:
    """Load local Python tools from comma/semicolon separated module specs."""
    return load_tool_provider_from_specs(split_tool_module_specs(module_specs))


def load_tool_provider_from_specs(specs: list[str]) -> LocalPythonToolProvider | None:
    """Load local Python tools from normalized module specs."""
    if not specs:
        return None

    provider = LocalPythonToolProvider()
    for spec in specs:
        _load_tool_module(spec, provider)
    return provider


def load_model_provider(import_path: str) -> Any | Callable[[], Any] | None:
    """Resolve a custom model provider object or factory from an import path."""
    normalized = import_path.strip()
    if not normalized:
        return None
    return _resolve_import(normalized)


def split_tool_module_specs(value: str) -> list[str]:
    """Split comma/semicolon separated module specs."""
    return [part.strip() for part in re.split(r"[,;]", value or "") if part.strip()]


def _load_tool_module(spec: str, provider: LocalPythonToolProvider) -> None:
    resolved = _resolve_import(spec) if _looks_like_object_spec(spec) else importlib.import_module(spec)
    if callable(resolved) and not hasattr(resolved, "register_tools"):
        resolved(provider)
        return

    register_tools = getattr(resolved, "register_tools", None)
    if not callable(register_tools):
        raise ValueError(f"Standalone tool module must expose register_tools(provider): {spec}")
    register_tools(provider)


def _looks_like_object_spec(spec: str) -> bool:
    return ":" in spec


def _resolve_import(import_path: str) -> Any:
    module_name, attr_path = _split_import_path(import_path)
    module = importlib.import_module(module_name)
    target: Any = module
    for part in attr_path.split("."):
        target = getattr(target, part)
    return target


def _split_import_path(import_path: str) -> tuple[str, str]:
    if ":" in import_path:
        module_name, attr_path = import_path.split(":", 1)
        if not module_name or not attr_path:
            raise ValueError(f"Invalid import path: {import_path}")
        return module_name, attr_path

    module_name, separator, attr_path = import_path.rpartition(".")
    if not separator or not module_name or not attr_path:
        raise ValueError(f"Import path must include an attribute: {import_path}")
    return module_name, attr_path


def _configured_path(value: str) -> Path | None:
    text = value.strip()
    return Path(text).expanduser() if text else None
