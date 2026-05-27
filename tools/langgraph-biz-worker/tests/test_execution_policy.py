from __future__ import annotations

import pytest

from langgraph_biz_worker.runtime.execution_policy import (
    ExecutionPolicy,
    copy_execution_policy_from_context,
    strip_execution_policy_context,
)


def test_execution_policy_normalizes_aliases(tmp_path):
    allowed_root = tmp_path / "workspace"
    workdir = allowed_root / "project"
    workdir.mkdir(parents=True)

    policy = ExecutionPolicy.from_context({
        "executionPolicy": {
            "workingDirectory": str(workdir),
            "allowedDirectories": [str(allowed_root)],
            "allowedTools": "query_order; update_order_status",
        }
    })

    assert policy.configured is True
    assert policy.workdir == workdir.resolve()
    assert policy.allowed_dirs == (allowed_root.resolve(),)
    assert policy.allowed_tools == frozenset({"query_order", "update_order_status"})
    assert policy.allows_tool("query_order") is True
    assert policy.allows_tool("delete_order") is False


def test_execution_policy_normalizes_workspace_governance_payloads(tmp_path):
    workdir = tmp_path / "project"
    workdir.mkdir()

    policy = ExecutionPolicy.from_context({
        "execution_policy": {
            "workdir": str(workdir),
            "readOnly": "true",
            "quotaPolicy": {"maxBytes": 128},
            "retentionPolicy": {"days": 7},
            "concurrencyPolicy": {"maxWriters": 1},
        }
    })

    assert policy.read_only is True
    assert policy.quota_policy == {"maxBytes": 128}
    assert policy.retention_policy == {"days": 7}
    assert policy.concurrency_policy == {"maxWriters": 1}
    assert policy.max_write_bytes(1024) == 128
    assert policy.to_context()["execution_policy"]["read_only"] is True
    assert policy.to_context()["execution_policy"]["quota_policy"] == {"maxBytes": 128}


def test_execution_policy_defaults_allowed_dirs_to_workdir(tmp_path):
    workdir = tmp_path / "project"
    workdir.mkdir()

    policy = ExecutionPolicy.from_context({"execution_policy": {"workdir": str(workdir)}})

    assert policy.workdir == workdir.resolve()
    assert policy.allowed_dirs == (workdir.resolve(),)
    assert policy.resolve_path("notes.txt") == (workdir / "notes.txt").resolve()
    with pytest.raises(ValueError, match="PATH_NOT_AUTHORIZED"):
        policy.resolve_path("../escape.txt")


def test_execution_policy_rejects_workdir_outside_allowed_dirs(tmp_path):
    allowed_root = tmp_path / "allowed"
    workdir = tmp_path / "outside"
    allowed_root.mkdir()
    workdir.mkdir()

    with pytest.raises(ValueError, match="WORKDIR_NOT_AUTHORIZED"):
        ExecutionPolicy.from_context({
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_dirs": [str(allowed_root)],
            }
        })


def test_execution_policy_rejects_empty_allowed_dirs_with_workdir(tmp_path):
    workdir = tmp_path / "project"
    workdir.mkdir()

    with pytest.raises(ValueError, match="allowed_dirs must include workdir"):
        ExecutionPolicy.from_context({
            "execution_policy": {
                "workdir": str(workdir),
                "allowed_dirs": [],
            }
        })


def test_execution_policy_helpers_copy_and_strip_visible_context(tmp_path):
    workdir = tmp_path / "project"
    workdir.mkdir()
    visible = {
        "order_id": "O-1",
        "execution_policy": {
            "workdir": str(workdir),
            "allowed_tools": ["query_order"],
        },
    }

    runtime_context = copy_execution_policy_from_context({"task_id": "t1"}, visible)

    assert runtime_context["task_id"] == "t1"
    assert runtime_context["execution_policy"] == visible["execution_policy"]
    assert strip_execution_policy_context(visible) == {"order_id": "O-1"}


def test_execution_policy_allows_skill_discovery_when_skill_material_tool_allowed():
    policy = ExecutionPolicy(
        allowed_tools=frozenset({"invoke_business_skill"}),
        configured=True,
    )

    assert policy.allows_tool("invoke_business_skill") is True
    assert policy.allows_tool("invoke_business_agent") is True
    assert policy.allows_tool("list_skill_resources") is True
    assert policy.allows_tool("read_skill_resource") is True
    assert policy.allows_tool("invoke_business_function") is False


def test_execution_policy_allows_skill_discovery_when_agent_tool_allowed():
    policy = ExecutionPolicy(
        allowed_tools=frozenset({"invoke_business_agent"}),
        configured=True,
    )

    assert policy.allows_tool("invoke_business_agent") is True
    assert policy.allows_tool("list_skill_resources") is True
    assert policy.allows_tool("read_skill_resource") is True
    assert policy.allows_tool("invoke_business_skill") is False


def test_execution_policy_does_not_allow_skill_discovery_for_unrelated_tools():
    policy = ExecutionPolicy(
        allowed_tools=frozenset({"invoke_business_function"}),
        configured=True,
    )

    assert policy.allows_tool("invoke_business_function") is True
    assert policy.allows_tool("list_skill_resources") is False
    assert policy.allows_tool("read_skill_resource") is False
