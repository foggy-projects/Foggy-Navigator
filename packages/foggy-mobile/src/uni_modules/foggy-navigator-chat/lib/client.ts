import type {
  FoggyNavigatorChatClient,
  FoggyNavigatorChatConfig,
  FoggyNavigatorSendOptions,
  FoggyNavigatorSessionMessagesPage,
  FoggyNavigatorSessionPage,
  FoggyNavigatorTask,
  FoggyNavigatorTaskMessagesPage,
  PaginationOptions,
} from './types'

type UniHttpMethod = 'GET' | 'POST' | 'DELETE'

interface UniRequestResult {
  statusCode?: number
  data?: unknown
  errMsg?: string
}

interface ResponseEnvelope<T> {
  code?: number | string
  msg?: string
  message?: string
  data?: T
}

export function createUniNavigatorBffClient(config: FoggyNavigatorChatConfig): FoggyNavigatorChatClient {
  return new UniNavigatorBffClient(config)
}

export class UniNavigatorBffClient implements FoggyNavigatorChatClient {
  private readonly config: FoggyNavigatorChatConfig

  constructor(config: FoggyNavigatorChatConfig) {
    this.config = config
  }

  async ask(
    question: string,
    contextId?: string,
    options: FoggyNavigatorSendOptions = {},
  ): Promise<FoggyNavigatorTask> {
    const body: Record<string, unknown> = { question }
    if (contextId) body.contextId = contextId
    if (this.config.maxTurns) body.maxTurns = this.config.maxTurns
    if (options.metadata && Object.keys(options.metadata).length > 0) body.metadata = options.metadata
    if (options.clientContext && Object.keys(options.clientContext).length > 0) body.clientContext = options.clientContext
    if (options.attachments && options.attachments.length > 0) body.attachments = options.attachments
    return this.post(`/agents/${encodeURIComponent(this.config.agentId)}/ask`, body)
  }

  async getTaskMessages(
    taskId: string,
    cursor?: string | null,
    limit = 50,
  ): Promise<FoggyNavigatorTaskMessagesPage> {
    const params = paginationParams({ cursor, limit }, 50)
    return this.get(`/agents/${encodeURIComponent(this.config.agentId)}/tasks/${encodeURIComponent(taskId)}/messages?${params}`)
  }

  async cancelTask(taskId: string): Promise<void> {
    await this.post(`/agents/${encodeURIComponent(this.config.agentId)}/tasks/${encodeURIComponent(taskId)}/cancel`, {})
  }

  async listSessions(options: PaginationOptions = {}): Promise<FoggyNavigatorSessionPage> {
    const params = paginationParams(options, 20)
    return this.get(`/agents/${encodeURIComponent(this.config.agentId)}/sessions?${params}`)
  }

  async getSessionMessages(
    contextId: string,
    options: PaginationOptions = {},
  ): Promise<FoggyNavigatorSessionMessagesPage> {
    const params = paginationParams(options, 50)
    return this.get(`/agents/${encodeURIComponent(this.config.agentId)}/sessions/${encodeURIComponent(contextId)}/messages?${params}`)
  }

  async deleteSession(contextId: string): Promise<void> {
    await this.delete(`/agents/${encodeURIComponent(this.config.agentId)}/sessions/${encodeURIComponent(contextId)}`)
  }

  private async get<T>(path: string): Promise<T> {
    return this.request<T>('GET', path)
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>('POST', path, body)
  }

  private async delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }

  private async request<T>(method: UniHttpMethod, path: string, data?: unknown): Promise<T> {
    const header = await this.headers(method)
    const result = await uniRequest({
      url: `${this.openApiBaseUrl}${path}`,
      method,
      data,
      header,
    })
    return unwrapEnvelope<T>(result)
  }

  private get openApiBaseUrl(): string {
    return `${this.config.baseUrl.replace(/\/$/, '')}/api/v1/open`
  }

  private async headers(method: UniHttpMethod): Promise<Record<string, string>> {
    const configured = typeof this.config.headers === 'function'
      ? await this.config.headers()
      : this.config.headers
    const header: Record<string, string> = {
      Accept: 'application/json',
      ...(configured ?? {}),
    }
    if (method === 'POST') {
      header['Content-Type'] = header['Content-Type'] ?? 'application/json'
    }
    return header
  }
}

function paginationParams(options: PaginationOptions, defaultLimit: number): string {
  const params: string[] = []
  if (options.cursor) params.push(`cursor=${encodeURIComponent(options.cursor)}`)
  params.push(`limit=${encodeURIComponent(String(options.limit ?? defaultLimit))}`)
  return params.join('&')
}

function uniRequest(options: {
  url: string
  method: UniHttpMethod
  data?: unknown
  header?: Record<string, string>
}): Promise<UniRequestResult> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: options.url,
      method: options.method,
      data: options.data,
      header: options.header,
      success: (result: UniRequestResult) => resolve(result),
      fail: (error: unknown) => reject(error),
    })
  })
}

function unwrapEnvelope<T>(result: UniRequestResult): T {
  const statusCode = result.statusCode ?? 0
  if (statusCode < 200 || statusCode >= 300) {
    throw new Error(`HTTP ${statusCode}: ${safeErrorMessage(result.data)}`)
  }

  const payload = result.data
  if (payload && typeof payload === 'object' && 'code' in payload) {
    const envelope = payload as ResponseEnvelope<T>
    const code = Number(envelope.code)
    if (code !== 0 && code !== 200) {
      throw new Error(envelope.msg || envelope.message || `API error code=${String(envelope.code)}`)
    }
    return envelope.data as T
  }

  return payload as T
}

function safeErrorMessage(value: unknown): string {
  if (!value) return ''
  if (typeof value === 'string') return sanitizeErrorSummary(value)
  if (typeof value === 'object') {
    const object = value as Record<string, unknown>
    const message = object.msg ?? object.message ?? object.error
    if (typeof message === 'string') return sanitizeErrorSummary(message)
  }
  return '请求失败'
}

function sanitizeErrorSummary(content: string): string {
  if (/task_scoped_token|adapterConfigJson|manifestJson|client_app_secret|runtime credential|authorization|worker gateway|gateway url|secret/i.test(content)) {
    return '请求失败，请稍后重试或联系管理员。'
  }
  return content
}
