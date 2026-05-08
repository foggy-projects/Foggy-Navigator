"""Skill management endpoints — sync and webhook.

- POST /api/v1/skills/sync  — manual trigger to pull public skills from GitLab
- POST /api/v1/skills/webhook — GitLab push event webhook receiver
"""

from __future__ import annotations

import hashlib
import hmac
import logging
from pathlib import Path

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from pydantic import BaseModel

from ..auth import verify_token
from ..config import settings
from ..runtime.skill_git_sync import SyncResult, sync_public_skills

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


class MaterializeRequest(BaseModel):
    skill_id: str
    scope: str = "public"
    account_id: str | None = None
    client_app_id: str | None = None
    name: str | None = None
    display_name: str | None = None
    description: str | None = None
    markdown_body: str | None = None


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
        target_dir = _skills_root.parent / "data" / "accounts" / account_id / "skills" / skill_id
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

    target_dir.mkdir(parents=True, exist_ok=True)
    
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
    if client_app_id:
        yaml_lines.append(f"  client_app_id: {client_app_id}")
    yaml_lines.append("---")
    
    md_content = "\n".join(yaml_lines) + "\n\n"
    if req.markdown_body:
        md_content += req.markdown_body
        
    skill_file = target_dir / "SKILL.md"
    skill_file.write_text(md_content, encoding="utf-8")
    
    if _on_sync_complete:
        _on_sync_complete()
        
    return {"status": "success", "path": str(skill_file), "client_app_id": client_app_id}


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
