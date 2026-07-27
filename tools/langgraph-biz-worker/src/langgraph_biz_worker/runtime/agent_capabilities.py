"""Capability declarations for LangGraph Biz Worker Agent delegation."""

from __future__ import annotations

from ..models import (
    AgentDelegationCapabilities,
    AgentDelegationToolCapability,
    WorkerCapabilities,
)


def build_worker_capabilities(
    max_agent_nesting_depth: int,
    *,
    auth_required: bool = False,
    identity_configured: bool = False,
) -> WorkerCapabilities:
    """Build the online capability contract exposed by ``GET /health``."""
    return WorkerCapabilities(
        agent_delegation=AgentDelegationCapabilities(
            max_agent_nesting_depth=max_agent_nesting_depth,
            root_agent_delegation_allowed=max_agent_nesting_depth > 0,
            nested_agent_delegation_allowed=max_agent_nesting_depth > 1,
            nested_agent_authorization_gates=[
                "agent_manifest.allowed_tools",
                "execution_policy.allowed_tools",
                "runtime.max_agent_nesting_depth",
            ],
            tools={
                "spawn_agent": AgentDelegationToolCapability(
                    supported=True,
                    tool_name="invoke_business_agent",
                    mode="opens_child_agent_frame_and_runs_until_result_or_recoverable_interruption",
                ),
                "wait_agent": AgentDelegationToolCapability(
                    supported=True,
                    tool_name="invoke_business_agent",
                    mode="implicit_synchronous_wait_inside_spawn_call",
                ),
                "send_input": AgentDelegationToolCapability(
                    supported=False,
                    mode="not_exposed_as_a_separate_child_agent_tool",
                ),
                "close_agent": AgentDelegationToolCapability(
                    supported=True,
                    tool_name="submit_frame_result",
                    mode="child_agent_controlled_completion_or_handoff_to_parent",
                ),
                "resume_agent": AgentDelegationToolCapability(
                    supported=True,
                    tool_name="resume_recoverable_child_skill",
                    mode="parent_resumes_recoverable_child_frame",
                ),
            },
        ),
        completion_readiness={
            "supported": True,
            "route": "/api/v1/tasks/{taskId}/completion-readiness",
            "schema": "LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1",
            "content_free": True,
            "terminal_statuses": ["COMPLETED", "FAILED", "CANCELLED"],
            "auth_required": auth_required,
            "identity_configured": identity_configured,
        },
    )
