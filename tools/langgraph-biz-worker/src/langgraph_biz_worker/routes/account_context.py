"""Account context file endpoints for Navigator OpenAPI mediation."""

from __future__ import annotations

import hashlib
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

from ..auth import verify_token
from ..runtime.account_context_files import (
    ACCOUNT_CONTEXT_FILE_ORDER,
    MAX_ACCOUNT_CONTEXT_FILE_BYTES,
    _resolve_context_file,
)
from ..runtime.account_path_guard import PathGuardError, _validate_account_id

router = APIRouter(prefix="/api/v1/account-context", tags=["account-context"])

_data_root: Path | None = None

WRITABLE_CONTEXT_FILES = frozenset({"ACCOUNT_POLICY.md"})
MAX_WRITE_BYTES = 64 * 1024


def configure(data_root: Path) -> None:
    """Wire the worker data root used by account-scoped artifacts."""
    global _data_root
    _data_root = data_root


class AccountContextFileMetadata(BaseModel):
    file_name: str
    exists: bool
    size: int = 0
    line_count: int = 0
    sha256: str | None = None
    truncated: bool = False
    writable: bool = False


class AccountContextFileResponse(AccountContextFileMetadata):
    content: str | None = None


class AccountContextFileWriteRequest(BaseModel):
    content: str
    expected_sha256: str | None = None


@router.get("/accounts/{account_id}/files", dependencies=[Depends(verify_token)])
async def list_account_context_files(account_id: str) -> dict:
    account_root = _account_root(account_id)
    return {
        "account_id": account_id,
        "files": [
            _metadata(account_root, file_name).dict()
            for file_name in ACCOUNT_CONTEXT_FILE_ORDER
        ],
    }


@router.get("/accounts/{account_id}/files/{file_name}", dependencies=[Depends(verify_token)])
async def read_account_context_file(account_id: str, file_name: str) -> dict:
    account_root = _account_root(account_id)
    file_name = _validate_file_name(file_name)
    metadata = _metadata(account_root, file_name)
    if not metadata.exists:
        return AccountContextFileResponse(**metadata.dict(), content=None).dict()

    path = _resolve_checked(account_root, file_name)
    content = path.read_text(encoding="utf-8", errors="replace")
    truncated = len(content.encode("utf-8")) > MAX_ACCOUNT_CONTEXT_FILE_BYTES
    if truncated:
        content = content.encode("utf-8")[:MAX_ACCOUNT_CONTEXT_FILE_BYTES].decode("utf-8", errors="replace")
    data = metadata.dict()
    data["truncated"] = truncated
    return AccountContextFileResponse(**data, content=content).dict()


@router.put("/accounts/{account_id}/files/{file_name}", dependencies=[Depends(verify_token)])
async def write_account_context_file(
    account_id: str,
    file_name: str,
    request: AccountContextFileWriteRequest,
) -> dict:
    account_root = _account_root(account_id)
    file_name = _validate_file_name(file_name)
    if file_name not in WRITABLE_CONTEXT_FILES:
        raise HTTPException(status_code=403, detail=f"{file_name} is not writable through this endpoint")

    content = request.content or ""
    raw = content.encode("utf-8")
    if len(raw) > MAX_WRITE_BYTES:
        raise HTTPException(status_code=400, detail=f"{file_name} exceeds {MAX_WRITE_BYTES} bytes")

    path = _resolve_checked(account_root, file_name)
    if request.expected_sha256:
        current = _metadata(account_root, file_name).sha256
        if (current or "") != request.expected_sha256.lower():
            raise HTTPException(status_code=409, detail=f"{file_name} sha256 mismatch")

    account_root.mkdir(parents=True, exist_ok=True)
    path.write_text(_normalize_text(content), encoding="utf-8")
    return _metadata(account_root, file_name).dict()


def _account_root(account_id: str) -> Path:
    if _data_root is None:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Account context route not configured")
    try:
        _validate_account_id(account_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    root = Path(_data_root).resolve() / "accounts" / account_id / "agent"
    if root.exists() and root.is_symlink():
        raise HTTPException(status_code=400, detail="account root must not be a symlink")
    return root


def _validate_file_name(file_name: str) -> str:
    if file_name not in ACCOUNT_CONTEXT_FILE_ORDER:
        raise HTTPException(status_code=400, detail="unsupported account context file")
    return file_name


def _resolve_checked(account_root: Path, file_name: str) -> Path:
    try:
        return _resolve_context_file(account_root, file_name)
    except PathGuardError as exc:
        raise HTTPException(status_code=400, detail=exc.detail) from exc


def _metadata(account_root: Path, file_name: str) -> AccountContextFileMetadata:
    path = _resolve_checked(account_root, file_name)
    writable = file_name in WRITABLE_CONTEXT_FILES
    if not path.is_file():
        return AccountContextFileMetadata(file_name=file_name, exists=False, writable=writable)

    raw = path.read_bytes()
    text = raw.decode("utf-8", errors="replace")
    return AccountContextFileMetadata(
        file_name=file_name,
        exists=True,
        size=len(raw),
        line_count=len(text.splitlines()),
        sha256=hashlib.sha256(raw).hexdigest(),
        truncated=len(raw) > MAX_ACCOUNT_CONTEXT_FILE_BYTES,
        writable=writable,
    )


def _normalize_text(content: str) -> str:
    return content.replace("\r\n", "\n").replace("\r", "\n")
