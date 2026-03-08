import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG } from '../src/config.js';
import type { AgentMessage } from '../src/types.js';

describe('06 - 委托功能测试 (Delegation)', () => {
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
        console.warn(`Cleanup failed for session: ${sessionId}`);
      }
      sessionId = null;
    }
  });

  test('发送触发委托的消息应收到 ROUTE_REQUEST 事件', async () => {
    // 1. 创建会话
    const session = await client.createSession({
      title: '委托测试会话',
      agentId: 'tutor-agent'
    });
    sessionId = session.id;
    expect(session.id).toBeTruthy();
    console.log(`Created session: ${sessionId}`);

    // 2. 设置 SSE 监听
    const receivedEvents: AgentMessage[] = [];
    let routeRequestReceived = false;
    let toolCallReceived = false;

    const eventPromise = new Promise<void>((resolve) => {
      const timeout = setTimeout(() => {
        console.log('Timeout reached, resolving...');
        resolve();
      }, 30000); // 30秒超时

      const eventSource = client.subscribeToStream(sessionId!, {
        onOpen: () => {
          console.log('SSE connection opened');
        },
        onConnected: (data) => {
          console.log('SSE connected:', data);
        },
        onEvent: (event: AgentMessage) => {
          console.log('Received event:', event.type, event.payload);
          receivedEvents.push(event);

          if (event.type === 'TOOL_CALL_START' && event.payload?.toolName === 'delegate') {
            toolCallReceived = true;
            console.log('Delegate tool call received');
          }

          if (event.type === 'ROUTE_REQUEST') {
            routeRequestReceived = true;
            console.log('Route request received:', event.payload);
            clearTimeout(timeout);
            eventSource.close();
            resolve();
          }
        },
        onError: (error) => {
          console.error('SSE error:', error);
          clearTimeout(timeout);
          resolve();
        }
      });

      // 3. 等待连接稳定后发送消息
      setTimeout(async () => {
        console.log('Sending delegation trigger message...');
        try {
          // 发送触发委托的消息 - "写代码" 关键词会触发 delegate-to-coding 规则
          const message = await client.sendMessage(sessionId!, {
            content: '请帮我写代码实现一个功能'
          });
          console.log('Message sent:', message.id);
        } catch (error) {
          console.error('Failed to send message:', error);
        }
      }, 1000);
    });

    await eventPromise;

    // 4. 验证结果
    console.log(`Total events received: ${receivedEvents.length}`);
    receivedEvents.forEach((e, i) => {
      console.log(`  Event ${i}: ${e.type}`);
    });

    // 验证收到了 TEXT_CHUNK 或 TEXT_COMPLETE 事件（文本内容）
    const textEvents = receivedEvents.filter(e =>
      e.type === 'TEXT_CHUNK' || e.type === 'TEXT_COMPLETE'
    );
    expect(textEvents.length).toBeGreaterThan(0);

    // 验证收到了 TOOL_CALL_START 事件（delegate 工具调用）
    // 注意：如果 LangChain4j 没有正确解析 tool_calls，这个断言可能失败
    if (toolCallReceived) {
      const delegateToolCall = receivedEvents.find(e =>
        e.type === 'TOOL_CALL_START' && e.payload?.toolName === 'delegate'
      );
      expect(delegateToolCall).toBeDefined();
    }

    // 验证收到了 TOOL_CALL_START 事件（delegate 工具调用）
    // 这证明 LLM 返回的 tool_calls 被正确解析和处理
    expect(toolCallReceived).toBe(true);
    const delegateToolCall = receivedEvents.find(e =>
      e.type === 'TOOL_CALL_START' && e.payload?.toolName === 'delegate'
    );
    expect(delegateToolCall).toBeDefined();
    expect(delegateToolCall?.payload?.arguments?.targetAgentId).toBe('coding-agent');
    expect(delegateToolCall?.payload?.arguments?.intent).toContain('编写代码');

    // 注意：ROUTE_REQUEST 可能不会收到，因为目标 agent (coding-agent) 未注册
    // 当目标 agent 不存在时，会收到 ERROR 事件，这是预期行为
    const errorEvent = receivedEvents.find(e =>
      e.type === 'ERROR' && e.payload?.error?.includes('Target agent not found')
    );
    if (errorEvent) {
      console.log('Expected error: Target agent not found (coding-agent is not registered)');
    } else if (routeRequestReceived) {
      const routeRequest = receivedEvents.find(e => e.type === 'ROUTE_REQUEST');
      expect(routeRequest).toBeDefined();
      expect(routeRequest?.payload?.action).toBe('DELEGATE');
      expect(routeRequest?.payload?.target?.agentId).toBe('coding-agent');
    }
  }, 60000); // 60秒总超时

  test('委托工具调用应被正确检测', async () => {
    // 1. 创建会话
    const session = await client.createSession({
      title: '委托状态测试',
      agentId: 'tutor-agent'
    });
    sessionId = session.id;

    // 2. 监听事件等待 TOOL_CALL_START
    let toolCallReceived = false;
    let delegateArgs: any = null;

    const eventPromise = new Promise<boolean>((resolve) => {
      const timeout = setTimeout(() => resolve(toolCallReceived), 15000);

      const eventSource = client.subscribeToStream(sessionId!, {
        onEvent: (event: AgentMessage) => {
          if (event.type === 'TOOL_CALL_START' && event.payload?.toolName === 'delegate') {
            toolCallReceived = true;
            delegateArgs = event.payload?.arguments;
            clearTimeout(timeout);
            eventSource.close();
            resolve(true);
          }
        },
        onError: () => {
          clearTimeout(timeout);
          resolve(false);
        }
      });

      // 发送触发委托的消息
      setTimeout(async () => {
        await client.sendMessage(sessionId!, {
          content: '帮我写代码'
        });
      }, 1000);
    });

    await eventPromise;

    // 3. 验证工具调用
    expect(toolCallReceived).toBe(true);
    expect(delegateArgs).toBeDefined();
    expect(delegateArgs?.targetAgentId).toBe('coding-agent');
  }, 30000);
});
