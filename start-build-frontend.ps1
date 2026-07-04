$script = Join-Path $PSScriptRoot "scripts\start-build-frontend.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script @args
exit $LASTEXITCODE
