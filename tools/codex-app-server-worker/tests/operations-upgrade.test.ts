import assert from 'node:assert/strict'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import { createServer } from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

const LIFECYCLE_KEYS = [
  'CODEX_APP_SERVER_RUN_DIR',
  'CODEX_APP_SERVER_LOG_DIR',
  'CODEX_APP_SERVER_STATE_DIR',
  'CODEX_APP_SERVER_WORKER_HOST',
  'CODEX_APP_SERVER_WORKER_PORT',
]

test('updater rejects a running 0.1.0 install without touching its process or files', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex first hop #'))
  const installDir = path.join(root, 'legacy install #1')
  const packageContainer = path.join(root, 'package container #1')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'new updater no modules #1')
  const fakeBin = path.join(root, 'fake npm bin #1')
  const runDir = path.join(root, 'external run #1')
  const logDir = path.join(root, 'external logs #1')
  const stateDir = path.join(root, 'external state #1')
  let legacyProcess: ChildProcess | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareFakeNpm(fakeBin)
    prepareLegacyInstall(installDir)
    prepareBootstrapUpdater(bootstrapDir)

    const envFile = path.join(installDir, '.env')
    fs.writeFileSync(envFile, [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    const envHash = fileHash(envFile)

    fs.mkdirSync(runDir, { recursive: true })
    const legacyEnv = {
      ...process.env,
      CODEX_APP_SERVER_RUN_DIR: runDir,
      CODEX_APP_SERVER_LOG_DIR: logDir,
      CODEX_APP_SERVER_STATE_DIR: stateDir,
      CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
      CODEX_APP_SERVER_WORKER_PORT: String(port),
    }
    legacyProcess = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: legacyEnv,
      stdio: 'ignore',
    })
    await waitForHealth(port)
    const legacyPid = legacyProcess.pid
    assert.ok(legacyPid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(legacyPid))

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, 'update.ps1'),
        '-Package', packageContainer, '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, 'update.sh'), '--package', packageContainer, '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })

    assert.notEqual(result.status, 0)
    assert.match(`${result.stdout}\n${result.stderr}`, /0\.1\.0.*not supported|not supported.*0\.1\.0/i)
    assert.equal(fs.existsSync(path.join(bootstrapDir, 'node_modules')), false)
    assert.equal(fs.existsSync(path.join(candidateDir, 'node_modules')), false)
    assert.equal(Number(fs.readFileSync(path.join(runDir, 'worker.pid'), 'utf8')), legacyPid)
    assert.equal(isProcessAlive(legacyPid), true)
    await waitForHealth(port)
    assert.equal(fileHash(envFile), envHash)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.0')
    assert.equal(fs.existsSync(path.join(runDir, 'stop.request')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    const updaterEnv = cleanLifecycleEnvironment()
    const stopScript = path.join(installDir, process.platform === 'win32' ? 'stop.ps1' : 'stop.sh')
    if (fs.existsSync(stopScript)) {
      if (process.platform === 'win32') {
        spawnSync('powershell.exe', [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript,
        ], { env: updaterEnv, stdio: 'ignore', timeout: 10_000 })
      } else {
        spawnSync('bash', [stopScript], { env: updaterEnv, stdio: 'ignore', timeout: 10_000 })
      }
    }
    await forceTerminateAndWait(legacyProcess?.pid)
    removeTemporaryTree(root)
  }
})

test('0.1.0 rejection leaves a running legacy descendant tree untouched', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex unsafe first hop #'))
  const installDir = path.join(root, 'legacy install #1')
  const packageContainer = path.join(root, 'package container #1')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'new updater #1')
  const fakeBin = path.join(root, 'fake npm #1')
  const runDir = path.join(root, 'external run #1')
  const logDir = path.join(root, 'external logs #1')
  const stateDir = path.join(root, 'external state #1')
  const childPidFile = path.join(root, 'legacy-child.pid')
  let legacyProcess: ChildProcess | undefined
  let childPid: number | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareFakeNpm(fakeBin)
    prepareLegacyInstall(installDir)
    fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), `
const { spawn } = require('node:child_process')
const fs = require('node:fs')
const http = require('node:http')
const path = require('node:path')
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
  stdio: 'ignore', windowsHide: true, detached: true,
})
child.unref()
fs.writeFileSync(process.env.CAW_CHILD_PID_FILE, String(child.pid))
const server = http.createServer((request, response) => {
  response.setHeader('content-type', 'application/json')
  response.end(JSON.stringify({ ready: request.url === '/health' }))
})
server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
const monitor = setInterval(() => {
  const stopFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  if (!fs.existsSync(stopFile)) return
  clearInterval(monitor)
  server.close(() => process.exit(0))
}, 10)
`)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    legacyProcess = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: {
        ...process.env,
        CAW_CHILD_PID_FILE: childPidFile,
        CODEX_APP_SERVER_RUN_DIR: runDir,
        CODEX_APP_SERVER_LOG_DIR: logDir,
        CODEX_APP_SERVER_STATE_DIR: stateDir,
        CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
        CODEX_APP_SERVER_WORKER_PORT: String(port),
      },
      stdio: 'ignore',
    })
    await waitForHealth(port)
    assert.ok(legacyProcess.pid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(legacyProcess.pid))
    childPid = Number(await waitForFile(childPidFile))
    assert.ok(Number.isInteger(childPid) && childPid > 0)
    const fixtureSnapshot = path.join(runDir, 'fixture.process-tree.json')
    const fixtureResult = spawnSync(process.execPath, [
      path.resolve('scripts', 'process-tree.mjs'),
      'snapshot', '--pid', String(legacyProcess.pid),
      '--entry', path.join(installDir, 'dist', 'index.js'),
      '--output', fixtureSnapshot,
    ], { encoding: 'utf8', timeout: 10_000 })
    assert.equal(fixtureResult.status, 0, formatSpawnFailure(fixtureResult))
    const fixtureIdentities = JSON.parse(fs.readFileSync(fixtureSnapshot, 'utf8')) as {
      processes: Array<{ pid: number }>
    }
    assert.equal(fixtureIdentities.processes.some(identity => identity.pid === childPid), true)
    fs.rmSync(fixtureSnapshot, { force: true })

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', packageContainer, '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })

    assert.equal(result.error, undefined, formatSpawnFailure(result))
    assert.notEqual(result.status, 0)
    assert.match(`${result.stdout}\n${result.stderr}`, /0\.1\.0.*not supported|not supported.*0\.1\.0/i)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.0')
    assert.equal(isProcessAlive(legacyProcess.pid!), true)
    assert.equal(isProcessAlive(childPid), true)
    assert.equal(Number(fs.readFileSync(path.join(runDir, 'worker.pid'), 'utf8')), legacyProcess.pid)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.request')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
  } finally {
    const recordedPid = readPidFile(path.join(runDir, 'worker.pid'))
    for (const pid of [legacyProcess?.pid, childPid, recordedPid]) await forceTerminateAndWait(pid)
    removeTemporaryTree(root)
  }
})

test('current Worker update commits transaction, removes marker, and restarts cleanly', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex current update #'))
  const installDir = path.join(root, 'current install')
  const candidateDir = path.join(root, 'package', 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'bootstrap')
  const fakeBin = path.join(root, 'fake npm')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  let oldWorker: ChildProcess | undefined
  let newPid: number | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareCurrentInstall(installDir)
    prepareFakeNpm(fakeBin)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    oldWorker = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: {
        ...process.env,
        CODEX_APP_SERVER_RUN_DIR: runDir,
        CODEX_APP_SERVER_LOG_DIR: logDir,
        CODEX_APP_SERVER_STATE_DIR: stateDir,
        CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
        CODEX_APP_SERVER_WORKER_PORT: String(port),
        CAW_TEST_CLEAR_RUNTIME_TREE_ON_STOP: 'true',
      },
      stdio: 'ignore',
    })
    await waitForHealth(port)
    assert.ok(oldWorker.pid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(oldWorker.pid))
    captureLifecycleSnapshot(installDir, runDir, oldWorker.pid)
    const runtimeTreeDir = path.join(stateDir, 'runtime-process-trees', 'idle-lane')
    const runtimeSnapshot = path.join(runtimeTreeDir, 'process-tree.json')
    captureProcessSnapshot(path.join(installDir, 'dist', 'index.js'), oldWorker.pid, runtimeSnapshot)
    assert.equal(fs.existsSync(runtimeSnapshot), true)

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', path.dirname(candidateDir), '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 90_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', path.dirname(candidateDir), '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 90_000 })

    assert.equal(result.status, 0, formatSpawnFailure(result))
    await waitForProcessExit(oldWorker.pid)
    newPid = readPidFile(path.join(runDir, 'worker.pid'))
    assert.ok(newPid && newPid !== oldWorker.pid)
    assert.equal(isProcessAlive(newPid), true)
    await waitForHealth(port)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1')
    assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(fs.existsSync(path.join(stateDir, 'runtime-process-trees')), false)
    assert.equal(findStageDirectories(root).length, 0)
    assert.equal(fs.readdirSync(root).some(name => name.startsWith('.caw-marker-')), false)
  } finally {
    const stopScript = path.join(installDir, process.platform === 'win32' ? 'stop.ps1' : 'stop.sh')
    if (fs.existsSync(stopScript)) {
      if (process.platform === 'win32') {
        spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', stopScript], {
          env: cleanLifecycleEnvironment(), stdio: 'ignore', timeout: 15_000,
        })
      } else {
        spawnSync('bash', [stopScript], { env: cleanLifecycleEnvironment(), stdio: 'ignore', timeout: 15_000 })
      }
    }
    for (const pid of [oldWorker?.pid, newPid]) await forceTerminateAndWait(pid)
    removeTemporaryTree(root)
  }
})

test('running update drains with runtime identity evidence and fails closed when residue remains', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex runtime residue update #'))
  const installDir = path.join(root, 'current install')
  const candidateDir = path.join(root, 'package', 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'bootstrap')
  const fakeBin = path.join(root, 'fake npm')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  let worker: ChildProcess | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareCurrentInstall(installDir)
    prepareFakeNpm(fakeBin)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    worker = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: {
        ...process.env,
        CODEX_APP_SERVER_RUN_DIR: runDir,
        CODEX_APP_SERVER_LOG_DIR: logDir,
        CODEX_APP_SERVER_STATE_DIR: stateDir,
        CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
        CODEX_APP_SERVER_WORKER_PORT: String(port),
      },
      stdio: 'ignore',
    })
    await waitForHealth(port)
    assert.ok(worker.pid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(worker.pid))
    captureLifecycleSnapshot(installDir, runDir, worker.pid)
    const runtimeSnapshot = path.join(stateDir, 'runtime-process-trees', 'idle-lane', 'process-tree.json')
    captureProcessSnapshot(path.join(installDir, 'dist', 'index.js'), worker.pid, runtimeSnapshot)

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', path.dirname(candidateDir), '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 90_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', path.dirname(candidateDir), '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 90_000 })

    assert.notEqual(result.status, 0)
    assert.match(`${result.stdout}\n${result.stderr}`, /drain left runtime process-tree evidence/i)
    await waitForProcessExit(worker.pid)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1-current')
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /update_runtime_process_tree_residue/)
    assert.equal(fs.existsSync(path.join(runDir, 'update.process-tree.json')), true)
    assert.equal(fs.existsSync(runtimeSnapshot), true)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    await forceTerminateAndWait(worker?.pid)
    removeTemporaryTree(root)
  }
})

test('candidate startup failure preserves candidate and backup without restarting the old package', () =>
  runCandidateStartupRollbackScenario(false))

test('candidate startup failure never restarts the old package when its latch cannot be persisted', () =>
  runCandidateStartupRollbackScenario(true))

test('updater rejects a non-empty directory without product identity before staging', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex target identity #'))
  try {
    const installDir = path.join(root, 'unrelated install')
    const marker = path.join(installDir, 'owner-data.txt')
    fs.mkdirSync(installDir, { recursive: true })
    fs.writeFileSync(marker, 'must remain unchanged')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.resolve('update.ps1'),
        '-Package', path.join(root, 'missing-package'), '-InstallDir', installDir,
      ], { encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [
        path.resolve('update.sh'), '--package', path.join(root, 'missing-package'), '--install-dir', installDir,
      ], { encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(result.status, 0)
    assert.match(`${result.stdout}\n${result.stderr}`, /non-empty install directory/i)
    assert.equal(fs.readFileSync(marker, 'utf8'), 'must remain unchanged')
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    removeTemporaryTree(root)
  }
})

test('candidate manifest requires every lifecycle script and an exact VERSION match', () => {
  const scenarios = [
    {
      name: 'missing-install-script',
      mutate: (candidate: string) => fs.rmSync(path.join(candidate, 'install.sh')),
      pattern: /missing install\.sh/i,
    },
    {
      name: 'missing-install-env-helper',
      mutate: (candidate: string) => fs.rmSync(path.join(candidate, 'scripts', 'configure-install-env.mjs')),
      pattern: /missing scripts[\\/]configure-install-env\.mjs/i,
    },
    {
      name: 'version-mismatch',
      mutate: (candidate: string) => fs.writeFileSync(path.join(candidate, 'VERSION'), '0.1.1-mismatch\n'),
      pattern: /VERSION must exactly match/i,
    },
  ]
  for (const scenario of scenarios) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), `codex manifest ${scenario.name} #`))
    try {
      const candidate = path.join(root, 'package', 'codex-app-server-worker')
      const installDir = path.join(root, 'install')
      const bootstrapDir = path.join(root, 'bootstrap')
      prepareCandidate(candidate)
      scenario.mutate(candidate)
      const updaterName = prepareBootstrapUpdater(bootstrapDir)
      const result = process.platform === 'win32'
        ? spawnSync('powershell.exe', [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
          '-Package', path.dirname(candidate), '-InstallDir', installDir,
        ], { encoding: 'utf8', timeout: 30_000 })
        : spawnSync('bash', [
          path.join(bootstrapDir, updaterName), '--package', path.dirname(candidate), '--install-dir', installDir,
        ], { encoding: 'utf8', timeout: 30_000 })
      assert.notEqual(result.status, 0)
      assert.match(`${result.stdout}\n${result.stderr}`, scenario.pattern)
      assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
      assert.equal(findStageDirectories(root).length, 0)
      assert.equal(fs.readdirSync(root).some(name => name.startsWith('.caw-marker-')), false)
    } finally {
      removeTemporaryTree(root)
    }
  }
})

test('updater refuses a pre-existing crash transaction marker without replacing it', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex crash transaction #'))
  try {
    const installDir = path.join(root, 'install')
    const bootstrapDir = path.join(root, 'bootstrap')
    prepareCurrentInstall(installDir)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    const marker = path.join(installDir, 'update.in-progress')
    const nonce = 'd'.repeat(32)
    const created = spawnSync(process.execPath, [
      path.resolve('scripts/lifecycle-marker.mjs'), 'create', '--path', marker,
      '--nonce', nonce, '--stage-root', path.join(root, '.caw-abcdef012345'),
    ], { encoding: 'utf8' })
    assert.equal(created.status, 0, created.stderr)
    const before = fs.readFileSync(marker, 'utf8')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', path.join(root, 'missing'), '-InstallDir', installDir,
      ], { encoding: 'utf8', timeout: 10_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', path.join(root, 'missing'), '--install-dir', installDir,
      ], { encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(result.status, 0)
    assert.match(`${result.stdout}\n${result.stderr}`, /unresolved update transaction/i)
    assert.equal(fs.readFileSync(marker, 'utf8'), before)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    removeTemporaryTree(root)
  }
})

test('updater is mutually exclusive with active start and stop lifecycle owners', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex lifecycle mutex #'))
  try {
    for (const operation of ['start', 'stop']) {
      const caseRoot = path.join(root, operation)
      const installDir = path.join(caseRoot, 'install')
      const bootstrapDir = path.join(caseRoot, 'bootstrap')
      prepareCurrentInstall(installDir)
      const updaterName = prepareBootstrapUpdater(bootstrapDir)
      const lock = path.join(installDir, 'lifecycle.lock')
      const nonce = operation === 'start' ? 'a'.repeat(32) : 'b'.repeat(32)
      const acquired = spawnSync(process.execPath, [
        path.resolve('scripts/lifecycle-marker.mjs'), 'lock-acquire', '--path', lock,
        '--nonce', nonce, '--operation', operation,
      ], { encoding: 'utf8' })
      assert.equal(acquired.status, 0, acquired.stderr)
      const before = fs.readFileSync(lock)

      const result = process.platform === 'win32'
        ? spawnSync('powershell.exe', [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
          '-Package', path.join(caseRoot, 'missing'), '-InstallDir', installDir,
        ], { encoding: 'utf8', timeout: 10_000 })
        : spawnSync('bash', [
          path.join(bootstrapDir, updaterName), '--package', path.join(caseRoot, 'missing'), '--install-dir', installDir,
        ], { encoding: 'utf8', timeout: 10_000 })
      assert.notEqual(result.status, 0)
      assert.match(`${result.stdout}\n${result.stderr}`, /lifecycle operation is locked/i)
      assert.deepEqual(fs.readFileSync(lock), before)
      assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
      assert.equal(findStageDirectories(caseRoot).length, 0)
      const released = spawnSync(process.execPath, [
        path.resolve('scripts/lifecycle-marker.mjs'), 'lock-release', '--path', lock, '--nonce', nonce,
      ], { encoding: 'utf8' })
      assert.equal(released.status, 0, released.stderr)
    }
  } finally {
    removeTemporaryTree(root)
  }
})

test('0.1.1 updater refuses a running Worker whose persisted lifecycle identity is missing', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex missing identity #'))
  const installDir = path.join(root, 'current install')
  const packageContainer = path.join(root, 'package')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'bootstrap')
  const fakeBin = path.join(root, 'fake npm')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  let worker: ChildProcess | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareFakeNpm(fakeBin)
    prepareCurrentInstall(installDir)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    worker = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: {
        ...process.env,
        CODEX_APP_SERVER_RUN_DIR: runDir,
        CODEX_APP_SERVER_LOG_DIR: logDir,
        CODEX_APP_SERVER_STATE_DIR: stateDir,
        CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
        CODEX_APP_SERVER_WORKER_PORT: String(port),
      },
      stdio: 'ignore',
    })
    await waitForHealth(port)
    assert.ok(worker.pid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(worker.pid))

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', packageContainer, '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })

    assert.notEqual(result.status, 0)
    assert.equal(isProcessAlive(worker.pid), true)
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /update_worker_identity_snapshot_missing/)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1-current')
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    await forceTerminateAndWait(worker?.pid)
    removeTemporaryTree(root)
  }
})

test('updater preserves and refuses previous update identity evidence', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex update evidence #'))
  const installDir = path.join(root, 'current install')
  const packageContainer = path.join(root, 'package')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'bootstrap')
  const fakeBin = path.join(root, 'fake npm')
  const runDir = path.join(root, 'run')
  const evidence = path.join(runDir, 'update.process-tree.json')
  try {
    prepareCandidate(candidateDir)
    prepareFakeNpm(fakeBin)
    prepareCurrentInstall(installDir)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), `CODEX_APP_SERVER_RUN_DIR='${runDir}'\n`)
    fs.mkdirSync(runDir, { recursive: true })
    fs.writeFileSync(evidence, 'SAFE_OPERATOR_EVIDENCE\n')

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', packageContainer, '-InstallDir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
      ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })

    assert.notEqual(result.status, 0)
    assert.equal(fs.readFileSync(evidence, 'utf8'), 'SAFE_OPERATOR_EVIDENCE\n')
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /previous_update_identity_evidence_present/)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1-current')
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(findStageDirectories(root).length, 0)
  } finally {
    removeTemporaryTree(root)
  }
})

test('updater refuses state-only lifecycle and runtime process-tree fallback evidence', () => {
  const scenarios = [
    {
      name: 'lifecycle-failure',
      evidencePath: ['lifecycle.failed'],
      kind: 'file',
      content: 'WORKER_START_IDENTITY_NOT_PROVEN\n',
      pattern: /runtime lifecycle evidence|lifecycle\.failed/i,
    },
    {
      name: 'runtime-process-tree',
      evidencePath: ['runtime-process-trees', 'instance-1', 'capture.failure'],
      kind: 'file',
      content: 'PROCESS_TREE_CAPTURE_FAILED\n',
      pattern: /runtime process-tree evidence|runtime-process-trees/i,
    },
    {
      name: 'runtime-process-tree-link',
      evidencePath: ['runtime-process-trees'],
      kind: 'empty-link',
      pattern: /runtime process-tree evidence|runtime-process-trees/i,
    },
    {
      name: 'lifecycle-dangling-link',
      evidencePath: ['lifecycle.failed'],
      kind: 'dangling-link',
      pattern: /runtime lifecycle evidence|lifecycle\.failed/i,
    },
  ]
  for (const scenario of scenarios) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), `codex state fallback ${scenario.name} #`))
    const installDir = path.join(root, 'current install')
    const packageContainer = path.join(root, 'package')
    const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
    const bootstrapDir = path.join(root, 'bootstrap')
    const fakeBin = path.join(root, 'fake npm')
    const runDir = path.join(root, 'run')
    const stateDir = path.join(root, 'state')
    const evidence = path.join(stateDir, ...scenario.evidencePath)
    try {
      prepareCandidate(candidateDir)
      prepareFakeNpm(fakeBin)
      prepareCurrentInstall(installDir)
      const updaterName = prepareBootstrapUpdater(bootstrapDir)
      fs.writeFileSync(path.join(installDir, '.env'), [
        `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
        `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
        '',
      ].join('\n'))
      fs.mkdirSync(path.dirname(evidence), { recursive: true })
      if (scenario.kind === 'file') {
        fs.writeFileSync(evidence, scenario.content)
      } else {
        const linkTarget = path.join(root, `link target ${scenario.name}`)
        fs.mkdirSync(linkTarget, { recursive: true })
        fs.symlinkSync(linkTarget, evidence, process.platform === 'win32' ? 'junction' : 'dir')
        if (scenario.kind === 'dangling-link') fs.rmSync(linkTarget, { recursive: true })
      }
      assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)

      const updaterEnv = cleanLifecycleEnvironment(fakeBin)
      updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
      const result = process.platform === 'win32'
        ? spawnSync('powershell.exe', [
          '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
          '-Package', packageContainer, '-InstallDir', installDir,
        ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })
        : spawnSync('bash', [
          path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
        ], { env: updaterEnv, encoding: 'utf8', timeout: 60_000 })

      assert.notEqual(result.status, 0)
      assert.match(`${result.stdout}\n${result.stderr}`, scenario.pattern)
      if (scenario.kind === 'file') assert.equal(fs.readFileSync(evidence, 'utf8'), scenario.content)
      else assert.equal(fs.lstatSync(evidence).isSymbolicLink(), true)
      assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)
      assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1-current')
      assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
      assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
      assert.equal(findStageDirectories(root).length, 0)
    } finally {
      removeTemporaryTree(root)
    }
  }
})

test('Bash updater treats an unreadable stopped runtime evidence directory as present', {
  skip: process.platform !== 'linux' ? 'Linux Bash fault injection only' : false,
}, () => runRuntimeInspectionFailureScenario(false))

test('Bash updater latches an unreadable runtime evidence directory after drain', {
  skip: process.platform !== 'linux' ? 'Linux Bash fault injection only' : false,
}, () => runRuntimeInspectionFailureScenario(true))

test('Bash starter treats an unreadable runtime evidence directory as present', {
  skip: process.platform !== 'linux' ? 'Linux Bash fault injection only' : false,
}, async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex start runtime inspection #'))
  const installDir = path.join(root, 'current install')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const runtimeTreeDir = path.join(stateDir, 'runtime-process-trees')
  let hookFile: string | undefined
  try {
    prepareCurrentInstall(installDir)
    fs.mkdirSync(runtimeTreeDir, { recursive: true })
    hookFile = createRuntimeReadFailureHook(runtimeTreeDir)
    const env = cleanLifecycleEnvironment()
    env.CODEX_APP_SERVER_RUN_DIR = runDir
    env.CODEX_APP_SERVER_LOG_DIR = logDir
    env.CODEX_APP_SERVER_STATE_DIR = stateDir
    env.CODEX_APP_SERVER_WORKER_HOST = '127.0.0.1'
    env.CODEX_APP_SERVER_WORKER_PORT = String(await reserveLoopbackPort())
    env.NODE_OPTIONS = `--require=${hookFile}`

    const result = spawnSync('bash', [path.join(installDir, 'start.sh'), '--no-build'], {
      env, encoding: 'utf8', timeout: 20_000,
    })

    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /evidence inspection failed; treating evidence as present/i)
    assert.match(result.stderr, /runtime process-tree evidence|runtime-process-trees/i)
    assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /runtime_process_tree_evidence_present/)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)
    assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(fs.existsSync(runtimeTreeDir), true)
  } finally {
    const recordedPid = readPidFile(path.join(runDir, 'worker.pid'))
    await forceTerminateAndWait(recordedPid)
    if (hookFile) fs.rmSync(hookFile, { force: true })
    removeTemporaryTree(root)
  }
})

async function runRuntimeInspectionFailureScenario(wasRunning: boolean): Promise<void> {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), `codex runtime inspection ${wasRunning ? 'running' : 'stopped'} #`))
  const installDir = path.join(root, 'current install')
  const packageContainer = path.join(root, 'package')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'bootstrap')
  const fakeBin = path.join(root, 'fake npm')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const runtimeTreeDir = path.join(stateDir, 'runtime-process-trees')
  let worker: ChildProcess | undefined
  let hookFile: string | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    prepareFakeNpm(fakeBin)
    prepareCurrentInstall(installDir)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    assert.equal(updaterName, 'update.sh')
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    fs.mkdirSync(runtimeTreeDir, { recursive: true })

    if (wasRunning) {
      worker = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
        env: {
          ...process.env,
          CODEX_APP_SERVER_RUN_DIR: runDir,
          CODEX_APP_SERVER_LOG_DIR: logDir,
          CODEX_APP_SERVER_STATE_DIR: stateDir,
          CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
          CODEX_APP_SERVER_WORKER_PORT: String(port),
        },
        stdio: 'ignore',
      })
      await waitForHealth(port)
      assert.ok(worker.pid)
      fs.writeFileSync(path.join(runDir, 'worker.pid'), String(worker.pid))
      captureLifecycleSnapshot(installDir, runDir, worker.pid)
    }

    hookFile = createRuntimeReadFailureHook(runtimeTreeDir)
    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    updaterEnv.NODE_OPTIONS = `--require=${hookFile}`
    const result = spawnSync('bash', [
      path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
    ], { env: updaterEnv, encoding: 'utf8', timeout: 90_000 })

    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /evidence inspection failed; treating evidence as present/i)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1-current')
    assert.equal(fs.existsSync(runtimeTreeDir), true)
    assert.deepEqual(fs.readdirSync(runtimeTreeDir), [])
    assert.equal(fs.existsSync(path.join(installDir, 'update.in-progress')), false)
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    assert.equal(findStageDirectories(root).length, 0)

    if (wasRunning) {
      await waitForProcessExit(worker!.pid!)
      assert.match(fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'), /update_runtime_process_tree_residue/)
      assert.equal(fs.existsSync(path.join(runDir, 'update.process-tree.json')), true)
      assert.equal(fs.existsSync(path.join(runDir, 'worker.pid')), false)
      assert.equal(fs.existsSync(path.join(runDir, 'worker.process-tree.json')), false)
    } else {
      assert.equal(fs.existsSync(path.join(runDir, 'stop.failed')), false)
      assert.equal(fs.existsSync(path.join(runDir, 'update.process-tree.json')), false)
    }
  } finally {
    const recordedPid = readPidFile(path.join(runDir, 'worker.pid'))
    for (const pid of [worker?.pid, recordedPid]) await forceTerminateAndWait(pid)
    if (hookFile) fs.rmSync(hookFile, { force: true })
    removeTemporaryTree(root)
  }
}

async function runCandidateStartupRollbackScenario(blockLatchPersistence: boolean): Promise<void> {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), `codex failed rollback ${blockLatchPersistence ? 'blocked ' : ''}#`))
  const installDir = path.join(root, 'legacy install #1')
  const packageContainer = path.join(root, 'package container #1')
  const candidateDir = path.join(packageContainer, 'codex-app-server-worker')
  const bootstrapDir = path.join(root, 'new updater #1')
  const fakeBin = path.join(root, 'fake npm #1')
  const runDir = path.join(root, 'external run #1')
  const logDir = path.join(root, 'external logs #1')
  const stateDir = path.join(root, 'external state #1')
  let legacyProcess: ChildProcess | undefined
  let recordedPid: number | undefined
  try {
    const port = await reserveLoopbackPort()
    prepareCandidate(candidateDir)
    const failingStartPs = blockLatchPersistence
      ? '$RunDir = $env:CODEX_APP_SERVER_RUN_DIR\nRemove-Item -LiteralPath $RunDir -Recurse -Force\nSet-Content -LiteralPath $RunDir -Value blocked -NoNewline\nexit 23\n'
      : 'exit 23\n'
    const failingStartSh = blockLatchPersistence
      ? '#!/usr/bin/env bash\nrm -rf "$CODEX_APP_SERVER_RUN_DIR"\nprintf blocked > "$CODEX_APP_SERVER_RUN_DIR"\nexit 23\n'
      : '#!/usr/bin/env bash\nexit 23\n'
    fs.writeFileSync(path.join(candidateDir, 'start.ps1'), failingStartPs)
    fs.writeFileSync(path.join(candidateDir, 'start.sh'), failingStartSh)
    if (process.platform !== 'win32') fs.chmodSync(path.join(candidateDir, 'start.sh'), 0o755)
    prepareFakeNpm(fakeBin)
    prepareCurrentInstall(installDir)
    const updaterName = prepareBootstrapUpdater(bootstrapDir)
    fs.writeFileSync(path.join(installDir, '.env'), [
      `CODEX_APP_SERVER_RUN_DIR='${runDir}'`,
      `CODEX_APP_SERVER_LOG_DIR='${logDir}'`,
      `CODEX_APP_SERVER_STATE_DIR='${stateDir}'`,
      'CODEX_APP_SERVER_WORKER_HOST=127.0.0.1',
      `CODEX_APP_SERVER_WORKER_PORT=${port}`,
      '',
    ].join('\n'))
    fs.mkdirSync(runDir, { recursive: true })
    legacyProcess = spawn(process.execPath, [path.join(installDir, 'dist', 'index.js')], {
      env: {
        ...process.env,
        CODEX_APP_SERVER_RUN_DIR: runDir,
        CODEX_APP_SERVER_LOG_DIR: logDir,
        CODEX_APP_SERVER_STATE_DIR: stateDir,
        CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
        CODEX_APP_SERVER_WORKER_PORT: String(port),
      },
      stdio: 'ignore',
    })
    await waitForHealth(port)
    assert.ok(legacyProcess.pid)
    fs.writeFileSync(path.join(runDir, 'worker.pid'), String(legacyProcess.pid))
    captureLifecycleSnapshot(installDir, runDir, legacyProcess.pid)

    const updaterEnv = cleanLifecycleEnvironment(fakeBin)
    updaterEnv.CAW_TEST_DOTENV_SOURCE = path.resolve('node_modules', 'dotenv')
    const result = process.platform === 'win32'
      ? spawnSync('powershell.exe', [
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(bootstrapDir, updaterName),
        '-Package', packageContainer, '-InstallDir', installDir,
      ], { env: updaterEnv, stdio: 'ignore', timeout: 60_000 })
      : spawnSync('bash', [
        path.join(bootstrapDir, updaterName), '--package', packageContainer, '--install-dir', installDir,
      ], { env: updaterEnv, stdio: 'ignore', timeout: 60_000 })

    assert.equal(result.error, undefined, result.error?.message)
    assert.notEqual(result.status, 0)
    assert.equal(fs.readFileSync(path.join(installDir, 'VERSION'), 'utf8').trim(), '0.1.1')
    const stageDirectories = findStageDirectories(root)
    assert.equal(stageDirectories.length, 1)
    assert.equal(fs.readFileSync(path.join(stageDirectories[0], 'backup', 'VERSION'), 'utf8').trim(), '0.1.1-current')
    const transaction = JSON.parse(fs.readFileSync(path.join(installDir, 'update.in-progress'), 'utf8')) as {
      phase: string; backed_up: string[]; installed: string[]
    }
    assert.equal(transaction.phase, 'candidate_failed')
    assert.ok(transaction.backed_up.includes('VERSION'))
    assert.ok(transaction.installed.includes('VERSION'))
    assert.equal(fs.existsSync(path.join(installDir, 'lifecycle.lock')), false)
    if (blockLatchPersistence) {
      assert.equal(fs.statSync(runDir).isFile(), true)
    } else {
      assert.match(
        fs.readFileSync(path.join(runDir, 'stop.failed'), 'utf8'),
        /rollback_after_candidate_start_failure/,
      )
    }
    recordedPid = readPidFile(path.join(runDir, 'worker.pid'))
    if (recordedPid) assert.equal(isProcessAlive(recordedPid), false)
  } finally {
    recordedPid ??= readPidFile(path.join(runDir, 'worker.pid'))
    for (const pid of [legacyProcess?.pid, recordedPid]) await forceTerminateAndWait(pid)
    removeTemporaryTree(root)
  }
}

test('Bash updater rejects multiple immediate candidate roots', {
  skip: process.platform === 'win32',
}, () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex candidate roots '))
  try {
    const packageDir = path.join(root, 'package')
    for (const name of ['first', 'second']) {
      const candidate = path.join(packageDir, name)
      fs.mkdirSync(candidate, { recursive: true })
      fs.writeFileSync(path.join(candidate, 'package.json'), '{"name":"codex-app-server-worker"}')
    }
    const result = spawnSync('bash', [
      path.resolve('update.sh'), '--package', packageDir, '--install-dir', path.join(root, 'install'),
    ], { encoding: 'utf8', timeout: 10_000 })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /exactly one codex-app-server-worker root/)
  } finally {
    removeTemporaryTree(root)
  }
})

function prepareCandidate(candidateDir: string): void {
  for (const directory of ['dist', 'src', 'tests', 'contracts', 'scripts']) {
    fs.mkdirSync(path.join(candidateDir, directory), { recursive: true })
  }
  for (const script of ['start.ps1', 'start.sh', 'stop.ps1', 'stop.sh', 'update.ps1', 'update.sh', 'install.ps1', 'install.sh']) {
    fs.copyFileSync(script, path.join(candidateDir, script))
  }
  fs.copyFileSync('scripts/read-dotenv-value.mjs', path.join(candidateDir, 'scripts', 'read-dotenv-value.mjs'))
  fs.copyFileSync('scripts/configure-install-env.mjs', path.join(candidateDir, 'scripts', 'configure-install-env.mjs'))
  fs.copyFileSync('scripts/process-tree.mjs', path.join(candidateDir, 'scripts', 'process-tree.mjs'))
  fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(candidateDir, 'scripts', 'lifecycle-marker.mjs'))
  fs.writeFileSync(path.join(candidateDir, 'package.json'), JSON.stringify({
    name: 'codex-app-server-worker',
    version: '0.1.1',
  }))
  fs.writeFileSync(path.join(candidateDir, 'package-lock.json'), '{"lockfileVersion":3}')
  fs.writeFileSync(path.join(candidateDir, 'tsconfig.json'), '{}')
  fs.writeFileSync(path.join(candidateDir, 'VERSION'), '0.1.1\n')
  fs.writeFileSync(path.join(candidateDir, '.env.example'), '')
  fs.writeFileSync(path.join(candidateDir, 'README.md'), '')
  fs.writeFileSync(path.join(candidateDir, 'dist', 'index.js'), fakeWorkerSource())
  if (process.platform !== 'win32') {
    for (const script of ['start.sh', 'stop.sh', 'update.sh']) fs.chmodSync(path.join(candidateDir, script), 0o755)
  }
}

function prepareBootstrapUpdater(bootstrapDir: string): string {
  fs.mkdirSync(path.join(bootstrapDir, 'scripts'), { recursive: true })
  const updaterName = process.platform === 'win32' ? 'update.ps1' : 'update.sh'
  fs.copyFileSync(updaterName, path.join(bootstrapDir, updaterName))
  fs.copyFileSync('scripts/lifecycle-marker.mjs', path.join(bootstrapDir, 'scripts', 'lifecycle-marker.mjs'))
  if (process.platform !== 'win32') fs.chmodSync(path.join(bootstrapDir, updaterName), 0o755)
  return updaterName
}

function prepareCurrentInstall(installDir: string, version = '0.1.1-current'): void {
  fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
  fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
  for (const script of ['start.ps1', 'start.sh', 'stop.ps1', 'stop.sh']) {
    fs.copyFileSync(script, path.join(installDir, script))
  }
  for (const helper of ['read-dotenv-value.mjs', 'process-tree.mjs', 'lifecycle-marker.mjs']) {
    fs.copyFileSync(path.join('scripts', helper), path.join(installDir, 'scripts', helper))
  }
  fs.mkdirSync(path.join(installDir, 'node_modules'), { recursive: true })
  fs.cpSync(path.resolve('node_modules', 'dotenv'), path.join(installDir, 'node_modules', 'dotenv'), { recursive: true })
  fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), fakeWorkerSource())
  fs.writeFileSync(path.join(installDir, 'package.json'), JSON.stringify({
    name: 'codex-app-server-worker', version,
  }))
  fs.writeFileSync(path.join(installDir, 'VERSION'), `${version}\n`)
  if (process.platform !== 'win32') {
    for (const script of ['start.sh', 'stop.sh']) fs.chmodSync(path.join(installDir, script), 0o755)
  }
}

function captureLifecycleSnapshot(installDir: string, runDir: string, pid: number): void {
  captureProcessSnapshot(
    path.join(installDir, 'dist', 'index.js'),
    pid,
    path.join(runDir, 'worker.process-tree.json'),
  )
}

function captureProcessSnapshot(entry: string, pid: number, output: string): void {
  const result = spawnSync(process.execPath, [
    path.resolve('scripts/process-tree.mjs'),
    'snapshot', '--pid', String(pid), '--entry', entry, '--output', output,
  ], { encoding: 'utf8', timeout: 20_000 })
  assert.equal(result.status, 0, formatSpawnFailure(result))
}

function prepareLegacyInstall(installDir: string): void {
  fs.mkdirSync(path.join(installDir, 'dist'), { recursive: true })
  fs.writeFileSync(path.join(installDir, 'dist', 'index.js'), fakeWorkerSource())
  fs.writeFileSync(path.join(installDir, 'package.json'), JSON.stringify({
    name: 'codex-app-server-worker',
    version: '0.1.0',
  }))
  fs.writeFileSync(path.join(installDir, 'VERSION'), '0.1.0\n')
  fs.writeFileSync(path.join(installDir, 'stop.ps1'), String.raw`$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunDir = if ($env:CODEX_APP_SERVER_RUN_DIR) { $env:CODEX_APP_SERVER_RUN_DIR } else { Join-Path $Root 'logs\run' }
$PidFile = Join-Path $RunDir 'worker.pid'
$StopFile = Join-Path $RunDir 'stop.request'
if (-not (Test-Path $PidFile)) { exit 0 }
$PidValue = [int](Get-Content -Raw $PidFile)
Set-Content -LiteralPath $StopFile -Value 'stop' -NoNewline
for ($Index = 0; $Index -lt 100; $Index++) {
  if (-not (Get-Process -Id $PidValue -ErrorAction SilentlyContinue)) { break }
  Start-Sleep -Milliseconds 50
}
if (Get-Process -Id $PidValue -ErrorAction SilentlyContinue) { Stop-Process -Id $PidValue -Force }
Remove-Item -LiteralPath $PidFile,$StopFile -Force -ErrorAction SilentlyContinue
`)
  fs.writeFileSync(path.join(installDir, 'stop.sh'), `#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="\${CODEX_APP_SERVER_RUN_DIR:-$ROOT/logs/run}"
PID_FILE="$RUN_DIR/worker.pid"
STOP_FILE="$RUN_DIR/stop.request"
[[ -f "$PID_FILE" ]] || exit 0
pid="$(tr -d '[:space:]' < "$PID_FILE")"
printf stop > "$STOP_FILE"
for ((attempt=0; attempt<100; attempt+=1)); do kill -0 "$pid" 2>/dev/null || break; sleep 0.05; done
kill -0 "$pid" 2>/dev/null && kill -9 "$pid" || true
rm -f "$PID_FILE" "$STOP_FILE"
`)
  if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, 'stop.sh'), 0o755)
}

function prepareLegacyRestartMarker(installDir: string, marker: string): void {
  fs.writeFileSync(path.join(installDir, 'start.ps1'), `Set-Content -LiteralPath '${marker.replaceAll("'", "''")}' -Value restarted -NoNewline\n`)
  fs.writeFileSync(path.join(installDir, 'start.sh'), `#!/usr/bin/env bash\nprintf restarted > '${marker.replaceAll("'", "'\\''")}'\n`)
  if (process.platform !== 'win32') fs.chmodSync(path.join(installDir, 'start.sh'), 0o755)
}

function prepareFakeNpm(fakeBin: string): void {
  fs.mkdirSync(fakeBin, { recursive: true })
  fs.writeFileSync(path.join(fakeBin, 'fake-npm.cjs'), `
const fs = require('node:fs')
const path = require('node:path')
if (process.argv[2] === 'ci') {
  const target = path.join(process.cwd(), 'node_modules', 'dotenv')
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.cpSync(process.env.CAW_TEST_DOTENV_SOURCE, target, { recursive: true })
}
`)
  fs.writeFileSync(path.join(fakeBin, 'npm.cmd'), '@echo off\r\nnode "%~dp0fake-npm.cjs" %*\r\n')
  fs.writeFileSync(path.join(fakeBin, 'npm'), `#!/usr/bin/env bash
node "$(cd "$(dirname "$0")" && pwd)/fake-npm.cjs" "$@"
`)
  if (process.platform !== 'win32') fs.chmodSync(path.join(fakeBin, 'npm'), 0o755)
}

function fakeWorkerSource(): string {
  return `
const fs = require('node:fs')
const http = require('node:http')
const path = require('node:path')
fs.mkdirSync(process.env.CODEX_APP_SERVER_STATE_DIR, { recursive: true })
fs.writeFileSync(path.join(process.env.CODEX_APP_SERVER_STATE_DIR, 'selected-state.txt'), process.env.CODEX_APP_SERVER_STATE_DIR)
const server = http.createServer((request, response) => {
  response.setHeader('content-type', 'application/json')
  response.end(JSON.stringify({ ready: request.url === '/health' }))
})
server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
  const monitor = setInterval(() => {
  const stopFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'stop.request')
  if (!fs.existsSync(stopFile)) return
  clearInterval(monitor)
  const requestId = fs.readFileSync(stopFile, 'utf8').trim()
  fs.rmSync(stopFile, { force: true })
  if (process.env.CAW_TEST_CLEAR_RUNTIME_TREE_ON_STOP === 'true') {
    fs.rmSync(path.join(process.env.CODEX_APP_SERVER_STATE_DIR, 'runtime-process-trees'), { recursive: true, force: true })
  }
  const successFile = path.join(process.env.CODEX_APP_SERVER_RUN_DIR, 'shutdown.success')
  const temporary = successFile + '.tmp'
  fs.writeFileSync(temporary, requestId)
  fs.renameSync(temporary, successFile)
  server.close(() => process.exit(0))
}, 20)
`
}

function createRuntimeReadFailureHook(runtimeTreeDir: string): string {
  const hookFile = path.join(
    os.tmpdir(),
    `caw-runtime-read-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}.cjs`,
  )
  fs.writeFileSync(hookFile, `
const fs = require('node:fs')
const path = require('node:path')
const deniedPath = path.resolve(${JSON.stringify(runtimeTreeDir)})
const originalReaddirSync = fs.readdirSync
fs.readdirSync = function (candidate, ...args) {
  if (path.resolve(String(candidate)) === deniedPath) {
    const error = new Error('controlled runtime evidence read failure')
    error.code = 'EACCES'
    throw error
  }
  return Reflect.apply(originalReaddirSync, this, [candidate, ...args])
}
`)
  return hookFile
}

function cleanLifecycleEnvironment(fakeBin?: string): NodeJS.ProcessEnv {
  const env = { ...process.env }
  for (const key of Object.keys(env)) {
    if (LIFECYCLE_KEYS.includes(key.toUpperCase()) || key.toUpperCase() === 'PATH') delete env[key]
  }
  env.PATH = fakeBin ? `${fakeBin}${path.delimiter}${process.env.PATH || ''}` : process.env.PATH || ''
  return env
}

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

async function waitForHealth(port: number): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/health`)
      const body = await response.json() as { ready?: boolean }
      if (response.ok && body.ready === true) return
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`Health endpoint did not become ready on port ${port}`)
}

async function waitForFile(file: string): Promise<string> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (fs.existsSync(file)) return fs.readFileSync(file, 'utf8').trim()
    await new Promise(resolve => setTimeout(resolve, 20))
  }
  throw new Error(`Timed out waiting for ${file}`)
}

async function waitForProcessExit(pid: number): Promise<void> {
  for (let attempt = 0; attempt < 250; attempt += 1) {
    if (!isProcessAlive(pid)) return
    await new Promise(resolve => setTimeout(resolve, 20))
  }
  throw new Error(`Process ${pid} did not exit`)
}

async function forceTerminateAndWait(pid: number | undefined): Promise<void> {
  if (!pid || !isProcessAlive(pid)) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    try { process.kill(pid, 'SIGKILL') } catch {}
  }
  await waitForProcessExit(pid)
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function readPidFile(file: string): number | undefined {
  if (!fs.existsSync(file)) return undefined
  const pid = Number(fs.readFileSync(file, 'utf8').trim())
  return Number.isInteger(pid) && pid > 0 ? pid : undefined
}

function removeTemporaryTree(root: string): void {
  fs.rmSync(root, { recursive: true, force: true, maxRetries: 20, retryDelay: 250 })
}

function formatSpawnFailure(result: ReturnType<typeof spawnSync>): string {
  return [result.error?.message, result.stdout?.toString(), result.stderr?.toString()]
    .filter(Boolean)
    .join('\n')
}

function fileHash(file: string): string {
  return fs.statSync(file).size + ':' + fs.readFileSync(file, 'utf8')
}

function findStageDirectories(root: string): string[] {
  return fs.readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && entry.name.startsWith('.caw-'))
    .map(entry => path.join(root, entry.name))
}
