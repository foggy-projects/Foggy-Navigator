"""Tests for Worker online capability declarations."""

from langgraph_biz_worker.runtime.agent_capabilities import build_worker_capabilities


def test_agent_delegation_capabilities_default_depth_blocks_nested_agent():
    capabilities = build_worker_capabilities(max_agent_nesting_depth=1)
    agent = capabilities.agent_delegation

    assert agent.contract_version == "agent-delegation.v1"
    assert agent.max_agent_nesting_depth == 1
    assert agent.root_agent_depth == 0
    assert agent.root_agent_delegation_allowed is True
    assert agent.nested_agent_delegation_allowed is False
    assert agent.child_agent_inherits_parent_tools is False
    assert agent.explicit_nested_agent_authorization_required is True
    assert agent.nested_agent_authorization_gates == [
        "agent_manifest.allowed_tools",
        "execution_policy.allowed_tools",
        "runtime.max_agent_nesting_depth",
    ]
    assert agent.tools["spawn_agent"].supported is True
    assert agent.tools["spawn_agent"].tool_name == "invoke_business_agent"
    assert agent.tools["wait_agent"].mode == "implicit_synchronous_wait_inside_spawn_call"
    assert agent.tools["send_input"].supported is False
    assert agent.tools["close_agent"].tool_name == "submit_frame_result"
    assert agent.tools["resume_agent"].tool_name == "resume_recoverable_child_skill"


def test_agent_delegation_capabilities_depth_two_allows_nested_agent():
    capabilities = build_worker_capabilities(max_agent_nesting_depth=2)
    agent = capabilities.agent_delegation

    assert agent.max_agent_nesting_depth == 2
    assert agent.root_agent_delegation_allowed is True
    assert agent.nested_agent_delegation_allowed is True
