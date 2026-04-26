import { spawn, type ChildProcess } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import fs from 'node:fs/promises'
import { config } from '../config.js'
import type { ImageAttachment, TaskEntry, WorkerEvent } from '../models.js'
import { EventBroadcast } from '../persistence/event-store.js'
import { mapGeminiJsonToWorkerEvents } from './event-mapper.js'

export const taskRegistry = new Map<string, TaskEntry>()
export const taskBroadcasts = new Map<string, EventBroadcast>()
const GEMINI_BIN = process.platform === 'win32' ? 'gemini.cmd' : 'gemini'

function createGeminiProcess(args: string[], cwd?: string, env?: NodeJS.ProcessEnv): ChildProcess {
  if (process.platform === 'win32') {
    return spawn('cmd.exe', ['/c', 'gemini', ...args], {
      cwd,
      shell: false,
      env,
    })
  }
  return spawn(GEMINI_BIN, args, {
    cwd,
    shell: false,
    env,
  })
}

export function buildGeminiProcessEnv(
  taskId: string,
  apiKey: string | undefined,
  baseUrl: string | undefined,
  envVars: Record<string, string> | undefined
): NodeJS.ProcessEnv {
  return {
    ...process.env,
    ...(envVars || {}),
    ...(apiKey ? { GEMINI_API_KEY: apiKey } : {}),
    ...(baseUrl ? { GEMINI_BASE_URL: baseUrl } : {}),
    // Headless worker tasks cannot answer Gemini CLI's interactive trusted-folder prompt.
    GEMINI_CLI_TRUST_WORKSPACE: 'true',
    FOGGY_GEMINI_TASK_ID: taskId,
  }
}

function stripBase64Prefix(data: string): string {
  const marker = 'base64,'
  const markerIndex = data.indexOf(marker)
  return markerIndex >= 0 ? data.slice(markerIndex + marker.length) : data
}

function sanitizeAttachmentName(name: string, index: number): string {
  const trimmed = name.trim()
  const replaced = trimmed.replace(/[<>:"/\\|?*\x00-\x1F]/g, '_').replace(/^\.+/, '')
  const fallback = replaced || `attachment-${index + 1}`
  return fallback.length > 255 ? fallback.slice(-255) : fallback
}

async function saveAttachments(
  taskId: string,
  cwd: string | undefined,
  images: ImageAttachment[] | undefined
): Promise<string[]> {
  if (!images || images.length === 0) {
    return []
  }
  const baseDir = cwd
    ? path.join(cwd, '.foggy-attachments', 'gemini', taskId)
    : path.join(os.tmpdir(), 'foggy-gemini-attachments', taskId)
  await fs.mkdir(baseDir, { recursive: true })

  const output: string[] = []
  for (const [index, image] of images.entries()) {
    const filename = sanitizeAttachmentName(image.name, index)
    const outputPath = path.join(baseDir, filename)
    await fs.writeFile(outputPath, Buffer.from(stripBase64Prefix(image.data), 'base64'))
    output.push(outputPath)
  }
  return output
}

function buildPrompt(prompt: string, attachmentPaths: string[]): string {
  if (attachmentPaths.length === 0) {
    return prompt
  }
  return [
    'The user attached files. Review them from disk if needed:',
    ...attachmentPaths.map(filePath => `- ${filePath}`),
    '',
    prompt,
  ].join('\n')
}

function buildArgs(
  prompt: string,
  sessionId: string | undefined,
  model: string | undefined,
  skipTrust: boolean
): string[] {
  const args = ['-p', prompt, '--output-format', 'stream-json']
  if (skipTrust) {
    args.push('--skip-trust')
  }
  if (model) {
    args.push('-m', model)
  }
  if (sessionId) {
    args.push('-r', sessionId)
  }
  args.push('--yolo')
  return args
}

function emitEvent(broadcast: EventBroadcast, event: Omit<WorkerEvent, 'seq'>): void {
  broadcast.emit({ ...event, seq: broadcast.nextSeq() })
}

export function getRunningTaskCount(): number {
  let count = 0
  for (const entry of taskRegistry.values()) {
    if (entry.status === 'running') count++
  }
  return count
}

export function hasGeminiCli(): Promise<boolean> {
  return new Promise(resolve => {
    const child = process.platform === 'win32'
      ? spawn('cmd.exe', ['/c', 'gemini', '--version'], { stdio: 'ignore', shell: false })
      : spawn(GEMINI_BIN, ['--version'], { stdio: 'ignore', shell: false })
    child.on('error', () => resolve(false))
    child.on('exit', code => resolve(code === 0))
  })
}

export async function runQuery(
  taskId: string,
  prompt: string,
  cwd: string | undefined,
  sessionId: string | undefined,
  model: string | undefined,
  _maxTurns: number | undefined,
  images: ImageAttachment[] | undefined,
  apiKey: string | undefined,
  baseUrl: string | undefined,
  envVars: Record<string, string> | undefined,
  skipTrust: boolean = true
): Promise<void> {
  const broadcast = new EventBroadcast(taskId)
  taskBroadcasts.set(taskId, broadcast)

  const abortController = new AbortController()
  const entry: TaskEntry = {
    taskId,
    status: 'running',
    abortController,
    sessionId,
    model: model || config.defaultModel,
    startedAt: Date.now(),
  }
  taskRegistry.set(taskId, entry)

  try {
    const attachmentPaths = await saveAttachments(taskId, cwd, images)
    const effectivePrompt = buildPrompt(prompt, attachmentPaths)
    const args = buildArgs(effectivePrompt, sessionId, model || config.defaultModel, skipTrust)
    const child: ChildProcess = createGeminiProcess(
      args,
      cwd,
      buildGeminiProcessEnv(taskId, apiKey, baseUrl, envVars)
    )

    entry.pid = child.pid

    let stdoutBuffer = ''
    let stderrBuffer = ''
    let lastAssistantText = ''
    let resolvedSessionId = sessionId
    let terminalSent = false
    const toolNamesById = new Map<string, string>()

    child.stdout?.on('data', chunk => {
      stdoutBuffer += chunk.toString()
      const lines = stdoutBuffer.split(/\r?\n/)
      stdoutBuffer = lines.pop() || ''

      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        try {
          const payload = JSON.parse(trimmed) as Record<string, unknown>
          const discoveredSessionId = typeof payload.session_id === 'string' ? payload.session_id : resolvedSessionId
          if (discoveredSessionId) {
            resolvedSessionId = discoveredSessionId
            entry.sessionId = discoveredSessionId
          }

          const events = mapGeminiJsonToWorkerEvents(taskId, resolvedSessionId, payload, () => broadcast.nextSeq())
          for (const event of events) {
            if (event.type === 'tool_use' && event.tool_use_id && event.tool) {
              toolNamesById.set(event.tool_use_id, event.tool)
            }
            if (event.type === 'tool_result' && event.tool_use_id) {
              const resolvedToolName = toolNamesById.get(event.tool_use_id)
              if (resolvedToolName) {
                event.tool = resolvedToolName
              }
            }
            if (event.type === 'assistant_text' && event.content) {
              lastAssistantText += event.content
            }
            if (event.type === 'result' && !event.content && !event.result && lastAssistantText) {
              event.content = lastAssistantText
              event.result = lastAssistantText
            }
            if (event.type === 'result' || event.type === 'error') {
              terminalSent = true
            }
            broadcast.emit(event)
          }
        } catch {
          emitEvent(broadcast, {
            type: 'assistant_text',
            task_id: taskId,
            session_id: resolvedSessionId,
            content: trimmed,
          })
          lastAssistantText += trimmed
        }
      }
    })

    child.stderr?.on('data', chunk => {
      stderrBuffer += chunk.toString()
    })

    abortController.signal.addEventListener('abort', () => {
      try {
        child.kill('SIGTERM')
      } catch {
        // ignore
      }
    })

    await new Promise<void>((resolve, reject) => {
      child.on('error', reject)
      child.on('exit', code => {
        if (abortController.signal.aborted) {
          entry.status = 'aborted'
          entry.completedAt = Date.now()
          if (!terminalSent) {
            emitEvent(broadcast, {
              type: 'error',
              task_id: taskId,
              session_id: resolvedSessionId,
              error: 'Task aborted',
            })
          }
          resolve()
          return
        }

        if (code === 0) {
          entry.status = 'completed'
          entry.completedAt = Date.now()
          if (!terminalSent) {
            emitEvent(broadcast, {
              type: 'result',
              task_id: taskId,
              session_id: resolvedSessionId,
              result: lastAssistantText || undefined,
              content: lastAssistantText || undefined,
              model: entry.model,
              duration_ms: entry.completedAt - entry.startedAt,
            })
          }
          resolve()
          return
        }

        entry.status = 'failed'
        entry.completedAt = Date.now()
        emitEvent(broadcast, {
          type: 'error',
          task_id: taskId,
          session_id: resolvedSessionId,
          error: stderrBuffer.trim() || `Gemini CLI exited with code ${code ?? 'unknown'}`,
        })
        resolve()
      })
    })
  } catch (error: any) {
    entry.status = abortController.signal.aborted ? 'aborted' : 'failed'
    entry.completedAt = Date.now()
    emitEvent(broadcast, {
      type: 'error',
      task_id: taskId,
      session_id: entry.sessionId,
      error: error?.message || String(error),
    })
  } finally {
    await broadcast.flush()
    broadcast.close()
  }
}

export function abortTask(taskId: string): boolean {
  const entry = taskRegistry.get(taskId)
  if (!entry || entry.status !== 'running') return false
  entry.abortController?.abort()
  entry.status = 'aborted'
  entry.completedAt = Date.now()
  return true
}

export function getTaskStatus(taskId: string): TaskEntry | undefined {
  return taskRegistry.get(taskId)
}

const MAX_COMPLETED = 100

export function cleanupOldTasks(): void {
  // Cheap O(1) guard before the O(N log N) walk: this is called on every /api/v1/query and
  // the registry sits well under the threshold for the vast majority of the worker's lifetime.
  if (taskRegistry.size <= MAX_COMPLETED) {
    return
  }
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
