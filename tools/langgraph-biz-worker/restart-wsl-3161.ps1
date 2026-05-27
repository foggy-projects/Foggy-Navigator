# Restart the WSL-hosted LangGraph Biz Worker used by local smoke tests.
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\langgraph-biz-worker\restart-wsl-3161.ps1
#   powershell -ExecutionPolicy Bypass -File tools\langgraph-biz-worker\restart-wsl-3161.ps1 -SyncSource

param(
    [int]$Port = 3161,
    [string]$WslWorkerDir = "/home/navigator/.langgraph-biz-worker",
    [string]$WslUser = "navigator",
    [string]$EnvFile = ".env",
    [switch]$SyncSource
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function ConvertTo-BashLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "'""'""'") + "'"
}

function Invoke-WslBash {
    param([string]$Script)
    $tempScript = New-TemporaryFile
    try {
        Set-Content -Path $tempScript -Value $Script -Encoding ASCII
        $tempScriptWsl = (& wsl -e wslpath -a $tempScript.FullName).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $tempScriptWsl) {
            throw "Failed to resolve temporary script path in WSL"
        }
        & wsl -e bash $tempScriptWsl
        if ($LASTEXITCODE -ne 0) {
            throw "WSL command failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Remove-Item -LiteralPath $tempScript -Force -ErrorAction SilentlyContinue
    }
}

$repoWorkerDirWsl = (& wsl -e wslpath -a $ScriptDir).Trim()
if ($LASTEXITCODE -ne 0 -or -not $repoWorkerDirWsl) {
    throw "Failed to resolve repository worker path in WSL"
}

$repoWorkerDirArg = ConvertTo-BashLiteral $repoWorkerDirWsl
$wslWorkerDirArg = ConvertTo-BashLiteral $WslWorkerDir
$wslUserArg = ConvertTo-BashLiteral $WslUser
$envFileArg = ConvertTo-BashLiteral $EnvFile

if ($SyncSource) {
    Write-Host "Syncing source to WSL worker directory..." -ForegroundColor Cyan
    Invoke-WslBash @"
set -euo pipefail
repo_dir=$repoWorkerDirArg
worker_dir=$wslWorkerDirArg
wsl_user=$wslUserArg
test -d "`$repo_dir/src"
test -d "`$worker_dir/src"
runuser -u "`$wsl_user" -- rsync -a "`$repo_dir/src/" "`$worker_dir/src/"
runuser -u "`$wsl_user" -- rsync -a "`$repo_dir/pyproject.toml" "`$worker_dir/pyproject.toml"
"@
}

Write-Host "Restarting LangGraph Biz Worker in WSL on port $Port..." -ForegroundColor Cyan
Invoke-WslBash @"
set -euo pipefail
worker_dir=$wslWorkerDirArg
wsl_user=$wslUserArg
env_file=$envFileArg
port=$Port

cd "`$worker_dir"

listening_pid() {
  ss -ltnp 2>/dev/null \
    | grep -E "[:.]`$port[[:space:]]" \
    | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' \
    | head -n 1
}

pid="`$(listening_pid)"
if [ -n "`$pid" ]; then
  echo "Stopping existing listener on port `$port (pid=`$pid)"
  kill "`$pid" || true
  for _ in `$(seq 1 20); do
    if kill -0 "`$pid" 2>/dev/null; then
      sleep 0.2
    else
      break
    fi
  done
  if kill -0 "`$pid" 2>/dev/null; then
    kill -9 "`$pid" || true
  fi
fi

mkdir -p logs
if [ -x ".venv/bin/python" ]; then
  python_bin=".venv/bin/python"
elif [ -x ".venv-wsl/bin/python" ]; then
  python_bin=".venv-wsl/bin/python"
else
  python_bin="python3"
fi

runuser -u "`$wsl_user" -- bash -c '
set -euo pipefail
worker_dir="`$1"
env_file="`$2"
python_bin="`$3"
port="`$4"
cd "`$worker_dir"
export PYTHONPATH="`$worker_dir/src"
export BIZ_WORKER_ENV_FILE="`$worker_dir/`$env_file"
exec nohup "`$python_bin" -m uvicorn langgraph_biz_worker.main:app --host 0.0.0.0 --port "`$port" > "`$worker_dir/logs/worker-`$port.log" 2> "`$worker_dir/logs/worker-`$port-error.log" < /dev/null
' bash "`$worker_dir" "`$env_file" "`$python_bin" "`$port" &

for _ in `$(seq 1 30); do
  if health="`$(curl -fsS "http://127.0.0.1:`$port/health" 2>/dev/null)"; then
    echo "`$health"
    exit 0
  fi
  sleep 1
done

echo "Worker did not become healthy on port `$port" >&2
tail -n 40 "logs/worker-`$port-error.log" >&2 || true
exit 1
"@
