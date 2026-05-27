"""Tests for read-only account context file prompt injection."""

from __future__ import annotations

from langgraph_biz_worker.runtime.account_context_files import (
    build_account_context_prompt,
    read_account_context_files,
)
from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy


def test_reads_account_context_files_in_authority_order(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "MEMORY.md").write_text("memory note", encoding="utf-8")
    (account_root / "ACCOUNT_POLICY.md").write_text("policy rule", encoding="utf-8")
    (account_root / "AGENT.md").write_text("agent rule", encoding="utf-8")

    files = read_account_context_files(tmp_path / "data", "acct-001")

    assert [file.name for file in files] == ["ACCOUNT_POLICY.md", "AGENT.md", "MEMORY.md"]
    assert [file.content for file in files] == ["policy rule", "agent rule", "memory note"]


def test_build_prompt_documents_authority_and_permissions(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "ACCOUNT_POLICY.md").write_text("policy rule", encoding="utf-8")
    (account_root / "MEMORY.md").write_text("memory note", encoding="utf-8")

    prompt = build_account_context_prompt(tmp_path / "data", "acct-001")

    assert "## Account Context" in prompt
    assert "`ACCOUNT_POLICY.md` is upstream-controlled and read-only to you." in prompt
    assert "already loaded in this prompt" in prompt
    assert "Content source: `ACCOUNT_POLICY.md`" in prompt
    assert "do not call read_file for `ACCOUNT_POLICY.md` just to confirm it" in prompt
    assert "### ACCOUNT_POLICY.md" in prompt
    assert "policy rule" in prompt
    assert "### MEMORY.md" in prompt
    assert "memory note" in prompt
    assert "AGENT.md" not in [line.strip("# ") for line in prompt.splitlines() if line.startswith("### ")]


def test_invalid_account_id_returns_no_context(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "ACCOUNT_POLICY.md").write_text("policy rule", encoding="utf-8")

    assert read_account_context_files(tmp_path / "data", "../acct-001") == []
    assert build_account_context_prompt(tmp_path / "data", "../acct-001") == ""


def test_long_context_file_is_truncated(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "MEMORY.md").write_text("abcdef", encoding="utf-8")

    files = read_account_context_files(tmp_path / "data", "acct-001", max_bytes_per_file=3)
    prompt = build_account_context_prompt(tmp_path / "data", "acct-001", max_bytes_per_file=3)

    assert files[0].name == "MEMORY.md"
    assert files[0].content == "abc"
    assert files[0].truncated is True
    assert "[truncated after 3 bytes]" in prompt


def test_reads_delegated_workspace_context_from_execution_policy(tmp_path):
    data_root = tmp_path / "data"
    workspace = tmp_path / "delegated" / "user-001"
    agent_root = workspace / "agent"
    agent_root.mkdir(parents=True)
    (agent_root / "ACCOUNT_POLICY.md").write_text("delegated policy", encoding="utf-8")
    (agent_root / "MEMORY.md").write_text("delegated memory", encoding="utf-8")
    policy = ExecutionPolicy.from_context({
        "execution_policy": {
            "workdir": str(workspace),
            "allowed_dirs": [str(workspace)],
        },
    })

    files = read_account_context_files(data_root, None, execution_policy=policy)
    prompt = build_account_context_prompt(data_root, None, execution_policy=policy)

    assert [file.name for file in files] == ["ACCOUNT_POLICY.md", "MEMORY.md"]
    assert "delegated policy" in prompt
    assert "delegated memory" in prompt
