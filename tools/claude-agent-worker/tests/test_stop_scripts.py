"""Safety contracts for legacy Worker stop scripts."""

from __future__ import annotations

from pathlib import Path


WORKER_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = WORKER_DIR.parents[1]


def test_stop_scripts_fail_closed_before_any_non_forced_stop() -> None:
    for name in ("stop.sh", "stop.ps1"):
        script = (WORKER_DIR / name).read_text(encoding="utf-8")

        assert "WORKER_DRAIN_UNCONFIRMED" in script
        assert "worker_ownership_unverified" in script
        assert "process_snapshot_unavailable_or_invalid" in script
        assert "preflight_not_quiescent" in script
        assert "stop-evidence" in script
        assert "/api/v1/processes" in script
        assert "kill -9" not in script
        assert "taskkill /F" not in script
        assert "Stop-Process -Id $processId -Force" not in script


def test_unix_stop_script_uses_sigterm_only_after_snapshot_gate() -> None:
    script = (WORKER_DIR / "stop.sh").read_text(encoding="utf-8")

    assert "kill -TERM" in script
    assert script.index("if ! fetch_snapshot") < script.index("if ! request_graceful_stop")
    assert "ss -ltnp" in script


def test_legacy_start_scripts_delegate_to_fail_closed_stop_gates() -> None:
    scripts = {
        "codex_shell": REPO_ROOT / "tools/codex-agent-worker/start.sh",
        "claude_shell": WORKER_DIR / "start.sh",
        "codex_powershell": REPO_ROOT / "tools/codex-agent-worker/start.ps1",
        "claude_powershell": WORKER_DIR / "start.ps1",
    }

    contents = {name: path.read_text(encoding="utf-8") for name, path in scripts.items()}
    for script in contents.values():
        assert "kill -9" not in script
        assert "taskkill /F" not in script
        assert "Stop-Process -Id $procId -Force" not in script
        assert "Refusing to start a replacement" in script

    assert 'if ! bash "$SCRIPT_DIR/stop.sh"' in contents["codex_shell"]
    assert 'if ! bash "$WorkerDir/stop.sh"' in contents["claude_shell"]
    for name in ("codex_powershell", "claude_powershell"):
        assert 'Join-Path $' in contents[name]
        assert '"stop.ps1"' in contents[name]
        assert '& $PowerShellHost -NoProfile -ExecutionPolicy Bypass -File $StopScript' in contents[name]
        assert 'if ($safeStopExitCode -ne 0)' in contents[name]
        assert 'exit $safeStopExitCode' in contents[name]
