import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const WORKER_ID_KEY = 'CODEX_NAVIGATOR_WORKER_ID'
const LEDGER_DIR_KEY = 'CODEX_TERMINATION_OPERATION_LEDGER_DIR'

function keyPattern(key) {
  return new RegExp(`^\\s*${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*=(.*)$`)
}

function decodeEnvValue(rawValue, field) {
  const value = rawValue.trim()
  if (!value) return ''
  if (value.startsWith('"')) {
    try {
      const decoded = JSON.parse(value)
      if (typeof decoded !== 'string') throw new Error('not a string')
      return decoded
    } catch {
      throw new Error(`${field} contains an invalid quoted value`)
    }
  }
  if (value.startsWith("'")) {
    if (!value.endsWith("'") || value.length < 2) {
      throw new Error(`${field} contains an invalid quoted value`)
    }
    return value.slice(1, -1)
  }
  return value.replace(/\s+#.*$/, '').trim()
}

function readAssignments(content, key) {
  const pattern = keyPattern(key)
  const values = []
  for (const line of content.split(/\r?\n/)) {
    const match = line.match(pattern)
    if (match) values.push(decodeEnvValue(match[1] || '', key))
  }
  return values
}

function resolveExistingValue(content, key) {
  const values = readAssignments(content, key)
  if (values.length === 0) return ''
  const unique = new Set(values)
  if (unique.size !== 1) {
    throw new Error(`${key} has conflicting active assignments`)
  }
  return values[0] || ''
}

function upsertAssignment(content, key, value) {
  const pattern = keyPattern(key)
  const replacement = `${key}=${JSON.stringify(value)}`
  const output = []
  let replaced = false
  for (const line of content.split(/\r?\n/)) {
    if (!pattern.test(line)) {
      output.push(line)
      continue
    }
    if (!replaced) {
      output.push(replacement)
      replaced = true
    }
  }
  if (!replaced) {
    if (output.length > 0 && output.at(-1) !== '') output.push('')
    output.push(replacement)
  }
  while (output.length > 1 && output.at(-1) === '' && output.at(-2) === '') output.pop()
  return `${output.join('\n').replace(/\n*$/, '')}\n`
}

function validateWorkerId(workerId) {
  if (!workerId) return
  if (/\s/.test(workerId) || workerId.length > 128) {
    throw new Error('Worker identity must be non-empty, contain no whitespace, and be at most 128 characters')
  }
}

function ensureLedgerDirectory(ledgerDir) {
  if (!path.isAbsolute(ledgerDir)) {
    throw new Error('Termination ledger path must be absolute')
  }
  fs.mkdirSync(ledgerDir, { recursive: true, mode: 0o700 })
  const stat = fs.lstatSync(ledgerDir)
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error('Termination ledger path must be a real directory')
  }
  fs.accessSync(ledgerDir, fs.constants.R_OK | fs.constants.W_OK)
}

export function configureInstallEnv({
  envPath,
  installDir,
  workerId = process.env.CODEX_NAVIGATOR_WORKER_ID || '',
  ledgerDir = process.env.CODEX_TERMINATION_OPERATION_LEDGER_DIR || '',
}) {
  if (!envPath || !installDir) throw new Error('Installer environment path and install directory are required')
  let content = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : ''

  const requestedWorkerId = workerId.trim()
  const existingWorkerId = resolveExistingValue(content, WORKER_ID_KEY).trim()
  const effectiveWorkerId = requestedWorkerId || existingWorkerId
  validateWorkerId(effectiveWorkerId)
  if (effectiveWorkerId) content = upsertAssignment(content, WORKER_ID_KEY, effectiveWorkerId)

  const requestedLedgerDir = ledgerDir.trim()
  const existingLedgerDir = resolveExistingValue(content, LEDGER_DIR_KEY).trim()
  const effectiveLedgerDir = requestedLedgerDir
    || existingLedgerDir
    || path.resolve(installDir, 'logs', 'termination-operations')
  ensureLedgerDirectory(effectiveLedgerDir)
  content = upsertAssignment(content, LEDGER_DIR_KEY, effectiveLedgerDir)

  fs.writeFileSync(envPath, content, { encoding: 'utf8', mode: 0o600 })
  return {
    workerIdConfigured: Boolean(effectiveWorkerId),
    ledgerReady: true,
    ledgerDir: effectiveLedgerDir,
  }
}

const isCli = process.argv[1]
  && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))
if (isCli) {
  try {
    const [envPath, installDir] = process.argv.slice(2)
    const result = configureInstallEnv({ envPath, installDir })
    process.stdout.write(
      `Termination configuration: identity ${result.workerIdConfigured ? 'configured' : 'missing'}, ledger ready\n`,
    )
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : 'Termination configuration failed'}\n`)
    process.exitCode = 1
  }
}
