# Coding Agent Start and Test Script for Windows PowerShell
#
# Usage:
#   .\start-and-test.ps1 start     - Start backend service
#   .\start-and-test.ps1 stop      - Stop all services
#   .\start-and-test.ps1 status    - Show service status
#   .\start-and-test.ps1 test      - Start and prepare for testing
#   .\start-and-test.ps1 clean     - Clean logs and temp files
#   .\start-and-test.ps1 restart   - Restart services
#   .\start-and-test.ps1 frontend  - Start frontend dev server
#

param(
    [Parameter(Position=0)]
    [ValidateSet("start", "stop", "status", "test", "clean", "restart", "frontend")]
    [string]$Command = "help"
)

# Configuration
$BACKEND_PORT = 8112
$FRONTEND_PORT = 5173
$LOG_DIR = "logs"
$BACKEND_LOG = Join-Path $LOG_DIR "backend.log"
$FRONTEND_LOG = Join-Path $LOG_DIR "frontend.log"
$PID_FILE = Join-Path $LOG_DIR "pids.txt"
$CREDENTIALS_FILE = Join-Path $LOG_DIR "credentials.txt"

# Color output functions
function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host "[SUCCESS] $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

# Check if port is in use
function Test-Port {
    param([int]$Port)
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $connections
}

# Find process ID by port
function Get-ProcessByPort {
    param([int]$Port)
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($connections) {
        return $connections[0].OwningProcess
    }
    return $null
}

# Create log directory
if (-not (Test-Path $LOG_DIR)) {
    New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Yellow
Write-Host "Coding Agent Management Script (Windows)" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Yellow
Write-Host ""

# Start backend service
function Start-Backend {
    Write-Info "Starting Coding Agent backend service..."

    # Check port
    if (Test-Port -Port $BACKEND_PORT) {
        Write-Warn "Port $BACKEND_PORT is already in use, trying to stop old process..."
        Stop-Backend
        Start-Sleep -Seconds 2
    }

    # Start with Maven
    Write-Info "Starting Spring Boot application with Maven..."

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "cmd.exe"
    $startInfo.Arguments = "/c mvn spring-boot:run -Dspring-boot.run.profiles=docker > `"$BACKEND_LOG`" 2>&1"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
    $startInfo.WorkingDirectory = (Get-Location).Path

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $process.Start() | Out-Null

    $processId = $process.Id
    Add-Content -Path $PID_FILE -Value "backend:$processId"

    Write-Info "Backend service starting (PID: $processId), log: $BACKEND_LOG"
    Write-Info "Waiting for service to be ready (about 20-30 seconds)..."

    # Wait for service to start
    $maxWait = 60
    $waited = 0
    $started = $false

    while ($waited -lt $maxWait) {
        if (Test-Path $BACKEND_LOG) {
            $content = Get-Content $BACKEND_LOG -Raw -ErrorAction SilentlyContinue

            if ($content -match "Started CodingAgentApplication") {
                Write-Success "Backend service started successfully!"
                $started = $true

                # Extract and save password
                if ($content -match "security password:\s+(\S+)") {
                    $password = $Matches[1]
                    Write-Success "Generated password: $password"
                    Set-Content -Path $CREDENTIALS_FILE -Value "BACKEND_PASSWORD=$password"
                    Write-Host "Username: user"
                    Write-Host "Password: $password"
                }
                break
            }

            if ($content -match "BUILD FAILURE|Exception") {
                Write-Error-Custom "Backend service failed to start!"
                Get-Content $BACKEND_LOG -Tail 50
                return $false
            }
        }

        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host "." -NoNewline
    }

    Write-Host ""

    if (-not $started) {
        Write-Error-Custom "Backend service startup timeout (over $maxWait seconds)"
        Write-Error-Custom "Check log: $BACKEND_LOG"
        if (Test-Path $BACKEND_LOG) {
            Get-Content $BACKEND_LOG -Tail 30
        }
        return $false
    }

    return $true
}

# Start frontend dev server
function Start-Frontend {
    Write-Info "Starting frontend development server..."

    if (Test-Port -Port $FRONTEND_PORT) {
        Write-Warn "Port $FRONTEND_PORT is already in use, trying to stop old process..."
        Stop-Frontend
        Start-Sleep -Seconds 2
    }

    Push-Location frontend

    # Check and install dependencies
    if (-not (Test-Path "node_modules")) {
        Write-Info "Installing frontend dependencies..."
        npm install
    }

    # Start dev server
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "cmd.exe"
    $startInfo.Arguments = "/c npm run dev > `"..\$FRONTEND_LOG`" 2>&1"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
    $startInfo.WorkingDirectory = (Get-Location).Path

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $process.Start() | Out-Null

    $processId = $process.Id
    Pop-Location
    Add-Content -Path $PID_FILE -Value "frontend:$processId"

    Write-Info "Frontend service starting (PID: $processId), log: $FRONTEND_LOG"
    Start-Sleep -Seconds 3
    Write-Success "Frontend dev server started successfully!"
    Write-Info "Access URL: http://localhost:$FRONTEND_PORT"
}

# Stop backend service
function Stop-Backend {
    Write-Info "Stopping backend service..."

    # Read from PID file
    if (Test-Path $PID_FILE) {
        $lines = Get-Content $PID_FILE -ErrorAction SilentlyContinue
        foreach ($line in $lines) {
            if ($line -match "^backend:(\d+)") {
                $processId = [int]$Matches[1]
                $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
                if ($process) {
                    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
                    Write-Success "Backend service stopped (PID: $processId)"
                }
            }
        }
        # Remove record
        $newContent = $lines | Where-Object { $_ -notmatch "^backend:" }
        if ($newContent) {
            Set-Content -Path $PID_FILE -Value $newContent
        } else {
            Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
        }
    }

    # Find and stop by port
    if (Test-Port -Port $BACKEND_PORT) {
        Write-Warn "Stopping backend process by port..."
        $processId = Get-ProcessByPort -Port $BACKEND_PORT
        if ($processId) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Write-Success "Stopped process PID: $processId"
        }
    }
}

# Stop frontend service
function Stop-Frontend {
    Write-Info "Stopping frontend service..."

    # Read from PID file
    if (Test-Path $PID_FILE) {
        $lines = Get-Content $PID_FILE -ErrorAction SilentlyContinue
        foreach ($line in $lines) {
            if ($line -match "^frontend:(\d+)") {
                $processId = [int]$Matches[1]
                $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
                if ($process) {
                    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
                    Write-Success "Frontend service stopped (PID: $processId)"
                }
            }
        }
        # Remove record
        $newContent = $lines | Where-Object { $_ -notmatch "^frontend:" }
        if ($newContent) {
            Set-Content -Path $PID_FILE -Value $newContent
        } else {
            Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
        }
    }

    # Find and stop by port
    if (Test-Port -Port $FRONTEND_PORT) {
        Write-Warn "Stopping frontend process by port..."
        $processId = Get-ProcessByPort -Port $FRONTEND_PORT
        if ($processId) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Write-Success "Stopped process PID: $processId"
        }
    }
}

# Stop all services
function Stop-All {
    Write-Info "Stopping all services..."
    Stop-Backend
    Stop-Frontend

    # Stop playwright sessions
    if (Get-Command playwright-cli -ErrorAction SilentlyContinue) {
        Write-Info "Stopping playwright sessions..."
        playwright-cli session-stop-all 2>$null
    }

    Write-Success "All services stopped"
}

# Show service status
function Show-Status {
    Write-Host ""
    Write-Host "========================================"
    Write-Host "  Coding Agent Service Status"
    Write-Host "========================================"
    Write-Host ""

    # Backend status
    Write-Host -NoNewline "Backend Service (Port $BACKEND_PORT): "
    if (Test-Port -Port $BACKEND_PORT) {
        Write-Host "RUNNING" -ForegroundColor Green
        if (Test-Path $CREDENTIALS_FILE) {
            $content = Get-Content $CREDENTIALS_FILE -Raw
            if ($content -match "BACKEND_PASSWORD=(.+)") {
                $password = $Matches[1].Trim()
                Write-Host "  URL: http://localhost:$BACKEND_PORT"
                Write-Host "  Username: user"
                Write-Host "  Password: $password"
            }
        }
    } else {
        Write-Host "NOT RUNNING" -ForegroundColor Red
    }

    Write-Host ""

    # Frontend status
    Write-Host -NoNewline "Frontend Service (Port $FRONTEND_PORT): "
    if (Test-Port -Port $FRONTEND_PORT) {
        Write-Host "RUNNING" -ForegroundColor Green
        Write-Host "  URL: http://localhost:$FRONTEND_PORT"
    } else {
        Write-Host "NOT RUNNING (using backend static resources)" -ForegroundColor Yellow
    }

    Write-Host ""

    # PID file info
    if (Test-Path $PID_FILE) {
        $lines = Get-Content $PID_FILE -ErrorAction SilentlyContinue
        if ($lines) {
            Write-Host "Process Information:"
            foreach ($line in $lines) {
                if ($line -match "^(.+):(\d+)") {
                    $service = $Matches[1]
                    $processId = [int]$Matches[2]
                    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
                    if ($process) {
                        Write-Host "  $service : RUNNING (PID: $processId)" -ForegroundColor Green
                    } else {
                        Write-Host "  $service : STOPPED (PID: $processId)" -ForegroundColor Red
                    }
                }
            }
            Write-Host ""
        }
    }

    Write-Host "========================================"
}

# Run test
function Run-Test {
    Write-Info "Preparing automated testing environment..."

    # Ensure service is running
    if (-not (Test-Port -Port $BACKEND_PORT)) {
        Write-Info "Backend service not running, starting..."
        $result = Start-Backend
        if (-not $result) {
            return
        }
    }

    # Load credentials
    if (Test-Path $CREDENTIALS_FILE) {
        $content = Get-Content $CREDENTIALS_FILE -Raw
        if ($content -match "BACKEND_PASSWORD=(.+)") {
            $password = $Matches[1].Trim()
        } else {
            Write-Error-Custom "Cannot read password from credentials file"
            return
        }
    } else {
        Write-Error-Custom "Credentials file not found, cannot run test"
        return
    }

    Write-Host ""
    Write-Success "Test environment ready!"
    Write-Info "========================================"
    Write-Info "Login credentials:"
    Write-Info "  URL: http://localhost:$BACKEND_PORT"
    Write-Info "  Username: user"
    Write-Info "  Password: $password"
    Write-Info "========================================"
    Write-Host ""
    Write-Info "Tip: Use playwright-cli for testing"
    Write-Info "Example: playwright-cli open http://localhost:$BACKEND_PORT"
    Write-Host ""
    Write-Info "Or use skill: /test-coding-agent"
}

# Clean logs and temp files
function Clean-All {
    Write-Info "Cleaning logs and temporary files..."
    Remove-Item -Path $LOG_DIR -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -Path "frontend\node_modules\.vite" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -Path ".playwright-cli" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Success "Cleanup complete"
}

# Show help
function Show-Help {
    Write-Host ""
    Write-Host "Usage: .\start-and-test.ps1 {start|stop|status|test|clean|restart|frontend}"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  start     - Start backend service (Maven + docker profile)"
    Write-Host "  stop      - Stop all services (backend, frontend, playwright)"
    Write-Host "  status    - Show service status and credentials"
    Write-Host "  test      - Prepare testing environment (start and show credentials)"
    Write-Host "  clean     - Clean logs and temporary files"
    Write-Host "  restart   - Restart services"
    Write-Host "  frontend  - Start frontend dev server (optional)"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\start-and-test.ps1 start          # Start service"
    Write-Host "  .\start-and-test.ps1 status         # Check status"
    Write-Host "  .\start-and-test.ps1 test           # Prepare test"
    Write-Host "  .\start-and-test.ps1 stop           # Stop all"
    Write-Host ""
}

# Main command handling
switch ($Command) {
    "start" {
        Start-Backend
        Show-Status
    }
    "stop" {
        Stop-All
    }
    "status" {
        Show-Status
    }
    "test" {
        Run-Test
    }
    "clean" {
        Stop-All
        Clean-All
    }
    "restart" {
        Stop-All
        Start-Sleep -Seconds 2
        Start-Backend
        Show-Status
    }
    "frontend" {
        Start-Frontend
        Show-Status
    }
    default {
        Show-Help
    }
}
