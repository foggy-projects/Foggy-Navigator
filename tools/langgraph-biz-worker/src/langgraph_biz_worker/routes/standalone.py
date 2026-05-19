"""Standalone SkillAgent service endpoints."""

from __future__ import annotations

from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import AliasChoices, BaseModel, Field

from ..auth import verify_token
from ..runtime.skill_agent import SkillAgent
from ..runtime.skill_identity import SkillNameValidationError
from ..runtime.tool_provider import ToolProvider

router = APIRouter(
    prefix="/api/v1",
    tags=["standalone"],
    dependencies=[Depends(verify_token)],
)

_skills_root: Path | None = None
_data_root: Path | None = None
_tool_provider: ToolProvider | None = None
_model_provider: Any | Callable[[], Any] | None = None
_tool_modules: list[str] = []
_model_provider_configured = False
_llm_provider = ""
_on_change: Callable[[], None] | None = None


class SkillCreateRequest(BaseModel):
    """Request body for creating a local standalone skill."""

    skill_name: str
    content: str | None = None
    markdown_body: str | None = None
    description: str = ""
    instructions: str = ""
    tools: list[str] | str | None = None
    resources: dict[str, str] = Field(default_factory=dict)
    overwrite: bool = False


class AskRequest(BaseModel):
    """Request body for standalone skill execution."""

    skill_name: str
    message: str = Field(validation_alias=AliasChoices("message", "prompt"))
    context: dict[str, Any] = Field(default_factory=dict)


def configure(
    skills_root: str | Path,
    *,
    data_root: str | Path | None = None,
    tool_provider: ToolProvider | None = None,
    model_provider: Any | Callable[[], Any] | None = None,
    tool_modules: Iterable[str] | None = None,
    model_provider_configured: bool | None = None,
    llm_provider: str = "",
    on_change: Callable[[], None] | None = None,
) -> None:
    """Configure service-scoped SkillAgent dependencies."""
    global _skills_root, _data_root, _tool_provider, _model_provider, _tool_modules
    global _model_provider_configured, _llm_provider, _on_change
    _skills_root = Path(skills_root)
    _data_root = Path(data_root) if data_root is not None else _skills_root.parent / "data"
    _tool_provider = tool_provider
    _model_provider = model_provider
    _tool_modules = list(tool_modules or [])
    _model_provider_configured = bool(model_provider) if model_provider_configured is None else model_provider_configured
    _llm_provider = llm_provider.strip().lower()
    _on_change = on_change


@router.get("/standalone/status")
async def standalone_status() -> dict[str, Any]:
    """Return non-sensitive standalone service diagnostics."""
    return {
        "configured": _skills_root is not None,
        "skillsRoot": str(_skills_root) if _skills_root is not None else None,
        "dataRoot": str(_data_root) if _data_root is not None else None,
        "toolModules": list(_tool_modules),
        "loadedTools": _loaded_tool_names(),
        "modelProviderConfigured": _model_provider_configured,
        "llmProvider": _llm_provider,
    }


@router.get("/skills")
async def list_skills() -> dict[str, Any]:
    """List standalone skills directly managed under ``skills_root``."""
    return {"skills": _agent().list_skills()}


@router.post("/skills", status_code=status.HTTP_201_CREATED)
async def register_skill(request: SkillCreateRequest) -> dict[str, Any]:
    """Create or replace one standalone skill."""
    try:
        result = _agent().register_skill(
            request.skill_name,
            content=request.content,
            markdown_body=request.markdown_body,
            description=request.description,
            instructions=request.instructions,
            tools=_tools_payload(request.tools),
            resources=request.resources,
            overwrite=request.overwrite,
        )
    except Exception as exc:  # noqa: BLE001 - mapped to stable API errors below
        _raise_http_error(exc)
    _notify_change()
    return result


@router.get("/skills/{skill_name}")
async def get_skill(skill_name: str) -> dict[str, Any]:
    """Return one standalone skill definition and manifest summary."""
    try:
        return _agent().get_skill(skill_name)
    except Exception as exc:  # noqa: BLE001 - mapped to stable API errors below
        _raise_http_error(exc)


@router.delete("/skills/{skill_name}")
async def delete_skill(skill_name: str) -> dict[str, Any]:
    """Delete one standalone skill."""
    try:
        result = _agent().delete_skill(skill_name)
    except Exception as exc:  # noqa: BLE001 - mapped to stable API errors below
        _raise_http_error(exc)
    _notify_change()
    return result


@router.post("/skills/{skill_name}/validate")
async def validate_skill(skill_name: str) -> dict[str, Any]:
    """Validate one standalone skill."""
    try:
        return _agent().validate_skill(skill_name)
    except Exception as exc:  # noqa: BLE001 - mapped to stable API errors below
        _raise_http_error(exc)


@router.post("/ask")
async def ask(request: AskRequest) -> dict[str, Any]:
    """Execute one skill by public ``skill_name``."""
    try:
        return await _agent().ask(
            skill_name=request.skill_name,
            message=request.message,
            context=request.context,
        )
    except Exception as exc:  # noqa: BLE001 - mapped to stable API errors below
        _raise_http_error(exc)


def _agent() -> SkillAgent:
    if _skills_root is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Standalone SkillAgent service is not configured",
        )
    return SkillAgent(
        _skills_root,
        data_root=_data_root,
        tool_provider=_tool_provider,
        model_provider=_model_provider,
    )


def _tools_payload(value: list[str] | str | None) -> Iterable[str] | str | None:
    return value


def _notify_change() -> None:
    if _on_change is not None:
        _on_change()


def _loaded_tool_names() -> list[str]:
    if _tool_provider is None:
        return []
    try:
        specs = _tool_provider.list_tools("*", {})
    except Exception:
        return []
    names = []
    for spec in specs:
        name = getattr(spec, "name", None)
        if isinstance(name, str) and name:
            names.append(name)
    return sorted(set(names))


def _raise_http_error(exc: Exception) -> None:
    if isinstance(exc, HTTPException):
        raise exc
    if isinstance(exc, SkillNameValidationError):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if isinstance(exc, FileExistsError):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    if isinstance(exc, FileNotFoundError):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    if isinstance(exc, ValueError):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    raise exc
