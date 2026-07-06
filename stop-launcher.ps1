$script = Join-Path $PSScriptRoot "scripts\stop-launcher.ps1"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script @args
exit $LASTEXITCODE
