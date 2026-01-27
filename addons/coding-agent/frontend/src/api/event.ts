import client from './client'
import type { Event } from '@/types'

export async function listEvents(params?: {
  conversationId?: string
  type?: string
  limit?: number
}): Promise<Event[]> {
  return client.get('/events', { params })
}

export function subscribeToEvents(
  conversationId: string,
  handlers: {
    onEvent?: (event: Event) => void
    onError?: (error: any) => void
    onOpen?: () => void
  }
): EventSource {
  const baseURL = import.meta.env.VITE_API_BASE_URL || ''
  const url = `${baseURL}/api/v1/events/stream?conversationId=${conversationId}`

  const eventSource = new EventSource(url)

  eventSource.onopen = () => handlers.onOpen?.()

  eventSource.onmessage = (e) => {
    try {
      const event: Event = JSON.parse(e.data)
      handlers.onEvent?.(event)
    } catch (error) {
      console.error('Failed to parse event:', error)
    }
  }

  eventSource.onerror = (e) => handlers.onError?.(e)

  return eventSource
}
