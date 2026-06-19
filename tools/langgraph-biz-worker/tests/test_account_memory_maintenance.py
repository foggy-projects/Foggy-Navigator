from __future__ import annotations

from langgraph_biz_worker.runtime.account_memory_maintenance import maintain_memory_file


def _agent_root(data_root, account_id: str = "acct-001"):
    root = data_root / "accounts" / account_id / "agent"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _write_allowing_policy(agent_root) -> None:
    (agent_root / "ACCOUNT_POLICY.md").write_text(
        "MEMORY.md may be maintained autonomously for stable account preferences.\n",
        encoding="utf-8",
    )


def test_maintain_memory_file_adds_safe_observation_when_policy_allows(tmp_path):
    data_root = tmp_path / "data"
    agent_root = _agent_root(data_root)
    _write_allowing_policy(agent_root)

    result = maintain_memory_file(
        data_root,
        "acct-001",
        task_id="task_memory_001",
        observations=("User prefers concise status updates.",),
    )

    assert result["ok"] is True
    assert result["changed"] is True
    assert result["added_count"] == 1
    memory = (agent_root / "MEMORY.md").read_text(encoding="utf-8")
    assert "## Background Maintained Notes" in memory
    assert "- User prefers concise status updates." in memory
    assert result["audit_records"][-1]["operation"] == "write_file"
    assert result["audit_records"][-1]["relative_path"] == "agent/MEMORY.md"


def test_maintain_memory_file_fails_closed_without_policy(tmp_path):
    data_root = tmp_path / "data"

    result = maintain_memory_file(
        data_root,
        "acct-001",
        task_id="task_memory_002",
        observations=("User prefers concise status updates.",),
    )

    assert result["ok"] is True
    assert result["changed"] is False
    assert result["reason"] == "policy_missing"
    assert not (data_root / "accounts" / "acct-001" / "agent" / "MEMORY.md").exists()


def test_maintain_memory_file_respects_policy_denial(tmp_path):
    data_root = tmp_path / "data"
    agent_root = _agent_root(data_root)
    (agent_root / "ACCOUNT_POLICY.md").write_text("MEMORY.md is read-only.\n", encoding="utf-8")

    result = maintain_memory_file(
        data_root,
        "acct-001",
        task_id="task_memory_003",
        observations=("User prefers concise status updates.",),
    )

    assert result["ok"] is True
    assert result["changed"] is False
    assert result["reason"] == "policy_denied"
    assert not (agent_root / "MEMORY.md").exists()


def test_maintain_memory_file_rejects_sensitive_observations(tmp_path):
    data_root = tmp_path / "data"
    agent_root = _agent_root(data_root)
    _write_allowing_policy(agent_root)

    result = maintain_memory_file(
        data_root,
        "acct-001",
        task_id="task_memory_004",
        observations=("The API key is abc123.",),
    )

    assert result["ok"] is True
    assert result["changed"] is False
    assert result["reason"] == "no_safe_candidates"
    assert result["rejected_count"] == 1
    assert not (agent_root / "MEMORY.md").exists()


def test_maintain_memory_file_deduplicates_existing_memory(tmp_path):
    data_root = tmp_path / "data"
    agent_root = _agent_root(data_root)
    _write_allowing_policy(agent_root)
    (agent_root / "MEMORY.md").write_text(
        "# Memory\n\n## Background Maintained Notes\n- User prefers concise status updates.\n",
        encoding="utf-8",
    )

    result = maintain_memory_file(
        data_root,
        "acct-001",
        task_id="task_memory_005",
        observations=("user prefers concise status updates.",),
    )

    assert result["ok"] is True
    assert result["changed"] is False
    assert result["reason"] == "duplicate"
