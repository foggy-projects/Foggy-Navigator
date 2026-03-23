import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import type { WorkerEvent } from '../models.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const EVENTS_DIR = path.resolve(__dirname, '..', '..', 'logs', 'events')

/**
 * EventBroadcast — manages event streaming to SSE subscribers
 *
 * Features:
 * - Monotonically increasing sequence numbers (ESN)
 * - In-memory event buffer for reconnection replay
 * - JSONL file persistence for crash recovery
 * - Multiple concurrent subscribers
 */
export class EventBroadcast {
  private taskId: string
  private events: WorkerEvent[] = []
  private seq: number = 0
  private closed: boolean = false
  private subscribers: Set<(event: WorkerEvent) => void> = new Set()
  private jsonlPath: string
  private pendingWrite: Promise<void> = Promise.resolve()

  constructor(taskId: string) {
    this.taskId = taskId

    // Ensure events directory exists
    if (!fs.existsSync(EVENTS_DIR)) {
      fs.mkdirSync(EVENTS_DIR, { recursive: true })
    }
    this.jsonlPath = path.join(EVENTS_DIR, `${taskId}.jsonl`)
  }

  /**
   * Get next sequence number
   */
  nextSeq(): number {
    return ++this.seq
  }

  /**
   * Emit an event to all subscribers and persist
   */
  emit(event: WorkerEvent): void {
    if (this.closed) return

    this.events.push(event)

    this.pendingWrite = this.pendingWrite
      .catch(() => undefined)
      .then(async () => {
        try {
          await fs.promises.appendFile(this.jsonlPath, JSON.stringify(event) + '\n')
        } catch (e) {
          console.warn(`Failed to persist event for task ${this.taskId}:`, e)
        }
      })

    // Notify subscribers
    for (const subscriber of this.subscribers) {
      try {
        subscriber(event)
      } catch (e) {
        console.warn(`Subscriber error for task ${this.taskId}:`, e)
      }
    }
  }

  /**
   * Subscribe to events (returns unsubscribe function)
   */
  subscribe(callback: (event: WorkerEvent) => void): () => void {
    this.subscribers.add(callback)
    return () => {
      this.subscribers.delete(callback)
    }
  }

  /**
   * Get events after a given sequence number (for reconnection replay)
   */
  getEventsAfter(afterSeq: number): WorkerEvent[] {
    return this.events.filter(e => (e.seq || 0) > afterSeq)
  }

  /**
   * Load events from JSONL file (for crash recovery)
   */
  loadFromDisk(): WorkerEvent[] {
    if (!fs.existsSync(this.jsonlPath)) return []

    try {
      const content = fs.readFileSync(this.jsonlPath, 'utf-8')
      const lines = content.trim().split('\n').filter(Boolean)
      const events = lines.map(line => JSON.parse(line) as WorkerEvent)
      this.events = events
      this.seq = events.reduce((max, e) => Math.max(max, e.seq || 0), 0)
      return events
    } catch (e) {
      console.warn(`Failed to load events for task ${this.taskId}:`, e)
      return []
    }
  }

  /**
   * Close the broadcast (no more events)
   */
  close(): void {
    this.closed = true
    this.subscribers.clear()
  }

  /**
   * Wait until all queued writes are flushed to disk.
   */
  async flush(): Promise<void> {
    await this.pendingWrite.catch(() => undefined)
  }

  /**
   * Check if broadcast is closed
   */
  isClosed(): boolean {
    return this.closed
  }

  /**
   * Get latest sequence number
   */
  getLatestSeq(): number {
    return this.seq
  }

  /**
   * Get total event count
   */
  getEventCount(): number {
    return this.events.length
  }

  /**
   * Cleanup persisted events
   */
  cleanup(): void {
    void this.pendingWrite
      .catch(() => undefined)
      .then(async () => {
        try {
          if (fs.existsSync(this.jsonlPath)) {
            await fs.promises.unlink(this.jsonlPath)
          }
        } catch (e) {
          console.warn(`Failed to cleanup events for task ${this.taskId}:`, e)
        }
      })
  }
}
