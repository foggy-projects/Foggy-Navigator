<#
.SYNOPSIS
Runs the Codex runtime-affinity migration against isolated MySQL containers.

.EXAMPLE
./tests/migration/test-codex-runtime-affinity.ps1 -RunLauncherValidate

Validates fixtures and the current launcher with the production profile.

.EXAMPLE
./tests/migration/test-codex-runtime-affinity.ps1 `
  -NMinusOneLauncherJar D:/tmp/n1/launcher-1.0.0-SNAPSHOT.jar

Validates an older launcher before and after the expand migration, including
legacy provider-state reads and Session CRUD. The N-1 lane intentionally uses
a compatibility profile with ddl-auto=validate because releases before the
common-repository ownership fix cannot start their original production profile.
#>
[CmdletBinding()]
param(
    [string[]]$Images = @('mysql:8.0.44', 'mysql:8.4.8'),
    [switch]$RunLauncherValidate,
    [string]$LauncherJar,
    [string]$NMinusOneLauncherJar
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = Join-Path $PSScriptRoot 'codex-runtime-affinity-fixture.sql'
$migrationPath = Join-Path $repoRoot 'docs/migration/2026-07-10-codex-runtime-affinity.sql'
$epochCompatibilityMigrationPath = Join-Path $repoRoot 'docs/migration/2026-07-10-codex-task-created-at-epoch-ms.sql'
$runtimeArchiveMigrationPath = Join-Path $repoRoot 'docs/migration/2026-07-11-codex-runtime-archive.sql'
$endpointMigrationPath = Join-Path $repoRoot 'docs/migration/2026-07-12-codex-app-server-endpoints.sql'
$providerSplitMigrationPath = Join-Path $repoRoot 'docs/migration/2026-07-12-codex-provider-split.sql'
$assertionsPath = Join-Path $PSScriptRoot 'codex-runtime-affinity-assertions.sql'
$expectedVersions = @{
    'mysql:8.0.44' = '8.0.44'
    'mysql:8.4.8' = '8.4.8'
}

function Invoke-DockerCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed:`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database,
        [Parameter(Mandatory)][string]$Sql
    )

    $output = $Sql | & docker exec -i --env "MYSQL_PWD=$Password" $Container `
        mysql --user=root --database=$Database --batch --raw 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL execution failed in $Container/${Database}:`n$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Wait-MySql {
    param(
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Password
    )

    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    while ([DateTime]::UtcNow -lt $deadline) {
        & docker exec --env "MYSQL_PWD=$Password" $Container `
            mysql --user=root --batch --skip-column-names --execute='SELECT 1' *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "MySQL did not become ready in container $Container"
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Wait-LauncherStarted {
    param(
        [Parameter(Mandatory)][System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)][string]$StdoutPath,
        [Parameter(Mandatory)][string]$StderrPath,
        [Parameter(Mandatory)][string]$Label
    )

    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        $stdout = if (Test-Path $StdoutPath) { Get-Content -Raw $StdoutPath } else { '' }
        if ($stdout -match 'Started FogyNavigatorApplication') {
            return
        }
        if ($Process.HasExited) {
            $stderr = if (Test-Path $StderrPath) { Get-Content -Raw $StderrPath } else { '' }
            $diagnostic = (($stdout + [Environment]::NewLine + $stderr) -split "`r?`n") |
                Where-Object {
                    $_ -match 'APPLICATION FAILED|Description:|Action:|ERROR|Exception|Caused by:|Schema-validation'
                } |
                Select-Object -Last 80
            throw "$Label exited with code $($Process.ExitCode):`n$($diagnostic -join [Environment]::NewLine)"
        }
        Start-Sleep -Seconds 2
    }
    throw "$Label did not report a successful startup within four minutes"
}

function Stop-TrackedProcess {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process) {
        return
    }
    $Process.Refresh()
    if (-not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
        $Process.WaitForExit(15000) | Out-Null
    }
}

function Start-And-ValidateLauncher {
    param(
        [Parameter(Mandatory)][string]$JarPath,
        [Parameter(Mandatory)][string]$JdbcUrl,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Profile,
        [Parameter(Mandatory)][string]$DdlAuto,
        [Parameter(Mandatory)][string]$Label,
        [scriptblock]$Smoke,
        [object[]]$SmokeArguments = @()
    )

    $port = Get-FreeTcpPort
    $runId = [Guid]::NewGuid().ToString('N')
    $stdoutPath = Join-Path ([System.IO.Path]::GetTempPath()) "navi-$runId.stdout.log"
    $stderrPath = Join-Path ([System.IO.Path]::GetTempPath()) "navi-$runId.stderr.log"
    $arguments = @(
        '-jar', $JarPath,
        "--spring.profiles.active=$Profile",
        "--spring.datasource.url=$JdbcUrl",
        '--spring.datasource.username=root',
        "--spring.datasource.password=$Password",
        "--spring.jpa.hibernate.ddl-auto=$DdlAuto",
        '--navigator.database.startup-migrations.enabled=false',
        "--server.port=$port",
        '--jwt.secret=prod-jwt-secret-value-with-more-than-32-characters',
        '--system.root.password=prod-root-password-value',
        '--navigator.security.credential-key=prod-credential-key-value',
        '--navigator.security.credential-salt=0123456789abcdef',
        '--navigator.api.external-url=https://navigator.example.test',
        '--logging.level.com.foggyframework=WARN'
    )

    $process = $null
    try {
        $process = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru `
            -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        Wait-LauncherStarted -Process $process -StdoutPath $stdoutPath -StderrPath $stderrPath -Label $Label
        if ($Smoke) {
            & $Smoke $port @SmokeArguments
        }
        Write-Host "[PASS] $Label started with ddl-auto=$DdlAuto"
    }
    finally {
        Stop-TrackedProcess -Process $process
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-RequiredJsonProperty {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Label
    )

    if ($null -eq $Value) {
        throw "$Label is null"
    }
    $property = $Value.PSObject.Properties[$Name]
    if (-not $property) {
        throw "$Label did not contain property '$Name'"
    }
    return $property.Value
}

function Assert-RxSuccess {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][string]$Label,
        [switch]$RequireData
    )

    $code = Get-RequiredJsonProperty -Value $Response -Name 'code' -Label "$Label response"
    if ([int]$code -ne 200) {
        throw "$Label returned RX code $code instead of 200"
    }
    $dataProperty = $Response.PSObject.Properties['data']
    $data = if ($dataProperty) { $dataProperty.Value } else { $null }
    if ($RequireData -and $null -eq $data) {
        throw "$Label returned RX code 200 without data"
    }
    return $data
}

function Assert-RxDataId {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][string]$ExpectedId,
        [Parameter(Mandatory)][string]$Label
    )

    $data = Assert-RxSuccess -Response $Response -Label $Label -RequireData
    $actualId = Get-RequiredJsonProperty -Value $data -Name 'id' -Label "$Label data"
    if ([string]$actualId -cne $ExpectedId) {
        throw "$Label returned data.id '$actualId', expected '$ExpectedId'"
    }
    return $data
}

function Assert-RxFailure {
    param(
        [Parameter(Mandatory)]$Response,
        [Parameter(Mandatory)][string]$Label
    )

    $code = Get-RequiredJsonProperty -Value $Response -Name 'code' -Label "$Label response"
    if ([int]$code -in @(200, 201)) {
        throw "$Label unexpectedly returned successful RX code $code"
    }
}

function Get-NMinusOneHeaders {
    param([Parameter(Mandatory)][int]$Port)

    $loginBody = @{ username = 'root'; password = 'prod-root-password-value' } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/v1/auth/login" `
        -ContentType 'application/json' -Body $loginBody
    $loginData = Assert-RxSuccess -Response $login -Label 'N-1 login' -RequireData
    $token = Get-RequiredJsonProperty -Value $loginData -Name 'token' -Label 'N-1 login data'
    if (-not $token) {
        throw 'N-1 login response did not contain a token'
    }
    return @{ Authorization = "Bearer $token" }
}

function Invoke-NMinusOneLegacyReadSmoke {
    param([Parameter(Mandatory)][int]$Port)

    $headers = Get-NMinusOneHeaders -Port $Port
    foreach ($sessionId in @('n1-scalar-state', 'n1-array-state')) {
        $response = Invoke-RestMethod -Method Get `
            -Uri "http://127.0.0.1:$Port/api/v1/sessions/$sessionId" -Headers $headers
        Assert-RxDataId -Response $response -ExpectedId $sessionId `
            -Label "N-1 legacy session GET $sessionId" | Out-Null
    }
    Write-Host '[PASS] N-1 read sessions containing scalar and array provider state'
}

function Invoke-NMinusOneCrudSmoke {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][string]$Container,
        [Parameter(Mandatory)][string]$Password,
        [Parameter(Mandatory)][string]$Database
    )

    $headers = Get-NMinusOneHeaders -Port $Port
    $createBody = @{ title = 'N-1 migration CRUD'; agentId = 'migration-fixture-agent' } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/v1/sessions" `
        -Headers $headers -ContentType 'application/json' -Body $createBody
    $createdData = Assert-RxSuccess -Response $created -Label 'N-1 session create' -RequireData
    $sessionId = Get-RequiredJsonProperty -Value $createdData -Name 'id' -Label 'N-1 session create data'
    if (-not $sessionId) {
        throw 'N-1 create session response did not contain an id'
    }
    $read = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/api/v1/sessions/$sessionId" `
        -Headers $headers
    Assert-RxDataId -Response $read -ExpectedId $sessionId -Label 'N-1 created session GET' | Out-Null

    $deleted = Invoke-RestMethod -Method Delete -Uri "http://127.0.0.1:$Port/api/v1/sessions/$sessionId" `
        -Headers $headers
    Assert-RxSuccess -Response $deleted -Label 'N-1 session delete' | Out-Null

    $afterDelete = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/api/v1/sessions/$sessionId" `
        -Headers $headers -SkipHttpErrorCheck
    Assert-RxFailure -Response $afterDelete -Label 'N-1 deleted session GET'

    if ([string]$sessionId -notmatch '^[A-Za-z0-9-]+$') {
        throw "N-1 created an unsafe session id '$sessionId'"
    }
    $softDeleteSql = @"
SELECT COUNT(*) AS soft_deleted_count
  FROM sessions
 WHERE id = '$sessionId'
   AND status = 'DELETED'
   AND interaction_state = 'DELETED'
   AND deleted_at IS NOT NULL;
"@
    $softDeletedCount = (Invoke-MySql -Container $Container -Password $Password `
        -Database $Database -Sql $softDeleteSql | Select-Object -Last 1).Trim()
    if ($softDeletedCount -ne '1') {
        throw "N-1 session delete did not persist the expected soft-delete state for $sessionId"
    }
    Write-Host "[PASS] N-1 session create/get/delete completed for $sessionId"
}

function Resolve-LauncherJar {
    param([string]$RequestedJar)

    if ($RequestedJar) {
        return (Resolve-Path $RequestedJar).Path
    }

    $candidate = Join-Path $repoRoot 'launcher/target/launcher-1.0.0-SNAPSHOT.jar'
    if (-not (Test-Path $candidate)) {
        throw 'Launcher jar is missing. Run mvn -pl launcher -am -DskipTests package or pass -LauncherJar.'
    }
    return (Resolve-Path $candidate).Path
}

if ($RunLauncherValidate) {
    $LauncherJar = Resolve-LauncherJar -RequestedJar $LauncherJar
}
if ($NMinusOneLauncherJar) {
    $NMinusOneLauncherJar = (Resolve-Path $NMinusOneLauncherJar).Path
}

$fixtureSql = Get-Content -Raw $fixturePath
$migrationSql = Get-Content -Raw $migrationPath
$epochCompatibilityMigrationSql = Get-Content -Raw $epochCompatibilityMigrationPath
$runtimeArchiveMigrationSql = Get-Content -Raw $runtimeArchiveMigrationPath
$endpointMigrationSql = Get-Content -Raw $endpointMigrationPath
$providerSplitMigrationSql = Get-Content -Raw $providerSplitMigrationPath
$assertionsSql = Get-Content -Raw $assertionsPath
$providerSplitFixtureSql = @'
INSERT INTO codex_tasks (
    id, task_id, worker_task_id, session_id, worker_id, status,
    runtime_id, runtime_revision, runtime_type, runtime_instance_id,
    routing_epoch, runtime_acceptance_state, created_at_epoch_ms
) VALUES (
    25, 'pre-split-app-task', 'pre-split-app-worker-task', 'app-existing', 'app-worker', 'COMPLETED',
    'app-runtime', 3, 'APP_SERVER', 'app-instance',
    7, 'ACCEPTED', 1783785600000
);
INSERT INTO session_tasks (task_id, session_id, provider_type)
VALUES ('pre-split-app-task', 'app-existing', 'codex-worker');
'@

foreach ($image in $Images) {
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $container = "navi-affinity-$suffix"
    $password = "Affinity${suffix}Aa1"
    $fixtureDatabase = 'affinity_fixture'
    $launcherDatabase = 'launcher_validate'
    $nMinusOneDatabase = 'n_minus_one_validate'

    try {
        Write-Host "[INFO] Starting isolated $image as $container"
        Invoke-DockerCommand -Arguments @(
            'run', '--detach', '--name', $container,
            '--publish', '127.0.0.1::3306',
            '--env', "MYSQL_ROOT_PASSWORD=$password",
            '--env', "MYSQL_DATABASE=$fixtureDatabase",
            $image
        ) | Out-Null
        Wait-MySql -Container $container -Password $password

        $actualVersion = (Invoke-MySql -Container $container -Password $password `
            -Database $fixtureDatabase -Sql 'SELECT VERSION();' | Select-Object -Last 1).Trim()
        if ($expectedVersions.ContainsKey($image) -and
            -not $actualVersion.StartsWith($expectedVersions[$image])) {
            throw "$image resolved to $actualVersion, expected $($expectedVersions[$image])"
        }

        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $fixtureSql | Out-Null
        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $migrationSql | Out-Null
        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $providerSplitFixtureSql | Out-Null
        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $runtimeArchiveMigrationSql | Out-Null
        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $endpointMigrationSql | Out-Null
        Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $providerSplitMigrationSql | Out-Null
        $evidence = Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $assertionsSql
        Write-Host "[PASS] migration fixtures on $actualVersion"
        $evidence | ForEach-Object { Write-Host "       $_" }

        if ($RunLauncherValidate -or $NMinusOneLauncherJar) {
            $portBinding = (Invoke-DockerCommand -Arguments @(
                'port', $container, '3306/tcp'
            ) | Select-Object -Last 1).Trim()
            $hostPort = [int]($portBinding -replace '^.*:', '')

            if ($RunLauncherValidate) {
                Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
                    -Sql "CREATE DATABASE $launcherDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
                $jdbcUrl = "jdbc:mysql://127.0.0.1:${hostPort}/${launcherDatabase}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                Start-And-ValidateLauncher -JarPath $LauncherJar -JdbcUrl $jdbcUrl -Password $password `
                    -Profile 'migration-schema-bootstrap' -DdlAuto 'create' `
                    -Label "current launcher schema bootstrap on $actualVersion"

                $downgradeSql = @'
DROP TABLE IF EXISTS codex_app_server_endpoints;
DROP TABLE IF EXISTS codex_runtime_revisions;
ALTER TABLE codex_tasks
    DROP COLUMN provider_type,
    DROP COLUMN created_at_epoch_ms,
    DROP COLUMN runtime_request_ciphertext,
    DROP COLUMN runtime_request_hash,
    DROP COLUMN runtime_acceptance_state,
    DROP COLUMN routing_epoch,
    DROP COLUMN runtime_instance_id,
    DROP COLUMN runtime_type,
    DROP COLUMN runtime_revision,
    DROP COLUMN runtime_id;
'@
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $downgradeSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $migrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $runtimeArchiveMigrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $endpointMigrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $providerSplitMigrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql 'ALTER TABLE codex_tasks DROP COLUMN created_at_epoch_ms;' | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $epochCompatibilityMigrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $launcherDatabase `
                    -Sql $epochCompatibilityMigrationSql | Out-Null
                Start-And-ValidateLauncher -JarPath $LauncherJar -JdbcUrl $jdbcUrl -Password $password `
                    -Profile 'prod' -DdlAuto 'validate' `
                    -Label "current launcher prod validate on $actualVersion"
            }

            if ($NMinusOneLauncherJar) {
                Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
                    -Sql "CREATE DATABASE $nMinusOneDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
                $nMinusOneJdbcUrl = "jdbc:mysql://127.0.0.1:${hostPort}/${nMinusOneDatabase}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                Start-And-ValidateLauncher -JarPath $NMinusOneLauncherJar -JdbcUrl $nMinusOneJdbcUrl `
                    -Password $password -Profile 'n-minus-one-schema-bootstrap' -DdlAuto 'create' `
                    -Label "N-1 launcher schema bootstrap on $actualVersion"

                $legacyStateSql = @'
INSERT INTO sessions (
    id, user_id, agent_id, provider_type, title, status,
    interaction_state, pinned, last_activity_at, provider_state_json,
    created_at, updated_at
)
SELECT 'n1-scalar-state', id, 'codex-worker', 'codex-worker', 'N-1 scalar state',
       'ACTIVE', 'PROCESSING', b'0', CURRENT_TIMESTAMP(6), '"legacy-thread"',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM users WHERE username = 'root';
INSERT INTO sessions (
    id, user_id, agent_id, provider_type, title, status,
    interaction_state, pinned, last_activity_at, provider_state_json,
    created_at, updated_at
)
SELECT 'n1-array-state', id, 'codex-worker', 'codex-worker', 'N-1 array state',
       'ACTIVE', 'PROCESSING', b'0', CURRENT_TIMESTAMP(6), '["legacy-thread"]',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM users WHERE username = 'root';
INSERT INTO codex_tasks (
    task_id, worker_task_id, session_id, worker_id, user_id,
    prompt, status, created_at, updated_at
)
SELECT 'n1-scalar-task', 'n1-scalar-worker-task', 'n1-scalar-state',
       'n1-scalar-worker', id, 'compatibility probe', 'COMPLETED',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM users WHERE username = 'root';
INSERT INTO codex_tasks (
    task_id, worker_task_id, session_id, worker_id, user_id,
    prompt, status, created_at, updated_at
)
SELECT 'n1-array-task', 'n1-array-worker-task', 'n1-array-state',
       'n1-array-worker', id, 'compatibility probe', 'COMPLETED',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM users WHERE username = 'root';
'@
                Invoke-MySql -Container $container -Password $password -Database $nMinusOneDatabase `
                    -Sql $legacyStateSql | Out-Null
                Start-And-ValidateLauncher -JarPath $NMinusOneLauncherJar -JdbcUrl $nMinusOneJdbcUrl `
                    -Password $password -Profile 'n-minus-one-compat' -DdlAuto 'validate' `
                    -Label "N-1 legacy-state read on $actualVersion" `
                    -Smoke ${function:Invoke-NMinusOneLegacyReadSmoke}
                Invoke-MySql -Container $container -Password $password -Database $nMinusOneDatabase `
                    -Sql $migrationSql | Out-Null
                Invoke-MySql -Container $container -Password $password -Database $nMinusOneDatabase `
                    -Sql $runtimeArchiveMigrationSql | Out-Null
                $providerStateCheck = @'
SELECT COUNT(*) AS object_state_count
  FROM sessions
 WHERE id IN ('n1-scalar-state', 'n1-array-state')
   AND JSON_VALID(provider_state_json)
   AND JSON_TYPE(provider_state_json) = 'OBJECT';
'@
                $objectStateCount = (Invoke-MySql -Container $container -Password $password `
                    -Database $nMinusOneDatabase -Sql $providerStateCheck | Select-Object -Last 1).Trim()
                if ($objectStateCount -ne '2') {
                    throw "N-1 provider state backfill expected 2 objects, found $objectStateCount"
                }
                Start-And-ValidateLauncher -JarPath $NMinusOneLauncherJar -JdbcUrl $nMinusOneJdbcUrl `
                    -Password $password `
                    -Profile 'n-minus-one-compat' -DdlAuto 'validate' `
                    -Label "N-1 launcher expanded-schema validate on $actualVersion" `
                    -Smoke ${function:Invoke-NMinusOneCrudSmoke} `
                    -SmokeArguments @($container, $password, $nMinusOneDatabase)
            }
        }
    }
    finally {
        & docker rm --force $container *> $null
        Write-Host "[INFO] Removed isolated container $container"
    }
}

Write-Host '[PASS] codex runtime affinity migration harness completed'
