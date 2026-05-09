import type { AgentTask, NavigatorChatConfig, TaskMessagesPage } from '../types'

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
  async ask(question: string, contextId?: string): Promise<AgentTask> {
    const body: Record<string, unknown> = { question }
    if (contextId) body.contextId = contextId
    if (this.config.maxTurns) body.maxTurns = this.config.maxTurns
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

  private async unwrap<T>(resp: Response): Promise<T> {
    if (!resp.ok) {
      const text = await resp.text().catch(() => '')
      throw new Error(`HTTP ${resp.status}: ${text}`)
    }
    const json = await resp.json()
    if (json.code !== 0) {
      throw new Error(json.msg || `API error code=${json.code}`)
    }
    return json.data as T
  }
}
