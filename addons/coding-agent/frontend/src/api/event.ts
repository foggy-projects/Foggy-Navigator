import client from './client'
import type { Event as CodingEvent } from '@/types'
import { createSseClient } from '@foggy/chat'
import type { SseClientOptions } from '@foggy/chat'
import type { OhRawEvent } from '@/adapters/OpenHandsAdapter'
import { openHandsAdapter } from '@/adapters/OpenHandsAdapter'
import { getToken } from '@/utils/auth'

export async function listEvents(params?: {
  conversationId?: string
  type?: string
  limit?: number
}): Promise<CodingEvent[]> {
  return client.get('/events', { params })
}

export function subscribeToConversation(
  conversationId: string,
  handlers: {
    onMessage?: SseClientOptions<OhRawEvent>['onMessage']
    onConnected?: () => void
    onError?: (error: globalThis.Event) => void
  }
): EventSource {
  // Build SSE URL — bypass Vite proxy for SSE to avoid buffering/timeout issues
  const token = getToken()
  // In dev mode, connect directly to the backend; in production, use relative path
  const sseBase = import.meta.env.DEV
    ? 'http://localhost:8112/api/v1'
    : (import.meta.env.VITE_API_BASE_URL || '/api/v1')
  const url = `${sseBase}/events/stream?conversationId=${conversationId}${token ? `&token=${token}` : ''}`

  return createSseClient<OhRawEvent>({
    url,
    eventName: 'event',
    adapter: openHandsAdapter,
    sessionId: conversationId,
    onMessage: handlers.onMessage ?? (() => {}),
    onConnected: handlers.onConnected,
    onError: handlers.onError,
  })
}
