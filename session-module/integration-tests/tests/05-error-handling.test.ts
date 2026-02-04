import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, createClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG, generateTestSessionTitle } from '../src/config.js';

describe('05 - 错误处理 (Error Handling)', () => {
  let client: SessionClient;
  let sessionId: string | null = null;

  beforeAll(async () => {
    client = await createAuthenticatedClient();
  });

  afterEach(async () => {
    if (sessionId && TEST_CONFIG.autoCleanup) {
      try {
        await client.deleteSession(sessionId);
      } catch (error) {
        console.warn(`Cleanup failed: ${sessionId}`);
      }
      sessionId = null;
    }
  });

  test('获取不存在的会话应返回 400', async () => {
    try {
      await client.getSession('non-existent-id-' + Date.now());
      expect.fail('Should have thrown');
    } catch (error: any) {
      expect(error.response?.status).toBe(400);
    }
  });

  test('向不存在的会话发送消息应返回 400', async () => {
    try {
      await client.sendMessage('non-existent-id-' + Date.now(), {
        content: 'hello'
      });
      expect.fail('Should have thrown');
    } catch (error: any) {
      expect(error.response?.status).toBe(400);
    }
  });

  test('创建会话时空 agentId 应能处理', async () => {
    try {
      const session = await client.createSession({
        title: generateTestSessionTitle(),
        agentId: ''
      });
      // 服务端允许空 agentId 时，清理资源
      if (session?.id) {
        sessionId = session.id;
      }
      // 空 agentId 被接受也是合理的（由业务层决定）
      expect(session.id).toBeTruthy();
    } catch (error: any) {
      // 如果拒绝，应该是 4xx 错误
      expect(error.response?.status).toBeGreaterThanOrEqual(400);
    }
  });

  test('未认证访问应返回 401', async () => {
    const unauthClient = createClient();

    try {
      await unauthClient.listSessions();
      expect.fail('Should have thrown');
    } catch (error: any) {
      expect(error.response?.status).toBe(401);
    }
  });

  test('删除不存在的会话不应抛出 500 错误', async () => {
    try {
      await client.deleteSession('non-existent-id-' + Date.now());
      // 有些实现对不存在的 ID 删除是幂等的，不报错
    } catch (error: any) {
      // 如果报错，应该是 4xx 而不是 5xx
      expect(error.response?.status).toBeLessThan(500);
    }
  });

  test('发送空内容消息应正常处理', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    try {
      const message = await client.sendMessage(sessionId, { content: '' });
      // 如果允许空消息，验证基本结构
      expect(message.role).toBe('USER');
    } catch (error: any) {
      // 如果不允许空消息，应该是 4xx 错误
      expect(error.response?.status).toBeGreaterThanOrEqual(400);
      expect(error.response?.status).toBeLessThan(500);
    }
  });
});
