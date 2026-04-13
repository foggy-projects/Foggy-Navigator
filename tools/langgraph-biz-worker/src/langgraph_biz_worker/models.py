"""Pydantic models for request/response/SSE events."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------


class QueryRequest(BaseModel):
    """Request body for ``POST /api/v1/query``."""

    prompt: str
    session_id: str | None = None
    model: str | None = None
    model_config_id: str | None = None
    context: dict[str, Any] | None = None

    # Tracking IDs forwarded by Java side
    task_id: str | None = Field(None, alias="taskId")
    user_id: str | None = Field(None, alias="userId")
    tenant_id: str | None = Field(None, alias="tenantId")


# ---------------------------------------------------------------------------
# SSE event model
# ---------------------------------------------------------------------------


class QueryEvent(BaseModel):
    """A single SSE event emitted during query execution.

    Phase 1 event types:
    - ``system``         : runtime status messages
    - ``assistant_text`` : LLM-generated text chunks
    - ``result``         : final result payload
    - ``error``          : error conditions

    Future phases will add:
    - ``tool_use``, ``tool_result``
    - ``skill_frame_open``, ``skill_frame_close``
    - ``approval_request``
    """

    type: str
    content: str | None = None
    task_id: str = ""
    session_id: str | None = None
    error: str | None = None
    model: str | None = None

    # Skill frame tracking (Phase 2+)
    skill_frame_id: str | None = None
    skill_id: str | None = None

    # Result metadata (populated on type="result")
    duration_ms: int | None = None


# ---------------------------------------------------------------------------
# Health response
# ---------------------------------------------------------------------------


class HealthResponse(BaseModel):
    """Response model for ``GET /health``."""

    hostname: str
    version: str
    active_tasks: int
    worker_name: str
