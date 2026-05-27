import type { SseTransportOptions, SseTransport } from './types'

/**
 * 微信小程序 SSE 传输层 — 基于 wx.request({ enableChunked: true })
 */
export function createWxSseTransport(options: SseTransportOptions): SseTransport {
  let buffer = ''
  let eventType = ''
  const dataLines: string[] = []

  const requestTask = uni.request({
    url: options.url,
    method: 'GET',
    header: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      ...(options.headers ?? {}),
    },
    enableChunkedTransfer: true,
    success: () => {
      options.onClose()
    },
    fail: (err) => {
      options.onError(err)
      options.onClose()
    },
  })

  // Listen for chunked data
  const chunkedRequestTask = requestTask as typeof requestTask & {
    onChunkReceived?: (callback: (res: { data: ArrayBuffer }) => void) => void
  }
  if (chunkedRequestTask.onChunkReceived) {
    chunkedRequestTask.onChunkReceived((res: { data: ArrayBuffer }) => {
      const decoder = new TextDecoder()
      const chunk = decoder.decode(res.data)
      buffer += chunk

      const lastNewline = buffer.lastIndexOf('\n')
      if (lastNewline === -1) return

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
    })
  }

  return {
    close() {
      requestTask.abort()
    },
  }
}
