import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import type { WorkerEvent } from '../models.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const EVENTS_DIR = path.resolve(__dirname, '..', '..', 'logs', 'events')

let eventsDirReady = false

function ensureEventsDir(): void {
  if (!eventsDirReady) {
    fs.mkdirSync(EVENTS_DIR, { recursive: true })
    eventsDirReady = true
  }
}

export class EventBroadcast {
  private taskId: string
  private events: WorkerEvent[] = []
  private seq: number = 0
  private closed: boolean = false
  private subscribers: Set<(event: WorkerEvent) => void> = new Set()
  private jsonlPath: string
  private writeStream: fs.WriteStream | null = null

  constructor(taskId: string) {
    this.taskId = taskId
    ensureEventsDir()
    this.jsonlPath = path.join(EVENTS_DIR, `${taskId}.jsonl`)
  }

  private getWriteStream(): fs.WriteStream {
    if (!this.writeStream) {
      // Long-lived append stream avoids per-event open()/close() syscalls for streams that
      // can fire dozens of times per task. The serial event ordering Node.js guarantees on
      // a single WriteStream replaces the pendingWrite promise chain.
      this.writeStream = fs.createWriteStream(this.jsonlPath, { flags: 'a' })
      this.writeStream.on('error', err => {
        console.warn(`Write stream error for task ${this.taskId}:`, err)
      })
    }
    return this.writeStream
  }

  nextSeq(): number {
    return ++this.seq
  }

  emit(event: WorkerEvent): void {
    if (this.closed) return
    this.events.push(event)
    try {
      this.getWriteStream().write(JSON.stringify(event) + '\n')
    } catch (e) {
      console.warn(`Failed to persist event for task ${this.taskId}:`, e)
    }

    for (const subscriber of this.subscribers) {
      try {
        subscriber(event)
      } catch (e) {
        console.warn(`Subscriber error for task ${this.taskId}:`, e)
      }
    }
  }

  subscribe(callback: (event: WorkerEvent) => void): () => void {
    this.subscribers.add(callback)
    return () => {
      this.subscribers.delete(callback)
    }
  }

  getEventsAfter(afterSeq: number): WorkerEvent[] {
    return this.events.filter(e => (e.seq || 0) > afterSeq)
  }

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

  // Drain pending writes through the kernel before close. Lets callers replace the
  // previous fixed-delay sleep in cli-wrapper that used to "give appendFile time to land".
  async flush(): Promise<void> {
    const stream = this.writeStream
    if (!stream || stream.writableEnded) return
    await new Promise<void>(resolve => {
      stream.write('', () => resolve())
    })
  }

  close(): void {
    this.closed = true
    this.subscribers.clear()
    if (this.writeStream && !this.writeStream.writableEnded) {
      this.writeStream.end()
    }
  }

  isClosed(): boolean {
    return this.closed
  }

  getLatestSeq(): number {
    return this.seq
  }

  getEventCount(): number {
    return this.events.length
  }

  cleanup(): void {
    if (this.writeStream && !this.writeStream.writableEnded) {
      this.writeStream.end()
    }
    this.writeStream = null
    fs.promises.unlink(this.jsonlPath).catch(e => {
      const err = e as NodeJS.ErrnoException
      if (err.code !== 'ENOENT') {
        console.warn(`Failed to cleanup events for task ${this.taskId}:`, e)
      }
    })
  }
}
