import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import { createServer } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

test('operations scripts use isolated paths, port 3062, hidden start, and shutdown grace budget', () => {
  const startPs = fs.readFileSync('start.ps1', 'utf8')
  const stopPs = fs.readFileSync('stop.ps1', 'utf8')
  const startSh = fs.readFileSync('start.sh', 'utf8')
  const stopSh = fs.readFileSync('stop.sh', 'utf8')
  const updatePs = fs.readFileSync('update.ps1', 'utf8')
  const updateSh = fs.readFileSync('update.sh', 'utf8')
  const dotenvReader = fs.readFileSync('scripts/read-dotenv-value.mjs', 'utf8')
  const processTreeHelper = fs.readFileSync('scripts/process-tree.mjs', 'utf8')
  assert.match(startPs, /WindowStyle Hidden/)
  assert.match(startPs, /logs\\run/)
  assert.match(startPs, /3062/)
  assert.match(startSh, /logs\/run/)
  assert.match(startSh, /3062/)
  assert.match(stopPs, /CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS/)
  assert.match(stopPs, /ShutdownTimeoutMs \+ 5000/)
  assert.match(stopSh, /shutdown_timeout_ms \+ 5000/)
  assert.match(startPs, /CODEX_APP_SERVER_RUN_DIR/)
  assert.match(startSh, /CODEX_APP_SERVER_RUN_DIR/)
  assert.match(startPs, /CODEX_APP_SERVER_LOG_DIR/)
  assert.match(startSh, /CODEX_APP_SERVER_LOG_DIR/)
  assert.ok(startPs.indexOf('function Read-DotEnvValue') < startPs.indexOf('$RunDir ='))
  assert.ok(startSh.indexOf('read_env_value()') < startSh.indexOf('RUN_DIR='))
  assert.match(startPs, /Read-DotEnvValue 'CODEX_APP_SERVER_STATE_DIR'/)
  assert.match(startSh, /read_env_value CODEX_APP_SERVER_STATE_DIR/)
  const configBindings = [
    ['CODEX_APP_SERVER_RUN_DIR', 'RunDir', 'EnvRunDir', 'RUN_DIR', 'dotenv_run_dir'],
    ['CODEX_APP_SERVER_LOG_DIR', 'LogDir', 'EnvLogDir', 'LOG_DIR', 'dotenv_log_dir'],
    ['CODEX_APP_SERVER_STATE_DIR', 'StateDir', 'EnvStateDir', 'STATE_DIR', 'dotenv_state_dir'],
    ['CODEX_APP_SERVER_WORKER_HOST', 'DisplayHost', 'EnvHost', 'display_host', 'dotenv_host'],
    ['CODEX_APP_SERVER_WORKER_PORT', 'DisplayPort', 'EnvPort', 'display_port', 'dotenv_port'],
  ] as const
  for (const script of [startPs, stopPs, updatePs]) {
    assert.ok(script.indexOf('function Read-DotEnvValue') < script.indexOf('$RunDir ='))
    for (const [key, psTarget, psDotenv] of configBindings) {
      assert.match(script, new RegExp(`Read-DotEnvValue '${key}'`))
      assert.match(script, new RegExp(`\\$env:${key} =`))
      assert.ok(script.includes(`$${psTarget} = Select-ConfigValue $env:${key} $${psDotenv}`))
    }
    assert.doesNotMatch(script, /Invoke-Expression/)
  }
  for (const script of [startSh, stopSh, updateSh]) {
    assert.ok(script.indexOf('read_env_value()') < script.indexOf('RUN_DIR='))
    for (const [key, , , shTarget, shDotenv] of configBindings) {
      assert.match(script, new RegExp(`read_env_value ${key}`))
      assert.match(script, new RegExp(`export ${key}=`))
      const bashProcessValue = '${' + key + ':-}'
      assert.ok(script.includes(`${shTarget}="$(select_config_value "${bashProcessValue}" "$${shDotenv}"`))
    }
    assert.doesNotMatch(script, /^\s*(?:source|\.)\s+.*\.env/m)
    assert.doesNotMatch(script, /\beval\b/)
  }
  assert.match(updatePs, /Test-Running \$InstallDir \$RunDir/)
  assert.match(updateSh, /PID_FILE="\$RUN_DIR\/worker\.pid"/)
  assert.ok(updatePs.indexOf('Validating codex-app-server-worker') < updatePs.indexOf('$RunDir ='))
  assert.ok(updateSh.indexOf('Validating codex-app-server-worker') < updateSh.indexOf('RUN_DIR='))
  assert.ok(updatePs.indexOf('$RunDir =') < updatePs.indexOf('Dry run complete'))
  assert.ok(updateSh.indexOf('RUN_DIR=') < updateSh.indexOf('Dry run complete'))
  assert.match(updatePs, /Join-Path \$Candidate 'scripts\\read-dotenv-value\.mjs'/)
  assert.match(updateSh, /"\$CANDIDATE\/scripts\/read-dotenv-value\.mjs"/)
  assert.doesNotMatch(updateSh, /\b(?:mapfile|readarray)\b/)
  assert.doesNotMatch(updateSh, /\bfind\b[^\n]*(?:-mindepth|-maxdepth)/)
  assert.match(updateSh, /for child in "\$EXTRACT_ROOT"\/\*/)
  assert.match(dotenvReader, /dotenv\.parse/)
  assert.match(startSh, /cd "\$ROOT"/)
  assert.match(startSh, /ready === true/)
  assert.doesNotMatch(stopSh, /\bseq\b/)
  assert.match(startPs, /Invoke-RestMethod/)
  assert.match(startPs, /Health\.ready/)
  assert.match(stopPs, /stop\.request/)
  assert.match(stopSh, /stop\.request/)
  for (const script of [startPs, stopPs, updatePs]) assert.match(script, /stop\.failed/)
  for (const script of [startSh, stopSh, updateSh]) assert.match(script, /stop\.failed/)
  for (const script of [startPs, stopPs, updatePs, startSh, stopSh, updateSh]) {
    assert.match(script, /lifecycle\.lock/)
    assert.match(script, /lock-(?:acquire|verify-owner|release)/)
  }
  for (const script of [startSh, stopSh, updateSh]) {
    assert.match(script, /PRESERVE_LIFECYCLE_LOCK/)
    assert.match(script, /trap 'preserve_lifecycle_lock_on_signal 143' TERM/)
  }
  assert.match(startPs, /LifecycleLockCanRelease/)
  assert.match(startSh, /LIFECYCLE_LOCK_CAN_RELEASE/)
  assert.match(updatePs, /-LifecycleLockNonce \$TransactionNonce/)
  assert.match(updateSh, /--lifecycle-lock-nonce "\$TRANSACTION_NONCE"/)
  assert.match(stopPs, /Remove-EvidenceFile \$SnapshotFile/)
  assert.doesNotMatch(stopPs, /Remove-Item -LiteralPath \$StopFile,\$PidFile[^\n]*SilentlyContinue/)
  assert.match(stopPs, /shutdown\.success/)
  assert.match(stopSh, /shutdown\.success/)
  assert.match(stopPs, /process-tree\.mjs/)
  assert.match(stopSh, /process-tree\.mjs/)
  assert.match(processTreeHelper, /taskkill\.exe/)
  assert.match(processTreeHelper, /SIGSTOP/)
  assert.match(processTreeHelper, /SIGCONT/)
  assert.doesNotMatch(stopPs, /Stop-Process -Id \$PidValue\s*$/m)
  assert.match(stopPs, /does not belong/)
  assert.match(stopSh, /does not belong/)
  assert.match(updatePs, /-NoBuild/)
  assert.match(updateSh, /--no-build/)
  assert.match(updatePs, /if \(\$CandidateStartAttempted\)[\s\S]*automatic rollback is suppressed/)
  assert.match(updateSh, /"\$CANDIDATE_START_ATTEMPTED" == true[\s\S]*automatic rollback is suppressed/)
  assert.match(updatePs, /\$RollbackErrors[\s\S]*\$script:PreserveStageRoot = \$true/)
  assert.match(updateSh, /Rollback restore target is occupied[\s\S]*PRESERVE_STAGE_ROOT=true/)
  assert.match(updatePs, /if \(\$PreserveStageRoot\)[\s\S]*Update staging was preserved/)
  assert.match(updateSh, /if \[\[ "\$PRESERVE_STAGE_ROOT" == true \]\][\s\S]*Update staging was preserved/)
  for (const script of [startPs, stopPs, updatePs]) assert.match(script, /worker\.process-tree\.json/)
  for (const script of [startSh, stopSh, updateSh]) assert.match(script, /worker\.process-tree\.json/)
  assert.match(startPs, /runtime-process-trees/)
  assert.match(startSh, /runtime-process-trees/)
  assert.match(startPs, /lifecycle\.failed/)
  assert.match(startSh, /lifecycle\.failed/)
  assert.match(updatePs, /lifecycle\.failed/)
  assert.match(updateSh, /lifecycle\.failed/)
  assert.match(updatePs, /runtime-process-trees/)
  assert.match(updateSh, /runtime-process-trees/)
  assert.ok(updatePs.indexOf('lifecycle.failed') < updatePs.indexOf('Dry run complete'))
  assert.ok(updateSh.indexOf('lifecycle.failed') < updateSh.indexOf('Dry run complete'))
  assert.doesNotMatch(updatePs, /3052|3061|3162|3262/)
  assert.doesNotMatch(updateSh, /3052|3061|3162|3262/)
})

test('dotenv reader preserves quoted external directories and never evaluates values', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-app-dotenv-reader-'))
  try {
    const envFile = path.join(root, '.env')
    const marker = path.join(root, 'must-not-exist')
    const expectedStateDir = String.raw`C:\Runtime State\codex #1`
    const unsafeValue = `$(touch ${marker})`
    fs.writeFileSync(envFile, [
      `CODEX_APP_SERVER_STATE_DIR="${expectedStateDir}"`,
      `UNSAFE_VALUE=${unsafeValue}`,
      '',
    ].join('\n'))

    const state = spawnSync(process.execPath, [
      'scripts/read-dotenv-value.mjs', envFile, 'CODEX_APP_SERVER_STATE_DIR',
    ], { encoding: 'utf8' })
    assert.equal(state.status, 0, state.stderr)
    assert.equal(state.stdout, expectedStateDir)

    const unsafe = spawnSync(process.execPath, [
      'scripts/read-dotenv-value.mjs', envFile, 'UNSAFE_VALUE',
    ], { encoding: 'utf8' })
    assert.equal(unsafe.status, 0, unsafe.stderr)
    assert.equal(unsafe.stdout, unsafeValue)
    assert.equal(fs.existsSync(marker), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true })
  }
})

test('PowerShell start supports an install path containing spaces and a hash', {
  skip: process.platform !== 'win32',
}, async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app start '))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'runtime run #1')
  const logDir = path.join(root, 'runtime logs #1')
  let workerPid: number | undefined
  let workerEnv: NodeJS.ProcessEnv | undefined
  let holderPid: number | undefined
  const stopGate = path.join(root, 'allow stop')
  const holderReady = path.join(root, 'pid lock ready')
  const holderRelease = path.join(root, 'release pid lock')
  try {
    const port = await reserveLoopbackPort()
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    fs.copyFileSync('start.ps1', path.join(installDir, 'start.ps1'))
    fs.copyFileSync('stop.ps1', path.join(installDir, 'stop.ps1'))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const server = http.createServer((request, response) => {
  response.setHeader('content-type', 'application/json')
  response.end(JSON.stringify({ ready: request.url === '/health' }))
})

server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
const monitor = setInterval(() => {
  if (!fs.existsSync(path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request'))) return
  if (process.env.CAW_TEST_STOP_GATE && !fs.existsSync(process.env.CAW_TEST_STOP_GATE)) return
  clearInterval(monitor)
  const requestFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  const requestId = fs.readFileSync(requestFile, 'utf8').trim()
  fs.rmSync(requestFile, { force: true })
  const successFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'shutdown.success')
  const temporary = successFile + '.tmp'
  fs.writeFileSync(temporary, requestId)
  fs.renameSync(temporary, successFile)
  server.close(() => process.exit(0))
}, 20)
`)

    workerEnv = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: path.join(root, 'runtime state #1'),
      CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
      CODEX_APP_SERVER_WORKER_PORT: String(port),
      CAW_TEST_STOP_GATE: stopGate,
    }
    const started = spawnSync('powershell.exe', [
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, 'start.ps1'), '-NoBuild',
    ], {
      stdio: 'ignore',
      timeout: 30_000,
      env: workerEnv,
    })
    assert.equal(started.status, 0, started.error?.message)
    workerPid = Number(fs.readFileSync(path.join(runDir, 'worker.pid'), 'utf8'))
    assert.ok(Number.isInteger(workerPid) && workerPid > 0)
    process.kill(workerPid, 0)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), true)
    const duplicateStart = spawnSync('powershell.exe', [
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, 'start.ps1'), '-NoBuild',
    ], { encoding: 'utf8', timeout: 10_000, env: workerEnv })
    assert.notEqual(duplicateStart.status, 0)
    assert.match(duplicateStart.stderr, /already running/i)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)
    process.kill(workerPid, 0)

    const stopChild = runCommandAsync('powershell.exe', [
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, 'stop.ps1'),
    ], workerEnv)
    await waitForFile(path.join(runDir, 'stop.request'))
    const quote = (value: string) => value.replaceAll("'", "''")
    const holder = spawn('powershell.exe', ['-NoProfile', '-Command', [
      `$stream = [IO.File]::Open('${quote(path.join(runDir, 'worker.pid'))}', [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)`,
      `Set-Content -LiteralPath '${quote(holderReady)}' -Value ready -NoNewline`,
      `while (-not (Test-Path -LiteralPath '${quote(holderRelease)}')) { Start-Sleep -Milliseconds 20 }`,
      '$stream.Dispose()',
    ].join('; ')], { stdio: 'ignore' })
    holderPid = holder.pid
    assert.ok(holderPid)
    await waitForFile(holderReady)
    fs.writeFileSync(stopGate, 'go')
    const cleanupFailure = await stopChild
    assert.equal(cleanupFailure.status, 3, cleanupFailure.stderr)
    assert.doesNotMatch(cleanupFailure.stdout, /codex-app-server-worker stopped/i)
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /shutdown_cleanup_failed/)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), true)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    fs.writeFileSync(holderRelease, 'release')
    await waitForProcessExit(holderPid)
    holderPid = undefined
    await waitForProcessExit(workerPid)
  } finally {
    if (holderPid && isProcessAlive(holderPid)) {
      fs.writeFileSync(holderRelease, 'release')
      spawnSync('taskkill.exe', ['/PID', String(holderPid), '/T', '/F'], { stdio: 'ignore' })
    }
    if (workerPid && workerEnv) {
      const stopped = spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, 'stop.ps1'),
      ], { stdio: 'ignore', timeout: 10_000, env: workerEnv })
      if (stopped.status !== 0) {
        spawnSync('taskkill.exe', ['/PID', String(workerPid), '/T', '/F'])
      }
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('concurrent standalone starts create one Worker and keep PID and snapshot ownership coherent', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex concurrent start #'))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'runtime run #1')
  const logDir = path.join(root, 'runtime logs #1')
  let workerPid: number | undefined
  let firstStart: ReturnType<typeof spawn> | undefined
  try {
    const port = await reserveLoopbackPort()
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    for (const script of ['start.ps1', 'start.sh', 'stop.ps1', 'stop.sh']) {
      fs.copyFileSync(script, path.join(installDir, script))
    }
    for (const helper of ['process-tree.mjs', 'lifecycle-marker.mjs']) {
      fs.copyFileSync(path.join('scripts', helper), path.join(installDir, 'scripts', helper))
    }
    if (process.platform !== 'win32') {
      for (const script of ['start.sh', 'stop.sh']) fs.chmodSync(path.join(installDir, script), 0o755)
    }
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const server = http.createServer((request, response) => response.end(JSON.stringify({ ready: request.url === '/health' })))
server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
const monitor = setInterval(() => {
  const requestFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  if (!fs.existsSync(requestFile)) return
  clearInterval(monitor)
  const requestId = fs.readFileSync(requestFile, 'utf8').trim()
  fs.rmSync(requestFile, { force: true })
  fs.writeFileSync(path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'shutdown.success'), requestId)
  server.close(() => process.exit(0))
}, 20)
`)
    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: path.join(root, 'runtime state #1'),
      CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
      CODEX_APP_SERVER_WORKER_PORT: String(port),
    }
    const startScript = path.join(installDir, process.platform === 'win32' ? 'start.ps1' : 'start.sh')
    const startCommand = process.platform === 'win32' ? 'powershell.exe' : 'bash'
    const startArgs = process.platform === 'win32'
      ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', startScript, '-NoBuild']
      : [startScript, '--no-build']
    firstStart = spawn(startCommand, startArgs, { env, stdio: 'ignore' })
    await waitForFile(path.join(installDir, 'lifecycle.lock'))
    const blocked = spawnSync(startCommand, startArgs, { env, encoding: 'utf8', timeout: 20_000 })
    assert.notEqual(blocked.status, 0)
    assert.match(blocked.stderr, /lifecycle operation is locked/i)
    assert.equal(await waitForChildExit(firstStart), 0)

    workerPid = Number(fs.readFileSync(path.join(runDir, 'worker.pid'), 'utf8'))
    assert.ok(Number.isSafeInteger(workerPid) && workerPid > 0)
    const snapshotFile = path.join(runDir, 'worker.process-tree.json')
    const snapshot = JSON.parse(fs.readFileSync(snapshotFile, 'utf8')) as { root_pid: number }
    assert.equal(snapshot.root_pid, workerPid)
    const identity = spawnSync(process.execPath, [
      path.join(installDir, 'scripts', 'process-tree.mjs'), 'status', '--pid', String(workerPid),
      '--entry', path.join(installDir, 'dist', 'index.js'), '--output', snapshotFile,
    ], { encoding: 'utf8', timeout: 20_000 })
    assert.equal(identity.status, 10, identity.stderr)

    const stopScript = path.join(installDir, process.platform === 'win32' ? 'stop.ps1' : 'stop.sh')
    const stopped = process.platform === 'win32'
      ? spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript], { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', [stopScript], { env, encoding: 'utf8', timeout: 20_000 })
    assert.equal(stopped.status, 0, stopped.stderr)
    await waitForProcessExit(workerPid)
    for (const residue of [
      path.join(installDir, 'lifecycle.lock'),
      path.join(runDir, 'worker.pid'),
      snapshotFile,
      path.join(runDir, 'stop.request'),
      path.join(runDir, 'shutdown.success'),
      path.join(runDir, 'shutdown.failure'),
      path.join(runDir, 'stop.failed'),
    ]) assert.equal(fs.existsSync(residue), false, `unexpected residue: ${residue}`)
  } finally {
    if (firstStart?.pid && firstStart.exitCode === null) {
      if (process.platform === 'win32') spawnSync('taskkill.exe', ['/PID', String(firstStart.pid), '/T', '/F'], { stdio: 'ignore' })
      else { try { firstStart.kill('SIGKILL') } catch {} }
    }
    if (!workerPid) {
      const pidFile = path.join(runDir, 'worker.pid')
      if (fs.existsSync(pidFile)) workerPid = Number(fs.readFileSync(pidFile, 'utf8'))
    }
    if (workerPid && isProcessAlive(workerPid)) {
      if (process.platform === 'win32') spawnSync('taskkill.exe', ['/PID', String(workerPid), '/T', '/F'], { stdio: 'ignore' })
      else { try { process.kill(workerPid, 'SIGKILL') } catch {} }
      await waitForProcessExit(workerPid).catch(() => undefined)
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 })
  }
})

test('Bash SIGTERM preserves the owned lifecycle lock', { skip: process.platform === 'win32' }, async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex bash signal lock #'))
  const installDir = path.join(root, 'install')
  const fakeBin = path.join(root, 'fake-bin')
  const npmPidFile = path.join(root, 'npm.pid')
  let startProcess: ReturnType<typeof spawn> | undefined
  let npmPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    fs.mkdirSync(fakeBin, { recursive: true })
    fs.copyFileSync('start.sh', path.join(installDir, 'start.sh'))
    for (const helper of ['process-tree.mjs', 'lifecycle-marker.mjs']) {
      fs.copyFileSync(path.join('scripts', helper), path.join(installDir, 'scripts', helper))
    }
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), 'setInterval(() => {}, 1000)\n')
    const fakeNpm = path.join(fakeBin, 'npm')
    fs.writeFileSync(fakeNpm, `#!/usr/bin/env bash\nprintf '%s' "$$" > "$CAW_SIGNAL_NPM_PID"\nwhile :; do sleep 1; done\n`)
    fs.chmodSync(path.join(installDir, 'start.sh'), 0o755)
    fs.chmodSync(fakeNpm, 0o755)
    const env = {
      ...process.env,
      PATH: `${fakeBin}${path.delimiter}${process.env.PATH ?? ''}`,
      CAW_SIGNAL_NPM_PID: npmPidFile,
      CODEX_APP_SERVER_RUN_DIR: path.join(root, 'run'),
      CODEX_APP_SERVER_LOG_DIR: path.join(root, 'logs'),
      CODEX_APP_SERVER_STATE_DIR: path.join(root, 'state'),
    }
    startProcess = spawn('bash', [path.join(installDir, 'start.sh')], {
      env,
      detached: true,
      stdio: 'ignore',
    })
    await waitForFile(path.join(installDir, 'lifecycle.lock'))
    npmPid = Number(await waitForFile(npmPidFile))
    assert.ok(startProcess.pid)
    process.kill(-startProcess.pid, 'SIGTERM')
    const exitStatus = await waitForChildExitWithin(startProcess, 10_000)
    assert.notEqual(exitStatus, 0)
    await waitForProcessExit(npmPid)

    const lockFile = path.join(installDir, 'lifecycle.lock')
    assert.equal(fs.existsSync(lockFile), true)
    assert.equal(JSON.parse(fs.readFileSync(lockFile, 'utf8')).operation, 'start')
    const blocked = spawnSync('bash', [path.join(installDir, 'start.sh'), '--no-build'], {
      env, encoding: 'utf8', timeout: 10_000,
    })
    assert.notEqual(blocked.status, 0)
    assert.match(blocked.stderr, /lifecycle operation is locked/i)
  } finally {
    if (startProcess?.pid && startProcess.exitCode === null && startProcess.signalCode === null) {
      try { process.kill(-startProcess.pid, 'SIGKILL') } catch {}
    }
    if (npmPid && isProcessAlive(npmPid)) {
      try { process.kill(npmPid, 'SIGKILL') } catch {}
      await waitForProcessExit(npmPid).catch(() => undefined)
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 })
  }
})

test('forced stop kills the Worker process tree and refuses a package swap', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app forced stop #'))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'runtime run #1')
  const childPidFile = path.join(root, 'child.pid')
  let parentPid: number | undefined
  let childPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    fs.mkdirSync(runDir, { recursive: true })
    const stopScript = process.platform === 'win32' ? 'stop.ps1' : 'stop.sh'
    const startScript = process.platform === 'win32' ? 'start.ps1' : 'start.sh'
    fs.copyFileSync(stopScript, path.join(installDir, stopScript))
    fs.copyFileSync(startScript, path.join(installDir, startScript))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') {
      fs.chmodSync(path.join(installDir, stopScript), 0o755)
      fs.chmodSync(path.join(installDir, startScript), 0o755)
    }
    const entry = path.join(installDir, 'dist', 'index.js')
    fs.writeFileSync(entry, `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
  stdio: 'ignore',
  windowsHide: true,
})
fs.writeFileSync(process.env.CAW_CHILD_PID_FILE, String(child.pid))
setInterval(() => {}, 1000)
`)
    const worker = spawn(process.execPath, [entry], {
      env: { ...process.env, CAW_CHILD_PID_FILE: childPidFile },
      stdio: 'ignore',
      windowsHide: true,
    })
    parentPid = worker.pid
    assert.ok(parentPid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(parentPid))
    childPid = Number(await waitForFile(childPidFile))
    assert.ok(Number.isInteger(childPid) && childPid > 0)
    captureLifecycleSnapshot(entry, parentPid, runDir)

    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS: '0',
    }
    const stopped = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, stopScript),
      ], { env, encoding: 'utf8', timeout: 15_000 })
      : spawnSync('bash', [path.join(installDir, stopScript)], {
        env, encoding: 'utf8', timeout: 15_000,
      })

    assert.equal(stopped.status, 2, stopped.stderr || stopped.stdout)
    await waitForProcessExit(parentPid)
    await waitForProcessExit(childPid)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.request')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), true)

    const refusedStart = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild',
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [path.join(installDir, startScript), '--no-build'], {
        env, encoding: 'utf8', timeout: 10_000,
      })
    assert.notEqual(refusedStart.status, 0)
    assert.match(refusedStart.stderr, /stop\.failed|failed stop/i)
  } finally {
    for (const pid of [childPid, parentPid]) {
      if (!pid || !isProcessAlive(pid)) continue
      if (process.platform === 'win32') {
        spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        try { process.kill(pid, 'SIGKILL') } catch {}
      }
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('failed self-shutdown cannot masquerade as a graceful stop or orphan its child', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app failed shutdown #'))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'runtime run #1')
  const childPidFile = path.join(root, 'child.pid')
  let parentPid: number | undefined
  let childPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    fs.mkdirSync(runDir, { recursive: true })
    const stopScript = process.platform === 'win32' ? 'stop.ps1' : 'stop.sh'
    fs.copyFileSync(stopScript, path.join(installDir, stopScript))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, stopScript), 0o755)
    const entry = path.join(installDir, 'dist', 'index.js')
    fs.writeFileSync(entry, `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
  stdio: 'ignore',
  windowsHide: true,
})
fs.writeFileSync(process.env.CAW_CHILD_PID_FILE, String(child.pid))
const monitor = setInterval(() => {
  const stopFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  if (!fs.existsSync(stopFile)) return
  clearInterval(monitor)
  fs.rmSync(stopFile, { force: true })
  process.exit(1)
}, 10)
`)
    const worker = spawn(process.execPath, [entry], {
      env: {
        ...process.env,
        CAW_CHILD_PID_FILE: childPidFile,
        CODEX_APP_SERVER_RUN_DIR: runDir,
      },
      stdio: 'ignore',
      windowsHide: true,
    })
    parentPid = worker.pid
    assert.ok(parentPid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(parentPid))
    childPid = Number(await waitForFile(childPidFile))
    assert.ok(Number.isInteger(childPid) && childPid > 0)
    captureLifecycleSnapshot(entry, parentPid, runDir)

    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS: '100',
    }
    const stopped = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, stopScript),
      ], { env, encoding: 'utf8', timeout: 15_000 })
      : spawnSync('bash', [path.join(installDir, stopScript)], {
        env, encoding: 'utf8', timeout: 15_000,
      })

    assert.equal(stopped.status, 2, stopped.stderr || stopped.stdout)
    await waitForProcessExit(parentPid)
    await waitForProcessExit(childPid)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), true)
    assert.equal(fs.existsSync(path.join(runDir, 'shutdown.success')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), true)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
  } finally {
    for (const pid of [childPid, parentPid]) {
      if (!pid || !isProcessAlive(pid)) continue
      if (process.platform === 'win32') {
        spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        try { process.kill(pid, 'SIGKILL') } catch {}
      }
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('startup failure clears the verified Worker process tree before returning', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app failed startup #'))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'runtime run #1')
  const logDir = path.join(root, 'runtime logs #1')
  const childPidFile = path.join(root, 'child.pid')
  let childPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    const startScript = process.platform === 'win32' ? 'start.ps1' : 'start.sh'
    fs.copyFileSync(startScript, path.join(installDir, startScript))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, startScript), 0o755)
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
  stdio: 'ignore', windowsHide: true,
})
fs.writeFileSync(process.env.CAW_CHILD_PID_FILE, String(child.pid))
setTimeout(() => process.exit(1), 1500)
`)
    const env = {
      ...process.env,
      CAW_CHILD_PID_FILE: childPidFile,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: path.join(root, 'runtime state #1'),
      CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
      CODEX_APP_SERVER_WORKER_PORT: String(await reserveLoopbackPort()),
    }
    const started = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild',
      ], { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', [path.join(installDir, startScript), '--no-build'], {
        env, encoding: 'utf8', timeout: 20_000,
      })

    assert.notEqual(started.status, 0)
    childPid = Number(await waitForFile(childPidFile))
    assert.ok(Number.isInteger(childPid) && childPid > 0)
    const retainedSnapshot = path.join(runDir, 'worker.process-tree.json')
    const verified = spawnSync(process.execPath, [
      path.resolve('scripts/process-tree.mjs'),
      'verify', '--output', retainedSnapshot,
    ], { encoding: 'utf8', timeout: 20_000 })
    assert.equal(verified.status, 0, verified.stderr)
    if (process.platform === 'win32') await waitForProcessExit(childPid)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)
    assert.equal(fs.existsSync(retainedSnapshot), true)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
  } finally {
    if (childPid && isProcessAlive(childPid)) {
      if (process.platform === 'win32') {
        spawnSync('taskkill.exe', ['/PID', String(childPid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        try { process.kill(childPid, 'SIGKILL') } catch {}
      }
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  }
})

test('stop fails closed when a live Worker has no persisted identity snapshot', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app missing identity #'))
  const installDir = path.join(root, 'install')
  const runDir = path.join(root, 'run')
  let workerPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    fs.mkdirSync(runDir, { recursive: true })
    const stopScript = process.platform === 'win32' ? 'stop.ps1' : 'stop.sh'
    fs.copyFileSync(stopScript, path.join(installDir, stopScript))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, stopScript), 0o755)
    const entry = path.join(installDir, 'dist', 'index.js')
    fs.writeFileSync(entry, 'setInterval(() => {}, 1000)\n')
    const worker = spawn(process.execPath, [entry], { stdio: 'ignore', windowsHide: true })
    workerPid = worker.pid
    assert.ok(workerPid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(workerPid))

    const env = { ...process.env, CODEX_APP_SERVER_RUN_DIR: runDir }
    const stopped = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, stopScript),
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [path.join(installDir, stopScript)], { env, encoding: 'utf8', timeout: 10_000 })

    assert.equal(stopped.status, 4, stopped.stderr || stopped.stdout)
    assert.equal(isProcessAlive(workerPid), true)
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /worker_identity_snapshot_missing/)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), true)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
  } finally {
    if (workerPid && isProcessAlive(workerPid)) {
      if (process.platform === 'win32') {
        spawnSync('taskkill.exe', ['/PID', String(workerPid), '/T', '/F'], { stdio: 'ignore' })
      } else {
        try { process.kill(workerPid, 'SIGKILL') } catch {}
      }
      await waitForProcessExit(workerPid).catch(() => undefined)
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  }
})

test('start refuses unresolved runtime process-tree evidence without spawning a Worker', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app runtime evidence #'))
  const installDir = path.join(root, 'install')
  const runDir = path.join(root, 'run')
  const stateDir = path.join(root, 'state')
  const spawnMarker = path.join(root, 'spawned.txt')
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    const startScript = process.platform === 'win32' ? 'start.ps1' : 'start.sh'
    fs.copyFileSync(startScript, path.join(installDir, startScript))
    fs.copyFileSync('scripts/process-tree.mjs', path.join(installDir, 'scripts', 'process-tree.mjs'))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, startScript), 0o755)
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
require('node:fs').writeFileSync(${JSON.stringify(spawnMarker)}, 'spawned')
setInterval(() => {}, 1000)
`)
    const evidenceFile = path.join(stateDir, 'runtime-process-trees', 'instance-1', 'capture.failure')
    fs.mkdirSync(path.dirname(evidenceFile), { recursive: true })
    fs.writeFileSync(evidenceFile, 'PROCESS_TREE_CAPTURE_FAILED\n')
    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_STATE_DIR: stateDir,
    }
    const started = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild',
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [path.join(installDir, startScript), '--no-build'], {
        env, encoding: 'utf8', timeout: 10_000,
      })

    assert.notEqual(started.status, 0)
    assert.match(started.stderr, /runtime-process-trees|process-tree evidence/i)
    assert.equal(fs.existsSync(spawnMarker), false)
    assert.equal(fs.existsSync(evidenceFile), true)
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /runtime_process_tree_evidence_present/)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
  } finally {
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 })
  }
})

test('initial snapshot and run latch failure persist state fallback and clean the discovered tree', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app snapshot fallback #'))
  const installDir = path.join(root, 'install')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const parentPidFile = path.join(root, 'parent.pid')
  const childPidFile = path.join(root, 'child.pid')
  const grandchildPidFile = path.join(root, 'grandchild.pid')
  const spawnCountFile = path.join(root, 'spawn-count.txt')
  const observedPids: number[] = []
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    const startScript = process.platform === 'win32' ? 'start.ps1' : 'start.sh'
    fs.copyFileSync(startScript, path.join(installDir, startScript))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, startScript), 0o755)
    fs.writeFileSync(path.join(installDir, 'scripts', 'process-tree.mjs'), 'await new Promise(resolve => setTimeout(resolve, 500))\nprocess.exit(64)\n')
    const childEntry = path.join(installDir, 'dist', 'child.js')
    fs.writeFileSync(childEntry, `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const grandchild = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore', windowsHide: true })
fs.writeFileSync(${JSON.stringify(grandchildPidFile)}, String(grandchild.pid))
setInterval(() => {}, 1000)
`)
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
fs.writeFileSync(${JSON.stringify(parentPidFile)}, String(process.pid))
const count = fs.existsSync(${JSON.stringify(spawnCountFile)}) ? Number(fs.readFileSync(${JSON.stringify(spawnCountFile)}, 'utf8')) : 0
fs.writeFileSync(${JSON.stringify(spawnCountFile)}, String(count + 1))
fs.mkdirSync(require('node:path').join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.failed'), { recursive: true })
const child = spawn(process.execPath, [${JSON.stringify(childEntry)}], { stdio: 'ignore', windowsHide: true })
fs.writeFileSync(${JSON.stringify(childPidFile)}, String(child.pid))
setInterval(() => {}, 1000)
`)
    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: stateDir,
    }
    const started = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild',
      ], { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', [path.join(installDir, startScript), '--no-build'], {
        env, encoding: 'utf8', timeout: 20_000,
      })
    assert.notEqual(started.status, 0)
    for (const file of [parentPidFile, childPidFile, grandchildPidFile]) {
      observedPids.push(Number(await waitForFile(file)))
    }
    for (const pid of observedPids) await waitForProcessExit(pid)
    assert.equal(fs.readFileSync(path.join(stateDir, 'lifecycle.failed'), 'utf8'), 'WORKER_START_IDENTITY_NOT_PROVEN\n')
    assert.equal(fs.statSync(path.join(runDir, 'stop.failed')).isDirectory(), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)

    fs.rmSync(path.join(runDir, 'stop.failed'), { recursive: true, force: true })
    const refused = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild',
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [path.join(installDir, startScript), '--no-build'], {
        env, encoding: 'utf8', timeout: 10_000,
      })
    assert.notEqual(refused.status, 0)
    assert.match(refused.stderr, /lifecycle\.failed|lifecycle evidence/i)
    assert.equal(fs.readFileSync(spawnCountFile, 'utf8'), '1')
  } finally {
    for (const pid of observedPids) {
      if (!isProcessAlive(pid)) continue
      if (process.platform === 'win32') spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
      else { try { process.kill(pid, 'SIGKILL') } catch {} }
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 })
  }
})

test('initial snapshot failure retains the lifecycle lock when no durable failure evidence can be written', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex snapshot no evidence #'))
  const installDir = path.join(root, 'install')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const workerPidFile = path.join(root, 'worker.pid.observed')
  const spawnCountFile = path.join(root, 'spawn-count.txt')
  let workerPid: number | undefined
  try {
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    const startScript = process.platform === 'win32' ? 'start.ps1' : 'start.sh'
    fs.copyFileSync(startScript, path.join(installDir, startScript))
    fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(installDir, 'scripts', 'lifecycle-marker.mjs'))
    fs.writeFileSync(path.join(installDir, 'scripts', 'process-tree.mjs'), 'await new Promise(resolve => setTimeout(resolve, 500))\nprocess.exit(64)\n')
    if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, startScript), 0o755)
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const fs = require('node:fs')
const path = require('node:path')
fs.writeFileSync(${JSON.stringify(workerPidFile)}, String(process.pid))
const count = fs.existsSync(${JSON.stringify(spawnCountFile)}) ? Number(fs.readFileSync(${JSON.stringify(spawnCountFile)}, 'utf8')) : 0
fs.writeFileSync(${JSON.stringify(spawnCountFile)}, String(count + 1))
fs.mkdirSync(path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.failed'), { recursive: true })
fs.mkdirSync(path.join(process.env.CODEX_APP_SERVER_STATE_DIR, 'lifecycle.failed'), { recursive: true })
setInterval(() => {}, 1000)
`)
    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: stateDir,
    }
    const startArgs = process.platform === 'win32'
      ? ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(installDir, startScript), '-NoBuild']
      : [path.join(installDir, startScript), '--no-build']
    const started = process.platform === 'win32'
      ? spawnSync('powershell.exe', startArgs, { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', startArgs, { env, encoding: 'utf8', timeout: 20_000 })
    assert.notEqual(started.status, 0)
    workerPid = Number(await waitForFile(workerPidFile))
    await waitForProcessExit(workerPid)
    assert.equal(fs.statSync(path.join(runDir, 'stop.failed')).isDirectory(), true)
    assert.equal(fs.statSync(path.join(stateDir, 'lifecycle.failed')).isDirectory(), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), false)
    const lockFile = path.join(installDir, 'lifecycle.lock')
    assert.equal(fs.existsSync(lockFile), true)
    assert.equal(JSON.parse(fs.readFileSync(lockFile, 'utf8')).operation, 'start')

    const refused = process.platform === 'win32'
      ? spawnSync('powershell.exe', startArgs, { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', startArgs, { env, encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(refused.status, 0)
    assert.match(refused.stderr, /lifecycle operation is locked/i)
    assert.equal(fs.readFileSync(spawnCountFile, 'utf8'), '1')
  } finally {
    if (workerPid && isProcessAlive(workerPid)) {
      if (process.platform === 'win32') spawnSync('taskkill.exe', ['/PID', String(workerPid), '/T', '/F'], { stdio: 'ignore' })
      else { try { process.kill(workerPid, 'SIGKILL') } catch {} }
      await waitForProcessExit(workerPid).catch(() => undefined)
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 })
  }
})

test('update transaction marker blocks non-owner start and stop while allowing its owner', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex app transaction owner #'))
  const installDir = path.join(root, 'install')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const nonce = 'c'.repeat(32)
  const otherOwnerNonce = 'd'.repeat(32)
  let workerPid: number | undefined
  try {
    const port = await reserveLoopbackPort()
    fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
    fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
    for (const script of ['start.ps1', 'start.sh', 'stop.ps1', 'stop.sh']) {
      fs.copyFileSync(script, path.join(installDir, script))
    }
    for (const helper of ['process-tree.mjs', 'lifecycle-marker.mjs']) {
      fs.copyFileSync(path.join('scripts', helper), path.join(installDir, 'scripts', helper))
    }
    if (process.platform !== 'win32') {
      for (const script of ['start.sh', 'stop.sh']) fs.chmodSync(path.join(installDir, script), 0o755)
    }
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const server = http.createServer((request, response) => response.end(JSON.stringify({ ready: request.url === '/health' })))
server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
const monitor = setInterval(() => {
  const requestFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  if (!fs.existsSync(requestFile)) return
  clearInterval(monitor)
  const nonce = fs.readFileSync(requestFile, 'utf8').trim()
  fs.rmSync(requestFile, { force: true })
  fs.writeFileSync(path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'shutdown.success'), nonce)
  server.close(() => process.exit(0))
}, 20)
`)
    const marker = path.join(installDir, 'update.in-progress')
    const lifecycleLock = path.join(installDir, 'lifecycle.lock')
    const created = spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'create', '--path', marker,
      '--nonce', nonce, '--stage-root', path.join(root, '.caw-0123456789ab'),
    ], { encoding: 'utf8' })
    assert.equal(created.status, 0, created.stderr)
    const acquired = spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-acquire', '--path', lifecycleLock,
      '--nonce', otherOwnerNonce, '--operation', 'update',
    ], { encoding: 'utf8' })
    assert.equal(acquired.status, 0, acquired.stderr)
    const env = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: stateDir,
      CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
      CODEX_APP_SERVER_WORKER_PORT: String(port),
    }
    const startScript = path.join(installDir, process.platform === 'win32' ? 'start.ps1' : 'start.sh')
    const stopScript = path.join(installDir, process.platform === 'win32' ? 'stop.ps1' : 'stop.sh')
    const blockedStart = process.platform === 'win32'
      ? spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', startScript, '-NoBuild'], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [startScript, '--no-build'], { env, encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(blockedStart.status, 0)
    assert.match(blockedStart.stderr, /lifecycle operation is locked/i)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)

    const mismatchedStart = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', startScript, '-NoBuild',
        '-UpdateTransactionNonce', nonce, '-LifecycleLockNonce', otherOwnerNonce,
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [
        startScript, '--no-build', '--update-transaction-nonce', nonce,
        '--lifecycle-lock-nonce', otherOwnerNonce,
      ], { env, encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(mismatchedStart.status, 0)
    assert.match(mismatchedStart.stderr, /matching update transaction nonce/i)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)

    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-release', '--path', lifecycleLock,
      '--nonce', otherOwnerNonce,
    ], { encoding: 'utf8' }).status, 0)
    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-acquire', '--path', lifecycleLock,
      '--nonce', nonce, '--operation', 'update',
    ], { encoding: 'utf8' }).status, 0)

    const ownedStart = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', startScript, '-NoBuild',
        '-UpdateTransactionNonce', nonce, '-LifecycleLockNonce', nonce,
      ], { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', [
        startScript, '--no-build', '--update-transaction-nonce', nonce, '--lifecycle-lock-nonce', nonce,
      ], { env, encoding: 'utf8', timeout: 20_000 })
    assert.equal(ownedStart.status, 0, ownedStart.stderr)
    workerPid = Number(fs.readFileSync(path.join(runDir, 'worker.pid'), 'utf8'))
    assert.ok(workerPid > 0)
    assert.equal(fs.existsSync(lifecycleLock), true)

    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-release', '--path', lifecycleLock,
      '--nonce', nonce,
    ], { encoding: 'utf8' }).status, 0)
    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-acquire', '--path', lifecycleLock,
      '--nonce', otherOwnerNonce, '--operation', 'update',
    ], { encoding: 'utf8' }).status, 0)

    const blockedStop = process.platform === 'win32'
      ? spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [stopScript], { env, encoding: 'utf8', timeout: 10_000 })
    assert.equal(blockedStop.status, 5)
    assert.equal(isProcessAlive(workerPid), true)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)

    const mismatchedStop = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript,
        '-UpdateTransactionNonce', nonce, '-LifecycleLockNonce', otherOwnerNonce,
      ], { env, encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [
        stopScript, '--update-transaction-nonce', nonce, '--lifecycle-lock-nonce', otherOwnerNonce,
      ], { env, encoding: 'utf8', timeout: 10_000 })
    assert.equal(mismatchedStop.status, 5)
    assert.match(mismatchedStop.stderr, /matching update transaction nonce/i)
    assert.equal(isProcessAlive(workerPid), true)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)

    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-release', '--path', lifecycleLock,
      '--nonce', otherOwnerNonce,
    ], { encoding: 'utf8' }).status, 0)
    assert.equal(spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-acquire', '--path', lifecycleLock,
      '--nonce', nonce, '--operation', 'update',
    ], { encoding: 'utf8' }).status, 0)

    const ownedStop = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript,
        '-UpdateTransactionNonce', nonce, '-LifecycleLockNonce', nonce,
      ], { env, encoding: 'utf8', timeout: 20_000 })
      : spawnSync('bash', [
        stopScript, '--update-transaction-nonce', nonce, '--lifecycle-lock-nonce', nonce,
      ], { env, encoding: 'utf8', timeout: 20_000 })
    assert.equal(ownedStop.status, 0, ownedStop.stderr)
    await waitForProcessExit(workerPid)
    assert.equal(fs.existsSync(lifecycleLock), true)
    const removed = spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'remove', '--path', marker, '--nonce', nonce,
    ], { encoding: 'utf8' })
    assert.equal(removed.status, 0, removed.stderr)
    const released = spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'lock-release', '--path', lifecycleLock, '--nonce', nonce,
    ], { encoding: 'utf8' })
    assert.equal(released.status, 0, released.stderr)
  } finally {
    if (workerPid && isProcessAlive(workerPid)) {
      if (process.platform === 'win32') spawnSync('taskkill.exe', ['/PID', String(workerPid), '/T', '/F'], { stdio: 'ignore' })
      else { try { process.kill(workerPid, 'SIGKILL') } catch {} }
    }
    fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 100 })
  }
})

async function reserveLoopbackPort(): Promise<number> {
  const server = createServer()
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  const port = typeof address === 'object' && address ? address.port : 0
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  if (!port) throw new Error('Failed to reserve a loopback port')
  return port
}

function runCommandAsync(command: string, args: string[], env: NodeJS.ProcessEnv): Promise<{
  status: number | null
  stdout: string
  stderr: string
}> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.once('error', reject)
    child.once('exit', status => resolve({ status, stdout, stderr }))
  })
}

function captureLifecycleSnapshot(entry: string, pid: number, runDir: string): void {
  const result = spawnSync(process.execPath, [
    path.resolve('scripts/process-tree.mjs'),
    'snapshot', '--pid', String(pid), '--entry', entry,
    '--output', path.join(runDir, 'worker.process-tree.json'),
  ], { encoding: 'utf8', timeout: 20_000 })
  assert.equal(result.status, 0, result.stderr)
}

async function waitForFile(file: string): Promise<string> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8').trim()
    await new Promise(resolve => setTimeout(resolve, 20))
  }
  throw new Error(`Timed out waiting for ${file}`)
}

async function waitForProcessExit(pid: number): Promise<void> {
  for (let attempt = 0; attempt < 500; attempt += 1) {
    if (!isProcessAlive(pid)) return
    await new Promise(resolve => setTimeout(resolve, 20))
  }
  throw new Error(`Process ${pid} did not exit`)
}

function waitForChildExit(child: ReturnType<typeof spawn>): Promise<number | null> {
  if (child.exitCode !== null) return Promise.resolve(child.exitCode)
  return new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('exit', resolve)
  })
}

async function waitForChildExitWithin(child: ReturnType<typeof spawn>, timeoutMs: number): Promise<number | null> {
  let timer: NodeJS.Timeout | undefined
  try {
    return await Promise.race([
      waitForChildExit(child),
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(new Error('Timed out waiting for child process')), timeoutMs)
      }),
    ])
  } finally {
    if (timer) clearTimeout(timer)
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    if (process.platform !== 'win32') {
      const state = spawnSync('ps', ['-o', 'stat=', '-p', String(pid)], { encoding: 'utf8' })
      if (state.status === 0 && state.stdout.trim().startsWith('Z')) return false
    }
    return true
  } catch {
    return false
  }
}
