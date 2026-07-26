import { createHash, randomUUID } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs/promises'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_ROOT = path.resolve(__dirname, '..', '..', 'logs')
const TASK_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/

export const COMPLETION_RECEIPT_SCHEMA = 'CODEX_COMPLETION_RECEIPT_V2'

export type CompletionReceiptV2 = {
  schema: typeof COMPLETION_RECEIPT_SCHEMA
  task_id: string
  worker_id: string
  provider_task_id: string
  dispatch_count: 1
  provider_thread_id: string | null
  terminal_status: 'COMPLETED'
  terminal_source: 'PROVIDER_TERMINAL_EVENT'
  recorded_at: string
  final_output_present: true
  final_output_durable: true
  final_output_digest: string
  final_output_size_bytes: number
  structured_output_present: boolean
  structured_output_digest: string | null
  completion_signal_present: true
  completion_signal_source: 'PROVIDER_TERMINAL_EVENT'
  completion_signal_recorded_at: string
  result_recoverable: true
}

export type CompletionReceiptObservation = Omit<CompletionReceiptV2, 'result_recoverable'> & {
  result_recoverable: boolean
}

export type CompletionReceiptWrite = {
  taskId: string
  workerId: string
  providerThreadId?: string
  resultText: string
  structuredOutputPresent: boolean
}

function requireSafeIdentifier(value: string, field: string): string {
  const normalized = value.trim()
  if (!TASK_ID_PATTERN.test(normalized)) {
    throw new Error(`${field}_INVALID`)
  }
  return normalized
}

function isIsoInstant(value: unknown): value is string {
  return typeof value === 'string'
    && Number.isFinite(Date.parse(value))
    && new Date(Date.parse(value)).toISOString() === value
}

function isReceipt(value: unknown): value is CompletionReceiptV2 {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const receipt = value as Record<string, unknown>
  return receipt.schema === COMPLETION_RECEIPT_SCHEMA
    && typeof receipt.task_id === 'string'
    && TASK_ID_PATTERN.test(receipt.task_id)
    && typeof receipt.worker_id === 'string'
    && TASK_ID_PATTERN.test(receipt.worker_id)
    && receipt.provider_task_id === receipt.task_id
    && receipt.dispatch_count === 1
    && (receipt.provider_thread_id === null
      || (typeof receipt.provider_thread_id === 'string'
        && TASK_ID_PATTERN.test(receipt.provider_thread_id)))
    && receipt.terminal_status === 'COMPLETED'
    && receipt.terminal_source === 'PROVIDER_TERMINAL_EVENT'
    && isIsoInstant(receipt.recorded_at)
    && receipt.final_output_present === true
    && receipt.final_output_durable === true
    && typeof receipt.final_output_digest === 'string'
    && DIGEST_PATTERN.test(receipt.final_output_digest)
    && Number.isSafeInteger(receipt.final_output_size_bytes)
    && Number(receipt.final_output_size_bytes) > 0
    && typeof receipt.structured_output_present === 'boolean'
    && (receipt.structured_output_present
      ? receipt.structured_output_digest === receipt.final_output_digest
      : receipt.structured_output_digest === null)
    && receipt.completion_signal_present === true
    && receipt.completion_signal_source === 'PROVIDER_TERMINAL_EVENT'
    && isIsoInstant(receipt.completion_signal_recorded_at)
    && receipt.completion_signal_recorded_at === receipt.recorded_at
    && receipt.result_recoverable === true
}

async function syncDirectory(directory: string): Promise<void> {
  let handle
  try {
    handle = await fs.open(directory, 'r')
    await handle.sync()
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code !== 'EINVAL' && code !== 'ENOTSUP' && code !== 'EISDIR') throw error
  } finally {
    await handle?.close().catch(() => undefined)
  }
}

async function atomicWrite(filePath: string, content: string | Uint8Array): Promise<void> {
  const directory = path.dirname(filePath)
  await fs.mkdir(directory, { recursive: true, mode: 0o700 })
  const temporary = path.join(directory, `.${path.basename(filePath)}.${randomUUID()}.tmp`)
  const handle = await fs.open(temporary, 'wx', 0o600)
  try {
    await handle.writeFile(content)
    await handle.sync()
  } finally {
    await handle.close()
  }
  try {
    await fs.rename(temporary, filePath)
    await fs.chmod(filePath, 0o600).catch(() => undefined)
    await syncDirectory(directory)
  } catch (error) {
    await fs.unlink(temporary).catch(() => undefined)
    throw error
  }
}

/**
 * Stores the recoverable result separately from a content-free receipt.
 *
 * Completion-readiness reads only the receipt and result file metadata. It
 * never opens the result object, event log, workspace, prompt, or response.
 */
export class CompletionReceiptStore {
  private readonly receiptDir: string
  private readonly resultDir: string

  constructor(rootDir = DEFAULT_ROOT) {
    this.receiptDir = path.join(rootDir, 'completion-receipts')
    this.resultDir = path.join(rootDir, 'completion-results')
  }

  private receiptPath(taskId: string): string {
    return path.join(this.receiptDir, `${requireSafeIdentifier(taskId, 'TASK_ID')}.json`)
  }

  private resultPath(taskId: string): string {
    return path.join(this.resultDir, `${requireSafeIdentifier(taskId, 'TASK_ID')}.result`)
  }

  async persist(input: CompletionReceiptWrite): Promise<CompletionReceiptV2> {
    const taskId = requireSafeIdentifier(input.taskId, 'TASK_ID')
    const workerId = requireSafeIdentifier(input.workerId, 'WORKER_ID')
    const providerThreadId = input.providerThreadId
      ? requireSafeIdentifier(input.providerThreadId, 'PROVIDER_THREAD_ID')
      : null
    const result = Buffer.from(input.resultText, 'utf8')
    if (result.byteLength === 0) {
      throw new Error('FINAL_OUTPUT_EMPTY')
    }
    const digest = `sha256:${createHash('sha256').update(result).digest('hex')}`
    const recordedAt = new Date().toISOString()

    await atomicWrite(this.resultPath(taskId), result)

    const receipt: CompletionReceiptV2 = {
      schema: COMPLETION_RECEIPT_SCHEMA,
      task_id: taskId,
      worker_id: workerId,
      provider_task_id: taskId,
      dispatch_count: 1,
      provider_thread_id: providerThreadId,
      terminal_status: 'COMPLETED',
      terminal_source: 'PROVIDER_TERMINAL_EVENT',
      recorded_at: recordedAt,
      final_output_present: true,
      final_output_durable: true,
      final_output_digest: digest,
      final_output_size_bytes: result.byteLength,
      structured_output_present: input.structuredOutputPresent,
      structured_output_digest: input.structuredOutputPresent ? digest : null,
      completion_signal_present: true,
      completion_signal_source: 'PROVIDER_TERMINAL_EVENT',
      completion_signal_recorded_at: recordedAt,
      result_recoverable: true,
    }
    await atomicWrite(this.receiptPath(taskId), `${JSON.stringify(receipt)}\n`)
    return receipt
  }

  async inspect(taskId: string): Promise<CompletionReceiptObservation | null> {
    const safeTaskId = requireSafeIdentifier(taskId, 'TASK_ID')
    let raw
    try {
      raw = await fs.readFile(this.receiptPath(safeTaskId), 'utf8')
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return null
      throw error
    }

    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch {
      throw new Error('COMPLETION_RECEIPT_INVALID')
    }
    if (!isReceipt(parsed) || parsed.task_id !== safeTaskId) {
      throw new Error('COMPLETION_RECEIPT_INVALID')
    }

    let resultRecoverable = false
    try {
      const stat = await fs.stat(this.resultPath(safeTaskId))
      resultRecoverable = stat.isFile() && stat.size === parsed.final_output_size_bytes
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    }
    return { ...parsed, result_recoverable: resultRecoverable }
  }
}

export const completionReceiptStore = new CompletionReceiptStore()
