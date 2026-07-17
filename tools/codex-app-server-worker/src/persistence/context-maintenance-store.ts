import fs from 'node:fs/promises'
import path from 'node:path'
import { readJsonlAndRepair, syncParentDirectory } from './jsonl-durability.js'

export type ContextUsageSnapshot = {
  schema_version: 1
  thread_id: string
  turn_id?: string
  observed_at: string
  last_total_tokens?: number
  model_context_window?: number
  remaining_tokens?: number
  status: 'known' | 'window_unknown' | 'unknown'
}

export type ContextCompactOperation = {
  schema_version: 1
  operation_id: string
  task_id: string
  thread_id: string
  status: 'running' | 'completed' | 'failed' | 'unknown'
  compact_turn_id?: string
  error_code?: string
  created_at: string
  updated_at: string
}

export class ContextCompactOperationConflictError extends Error {
  readonly code = 'CONTEXT_COMPACT_OPERATION_CONFLICT'
  constructor() {
    super('Context compact operation id is already bound to another task or thread')
    this.name = 'ContextCompactOperationConflictError'
  }
}

export class ContextMaintenanceStore {
  private readonly root: string
  private readonly usage = new Map<string, ContextUsageSnapshot>()
  private readonly operations = new Map<string, ContextCompactOperation>()
  private writeTail: Promise<void> = Promise.resolve()
  private usageMutationTail: Promise<void> = Promise.resolve()
  private operationMutationTail: Promise<void> = Promise.resolve()

  constructor(stateDir: string, private readonly now: () => Date = () => new Date()) {
    this.root = path.join(stateDir, 'context-maintenance')
  }

  async initialize(): Promise<void> {
    await fs.mkdir(this.root, { recursive: true, mode: 0o700 })
    await fs.chmod(this.root, 0o700)
    await this.loadFile<ContextUsageSnapshot>('usage.jsonl', isUsage, item => this.usage.set(item.thread_id, item))
    await this.loadFile<ContextCompactOperation>('operations.jsonl', isOperation, item => this.operations.set(item.operation_id, item))
    const interrupted = [...this.operations.values()].filter(item => item.status === 'running')
    for (const item of interrupted) {
      await this.updateOperation(item.operation_id, {
        status: 'unknown',
        error_code: 'APP_SERVER_COMPACT_RECOVERY_REQUIRED',
      })
    }
  }

  getUsage(threadId: string): ContextUsageSnapshot | undefined {
    return clone(this.usage.get(threadId))
  }

  async recordUsage(snapshot: ContextUsageSnapshot): Promise<ContextUsageSnapshot> {
    let result: ContextUsageSnapshot | undefined
    const mutation = this.usageMutationTail.then(async () => {
      const previous = this.usage.get(snapshot.thread_id)
      if (previous && previous.observed_at > snapshot.observed_at) {
        result = clone(previous)
        return
      }
      await this.append('usage.jsonl', snapshot)
      this.usage.set(snapshot.thread_id, clone(snapshot)!)
      result = clone(snapshot)
    })
    this.usageMutationTail = mutation.catch(() => undefined)
    await mutation
    return result!
  }

  getOperation(operationId: string): ContextCompactOperation | undefined {
    return clone(this.operations.get(operationId))
  }

  async startOperation(taskId: string, threadId: string, operationId: string): Promise<ContextCompactOperation> {
    let result: ContextCompactOperation | undefined
    const mutation = this.operationMutationTail.then(async () => {
      const existing = this.operations.get(operationId)
      if (existing) {
        if (existing.task_id !== taskId || existing.thread_id !== threadId) {
          throw new ContextCompactOperationConflictError()
        }
        result = clone(existing)
        return
      }
      const timestamp = this.now().toISOString()
      const operation: ContextCompactOperation = {
        schema_version: 1,
        operation_id: operationId,
        task_id: taskId,
        thread_id: threadId,
        status: 'running',
        created_at: timestamp,
        updated_at: timestamp,
      }
      await this.append('operations.jsonl', operation)
      this.operations.set(operationId, operation)
      result = clone(operation)
    })
    this.operationMutationTail = mutation.catch(() => undefined)
    await mutation
    return result!
  }

  async updateOperation(
    operationId: string,
    patch: Partial<Pick<ContextCompactOperation, 'status' | 'compact_turn_id' | 'error_code'>>,
  ): Promise<ContextCompactOperation> {
    let result: ContextCompactOperation | undefined
    const mutation = this.operationMutationTail.then(async () => {
      const current = this.operations.get(operationId)
      if (!current) throw new Error(`Context compact operation not found: ${operationId}`)
      const updated = {
        ...current,
        ...patch,
        updated_at: this.now().toISOString(),
      }
      await this.append('operations.jsonl', updated)
      this.operations.set(operationId, updated)
      result = clone(updated)
    })
    this.operationMutationTail = mutation.catch(() => undefined)
    await mutation
    return result!
  }

  private async loadFile<T>(name: string, validate: (value: unknown) => value is T, remember: (item: T) => void) {
    const file = path.join(this.root, name)
    try {
      for (const item of readJsonlAndRepair(file, validate)) remember(item)
    } catch (error) {
      if (!isNodeError(error, 'ENOENT')) throw error
    }
  }

  private async append(name: string, value: unknown): Promise<void> {
    const file = path.join(this.root, name)
    const write = this.writeTail.then(async () => {
      const handle = await fs.open(file, 'a', 0o600)
      try {
        await handle.writeFile(`${JSON.stringify(value)}\n`, 'utf8')
        await handle.sync()
      } finally {
        await handle.close()
      }
      await fs.chmod(file, 0o600)
      await syncParentDirectory(file)
    })
    this.writeTail = write.catch(() => undefined)
    return write
  }
}

function isUsage(value: unknown): value is ContextUsageSnapshot {
  if (!isRecord(value)) return false
  return value.schema_version === 1
    && typeof value.thread_id === 'string'
    && typeof value.observed_at === 'string'
    && ['known', 'window_unknown', 'unknown'].includes(String(value.status))
}

function isOperation(value: unknown): value is ContextCompactOperation {
  if (!isRecord(value)) return false
  return value.schema_version === 1
    && typeof value.operation_id === 'string'
    && typeof value.task_id === 'string'
    && typeof value.thread_id === 'string'
    && ['running', 'completed', 'failed', 'unknown'].includes(String(value.status))
    && typeof value.created_at === 'string'
    && typeof value.updated_at === 'string'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function isNodeError(error: unknown, code: string): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error && (error as NodeJS.ErrnoException).code === code
}

function clone<T>(value: T | undefined): T | undefined {
  return value === undefined ? undefined : structuredClone(value)
}
