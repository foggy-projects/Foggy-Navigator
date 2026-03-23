import dotenv from 'dotenv'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
dotenv.config({ path: path.resolve(__dirname, '..', '.env') })

const VALID_LOG_LEVELS = new Set(['debug', 'info', 'warn', 'error'])

export interface AppConfig {
  port: number
  host: string
  workerName: string
  openaiApiKey: string
  workerToken: string
  allowedCwds: string[]
  logLevel: 'debug' | 'info' | 'warn' | 'error'
}

function requireNonEmptyString(value: string, field: string, maxLength: number): string {
  const trimmed = value.trim()
  if (!trimmed) {
    throw new Error(`${field} must not be empty`)
  }
  if (trimmed.length > maxLength) {
    throw new Error(`${field} is too long`)
  }
  return trimmed
}

function parsePort(rawPort: string | undefined): number {
  const value = (rawPort || '3051').trim()
  if (!/^\d+$/.test(value)) {
    throw new Error('CODEX_WORKER_PORT must be an integer')
  }

  const port = Number(value)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('CODEX_WORKER_PORT must be between 1 and 65535')
  }
  return port
}

function parseHost(rawHost: string | undefined): string {
  return requireNonEmptyString(rawHost || '0.0.0.0', 'CODEX_WORKER_HOST', 255)
}

function parseWorkerName(rawWorkerName: string | undefined): string {
  return requireNonEmptyString(rawWorkerName || 'codex-worker-default', 'CODEX_WORKER_NAME', 128)
}

function parseToken(rawToken: string | undefined): string {
  const token = (rawToken || '').trim()
  if (/\s/.test(token)) {
    throw new Error('CODEX_WORKER_TOKEN must not contain whitespace')
  }
  return token
}

function parseApiKey(rawApiKey: string): string {
  if (!rawApiKey) return ''
  if (/\s/.test(rawApiKey)) {
    throw new Error('OPENAI_API_KEY must not contain whitespace')
  }
  if (rawApiKey.length > 512) {
    throw new Error('OPENAI_API_KEY is too long')
  }
  return rawApiKey
}

function parseAllowedCwds(rawAllowedCwds: string | undefined): string[] {
  if (!rawAllowedCwds) return []

  const values = rawAllowedCwds
    .split(',')
    .map(s => s.trim())
    .filter(Boolean)

  const normalized = new Set<string>()
  for (const cwd of values) {
    if (!path.isAbsolute(cwd)) {
      throw new Error(`CODEX_ALLOWED_CWDS entries must be absolute paths: ${cwd}`)
    }
    normalized.add(path.normalize(cwd))
  }

  return Array.from(normalized)
}

function parseLogLevel(rawLogLevel: string | undefined): AppConfig['logLevel'] {
  const value = (rawLogLevel || 'info').trim().toLowerCase()
  if (!VALID_LOG_LEVELS.has(value)) {
    throw new Error('CODEX_LOG_LEVEL must be one of: debug, info, warn, error')
  }
  return value as AppConfig['logLevel']
}

export function createConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const openaiApiKey = parseApiKey(getOptionalEnvFromEnv(env, 'OPENAI_API_KEY'))

  return {
    port: parsePort(env.CODEX_WORKER_PORT),
    host: parseHost(env.CODEX_WORKER_HOST),
    workerName: parseWorkerName(env.CODEX_WORKER_NAME),
    openaiApiKey,
    workerToken: parseToken(env.CODEX_WORKER_TOKEN),
    allowedCwds: parseAllowedCwds(env.CODEX_ALLOWED_CWDS),
    logLevel: parseLogLevel(env.CODEX_LOG_LEVEL),
  }
}

function getOptionalEnvFromEnv(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim() || ''
  if (!value) return ''

  const placeholders = new Set(['sk-xxx', 'your-api-key-here', 'replace-me'])
  return placeholders.has(value.toLowerCase()) ? '' : value
}

export const config = createConfig()
