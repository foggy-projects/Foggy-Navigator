#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const SDK_PACKAGE = '@openai/codex-sdk'
const OFFICIAL_NPM_REGISTRY = 'https://registry.npmjs.org/'

export class SdkPreflightError extends Error {
  constructor(message, exitCode = 1) {
    super(message)
    this.name = 'SdkPreflightError'
    this.exitCode = exitCode
  }
}

export function parseSemver(value) {
  if (typeof value !== 'string') return null
  const match = value.trim().match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/)
  if (!match) return null
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4] ? match[4].split('.') : [],
  }
}

function comparePrerelease(left, right) {
  if (left.length === 0 && right.length === 0) return 0
  if (left.length === 0) return 1
  if (right.length === 0) return -1

  const length = Math.max(left.length, right.length)
  for (let index = 0; index < length; index += 1) {
    if (left[index] === undefined) return -1
    if (right[index] === undefined) return 1
    if (left[index] === right[index]) continue

    const leftNumeric = /^\d+$/.test(left[index])
    const rightNumeric = /^\d+$/.test(right[index])
    if (leftNumeric && rightNumeric) return Number(left[index]) < Number(right[index]) ? -1 : 1
    if (leftNumeric !== rightNumeric) return leftNumeric ? -1 : 1
    return left[index] < right[index] ? -1 : 1
  }
  return 0
}

export function compareSemver(leftValue, rightValue) {
  const left = parseSemver(leftValue)
  const right = parseSemver(rightValue)
  if (!left || !right) {
    throw new SdkPreflightError(`Invalid semantic version comparison: ${leftValue} vs ${rightValue}`, 2)
  }

  for (const key of ['major', 'minor', 'patch']) {
    if (left[key] !== right[key]) return left[key] < right[key] ? -1 : 1
  }
  return comparePrerelease(left.prerelease, right.prerelease)
}

export function readRuntimeRequirements(workerDir) {
  const requirementsPath = path.join(workerDir, 'runtime-requirements.json')
  let parsed
  try {
    parsed = JSON.parse(fs.readFileSync(requirementsPath, 'utf8'))
  } catch (error) {
    throw new SdkPreflightError(`Cannot read runtime requirements at ${requirementsPath}: ${error.message}`, 2)
  }

  const minimumVersion = parsed?.codexSdk?.minimumVersion
  const repairVersion = parsed?.codexSdk?.repairVersion
  if (!parseSemver(minimumVersion) || !parseSemver(repairVersion)) {
    throw new SdkPreflightError('runtime-requirements.json must define valid codexSdk versions', 2)
  }
  if (compareSemver(repairVersion, minimumVersion) < 0) {
    throw new SdkPreflightError('codexSdk.repairVersion must be greater than or equal to minimumVersion', 2)
  }
  return { minimumVersion, repairVersion }
}

export function readInstalledSdkVersion(workerDir) {
  const packagePath = path.join(workerDir, 'node_modules', '@openai', 'codex-sdk', 'package.json')
  if (!fs.existsSync(packagePath)) return null
  try {
    const version = JSON.parse(fs.readFileSync(packagePath, 'utf8'))?.version
    return typeof version === 'string' && version.trim() ? version.trim() : 'unknown'
  } catch {
    return 'unknown'
  }
}

export function inspectSdkCompatibility(workerDir) {
  const requirements = readRuntimeRequirements(workerDir)
  const installedVersion = readInstalledSdkVersion(workerDir)
  const parsedInstalled = parseSemver(installedVersion)
  const compatible = Boolean(parsedInstalled) && compareSemver(installedVersion, requirements.minimumVersion) >= 0
  let reason = 'compatible'
  if (installedVersion === null) reason = 'not-installed'
  else if (!parsedInstalled) reason = 'invalid-version'
  else if (!compatible) reason = 'below-minimum'
  return { ...requirements, installedVersion, compatible, reason }
}

export function parseBoolean(value, fallback = true) {
  if (value === undefined || value === null || String(value).trim() === '') return fallback
  const normalized = String(value).trim().toLowerCase()
  if (['1', 'true', 'yes', 'on'].includes(normalized)) return true
  if (['0', 'false', 'no', 'off'].includes(normalized)) return false
  throw new SdkPreflightError(`Invalid boolean value: ${value}`, 2)
}

function normalizeRegistry(value) {
  return String(value || '').trim().replace(/\/+$/, '').toLowerCase()
}

export function defaultRunCommand(command, args, options = {}) {
  const capture = Boolean(options.capture)
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: 'utf8',
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
  })
  return {
    status: result.status ?? 1,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
  }
}

export function resolveNpmInvocation(env = process.env) {
  const candidates = [
    env.npm_execpath,
    path.join(path.dirname(process.execPath), 'node_modules', 'npm', 'bin', 'npm-cli.js'),
  ].filter(Boolean)
  const npmCliPath = candidates.find(candidate => fs.existsSync(candidate))
  if (npmCliPath) {
    return { command: process.execPath, argsPrefix: [npmCliPath] }
  }
  if (process.platform === 'win32') {
    return { command: env.ComSpec || 'cmd.exe', argsPrefix: ['/d', '/s', '/c', 'npm'] }
  }
  return { command: 'npm', argsPrefix: [] }
}

export function installSdkWithRegistryFallback(options) {
  const {
    workerDir,
    version,
    omitDev = false,
    runCommand = defaultRunCommand,
    logger = console,
    npmInvocation = resolveNpmInvocation(),
  } = options
  const installArgs = ['install', `${SDK_PACKAGE}@${version}`]
  if (omitDev) installArgs.push('--omit=dev')

  logger.log(`[codex-sdk] Running: npm ${installArgs.join(' ')}`)
  const primary = runCommand(
    npmInvocation.command,
    [...npmInvocation.argsPrefix, ...installArgs],
    { cwd: workerDir, capture: false }
  )
  if (primary.status === 0) return true

  const registryResult = runCommand(
    npmInvocation.command,
    [...npmInvocation.argsPrefix, '--loglevel=silent', 'config', 'get', 'registry'],
    { cwd: workerDir, capture: true }
  )
  const configuredRegistry = registryResult.status === 0 ? registryResult.stdout.trim() : ''
  if (normalizeRegistry(configuredRegistry) === normalizeRegistry(OFFICIAL_NPM_REGISTRY)) return false

  logger.warn(`[codex-sdk] npm install failed using registry: ${configuredRegistry || 'unknown'}`)
  logger.warn(`[codex-sdk] Retrying with official registry: ${OFFICIAL_NPM_REGISTRY}`)
  const retryArgs = [...installArgs, `--registry=${OFFICIAL_NPM_REGISTRY}`]
  const retry = runCommand(
    npmInvocation.command,
    [...npmInvocation.argsPrefix, ...retryArgs],
    { cwd: workerDir, capture: false }
  )
  return retry.status === 0
}

export function validateTargetVersion(workerDir, targetVersion, force = false) {
  const { minimumVersion } = readRuntimeRequirements(workerDir)
  const parsedTarget = parseSemver(targetVersion)
  const compatible = Boolean(parsedTarget) && compareSemver(targetVersion, minimumVersion) >= 0
  if (compatible) return { minimumVersion, targetVersion, compatible: true, forced: false }
  if (force) return { minimumVersion, targetVersion, compatible: false, forced: true }
  throw new SdkPreflightError(
    `Refusing Codex SDK ${targetVersion}; this worker requires >= ${minimumVersion}. Use --force only for development recovery.`,
    2
  )
}

export function ensureSdk(options) {
  const {
    workerDir,
    autoUpdate = true,
    omitDev = false,
    install = installSdkWithRegistryFallback,
    logger = console,
  } = options
  let status = inspectSdkCompatibility(workerDir)
  if (status.compatible) {
    logger.log(`[codex-sdk] Compatible SDK ${status.installedVersion} (minimum ${status.minimumVersion}).`)
    return { ...status, repaired: false }
  }

  const installedLabel = status.installedVersion || 'not-installed'
  if (!autoUpdate) {
    throw new SdkPreflightError(
      `Codex SDK ${installedLabel} does not satisfy >= ${status.minimumVersion}; automatic repair is disabled.`,
      3
    )
  }

  logger.warn(
    `[codex-sdk] SDK ${installedLabel} does not satisfy >= ${status.minimumVersion}; repairing to ${status.repairVersion}.`
  )
  const installed = install({
    workerDir,
    version: status.repairVersion,
    omitDev,
    logger,
  })
  if (!installed) {
    throw new SdkPreflightError(`Failed to install Codex SDK ${status.repairVersion}.`, 4)
  }

  status = inspectSdkCompatibility(workerDir)
  if (!status.compatible) {
    throw new SdkPreflightError(
      `Codex SDK repair completed but installed version ${status.installedVersion || 'not-installed'} still does not satisfy >= ${status.minimumVersion}.`,
      4
    )
  }
  logger.log(`[codex-sdk] Repair complete: ${status.installedVersion}.`)
  return { ...status, repaired: true }
}

function parseArgs(argv) {
  const result = {
    workerDir: path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
    omitDev: false,
    autoUpdate: undefined,
    checkTarget: '',
    force: false,
  }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--worker-dir') result.workerDir = path.resolve(argv[++index])
    else if (arg === '--omit-dev') result.omitDev = true
    else if (arg === '--auto-update') result.autoUpdate = true
    else if (arg === '--no-auto-update') result.autoUpdate = false
    else if (arg === '--check-target') result.checkTarget = argv[++index]
    else if (arg === '--force') result.force = true
    else throw new SdkPreflightError(`Unknown argument: ${arg}`, 2)
  }
  return result
}

export function runMain(argv = process.argv.slice(2), env = process.env) {
  const args = parseArgs(argv)
  if (args.checkTarget) {
    const result = validateTargetVersion(args.workerDir, args.checkTarget, args.force)
    if (result.forced) {
      console.warn(
        `[codex-sdk] WARNING: forcing SDK ${result.targetVersion} below required ${result.minimumVersion}. The next normal start will repair it.`
      )
    } else {
      console.log(`[codex-sdk] Target SDK ${result.targetVersion} satisfies >= ${result.minimumVersion}.`)
    }
    return result
  }

  const autoUpdate = args.autoUpdate ?? parseBoolean(env.CODEX_WORKER_AUTO_UPDATE_SDK, true)
  return ensureSdk({
    workerDir: args.workerDir,
    autoUpdate,
    omitDev: args.omitDev,
  })
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : ''
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    runMain()
  } catch (error) {
    console.error(`[codex-sdk] ${error.message}`)
    process.exit(error instanceof SdkPreflightError ? error.exitCode : 1)
  }
}
