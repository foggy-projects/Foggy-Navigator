from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from langgraph_biz_worker.routes import account_context


def _client(tmp_path) -> TestClient:
    account_context.configure(tmp_path / "data")
    app = FastAPI()
    app.include_router(account_context.router)
    return TestClient(app)


def test_lists_fixed_context_files(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "ACCOUNT_POLICY.md").write_text("policy\n", encoding="utf-8")

    response = _client(tmp_path).get("/api/v1/account-context/accounts/acct-001/files")

    assert response.status_code == 200
    body = response.json()
    assert body["account_id"] == "acct-001"
    assert [item["file_name"] for item in body["files"]] == [
        "ACCOUNT_POLICY.md",
        "AGENT.md",
        "MEMORY.md",
    ]
    assert body["files"][0]["exists"] is True
    assert body["files"][0]["writable"] is True
    assert body["files"][1]["writable"] is False


def test_reads_context_file_without_exposing_paths(tmp_path):
    account_root = tmp_path / "data" / "accounts" / "acct-001" / "agent"
    account_root.mkdir(parents=True)
    (account_root / "MEMORY.md").write_text("remember this\n", encoding="utf-8")

    response = _client(tmp_path).get("/api/v1/account-context/accounts/acct-001/files/MEMORY.md")

    assert response.status_code == 200
    body = response.json()
    assert body["file_name"] == "MEMORY.md"
    assert body["content"] == "remember this\n"
    assert "path" not in body


def test_write_policy_is_guarded_by_expected_sha(tmp_path):
    client = _client(tmp_path)
    response = client.put(
        "/api/v1/account-context/accounts/acct-001/files/ACCOUNT_POLICY.md",
        json={"content": "policy v1\n"},
    )
    assert response.status_code == 200
    sha = response.json()["sha256"]

    mismatch = client.put(
        "/api/v1/account-context/accounts/acct-001/files/ACCOUNT_POLICY.md",
        json={"content": "policy v2\n", "expected_sha256": "bad"},
    )
    assert mismatch.status_code == 409

    updated = client.put(
        "/api/v1/account-context/accounts/acct-001/files/ACCOUNT_POLICY.md",
        json={"content": "policy v2\n", "expected_sha256": sha},
    )
    assert updated.status_code == 200
    assert updated.json()["sha256"] != sha


def test_only_account_policy_is_writable(tmp_path):
    response = _client(tmp_path).put(
        "/api/v1/account-context/accounts/acct-001/files/AGENT.md",
        json={"content": "agent rule\n"},
    )

    assert response.status_code == 403
