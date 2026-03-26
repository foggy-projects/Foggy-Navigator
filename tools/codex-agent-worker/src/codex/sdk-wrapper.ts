import { existsSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import os from 'node:os'
import path from 'node:path'
import { Codex } from '@openai/codex-sdk'
import type { CodexOptions, Input, ThreadOptions, ModelReasoningEffort, ThreadItem } from '@openai/codex-sdk'
import fs from 'node:fs/promises'
import { config } from '../config.js'
import type { ImageAttachment, TaskEntry, WorkerEvent } from '../models.js'
import { createResultEvent, createErrorEvent } from './event-mapper.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { detectSpawnedCodexPid, snapshotCodexCliPids } from './processes.js'

const moduleRequire = createRequire(import.meta.url)

/**
 * Global task registry — tracks all active and recently completed tasks
 */
export const taskRegistry = new Map<string, TaskEntry>()

/**
 * Event broadcasts per task — subscribers receive real-time events
 */
export const taskBroadcasts = new Map<string, EventBroadcast>()

/**
 * 解析 model:reasoning_level 后缀
 * 如 "gpt-5.4:high" → { model: "gpt-5.4", reasoningLevel: "high" }
 * 如 "gpt-5.4-mini" → { model: "gpt-5.4-mini", reasoningLevel: undefined }
 *
 * SDK ModelReasoningEffort: "minimal" | "low" | "medium" | "high" | "xhigh"
 * 前端传 "extra-high" → 映射为 "xhigh"
 */
export function parseModelString(rawModel: string): { model: string; reasoningLevel?: ModelReasoningEffort } {
  const colonIdx = rawModel.indexOf(':')
  if (colonIdx <= 0) return { model: rawModel }

  const model = rawModel.substring(0, colonIdx)
  let level = rawModel.substring(colonIdx + 1)

  // 映射前端命名到 SDK 枚举
  if (level === 'extra-high') level = 'xhigh'

  const valid: ModelReasoningEffort[] = ['minimal', 'low', 'medium', 'high', 'xhigh']
  if (valid.includes(level as ModelReasoningEffort)) {
    return { model, reasoningLevel: level as ModelReasoningEffort }
  }
  return { model }
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
  const extraPathEntries = (options.additionalPathEntries ?? resolveCodexPathEntries(platform))
    .filter(Boolean)
    .filter(entry => !existingPath.split(path.delimiter).includes(entry))

  if (extraPathEntries.length > 0) {
    env[pathKey] = [ ...extraPathEntries, existingPath ].filter(Boolean).join(path.delimiter)
  }

  if (!env.CODEX_MANAGED_BY_NPM) {
    env.CODEX_MANAGED_BY_NPM = '1'
  }

  if (platform === 'win32') {
    env.SystemRoot = env.SystemRoot || 'C:\\WINDOWS'
    env.ComSpec = env.ComSpec || path.join(env.SystemRoot, 'System32', 'cmd.exe')
    env.TEMP = env.TEMP || tempDir
    env.TMP = env.TMP || tempDir
  }

  return env
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
        input: { changes: item.changes },
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
        type: 'error',
        task_id: taskId,
        session_id: threadId,
        error: item.message,
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
  apiKey: string | undefined
): Promise<void> {
  const broadcast = new EventBroadcast(taskId)
  taskBroadcasts.set(taskId, broadcast)

  const abortController = new AbortController()
  const rawModel = model || 'gpt-5.4-mini'

  const entry: TaskEntry = {
    taskId,
    status: 'running',
    abortController,
    threadId,
    model: rawModel,
    startedAt: Date.now(),
  }
  taskRegistry.set(taskId, entry)

  const startTime = Date.now()
  let numTurns = 0
  let lastAssistantText = ''
  let totalUsage = { inputTokens: 0, outputTokens: 0 }
  let resolvedThreadId = threadId
  let terminalEventSent = false
  let terminalFailureMessage: string | undefined
  let abortReason = 'Task aborted'
  const startedToolUses = new Set<string>()
  const maxTurnLimit = maxTurns !== undefined && Number.isInteger(maxTurns) && maxTurns > 0
    ? maxTurns
    : undefined

  entry.model = rawModel
  const { model: effectiveModel, reasoningLevel } = parseModelString(rawModel)
  let resolvedModel = effectiveModel

  try {
    const existingPids = await snapshotCodexCliPids()

    // 创建 Codex 实例
    // 支持两种认证模式：
    // 1. API Key 模式：传入 apiKey
    // 2. 订阅模式：不传 apiKey，SDK 通过 Codex CLI 自动读取 ~/.codex/auth.json
    const effectiveApiKey = apiKey || config.openaiApiKey || undefined
    const codexOptions: CodexOptions = {
      env: buildCodexProcessEnv({
        ...process.env,
        FOGGY_CODEX_TASK_ID: taskId,
        ...(threadId ? { FOGGY_CODEX_THREAD_ID: threadId } : {}),
      }),
    }
    if (effectiveApiKey) {
      codexOptions.apiKey = effectiveApiKey
    }

    const codex = new Codex(codexOptions)

    // 创建或恢复 Thread
    const threadOptions: ThreadOptions = {
      model: effectiveModel,
      skipGitRepoCheck: true,
      sandboxMode: 'danger-full-access',
    }
    if (cwd) threadOptions.workingDirectory = cwd
    if (reasoningLevel) threadOptions.modelReasoningEffort = reasoningLevel

    const thread = threadId
      ? codex.resumeThread(threadId, threadOptions)
      : codex.startThread(threadOptions)

    const input = await buildCodexInput(taskId, prompt, cwd, images)

    // 流式执行
    const { events } = await thread.runStreamed(input, {
      signal: abortController.signal,
    })
    entry.pid = await detectSpawnedCodexPid(existingPids)

    for await (const event of events) {
      if (abortController.signal.aborted) break

      switch (event.type) {
        case 'thread.started':
          resolvedThreadId = event.thread_id
          entry.threadId = resolvedThreadId
          break

        case 'turn.started':
          if (shouldAbortBeforeTurnStart(numTurns, maxTurnLimit)) {
            abortReason = `Task aborted: max_turns limit reached (${maxTurnLimit})`
            abortController.abort(abortReason)
          }
          break

        case 'item.completed': {
          const workerEvents = mapThreadItemToEvents(
            taskId,
            event.item,
            resolvedThreadId,
            () => broadcast.nextSeq(),
            startedToolUses
          )

          for (const we of workerEvents) {
            if (we.type === 'assistant_text' && we.content && we.subtype !== 'reasoning') {
              lastAssistantText += we.content
            }
            broadcast.emit(we)
          }
          break
        }

        case 'item.started':
        case 'item.updated': {
          if (event.type === 'item.started') {
            if (event.item.type === 'command_execution') {
              startedToolUses.add(event.item.id)
              emitWorkerEvent(
                broadcast,
                createToolUseEvent(taskId, resolvedThreadId, 'command_execution', { command: event.item.command }, event.item.id)
              )
            } else if (event.item.type === 'mcp_tool_call') {
              startedToolUses.add(event.item.id)
              emitWorkerEvent(
                broadcast,
                createToolUseEvent(
                  taskId,
                  resolvedThreadId,
                  `${event.item.server}:${event.item.tool}`,
                  event.item.arguments as Record<string, unknown>,
                  event.item.id
                )
              )
            }
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
          terminalFailureMessage = event.error.message
          broadcast.emit(createErrorEvent(
            taskId, resolvedThreadId, terminalFailureMessage, broadcast.nextSeq()
          ))
          terminalEventSent = true
          break

        case 'error':
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
      taskId, resolvedThreadId, lastAssistantText || undefined,
      totalUsage, resolvedModel, durationMs, numTurns, broadcast.nextSeq()
    )
    broadcast.emit(resultEvent)

    entry.status = 'completed'
    entry.completedAt = Date.now()

  } catch (error: any) {
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
  }
}

/**
 * Abort a running task
 */
export function abortTask(taskId: string): boolean {
  const entry = taskRegistry.get(taskId)
  if (!entry || entry.status !== 'running') return false

  entry.abortController?.abort()
  entry.status = 'aborted'
  entry.completedAt = Date.now()

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