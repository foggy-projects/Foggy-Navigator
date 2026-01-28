@echo off
REM Coding Agent 启动脚本 (Windows Batch)
REM 这是一个简单的包装器，调用 PowerShell 脚本

setlocal

REM 检查是否提供了参数
if "%1"=="" (
    powershell -ExecutionPolicy Bypass -File "%~dp0start-and-test.ps1" help
) else (
    powershell -ExecutionPolicy Bypass -File "%~dp0start-and-test.ps1" %*
)

endlocal
