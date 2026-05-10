"""Tests for Skill Git sync and skills routes."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from langgraph_biz_worker.runtime.skill_git_sync import (
    SyncResult,
    _discover_skills,
    sync_public_skills,
)


# ---------------------------------------------------------------------------
# Unit tests for skill_git_sync
# ---------------------------------------------------------------------------


class TestDiscoverSkills:
    def test_finds_skill_directories(self, tmp_path):
        (tmp_path / "skill-a").mkdir()
        (tmp_path / "skill-a" / "SKILL.md").write_text("---\nname: a\n---\n")
        (tmp_path / "skill-b").mkdir()
        (tmp_path / "skill-b" / "SKILL.md").write_text("---\nname: b\n---\n")
        (tmp_path / "not-a-skill").mkdir()  # no SKILL.md
        (tmp_path / "README.md").write_text("ignore me")

        skills = _discover_skills(tmp_path)
        assert skills == ["skill-a", "skill-b"]

    def test_empty_dir(self, tmp_path):
        assert _discover_skills(tmp_path) == []

    def test_nonexistent_dir(self, tmp_path):
        assert _discover_skills(tmp_path / "nope") == []


class TestSyncPublicSkills:
    def test_no_repo_returns_failure(self, tmp_path):
        result = sync_public_skills("", tmp_path / "public")
        assert not result.success
        assert not result.success
        assert "skill_git_repo" in result.message

    @patch("langgraph_biz_worker.runtime.skill_git_sync._run_git")
    def test_clone_on_first_run(self, mock_git, tmp_path):
        target = tmp_path / "public"
        mock_git.return_value = MagicMock(returncode=0, stderr="")

        # Create a fake skill after "clone"
        def side_effect(args, **kwargs):
            if args[0] == "clone":
                target.mkdir(parents=True, exist_ok=True)
                (target / "my-skill").mkdir()
                (target / "my-skill" / "SKILL.md").write_text("---\nname: my_skill\n---\n")
            return MagicMock(returncode=0, stderr="")

        mock_git.side_effect = side_effect

        result = sync_public_skills("https://gitlab.example.com/repo.git", target)
        assert result.success
        assert "my-skill" in result.skills_found

    @patch("langgraph_biz_worker.runtime.skill_git_sync._run_git")
    def test_pull_when_already_cloned(self, mock_git, tmp_path):
        target = tmp_path / "public"
        target.mkdir(parents=True)
        (target / ".git").mkdir()  # simulate existing repo
        (target / "existing-skill").mkdir()
        (target / "existing-skill" / "SKILL.md").write_text("---\nname: existing\n---\n")

        mock_git.return_value = MagicMock(returncode=0, stderr="")

        result = sync_public_skills("https://gitlab.example.com/repo.git", target)
        assert result.success
        # Should have called fetch then reset
        calls = [c[0][0] for c in mock_git.call_args_list]
        assert calls == [["fetch", "origin", "main"], ["reset", "--hard", "origin/main"]]

    @patch("langgraph_biz_worker.runtime.skill_git_sync._run_git")
    def test_clone_failure(self, mock_git, tmp_path):
        mock_git.return_value = MagicMock(returncode=128, stderr="fatal: repo not found")
        result = sync_public_skills("https://bad.url/repo.git", tmp_path / "public")
        assert not result.success
        assert "clone failed" in result.message

    def test_git_not_found(self, tmp_path):
        with patch("langgraph_biz_worker.runtime.skill_git_sync._run_git", side_effect=FileNotFoundError):
            result = sync_public_skills("https://gitlab.example.com/repo.git", tmp_path / "public")
        assert not result.success
        assert "git command not found" in result.message


# ---------------------------------------------------------------------------
# HTTP endpoint tests
# ---------------------------------------------------------------------------


@pytest.fixture
async def client():
    from langgraph_biz_worker.main import app
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


class TestWebhookEndpoint:
    @patch("langgraph_biz_worker.routes.skills.sync_public_skills")
    async def test_webhook_triggers_sync(self, mock_sync, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        mock_sync.return_value = SyncResult(success=True, message="ok", skills_found=["a"])
        skills_module.configure(tmp_path)

        with patch("langgraph_biz_worker.routes.skills.settings") as mock_settings:
            mock_settings.skill_git_repo = "https://repo"
            mock_settings.skill_git_branch = "main"
            mock_settings.skill_git_token = ""
            mock_settings.skill_webhook_secret = ""

            resp = await client.post("/api/v1/skills/webhook", json={
                "ref": "refs/heads/main",
            })

        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "synced"

    @patch("langgraph_biz_worker.routes.skills.settings")
    async def test_webhook_ignores_wrong_branch(self, mock_settings, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        skills_module.configure(tmp_path)
        mock_settings.skill_git_repo = "https://repo"
        mock_settings.skill_git_branch = "main"
        mock_settings.skill_webhook_secret = ""

        resp = await client.post("/api/v1/skills/webhook", json={
            "ref": "refs/heads/develop",
        })
        assert resp.status_code == 200
        assert resp.json()["status"] == "ignored"

    @patch("langgraph_biz_worker.routes.skills.settings")
    async def test_webhook_rejects_bad_token(self, mock_settings, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        skills_module.configure(tmp_path)
        mock_settings.skill_webhook_secret = "correct-secret"
        mock_settings.skill_git_repo = "https://repo"

        resp = await client.post(
            "/api/v1/skills/webhook",
            json={"ref": "refs/heads/main"},
            headers={"X-Gitlab-Token": "wrong-secret"},
        )
        assert resp.status_code == 403


class TestMaterializeEndpoint:
    async def test_materialize_writes_public_app_skill_bundle_resources(self, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        skills_root = tmp_path / "skills"
        skills_module.configure(skills_root)

        resp = await client.post("/api/v1/skills/materialize", json={
            "skill_id": "foggy-query-agent",
            "client_app_id": "app_01",
            "name": "foggy-query-agent",
            "markdown_body": "Use references for complex payloads.",
            "resources": [
                {
                    "path": "references/functions/queryModel.md",
                    "content": "# queryModel\nUse model and payload.",
                },
                {
                    "path": "assets/schema.json",
                    "content": "{\"type\":\"object\"}",
                },
            ],
        })

        assert resp.status_code == 200
        target = skills_root / "public" / "apps" / "app_01" / "foggy-query-agent"
        assert (target / "SKILL.md").is_file()
        assert (target / "references" / "functions" / "queryModel.md").read_text(encoding="utf-8") == "# queryModel\nUse model and payload."
        assert (target / "assets" / "schema.json").read_text(encoding="utf-8") == "{\"type\":\"object\"}"

    async def test_materialize_replaces_stale_bundle_resources(self, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        skills_root = tmp_path / "skills"
        target = skills_root / "public" / "apps" / "app_01" / "skill_01"
        (target / "references").mkdir(parents=True)
        (target / "references" / "old.md").write_text("old", encoding="utf-8")
        skills_module.configure(skills_root)

        resp = await client.post("/api/v1/skills/materialize", json={
            "skill_id": "skill_01",
            "client_app_id": "app_01",
            "markdown_body": "fresh",
            "resources": [
                {"path": "references/new.md", "content": "new"},
            ],
        })

        assert resp.status_code == 200
        assert not (target / "references" / "old.md").exists()
        assert (target / "references" / "new.md").read_text(encoding="utf-8") == "new"

    async def test_materialize_rejects_resource_path_escape(self, client, tmp_path):
        from langgraph_biz_worker.routes import skills as skills_module
        skills_module.configure(tmp_path / "skills")

        resp = await client.post("/api/v1/skills/materialize", json={
            "skill_id": "skill_01",
            "client_app_id": "app_01",
            "resources": [
                {"path": "../secret.md", "content": "nope"},
            ],
        })

        assert resp.status_code == 400


class TestAccountSkillLoading:
    """Test that SkillRegistry loads account-private skills."""

    def test_account_skills_override_builtin(self, tmp_path):
        from langgraph_biz_worker.runtime.skill_registry import SkillRegistry

        skills_root = tmp_path / "skills"
        data_root = tmp_path / "data"

        # Create builtin skill
        builtin = skills_root / "builtin" / "my-skill"
        builtin.mkdir(parents=True)
        (builtin / "SKILL.md").write_text("---\nname: my_skill\ndescription: builtin version\n---\n")

        # Create account skill with same name
        account = data_root / "accounts" / "user-001" / "skills" / "my-skill"
        account.mkdir(parents=True)
        (account / "SKILL.md").write_text("---\nname: my_skill\ndescription: account version\n---\n")

        registry = SkillRegistry(skills_root=skills_root)
        registry.load(account_id="user-001")

        m = registry.get_manifest("my_skill")
        assert m is not None
        assert m.description == "account version"

    def test_no_account_id_skips_account_layer(self, tmp_path):
        from langgraph_biz_worker.runtime.skill_registry import SkillRegistry

        skills_root = tmp_path / "skills"
        builtin = skills_root / "builtin" / "my-skill"
        builtin.mkdir(parents=True)
        (builtin / "SKILL.md").write_text("---\nname: my_skill\ndescription: builtin\n---\n")

        registry = SkillRegistry(skills_root=skills_root)
        registry.load()  # no account_id

        m = registry.get_manifest("my_skill")
        assert m is not None
        assert m.description == "builtin"

    def test_rejects_path_traversal_account_id(self, tmp_path):
        from langgraph_biz_worker.runtime.skill_registry import SkillRegistry

        registry = SkillRegistry(skills_root=tmp_path / "skills", data_root=tmp_path / "data")

        with pytest.raises(ValueError):
            registry.load(account_id="../other-user")
