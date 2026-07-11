import crypto from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import dotenv from 'dotenv'
import { assertCodexHomeIsolation } from './path-guards.js'

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
dotenv.config({ path: path.join(packageRoot, '.env') })

const DEFAULT_ALIASES: Readonly<Record<string, string>> = Object.freeze({
  'codex-latest': 'gpt-5.6-sol',
  'codex-terra': 'gpt-5.6-terra',
  'codex-luna': 'gpt-5.6-luna',
  'codex-fast': 'gpt-5.6-sol:low',
  'codex-deep': 'gpt-5.6-sol:high',
  'codex-xhigh': 'gpt-5.6-sol:xhigh',
  'codex-max': 'gpt-5.6-sol:max',
  'codex-ultra': 'gpt-5.6-sol:ultra',
})

export interface AppConfig {
  port: number
  host: string
  workerName: string
  workerToken: string
  runtimeId: string
  runtimeRevision: number
  instanceId: string
  openaiApiKey: string
  openaiBaseUrl: string
  codexHome: string
  codexBizHomeRoot: string
  allowedCwds: string[]
  maxConcurrentTasks: number
  maxQueuedTasks: number
  poolMaxInstances: number
  poolMaxInstancesPerLane: number
  poolMaxQueue: number
  poolAcquireTimeoutMs: number
  poolIdleTtlMs: number
  poolMaxLifetimeMs: number
  poolMaxTasksPerInstance: number
  shutdownTimeoutMs: number
  abortWaitTimeoutMs: number
  stateDir: string
  stateEncryptionKey?: Buffer
  defaultModel: string
  modelAliases: Record<string, string>
}

export function createConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const stateDir = optionalAbsolutePath(env.CODEX_APP_SERVER_STATE_DIR)
    || path.join(packageRoot, 'logs', 'state')
  const result: AppConfig = {
    port: integer(env.CODEX_APP_SERVER_WORKER_PORT, 3062, 1, 65535, 'CODEX_APP_SERVER_WORKER_PORT'),
    host: nonEmpty(env.CODEX_APP_SERVER_WORKER_HOST || '127.0.0.1', 'CODEX_APP_SERVER_WORKER_HOST', 255),
    workerName: nonEmpty(env.CODEX_APP_SERVER_WORKER_NAME || 'codex-app-server-worker-default', 'CODEX_APP_SERVER_WORKER_NAME', 128),
    workerToken: token(env.CODEX_APP_SERVER_WORKER_TOKEN),
    runtimeId: nonEmpty(env.CODEX_APP_SERVER_RUNTIME_ID || 'codex-app-server-primary', 'CODEX_APP_SERVER_RUNTIME_ID', 64),
    runtimeRevision: integer(env.CODEX_APP_SERVER_RUNTIME_REVISION, 1, 1, 2_147_483_647, 'CODEX_APP_SERVER_RUNTIME_REVISION'),
    instanceId: resolveInstanceId(env.CODEX_APP_SERVER_INSTANCE_ID, stateDir),
    openaiApiKey: token(env.OPENAI_API_KEY, 512),
    openaiBaseUrl: optionalHttpUrl(env.OPENAI_BASE_URL),
    codexHome: optionalAbsolutePath(env.CODEX_HOME),
    codexBizHomeRoot: optionalAbsolutePath(env.CODEX_BIZ_HOME_ROOT),
    allowedCwds: absolutePathList(env.CODEX_APP_SERVER_ALLOWED_CWDS),
    maxConcurrentTasks: integer(env.CODEX_APP_SERVER_MAX_CONCURRENT_TASKS, 32, 1, 128, 'CODEX_APP_SERVER_MAX_CONCURRENT_TASKS'),
    maxQueuedTasks: integer(env.CODEX_APP_SERVER_MAX_QUEUED_TASKS, 64, 1, 1_024, 'CODEX_APP_SERVER_MAX_QUEUED_TASKS'),
    poolMaxInstances: integer(env.CODEX_APP_SERVER_POOL_MAX_INSTANCES, 6, 1, 64, 'CODEX_APP_SERVER_POOL_MAX_INSTANCES'),
    poolMaxInstancesPerLane: integer(env.CODEX_APP_SERVER_POOL_MAX_INSTANCES_PER_LANE, 4, 1, 64, 'CODEX_APP_SERVER_POOL_MAX_INSTANCES_PER_LANE'),
    poolMaxQueue: integer(env.CODEX_APP_SERVER_POOL_MAX_QUEUE, 32, 1, 1_024, 'CODEX_APP_SERVER_POOL_MAX_QUEUE'),
    poolAcquireTimeoutMs: integer(env.CODEX_APP_SERVER_POOL_ACQUIRE_TIMEOUT_MS, 30_000, 100, 600_000, 'CODEX_APP_SERVER_POOL_ACQUIRE_TIMEOUT_MS'),
    poolIdleTtlMs: integer(env.CODEX_APP_SERVER_POOL_IDLE_TTL_MS, 300_000, 100, 86_400_000, 'CODEX_APP_SERVER_POOL_IDLE_TTL_MS'),
    poolMaxLifetimeMs: integer(env.CODEX_APP_SERVER_POOL_MAX_LIFETIME_MS, 3_600_000, 1_000, 86_400_000, 'CODEX_APP_SERVER_POOL_MAX_LIFETIME_MS'),
    poolMaxTasksPerInstance: integer(env.CODEX_APP_SERVER_POOL_MAX_TASKS_PER_INSTANCE, 100, 1, 100_000, 'CODEX_APP_SERVER_POOL_MAX_TASKS_PER_INSTANCE'),
    shutdownTimeoutMs: integer(env.CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS, 30_000, 100, 600_000, 'CODEX_APP_SERVER_SHUTDOWN_TIMEOUT_MS'),
    abortWaitTimeoutMs: integer(env.CODEX_APP_SERVER_ABORT_WAIT_TIMEOUT_MS, 7_000, 100, 60_000, 'CODEX_APP_SERVER_ABORT_WAIT_TIMEOUT_MS'),
    stateDir,
    stateEncryptionKey: parseEncryptionKey(env.CODEX_APP_SERVER_STATE_KEY),
    defaultModel: model(env.CODEX_DEFAULT_MODEL || 'codex-latest'),
    modelAliases: aliases(env.CODEX_MODEL_ALIASES),
  }
  if (result.poolMaxInstancesPerLane > result.poolMaxInstances) {
    throw new Error('CODEX_APP_SERVER_POOL_MAX_INSTANCES_PER_LANE must not exceed CODEX_APP_SERVER_POOL_MAX_INSTANCES')
  }
  if (result.maxConcurrentTasks > result.poolMaxInstances + result.poolMaxQueue) {
    throw new Error('CODEX_APP_SERVER_MAX_CONCURRENT_TASKS must not exceed pool instances plus pool queue')
  }
  if (result.maxConcurrentTasks > result.poolMaxInstancesPerLane + result.poolMaxQueue) {
    throw new Error('CODEX_APP_SERVER_MAX_CONCURRENT_TASKS must not exceed per-lane instances plus pool queue')
  }
  if (result.poolIdleTtlMs > result.poolMaxLifetimeMs) {
    throw new Error('CODEX_APP_SERVER_POOL_IDLE_TTL_MS must not exceed CODEX_APP_SERVER_POOL_MAX_LIFETIME_MS')
  }
  if (result.codexHome) {
    assertCodexHomeIsolation(result)
  }
  return result
}

function nonEmpty(raw: string, field: string, max: number): string {
  const value = raw.trim()
  if (!value || value.length > max) throw new Error(`${field} must be 1-${max} characters`)
  return value
}

function token(raw: string | undefined, max = 1024): string {
  const value = (raw || '').trim()
  if (/\s/.test(value) || value.length > max) throw new Error('token configuration is invalid')
  return value
}

function integer(raw: string | undefined, fallback: number, min: number, max: number, field: string): number {
  const value = raw?.trim() ? Number(raw) : fallback
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${field} must be an integer between ${min} and ${max}`)
  }
  return value
}

function optionalAbsolutePath(raw: string | undefined): string {
  const value = (raw || '').trim()
  if (!value) return ''
  if (!path.isAbsolute(value)) throw new Error('configured filesystem paths must be absolute')
  return path.normalize(value)
}

function absolutePathList(raw: string | undefined): string[] {
  if (!raw?.trim()) return []
  return [...new Set(raw.split(',').map(value => optionalAbsolutePath(value)).filter(Boolean))]
}

function optionalHttpUrl(raw: string | undefined): string {
  const value = (raw || '').trim()
  if (!value) return ''
  const parsed = new URL(value)
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error('OPENAI_BASE_URL must use http or https')
  return value.replace(/\/+$/, '')
}

function parseEncryptionKey(raw: string | undefined): Buffer | undefined {
  const value = (raw || '').trim()
  if (!value) return undefined
  const key = /^[0-9a-f]{64}$/i.test(value) ? Buffer.from(value, 'hex') : Buffer.from(value, 'base64')
  if (key.length !== 32) throw new Error('CODEX_APP_SERVER_STATE_KEY must encode exactly 32 bytes')
  return key
}

function model(raw: string): string {
  const value = nonEmpty(raw, 'CODEX_DEFAULT_MODEL', 128)
  if (/\s/.test(value)) throw new Error('CODEX_DEFAULT_MODEL must not contain whitespace')
  return value
}

function aliases(raw: string | undefined): Record<string, string> {
  const result = { ...DEFAULT_ALIASES }
  if (!raw?.trim()) return result
  const parsed = JSON.parse(raw) as unknown
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('CODEX_MODEL_ALIASES must be a JSON object')
  }
  for (const [key, value] of Object.entries(parsed)) {
    if (!key.trim() || key.includes(':') || typeof value !== 'string') {
      throw new Error('CODEX_MODEL_ALIASES contains an invalid entry')
    }
    const aliasKey = key.trim().toLowerCase()
    const aliasValue = model(value)
    if (Object.prototype.hasOwnProperty.call(DEFAULT_ALIASES, aliasKey) && DEFAULT_ALIASES[aliasKey] !== aliasValue) {
      throw new Error(`CODEX_MODEL_ALIASES cannot override built-in alias: ${aliasKey}`)
    }
    result[aliasKey] = aliasValue
  }
  return result
}

export function createTestEncryptionKey(): Buffer {
  return crypto.createHash('sha256').update('codex-app-server-worker-test-key').digest()
}

export function resolveInstanceId(
  configured: string | undefined,
  stateDir: string,
  hostname = os.hostname(),
): string {
  if (configured?.trim()) return nonEmpty(configured, 'CODEX_APP_SERVER_INSTANCE_ID', 128)
  const normalizedStateDir = process.platform === 'win32'
    ? path.resolve(stateDir).toLowerCase()
    : path.resolve(stateDir)
  const digest = crypto.createHash('sha256')
    .update(`${hostname}\0${normalizedStateDir}`)
    .digest('hex')
    .slice(0, 20)
  const safeHostname = hostname.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80) || 'host'
  return `${safeHostname}-${digest}`
}

export const config = createConfig()
