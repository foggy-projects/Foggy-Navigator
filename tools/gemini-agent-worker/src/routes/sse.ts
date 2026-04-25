import type { Response } from 'express'
import type { WorkerEvent } from '../models.js'

export const SSE_HEADERS = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
  'Connection': 'keep-alive',
  'X-Accel-Buffering': 'no',
} as const

export function startSse(res: Response): void {
  res.writeHead(200, SSE_HEADERS)
}

export function writeSseEvent(res: Response, event: unknown): boolean {
  try {
    res.write(`event: message\ndata: ${JSON.stringify(event)}\n\n`)
    return true
  } catch {
    return false
  }
}

export function writeWorkerEvents(res: Response, events: WorkerEvent[]): boolean {
  for (const event of events) {
    if (!writeSseEvent(res, event)) {
      return false
    }
  }
  return true
}

export function getSingleValue(value: string | string[] | undefined): string | undefined {
  if (Array.isArray(value)) {
    return value[0]
  }
  return value
}

export function getRequiredParam(value: string | string[] | undefined): string {
  return getSingleValue(value) || ''
}
