from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------

class ImageAttachment(BaseModel):
    """A single image attachment sent from the frontend."""

    name: str = Field(..., description="File name (e.g. screenshot-1.png)")
    data: str = Field(..., description="Base64-encoded image data")
    mime_type: str = Field("image/png", description="MIME type (e.g. image/png, image/webp)")


class QueryRequest(BaseModel):
    """Payload for ``POST /api/v1/query``."""

    prompt: str = Field(..., description="The user prompt to send to Claude Code")
    cwd: str | None = Field(None, description="Working directory for the Claude Code session")
    session_id: str | None = Field(None, description="Existing session ID to resume")
    max_turns: int | None = Field(None, description="Maximum number of agentic turns")
    model: str | None = Field(None, description="Model to use (e.g. claude-sonnet-4-20250514)")
    extra_args: dict[str, str | None] | None = Field(
        None, description="Additional CLI args passed to ClaudeCodeOptions.extra_args"
    )
    images: list[ImageAttachment] | None = Field(
        None, description="Base64-encoded images to save to cwd before query"
    )
    # Per-request auth overrides (from bound conversation config)
    api_key: str | None = Field(None, description="Per-request Anthropic API key override")
    auth_token: str | None = Field(None, description="Per-request Anthropic auth token override")
    base_url: str | None = Field(None, description="Per-request Anthropic base URL override")


# ---------------------------------------------------------------------------
# SSE event payload
# ---------------------------------------------------------------------------

class QueryEvent(BaseModel):
    """A single SSE event emitted during a ``/api/v1/query`` stream."""

    type: str = Field(..., description="Event type: assistant_text | tool_use | tool_result | result | error")
    content: str | None = Field(None, description="Text content for assistant_text events")
    tool: str | None = Field(None, description="Tool name for tool_use / tool_result events")
    input: dict[str, Any] | None = Field(None, description="Tool input for tool_use events")
    output: str | None = Field(None, description="Tool output for tool_result events")
    task_id: str = Field(..., description="Unique identifier for this query task")
    session_id: str | None = Field(None, description="Claude Code session ID")
    cost_usd: float | None = Field(None, description="Total cost in USD (result events)")
    duration_ms: int | None = Field(None, description="Total duration in milliseconds (result events)")
    input_tokens: int | None = Field(None, description="Input tokens consumed (result events)")
    output_tokens: int | None = Field(None, description="Output tokens consumed (result events)")
    num_turns: int | None = Field(None, description="Number of agentic turns (result events)")
    model: str | None = Field(None, description="Model used (assistant_text / result events)")
    error: str | None = Field(None, description="Error message (error events)")


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------

class HealthResponse(BaseModel):
    """Response for ``GET /health``."""

    hostname: str
    version: str
    active_tasks: int
    claude_cli_available: bool
    worker_name: str


# ---------------------------------------------------------------------------
# Sessions
# ---------------------------------------------------------------------------

class SessionInfo(BaseModel):
    """Metadata about a tracked Claude Code session."""

    session_id: str
    cwd: str
    created_at: datetime
    updated_at: datetime
    slug: str | None = None
    git_branch: str | None = None


# ---------------------------------------------------------------------------
# Abort
# ---------------------------------------------------------------------------

class AbortResponse(BaseModel):
    """Response for ``POST /api/v1/query/{task_id}/abort``."""

    task_id: str
    status: str


# ---------------------------------------------------------------------------
# Git info
# ---------------------------------------------------------------------------

class GitInfoResponse(BaseModel):
    """Response for ``GET /api/v1/git-info``."""

    path: str
    is_git_repo: bool
    branch: str | None = None
    remote_url: str | None = None
    status: str = "unknown"      # clean | dirty | unknown
    provider: str = "OTHER"      # GITHUB | GITLAB | GITEE | OTHER


# ---------------------------------------------------------------------------
# Skills
# ---------------------------------------------------------------------------

class SkillInfo(BaseModel):
    """A single Claude Code skill discovered from ``.claude/skills/``."""

    name: str
    description: str = ""
    scope: str = "project"       # "project" | "user"


# ---------------------------------------------------------------------------
# Worktree
# ---------------------------------------------------------------------------

class CreateWorktreeRequest(BaseModel):
    """Payload for ``POST /api/v1/worktrees``."""

    repo_path: str = Field(..., description="Absolute path to the main repository")
    branch: str = Field(..., description="Branch to checkout in the worktree")
    worktree_path: str | None = Field(None, description="Optional custom path for the worktree")


class WorktreeInfo(BaseModel):
    """A single git worktree entry."""

    path: str
    branch: str
    is_main: bool


class CreateWorktreeResponse(BaseModel):
    """Response for ``POST /api/v1/worktrees``."""

    path: str
    branch: str


class RemoveWorktreeRequest(BaseModel):
    """Payload for ``DELETE /api/v1/worktrees``."""

    worktree_path: str = Field(..., description="Absolute path to the worktree to remove")
