import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { SpawnAppServerProcess } from '../src/app-server/runtime.js'

type TestCleanupContext = {
  after(callback: () => void | Promise<void>): void
}

export async function createStubbornProcessTreeFixture(
  t: TestCleanupContext,
  mode: 'close' | 'turn' | 'repair-tree-on-term' = 'close',
): Promise<{
  stateDir: string
  entry: string
  spawnProcess: SpawnAppServerProcess
  readDescendantPid: () => Promise<number>
  waitForTurnStart: () => Promise<void>
}> {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'codex-runtime-tree-'))
  const stateDir = path.join(root, 'state')
  const entry = path.join(root, 'stubborn-app-server.mjs')
  const descendantPidFile = path.join(root, 'descendant.pid')
  const turnStartedFile = path.join(root, 'turn-started')
  let rootPid: number | undefined
  let descendantPid: number | undefined
  fs.mkdirSync(stateDir, { recursive: true })
  fs.writeFileSync(entry, `
import fs from 'node:fs'
import { spawn } from 'node:child_process'
import readline from 'node:readline'

const [pidFile, turnFile, mode] = process.argv.slice(2)
const descendant = spawn(process.execPath, ['-e', 'setInterval(() => undefined, 1000)'], {
  stdio: 'ignore',
  detached: true,
  windowsHide: true,
})
descendant.unref()
fs.writeFileSync(pidFile, String(descendant.pid))
process.on('SIGTERM', () => undefined)
const lines = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
lines.on('line', line => {
  const message = JSON.parse(line)
  const send = result => process.stdout.write(JSON.stringify({ id: message.id, result }) + '\\n')
  if (message.method === 'initialize') send({})
  if (message.method === 'thread/start') send({ thread: { id: 'thread-tree' } })
  if (message.method === 'turn/start') {
    fs.writeFileSync(turnFile, 'started')
    send({ turn: { id: 'turn-tree', status: 'inProgress' } })
  }
  if (message.method === 'turn/interrupt' && mode !== 'turn') send({})
})
setInterval(() => undefined, 1000)
`)

  const readDescendantPid = async (): Promise<number> => {
    await waitForFile(descendantPidFile)
    descendantPid = Number(fs.readFileSync(descendantPidFile, 'utf8'))
    assert.ok(Number.isSafeInteger(descendantPid) && descendantPid > 0)
    return descendantPid
  }
  t.after(async () => {
    for (const pid of [rootPid, descendantPid]) {
      if (pid && isProcessAlive(pid)) {
        try { process.kill(pid, 'SIGKILL') } catch { /* already exited */ }
      }
    }
    await new Promise(resolve => setTimeout(resolve, 25))
    fs.rmSync(root, { recursive: true, force: true })
  })
  return {
    stateDir,
    entry,
    spawnProcess: options => {
      const child = spawn(process.execPath, [entry, descendantPidFile, turnStartedFile, mode], {
        cwd: options.cwd,
        env: { ...process.env, ...options.env },
        stdio: ['pipe', 'pipe', 'pipe'],
        windowsHide: true,
      })
      if (mode === 'repair-tree-on-term') {
        const kill = child.kill.bind(child)
        child.kill = signal => {
          restoreProcessTreeSnapshot(stateDir)
          return kill(signal)
        }
      }
      rootPid = child.pid
      return child
    },
    readDescendantPid,
    waitForTurnStart: () => waitForFile(turnStartedFile),
  }
}

function restoreProcessTreeSnapshot(stateDir: string): void {
  const trackerRoot = path.join(stateDir, 'runtime-process-trees')
  if (!fs.existsSync(trackerRoot)) return
  for (const entry of fs.readdirSync(trackerRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const trackerDirectory = path.join(trackerRoot, entry.name)
    const backup = path.join(trackerDirectory, 'tree.backup')
    if (fs.existsSync(backup)) fs.copyFileSync(backup, path.join(trackerDirectory, 'tree.json'))
  }
}

export function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function waitForFile(file: string): Promise<void> {
  const deadline = Date.now() + 5_000
  while (!fs.existsSync(file)) {
    if (Date.now() >= deadline) throw new Error('fixture file was not created')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}
