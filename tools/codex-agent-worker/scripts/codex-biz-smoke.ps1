param(
    [string]$BaseUrl = "http://127.0.0.1:3051",
    [string]$Token = "",
    [string]$Cwd = "",
    [string]$ActorAKey = "codex-biz-smoke/actor-a",
    [string]$ActorBKey = "codex-biz-smoke/actor-b",
    [string]$Prompt = "Reply with exactly PONG.",
    [string]$ResumePrompt = "Reply with exactly PONG-RESUME.",
    [int]$TimeoutSec = 180,
    [switch]$RunLiveQueries
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Net.Http

function New-Headers {
    $headers = @{
        "Accept" = "application/json"
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }
    return $headers
}

function Invoke-HealthCheck {
    param([string]$Url)

    $healthUrl = "$Url/health"
    Write-Host "[codex-biz-smoke] Checking health: $healthUrl"
    $health = Invoke-RestMethod -Method Get -Uri $healthUrl -Headers (New-Headers) -TimeoutSec 30

    $configured = [bool]$health.codex_biz_home_root_configured
    $ready = [bool]$health.codex_biz_scoped_home_ready
    Write-Host "[codex-biz-smoke] health status=$($health.status) sdk=$($health.codex_sdk_available) auth=$($health.codex_auth_mode) bizHomeConfigured=$configured bizReady=$ready"

    if (-not $configured -or -not $ready) {
        throw "Codex Biz scoped home is not ready. Configure CODEX_BIZ_HOME_ROOT on the worker and restart it."
    }
}

function Invoke-JsonPostAsText {
    param(
        [string]$Url,
        [hashtable]$Headers,
        [string]$JsonBody,
        [int]$TimeoutSec
    )

    $client = [System.Net.Http.HttpClient]::new()
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $Url)
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        foreach ($key in $Headers.Keys) {
            if ($key -eq "Content-Type") {
                continue
            }
            [void]$request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key])
        }
        $request.Content = [System.Net.Http.StringContent]::new($JsonBody, [System.Text.Encoding]::UTF8, "application/json")

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $($response.ReasonPhrase): $text"
        }
        return $text
    } finally {
        if ($null -ne $request) {
            $request.Dispose()
        }
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Invoke-CodexBizQuery {
    param(
        [string]$Url,
        [string]$ActorKey,
        [string]$QueryPrompt,
        [string]$SessionId
    )

    $body = [ordered]@{
        prompt = $QueryPrompt
        codex_home_key = $ActorKey
        sandbox_mode = "workspace-write"
        approval_policy = "never"
        network_access_enabled = $false
        web_search_mode = "disabled"
        max_turns = 1
    }
    if (-not [string]::IsNullOrWhiteSpace($Cwd)) {
        $body["cwd"] = $Cwd
    }
    if (-not [string]::IsNullOrWhiteSpace($SessionId)) {
        $body["session_id"] = $SessionId
    }

    $headers = New-Headers
    $headers["Accept"] = "text/event-stream"
    $headers["Content-Type"] = "application/json"

    Write-Host "[codex-biz-smoke] Running live query for actorKey=$ActorKey resume=$(-not [string]::IsNullOrWhiteSpace($SessionId))"
    $responseText = Invoke-JsonPostAsText `
        -Url "$Url/api/v1/query" `
        -Headers $headers `
        -JsonBody ($body | ConvertTo-Json -Depth 10) `
        -TimeoutSec $TimeoutSec

    $events = @()
    foreach ($rawLine in ($responseText -split "`r?`n")) {
        $line = $rawLine.Trim()
        if (-not $line.StartsWith("data:")) {
            continue
        }
        $payload = $line.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload) -or $payload -eq "[DONE]") {
            continue
        }
        try {
            $events += ($payload | ConvertFrom-Json)
        } catch {
            Write-Warning "Ignoring unparsable SSE payload: $payload"
        }
    }

    $errorEvent = $events | Where-Object { $_.type -eq "error" } | Select-Object -Last 1
    if ($null -ne $errorEvent) {
        throw "Codex Biz query failed: $($errorEvent.error)"
    }

    $terminal = $events | Where-Object { $_.type -eq "result" } | Select-Object -Last 1
    if ($null -eq $terminal) {
        throw "Codex Biz query did not produce a result event. eventCount=$($events.Count)"
    }

    $summary = [pscustomobject]@{
        task_id = $terminal.task_id
        session_id = $terminal.session_id
        model = $terminal.model
        num_turns = $terminal.num_turns
        event_count = $events.Count
    }
    Write-Host "[codex-biz-smoke] result task=$($summary.task_id) session=$($summary.session_id) model=$($summary.model) turns=$($summary.num_turns) events=$($summary.event_count)"
    return $summary
}

$normalizedBaseUrl = $BaseUrl.TrimEnd("/")
Invoke-HealthCheck -Url $normalizedBaseUrl

if (-not $RunLiveQueries) {
    Write-Host "[codex-biz-smoke] Health readiness passed. Add -RunLiveQueries to run actor A/B live Codex calls."
    return
}

$actorA = Invoke-CodexBizQuery -Url $normalizedBaseUrl -ActorKey $ActorAKey -QueryPrompt $Prompt -SessionId ""
$actorB = Invoke-CodexBizQuery -Url $normalizedBaseUrl -ActorKey $ActorBKey -QueryPrompt $Prompt -SessionId ""

if ([string]::IsNullOrWhiteSpace($actorA.session_id)) {
    throw "Actor A query did not return session_id; cannot verify resume."
}

$actorAResume = Invoke-CodexBizQuery -Url $normalizedBaseUrl -ActorKey $ActorAKey -QueryPrompt $ResumePrompt -SessionId $actorA.session_id

Write-Host "[codex-biz-smoke] Live smoke passed. actorA_session=$($actorA.session_id) actorB_session=$($actorB.session_id) actorA_resume_session=$($actorAResume.session_id)"
