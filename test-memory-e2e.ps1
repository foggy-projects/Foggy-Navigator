$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "=========================================="
Write-Host "  Memory E2E Test"
Write-Host "=========================================="
Write-Host ""

# 1. Get auth token
Write-Host "[1/4] Getting auth token..."
$response = Invoke-RestMethod -Uri 'http://localhost:8112/api/v1/auth/login' -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"root","password":"root123"}'
$TOKEN = $response.data.token
Write-Host "  Token: $($TOKEN.Substring(0,20))..."

# 2. Create session and send save request
Write-Host ""
Write-Host "[2/4] Creating session and asking to save memory..."
$response = Invoke-RestMethod -Uri 'http://localhost:8112/api/v1/sessions' -Method Post -Headers @{'Authorization'="Bearer $TOKEN"} -ContentType 'application/json; charset=utf-8' -Body '{"agentId":"tutor-agent"}'
$session = $response.data.id
Write-Host "  Session: $session"

$body = @{content='请记住我最喜欢的编程语言是Rust'} | ConvertTo-Json -Compress
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri "http://localhost:8112/api/v1/sessions/$session/messages" -Method Post -Headers @{'Authorization'="Bearer $TOKEN"} -ContentType 'application/json; charset=utf-8' -Body $bodyBytes -TimeoutSec 60 | Out-Null

Write-Host "  Waiting for agent processing (15s)..."
Start-Sleep -Seconds 15

# Check logs
$saved = Select-String -Path 'D:\foggy-projects\Foggy-Navigator\logs\backend.log' -Pattern 'Memory saved via tool.*Rust' | Select-Object -Last 1
if ($saved) {
    Write-Host "  ✓ save_memory was called successfully"
} else {
    Write-Host "  ✗ save_memory was NOT called - checking for errors..."
    $errors = Select-String -Path 'D:\foggy-projects\Foggy-Navigator\logs\backend.log' -Pattern 'ERROR.*Agent invocation|invalid.*key' | Select-Object -Last 3
    $errors | ForEach-Object { Write-Host "    $_" }
}

# 3. List memories
Write-Host ""
Write-Host "[3/4] Listing memories via API..."
$response = Invoke-RestMethod -Uri 'http://localhost:8112/api/v1/config/platform/memories' -Headers @{'Authorization'="Bearer $TOKEN"}
$response.data | ForEach-Object {
    Write-Host "  - [$($_.category)] $($_.content)"
}

# 4. Create new session and check memory injection
Write-Host ""
Write-Host "[4/4] Creating new session to test memory injection..."
$response = Invoke-RestMethod -Uri 'http://localhost:8112/api/v1/sessions' -Method Post -Headers @{'Authorization'="Bearer $TOKEN"} -ContentType 'application/json; charset=utf-8' -Body '{"agentId":"tutor-agent"}'
$session2 = $response.data.id

$body2 = @{content='你记得我喜欢什么编程语言吗'} | ConvertTo-Json -Compress
$bodyBytes2 = [System.Text.Encoding]::UTF8.GetBytes($body2)
Invoke-RestMethod -Uri "http://localhost:8112/api/v1/sessions/$session2/messages" -Method Post -Headers @{'Authorization'="Bearer $TOKEN"} -ContentType 'application/json; charset=utf-8' -Body $bodyBytes2 -TimeoutSec 60 | Out-Null

Start-Sleep -Seconds 10
$injected = Select-String -Path 'D:\foggy-projects\Foggy-Navigator\logs\backend.log' -Pattern 'Injected user memory' | Select-Object -Last 1
if ($injected) {
    Write-Host "  ✓ Memory was injected: $injected"
} else {
    Write-Host "  ✗ Memory was NOT injected"
}

Write-Host ""
Write-Host "=========================================="
Write-Host "  Test Complete"
Write-Host "=========================================="
