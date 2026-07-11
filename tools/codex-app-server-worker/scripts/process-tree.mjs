#!/usr/bin/env node

import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

export const EXIT_CLEAN = 0
export const EXIT_ALIVE = 10
export const EXIT_RESIDUE = 11
export const EXIT_INVALID = 64
const EXIT_OPERATION_FAILED = 70
const SCHEMA_VERSION = 2
const SHA256_PATTERN = /^[a-f0-9]{64}$/
const MAX_PROCESSES = 65_536
const WINDOWS_ROOT_SETTLE_TIMEOUT_MS = 500
const WINDOWS_TREE_SETTLE_TIMEOUT_MS = 3_500
const WINDOWS_TREE_POLL_INTERVAL_MS = 100

class CliError extends Error {
  constructor(message, exitCode = EXIT_INVALID) {
    super(message)
    this.exitCode = exitCode
  }
}

export async function main(argv = process.argv.slice(2)) {
  const options = parseArguments(argv)

  if (options.action === 'snapshot') {
    const snapshot = createSnapshot(options)
    writeInitialSnapshot(options.output, snapshot)
    printResult('snapshot', snapshot.processes.length)
    return EXIT_CLEAN
  }

  let snapshot = readSnapshot(options.output)
  assertOptionalBinding(snapshot, options)

  if (options.action === 'extend' || options.action === 'poll' || options.action === 'poll-root') {
    const processTable = captureProcessTable()
    const result = extendSnapshotData(snapshot, processTable)
    snapshot = result.snapshot
    if (result.added > 0) writeSnapshot(options.output, snapshot)
    if (options.action === 'poll' || options.action === 'poll-root') {
      if (options.action === 'poll-root') {
        const root = snapshot.processes.find(identity => identity.pid === snapshot.root_pid && identity.depth === 0)
        const rootAlive = Boolean(root) && matchesIdentity(root, processTable.get(snapshot.root_pid))
        printResult(rootAlive ? 'alive' : 'clean', rootAlive ? 1 : 0)
        return rootAlive ? EXIT_ALIVE : EXIT_CLEAN
      }
      const alive = findAliveIdentities(snapshot, processTable)
      printResult(alive.length === 0 ? 'clean' : 'alive', alive.length)
      return alive.length === 0 ? EXIT_CLEAN : EXIT_ALIVE
    }
    printResult('extend', result.added)
    return EXIT_CLEAN
  }

  if (options.action === 'status' || options.action === 'verify') {
    const alive = findAliveIdentities(snapshot, captureProcessTable())
    printResult(alive.length === 0 ? 'clean' : 'alive', alive.length)
    if (alive.length === 0) return EXIT_CLEAN
    return options.action === 'status' ? EXIT_ALIVE : EXIT_RESIDUE
  }

  const result = process.platform === 'win32'
    ? await killWindowsTree(snapshot, options.output)
    : await killPosixTree(snapshot, options.output)
  printResult(result.residue === 0 ? 'clean' : 'residue', result.residue)
  return result.residue === 0 ? EXIT_CLEAN : EXIT_RESIDUE
}

function parseArguments(argv) {
  if (argv.length === 0) throw new CliError('missing action')
  const action = argv[0]
  if (!['snapshot', 'extend', 'poll', 'poll-root', 'status', 'verify', 'kill'].includes(action)) {
    throw new CliError('unsupported action')
  }

  const values = new Map()
  for (let index = 1; index < argv.length; index += 2) {
    const key = argv[index]
    const value = argv[index + 1]
    if (!['--pid', '--entry', '--output'].includes(key) || value === undefined || values.has(key)) {
      throw new CliError('invalid arguments')
    }
    values.set(key, value)
  }

  const outputValue = values.get('--output')
  if (!outputValue) throw new CliError('missing output')
  const output = path.resolve(outputValue)
  const pidValue = values.get('--pid')
  const entryValue = values.get('--entry')
  const pid = pidValue === undefined ? undefined : parsePid(pidValue)
  const entry = entryValue === undefined ? undefined : path.resolve(entryValue)

  if (action === 'snapshot' && (pid === undefined || entry === undefined)) {
    throw new CliError('snapshot requires pid and entry')
  }
  return { action, output, pid, entry }
}

function parsePid(value) {
  if (!/^[1-9]\d*$/.test(value)) throw new CliError('invalid pid')
  const pid = Number(value)
  if (!Number.isSafeInteger(pid) || pid <= 0 || pid > 2_147_483_647) throw new CliError('invalid pid')
  return pid
}

function createSnapshot({ pid, entry }) {
  let entryStat
  try {
    entryStat = fs.statSync(entry)
  } catch {
    throw new CliError('entry is unavailable')
  }
  if (!entryStat.isFile()) throw new CliError('entry is not a file')

  const table = captureProcessTable()
  const root = table.get(pid)
  if (!root || root.zombie) throw new CliError('root process is unavailable')
  assertExactProcessIdentity(root)
  if (!argvContainsEntry(root.argv, entry)) throw new CliError('root process does not match entry')

  const processes = collectCurrentTree(table, pid).map(toStoredIdentity)
  const snapshot = {
    schema_version: SCHEMA_VERSION,
    platform: process.platform,
    captured_at: new Date().toISOString(),
    root_pid: pid,
    entry_sha256: sha256(normalizeEntry(entry)),
    processes,
  }
  validateSnapshot(snapshot)
  return snapshot
}

function extendSnapshotData(snapshot, table) {
  const rootIdentity = snapshot.processes.find(identity => identity.pid === snapshot.root_pid && identity.depth === 0)
  const currentRoot = table.get(snapshot.root_pid)
  if (currentRoot) assertExactProcessIdentity(currentRoot)
  if (!rootIdentity || !matchesIdentity(rootIdentity, currentRoot)) return { snapshot, added: 0 }

  const known = new Set(snapshot.processes.map(identityKey))
  const additions = []
  for (const current of collectCurrentTree(table, snapshot.root_pid)) {
    const stored = toStoredIdentity(current)
    if (!known.has(identityKey(stored))) {
      additions.push(stored)
      known.add(identityKey(stored))
    }
  }
  if (additions.length === 0) return { snapshot, added: 0 }

  const next = {
    ...snapshot,
    captured_at: new Date().toISOString(),
    processes: [...snapshot.processes, ...additions].sort(compareStoredIdentities),
  }
  validateSnapshot(next)
  return { snapshot: next, added: additions.length }
}

function collectCurrentTree(table, rootPid) {
  const root = table.get(rootPid)
  if (!root) return []
  const children = new Map()
  for (const current of table.values()) {
    const siblings = children.get(current.ppid) ?? []
    siblings.push(current)
    children.set(current.ppid, siblings)
  }

  const result = []
  const visited = new Set()
  const queue = [{ process: root, depth: 0 }]
  while (queue.length > 0) {
    const item = queue.shift()
    if (visited.has(item.process.pid)) continue
    visited.add(item.process.pid)
    result.push({ ...item.process, depth: item.depth })
    for (const child of children.get(item.process.pid) ?? []) {
      queue.push({ process: child, depth: item.depth + 1 })
    }
  }
  return result.sort((left, right) => left.depth - right.depth || left.pid - right.pid)
}

function captureProcessTable() {
  if (process.platform === 'win32') return captureWindowsProcessTable()
  if (process.platform === 'linux') return captureLinuxProcessTable()
  throw new CliError('exact process identity is unsupported on this platform', EXIT_OPERATION_FAILED)
}

function captureWindowsProcessTable() {
  const powershell = process.env.SystemRoot
    ? path.join(process.env.SystemRoot, 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    : 'powershell.exe'
  const script = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace Foggy.ProcessTree {
    public static class NativeCommandLine {
        [DllImport("shell32.dll", SetLastError = true)]
        private static extern IntPtr CommandLineToArgvW(
            [MarshalAs(UnmanagedType.LPWStr)] string commandLine,
            out int argumentCount);

        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr memory);

        public static string[] Parse(string commandLine) {
            int argumentCount;
            IntPtr arguments = CommandLineToArgvW(commandLine, out argumentCount);
            if (arguments == IntPtr.Zero) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            try {
                string[] result = new string[argumentCount];
                for (int index = 0; index < argumentCount; index++) {
                    IntPtr argument = Marshal.ReadIntPtr(arguments, index * IntPtr.Size);
                    result[index] = Marshal.PtrToStringUni(argument) ?? String.Empty;
                }
                return result;
            } finally {
                LocalFree(arguments);
            }
        }
    }
}
'@
$rows = @(
    foreach ($process in Get-CimInstance Win32_Process) {
        $command = [string]$process.CommandLine
        $argv = @()
        $argvExact = $false
        if (-not [string]::IsNullOrEmpty($command)) {
            $argv = @([Foggy.ProcessTree.NativeCommandLine]::Parse($command))
            $argvExact = $true
        }
        [pscustomobject]@{
            pid = [int]$process.ProcessId
            ppid = [int]$process.ParentProcessId
            creation = if ($process.CreationDate) { $process.CreationDate.ToUniversalTime().ToString('o') } else { '' }
            argv = $argv
            argv_exact = $argvExact
        }
    }
)
@($rows) | ConvertTo-Json -Compress -Depth 5
`
  const result = spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-Command', script], {
    encoding: 'utf8',
    windowsHide: true,
    maxBuffer: 32 * 1024 * 1024,
  })
  if (result.status !== 0 || result.error) throw new CliError('process query failed', EXIT_OPERATION_FAILED)

  let parsed
  try {
    parsed = JSON.parse(result.stdout.replace(/^\uFEFF/, '').trim() || '[]')
  } catch {
    throw new CliError('process query returned invalid data', EXIT_OPERATION_FAILED)
  }
  const rows = Array.isArray(parsed) ? parsed : [parsed]
  return buildProcessTable(rows.map(row => ({
    pid: row.pid,
    ppid: row.ppid,
    creationId: row.creation,
    argv: normalizeCapturedArgv(row.argv),
    argvExact: row.argv_exact === true,
    zombie: false,
  })))
}

function captureLinuxProcessTable() {
  const procRoot = '/proc'
  const bootId = readRequiredText(path.join(procRoot, 'sys/kernel/random/boot_id'), 'boot identity').trim()
  if (!/^[a-f0-9-]{16,64}$/i.test(bootId)) {
    throw new CliError('boot identity is invalid', EXIT_OPERATION_FAILED)
  }

  let entries
  try {
    entries = fs.readdirSync(procRoot, { withFileTypes: true })
  } catch {
    throw new CliError('process query failed', EXIT_OPERATION_FAILED)
  }

  const rows = []
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^[1-9]\d*$/.test(entry.name)) continue
    const row = readLinuxProcess(procRoot, Number(entry.name), bootId)
    if (row) rows.push(row)
  }
  return buildProcessTable(rows)
}

function readLinuxProcess(procRoot, pid, bootId) {
  const processRoot = path.join(procRoot, String(pid))
  let initialStat
  let commandLine
  let finalStat
  try {
    initialStat = fs.readFileSync(path.join(processRoot, 'stat'), 'utf8')
    commandLine = fs.readFileSync(path.join(processRoot, 'cmdline'))
    finalStat = fs.readFileSync(path.join(processRoot, 'stat'), 'utf8')
  } catch (error) {
    if (isVanishedProcessError(error)) return undefined
    throw new CliError('exact process identity is unavailable', EXIT_OPERATION_FAILED)
  }

  const before = parseLinuxStat(initialStat, pid)
  const after = parseLinuxStat(finalStat, pid)
  if (before.startTicks !== after.startTicks) {
    throw new CliError('process identity changed during capture', EXIT_OPERATION_FAILED)
  }
  const argv = parseLinuxCommandLine(commandLine)
  return {
    pid,
    ppid: after.ppid,
    creationId: `${bootId}:${after.startTicks}`,
    argv,
    argvExact: true,
    commandSha256: sha256(commandLine),
    zombie: after.state === 'Z',
  }
}

function parseLinuxStat(value, expectedPid) {
  const close = value.lastIndexOf(')')
  if (close < 2 || value[close + 1] !== ' ') {
    throw new CliError('process stat is invalid', EXIT_OPERATION_FAILED)
  }
  const pid = Number(value.slice(0, value.indexOf(' ')))
  // The suffix begins at field 3 (state), so index 19 is field 22 (starttime).
  const fields = value.slice(close + 2).trim().split(/\s+/)
  const ppid = Number(fields[1])
  const startTicks = fields[19]
  if (pid !== expectedPid
    || !Number.isSafeInteger(ppid)
    || ppid < 0
    || typeof fields[0] !== 'string'
    || fields[0].length !== 1
    || !/^[0-9]+$/.test(startTicks ?? '')) {
    throw new CliError('process stat is invalid', EXIT_OPERATION_FAILED)
  }
  return { ppid, state: fields[0], startTicks }
}

function parseLinuxCommandLine(value) {
  if (value.length === 0) return []
  const argv = []
  let offset = 0
  while (offset < value.length) {
    const separator = value.indexOf(0, offset)
    const end = separator < 0 ? value.length : separator
    try {
      argv.push(new TextDecoder('utf-8', { fatal: true }).decode(value.subarray(offset, end)))
    } catch {
      throw new CliError('process argv is not valid UTF-8', EXIT_OPERATION_FAILED)
    }
    if (separator < 0) break
    offset = separator + 1
  }
  return argv
}

function readRequiredText(file, label) {
  try {
    return fs.readFileSync(file, 'utf8')
  } catch {
    throw new CliError(`${label} is unavailable`, EXIT_OPERATION_FAILED)
  }
}

function isVanishedProcessError(error) {
  return error && typeof error === 'object' && (error.code === 'ENOENT' || error.code === 'ESRCH')
}

function normalizeCapturedArgv(value) {
  if (!Array.isArray(value) || value.some(argument => typeof argument !== 'string')) return []
  return value
}

function buildProcessTable(rows) {
  const table = new Map()
  for (const row of rows) {
    const pid = Number(row.pid)
    const ppid = Number(row.ppid)
    const creationId = typeof row.creationId === 'string' ? row.creationId.trim() : ''
    const argv = Array.isArray(row.argv) && row.argv.every(argument => typeof argument === 'string') ? row.argv : []
    if (!Number.isSafeInteger(pid) || pid <= 0 || !Number.isSafeInteger(ppid) || ppid < 0) continue
    table.set(pid, {
      pid,
      ppid,
      creationId,
      argv,
      argvExact: row.argvExact === true,
      commandSha256: typeof row.commandSha256 === 'string'
        ? row.commandSha256
        : sha256(encodeArgv(argv)),
      zombie: row.zombie === true,
    })
  }
  return table
}

async function killWindowsTree(initialSnapshot, output) {
  let snapshot = initialSnapshot
  const extended = extendSnapshotData(snapshot, captureProcessTable())
  snapshot = extended.snapshot
  if (extended.added > 0) writeSnapshot(output, snapshot)

  const root = snapshot.processes.find(identity => identity.pid === snapshot.root_pid && identity.depth === 0)
  if (root && revalidateIdentity(root)) runTaskkill(root.pid)
  let remaining = await waitForTreeCleanup(
    () => findAliveIdentities(snapshot, captureProcessTable()),
    WINDOWS_ROOT_SETTLE_TIMEOUT_MS,
    WINDOWS_TREE_POLL_INTERVAL_MS,
  )
  for (const identity of [...remaining].sort((left, right) => right.depth - left.depth)) {
    if (revalidateIdentity(identity)) runTaskkill(identity.pid)
  }
  remaining = await waitForTreeCleanup(
    () => findAliveIdentities(snapshot, captureProcessTable()),
    WINDOWS_TREE_SETTLE_TIMEOUT_MS,
    WINDOWS_TREE_POLL_INTERVAL_MS,
  )
  return { residue: remaining.length }
}

export async function waitForTreeCleanup(inspect, timeoutMs, pollIntervalMs = 100) {
  const deadline = Date.now() + Math.max(0, timeoutMs)
  let remaining = inspect()
  while (remaining.length > 0 && Date.now() < deadline) {
    await delay(Math.min(pollIntervalMs, Math.max(0, deadline - Date.now())))
    remaining = inspect()
  }
  return remaining
}

function runTaskkill(pid) {
  spawnSync('taskkill.exe', ['/PID', String(pid), '/T', '/F'], {
    stdio: 'ignore',
    windowsHide: true,
    timeout: 10_000,
  })
}

async function killPosixTree(initialSnapshot, output) {
  let snapshot = initialSnapshot
  const stopped = new Map()
  try {
    for (let pass = 0; pass < 16; pass += 1) {
      const extended = extendSnapshotData(snapshot, captureProcessTable())
      snapshot = extended.snapshot
      if (extended.added > 0) writeSnapshot(output, snapshot)

      let stoppedThisPass = 0
      for (const identity of [...snapshot.processes].sort(compareStoredIdentities)) {
        const key = identityKey(identity)
        if (stopped.has(key) || !revalidateIdentity(identity)) continue
        if (sendSignal(identity.pid, 'SIGSTOP')) {
          stopped.set(key, identity)
          stoppedThisPass += 1
        }
      }
      if (extended.added === 0 && stoppedThisPass === 0) break
    }

    const finalExtension = extendSnapshotData(snapshot, captureProcessTable())
    snapshot = finalExtension.snapshot
    if (finalExtension.added > 0) writeSnapshot(output, snapshot)
    for (const identity of [...snapshot.processes].sort((left, right) => right.depth - left.depth || right.pid - left.pid)) {
      if (revalidateIdentity(identity)) sendSignal(identity.pid, 'SIGKILL')
    }
    await delay(100)
  } finally {
    let table
    try {
      table = captureProcessTable()
    } catch {
      table = new Map()
    }
    for (const identity of stopped.values()) {
      if (matchesIdentity(identity, table.get(identity.pid))) sendSignal(identity.pid, 'SIGCONT')
    }
  }
  return { residue: findAliveIdentities(snapshot, captureProcessTable()).length }
}

function sendSignal(pid, signal) {
  try {
    process.kill(pid, signal)
    return true
  } catch {
    return false
  }
}

function revalidateIdentity(identity) {
  const current = captureProcessTable().get(identity.pid)
  if (current) assertExactProcessIdentity(current)
  return matchesIdentity(identity, current)
}

function findAliveIdentities(snapshot, table) {
  return snapshot.processes.filter(identity => {
    const current = table.get(identity.pid)
    if (current) assertExactProcessIdentity(current)
    return matchesIdentity(identity, current)
  })
}

function matchesIdentity(expected, current) {
  return Boolean(current)
    && current.argvExact
    && !current.zombie
    && expected.pid === current.pid
    && expected.creation_id === current.creationId
    && expected.command_sha256 === current.commandSha256
}

function toStoredIdentity(current) {
  assertExactProcessIdentity(current)
  return {
    pid: current.pid,
    ppid: current.ppid,
    depth: current.depth,
    creation_id: current.creationId,
    command_sha256: current.commandSha256,
  }
}

function assertExactProcessIdentity(current) {
  if (!current.argvExact
    || typeof current.creationId !== 'string'
    || current.creationId.length === 0
    || !SHA256_PATTERN.test(current.commandSha256)) {
    throw new CliError('exact process identity is unavailable', EXIT_OPERATION_FAILED)
  }
}

function compareStoredIdentities(left, right) {
  return left.depth - right.depth || left.pid - right.pid || left.creation_id.localeCompare(right.creation_id)
}

function identityKey(identity) {
  return `${identity.pid}\0${identity.creation_id}\0${identity.command_sha256}`
}

function readSnapshot(output) {
  let stat
  try {
    stat = fs.lstatSync(output)
  } catch {
    throw new CliError('snapshot is unavailable')
  }
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) throw new CliError('snapshot must be a regular file')

  let snapshot
  try {
    snapshot = JSON.parse(fs.readFileSync(output, 'utf8'))
  } catch {
    throw new CliError('snapshot is invalid')
  }
  validateSnapshot(snapshot)
  return snapshot
}

function writeSnapshot(output, snapshot) {
  validateSnapshot(snapshot)
  ensureSafeSnapshotParent(output)
  assertSafeSnapshotOutput(output)

  const temporary = temporarySnapshotPath(output)
  try {
    writeDurableSnapshot(temporary, snapshot)
    fs.renameSync(temporary, output)
    fsyncDirectory(path.dirname(output))
  } finally {
    fs.rmSync(temporary, { force: true })
  }
}

function writeInitialSnapshot(output, snapshot) {
  validateSnapshot(snapshot)
  ensureSafeSnapshotParent(output)
  const temporary = temporarySnapshotPath(output)
  try {
    writeDurableSnapshot(temporary, snapshot)
    fs.linkSync(temporary, output)
    fsyncDirectory(path.dirname(output))
  } catch (error) {
    if (error?.code === 'EEXIST') throw new CliError('snapshot output already exists')
    throw error
  } finally {
    fs.rmSync(temporary, { force: true })
  }
}

function assertSafeSnapshotOutput(output) {
  const stat = fs.lstatSync(output)
  if (!stat.isFile() || stat.isSymbolicLink() || stat.nlink !== 1) throw new CliError('snapshot output is unsafe')
}

function ensureSafeSnapshotParent(output) {
  const parent = path.dirname(output)
  fs.mkdirSync(parent, { recursive: true })
  const stat = fs.lstatSync(parent)
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new CliError('snapshot parent is unsafe')
}

function temporarySnapshotPath(output) {
  return path.join(
    path.dirname(output),
    `.${path.basename(output)}.${process.pid}.${crypto.randomBytes(8).toString('hex')}.tmp`,
  )
}

function writeDurableSnapshot(file, snapshot) {
  const descriptor = fs.openSync(file, 'wx', 0o600)
  try {
    fs.writeFileSync(descriptor, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8')
    fs.fsyncSync(descriptor)
  } finally {
    fs.closeSync(descriptor)
  }
}

function fsyncDirectory(directory) {
  let descriptor
  try {
    descriptor = fs.openSync(directory, 'r')
    fs.fsyncSync(descriptor)
  } catch (error) {
    if (process.platform !== 'win32') throw error
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor)
  }
}

function validateSnapshot(snapshot) {
  if (!isPlainObject(snapshot)) throw new CliError('snapshot is invalid')
  assertExactKeys(snapshot, ['schema_version', 'platform', 'captured_at', 'root_pid', 'entry_sha256', 'processes'])
  if (snapshot.schema_version !== SCHEMA_VERSION || snapshot.platform !== process.platform) {
    throw new CliError('snapshot is incompatible')
  }
  if (!Number.isSafeInteger(snapshot.root_pid) || snapshot.root_pid <= 0) throw new CliError('snapshot root is invalid')
  if (!SHA256_PATTERN.test(snapshot.entry_sha256)) throw new CliError('snapshot entry identity is invalid')
  if (typeof snapshot.captured_at !== 'string' || !Number.isFinite(Date.parse(snapshot.captured_at))) {
    throw new CliError('snapshot timestamp is invalid')
  }
  if (!Array.isArray(snapshot.processes) || snapshot.processes.length === 0 || snapshot.processes.length > MAX_PROCESSES) {
    throw new CliError('snapshot process list is invalid')
  }

  const identities = new Set()
  let roots = 0
  for (const identity of snapshot.processes) {
    if (!isPlainObject(identity)) throw new CliError('snapshot process identity is invalid')
    assertExactKeys(identity, ['pid', 'ppid', 'depth', 'creation_id', 'command_sha256'])
    if (!Number.isSafeInteger(identity.pid) || identity.pid <= 0 || identity.pid > 2_147_483_647) {
      throw new CliError('snapshot pid is invalid')
    }
    if (!Number.isSafeInteger(identity.ppid) || identity.ppid < 0 || identity.ppid > 2_147_483_647) {
      throw new CliError('snapshot parent pid is invalid')
    }
    if (!Number.isSafeInteger(identity.depth) || identity.depth < 0 || identity.depth > MAX_PROCESSES) {
      throw new CliError('snapshot depth is invalid')
    }
    if (typeof identity.creation_id !== 'string' || identity.creation_id.length === 0 || identity.creation_id.length > 256) {
      throw new CliError('snapshot creation identity is invalid')
    }
    if (!SHA256_PATTERN.test(identity.command_sha256)) throw new CliError('snapshot command identity is invalid')
    const key = identityKey(identity)
    if (identities.has(key)) throw new CliError('snapshot contains duplicate identities')
    identities.add(key)
    if (identity.pid === snapshot.root_pid && identity.depth === 0) roots += 1
  }
  if (roots !== 1) throw new CliError('snapshot root identity is invalid')
}

function assertExactKeys(value, expected) {
  const actual = Object.keys(value).sort()
  const wanted = [...expected].sort()
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new CliError('snapshot contains unsupported data')
  }
}

function assertOptionalBinding(snapshot, options) {
  if (options.pid !== undefined && options.pid !== snapshot.root_pid) throw new CliError('snapshot pid does not match')
  if (options.entry !== undefined && sha256(normalizeEntry(options.entry)) !== snapshot.entry_sha256) {
    throw new CliError('snapshot entry does not match')
  }
}

function argvContainsEntry(argv, entry) {
  if (!Array.isArray(argv)) return false
  const normalizedEntry = normalizeEntry(entry)
  return argv.some(argument => normalizeArgvToken(argument) === normalizedEntry)
}

function normalizeEntry(entry) {
  const normalized = path.resolve(entry).replaceAll('\\', '/')
  return process.platform === 'win32' ? normalized.toLowerCase() : normalized
}

function normalizeArgvToken(argument) {
  const normalized = argument.replaceAll('\\', '/')
  return process.platform === 'win32' ? normalized.toLowerCase() : normalized
}

function encodeArgv(argv) {
  const chunks = []
  for (const argument of argv) {
    chunks.push(Buffer.from(argument, 'utf8'), Buffer.from([0]))
  }
  return Buffer.concat(chunks)
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function printResult(status, count) {
  process.stdout.write(`${JSON.stringify({ status, count })}\n`)
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

const isDirectExecution = process.argv[1]
  && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url

if (isDirectExecution) {
  main().then(
    exitCode => { process.exitCode = exitCode },
    error => {
      const exitCode = error instanceof CliError ? error.exitCode : EXIT_OPERATION_FAILED
      const message = error instanceof CliError ? error.message : 'operation failed'
      process.stderr.write(`process-tree: ${message}\n`)
      process.exitCode = exitCode
    },
  )
}
