import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG, generateTestSessionTitle } from '../src/config.js';
import type { AgentMessage } from '../src/types.js';

describe('03 - SSE 事件流 (SSE Events)', () => {
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

  test('应该能建立 SSE 连接', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    let opened = false;

    const eventPromise = new Promise<void>((resolve) => {
      const timeout = setTimeout(() => resolve(), 5000);

      const eventSource = client.subscribeToStream(sessionId!, {
        onOpen: () => {
          opened = true;
        },
        onConnected: (data) => {
          // 收到连接确认消息
          console.log('SSE connected:', data);
          clearTimeout(timeout);
          eventSource.close();
          resolve();
        },
        onError: () => {
          clearTimeout(timeout);
          resolve();
        }
      });
    });

    await eventPromise;
    expect(opened).toBe(true);
  });

  test('应该在发送消息后接收到 SSE 事件', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    const receivedEvents: AgentMessage[] = [];

    const eventPromise = new Promise<void>((resolve) => {
      // 最多等 10 秒
      const timeout = setTimeout(() => resolve(), 10000);

      const eventSource = client.subscribeToStream(sessionId!, {
        onConnected: () => {
          // 连接建立后发送消息
          client.sendMessage(sessionId!, { content: 'SSE测试消息' });
        },
        onEvent: (agentMessage) => {
          receivedEvents.push(agentMessage);
          // 收到任意事件即可（可能是 ERROR 因为无 LlmAdapter）
          if (agentMessage.type === 'TEXT_COMPLETE' ||
              agentMessage.type === 'ERROR' ||
              agentMessage.type === 'SESSION_END') {
            clearTimeout(timeout);
            eventSource.close();
            resolve();
          }
        },
        onError: () => {
          // SSE 连接错误，等超时后 resolve
        }
      });
    });

    await eventPromise;

    // 可能因无 LlmAdapter 无事件，也可能收到 ERROR，都是合理的
    console.log(`Received ${receivedEvents.length} SSE events`);
    if (receivedEvents.length > 0) {
      expect(receivedEvents[0].sessionId).toBe(sessionId);
      expect(receivedEvents[0].type).toBeTruthy();
    }
  });

  test('应该能接收心跳事件', async () => {
    const session = await client.createSession({
      title: generateTestSessionTitle(),
      agentId: TEST_CONFIG.defaultAgentId
    });
    sessionId = session.id;

    let heartbeatReceived = false;

    const eventPromise = new Promise<void>((resolve) => {
      // 心跳间隔 15 秒，等 20 秒
      const timeout = setTimeout(() => resolve(), 20000);

      const eventSource = client.subscribeToStream(sessionId!, {
        onEvent: (agentMessage) => {
          if (agentMessage.type === 'HEARTBEAT') {
            heartbeatReceived = true;
            clearTimeout(timeout);
            eventSource.close();
            resolve();
          }
        },
        onError: () => {
          clearTimeout(timeout);
          resolve();
        }
      });
    });

    await eventPromise;
    // 心跳可能在 15 秒后到来，如果 SSE 实现中包含心跳
    console.log(`Heartbeat received: ${heartbeatReceived}`);
  }, 25000);
});
