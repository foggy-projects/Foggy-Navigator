export type { ChatMessage, ConnectionStatus, ExecutionReportDigest } from '@foggy/chat-core'

export interface ExecutionReportMarkdownPayload {
  ok?: boolean
  mode?: string
  reportRef?: string
  report_ref?: string
  markdown?: string
  markdownExcerpt?: string
  markdown_excerpt?: string
  content?: string
  text?: string
  error?: string
  message?: string
  truncated?: boolean
  [key: string]: unknown
}

export type ExecutionReportMarkdownLoader = (
  reportRef: string,
) => Promise<string | ExecutionReportMarkdownPayload>
