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


def test_command_tool_available_requires_linux_setting_and_explicit_allowlist(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["command"])

    monkeypatch.setattr(command_tool.settings, "enable_command", False)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    assert command_tool_available(policy) is False

    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    assert command_tool_available(policy) is True

    monkeypatch.setattr(command_tool.platform, "system", lambda: "Windows")
    assert command_tool_available(policy) is False

    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")
    assert command_tool_available(_policy(tmp_path, allowed_tools=["read_file"])) is False
    assert command_tool_available(_policy(tmp_path, allowed_tools=None)) is False


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
    assert captured["argv"] == ["/bin/bash", "-lc", "git status --short"]
    assert captured["kwargs"]["cwd"] == str(policy.workdir)
    assert captured["kwargs"]["stdin"] is command_tool.subprocess.DEVNULL


def test_run_command_tool_rejects_when_not_explicitly_allowed(tmp_path, monkeypatch):
    policy = _policy(tmp_path, allowed_tools=["read_file"])
    monkeypatch.setattr(command_tool.settings, "enable_command", True)
    monkeypatch.setattr(command_tool.platform, "system", lambda: "Linux")

    result = run_command_tool({"command": "git status --short"}, policy)

    assert result["ok"] is False
    assert result["error_code"] == "COMMAND_NOT_AUTHORIZED"


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
