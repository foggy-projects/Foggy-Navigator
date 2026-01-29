# Create logs directory if not exists
New-Item -ItemType Directory -Force -Path "logs" | Out-Null

# Start the launcher with Java 17
$javaPath = "C:\Program Files\Java\jdk-17.0.1\bin\java.exe"
Start-Process $javaPath `
    -ArgumentList '-jar', 'launcher/target/launcher-1.0.0-SNAPSHOT.jar', '--spring.profiles.active=docker' `
    -RedirectStandardOutput 'logs\backend.log' `
    -RedirectStandardError 'logs\backend-error.log' `
    -NoNewWindow

Write-Host "Backend service starting..." -ForegroundColor Green
Write-Host "Waiting for service to be ready (about 30 seconds)..."

# Wait for service to start
$maxWait = 60
$waited = 0

while ($waited -lt $maxWait) {
    Start-Sleep -Seconds 2
    $waited += 2

    $connection = Get-NetTCPConnection -LocalPort 8112 -State Listen -ErrorAction SilentlyContinue
    if ($connection) {
        Write-Host "Service is ready!" -ForegroundColor Green
        break
    }

    Write-Host "." -NoNewline
}

if ($waited -ge $maxWait) {
    Write-Host ""
    Write-Host "Service startup timeout" -ForegroundColor Red
}
