import axios, { AxiosInstance, AxiosError } from 'axios';
import EventSource from 'eventsource';
import { TEST_CONFIG } from './config.js';
import type {
  RXResponse,
  CreateSessionRequest,
  SendMessageRequest,
  Session,
  Message,
  AgentMessage,
  GuideCard,
  LoginResultDTO
} from './types.js';

/**
 * Session Module API 客户端
 */
export class SessionClient {
  private client: AxiosInstance;

  constructor(baseURL: string = TEST_CONFIG.baseURL) {
    this.client = axios.create({
      baseURL,
      timeout: TEST_CONFIG.timeout,
      headers: {
        'Content-Type': 'application/json'
      }
    });

    // 响应拦截器：将 AxiosError 转为可序列化的 Error（避免 Vitest DataCloneError）
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
    const response = await this.client.post<RXResponse<LoginResultDTO>>(
      '/api/v1/auth/login',
      { username, password }
    );
    const token = response.data?.data?.token;
    if (!token) {
      throw new Error('Login failed: no token returned');
    }
    this.client.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    console.log('✓ Authenticated as', username);
  }

  /**
   * 创建会话
   */
  async createSession(request: CreateSessionRequest): Promise<Session> {
    const response = await this.client.post<RXResponse<Session>>(
      '/api/v1/sessions',
      request
    );
    return response.data.data!;
  }

  /**
   * 获取会话详情
   */
  async getSession(id: string): Promise<Session> {
    const response = await this.client.get<RXResponse<Session>>(
      `/api/v1/sessions/${id}`
    );
    return response.data.data!;
  }

  /**
   * 查询会话列表
   */
  async listSessions(agentId?: string): Promise<Session[]> {
    const response = await this.client.get<RXResponse<Session[]>>(
      '/api/v1/sessions',
      { params: agentId ? { agentId } : {} }
    );
    return response.data.data!;
  }

  /**
   * 删除会话
   */
  async deleteSession(id: string): Promise<void> {
    await this.client.delete(`/api/v1/sessions/${id}`);
  }

  /**
   * 获取消息列表
   */
  async getMessages(sessionId: string): Promise<Message[]> {
    const response = await this.client.get<RXResponse<Message[]>>(
      `/api/v1/sessions/${sessionId}/messages`
    );
    return response.data.data!;
  }

  /**
   * 发送消息
   */
  async sendMessage(sessionId: string, request: SendMessageRequest): Promise<Message> {
    const response = await this.client.post<RXResponse<Message>>(
      `/api/v1/sessions/${sessionId}/messages`,
      request
    );
    return response.data.data!;
  }

  /**
   * SSE 订阅
   */
  subscribeToStream(
    sessionId: string,
    callbacks: {
      onOpen?: () => void;
      onConnected?: (data: any) => void;
      onEvent?: (agentMessage: AgentMessage) => void;
      onError?: (error: any) => void;
    }
  ): EventSource {
    const url = new URL(
      `/api/v1/sessions/${sessionId}/stream`,
      this.client.defaults.baseURL
    );

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

    // 监听默认 message 事件（连接确认等）
    eventSource.onmessage = (e: any) => {
      try {
        const data = JSON.parse(e.data);
        callbacks.onConnected?.(data);
      } catch (error) {
        console.error('Failed to parse SSE message:', error);
      }
    };

    // 监听命名事件 "event"（AgentMessage）
    eventSource.addEventListener('event', (e: any) => {
      try {
        const agentMessage: AgentMessage = JSON.parse(e.data);
        callbacks.onEvent?.(agentMessage);
      } catch (error) {
        console.error('Failed to parse SSE event:', error);
      }
    });

    return eventSource;
  }

  /**
   * 获取引导卡片
   */
  async getGuideCards(agentId?: string): Promise<GuideCard[]> {
    const response = await this.client.get<RXResponse<GuideCard[]>>(
      '/api/v1/sessions/guide-cards',
      { params: agentId ? { agentId } : {} }
    );
    return response.data.data!;
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
  async waitForReady(maxAttempts: number = 20, interval: number = 1000): Promise<void> {
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
export function createClient(baseURL?: string): SessionClient {
  return new SessionClient(baseURL);
}

/**
 * 创建已认证的客户端实例
 */
export async function createAuthenticatedClient(baseURL?: string): Promise<SessionClient> {
  const client = new SessionClient(baseURL);
  await client.login(TEST_CONFIG.auth.username, TEST_CONFIG.auth.password);
  return client;
}
