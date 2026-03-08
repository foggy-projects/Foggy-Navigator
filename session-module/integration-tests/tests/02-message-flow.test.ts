import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG, generateTestSessionTitle } from '../src/config.js';

describe('02 - 消息收发 (Message Flow)', () => {
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

  test('应该成功发送消息并返回用户消息', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const message = await client.sendMessage(sessionId, {
      content: '你好，请帮我分析数据'
    });

    expect(message.id).toBeTruthy();
    expect(message.role).toBe('USER');
    expect(message.content).toBe('你好，请帮我分析数据');
    expect(message.sessionId).toBe(sessionId);
  });

  test('应该持久化用户消息', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    await client.sendMessage(sessionId, {
      content: '第一条消息'
    });

    // 等一下确保持久化完成
    await new Promise(r => setTimeout(r, 500));

    const messages = await client.getMessages(sessionId);
    expect(messages.length).toBeGreaterThanOrEqual(1);
    expect(messages.some(m => m.content === '第一条消息')).toBe(true);
  });

  test('应该支持发送多条消息', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    await client.sendMessage(sessionId, { content: '消息1' });
    await client.sendMessage(sessionId, { content: '消息2' });
    await client.sendMessage(sessionId, { content: '消息3' });

    await new Promise(r => setTimeout(r, 500));

    const messages = await client.getMessages(sessionId);
    const userMessages = messages.filter(m => m.role === 'USER');
    expect(userMessages.length).toBeGreaterThanOrEqual(3);
  });

  test('新建会话的消息列表应该为空', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const messages = await client.getMessages(sessionId);
    expect(messages).toBeDefined();
    expect(Array.isArray(messages)).toBe(true);
    expect(messages.length).toBe(0);
  });
});
