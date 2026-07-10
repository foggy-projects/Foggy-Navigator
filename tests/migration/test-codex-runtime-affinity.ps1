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
        [Parameter(Mandatory)][string]$Label
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
        Write-Host "[PASS] $Label started with ddl-auto=$DdlAuto"
    }
    finally {
        Stop-TrackedProcess -Process $process
        Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
    }
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
$assertionsSql = Get-Content -Raw $assertionsPath

foreach ($image in $Images) {
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $container = "navi-affinity-$suffix"
    $password = "Affinity${suffix}Aa1"
    $fixtureDatabase = 'affinity_fixture'
    $launcherDatabase = 'launcher_validate'

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
        $evidence = Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
            -Sql $assertionsSql
        Write-Host "[PASS] migration fixtures on $actualVersion"
        $evidence | ForEach-Object { Write-Host "       $_" }

        if ($RunLauncherValidate -or $NMinusOneLauncherJar) {
            Invoke-MySql -Container $container -Password $password -Database $fixtureDatabase `
                -Sql "CREATE DATABASE $launcherDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
            $portBinding = (Invoke-DockerCommand -Arguments @(
                'port', $container, '3306/tcp'
            ) | Select-Object -Last 1).Trim()
            $hostPort = [int]($portBinding -replace '^.*:', '')
            $jdbcUrl = "jdbc:mysql://127.0.0.1:${hostPort}/${launcherDatabase}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"

            if ($RunLauncherValidate) {
                Start-And-ValidateLauncher -JarPath $LauncherJar -JdbcUrl $jdbcUrl -Password $password `
                    -Profile 'migration-schema-bootstrap' -DdlAuto 'create' `
                    -Label "current launcher schema bootstrap on $actualVersion"

                $downgradeSql = @'
DROP TABLE IF EXISTS codex_runtime_revisions;
ALTER TABLE codex_tasks
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
                Start-And-ValidateLauncher -JarPath $LauncherJar -JdbcUrl $jdbcUrl -Password $password `
                    -Profile 'prod' -DdlAuto 'validate' `
                    -Label "current launcher prod validate on $actualVersion"
            }

            if ($NMinusOneLauncherJar) {
                Start-And-ValidateLauncher -JarPath $NMinusOneLauncherJar -JdbcUrl $jdbcUrl -Password $password `
                    -Profile 'prod' -DdlAuto 'validate' `
                    -Label "N-1 launcher prod validate on $actualVersion"
            }
        }
    }
    finally {
        & docker rm --force $container *> $null
        Write-Host "[INFO] Removed isolated container $container"
    }
}

Write-Host '[PASS] codex runtime affinity migration harness completed'
