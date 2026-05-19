import type {
  AgentTask,
  NavigatorChatConfig,
  NavigatorSendOptions,
  PaginationOptions,
  SessionListPage,
  SessionMessagesPage,
  TaskMessagesPage,
} from '../types'

/**
 * Navigator Open API 轻量客户端
 *
 * 支持两种模式：
 * 1. 直连模式：直接请求 Navigator（需要 CORS + apiKey）
 * 2. 代理模式：通过上游后端代理（config.fetch 自定义请求）
 */
export class NavigatorApi {
  private readonly config: NavigatorChatConfig
  private readonly doFetch: (url: string, init: RequestInit) => Promise<Response>

  constructor(config: NavigatorChatConfig) {
    this.config = config
    this.doFetch = config.fetch ?? globalThis.fetch.bind(globalThis)
  }

  /** 发送任务 */
  async ask(question: string, contextId?: string, options: NavigatorSendOptions = {}): Promise<AgentTask> {
    const body: Record<string, unknown> = { question }
    if (contextId) body.contextId = contextId
    if (this.config.maxTurns) body.maxTurns = this.config.maxTurns
    if (options.metadata && Object.keys(options.metadata).length > 0) body.metadata = options.metadata
    if (options.clientContext && Object.keys(options.clientContext).length > 0) body.clientContext = options.clientContext
    if (options.attachments && options.attachments.length > 0) body.attachments = options.attachments
    return this.post(`/agents/${this.config.agentId}/ask`, body)
  }

  /** 轮询任务状态 */
  async getTask(taskId: string): Promise<AgentTask> {
    return this.get(`/agents/${this.config.agentId}/tasks/${taskId}`)
  }

  /** 轮询任务增量消息 */
  async getTaskMessages(taskId: string, cursor?: string | null, limit = 50): Promise<TaskMessagesPage> {
    const params = new URLSearchParams()
    if (cursor) params.set('cursor', cursor)
    params.set('limit', String(limit))
    return this.get(`/agents/${this.config.agentId}/tasks/${taskId}/messages?${params.toString()}`)
  }

  /** 取消任务 */
  async cancelTask(taskId: string): Promise<void> {
    await this.post(`/agents/${this.config.agentId}/tasks/${taskId}/cancel`, {})
  }

  /** 列出活跃任务 */
  async listActiveTasks(): Promise<AgentTask[]> {
    return this.get(`/agents/${this.config.agentId}/tasks`)
  }

  /** 获取历史会话列表 */
  async listSessions(options: PaginationOptions = {}): Promise<SessionListPage> {
    const params = this.paginationParams(options, 20)
    return this.get(`/agents/${this.config.agentId}/sessions?${params.toString()}`)
  }

  /** 获取指定历史会话消息 */
  async getSessionMessages(contextId: string, options: PaginationOptions = {}): Promise<SessionMessagesPage> {
    const params = this.paginationParams(options, 50)
    return this.get(`/agents/${this.config.agentId}/sessions/${encodeURIComponent(contextId)}/messages?${params.toString()}`)
  }

  /** 删除指定历史会话 */
  async deleteSession(contextId: string): Promise<void> {
    await this.delete(`/agents/${this.config.agentId}/sessions/${encodeURIComponent(contextId)}`)
  }

  // ===== HTTP helpers =====

  private async get<T>(path: string): Promise<T> {
    const url = `${this.baseUrl}${path}`
    const resp = await this.doFetch(url, {
      method: 'GET',
      headers: this.headers(),
    })
    return this.unwrap(resp)
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    const url = `${this.baseUrl}${path}`
    const resp = await this.doFetch(url, {
      method: 'POST',
      headers: { ...this.headers(), 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    return this.unwrap(resp)
  }

  private async delete<T>(path: string): Promise<T> {
    const url = `${this.baseUrl}${path}`
    const resp = await this.doFetch(url, {
      method: 'DELETE',
      headers: this.headers(),
    })
    return this.unwrap(resp)
  }

  private get baseUrl(): string {
    const base = this.config.baseUrl.replace(/\/$/, '')
    return `${base}/api/v1/open`
  }

  private headers(): Record<string, string> {
    const h: Record<string, string> = { Accept: 'application/json' }
    if (this.config.apiKey) {
      h['X-API-Key'] = this.config.apiKey
    }
    return h
  }

  private paginationParams(options: PaginationOptions, defaultLimit: number): URLSearchParams {
    const params = new URLSearchParams()
    if (options.cursor) params.set('cursor', options.cursor)
    params.set('limit', String(options.limit ?? defaultLimit))
    return params
  }

  private async unwrap<T>(resp: Response): Promise<T> {
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      throw new Error(`HTTP ${resp.status}: ${text}`)
    }
    if (resp.status === 204) {
      return undefined as T
    }
    const json = await resp.json()
    if (json.code !== 0) {
      throw new Error(json.msg || `API error code=${json.code}`)
    }
    return json.data as T
  }
}
