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
  workerToken: string
  allowedCwds: string[]
  maxConcurrentTasks: number
  logLevel: 'debug' | 'info' | 'warn' | 'error'
  defaultModel: string
  modelAliases: Record<string, string>
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
  const value = (rawPort || '3071').trim()
  if (!/^\d+$/.test(value)) {
    throw new Error('GEMINI_WORKER_PORT must be an integer')
  }
  const port = Number(value)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('GEMINI_WORKER_PORT must be between 1 and 65535')
  }
  return port
}

function parseHost(rawHost: string | undefined): string {
  return requireNonEmptyString(rawHost || '0.0.0.0', 'GEMINI_WORKER_HOST', 255)
}

function parseWorkerName(rawWorkerName: string | undefined): string {
  return requireNonEmptyString(rawWorkerName || 'gemini-worker-default', 'GEMINI_WORKER_NAME', 128)
}

function parseToken(rawToken: string | undefined): string {
  const token = (rawToken || '').trim()
  if (/\s/.test(token)) {
    throw new Error('GEMINI_WORKER_TOKEN must not contain whitespace')
  }
  return token
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
      throw new Error(`GEMINI_ALLOWED_CWDS entries must be absolute paths: ${cwd}`)
    }
    normalized.add(path.normalize(cwd))
  }
  return Array.from(normalized)
}

function parseLogLevel(rawLogLevel: string | undefined): AppConfig['logLevel'] {
  const value = (rawLogLevel || 'info').trim().toLowerCase()
  if (!VALID_LOG_LEVELS.has(value)) {
    throw new Error('GEMINI_LOG_LEVEL must be one of: debug, info, warn, error')
  }
  return value as AppConfig['logLevel']
}

function parseMaxConcurrentTasks(rawValue: string | undefined): number {
  const value = (rawValue || '6').trim()
  if (!/^\d+$/.test(value)) {
    throw new Error('GEMINI_MAX_CONCURRENT_TASKS must be an integer')
  }
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 32) {
    throw new Error('GEMINI_MAX_CONCURRENT_TASKS must be between 1 and 32')
  }
  return parsed
}

function parseModelAliases(rawValue: string | undefined): Record<string, string> {
  const defaults: Record<string, string> = {
    'gemini-pro': 'auto-gemini-3',
    'gemini-flash': 'gemini-3-flash-preview',
    'gemini-flash-lite': 'gemini-3.1-flash-lite-preview',
    'pro': 'auto-gemini-3',
    'flash': 'gemini-3-flash-preview',
    'flash-lite': 'gemini-3.1-flash-lite-preview',
    'latest-pro': 'auto-gemini-3',
    'latest-flash': 'gemini-3-flash-preview',
    'latest-flash-lite': 'gemini-3.1-flash-lite-preview',
  }
  if (!rawValue || !rawValue.trim()) {
    return defaults
  }

  const aliases = { ...defaults }
  for (const item of rawValue.split(',')) {
    const pair = item.trim()
    if (!pair) continue
    const separatorIndex = pair.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex === pair.length - 1) {
      throw new Error(`GEMINI_MODEL_ALIASES entry must use alias=model format: ${pair}`)
    }
    const alias = pair.slice(0, separatorIndex).trim()
    const model = pair.slice(separatorIndex + 1).trim()
    if (!alias || !model) {
      throw new Error(`GEMINI_MODEL_ALIASES entry must use alias=model format: ${pair}`)
    }
    aliases[alias] = model
  }
  return aliases
}

export function createConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const modelAliases = parseModelAliases(env.GEMINI_MODEL_ALIASES)
  const defaultModelAlias = (env.GEMINI_DEFAULT_MODEL || 'gemini-pro').trim() || 'gemini-pro'
  return {
    port: parsePort(env.GEMINI_WORKER_PORT),
    host: parseHost(env.GEMINI_WORKER_HOST),
    workerName: parseWorkerName(env.GEMINI_WORKER_NAME),
    workerToken: parseToken(env.GEMINI_WORKER_TOKEN),
    allowedCwds: parseAllowedCwds(env.GEMINI_ALLOWED_CWDS),
    maxConcurrentTasks: parseMaxConcurrentTasks(env.GEMINI_MAX_CONCURRENT_TASKS),
    logLevel: parseLogLevel(env.GEMINI_LOG_LEVEL),
    defaultModel: modelAliases[defaultModelAlias] || defaultModelAlias,
    modelAliases,
  }
}

export const config = createConfig()
