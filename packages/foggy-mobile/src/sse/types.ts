export interface SseTransportOptions {
  url: string
  headers?: Record<string, string>
  onEvent: (eventType: string, data: string) => void
  onError: (error: unknown) => void
  onClose: () => void
}

export interface SseTransport {
  close(): void
}

export interface UniSseClientOptions<TRaw> {
  url: string
  eventName?: string
  sessionId: string
  headers?: Record<string, string>
  adapter: { convert(raw: TRaw, sessionId: string): import('@foggy/chat-core').AipMessage[] }
  onMessage: (messages: import('@foggy/chat-core').AipMessage[]) => void
  onConnected?: () => void
  onError?: (error: unknown) => void
  onRawEvent?: (raw: TRaw) => void
  maxRetries?: number
  baseRetryDelayMs?: number
  maxRetryDelayMs?: number
  onReconnecting?: (attempt: number) => void
  onMaxRetriesExceeded?: () => void
}

export interface UniSseController {
  close(): void
}
