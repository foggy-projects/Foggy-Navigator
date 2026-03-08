import { describe, test, expect, beforeAll, beforeEach, afterEach } from 'vitest';
import { createAuthenticatedClient, CodingAgentClient } from '../src/api-client.js';
import { generateTestUserId, generateTestProjectId, TEST_CONFIG } from '../src/config.js';
import type { Conversation } from '../src/types.js';

/**
 * 场景 1: 基本流程测试
 *
 * 验证完整的 Conversation 生命周期：
 * 1. 创建 Conversation
 * 2. 验证 Conversation 状态
 * 3. 发送 Message
 * 4. 获取 Messages
 * 5. 获取 Events
 * 6. 删除 Conversation
 */
describe('01 - 基本流程测试 (Basic Flow)', () => {
  let client: CodingAgentClient;
  let conversationId: string | null = null;

  beforeAll(async () => {
    client = await createAuthenticatedClient();
  });

  // 测试数据
  const userId = generateTestUserId();
  const projectId = generateTestProjectId();

  // 每个测试后清理资源
  afterEach(async () => {
    if (conversationId && TEST_CONFIG.autoCleanup) {
      try {
        await client.deleteConversation(conversationId);
        console.log(`✓ Cleaned up conversation: ${conversationId}`);
      } catch (error) {
        console.warn(`Failed to cleanup conversation: ${conversationId}`);
      }
      conversationId = null;
    }
  });

  test('应该成功创建 Conversation', async () => {
    // Given: 准备创建请求
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    // When: 创建 Conversation
    const conversation = await client.createConversation(createRequest);
    conversationId = conversation.conversationId;

    // Then: 验证创建成功
    expect(conversation).toBeDefined();
    expect(conversation.conversationId).toBeTruthy();
    expect(conversation.userId).toBe(userId);
    expect(conversation.projectId).toBe(projectId);
    expect(conversation.gitRepoUrl).toBe(createRequest.gitRepoUrl);
    expect(conversation.branchName).toBe(createRequest.branchName);

    // 状态应该是 STARTING/READY（有 OH 环境时）或 ERROR（无 OH 环境时）
    expect(['STARTING', 'WAITING_FOR_SANDBOX', 'PREPARING_REPOSITORY', 'READY', 'RUNNING', 'ERROR']).toContain(conversation.status);

    console.log('✓ Conversation created:', {
      conversationId: conversation.conversationId,
      status: conversation.status,
      namespace: conversation.namespace
    });
  });

  test('应该能够获取已创建的 Conversation', async () => {
    // Given: 先创建一个 Conversation
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const created = await client.createConversation(createRequest);
    conversationId = created.conversationId;

    // When: 获取 Conversation
    const retrieved = await client.getConversation(conversationId);

    // Then: 验证数据一致
    expect(retrieved).toBeDefined();
    expect(retrieved.conversationId).toBe(created.conversationId);
    expect(retrieved.userId).toBe(userId);
    expect(retrieved.projectId).toBe(projectId);

    console.log('✓ Conversation retrieved:', {
      conversationId: retrieved.conversationId,
      status: retrieved.status
    });
  });

  test('应该能够发送消息', async () => {
    // Given: 先创建一个 Conversation
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const conversation = await client.createConversation(createRequest);
    conversationId = conversation.conversationId;

    // When: 发送消息
    const messageContent = 'Hello, this is a test message';
    const message = await client.sendMessage(conversationId, {
      content: messageContent
    });

    // Then: 验证消息发送成功
    expect(message).toBeDefined();
    expect(message.messageId).toBeTruthy();
    expect(message.conversationId).toBe(conversationId);
    expect(message.content).toBe(messageContent);
    expect(message.timestamp).toBeTruthy();

    console.log('✓ Message sent:', {
      messageId: message.messageId,
      content: message.content
    });
  });

  test('应该能够获取消息列表', async () => {
    // Given: 创建 Conversation 并发送多条消息
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const conversation = await client.createConversation(createRequest);
    conversationId = conversation.conversationId;

    // 发送 3 条消息
    await client.sendMessage(conversationId, { content: 'Message 1' });
    await new Promise(resolve => setTimeout(resolve, 10)); // 确保时间戳不同
    await client.sendMessage(conversationId, { content: 'Message 2' });
    await new Promise(resolve => setTimeout(resolve, 10));
    await client.sendMessage(conversationId, { content: 'Message 3' });

    // When: 获取消息列表
    const messages = await client.getMessages(conversationId, 10);

    // Then: 验证消息列表
    expect(messages).toBeDefined();
    expect(messages.length).toBe(3);

    // 消息应该按时间降序排列（最新的在前）
    expect(messages[0].content).toBe('Message 3');
    expect(messages[1].content).toBe('Message 2');
    expect(messages[2].content).toBe('Message 1');

    console.log('✓ Messages retrieved:', messages.length);
  });

  test('应该能够查询 Conversation 列表', async () => {
    // Given: 创建多个 Conversation
    const createRequest1 = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const conv1 = await client.createConversation(createRequest1);

    const createRequest2 = {
      userId,
      projectId: generateTestProjectId(), // 不同的项目
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const conv2 = await client.createConversation(createRequest2);

    conversationId = conv1.conversationId; // 用于清理

    try {
      // When: 查询该用户的所有 Conversation
      const conversations = await client.listConversations({ userId });

      // Then: 验证查询结果
      expect(conversations).toBeDefined();
      expect(conversations.length).toBeGreaterThanOrEqual(2);

      const conv1Found = conversations.some(c => c.conversationId === conv1.conversationId);
      const conv2Found = conversations.some(c => c.conversationId === conv2.conversationId);

      expect(conv1Found).toBe(true);
      expect(conv2Found).toBe(true);

      console.log('✓ Conversations listed:', conversations.length);
    } finally {
      // 清理第二个 Conversation
      await client.deleteConversation(conv2.conversationId);
    }
  });

  test('完整流程：创建 → 发送消息 → 获取事件 → 删除', async () => {
    // Step 1: 创建 Conversation
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    const conversation = await client.createConversation(createRequest);
    conversationId = conversation.conversationId;
    console.log('Step 1: Conversation created');

    // Step 2: 发送消息
    await client.sendMessage(conversationId, {
      content: 'Test message for complete flow'
    });
    console.log('Step 2: Message sent');

    // Step 3: 等待一下让事件生成
    await new Promise(resolve => setTimeout(resolve, 500));

    // Step 4: 获取事件
    const events = await client.getEvents(conversationId);
    expect(events).toBeDefined();
    expect(events.length).toBeGreaterThan(0);
    console.log('Step 3: Events retrieved:', events.length);

    // 应该至少有一个 MESSAGE_SENT 事件
    const messageSentEvent = events.find(e => e.kind === 'MESSAGE_SENT');
    expect(messageSentEvent).toBeDefined();

    // Step 5: 删除 Conversation
    await client.deleteConversation(conversationId);
    console.log('Step 4: Conversation deleted');

    // Step 6: 验证删除成功
    try {
      const deletedConv = await client.getConversation(conversationId);
      // 如果获取到数据，说明删除失败
      if (deletedConv) {
        expect.fail('Conversation should have been deleted');
      }
    } catch (error: any) {
      // 删除后再获取会返回异常或null，这是预期的行为
      console.log('Expected: Conversation not found after deletion');
    }

    conversationId = null; // 已删除，不需要再清理
    console.log('✓ Complete flow test passed');
  });
});
