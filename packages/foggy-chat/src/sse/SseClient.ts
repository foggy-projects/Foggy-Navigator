import type { EventAdapter } from '../types/adapter'
import type { AipMessage } from '../types/aip'

export interface SseClientOptions<TRaw> {
  url: string
  eventName?: string
  adapter: EventAdapter<TRaw>
  sessionId: string
  onMessage: (messages: AipMessage[]) => void
  onConnected?: () => void
  onError?: (error: Event) => void
}

export function createSseClient<TRaw>(options: SseClientOptions<TRaw>): EventSource {
  const es = new EventSource(options.url)

  // Fix: use addEventListener for named events (backend sends .name("event"))
  es.addEventListener(options.eventName ?? 'event', (e: MessageEvent) => {
    const raw: TRaw = JSON.parse(e.data)
    const msgs = options.adapter.convert(raw, options.sessionId)
    if (msgs.length > 0) options.onMessage(msgs)
  })

  // Initial connection message (unnamed event)
  es.onmessage = (e: MessageEvent) => {
    try {
      const data = JSON.parse(e.data)
      if (data.type === 'connected') options.onConnected?.()
    } catch {
      // ignore non-JSON unnamed messages
    }
  }

  es.onerror = (e) => options.onError?.(e)

  return es
}
