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
  openaiBaseUrl: string
  workerToken: string
  allowedCwds: string[]
  maxConcurrentTasks: number
  logLevel: 'debug' | 'info' | 'warn' | 'error'
  /**
   * Worker 兜底默认模型（请求未显式指定 model 时使用）。
   *
   * 1.0.4 起：默认值为 alias `codex-latest`（与 Claude/Gemini 的 alias-first 风格一致）。
   * 升级模型版本时，仅修改 modelAliases 配置即可；前端、后端 Java 侧不需要任何改动。
   *
   * 可通过环境变量 CODEX_DEFAULT_MODEL 覆盖。可填 alias（如 `codex-latest`）或真实模型名（如 `gpt-5.5:high`）。
   */
  defaultModel: string
  /**
   * Codex 稳定 alias 到真实模型的映射。
   *
   * Worker 在执行任务前会先把请求中的 model 字符串经过 alias 解析：
   *   - 如果整串命中 alias，直接替换为真实模型（含 reasoning 后缀）
   *   - 如果是 `alias:reasoning` 格式，且对应 alias 不含冒号，则把 reasoning 拼到结果上
   *   - 否则直接透传（按真实模型名处理，向后兼容）
   *
   * 默认映射（可通过 CODEX_MODEL_ALIASES 环境变量覆盖）：
   *   codex-latest → gpt-5.5
   *   codex-fast   → gpt-5.5:low
   *   codex-deep   → gpt-5.5:high
   *   codex-mini   → gpt-5.4-mini   (待 5.5-mini 发布后再升级，仅改 Worker 配置)
   *
   * 环境变量格式：JSON 对象，如 {"codex-latest":"gpt-5.5","codex-fast":"gpt-5.5:low"}
   */
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

function parseMaxConcurrentTasks(rawValue: string | undefined): number {
  const value = (rawValue || '6').trim()
  if (!/^\d+$/.test(value)) {
    throw new Error('CODEX_MAX_CONCURRENT_TASKS must be an integer')
  }

  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 32) {
    throw new Error('CODEX_MAX_CONCURRENT_TASKS must be between 1 and 32')
  }
  return parsed
}

/**
 * 解析 CODEX_DEFAULT_MODEL，保持 runtime 行为稳定：
 * - 空值：回落到 alias `codex-latest`（1.0.4 新默认；旧版本曾用 'gpt-5.4-mini' 真实模型）
 * - 非空值：做基本合法性校验，不做白名单限制（保持与请求层一致的"自由模型串"语义）
 *
 * 默认 alias 在 Worker 内部经 resolveModelAlias 解析为真实模型；不会原封不动透传给 Codex SDK。
 */
function parseDefaultModel(rawValue: string | undefined): string {
  const fallback = 'codex-latest'
  const value = (rawValue || '').trim()
  if (!value) return fallback
  if (/\s/.test(value)) {
    throw new Error('CODEX_DEFAULT_MODEL must not contain whitespace')
  }
  if (value.length > 128) {
    throw new Error('CODEX_DEFAULT_MODEL is too long')
  }
  return value
}

const DEFAULT_CODEX_MODEL_ALIASES: Readonly<Record<string, string>> = Object.freeze({
  'codex-latest': 'gpt-5.5',
  'codex-fast': 'gpt-5.5:low',
  'codex-deep': 'gpt-5.5:high',
  'codex-mini': 'gpt-5.4-mini',
})

/**
 * 解析 CODEX_MODEL_ALIASES。
 *
 * - 未配置：返回 DEFAULT_CODEX_MODEL_ALIASES 浅拷贝
 * - JSON 格式 `{"codex-latest":"gpt-5.5","codex-fast":"gpt-5.5:low"}`：覆盖/扩展默认映射
 * - 其它形式抛错（避免静默吞掉配置错误）
 *
 * 校验项：
 * - alias key 不允许包含冒号（避免和 reasoning 后缀语法冲突）
 * - alias value 不允许为空、不允许含空白
 * - 长度限制：单条不超过 128 字符
 */
function parseModelAliases(rawValue: string | undefined): Record<string, string> {
  const merged: Record<string, string> = { ...DEFAULT_CODEX_MODEL_ALIASES }
  const value = (rawValue || '').trim()
  if (!value) return merged

  let parsed: unknown
  try {
    parsed = JSON.parse(value)
  } catch (err) {
    throw new Error(`CODEX_MODEL_ALIASES must be valid JSON object: ${(err as Error).message}`)
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('CODEX_MODEL_ALIASES must be a JSON object (key=alias, value=real model)')
  }

  for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
    const aliasKey = k.trim()
    if (!aliasKey) {
      throw new Error('CODEX_MODEL_ALIASES alias key must not be empty')
    }
    if (aliasKey.includes(':')) {
      throw new Error(`CODEX_MODEL_ALIASES alias key must not contain ":": ${aliasKey}`)
    }
    if (aliasKey.length > 64) {
      throw new Error(`CODEX_MODEL_ALIASES alias key is too long: ${aliasKey}`)
    }
    if (typeof v !== 'string') {
      throw new Error(`CODEX_MODEL_ALIASES alias value must be string: ${aliasKey}`)
    }
    const aliasVal = v.trim()
    if (!aliasVal) {
      throw new Error(`CODEX_MODEL_ALIASES alias value must not be empty: ${aliasKey}`)
    }
    if (/\s/.test(aliasVal)) {
      throw new Error(`CODEX_MODEL_ALIASES alias value must not contain whitespace: ${aliasKey}`)
    }
    if (aliasVal.length > 128) {
      throw new Error(`CODEX_MODEL_ALIASES alias value is too long: ${aliasKey}`)
    }
    merged[aliasKey] = aliasVal
  }
  return merged
}

export function createConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const openaiApiKey = parseApiKey(getOptionalEnvFromEnv(env, 'OPENAI_API_KEY'))

  return {
    port: parsePort(env.CODEX_WORKER_PORT),
    host: parseHost(env.CODEX_WORKER_HOST),
    workerName: parseWorkerName(env.CODEX_WORKER_NAME),
    openaiApiKey,
    openaiBaseUrl: getOptionalEnvFromEnv(env, 'OPENAI_BASE_URL') || '',
    workerToken: parseToken(env.CODEX_WORKER_TOKEN),
    allowedCwds: parseAllowedCwds(env.CODEX_ALLOWED_CWDS),
    maxConcurrentTasks: parseMaxConcurrentTasks(env.CODEX_MAX_CONCURRENT_TASKS),
    logLevel: parseLogLevel(env.CODEX_LOG_LEVEL),
    defaultModel: parseDefaultModel(env.CODEX_DEFAULT_MODEL),
    modelAliases: parseModelAliases(env.CODEX_MODEL_ALIASES),
  }
}

function getOptionalEnvFromEnv(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim() || ''
  if (!value) return ''

  const placeholders = new Set(['sk-xxx', 'your-api-key-here', 'replace-me'])
  return placeholders.has(value.toLowerCase()) ? '' : value
}

export const config = createConfig()
