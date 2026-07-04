$script = Join-Path $PSScriptRoot "scripts\start-launcher.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script @args
exit $LASTEXITCODE
