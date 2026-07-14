from __future__ import annotations

import subprocess

from langgraph_biz_worker.runtime import command_tool
from langgraph_biz_worker.runtime.command_tool import command_tool_available, run_command_tool
from langgraph_biz_worker.runtime.execution_policy import ExecutionPolicy


def _policy(tmp_path, *, allowed_tools: list[str] | None = None) -> ExecutionPolicy:
    workdir = tmp_path / "workspace"
    workdir.mkdir(exist_ok=True)
    payload: dict[str, object] = {"workdir": str(workdir)}
    if allowed_tools is not None:
        payload["allowed_tools"] = allowed_tools
    return ExecutionPolicy.from_context({"execution_policy": payload})


def test_command_tool_available_requires_linux_setting_and_workspace(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["read_file"])

    monkeypatch.setattr(command_tool.settings, "enable_command", False)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    assert command_tool_available(policy) is False

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    assert command_tool_available(policy) is True

    monkeypatch.setattr(command_tool.platform, "system", lambda: "Windows")
    assert command_tool_available(policy) is False

    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    assert command_tool_available(_policy(tmp_path, allowed_tools=None)) is True
    assert command_tool_available(ExecutionPolicy.from_context({"execution_policy": {"allowed_tools": ["command"]}})) is False


def test_run_command_tool_executes_subprocess_in_policy_workdir(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])
    captured: dict[str, object] = {}

    def fake_run(argv, **kwargs):
        captured["argv"] = argv
        captured["kwargs"] = kwargs
        return subprocess.CompletedProcess(argv, 0, stdout="ok\n", stderr="")

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    monkeypatch.setattr(command_tool.subprocess, "run", fake_run)

    result = run_command_tool({"command": "git status --short"}, policy)

    assert result["ok"] is True
    assert result["exit_code"] == 0
    assert result["stdout"] == "ok\n"
    assert captured["argv"] == ["/bin/bash", "--noprofile", "--norc", "-c", "git status --short"]
    assert captured["kwargs"]["cwd"] == str(policy.workdir)
    assert captured["kwargs"]["stdin"] is command_tool.subprocess.DEVNULL
    assert captured["kwargs"]["umask"] == command_tool.COMMAND_UMASK


def test_run_command_tool_drops_root_to_policy_workdir_owner(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])
    captured: dict[str, object] = {}
    expected_env = {
        "PATH": "/usr/bin",
        "HOME": "/home/navigator",
        "USER": "navigator",
        "LOGNAME": "navigator",
    }

    def fake_run(argv, **kwargs):
        captured["argv"] = argv
        captured["kwargs"] = kwargs
        return subprocess.CompletedProcess(argv, 0, stdout="ok\n", stderr="")

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    monkeypatch.setattr(command_tool.os, "geteuid", lambda: 0, raising=False)
    monkeypatch.setattr(command_tool, "_workspace_owner_ids", lambda workdir: (1001, 1002))
    monkeypatch.setattr(command_tool, "_supplementary_groups_for_uid", lambda uid, gid: (1002, 1003))
    monkeypatch.setattr(command_tool, "_command_env_for_uid", lambda uid: expected_env)
    monkeypatch.setattr(command_tool.subprocess, "run", fake_run)

    result = run_command_tool({"command": "mkdir -p tasks/example"}, policy)

    assert result["ok"] is True
    assert captured["argv"] == ["/bin/bash", "--noprofile", "--norc", "-c", "mkdir -p tasks/example"]
    assert captured["kwargs"]["user"] == 1001
    assert captured["kwargs"]["group"] == 1002
    assert captured["kwargs"]["extra_groups"] == (1002, 1003)
    assert captured["kwargs"]["env"] is expected_env
    assert captured["kwargs"]["umask"] == command_tool.COMMAND_UMASK


def test_run_command_tool_keeps_current_identity_when_not_root(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])
    captured: dict[str, object] = {}

    def fake_run(argv, **kwargs):
        captured["kwargs"] = kwargs
        return subprocess.CompletedProcess(argv, 0, stdout="ok\n", stderr="")

    def fail_owner_lookup(workdir):
        raise AssertionError("non-root command should not inspect workspace owner")

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    monkeypatch.setattr(command_tool.os, "geteuid", lambda: 1001, raising=False)
    monkeypatch.setattr(command_tool, "_workspace_owner_ids", fail_owner_lookup)
    monkeypatch.setattr(command_tool.subprocess, "run", fake_run)
    secret_env = {
        "ANTHROPIC_API_KEY": "llm-secret",
        "AWS_ACCESS_KEY_ID": "aws-access-key",
        "AWS_SECRET_ACCESS_KEY": "aws-secret-key",
        "AWS_SESSION_TOKEN": "aws-session-token",
        "BIZ_WORKER_LLM_API_KEY": "provider-secret",
        "BIZ_WORKER_NAVIGATOR_WORKER_CREDENTIAL": "bwc_secret",
        "BIZ_WORKER_NAVIGATOR_WORKER_ID": "worker-a",
        "BIZ_WORKER_SKILL_GIT_TOKEN": "skill-secret",
        "BIZ_WORKER_SKILL_WEBHOOK_SECRET": "webhook-secret",
        "BIZ_WORKER_WORKER_TOKEN": "inbound-secret",
        "DATABASE_URL": "postgresql://user:secret@db/runtime",
        "DB_PASSWORD": "db-secret",
        "OPENAI_API_KEY": "openai-secret",
        "PGPASSWORD": "postgres-secret",
    }
    for key, value in secret_env.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setenv("PATH", "/usr/local/bin:/usr/bin")
    monkeypatch.setenv("JAVA_HOME", "/opt/jdk")
    monkeypatch.setenv("PNPM_HOME", "/opt/pnpm")
    monkeypatch.setenv("LANG", "C.UTF-8")

    result = run_command_tool({"command": "touch report.md"}, policy)

    assert result["ok"] is True
    assert "user" not in captured["kwargs"]
    assert "group" not in captured["kwargs"]
    assert "extra_groups" not in captured["kwargs"]
    child_env = captured["kwargs"]["env"]
    assert all(key not in child_env for key in secret_env)
    assert child_env["PATH"] == "/usr/local/bin:/usr/bin"
    assert child_env["JAVA_HOME"] == "/opt/jdk"
    assert child_env["PNPM_HOME"] == "/opt/pnpm"
    assert child_env["LANG"] == "C.UTF-8"
    assert captured["kwargs"]["umask"] == command_tool.COMMAND_UMASK


def test_run_command_tool_executes_without_command_allowlist(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["read_file"])
    captured: dict[str, object] = {}

    def fake_run(argv, **kwargs):
        captured["argv"] = argv
        return subprocess.CompletedProcess(argv, 0, stdout="ok\n", stderr="")

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    monkeypatch.setattr(command_tool.subprocess, "run", fake_run)

    result = run_command_tool({"command": "git status --short"}, policy)

    assert result["ok"] is True
    assert captured["argv"] == ["/bin/bash", "--noprofile", "--norc", "-c", "git status --short"]


def test_command_tool_rejects_read_only_workspace(tmp_path, monkeypatch):
    workdir = tmp_path / "workspace"
    workdir.mkdir(exist_ok=True)
    policy = ExecutionPolicy.from_context({
        "execution_policy": {
            "workdir": str(workdir),
            "allowed_tools": ["command"],
            "read_only": True,
        },
    })
    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")

    assert command_tool_available(policy) is False
    result = run_command_tool({"command": "git status --short"}, policy)

    assert result["ok"] is False
    assert result["error_code"] == "COMMAND_READ_ONLY"


def test_run_command_tool_rejects_workdir_escape(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])
    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")

    result = run_command_tool({"command": "pwd", "workdir": ".."}, policy)

    assert result["ok"] is False
    assert result["error_code"] == "COMMAND_WORKDIR_NOT_AUTHORIZED"


def test_run_command_tool_reports_timeout(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])

    def fake_run(argv, **kwargs):
        raise subprocess.TimeoutExpired(argv, timeout=1, output="partial", stderr="slow")

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    monkeypatch.setattr(command_tool.subprocess, "run", fake_run)

    result = run_command_tool({"command": "sleep 30", "timeout_seconds": 1}, policy)

    assert result["ok"] is False
    assert result["exit_code"] == 124
    assert result["timed_out"] is True
    assert result["stdout"] == "partial"
    assert "Command timed out" in result["stderr"]
