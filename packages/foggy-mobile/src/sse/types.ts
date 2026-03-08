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
