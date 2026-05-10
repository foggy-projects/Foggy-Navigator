"""Pydantic models for request/response/SSE events."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------


class QueryRequest(BaseModel):
    """Request body for ``POST /api/v1/query``."""

    prompt: str
    session_id: str | None = None
    foggy_session_id: str | None = None
    model: str | None = None
    model_config_id: str | None = None
    context: dict[str, Any] | None = None
    # Hidden runtime data from Navigator Java. Never include this in LLM prompts.
    runtime_context: dict[str, Any] | None = None

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

    # Tool execution tracking. Java relay and frontend use this to merge a
    # tool_use event with the matching tool_result event.
    tool_call_id: str | None = None
    tool_name: str | None = None
    function_id: str | None = None
    args: dict[str, Any] | None = None

    # Result metadata (populated on type="result")
    duration_ms: int | None = None

    # Structured output (populated on type="result")
    structured_output: dict[str, Any] | None = None

    # Approval / suspension metadata (used by skill approval and FSScript pause)
    approval_type: str | None = None
    payload: dict[str, Any] | None = None
    script_run_id: str | None = None
    suspend_id: str | None = None
    reason: str | None = None
    summary: dict[str, Any] | None = None
    timeout_at: str | None = None


# ---------------------------------------------------------------------------
# Frame status enum
# ---------------------------------------------------------------------------


class FrameStatus(str, Enum):
    """Skill Frame lifecycle states."""

    CREATED = "CREATED"
    RUNNING = "RUNNING"
    WAITING_CHILD = "WAITING_CHILD"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


# Terminal states — once entered, no further transitions allowed.
TERMINAL_STATES = frozenset({FrameStatus.COMPLETED, FrameStatus.FAILED, FrameStatus.CANCELLED})

# Legal state transitions matrix.
VALID_TRANSITIONS: dict[FrameStatus, frozenset[FrameStatus]] = {
    FrameStatus.CREATED: frozenset({FrameStatus.RUNNING}),
    FrameStatus.RUNNING: frozenset({
        FrameStatus.WAITING_CHILD,
        FrameStatus.AWAITING_APPROVAL,
        FrameStatus.COMPLETED,
        FrameStatus.FAILED,
        FrameStatus.CANCELLED,
    }),
    FrameStatus.WAITING_CHILD: frozenset({
        FrameStatus.RUNNING,
        FrameStatus.FAILED,
        FrameStatus.CANCELLED,
    }),
    FrameStatus.AWAITING_APPROVAL: frozenset({
        FrameStatus.RUNNING,
        FrameStatus.FAILED,
        FrameStatus.CANCELLED,
    }),
    FrameStatus.COMPLETED: frozenset(),
    FrameStatus.FAILED: frozenset(),
    FrameStatus.CANCELLED: frozenset(),
}


# ---------------------------------------------------------------------------
# Skill Frame state
# ---------------------------------------------------------------------------


class SkillFrameState(BaseModel):
    """Private execution state for a single Skill invocation."""

    frame_id: str
    task_id: str
    skill_id: str
    parent_frame_id: str | None = None
    status: FrameStatus = FrameStatus.CREATED

    input: dict[str, Any] = Field(default_factory=dict)
    private_messages: list[dict[str, Any]] = Field(default_factory=list)
    private_working_state: dict[str, Any] = Field(default_factory=dict)
    tool_calls: list[dict[str, Any]] = Field(default_factory=list)
    child_frame_ids: list[str] = Field(default_factory=list)

    output: dict[str, Any] | None = None
    result_summary: str | None = None
    artifact_refs: list[str] = Field(default_factory=list)
    evidence_refs: list[str] = Field(default_factory=list)
    approval_request: dict[str, Any] | None = None

    started_at: str = ""
    ended_at: str = ""

    # Retry tracking for submit_result rejections
    submit_attempts: int = 0
    max_submit_attempts: int = 3


# ---------------------------------------------------------------------------
# Skill Manifest
# ---------------------------------------------------------------------------


class SkillManifest(BaseModel):
    """Structured definition of a Skill loaded from YAML manifest."""

    id: str
    name: str
    description: str = ""
    markdown_body: str = ""
    input_schema: dict[str, Any] = Field(default_factory=dict)
    output_schema: dict[str, Any] = Field(default_factory=dict)
    allowed_tools: list[str] = Field(default_factory=list)
    approval_tools: list[str] = Field(default_factory=list)
    promote_to_parent: list[str] = Field(default_factory=list)
    business_rules: dict[str, Any] = Field(default_factory=dict)
    subgraph: str | None = None
    # Visibility: 'builtin' skills are internal/test-only and excluded from LLM routing prompt
    visibility: str = "public"  # "builtin" | "public" | "private"
    client_app_id: str | None = None


# ---------------------------------------------------------------------------
# Validation result
# ---------------------------------------------------------------------------


class ValidationResult(BaseModel):
    """Result of output contract validation."""

    ok: bool
    errors: list[str] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Health response
# ---------------------------------------------------------------------------


class HealthResponse(BaseModel):
    """Response model for ``GET /health``."""

    hostname: str
    version: str
    active_tasks: int
    worker_name: str
