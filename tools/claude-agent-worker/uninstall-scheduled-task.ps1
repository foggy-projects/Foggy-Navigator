# Claude Agent Worker - Uninstall Windows Task Scheduler entry
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File uninstall-scheduled-task.ps1

param(
    [string]$TaskName = "Foggy-ClaudeAgentWorker"
)

$ErrorActionPreference = "Stop"

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host "Scheduled task not found: $TaskName" -ForegroundColor Yellow
    exit 0
}

try {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
}
catch { }

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
Write-Host "Removed scheduled task: $TaskName" -ForegroundColor Green
