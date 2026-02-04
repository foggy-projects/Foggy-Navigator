import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG, generateTestSessionTitle } from '../src/config.js';

describe('01 - 会话 CRUD (Session CRUD)', () => {
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

  test('应该成功创建会话', async () => {
    const title = generateTestSessionTitle();
    const session = await client.createSession({
      title,
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    expect(session.id).toBeTruthy();
    expect(session.status).toBe('ACTIVE');
    expect(session.agentId).toBe(TEST_CONFIG.defaultAgentId);
    expect(session.userId).toBeTruthy();
    // tenantId may be null depending on user configuration
    expect(session.createdAt).toBeTruthy();
  });

  test('应该成功获取会话详情', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const fetched = await client.getSession(sessionId);
    expect(fetched.id).toBe(sessionId);
    expect(fetched.agentId).toBe(TEST_CONFIG.defaultAgentId);
    expect(fetched.status).toBe('ACTIVE');
  });

  test('应该成功列出用户会话', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const sessions = await client.listSessions();
    expect(sessions).toBeDefined();
    expect(Array.isArray(sessions)).toBe(true);
    expect(sessions.some(s => s.id === sessionId)).toBe(true);
  });

  test('应该按 agentId 筛选会话', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const sessions = await client.listSessions(TEST_CONFIG.defaultAgentId);
    expect(sessions).toBeDefined();
    expect(sessions.every(s => s.agentId === TEST_CONFIG.defaultAgentId)).toBe(true);
    expect(sessions.some(s => s.id === sessionId)).toBe(true);
  });

  test('应该成功删除会话', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    const deleteId = session.id;

    await client.deleteSession(deleteId);
    sessionId = null; // 已手动删除，跳过 afterEach 清理

    // 验证已删除：获取应报错
    try {
      await client.getSession(deleteId);
      expect.fail('Should have thrown');
    } catch (error: any) {
      expect(error.response?.status).toBeGreaterThanOrEqual(400);
    }
  });

  test('应该支持带 parentSessionId 创建子会话', async () => {
    // 先创建父会话
    const parent = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    const parentId = parent.id;

    // 创建子会话
    const child = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId,
      parentSessionId: parentId
    });
    sessionId = child.id;

    expect(child.parentSessionId).toBe(parentId);

    // 清理父会话
    try {
      await client.deleteSession(parentId);
    } catch (e) {
      // best effort
    }
  });
});
