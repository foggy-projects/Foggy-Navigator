import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import type { WorkerEvent } from '../models.js'
import { readJsonlAndRepair, syncParentDirectory } from './jsonl-durability.js'

export class EventBroadcast {
  private readonly events: WorkerEvent[] = []
  private readonly subscribers = new Set<(event: WorkerEvent) => void>()
  private readonly closeSubscribers = new Set<() => void>()
  private readonly jsonlPath: string
  private seq = 0
  private closed = false
  private closing = false
  private pendingWrite: Promise<void> = Promise.resolve()
  private writeError?: Error

  constructor(readonly taskId: string, eventsDir: string) {
    fs.mkdirSync(eventsDir, { recursive: true })
    this.jsonlPath = eventJournalPath(taskId, eventsDir)
  }

  static async purgePersisted(taskId: string, eventsDir: string): Promise<void> {
    const journal = eventJournalPath(taskId, eventsDir)
    const existed = fs.existsSync(journal)
    await fs.promises.rm(journal, { force: true })
    if (existed) await syncParentDirectory(journal)
  }

  nextSeq(): number {
    return ++this.seq
  }

  emit(event: WorkerEvent): void {
    if (this.closed || this.closing) return
    const persisted = event.seq === undefined ? { ...event, seq: this.nextSeq() } : event
    this.seq = Math.max(this.seq, persisted.seq || 0)
    this.pendingWrite = this.pendingWrite.then(async () => {
      if (this.writeError) return
      try {
        await appendDurable(this.jsonlPath, `${JSON.stringify(persisted)}\n`)
      } catch (error) {
        this.writeError = error instanceof Error ? error : new Error(String(error))
        return
      }
      this.events.push(persisted)
      this.notifySubscribers(persisted)
    })
  }

  loadFromDisk(): WorkerEvent[] {
    if (!fs.existsSync(this.jsonlPath)) return []
    const loaded = readJsonlAndRepair(this.jsonlPath, isWorkerEvent)
    this.events.splice(0, this.events.length, ...loaded)
    this.seq = loaded.reduce((max, event) => Math.max(max, event.seq || 0), 0)
    return [...loaded]
  }

  getEventsAfter(afterSeq: number): WorkerEvent[] {
    return this.events.filter(event => (event.seq || 0) > afterSeq)
  }

  subscribe(callback: (event: WorkerEvent) => void, onClose?: () => void): () => void {
    if (this.closed || this.closing) {
      queueMicrotask(() => onClose?.())
      return () => undefined
    }
    this.subscribers.add(callback)
    if (onClose) this.closeSubscribers.add(onClose)
    return () => {
      this.subscribers.delete(callback)
      if (onClose) this.closeSubscribers.delete(onClose)
    }
  }

  subscribeAfter(
    afterSeq: number,
    callback: (event: WorkerEvent) => void,
    onClose?: () => void,
  ): () => void {
    let latestDelivered = afterSeq
    const deliver = (event: WorkerEvent): void => {
      const sequence = event.seq || 0
      if (sequence <= latestDelivered) return
      latestDelivered = sequence
      callback(event)
    }
    const unsubscribe = this.subscribe(deliver, onClose)
    for (const event of this.getEventsAfter(afterSeq)) deliver(event)
    return unsubscribe
  }

  async close(): Promise<void> {
    if (this.closed) return
    if (this.closing) {
      await this.pendingWrite
      if (this.writeError) throw this.writeError
      return
    }
    this.closing = true
    await this.pendingWrite
    this.closed = true
    this.subscribers.clear()
    for (const subscriber of this.closeSubscribers) {
      try {
        subscriber()
      } catch {
        // Closing one disconnected response must not affect other subscribers.
      }
    }
    this.closeSubscribers.clear()
    if (this.writeError) throw this.writeError
  }

  isClosed(): boolean {
    return this.closed || this.closing
  }

  /** A failed journal must be replaced before a recovery attempt can emit again. */
  hasWriteError(): boolean {
    return this.writeError !== undefined
  }

  getLatestSeq(): number {
    return this.seq
  }

  getEventCount(): number {
    return this.events.length
  }

  async flush(): Promise<void> {
    await this.pendingWrite
    if (this.writeError) throw this.writeError
  }

  async purge(): Promise<void> {
    await this.pendingWrite
    this.events.splice(0, this.events.length)
    const existed = fs.existsSync(this.jsonlPath)
    await fs.promises.rm(this.jsonlPath, { force: true })
    if (existed) await syncParentDirectory(this.jsonlPath)
    this.writeError = undefined
  }

  private notifySubscribers(event: WorkerEvent): void {
    for (const subscriber of this.subscribers) {
      try {
        subscriber(event)
      } catch {
        // One disconnected/broken SSE subscriber must not affect task execution.
      }
    }
  }
}

function eventJournalPath(taskId: string, eventsDir: string): string {
  const file = crypto.createHash('sha256').update(taskId).digest('hex')
  return path.join(eventsDir, `${file}.jsonl`)
}

async function appendDurable(file: string, content: string): Promise<void> {
  const existed = fs.existsSync(file)
  const handle = await fs.promises.open(file, 'a', 0o600)
  try {
    await handle.writeFile(content, 'utf8')
    await handle.sync()
  } finally {
    await handle.close()
  }
  if (!existed) {
    await syncParentDirectory(file)
    await syncParentDirectory(path.dirname(file))
  }
}

function isWorkerEvent(value: unknown): value is WorkerEvent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const event = value as Partial<WorkerEvent>
  return typeof event.type === 'string'
    && typeof event.task_id === 'string'
    && Boolean(event.task_id)
}
