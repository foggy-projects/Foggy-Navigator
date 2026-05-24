"""Skill management endpoints — sync and webhook.

- POST /api/v1/skills/sync  — manual trigger to pull public skills from GitLab
- POST /api/v1/skills/webhook — GitLab push event webhook receiver
"""

from __future__ import annotations

import hashlib
import logging
import shutil
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field

from ..auth import verify_token
from ..config import settings
from ..runtime.skill_git_sync import sync_public_skills

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/skills", tags=["skills"])

# Resolved once at import; may be overridden via configure()
_skills_root: Path | None = None
_on_sync_complete = None  # callback: () -> None (reload registry)


def configure(skills_root: Path, on_sync_complete=None) -> None:
    """Wire the skills root directory and post-sync callback."""
    global _skills_root, _on_sync_complete
    _skills_root = skills_root
    _on_sync_complete = on_sync_complete


class SyncResponse(BaseModel):
    success: bool
    message: str
    skills_found: list[str]


class SkillResource(BaseModel):
    path: str
    content: str
    sha256: str | None = None


class MaterializeRequest(BaseModel):
    skill_id: str
    scope: str = "public"
    account_id: str | None = None
    client_app_id: str | None = None
    name: str | None = None
    display_name: str | None = None
    description: str | None = None
    context_visibility: str | None = None
    markdown_body: str | None = None
    resources: list[SkillResource] = Field(default_factory=list)


class ClearRequest(BaseModel):
    scope: str = "public"
    skill_id: str | None = None
    account_id: str | None = None
    client_app_id: str | None = None
    dry_run: bool = False


@router.post("/materialize", dependencies=[Depends(verify_token)])
async def materialize_skill(req: MaterializeRequest) -> dict:
    """Materialize a dynamically registered skill to the local filesystem."""
    if not _skills_root:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Skills route not configured",
        )
        
    from ..runtime.skill_registry import _validate_path_segment

    try:
        skill_id = _validate_path_segment(req.skill_id, "skill_id")
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    client_app_id = None
    if req.scope == "account":
        if not req.account_id:
            raise HTTPException(status_code=400, detail="account_id required for account scope")
        from ..runtime.skill_registry import _validate_account_id
        try:
            account_id = _validate_account_id(req.account_id)
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        target_dir = _skills_root.parent / "data" / "accounts" / account_id / "agent" / "skills" / skill_id
        visibility = "private"
    else:
        if req.client_app_id:
            try:
                client_app_id = _validate_path_segment(req.client_app_id, "client_app_id")
            except ValueError as e:
                raise HTTPException(status_code=400, detail=str(e))
            target_dir = _skills_root / "public" / "apps" / client_app_id / skill_id
        else:
            target_dir = _skills_root / "public" / skill_id
        visibility = "public"

    _validate_bundle_resources(req.resources)
    target_dir.mkdir(parents=True, exist_ok=True)
    if target_dir.is_symlink():
        raise HTTPException(status_code=400, detail="skill target directory must not be a symlink")
    
    manifest_name = req.name or skill_id
    display_name = req.display_name or req.name or skill_id
    yaml_lines = [
        "---",
        f"name: {manifest_name}",
    ]
    if req.description:
        desc = req.description.replace("\n", " ").replace("\r", "")
        yaml_lines.append(f"description: {desc}")
    yaml_lines.append("metadata:")
    yaml_lines.append(f"  display_name: {display_name}")
    yaml_lines.append(f"  visibility: {visibility}")
    yaml_lines.append(f"  context-visibility: {_normalize_context_visibility(req.context_visibility)}")
    if client_app_id:
        yaml_lines.append(f"  client_app_id: {client_app_id}")
    yaml_lines.append("---")
    
    md_content = "\n".join(yaml_lines) + "\n\n"
    if req.markdown_body:
        md_content += req.markdown_body
        
    skill_file = target_dir / "SKILL.md"
    skill_file.write_text(md_content, encoding="utf-8")

    _replace_bundle_resources(target_dir, req.resources)
    
    if _on_sync_complete:
        _on_sync_complete()
        
    return {"status": "success", "path": str(skill_file), "client_app_id": client_app_id}


@router.post("/clear", dependencies=[Depends(verify_token)])
async def clear_skill(req: ClearRequest) -> dict:
    """Remove dynamically materialized skill directories from the local filesystem."""
    if not _skills_root:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Skills route not configured",
        )

    from ..runtime.skill_registry import _validate_path_segment

    if req.scope == "account":
        if not req.account_id:
            raise HTTPException(status_code=400, detail="account_id required for account scope")
        from ..runtime.skill_registry import _validate_account_id
        try:
            account_id = _validate_account_id(req.account_id)
            skill_id = _validate_path_segment(req.skill_id, "skill_id") if req.skill_id else None
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        target_dir = _skills_root.parent / "data" / "accounts" / account_id / "agent" / "skills"
    else:
        try:
            client_app_id = _validate_path_segment(req.client_app_id, "client_app_id") if req.client_app_id else None
            skill_id = _validate_path_segment(req.skill_id, "skill_id") if req.skill_id else None
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        target_dir = _skills_root / "public" / "apps" / client_app_id if client_app_id else _skills_root / "public"

    if skill_id:
        target_dir = target_dir / skill_id

    root = (_skills_root.parent if req.scope == "account" else _skills_root).resolve()
    resolved = target_dir.resolve()
    if root not in [resolved, *resolved.parents]:
        raise HTTPException(status_code=400, detail="skill clear target escapes skills root")
    if target_dir.is_symlink():
        raise HTTPException(status_code=400, detail="skill clear target must not be a symlink")

    exists = target_dir.exists()
    deleted_count = _count_skill_dirs(target_dir, bool(skill_id)) if exists else 0
    if exists and not req.dry_run:
        if target_dir.is_file():
            target_dir.unlink()
        else:
            shutil.rmtree(target_dir)
        if _on_sync_complete:
            _on_sync_complete()

    return {
        "status": "dry-run" if req.dry_run else "cleared",
        "target": str(target_dir),
        "exists": exists,
        "deleted_count": deleted_count,
    }


def _count_skill_dirs(target_dir: Path, target_is_skill: bool) -> int:
    if target_is_skill:
        return 1 if (target_dir / "SKILL.md").exists() else 0
    if not target_dir.is_dir():
        return 0
    return sum(1 for child in target_dir.iterdir() if child.is_dir() and (child / "SKILL.md").exists())


def _replace_bundle_resources(target_dir: Path, resources: list[SkillResource]) -> None:
    """Replace references/assets resources for an idempotent materialize."""
    target_root = target_dir.resolve()
    for child_name in ("references", "assets"):
        child = target_dir / child_name
        if child.is_symlink() or child.is_file():
            child.unlink()
        elif child.is_dir():
            resolved = child.resolve()
            if target_root not in [resolved, *resolved.parents]:
                raise HTTPException(status_code=400, detail=f"unsafe resource directory: {child_name}")
            shutil.rmtree(child)

    seen: set[str] = set()
    for resource in resources or []:
        rel = _validate_resource_path(resource.path)
        if rel in seen:
            raise HTTPException(status_code=400, detail=f"duplicate resource path: {resource.path}")
        seen.add(rel)
        content_bytes = resource.content.encode("utf-8")
        if resource.sha256:
            actual = hashlib.sha256(content_bytes).hexdigest()
            if actual.lower() != resource.sha256.lower():
                raise HTTPException(status_code=400, detail=f"sha256 mismatch for resource: {resource.path}")
        target = (target_dir / rel).resolve()
        if target_root not in [target.parent, *target.parent.parents]:
            raise HTTPException(status_code=400, detail=f"resource path escapes skill directory: {resource.path}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content_bytes)


def _normalize_context_visibility(value: str | None) -> str:
    normalized = (value or "isolated").strip().replace("_", "-").lower()
    if normalized in {"isolated", "summary"}:
        return normalized
    if normalized == "passthrough":
        return "isolated"
    raise HTTPException(status_code=400, detail="invalid context_visibility")


_ALLOWED_RESOURCE_EXTENSIONS = {".md", ".txt", ".json", ".yaml", ".yml", ".fsscript"}


def _validate_bundle_resources(resources: list[SkillResource]) -> None:
    seen: set[str] = set()
    for resource in resources or []:
        rel = _validate_resource_path(resource.path)
        if rel in seen:
            raise HTTPException(status_code=400, detail=f"duplicate resource path: {resource.path}")
        seen.add(rel)
        if resource.sha256:
            actual = hashlib.sha256(resource.content.encode("utf-8")).hexdigest()
            if actual.lower() != resource.sha256.lower():
                raise HTTPException(status_code=400, detail=f"sha256 mismatch for resource: {resource.path}")


def _validate_resource_path(path: str) -> str:
    normalized = path.replace("\\", "/")
    if (
        not normalized
        or normalized != path
        or normalized.startswith("/")
        or normalized.endswith("/")
        or "//" in normalized
    ):
        raise HTTPException(status_code=400, detail=f"invalid resource path: {path}")
    parts = normalized.split("/")
    if parts[0] not in {"references", "assets"}:
        raise HTTPException(status_code=400, detail="resource path must start with references/ or assets/")
    if len(parts) < 2:
        raise HTTPException(status_code=400, detail=f"invalid resource path: {path}")
    for part in parts:
        if part in {"", ".", ".."} or "/" in part or "\\" in part:
            raise HTTPException(status_code=400, detail=f"invalid resource path segment: {part}")
    if Path(parts[-1]).suffix not in _ALLOWED_RESOURCE_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"unsupported resource file type: {path}")
    return normalized


@router.post("/sync", response_model=SyncResponse, dependencies=[Depends(verify_token)])
async def sync_skills() -> SyncResponse:
    """Manually trigger a pull of public skills from GitLab."""
    if not settings.skill_git_repo:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="skill_git_repo not configured",
        )
    if not _skills_root:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Skills route not configured",
        )

    result = sync_public_skills(
        repo_url=settings.skill_git_repo,
        target_dir=_skills_root / "public",
        branch=settings.skill_git_branch,
        token=settings.skill_git_token,
    )

    if result.success and _on_sync_complete:
        _on_sync_complete()

    return SyncResponse(
        success=result.success,
        message=result.message,
        skills_found=result.skills_found,
    )


@router.post("/webhook")
async def gitlab_webhook(request: Request) -> dict:
    """Receive GitLab push event and trigger skill sync.

    Validates the webhook secret token via X-Gitlab-Token header.
    Only responds to pushes on the configured branch.
    """
    # 1. Verify webhook secret
    gitlab_token = request.headers.get("X-Gitlab-Token", "")
    if settings.skill_webhook_secret and gitlab_token != settings.skill_webhook_secret:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid webhook token")

    if not settings.skill_git_repo or not _skills_root:
        return {"status": "ignored", "reason": "skill sync not configured"}

    # 2. Parse push event
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid JSON body")

    ref = body.get("ref", "")
    expected_ref = f"refs/heads/{settings.skill_git_branch}"
    if ref != expected_ref:
        logger.debug("Webhook ignored: ref=%s, expected=%s", ref, expected_ref)
        return {"status": "ignored", "reason": f"ref {ref} does not match {expected_ref}"}

    # 3. Sync
    logger.info("GitLab webhook triggered skill sync (ref=%s)", ref)
    result = sync_public_skills(
        repo_url=settings.skill_git_repo,
        target_dir=_skills_root / "public",
        branch=settings.skill_git_branch,
        token=settings.skill_git_token,
    )

    if result.success and _on_sync_complete:
        _on_sync_complete()

    return {
        "status": "synced" if result.success else "failed",
        "message": result.message,
        "skills_found": result.skills_found,
    }
