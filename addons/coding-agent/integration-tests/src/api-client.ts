import axios, { AxiosInstance, AxiosError } from 'axios';
import EventSource from 'eventsource';
import { TEST_CONFIG } from './config.js';
import type {
  CreateConversationRequest,
  Conversation,
  SendMessageRequest,
  Message,
  Event,
  ApiResponse
} from './types.js';

/**
 * Coding Agent API 客户端
 */
export class CodingAgentClient {
  private client: AxiosInstance;

  constructor(baseURL: string = TEST_CONFIG.baseURL) {
    this.client = axios.create({
      baseURL,
      timeout: TEST_CONFIG.timeout,
      headers: {
        'Content-Type': 'application/json'
      }
    });

    // 添加响应拦截器：将 AxiosError 转为可序列化的 Error（避免 Vitest DataCloneError）
    this.client.interceptors.response.use(
      response => response,
      (error: AxiosError) => {
        const status = error.response?.status;
        const data = error.response?.data;
        const url = error.config?.url;
        console.error('API Error:', { url, status, data });
        const apiError: any = new Error(`API Error: ${error.message}`);
        apiError.response = { status, data };
        apiError.url = url;
        throw apiError;
      }
    );
  }

  /**
   * 登录并设置 JWT token
   */
  async login(username: string, password: string): Promise<void> {
    const response = await this.client.post('/api/v1/auth/login', {
      username,
      password
    });
    const token = response.data?.data?.token;
    if (!token) {
      throw new Error('Login failed: no token returned');
    }
    this.client.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    console.log('✓ Authenticated as', username);
  }

  /**
   * 创建 Conversation
   */
  async createConversation(
    request: CreateConversationRequest
  ): Promise<Conversation> {
    const response = await this.client.post<Conversation>(
      '/api/v1/conversations',
      request
    );
    return response.data;
  }

  /**
   * 获取 Conversation 详情
   */
  async getConversation(conversationId: string): Promise<Conversation> {
    const response = await this.client.get<Conversation>(
      `/api/v1/conversations/${conversationId}`
    );
    return response.data;
  }

  /**
   * 查询 Conversation 列表
   */
  async listConversations(params?: {
    userId?: string;
    projectId?: string;
    status?: string;
    page?: number;
    size?: number;
  }): Promise<Conversation[]> {
    const response = await this.client.get<Conversation[]>(
      '/api/v1/conversations',
      { params }
    );
    return response.data;
  }

  /**
   * 停止 Conversation
   */
  async stopConversation(conversationId: string): Promise<void> {
    await this.client.post(`/api/v1/conversations/${conversationId}/stop`);
  }

  /**
   * 删除 Conversation
   */
  async deleteConversation(conversationId: string): Promise<void> {
    await this.client.delete(`/api/v1/conversations/${conversationId}`);
  }

  /**
   * 发送消息
   */
  async sendMessage(
    conversationId: string,
    request: SendMessageRequest
  ): Promise<Message> {
    const response = await this.client.post<Message>(
      `/api/v1/conversations/${conversationId}/messages`,
      request
    );
    return response.data;
  }

  /**
   * 获取消息列表
   */
  async getMessages(
    conversationId: string,
    limit?: number
  ): Promise<Message[]> {
    const response = await this.client.get<Message[]>(
      `/api/v1/conversations/${conversationId}/messages`,
      { params: { limit } }
    );
    return response.data;
  }

  /**
   * 查询历史事件
   */
  async getEvents(
    conversationId: string,
    params?: {
      kind?: string;
      timestampGte?: string;
      timestampLt?: string;
      pageId?: string;
      limit?: number;
    }
  ): Promise<Event[]> {
    const response = await this.client.get<Event[]>(
      `/api/v1/conversations/${conversationId}/events`,
      { params }
    );
    return response.data;
  }

  /**
   * 订阅 SSE 事件流
   */
  subscribeToEvents(
    conversationId: string,
    callbacks: {
      onEvent?: (event: Event) => void;
      onError?: (error: any) => void;
      onOpen?: () => void;
    },
    lastEventId?: string
  ): EventSource {
    const url = new URL(
      `/api/v1/conversations/${conversationId}/events/stream`,
      this.client.defaults.baseURL
    );

    if (lastEventId) {
      url.searchParams.set('lastEventId', lastEventId);
    }

    const authHeader = this.client.defaults.headers.common['Authorization'] as string | undefined;
    const eventSource = new EventSource(url.toString(), {
      headers: authHeader ? { Authorization: authHeader } : {}
    } as any);

    eventSource.onopen = () => {
      console.log('SSE connection opened');
      callbacks.onOpen?.();
    };

    eventSource.onerror = (error) => {
      console.error('SSE error:', error);
      callbacks.onError?.(error);
    };

    // 监听所有事件类型
    const eventTypes = [
      'CONVERSATION_STATUS',
      'MESSAGE_SENT',
      'AGENT_ACTION',
      'AGENT_OBSERVATION',
      'VALIDATION_TRIGGERED',
      'VALIDATION_RESULT',
      'ERROR'
    ];

    eventTypes.forEach(eventType => {
      eventSource.addEventListener(eventType, (e: any) => {
        try {
          const event: Event = {
            id: e.lastEventId,
            conversationId,
            kind: eventType as any,
            timestamp: new Date().toISOString(),
            data: JSON.parse(e.data)
          };
          callbacks.onEvent?.(event);
        } catch (error) {
          console.error('Failed to parse SSE event:', error);
        }
      });
    });

    return eventSource;
  }

  /**
   * 触发验证
   */
  async triggerValidation(conversationId: string): Promise<void> {
    await this.client.post(
      `/api/v1/conversations/${conversationId}/validate`
    );
  }

  /**
   * 健康检查
   */
  async healthCheck(): Promise<boolean> {
    try {
      await this.client.get('/actuator/health');
      return true;
    } catch (error) {
      return false;
    }
  }

  /**
   * 等待服务就绪
   */
  async waitForReady(maxAttempts: number = 30, interval: number = 1000): Promise<void> {
    for (let i = 0; i < maxAttempts; i++) {
      const isReady = await this.healthCheck();
      if (isReady) {
        console.log('Service is ready');
        return;
      }
      console.log(`Waiting for service... (${i + 1}/${maxAttempts})`);
      await new Promise(resolve => setTimeout(resolve, interval));
    }
    throw new Error('Service failed to become ready');
  }
}

/**
 * 创建默认客户端实例
 */
export function createClient(baseURL?: string): CodingAgentClient {
  return new CodingAgentClient(baseURL);
}

/**
 * 创建已认证的客户端实例
 */
export async function createAuthenticatedClient(baseURL?: string): Promise<CodingAgentClient> {
  const client = new CodingAgentClient(baseURL);
  await client.login(TEST_CONFIG.auth.username, TEST_CONFIG.auth.password);
  return client;
}
