import assert from 'node:assert/strict'
import { spawn, spawnSync, type ChildProcess } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import { waitForTreeCleanup } from '../scripts/process-tree.mjs'

const cli = path.resolve('scripts/process-tree.mjs')

test('bounded process-tree cleanup polling tolerates delayed process disappearance', async () => {
  let inspections = 0
  const remaining = await waitForTreeCleanup(() => {
    inspections += 1
    return inspections < 4 ? [{ pid: 101 }] : []
  }, 250, 5)

  assert.deepEqual(remaining, [])
  assert.equal(inspections, 4)
})

test('snapshot records hashed identities without persisting raw commands', async t => {
  const fixture = await startFixture(t, { spawnDelayMs: 0 })
  const snapshotFile = path.join(fixture.root, 'tree.json')

  const result = runCli('snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile)
  assert.equal(result.status, 0, result.stderr)

  const serialized = fs.readFileSync(snapshotFile, 'utf8')
  const snapshot = JSON.parse(serialized) as ProcessSnapshot
  assert.equal(serialized.includes(fixture.sentinel), false)
  assert.equal(serialized.includes(`setInterval(() => {}, 1000)`), false)
  assert.equal(snapshot.schema_version, 2)
  assert.equal(snapshot.root_pid, fixture.process.pid)
  assert.ok(snapshot.processes.length >= 2)
  for (const processIdentity of snapshot.processes) {
    assert.deepEqual(Object.keys(processIdentity).sort(), [
      'command_sha256',
      'creation_id',
      'depth',
      'pid',
      'ppid',
    ])
    assert.match(processIdentity.command_sha256, /^[a-f0-9]{64}$/)
    assert.ok(processIdentity.creation_id.length > 0)
  }

  const rootIdentity = snapshot.processes.find(identity => identity.pid === fixture.process.pid)
  assert.ok(rootIdentity)
  if (process.platform === 'linux') {
    const bootId = fs.readFileSync('/proc/sys/kernel/random/boot_id', 'utf8').trim()
    const startTicks = readLinuxStartTicks(fixture.process.pid)
    assert.equal(rootIdentity.creation_id, `${bootId}:${startTicks}`)
    assert.equal(rootIdentity.command_sha256, sha256(fs.readFileSync(`/proc/${fixture.process.pid}/cmdline`)))
  } else if (process.platform === 'win32') {
    assert.ok(Number.isFinite(Date.parse(rootIdentity.creation_id)))
    assert.equal(
      rootIdentity.command_sha256,
      sha256(encodeArgv([process.execPath, fixture.entry, fixture.sentinel])),
    )
  }
})

test('initial snapshot is no-clobber and concurrent creators cannot replace the winner', async t => {
  const first = await startFixture(t, { child: false })
  const second = await startFixture(t, { child: false })
  const snapshotFile = path.join(first.root, 'exclusive-tree.json')
  const results = await Promise.all([
    runCliAsync('snapshot', '--pid', String(first.process.pid), '--entry', first.entry, '--output', snapshotFile),
    runCliAsync('snapshot', '--pid', String(second.process.pid), '--entry', second.entry, '--output', snapshotFile),
  ])
  assert.equal(results.filter(result => result.status === 0).length, 1)
  assert.equal(results.filter(result => result.status !== 0).length, 1)

  const persisted = fs.readFileSync(snapshotFile)
  const snapshot = JSON.parse(persisted.toString('utf8')) as ProcessSnapshot
  const winner = results[0].status === 0 ? first : second
  assert.equal(snapshot.root_pid, winner.process.pid)

  const replacement = runCli(
    'snapshot', '--pid', String(winner.process.pid), '--entry', winner.entry, '--output', snapshotFile,
  )
  assert.notEqual(replacement.status, 0)
  assert.deepEqual(fs.readFileSync(snapshotFile), persisted)
})

test('Linux snapshot uses proc identities without requiring ps', {
  skip: process.platform !== 'linux' ? 'Linux /proc only' : false,
}, async t => {
  const fixture = await startFixture(t, { child: false })
  const snapshotFile = path.join(fixture.root, 'tree-without-path.json')
  const result = runCliWithEnvironment(
    { ...process.env, PATH: '' },
    'snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile,
  )
  assert.equal(result.status, 0, result.stderr)
  assert.equal(JSON.parse(fs.readFileSync(snapshotFile, 'utf8')).schema_version, 2)
})

test('kill leaves a live process untouched when its snapshotted identity does not match', async t => {
  const fixture = await startFixture(t, { child: false })
  const snapshotFile = path.join(fixture.root, 'tree.json')
  assert.equal(
    runCli('snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile).status,
    0,
  )
  assert.equal(runCli('verify', '--output', snapshotFile).status, 11)

  const snapshot = JSON.parse(fs.readFileSync(snapshotFile, 'utf8')) as ProcessSnapshot
  snapshot.processes[0].command_sha256 = '0'.repeat(64)
  fs.writeFileSync(snapshotFile, `${JSON.stringify(snapshot, null, 2)}\n`)

  const result = runCli('kill', '--output', snapshotFile)
  assert.equal(result.status, 0, result.stderr)
  assert.equal(isAlive(fixture.process.pid), true)
})

test('schema v1 snapshots fail closed after the exact identity upgrade', async t => {
  const fixture = await startFixture(t, { child: false })
  const snapshotFile = path.join(fixture.root, 'legacy-tree.json')
  assert.equal(
    runCli('snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile).status,
    0,
  )
  const snapshot = JSON.parse(fs.readFileSync(snapshotFile, 'utf8')) as ProcessSnapshot
  snapshot.schema_version = 1
  fs.writeFileSync(snapshotFile, `${JSON.stringify(snapshot, null, 2)}\n`)

  const result = runCli('kill', '--output', snapshotFile)
  assert.equal(result.status, 64, result.stderr)
  assert.equal(isAlive(fixture.process.pid), true)
})

test('unsupported platforms reject process identity capture', async t => {
  const fixture = await startFixture(t, { child: false })
  const snapshotFile = path.join(fixture.root, 'unsupported-tree.json')
  const source = [
    `Object.defineProperty(process, 'platform', { value: 'darwin' })`,
    `const { main } = await import(${JSON.stringify(pathToFileURL(cli).href)})`,
    `try { process.exitCode = await main(${JSON.stringify([
      'snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile,
    ])}) } catch (error) { process.exitCode = error.exitCode ?? 1 }`,
  ].join('; ')
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', source], {
    cwd: path.resolve('.'),
    encoding: 'utf8',
    timeout: 20_000,
  })
  assert.equal(result.status, 70, result.stderr)
  assert.equal(fs.existsSync(snapshotFile), false)
  assert.equal(isAlive(fixture.process.pid), true)
})

test('extend captures a new descendant and kill removes the exact parent-child tree', async t => {
  const fixture = await startFixture(t, { waitForTrigger: true })
  const snapshotFile = path.join(fixture.root, 'tree.json')

  const captured = runCli('snapshot', '--pid', String(fixture.process.pid), '--entry', fixture.entry, '--output', snapshotFile)
  assert.equal(captured.status, 0, captured.stderr)
  const initial = JSON.parse(fs.readFileSync(snapshotFile, 'utf8')) as ProcessSnapshot
  assert.equal(initial.processes.length, 1)

  fs.writeFileSync(fixture.childTriggerFile, '')
  const childPid = await waitForPid(fixture.childPidFile)
  const alive = runCli('status', '--output', snapshotFile)
  assert.equal(alive.status, 10, alive.stderr)

  const extended = runCli('poll', '--output', snapshotFile)
  assert.equal(extended.status, 10, extended.stderr)
  assert.equal(runCli('poll-root', '--output', snapshotFile).status, 10)
  const finalSnapshot = JSON.parse(fs.readFileSync(snapshotFile, 'utf8')) as ProcessSnapshot
  assert.ok(finalSnapshot.processes.some(identity => identity.pid === childPid && identity.depth === 1))

  const killed = runCli('kill', '--output', snapshotFile)
  assert.equal(killed.status, 0, killed.stderr)
  assert.equal(runCli('verify', '--output', snapshotFile).status, 0)
  await waitForGone(fixture.process.pid)
  await waitForGone(childPid)
})

test('snapshot rejects entry paths embedded inside a different command argument', async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-process-tree-boundary-'))
  const expectedEntry = path.join(root, 'expected worker.mjs')
  fs.writeFileSync(expectedEntry, 'setInterval(() => {}, 1000)\n')
  const children: ChildProcess[] = []
  t.after(async () => {
    for (const child of children) {
      if (child.pid) terminateTree(child.pid)
      if (child.pid) await waitForGone(child.pid).catch(() => undefined)
    }
    fs.rmSync(root, { recursive: true, force: true })
  })

  const rejectedArgumentVectors = [
    ['-e', `setInterval(() => {}, 1000); void ${JSON.stringify(expectedEntry)}`],
    ['-e', 'setInterval(() => {}, 1000)', `"${expectedEntry}"`],
    ['-e', 'setInterval(() => {}, 1000)', `prefix${expectedEntry}`],
    ['-e', 'setInterval(() => {}, 1000)', `${expectedEntry}.suffix`],
    ['-e', 'setInterval(() => {}, 1000)', `prefix"${expectedEntry}"`],
    ['-e', 'setInterval(() => {}, 1000)', `"${expectedEntry}".suffix`],
  ]
  for (const [index, arguments_] of rejectedArgumentVectors.entries()) {
    const child = spawn(process.execPath, arguments_, { stdio: 'ignore' })
    children.push(child)
    assert.ok(child.pid)
    await waitForAlive(child.pid)
    const snapshotFile = path.join(root, `rejected-${index}.json`)
    const result = runCli('snapshot', '--pid', String(child.pid), '--entry', expectedEntry, '--output', snapshotFile)
    assert.equal(result.status, 64, result.stderr)
    assert.equal(fs.existsSync(snapshotFile), false)
  }
})

interface FixtureOptions {
  child?: boolean
  spawnDelayMs?: number
  waitForTrigger?: boolean
}

interface RunningFixture {
  root: string
  entry: string
  sentinel: string
  childPidFile: string
  childTriggerFile: string
  process: ChildProcess & { pid: number }
}

interface ProcessSnapshot {
  schema_version: number
  root_pid: number
  processes: Array<{
    pid: number
    ppid: number
    depth: number
    creation_id: string
    command_sha256: string
  }>
}

async function startFixture(t: test.TestContext, options: FixtureOptions): Promise<RunningFixture> {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-process-tree-'))
  const entry = path.join(root, 'fixture worker.mjs')
  const childPidFile = path.join(root, 'child.pid')
  const childTriggerFile = path.join(root, 'spawn-child')
  const sentinel = `RAW_COMMAND_MUST_NOT_PERSIST_${Date.now()}_${Math.random()}`
  const shouldSpawnChild = options.child !== false
  fs.writeFileSync(entry, [
    "import { spawn } from 'node:child_process'",
    "import fs from 'node:fs'",
    `const shouldSpawn = ${JSON.stringify(shouldSpawnChild)}`,
    `const delay = ${options.spawnDelayMs ?? 0}`,
    `const waitForTrigger = ${JSON.stringify(options.waitForTrigger === true)}`,
    `const childPidFile = ${JSON.stringify(childPidFile)}`,
    `const childTriggerFile = ${JSON.stringify(childTriggerFile)}`,
    'const sentinel = process.argv[2]',
    'const spawnChild = () => {',
    "  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', sentinel], { stdio: 'ignore' })",
    "  fs.writeFileSync(childPidFile, String(child.pid), 'utf8')",
    '}',
    'if (shouldSpawn && waitForTrigger) {',
    '  const timer = setInterval(() => {',
    '    if (!fs.existsSync(childTriggerFile)) return',
    '    clearInterval(timer)',
    '    spawnChild()',
    '  }, 25)',
    '} else if (shouldSpawn) setTimeout(spawnChild, delay)',
    'setInterval(() => {}, 1000)',
    '',
  ].join('\n'))

  const child = spawn(process.execPath, [entry, sentinel], { stdio: 'ignore' })
  assert.ok(child.pid)
  const processWithPid = child as ChildProcess & { pid: number }
  t.after(async () => {
    const childPid = readPidIfPresent(childPidFile)
    if (childPid !== undefined) terminateTree(childPid)
    terminateTree(processWithPid.pid)
    if (childPid !== undefined) await waitForGone(childPid).catch(() => undefined)
    await waitForGone(processWithPid.pid).catch(() => undefined)
    fs.rmSync(root, { recursive: true, force: true })
  })
  await waitForAlive(processWithPid.pid)
  if (shouldSpawnChild && !options.waitForTrigger && (options.spawnDelayMs ?? 0) === 0) await waitForPid(childPidFile)
  return { root, entry, sentinel, childPidFile, childTriggerFile, process: processWithPid }
}

function runCli(...args: string[]) {
  return runCliWithEnvironment(process.env, ...args)
}

function runCliAsync(...args: string[]): Promise<{ status: number | null; stderr: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [cli, ...args], {
      cwd: path.resolve('.'),
      stdio: ['ignore', 'ignore', 'pipe'],
    })
    let stderr = ''
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', chunk => { stderr += chunk })
    child.once('error', reject)
    child.once('exit', status => resolve({ status, stderr }))
  })
}

function runCliWithEnvironment(env: NodeJS.ProcessEnv, ...args: string[]) {
  return spawnSync(process.execPath, [cli, ...args], {
    cwd: path.resolve('.'),
    encoding: 'utf8',
    env,
    timeout: 20_000,
  })
}

function readLinuxStartTicks(pid: number): string {
  const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8')
  const close = stat.lastIndexOf(')')
  assert.ok(close > 0)
  return stat.slice(close + 2).trim().split(/\s+/)[19]
}

function encodeArgv(argv: string[]): Buffer {
  const chunks: Buffer[] = []
  for (const argument of argv) chunks.push(Buffer.from(argument, 'utf8'), Buffer.from([0]))
  return Buffer.concat(chunks)
}

function sha256(value: crypto.BinaryLike): string {
  return crypto.createHash('sha256').update(value).digest('hex')
}

async function waitForPid(file: string): Promise<number> {
  const deadline = Date.now() + 5_000
  while (Date.now() < deadline) {
    if (fs.existsSync(file)) {
      const pid = Number.parseInt(fs.readFileSync(file, 'utf8'), 10)
      if (Number.isInteger(pid) && pid > 0) return pid
    }
    await delay(25)
  }
  throw new Error(`Timed out waiting for fixture child PID`)
}

async function waitForAlive(pid: number): Promise<void> {
  const deadline = Date.now() + 5_000
  while (Date.now() < deadline) {
    if (isAlive(pid)) return
    await delay(20)
  }
  throw new Error(`Timed out waiting for fixture process ${pid}`)
}

async function waitForGone(pid: number): Promise<void> {
  const deadline = Date.now() + 5_000
  while (Date.now() < deadline) {
    if (!isAlive(pid)) return
    await delay(25)
  }
  throw new Error(`Timed out waiting for fixture process ${pid} to exit`)
}

function isAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function readPidIfPresent(file: string): number | undefined {
  if (!fs.existsSync(file)) return undefined
  const pid = Number.parseInt(fs.readFileSync(file, 'utf8'), 10)
  return Number.isInteger(pid) && pid > 0 ? pid : undefined
}

function terminateTree(pid: number): void {
  if (!isAlive(pid)) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' })
    return
  }
  try {
    process.kill(pid, 'SIGKILL')
  } catch {
    // The process may have exited between the liveness check and signal.
  }
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}
