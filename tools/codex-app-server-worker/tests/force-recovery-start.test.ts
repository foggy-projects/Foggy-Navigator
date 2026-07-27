import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

test('start prints a force recovery command and explicit recovery kills only the snapshotted Worker tree', {
  skip: process.platform === 'win32',
}, async () => {
  const fixture = await prepareFixture('codex force recovery ')
  let oldPid: number | undefined
  let replacementPid: number | undefined
  try {
    const oldWorker = spawn(process.execPath, [fixture.entry], { env: fixture.env, stdio: 'ignore' })
    oldPid = oldWorker.pid
    assert.ok(oldPid)
    await waitForHealth(fixture.port)
    fs.writeFileSync(path.join(fixture.runDir, 'worker.pid'), String(oldPid))
    captureSnapshot(fixture, oldPid)
    fs.writeFileSync(path.join(fixture.runDir, 'stop.failed'), 'test_latched_stop\n')

    const blocked = runStart(fixture)
    assert.notEqual(blocked.status, 0)
    assert.match(blocked.stderr, /\.\/start\.sh --force-kill-and-start/)
    assert.match(blocked.stderr, /destructive/i)
    process.kill(oldPid, 0)

    const recovered = runStart(fixture, '--force-kill-and-start')
    assert.equal(recovered.status, 0, recovered.stderr)
    assert.match(recovered.stderr, /killing exact snapshotted Worker tree/i)
    assert.match(recovered.stderr, /proved zero Worker residue/i)
    await waitForExit(oldPid)
    replacementPid = Number(fs.readFileSync(path.join(fixture.runDir, 'worker.pid'), 'utf8'))
    assert.ok(Number.isSafeInteger(replacementPid) && replacementPid > 0 && replacementPid !== oldPid)
    assert.equal((await getHealth(fixture.port)).ready, true)
    assert.equal(fs.existsSync(path.join(fixture.runDir, 'stop.failed')), false)

    const stopped = spawnSync('bash', [path.join(fixture.installDir, 'stop.sh')], {
      env: fixture.env, encoding: 'utf8', timeout: 20_000,
    })
    assert.equal(stopped.status, 0, stopped.stderr)
    await waitForExit(replacementPid)
    replacementPid = undefined
  } finally {
    for (const pid of [replacementPid, oldPid]) {
      if (pid && isAlive(pid)) {
        try { process.kill(pid, 'SIGKILL') } catch {}
      }
    }
    fs.rmSync(fixture.root, { recursive: true, force: true })
  }
})

test('force recovery refuses an unbound PID and unresolved runtime evidence', {
  skip: process.platform === 'win32',
}, async () => {
  const fixture = await prepareFixture('codex force refusal ')
  let unrelatedPid: number | undefined
  try {
    const unrelated = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' })
    unrelatedPid = unrelated.pid
    assert.ok(unrelatedPid)
    fs.writeFileSync(path.join(fixture.runDir, 'worker.pid'), String(unrelatedPid))
    fs.writeFileSync(path.join(fixture.runDir, 'stop.failed'), 'missing_snapshot\n')

    const missingSnapshot = runStart(fixture, '--force-kill-and-start')
    assert.notEqual(missingSnapshot.status, 0)
    assert.match(missingSnapshot.stderr, /without an exact process-tree snapshot/i)
    process.kill(unrelatedPid, 0)

    fs.rmSync(path.join(fixture.runDir, 'worker.pid'))
    const lifecycleFailure = path.join(fixture.stateDir, 'lifecycle.failed')
    fs.mkdirSync(fixture.stateDir, { recursive: true })
    fs.writeFileSync(lifecycleFailure, 'WORKER_START_IDENTITY_NOT_PROVEN\n')
    const unresolvedLifecycle = runStart(fixture, '--force-kill-and-start')
    assert.notEqual(unresolvedLifecycle.status, 0)
    assert.match(unresolvedLifecycle.stderr, /cannot clear fallback lifecycle evidence/i)
    assert.equal(fs.existsSync(lifecycleFailure), true)
    fs.rmSync(lifecycleFailure)

    const updateTransaction = path.join(fixture.installDir, 'update.in-progress')
    fs.writeFileSync(updateTransaction, '{}')
    const unresolvedUpdate = runStart(fixture, '--force-kill-and-start')
    assert.notEqual(unresolvedUpdate.status, 0)
    assert.match(unresolvedUpdate.stderr, /update transaction/i)
    assert.equal(fs.existsSync(updateTransaction), true)
    fs.rmSync(updateTransaction)

    const runtimeEvidence = path.join(fixture.stateDir, 'runtime-process-trees', 'lane', 'process-tree.json')
    fs.mkdirSync(path.dirname(runtimeEvidence), { recursive: true })
    fs.writeFileSync(runtimeEvidence, '{}')
    const unresolvedRuntime = runStart(fixture, '--force-kill-and-start')
    assert.notEqual(unresolvedRuntime.status, 0)
    assert.match(unresolvedRuntime.stderr, /cannot clear runtime-owned evidence/i)
    assert.equal(fs.existsSync(runtimeEvidence), true)
    assert.equal(fs.existsSync(path.join(fixture.runDir, 'stop.failed')), true)
  } finally {
    if (unrelatedPid && isAlive(unrelatedPid)) {
      try { process.kill(unrelatedPid, 'SIGKILL') } catch {}
    }
    fs.rmSync(fixture.root, { recursive: true, force: true })
  }
})

test('both platform start scripts expose the explicit force recovery contract', () => {
  const shell = fs.readFileSync('start.sh', 'utf8')
  const powershell = fs.readFileSync('start.ps1', 'utf8')
  assert.match(shell, /--force-kill-and-start/)
  assert.match(shell, /kills only the exact snapshotted Worker tree/)
  assert.match(powershell, /\[switch\]\$ForceKillAndStart/)
  assert.match(powershell, /-ForceKillAndStart/)
  assert.match(powershell, /kills only the exact snapshotted Worker tree/)
})

async function prepareFixture(prefix: string) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), prefix))
  const installDir = path.join(root, 'install root #1')
  const runDir = path.join(root, 'run')
  const logDir = path.join(root, 'logs')
  const stateDir = path.join(root, 'state')
  const entry = path.join(installDir, 'dist', 'index.js')
  const port = await reservePort()
  fs.mkdirSync(path.dirname(entry), { recursive: true })
  fs.mkdirSync(path.join(installDir, 'scripts'), { recursive: true })
  fs.mkdirSync(runDir, { recursive: true })
  for (const script of ['start.sh', 'stop.sh']) {
    fs.copyFileSync(script, path.join(installDir, script))
    fs.chmodSync(path.join(installDir, script), 0o755)
  }
  for (const helper of ['process-tree.mjs', 'lifecycle-marker.mjs']) {
    fs.copyFileSync(path.join('scripts', helper), path.join(installDir, 'scripts', helper))
  }
  fs.writeFileSync(entry, `
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const runDir = process.env.CODEX_APP_SERVER_RUN_DIR
const server = http.createServer((request, response) => {
  response.setHeader('content-type', 'application/json')
  response.end(JSON.stringify({ ready: request.url === '/health' }))
})
server.listen(Number(process.env.CODEX_APP_SERVER_WORKER_PORT), process.env.CODEX_APP_SERVER_WORKER_HOST)
const monitor = setInterval(() => {
  const requestFile = path.join(runDir, 'stop.request')
  if (!fs.existsSync(requestFile)) return
  clearInterval(monitor)
  const requestId = fs.readFileSync(requestFile, 'utf8').trim()
  fs.rmSync(requestFile, { force: true })
  fs.writeFileSync(path.join(runDir, 'shutdown.success'), requestId)
  server.close(() => process.exit(0))
}, 20)
`)
  const env = {
    ...process.env,
    CODEX_APP_SERVER_RUN_DIR: runDir,
    CODEX_APP_SERVER_LOG_DIR: logDir,
    CODEX_APP_SERVER_STATE_DIR: stateDir,
    CODEX_APP_SERVER_WORKER_HOST: '127.0.0.1',
    CODEX_APP_SERVER_WORKER_PORT: String(port),
  }
  return { root, installDir, runDir, logDir, stateDir, entry, port, env }
}

function runStart(fixture: Awaited<ReturnType<typeof prepareFixture>>, ...extra: string[]) {
  return spawnSync('bash', [path.join(fixture.installDir, 'start.sh'), '--no-build', ...extra], {
    env: fixture.env, encoding: 'utf8', timeout: 20_000,
  })
}

function captureSnapshot(fixture: Awaited<ReturnType<typeof prepareFixture>>, pid: number) {
  const result = spawnSync(process.execPath, [
    path.join(fixture.installDir, 'scripts', 'process-tree.mjs'), 'snapshot',
    '--pid', String(pid), '--entry', fixture.entry,
    '--output', path.join(fixture.runDir, 'worker.process-tree.json'),
  ], { encoding: 'utf8' })
  assert.equal(result.status, 0, result.stderr)
}

async function reservePort(): Promise<number> {
  return await new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      assert.ok(address && typeof address === 'object')
      const port = address.port
      server.close(error => error ? reject(error) : resolve(port))
    })
  })
}

async function waitForHealth(port: number) {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    try {
      if ((await getHealth(port)).ready === true) return
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`health timeout on ${port}`)
}

async function getHealth(port: number): Promise<{ ready?: boolean }> {
  return await new Promise((resolve, reject) => {
    const request = http.get(`http://127.0.0.1:${port}/health`, { timeout: 1_000 }, response => {
      let body = ''
      response.setEncoding('utf8')
      response.on('data', chunk => { body += chunk })
      response.on('end', () => {
        try { resolve(JSON.parse(body)) } catch (error) { reject(error) }
      })
    })
    request.on('timeout', () => request.destroy(new Error('timeout')))
    request.on('error', reject)
  })
}

function isAlive(pid: number) {
  try { process.kill(pid, 0); return true } catch { return false }
}

async function waitForExit(pid: number) {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    if (!isAlive(pid)) return
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`process ${pid} did not exit`)
}
