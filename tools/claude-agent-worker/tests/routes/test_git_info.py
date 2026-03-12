"""Unit tests for routes/git_info.py — provider inference and endpoint logic."""

from __future__ import annotations

from agent_worker.routes.git_info import _infer_provider


class TestInferProvider:
    """Detect git provider from remote URL."""

    def test_github_ssh(self):
        assert _infer_provider("git@github.com:user/repo.git") == "GITHUB"

    def test_github_https(self):
        assert _infer_provider("https://github.com/user/repo.git") == "GITHUB"

    def test_gitlab_https(self):
        assert _infer_provider("https://gitlab.com/group/project.git") == "GITLAB"

    def test_gitlab_self_hosted(self):
        assert _infer_provider("https://gitlab.foggysource.com/team/repo.git") == "GITLAB"

    def test_gitee(self):
        assert _infer_provider("https://gitee.com/user/repo.git") == "GITEE"

    def test_other_provider(self):
        assert _infer_provider("https://bitbucket.org/user/repo.git") == "OTHER"

    def test_none_url(self):
        assert _infer_provider(None) == "OTHER"

    def test_empty_string(self):
        assert _infer_provider("") == "OTHER"

    def test_case_insensitive(self):
        assert _infer_provider("https://GITHUB.COM/user/repo") == "GITHUB"
