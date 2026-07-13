import { existsSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import os from 'node:os'
import path from 'node:path'
import { Codex } from '@openai/codex-sdk'
import type { CodexOptions, Input, ThreadOptions, ThreadItem } from '@openai/codex-sdk'
import fs from 'node:fs/promises'
import { config } from '../config.js'
import type { CodexApprovalPolicy, CodexSandboxMode, CodexWebSearchMode, ImageAttachment, TaskEntry, WorkerEvent } from '../models.js'
import { createResultEvent, createErrorEvent } from './event-mapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { recordSessionFileHintsForEventBestEffort } from '../persistence/session-file-hints.js'
import { detectSpawnedCodexPid, snapshotCodexCliPids } from './processes.js'
import { releaseCodexThreadReservationsForTask } from './thread-reservations.js'
import {
  buildNavigatorBusinessMcpConfig,
  buildNavigatorBusinessMcpEnv,
} from '../business-mcp/navigator-business-mcp-server.js'
import { normalizeCodexReasoningEffort, type CodexReasoningEffort } from './reasoning.js'
import { pathApiFor } from '../path-guards.js'

const moduleRequire = createRequire(import.meta.url)

export type CodexRunOptions = {
  codexHomeKey?: string
  developerInstructions?: string
  outputSchema?: Record<string, unknown>
  codexConfig?: Record<string, unknown>
  sandboxMode?: CodexSandboxMode
  approvalPolicy?: CodexApprovalPolicy
  networkAccessEnabled?: boolean
  webSearchMode?: CodexWebSearchMode
  businessRuntimeContext?: Record<string, unknown>
  additionalDirectories?: string[]
}

export type CodexFactory = (options: CodexOptions) => Pick<Codex, 'startThread' | 'resumeThread'>

export type RunQueryDependencies = {
  codexFactory?: CodexFactory
  snapshotCodexCliPids?: typeof snapshotCodexCliPids
  detectSpawnedCodexPid?: typeof detectSpawnedCodexPid
  prepareResumeToolsModelCatalog?: typeof prepareResumeToolsModelCatalog
}

/**
 * Global task registry — tracks all active and recently completed tasks
 */
export const taskRegistry = new Map<string, TaskEntry>()

/**
 * Event broadcasts per task — subscribers receive real-time events
 */
export const taskBroadcasts = new Map<string, EventBroadcast>()

export const CODEX_BIZ_HOME_ROOT_REQUIRED_ERROR = 'CODEX_BIZ_HOME_ROOT is required when codex_home_key is provided'
export const UNSUPPORTED_CODEX_MODEL = 'UNSUPPORTED_CODEX_MODEL'
export const CODEX_ULTRA_APP_SERVER_REQUIRED = 'CODEX_ULTRA_APP_SERVER_REQUIRED'

export class UnsupportedCodexModelError extends Error {
  readonly code = UNSUPPORTED_CODEX_MODEL

  constructor() {
    super(UNSUPPORTED_CODEX_MODEL)
    this.name = 'UnsupportedCodexModelError'
  }
}

export class CodexUltraAppServerRequiredError extends Error {
  readonly code = CODEX_ULTRA_APP_SERVER_REQUIRED

  constructor() {
    super(CODEX_ULTRA_APP_SERVER_REQUIRED)
    this.name = 'CodexUltraAppServerRequiredError'
  }
}

function isUltraModelRequest(rawModel: string): boolean {
  const trimmed = rawModel.trim()
  const colonIdx = trimmed.indexOf(':')
  const baseModel = (colonIdx >= 0 ? trimmed.substring(0, colonIdx) : trimmed).trim().toLowerCase()
  if (baseModel === 'codex-ultra') return true
  return colonIdx > 0
    && trimmed.substring(colonIdx + 1).trim().toLowerCase() === 'ultra'
}

function assertSdkModelSupported(rawModel: string): void {
  if (isUltraModelRequest(rawModel)) {
    throw new CodexUltraAppServerRequiredError()
  }
}

export function assertSdkCodexConfigSupported(codexConfig: unknown): void {
  if (!codexConfig || typeof codexConfig !== 'object' || Array.isArray(codexConfig)) return
  const configuredEffort = (codexConfig as Record<string, unknown>).model_reasoning_effort
  if (typeof configuredEffort === 'string' && configuredEffort.trim().toLowerCase() === 'ultra') {
    throw new CodexUltraAppServerRequiredError()
  }
}

/**
 * 解析 model:reasoning_level 后缀
 * 如 "gpt-5.4:high" → { model: "gpt-5.4", reasoningLevel: "high" }
 * 如 "gpt-5.3-codex-spark" → { model: "gpt-5.3-codex-spark", reasoningLevel: undefined }
 *
 * Worker reasoning effort: "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
 * Ultra 由独立 codex-app-server-worker 执行，不会传给 Codex SDK。
 * 前端传 "extra-high" → 映射为 "xhigh"
 */
export function parseModelString(rawModel: string): { model: string; reasoningLevel?: CodexReasoningEffort } {
  assertSdkModelSupported(rawModel)
  const colonIdx = rawModel.indexOf(':')
  if (colonIdx <= 0) return { model: rawModel.trim() }

  const model = rawModel.substring(0, colonIdx).trim()
  const reasoningLevel = normalizeCodexReasoningEffort(rawModel.substring(colonIdx + 1))
  return reasoningLevel ? { model, reasoningLevel } : { model }
}

export function applyResolvedReasoningEffort(
  codexConfig: Record<string, unknown>,
  reasoningLevel: CodexReasoningEffort | undefined
): void {
  if (reasoningLevel) {
    codexConfig.model_reasoning_effort = reasoningLevel
  }
}

export function resolveSdkReasoningEffort(
  modelReasoningLevel: CodexReasoningEffort | undefined,
  codexConfig: Record<string, unknown> | undefined
): CodexReasoningEffort | undefined {
  assertSdkCodexConfigSupported(codexConfig)
  const configuredEffort = typeof codexConfig?.model_reasoning_effort === 'string'
    ? normalizeCodexReasoningEffort(codexConfig.model_reasoning_effort)
    : undefined
  return modelReasoningLevel ?? configuredEffort
}

/**
 * 把请求中的 model 字符串经过 alias 解析为真实模型字符串。
 *
 * 设计目标：让前端和 Java 后端只感知稳定 alias（如 codex-latest），Worker 在执行前把
 * alias 映射到真实模型（如 gpt-5.6-sol）。模型版本升级时只改 Worker 配置即可。
 *
 * 解析规则（按优先级）：
 *   1. 整串命中 alias：直接返回 alias 对应的真实模型（含 reasoning 后缀，原样保留）
 *   2. `<alias>:<reasoning>` 格式且 alias 命中、且 alias value 不含冒号：
 *      把请求的 reasoning 拼到 alias value 上
 *   3. `<alias>:<reasoning>` 格式且 alias value 已含冒号：
 *      使用 alias 自身的 reasoning（避免双重冒号导致 Codex SDK 困惑）
 *   4. 都不命中：原样返回（保持向后兼容，请求方仍可直接传真实模型名）
 *
 * 示例（默认映射 codex-latest=gpt-5.6-sol, codex-fast=gpt-5.6-sol:low）：
 *   resolveModelAlias('codex-latest', m) → { resolved: 'gpt-5.6-sol', wasAlias: true }
 *   resolveModelAlias('codex-fast', m)   → { resolved: 'gpt-5.6-sol:low', wasAlias: true }
 *   resolveModelAlias('codex-latest:high', m) → { resolved: 'gpt-5.6-sol:high', wasAlias: true }
 *   resolveModelAlias('codex-fast:high', m)   → { resolved: 'gpt-5.6-sol:low', wasAlias: true } // alias 自带 reasoning，忽略请求的
 *   resolveModelAlias('gpt-5.6-sol', m)      → { resolved: 'gpt-5.6-sol', wasAlias: false }
 *   resolveModelAlias('gpt-5.6-sol:high', m) → { resolved: 'gpt-5.6-sol:high', wasAlias: false }
 */
export function resolveModelAlias(
  rawModel: string,
  aliases: Record<string, string>
): { resolved: string; wasAlias: boolean } {
  if (!rawModel) {
    return { resolved: rawModel, wasAlias: false }
  }
  // Case 1: 整串命中
  if (Object.prototype.hasOwnProperty.call(aliases, rawModel)) {
    return { resolved: aliases[rawModel]!, wasAlias: true }
  }
  // Case 2/3: alias:reasoning 格式
  const colonIdx = rawModel.indexOf(':')
  if (colonIdx > 0) {
    const aliasPart = rawModel.substring(0, colonIdx).trim()
    const reasoningPart = rawModel.substring(colonIdx + 1).trim()
    if (Object.prototype.hasOwnProperty.call(aliases, aliasPart)) {
      const aliasResolved = aliases[aliasPart]!
      // alias 自身已含 reasoning，请求附带的 reasoning 被忽略
      if (aliasResolved.includes(':')) {
        return { resolved: aliasResolved, wasAlias: true }
      }
      // alias 不含 reasoning，把请求的 reasoning 拼到尾部
      return { resolved: `${aliasResolved}:${reasoningPart}`, wasAlias: true }
    }
  }
  // Case 4: 直接透传（向后兼容真实模型名）
  return { resolved: rawModel, wasAlias: false }
}

export function resolveSupportedModelAlias(
  rawModel: string,
  aliases: Record<string, string>
): { resolved: string; wasAlias: boolean } {
  const result = resolveModelAlias(rawModel, aliases)
  // Check both sides so the SDK route cannot be reached through either the stable
  // codex-ultra name or an arbitrary alias that resolves to an Ultra suffix.
  assertSdkModelSupported(rawModel)
  assertSdkModelSupported(result.resolved)
  const baseModel = result.resolved.split(':', 1)[0]?.trim().toLowerCase()
  if (baseModel === 'gpt-5.4-mini') throw new UnsupportedCodexModelError()
  return result
}

export function shouldAbortBeforeTurnStart(completedTurns: number, maxTurns: number | undefined): boolean {
  return maxTurns !== undefined && completedTurns >= maxTurns
}

function resolveCodexPlatformPackage(
  platform: NodeJS.Platform,
  arch: string
): string | undefined {
  switch (`${platform}:${arch}`) {
    case 'linux:x64':
      return '@openai/codex-linux-x64'
    case 'linux:arm64':
      return '@openai/codex-linux-arm64'
    case 'android:x64':
      return '@openai/codex-linux-x64'
    case 'android:arm64':
      return '@openai/codex-linux-arm64'
    case 'darwin:x64':
      return '@openai/codex-darwin-x64'
    case 'darwin:arm64':
      return '@openai/codex-darwin-arm64'
    case 'win32:x64':
      return '@openai/codex-win32-x64'
    case 'win32:arm64':
      return '@openai/codex-win32-arm64'
    default:
      return undefined
  }
}

export function resolveCodexPathEntries(
  platform: NodeJS.Platform = process.platform,
  arch: string = process.arch
): string[] {
  const platformPackage = resolveCodexPlatformPackage(platform, arch)
  if (!platformPackage) return []

  try {
    const packageJsonPath = moduleRequire.resolve(`${platformPackage}/package.json`)
    const vendorRoot = path.join(path.dirname(packageJsonPath), 'vendor')
    const targetDirs = existsSync(vendorRoot)
      ? readdirSync(vendorRoot, { withFileTypes: true })
          .filter(entry => entry.isDirectory())
          .map(entry => path.join(vendorRoot, entry.name, 'path'))
      : []

    return targetDirs.filter(dir => existsSync(dir))
  } catch {
    return []
  }
}

type CodexEnvOptions = {
  platform?: NodeJS.Platform
  tempDir?: string
  additionalPathEntries?: string[]
}

export function buildCodexProcessEnv(
  baseEnv: NodeJS.ProcessEnv = process.env,
  options: CodexEnvOptions = {}
): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [key, value] of Object.entries(baseEnv)) {
    if (value !== undefined) {
      env[key] = value
    }
  }

  const platform = options.platform ?? process.platform
  const tempDir = options.tempDir ?? os.tmpdir()
  const pathKey = Object.keys(env).find(key => key.toUpperCase() === 'PATH') ?? 'PATH'
  const existingPath = env[pathKey] || ''
  const pathDelimiter = platform === 'win32' ? ';' : path.delimiter
  const normalizePathEntry = platform === 'win32'
    ? (entry: string) => entry.toLowerCase()
    : (entry: string) => entry
  const existingPathEntries = existingPath
    .split(pathDelimiter)
    .filter(Boolean)
    .map(normalizePathEntry)
  const existingPathSet = new Set(existingPathEntries)
  const extraPathEntries = (options.additionalPathEntries ?? resolveCodexPathEntries(platform))
    .filter(Boolean)
    .filter(entry => !existingPathSet.has(normalizePathEntry(entry)))

  if (extraPathEntries.length > 0) {
    env[pathKey] = [ ...extraPathEntries, existingPath ].filter(Boolean).join(pathDelimiter)
  }

  if (!env.CODEX_MANAGED_BY_NPM) {
    env.CODEX_MANAGED_BY_NPM = '1'
  }

  if (platform === 'win32') {
    env.SystemRoot = env.SystemRoot || 'C:\\WINDOWS'
    env.ComSpec = env.ComSpec || path.win32.join(env.SystemRoot, 'System32', 'cmd.exe')
    env.TEMP = env.TEMP || tempDir
    env.TMP = env.TMP || tempDir
  }

  return env
}

function deleteEnvKeyCaseInsensitive(env: NodeJS.ProcessEnv, key: string): void {
  const normalizedKey = key.toUpperCase()
  for (const existingKey of Object.keys(env)) {
    if (existingKey.toUpperCase() === normalizedKey) {
      delete env[existingKey]
    }
  }
}

function setEnvKeyCaseInsensitive(env: NodeJS.ProcessEnv, key: string, value: string | undefined): void {
  deleteEnvKeyCaseInsensitive(env, key)
  if (value !== undefined && value !== '') {
    env[key] = value
  }
}

export function buildCodexTaskEnv(
  baseEnv: NodeJS.ProcessEnv,
  options: {
    effectiveApiKey?: string
    effectiveBaseUrl?: string
    codexHome?: string
    taskId: string
    threadId?: string
  }
): Record<string, string> {
  const envSource: NodeJS.ProcessEnv = { ...baseEnv }
  setEnvKeyCaseInsensitive(envSource, 'OPENAI_API_KEY', options.effectiveApiKey)
  setEnvKeyCaseInsensitive(envSource, 'CODEX_API_KEY', options.effectiveApiKey)
  setEnvKeyCaseInsensitive(envSource, 'OPENAI_BASE_URL', options.effectiveBaseUrl)
  if (options.codexHome) {
    envSource.CODEX_HOME = options.codexHome
  }
  envSource.FOGGY_CODEX_TASK_ID = options.taskId
  if (options.threadId) {
    envSource.FOGGY_CODEX_THREAD_ID = options.threadId
  }
  return buildCodexProcessEnv(envSource)
}

export function resolveCodexHome(codexHomeKey: string | undefined, codexBizHomeRoot = config.codexBizHomeRoot): string | undefined {
  const key = codexHomeKey?.trim()
  if (!key) {
    return undefined
  }
  if (!codexBizHomeRoot) {
    throw new Error(CODEX_BIZ_HOME_ROOT_REQUIRED_ERROR)
  }
  const safePrefix = key
    .replace(/[^A-Za-z0-9._-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 80) || 'codex-home'
  const digest = createHash('sha256').update(key).digest('hex').slice(0, 16)
  return path.join(codexBizHomeRoot, `${safePrefix}-${digest}`)
}

export function resolveDefaultCodexHome(env: NodeJS.ProcessEnv = process.env, homeDir = os.homedir()): string {
  const configured = env.CODEX_HOME?.trim()
  return path.resolve(configured || path.join(homeDir, '.codex'))
}

type CodexCachedModel = {
  slug?: unknown
  use_responses_lite?: unknown
  [key: string]: unknown
}

type CodexCachedModelCatalog = {
  models?: unknown
}

/**
 * Codex 0.144.1 sends custom tools as a leading `additional_tools` history item for
 * Responses Lite models. On a resumed thread, a later persisted compaction item can
 * supersede that declaration, so the model no longer sees `exec` even though the CLI
 * registered it. Reusing the CLI's own cached model metadata and changing only
 * `use_responses_lite` keeps the same model, instructions, sandbox and approvals while
 * moving tools to the standard Responses API's top-level `tools` field.
 */
export async function prepareResumeToolsModelCatalog(options: {
  taskId: string
  model: string
  codexHome?: string
  defaultCodexHome?: string
}): Promise<string | undefined> {
  const candidateHomes = Array.from(new Set([
    options.codexHome,
    options.defaultCodexHome ?? resolveDefaultCodexHome(),
  ].filter((value): value is string => Boolean(value))))

  for (const home of candidateHomes) {
    const cachePath = path.join(home, 'models_cache.json')
    let parsed: CodexCachedModelCatalog
    try {
      parsed = JSON.parse(await fs.readFile(cachePath, 'utf8')) as CodexCachedModelCatalog
    } catch {
      continue
    }
    if (!Array.isArray(parsed.models)) {
      continue
    }

    const models = parsed.models.filter(isCodexCachedModel)
    const modelIndex = findCachedModelIndex(options.model, models)
    if (modelIndex < 0 || models[modelIndex]?.use_responses_lite !== true) {
      continue
    }

    const compatibleModels = models.map((model, index) => (
      index === modelIndex ? { ...model, use_responses_lite: false } : model
    ))
    const contents = `${JSON.stringify({ models: compatibleModels }, null, 2)}\n`
    const digest = createHash('sha256').update(contents).digest('hex').slice(0, 16)
    const safeTaskId = options.taskId.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80) || 'task'
    const catalogDir = path.join(os.tmpdir(), 'foggy-codex-agent-worker', 'resume-model-catalogs')
    const catalogPath = path.join(catalogDir, `${safeTaskId}-${digest}.json`)
    await fs.mkdir(catalogDir, { recursive: true })
    await fs.writeFile(catalogPath, contents, { encoding: 'utf8', mode: 0o600 })
    return catalogPath
  }

  return undefined
}

function isCodexCachedModel(value: unknown): value is CodexCachedModel {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const model = value as Record<string, unknown>
  return typeof model.slug === 'string'
    && typeof model.display_name === 'string'
    && Array.isArray(model.supported_reasoning_levels)
    && typeof model.shell_type === 'string'
    && typeof model.visibility === 'string'
    && typeof model.supported_in_api === 'boolean'
    && typeof model.priority === 'number'
    && typeof model.base_instructions === 'string'
    && typeof model.supports_reasoning_summaries === 'boolean'
    && typeof model.support_verbosity === 'boolean'
    && Boolean(model.truncation_policy)
    && typeof model.truncation_policy === 'object'
    && typeof model.supports_parallel_tool_calls === 'boolean'
    && Array.isArray(model.experimental_supported_tools)
}

function findCachedModelIndex(model: string, candidates: CodexCachedModel[]): number {
  const exactIndex = candidates.findIndex(candidate => candidate.slug === model)
  if (exactIndex >= 0) {
    return exactIndex
  }

  let bestIndex = -1
  let bestLength = -1
  for (const [index, candidate] of candidates.entries()) {
    if (typeof candidate.slug !== 'string') continue
    if (model.startsWith(candidate.slug) && candidate.slug.length > bestLength) {
      bestIndex = index
      bestLength = candidate.slug.length
    }
  }
  return bestIndex
}

export function resolveNavigatorBusinessMcpServerPath(currentModulePath = fileURLToPath(import.meta.url)): string {
  const ext = path.extname(currentModulePath) || '.js'
  const pathApi = pathApiFor(currentModulePath)
  return pathApi.resolve(pathApi.dirname(currentModulePath), '..', 'business-mcp', `navigator-business-mcp-server${ext}`)
}

export function resolveNavigatorBusinessMcpDebugLogPath(taskId: string, cwd: string | undefined): string {
  const root = cwd || config.allowedCwds[0] || process.cwd()
  return path.resolve(root, 'temp', 'codex-worker-3070', `business-mcp-${taskId}.log`)
}

export function mergeCodexConfig(...configs: Array<Record<string, unknown> | undefined>): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const configPart of configs) {
    if (!configPart) continue
    mergeObjectInto(result, configPart)
  }
  return result
}

function mergeObjectInto(target: Record<string, unknown>, source: Record<string, unknown>): void {
  for (const [key, sourceValue] of Object.entries(source)) {
    const targetValue = target[key]
    if (isPlainObject(targetValue) && isPlainObject(sourceValue)) {
      mergeObjectInto(targetValue, sourceValue)
    } else {
      target[key] = sourceValue
    }
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

const NAVIGATOR_BUSINESS_MCP_BLOCK_START = '# BEGIN NAVIGATOR_BUSINESS_MCP_MANAGED'
const NAVIGATOR_BUSINESS_MCP_BLOCK_END = '# END NAVIGATOR_BUSINESS_MCP_MANAGED'

export function renderNavigatorBusinessMcpConfigBlock(mcpConfig: Record<string, unknown>): string {
  const server = readNavigatorBusinessMcpServerConfig(mcpConfig)
  const command = readRequiredString(server, 'command')
  const cwd = readOptionalString(server, 'cwd')
  const args = readStringArray(server.args)
  const envVars = readStringArray(server.env_vars)
  const enabledTools = readStringArray(server.enabled_tools)
  const defaultToolsApprovalMode = readOptionalString(server, 'default_tools_approval_mode')
  const lines = [
    NAVIGATOR_BUSINESS_MCP_BLOCK_START,
    '[mcp_servers.navigator_business]',
    `command = ${toTomlString(command)}`,
  ]
  if (cwd) {
    lines.push(`cwd = ${toTomlString(cwd)}`)
  }
  if (args.length > 0) {
    lines.push(`args = [${args.map(toTomlString).join(', ')}]`)
  }
  if (envVars.length > 0) {
    lines.push(`env_vars = [${envVars.map(toTomlString).join(', ')}]`)
  }
  if (defaultToolsApprovalMode) {
    lines.push(`default_tools_approval_mode = ${toTomlString(defaultToolsApprovalMode)}`)
  }
  if (enabledTools.length > 0) {
    lines.push(`enabled_tools = [${enabledTools.map(toTomlString).join(', ')}]`)
  }
  lines.push(NAVIGATOR_BUSINESS_MCP_BLOCK_END)
  return `${lines.join('\n')}\n`
}

export async function ensureNavigatorBusinessMcpHomeConfig(
  codexHome: string,
  mcpConfig: Record<string, unknown>
): Promise<boolean> {
  const configPath = path.join(codexHome, 'config.toml')
  const block = renderNavigatorBusinessMcpConfigBlock(mcpConfig)
  let existing = ''
  try {
    existing = await fs.readFile(configPath, 'utf8')
  } catch (error) {
    if (!isNodeErrorCode(error, 'ENOENT')) {
      throw error
    }
  }
  const next = replaceManagedConfigBlock(existing, block)
  if (next === existing) {
    return false
  }
  await fs.mkdir(codexHome, { recursive: true })
  await fs.writeFile(configPath, next, 'utf8')
  return true
}

function replaceManagedConfigBlock(existing: string, block: string): string {
  const start = existing.indexOf(NAVIGATOR_BUSINESS_MCP_BLOCK_START)
  const end = existing.indexOf(NAVIGATOR_BUSINESS_MCP_BLOCK_END)
  if (start >= 0 && end > start) {
    const endOfBlock = end + NAVIGATOR_BUSINESS_MCP_BLOCK_END.length
    const trailingNewline = existing.slice(endOfBlock).match(/^\r?\n/)
    const replaceEnd = endOfBlock + (trailingNewline?.[0].length ?? 0)
    return `${existing.slice(0, start)}${block}${existing.slice(replaceEnd)}`
  }
  const prefix = existing.length === 0 || existing.endsWith('\n') ? existing : `${existing}\n`
  const separator = prefix.length === 0 || prefix.endsWith('\n\n') ? '' : '\n'
  return `${prefix}${separator}${block}`
}

function readNavigatorBusinessMcpServerConfig(mcpConfig: Record<string, unknown>): Record<string, unknown> {
  const servers = mcpConfig.mcp_servers
  if (!isPlainObject(servers) || !isPlainObject(servers.navigator_business)) {
    throw new Error('navigator_business MCP config is missing')
  }
  return servers.navigator_business
}

function readRequiredString(source: Record<string, unknown>, key: string): string {
  const value = source[key]
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`navigator_business MCP config missing ${key}`)
  }
  return value
}

function readOptionalString(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key]
  return typeof value === 'string' && value.trim() !== '' ? value : undefined
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is string => typeof item === 'string' && item.length > 0)
}

function toTomlString(value: string): string {
  return JSON.stringify(value)
}

function isNodeErrorCode(error: unknown, code: string): boolean {
  return Boolean(error && typeof error === 'object' && 'code' in error && (error as NodeJS.ErrnoException).code === code)
}

export async function seedCodexHomeAuthIfAvailable(
  targetCodexHome: string,
  sourceCodexHome = resolveDefaultCodexHome()
): Promise<boolean> {
  const sourceHome = path.resolve(sourceCodexHome)
  const targetHome = path.resolve(targetCodexHome)
  if (sourceHome.toLowerCase() === targetHome.toLowerCase()) {
    return false
  }

  const sourceAuth = path.join(sourceHome, 'auth.json')
  const targetAuth = path.join(targetHome, 'auth.json')
  try {
    await fs.access(sourceAuth)
  } catch {
    return false
  }

  await fs.mkdir(targetHome, { recursive: true })
  await fs.copyFile(sourceAuth, targetAuth)
  return true
}

export function getRunningTaskCount(): number {
  let count = 0
  for (const entry of taskRegistry.values()) {
    if (entry.status === 'running') {
      count++
    }
  }
  return count
}

function isImageAttachment(attachment: ImageAttachment): boolean {
  const mimeType = attachment.mime_type?.toLowerCase() || ''
  if (mimeType.startsWith('image/')) {
    return true
  }
  const ext = path.extname(attachment.name).toLowerCase()
  return ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp'].includes(ext)
}

function sanitizeAttachmentName(name: string, index: number): string {
  const trimmed = name.trim()
  const replaced = trimmed.replace(/[<>:"/\\|?*\x00-\x1F]/g, '_').replace(/^\.+/, '')
  const fallback = replaced || `image-${index + 1}`
  return fallback.length > 255 ? fallback.slice(-255) : fallback
}

function extensionFromMimeType(mimeType: string | undefined): string {
  switch ((mimeType || '').toLowerCase()) {
    case 'image/png':
      return '.png'
    case 'image/jpeg':
      return '.jpg'
    case 'image/webp':
      return '.webp'
    case 'image/gif':
      return '.gif'
    case 'image/bmp':
      return '.bmp'
    default:
      return ''
  }
}

function stripBase64Prefix(data: string): string {
  const marker = 'base64,'
  const markerIndex = data.indexOf(marker)
  return markerIndex >= 0 ? data.slice(markerIndex + marker.length) : data
}

export async function saveAttachments(
  taskId: string,
  cwd: string | undefined,
  images: ImageAttachment[] | undefined
): Promise<{ imagePaths: string[]; filePaths: string[] }> {
  if (!images || images.length === 0) {
    return { imagePaths: [], filePaths: [] }
  }

  const baseDir = cwd
    ? path.join(cwd, '.foggy-attachments', 'codex', taskId)
    : path.join(os.tmpdir(), 'foggy-codex-attachments', taskId)
  await fs.mkdir(baseDir, { recursive: true })

  const imagePaths: string[] = []
  const filePaths: string[] = []
  for (const [index, image] of images.entries()) {
    let filename = sanitizeAttachmentName(image.name, index)
    if (!path.extname(filename)) {
      filename += extensionFromMimeType(image.mime_type)
    }
    const buffer = Buffer.from(stripBase64Prefix(image.data), 'base64')
    if (buffer.length === 0) {
      continue
    }
    const outputPath = path.join(baseDir, filename)
    await fs.writeFile(outputPath, buffer)
    if (isImageAttachment(image)) {
      imagePaths.push(outputPath)
    } else {
      filePaths.push(outputPath)
    }
  }

  return { imagePaths, filePaths }
}

function augmentPromptWithAttachments(prompt: string, filePaths: string[]): string {
  if (filePaths.length === 0) {
    return prompt
  }

  const lines = [
    'The user attached files. They have been written to disk and may be relevant.',
    'Review them directly if needed:',
    ...filePaths.map(filePath => `- ${filePath}`),
    '',
    prompt,
  ]
  return lines.join('\n')
}

export async function buildCodexInput(
  taskId: string,
  prompt: string,
  cwd: string | undefined,
  images: ImageAttachment[] | undefined
): Promise<Input> {
  const { imagePaths, filePaths } = await saveAttachments(taskId, cwd, images)
  const effectivePrompt = augmentPromptWithAttachments(prompt, filePaths)
  if (imagePaths.length === 0 && filePaths.length === 0) {
    return prompt
  }
  return [
    { type: 'text', text: effectivePrompt },
    ...imagePaths.map(imagePath => ({ type: 'local_image' as const, path: imagePath })),
  ]
}

function createEventWithSeq(
  broadcast: EventBroadcast,
  event: Omit<WorkerEvent, 'seq'>
): WorkerEvent {
  return {
    ...event,
    seq: broadcast.nextSeq(),
  }
}

function emitWorkerEvent(
  broadcast: EventBroadcast,
  event: Omit<WorkerEvent, 'seq'>
): WorkerEvent {
  const eventWithSeq = createEventWithSeq(broadcast, event)
  broadcast.emit(eventWithSeq)
  return eventWithSeq
}

function createToolUseEvent(
  taskId: string,
  threadId: string | undefined,
  tool: string,
  input: Record<string, unknown> | undefined,
  toolUseId: string
): Omit<WorkerEvent, 'seq'> {
  return {
    type: 'tool_use',
    task_id: taskId,
    session_id: threadId,
    tool,
    input,
    tool_use_id: toolUseId,
  }
}

function logMcpToolItem(
  taskId: string,
  phase: 'started' | 'updated' | 'completed',
  item: Extract<ThreadItem, { type: 'mcp_tool_call' }>
): void {
  const error = item.error?.message ? ` error=${sanitizeLogSegment(item.error.message)}` : ''
  console.log(
    `[codex] mcp_tool_${phase} task=${taskId} id=${item.id} server=${item.server} tool=${item.tool} status=${item.status} has_result=${Boolean(item.result)}${error}`
  )
}

type CollabToolCallItem = {
  type: 'collab_tool_call'
  tool?: unknown
  status?: unknown
  receiver_thread_ids?: unknown
  agents_states?: unknown
  [key: string]: unknown
}

export function asCollabToolCallItem(item: unknown): CollabToolCallItem | undefined {
  if (!item || typeof item !== 'object' || (item as Record<string, unknown>).type !== 'collab_tool_call') {
    return undefined
  }
  return item as CollabToolCallItem
}

export function formatCollabToolDiagnostic(
  taskId: string,
  phase: 'started' | 'updated' | 'completed',
  item: CollabToolCallItem
): string {
  const tool = sanitizeLogToken(item.tool, 'unknown')
  const status = sanitizeLogToken(item.status, 'unknown')
  const receiverCount = Array.isArray(item.receiver_thread_ids) ? item.receiver_thread_ids.length : 0
  const agentCount = item.agents_states && typeof item.agents_states === 'object' && !Array.isArray(item.agents_states)
    ? Object.keys(item.agents_states).length
    : 0
  return `[codex] collab_tool_${phase} task=${sanitizeLogToken(taskId, 'unknown')} tool=${tool} status=${status} receiver_count=${receiverCount} agent_count=${agentCount}`
}

function logCollabToolItem(
  taskId: string,
  phase: 'started' | 'updated' | 'completed',
  item: CollabToolCallItem
): void {
  console.log(formatCollabToolDiagnostic(taskId, phase, item))
}

function sanitizeLogToken(value: unknown, fallback: string): string {
  if (typeof value !== 'string' || !value.trim()) return fallback
  return sanitizeLogSegment(value).replace(/\s+/g, '_')
}

function sanitizeLogSegment(value: string): string {
  return value
    .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/[\r\n]+/g, ' ')
    .slice(0, 300)
}

/**
 * 将 Codex SDK ThreadItem 映射为 WorkerEvent
 */
export function mapThreadItemToEvents(
  taskId: string,
  item: ThreadItem,
  threadId: string | undefined,
  nextSeq: () => number,
  startedToolUses: ReadonlySet<string> = new Set()
): WorkerEvent[] {
  const events: WorkerEvent[] = []

  switch (item.type) {
    case 'agent_message':
      if (item.text) {
        events.push({
          type: 'assistant_text',
          task_id: taskId,
          session_id: threadId,
          content: item.text,
          seq: nextSeq(),
        })
      }
      break

    case 'command_execution':
      if (!startedToolUses.has(item.id)) {
        events.push({
          ...createToolUseEvent(taskId, threadId, 'command_execution', { command: item.command }, item.id),
          seq: nextSeq(),
        })
      }
      // 如果已完成，输出结果
      if (item.status === 'completed' || item.status === 'failed') {
        events.push({
          type: 'tool_result',
          task_id: taskId,
          session_id: threadId,
          tool: 'command_execution',
          output: item.aggregated_output || '',
          tool_use_id: item.id,
          is_error: item.status === 'failed',
          seq: nextSeq(),
        })
      }
      break

    case 'file_change':
      events.push({
        type: 'tool_use',
        task_id: taskId,
        session_id: threadId,
        tool: 'file_change',
        input: { changes: item.changes, status: item.status },
        tool_use_id: item.id,
        seq: nextSeq(),
      })
      break

    case 'mcp_tool_call':
      if (!startedToolUses.has(item.id)) {
        events.push({
          ...createToolUseEvent(
            taskId,
            threadId,
            `${item.server}:${item.tool}`,
            item.arguments as Record<string, unknown>,
            item.id
          ),
          seq: nextSeq(),
        })
      }
      if (item.status === 'completed' || item.status === 'failed') {
        events.push({
          type: 'tool_result',
          task_id: taskId,
          session_id: threadId,
          tool: `${item.server}:${item.tool}`,
          output: item.result ? JSON.stringify(item.result) : (item.error?.message || ''),
          tool_use_id: item.id,
          is_error: item.status === 'failed',
          seq: nextSeq(),
        })
      }
      break

    case 'reasoning':
      // 推理摘要作为 assistant_text 发送
      if (item.text) {
        events.push({
          type: 'assistant_text',
          task_id: taskId,
          session_id: threadId,
          content: item.text,
          subtype: 'reasoning',
          seq: nextSeq(),
        })
      }
      break

    case 'error':
      events.push({
        // ThreadItem errors are SDK diagnostics and may be followed by a
        // successful agent_message/result. Terminal failures are represented
        // separately by top-level turn.failed/error events below.
        type: 'warning',
        task_id: taskId,
        session_id: threadId,
        content: item.message,
        subtype: 'sdk_diagnostic',
        seq: nextSeq(),
      })
      break
  }

  return events
}

/**
 * Run a Codex query and yield WorkerEvent objects via EventBroadcast
 *
 * Uses @openai/codex-sdk:
 * 1. Creates Codex instance → startThread() or resumeThread()
 * 2. Calls runStreamed() to get async event stream
 * 3. Maps each ThreadEvent to WorkerEvent format
 * 4. Broadcasts events to all subscribers
 */
export async function runQuery(
  taskId: string,
  prompt: string,
  cwd: string | undefined,
  threadId: string | undefined,
  model: string | undefined,
  maxTurns: number | undefined,
  images: ImageAttachment[] | undefined,
  apiKey: string | undefined,
  baseUrl: string | undefined,
  envVars: Record<string, string> | undefined,
  runOptions: CodexRunOptions = {},
  dependencies: RunQueryDependencies = {}
): Promise<void> {
  // Resolve the complete model/reasoning request before allocating task state.
  const requestedModel = model || config.defaultModel
  const aliasResult = resolveSupportedModelAlias(requestedModel, config.modelAliases)
  const rawModel = aliasResult.resolved
  const { model: effectiveModel, reasoningLevel } = parseModelString(rawModel)
  // Validate request-level config while preserving the SDK Worker's existing
  // behavior when neither the model nor the request specifies an effort.
  const effectiveReasoningLevel = resolveSdkReasoningEffort(reasoningLevel, runOptions.codexConfig)
  const broadcast = new EventBroadcast(taskId)
  taskBroadcasts.set(taskId, broadcast)
  const recordFileHints = (event: WorkerEvent): void => {
    recordSessionFileHintsForEventBestEffort(event, { cwd })
  }

  const abortController = new AbortController()
  // 1.0.4 起：alias-first
  // - 默认值（config.defaultModel）默认是 alias `codex-latest`
  // - 不论请求方传 alias（codex-latest）还是真实模型（gpt-5.6-sol），都先经过 resolveModelAlias 转换
  // - 真实模型直接透传（保持向后兼容）
  // entry.model 保留请求方提供的原始字符串（alias 或真实模型），便于上游列表 / 监控展示稳定值
  const entry: TaskEntry = {
    taskId,
    status: 'running',
    abortController,
    threadId,
    model: requestedModel,
    startedAt: Date.now(),
  }
  taskRegistry.set(taskId, entry)

  emitWorkerEvent(broadcast, {
    type: 'assistant_text',
    task_id: taskId,
    session_id: threadId,
    subtype: 'sync_checkpoint',
    content: '',
  })

  const startTime = Date.now()
  let numTurns = 0
  let pendingAssistantText: string | undefined
  let totalUsage = { inputTokens: 0, outputTokens: 0 }
  let resolvedThreadId = threadId
  let terminalEventSent = false
  let terminalFailureMessage: string | undefined
  let generatedResumeModelCatalogPath: string | undefined
  let abortReason = 'Task aborted'
  const startedToolUses = new Set<string>()
  const maxTurnLimit = maxTurns !== undefined && Number.isInteger(maxTurns) && maxTurns > 0
    ? maxTurns
    : undefined

  const flushPendingAssistantAsCommentary = (): void => {
    if (!pendingAssistantText) return
    const commentaryEvent = emitWorkerEvent(broadcast, {
      type: 'assistant_text',
      task_id: taskId,
      session_id: resolvedThreadId,
      subtype: 'commentary',
      content: pendingAssistantText,
    })
    recordFileHints(commentaryEvent)
    pendingAssistantText = undefined
  }

  // entry.model 仍保留请求方原始字符串；rawModel 是 alias 解析后的真实模型（含 reasoning 后缀）
  let resolvedModel = effectiveModel

  try {
    const existingPids = await (dependencies.snapshotCodexCliPids ?? snapshotCodexCliPids)()

    // 创建 Codex 实例
    // 支持两种认证模式：
    // 1. API Key 模式：传入 apiKey
    // 2. 订阅模式：不传 apiKey，SDK 通过 Codex CLI 自动读取 ~/.codex/auth.json
    const effectiveApiKey = apiKey || config.openaiApiKey || undefined
    const effectiveBaseUrl = baseUrl || config.openaiBaseUrl || undefined
    const codexHome = resolveCodexHome(runOptions.codexHomeKey)
    if (codexHome) {
      await fs.mkdir(codexHome, { recursive: true })
      if (!effectiveApiKey) {
        await seedCodexHomeAuthIfAvailable(codexHome)
      }
    }
    const codexOptions: CodexOptions = {
      env: buildCodexTaskEnv(process.env, {
        effectiveApiKey,
        effectiveBaseUrl,
        codexHome,
        taskId,
        threadId,
      }),
    }
    if (effectiveApiKey) {
      codexOptions.apiKey = effectiveApiKey
    }
    if (effectiveBaseUrl) {
      codexOptions.baseUrl = effectiveBaseUrl
    }
    console.log(
      `[codex] start task=${taskId} requested_model=${requestedModel} alias_hit=${aliasResult.wasAlias} resolved_model=${rawModel} effective_model=${effectiveModel} reasoning=${effectiveReasoningLevel ?? ''} has_request_api_key=${Boolean(apiKey)} has_effective_api_key=${Boolean(effectiveApiKey)} has_base_url=${Boolean(effectiveBaseUrl)} env_var_keys=${envVars ? Object.keys(envVars).join(',') : ''} thread_id=${threadId ?? ''} scoped_codex_home=${Boolean(codexHome)} sandbox_mode=${runOptions.sandboxMode ?? ''} approval_policy=${runOptions.approvalPolicy ?? ''}`
    )

    // Codex CLI 配置项默认值 + envVars 覆盖
    const codexConfigDefaults: Record<string, number> = {
      tool_output_token_limit: 10000,
      model_auto_compact_token_limit: 140000,
    }
    const codexConfig: Record<string, unknown> = { ...codexConfigDefaults }
    if (envVars) {
      const codexConfigKeys = ['model_context_window', 'model_auto_compact_token_limit', 'tool_output_token_limit']
      for (const key of codexConfigKeys) {
        const val = envVars[key]
        if (val != null && val !== '') {
          const num = Number(val)
          codexConfig[key] = Number.isNaN(num) ? val : num
        }
      }
    }
    if (runOptions.codexConfig) {
      Object.assign(codexConfig, runOptions.codexConfig)
    }
    // The model suffix is the most specific request-level choice and must win over generic config.
    // Use the SDK's public config channel because SDK 0.144.1 types do not yet list max.
    applyResolvedReasoningEffort(codexConfig, effectiveReasoningLevel)
    if (runOptions.developerInstructions) {
      codexConfig.developer_instructions = runOptions.developerInstructions
    }
    const hasCallerModelCatalog = Object.prototype.hasOwnProperty.call(codexConfig, 'model_catalog_json')
    if (threadId && !hasCallerModelCatalog) {
      generatedResumeModelCatalogPath = await (
        dependencies.prepareResumeToolsModelCatalog ?? prepareResumeToolsModelCatalog
      )({
        taskId,
        model: effectiveModel,
        codexHome,
      })
      if (generatedResumeModelCatalogPath) {
        codexConfig.model_catalog_json = generatedResumeModelCatalogPath
        console.log(
          `[codex] resume_tools_compat task=${taskId} model=${effectiveModel} mode=standard_responses`
        )
      }
    }
    const navigatorBusinessMcpConfig = buildNavigatorBusinessMcpConfig(
      runOptions.businessRuntimeContext,
      config.navigatorWorkerGatewayBaseUrl,
      resolveNavigatorBusinessMcpServerPath()
    )
    const navigatorBusinessMcpDebugLogPath = navigatorBusinessMcpConfig
      ? resolveNavigatorBusinessMcpDebugLogPath(taskId, cwd)
      : undefined
    const navigatorBusinessMcpEnv = buildNavigatorBusinessMcpEnv(
      runOptions.businessRuntimeContext,
      config.navigatorWorkerGatewayBaseUrl,
      navigatorBusinessMcpDebugLogPath,
      taskId
    )
    if (navigatorBusinessMcpEnv) {
      Object.assign(codexOptions.env as Record<string, string>, navigatorBusinessMcpEnv)
    }
    let sdkNavigatorBusinessMcpConfig = navigatorBusinessMcpConfig
    if (navigatorBusinessMcpConfig && codexHome) {
      await ensureNavigatorBusinessMcpHomeConfig(codexHome, navigatorBusinessMcpConfig)
      sdkNavigatorBusinessMcpConfig = undefined
      console.log(`[codex] navigator_business_mcp task=${taskId} mode=codex_home_config`)
    } else if (navigatorBusinessMcpConfig) {
      console.log(`[codex] navigator_business_mcp task=${taskId} mode=sdk_config`)
    }
    codexOptions.config = mergeCodexConfig(
      codexOptions.config as Record<string, unknown> | undefined,
      codexConfig,
      sdkNavigatorBusinessMcpConfig
    ) as CodexOptions['config']

    const codex = dependencies.codexFactory
      ? dependencies.codexFactory(codexOptions)
      : new Codex(codexOptions)

    // 创建或恢复 Thread
    const threadOptions: ThreadOptions = {
      model: effectiveModel,
      skipGitRepoCheck: true,
      sandboxMode: runOptions.sandboxMode ?? 'danger-full-access',
    }
    if (cwd) threadOptions.workingDirectory = cwd
    const mutableThreadOptions = threadOptions as ThreadOptions & Record<string, unknown>
    if (runOptions.approvalPolicy) mutableThreadOptions.approvalPolicy = runOptions.approvalPolicy
    if (runOptions.networkAccessEnabled !== undefined) {
      mutableThreadOptions.networkAccessEnabled = runOptions.networkAccessEnabled
    }
    if (runOptions.webSearchMode) mutableThreadOptions.webSearchMode = runOptions.webSearchMode
    if (runOptions.additionalDirectories?.length) {
      mutableThreadOptions.additionalDirectories = runOptions.additionalDirectories
    }

    const thread = threadId
      ? codex.resumeThread(threadId, threadOptions)
      : codex.startThread(threadOptions)

    const input = await buildCodexInput(taskId, prompt, cwd, images)

    // 流式执行
    const turnOptions: { signal: AbortSignal; outputSchema?: Record<string, unknown> } = {
      signal: abortController.signal,
    }
    if (runOptions.outputSchema) {
      turnOptions.outputSchema = runOptions.outputSchema
    }
    const { events } = await thread.runStreamed(input, turnOptions)
    entry.pid = await (dependencies.detectSpawnedCodexPid ?? detectSpawnedCodexPid)(existingPids)

    for await (const event of events) {
      if (abortController.signal.aborted) break

      switch (event.type) {
        case 'thread.started':
          resolvedThreadId = event.thread_id
          entry.threadId = resolvedThreadId
          break

        case 'turn.started':
          if (numTurns > 0) flushPendingAssistantAsCommentary()
          if (shouldAbortBeforeTurnStart(numTurns, maxTurnLimit)) {
            abortReason = `Task aborted: max_turns limit reached (${maxTurnLimit})`
            abortController.abort(abortReason)
          }
          break

        case 'item.completed': {
          if (event.item.type === 'mcp_tool_call') {
            logMcpToolItem(taskId, 'completed', event.item)
          }
          const collabItem = asCollabToolCallItem(event.item)
          if (collabItem) {
            logCollabToolItem(taskId, 'completed', collabItem)
          }
          if (event.item.type === 'agent_message') {
            if (event.item.text) {
              flushPendingAssistantAsCommentary()
              pendingAssistantText = event.item.text
            }
            break
          }

          flushPendingAssistantAsCommentary()
          const workerEvents = mapThreadItemToEvents(
            taskId,
            event.item,
            resolvedThreadId,
            () => broadcast.nextSeq(),
            startedToolUses
          )

          for (const we of workerEvents) {
            recordFileHints(we)
            broadcast.emit(we)
          }
          break
        }

        case 'item.started':
        case 'item.updated': {
          const collabItem = asCollabToolCallItem(event.item)
          if (collabItem) {
            logCollabToolItem(taskId, event.type === 'item.started' ? 'started' : 'updated', collabItem)
          }
          if (event.type === 'item.started') {
            flushPendingAssistantAsCommentary()
            if (event.item.type === 'command_execution') {
              startedToolUses.add(event.item.id)
              const workerEvent = emitWorkerEvent(
                broadcast,
                createToolUseEvent(taskId, resolvedThreadId, 'command_execution', { command: event.item.command }, event.item.id)
              )
              recordFileHints(workerEvent)
            } else if (event.item.type === 'mcp_tool_call') {
              logMcpToolItem(taskId, 'started', event.item)
              startedToolUses.add(event.item.id)
              const workerEvent = emitWorkerEvent(
                broadcast,
                createToolUseEvent(
                  taskId,
                  resolvedThreadId,
                  `${event.item.server}:${event.item.tool}`,
                  event.item.arguments as Record<string, unknown>,
                  event.item.id
                )
              )
              recordFileHints(workerEvent)
            }
          } else if (event.item.type === 'mcp_tool_call') {
            logMcpToolItem(taskId, 'updated', event.item)
          }
          break
        }

        case 'turn.completed':
          numTurns++
          if (event.usage) {
            totalUsage.inputTokens += event.usage.input_tokens || 0
            totalUsage.outputTokens += event.usage.output_tokens || 0
          }
          break

        case 'turn.failed':
          flushPendingAssistantAsCommentary()
          terminalFailureMessage = event.error.message
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, terminalFailureMessage, broadcast.nextSeq()
          ))
          terminalEventSent = true
          break

        case 'error':
          flushPendingAssistantAsCommentary()
          terminalFailureMessage = event.message
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, terminalFailureMessage, broadcast.nextSeq()
          ))
          terminalEventSent = true
          break
      }

      if (terminalFailureMessage || abortController.signal.aborted) {
        break
      }
    }

    if (terminalFailureMessage) {
      entry.status = 'failed'
      entry.completedAt = Date.now()
      return
    }

    if (abortController.signal.aborted) {
      flushPendingAssistantAsCommentary()
      entry.status = 'aborted'
      entry.completedAt = Date.now()
      if (!terminalEventSent) {
        broadcast.emit(createErrorEvent(
          taskId,
          resolvedThreadId,
          abortReason,
          broadcast.nextSeq()
        ))
      }
      return
    }

    // 发送结果事件
    const durationMs = Date.now() - startTime
    const resultEvent = createResultEvent(
      taskId, resolvedThreadId, pendingAssistantText,
      totalUsage, resolvedModel, durationMs, numTurns, broadcast.nextSeq()
    )
    broadcast.emit(resultEvent)

    entry.status = 'completed'
    entry.completedAt = Date.now()

  } catch (error: any) {
    flushPendingAssistantAsCommentary()
    if (abortController.signal.aborted) {
      entry.status = 'aborted'
      if (!terminalEventSent) {
        const reason = typeof abortController.signal.reason === 'string'
          ? abortController.signal.reason
          : abortReason
        const abortEvent = createErrorEvent(taskId, resolvedThreadId, reason, broadcast.nextSeq())
        broadcast.emit(abortEvent)
      }
    } else {
      entry.status = 'failed'
      const errorMsg = error?.message || String(error)
      if (!terminalEventSent) {
        const errorEvent = createErrorEvent(taskId, resolvedThreadId, errorMsg, broadcast.nextSeq())
        broadcast.emit(errorEvent)
      }
    }
    entry.completedAt = Date.now()
  } finally {
    broadcast.close()
    if (generatedResumeModelCatalogPath) {
      await fs.unlink(generatedResumeModelCatalogPath).catch(() => undefined)
    }
  }
}

/**
 * Abort a running task
 */
export function abortTask(taskId: string, reason = 'Task aborted'): boolean {
  const entry = taskRegistry.get(taskId)
  if (!entry || entry.status !== 'running') return false

  entry.abortController?.abort(reason)
  entry.status = 'aborted'
  entry.completedAt = Date.now()
  releaseCodexThreadReservationsForTask(taskId)

  return true
}

/**
 * Get task status
 */
export function getTaskStatus(taskId: string): TaskEntry | undefined {
  return taskRegistry.get(taskId)
}

/**
 * Clean up old completed tasks (keep last 100)
 */
export function cleanupOldTasks(): void {
  const MAX_COMPLETED = 100
  const completed = Array.from(taskRegistry.entries())
    .filter(([, e]) => e.status !== 'running')
    .sort((a, b) => (b[1].completedAt || 0) - (a[1].completedAt || 0))

  if (completed.length > MAX_COMPLETED) {
    for (const [id] of completed.slice(MAX_COMPLETED)) {
      const broadcast = taskBroadcasts.get(id)
      if (broadcast) {
        broadcast.cleanup()
      } else {
        new EventBroadcast(id).cleanup()
      }
      taskRegistry.delete(id)
      taskBroadcasts.delete(id)
    }
  }
}
