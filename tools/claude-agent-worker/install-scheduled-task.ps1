# Claude Agent Worker - Install Windows Task Scheduler entry
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File install-scheduled-task.ps1
#   powershell -ExecutionPolicy Bypass -File install-scheduled-task.ps1 -AtStartup -RunNow

param(
    [string]$TaskName = "Foggy-ClaudeAgentWorker",
    [string]$UserId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name,
    [switch]$AtStartup,
    [switch]$RunNow,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$WorkerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunnerScript = Join-Path $WorkerDir "run-scheduled-task.ps1"
$PowerShellExe = Join-Path $PSHOME "powershell.exe"

if (-not (Test-Path $RunnerScript)) {
    Write-Host "ERROR: runner script not found: $RunnerScript" -ForegroundColor Red
    exit 1
}

$taskArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", ('"{0}"' -f $RunnerScript)
) -join " "

$action = New-ScheduledTaskAction `
    -Execute $PowerShellExe `
    -Argument $taskArgs `
    -WorkingDirectory $WorkerDir

$triggers = @(
    (New-ScheduledTaskTrigger -AtLogOn -User $UserId)
)
if ($AtStartup) {
    $triggers += New-ScheduledTaskTrigger -AtStartup
}

$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1)

$principal = New-ScheduledTaskPrincipal `
    -UserId $UserId `
    -LogonType S4U `
    -RunLevel Limited

$description = "Foggy Claude Agent Worker managed by Windows Task Scheduler"

if ($Force -and (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $triggers `
    -Settings $settings `
    -Principal $principal `
    -Description $description `
    -Force | Out-Null

Write-Host "Installed scheduled task: $TaskName" -ForegroundColor Green
Write-Host "User:      $UserId" -ForegroundColor Cyan
Write-Host "LogonType: S4U" -ForegroundColor Cyan
Write-Host "Triggers:  AtLogOn" -ForegroundColor Cyan
if ($AtStartup) {
    Write-Host "           AtStartup" -ForegroundColor Cyan
}
Write-Host "Runner:    $RunnerScript" -ForegroundColor Gray
Write-Host ""
Write-Host "Manage with:" -ForegroundColor Yellow
Write-Host "  Start-ScheduledTask -TaskName `"$TaskName`"" -ForegroundColor Gray
Write-Host "  Stop-ScheduledTask  -TaskName `"$TaskName`"" -ForegroundColor Gray
Write-Host "  Get-ScheduledTaskInfo -TaskName `"$TaskName`"" -ForegroundColor Gray

if ($RunNow) {
    Write-Host ""
    Write-Host "Starting task now..." -ForegroundColor Yellow
    Start-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 2
    try {
        $info = Get-ScheduledTaskInfo -TaskName $TaskName
        Write-Host ("State:      {0}" -f (Get-ScheduledTask -TaskName $TaskName).State) -ForegroundColor Green
        Write-Host ("LastResult: {0}" -f $info.LastTaskResult) -ForegroundColor Green
    }
    catch {
        Write-Host "Task started, but state query failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}
