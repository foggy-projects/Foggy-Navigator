"""Tests for account workspace resolution."""

from __future__ import annotations

from pathlib import Path

import pytest

from langgraph_biz_worker.runtime.account_workspace import (
    AccountWorkspaceResolver,
    DELEGATED_STORAGE_ACCOUNT_ID,
)
from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy


def test_resolves_managed_account_workspace(tmp_path: Path):
    resolver = AccountWorkspaceResolver(tmp_path / "data")

    workspace = resolver.resolve("acct-001")

    assert workspace is not None
    assert workspace.mode == "managed"
    assert workspace.source == "default"
    assert workspace.account_id == "acct-001"
    assert workspace.storage_account_id == "acct-001"
    assert workspace.root == (tmp_path / "data" / "accounts" / "acct-001").resolve()
    assert workspace.skills_root == workspace.root / "skills"
    assert workspace.artifacts_root == workspace.root / "artifacts"


def test_resolves_delegated_workspace_without_account_id(tmp_path: Path):
    workspace_root = tmp_path / "delegated" / "user-001"
    workspace_root.mkdir(parents=True)
    policy = ExecutionPolicy.from_context({
        "execution_policy": {
            "workdir": str(workspace_root),
            "allowed_dirs": [str(workspace_root)],
        },
    })
    resolver = AccountWorkspaceResolver(tmp_path / "data")

    workspace = resolver.resolve(None, execution_policy=policy)

    assert workspace is not None
    assert workspace.mode == "delegated"
    assert workspace.source == "execution_policy"
    assert workspace.account_id is None
    assert workspace.storage_account_id == DELEGATED_STORAGE_ACCOUNT_ID
    assert workspace.root == workspace_root.resolve()


def test_rejects_invalid_managed_account_id(tmp_path: Path):
    resolver = AccountWorkspaceResolver(tmp_path / "data")

    with pytest.raises(ValueError):
        resolver.resolve("../acct-001")
