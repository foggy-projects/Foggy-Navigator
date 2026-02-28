"""Init-directory endpoint for creating directories and writing files."""

from __future__ import annotations

import logging
import os
from typing import Dict, List

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel

from ..auth import verify_token

logger = logging.getLogger(__name__)

router = APIRouter(tags=["init-directory"])


class InitDirectoryRequest(BaseModel):
    path: str
    files: Dict[str, str]


class InitDirectoryResponse(BaseModel):
    path: str
    files_created: List[str]


@router.post(
    "/api/v1/init-directory",
    response_model=InitDirectoryResponse,
    dependencies=[Depends(verify_token)],
)
async def init_directory(request: InitDirectoryRequest) -> InitDirectoryResponse:
    """Create a directory and write files into it.

    Used for assistant working directory initialization (CLAUDE.md, settings.json, etc.).
    """
    try:
        expanded_path = os.path.expanduser(request.path)
        os.makedirs(expanded_path, exist_ok=True)

        created_files: list[str] = []
        for file_path, content in request.files.items():
            full_path = os.path.join(expanded_path, file_path)
            os.makedirs(os.path.dirname(full_path), exist_ok=True)
            with open(full_path, "w", encoding="utf-8") as f:
                f.write(content)
            created_files.append(file_path)

        logger.info("Initialized directory %s with %d files", expanded_path, len(created_files))
        return InitDirectoryResponse(path=expanded_path, files_created=created_files)

    except PermissionError as e:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Permission denied: {e}",
        )
    except Exception as e:
        logger.error("Failed to initialize directory %s: %s", request.path, e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to initialize directory: {e}",
        )
