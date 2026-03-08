import type { SseTransportOptions, SseTransport } from './types'

/**
 * H5 平台 SSE 传输层 — 基于 fetch + ReadableStream
 */
export function createFetchSseTransport(options: SseTransportOptions): SseTransport {
  let abortController: AbortController | null = new AbortController()

  async function start() {
    try {
      const response = await fetch(options.url, {
        headers: {
          Accept: 'text/event-stream',
          'Cache-Control': 'no-cache',
          ...(options.headers ?? {}),
        },
        signal: abortController!.signal,
      })

      if (!response.ok || !response.body) {
        options.onError(new Error(`SSE connection failed: ${response.status}`))
        options.onClose()
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let eventType = ''
      const dataLines: string[] = []

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        const lastNewline = buffer.lastIndexOf('\n')
        if (lastNewline === -1) continue

        const complete = buffer.substring(0, lastNewline + 1)
        buffer = buffer.substring(lastNewline + 1)

        const lines = complete.split('\n')
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventType = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim())
          } else if (line.startsWith('id:')) {
            // SSE id field - ignored
          } else if (line.trim() === '') {
            if (dataLines.length > 0) {
              const data = dataLines.join('\n')
              options.onEvent(eventType, data)
              dataLines.length = 0
              eventType = ''
            }
          }
        }
      }

      options.onClose()
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') return
      options.onError(err)
      options.onClose()
    }
  }

  start()

  return {
    close() {
      if (abortController) {
        abortController.abort()
        abortController = null
      }
    },
  }
}
