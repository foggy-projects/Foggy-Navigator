---
name: coding-agent-integration-tests
description: Coding Agent 集成测试开发指导。当用户需要编写、运行或调试 coding-agent 的集成测试、端到端测试、API 测试时使用。触发词：/ca-tests, /dev-tests, 提及"集成测试"、"端到端测试"、"E2E"、"API测试"。
---

# Coding Agent 集成测试指导

为 `addons/coding-agent/integration-tests` 模块提供集成测试编写、运行、调试的标准化指导。

## 使用场景

当用户需要以下操作时激活：
- 编写新的集成测试用例
- 运行和调试集成测试
- 验证 API 端点功能
- 测试 SSE 事件流
- 测试并发和错误处理场景
- 补充测试覆盖率

## 模块结构

```
addons/coding-agent/integration-tests/
├── src/
│   ├── api-client.ts       # API 客户端封装
│   ├── config.ts           # 测试配置
│   └── types.ts            # 类型定义
├── tests/
│   ├── 01-basic-flow.test.ts           # 基本流程测试
│   ├── 02-sse-events.test.ts           # SSE 事件流测试
│   ├── 03-cleanup-recovery.test.ts     # 清理恢复测试
│   └── 04-error-handling.test.ts       # 错误处理测试
├── package.json
├── vitest.config.ts
└── .env                    # 环境变量
```

## 执行流程

### 1. 环境准备

确保后端服务运行中：

```bash
# 启动后端服务
cd addons/coding-agent && mvn spring-boot:run

# 验证服务可用
curl http://localhost:8112/actuator/health
```

### 2. 编写测试用例

#### 基本测试结构

```typescript
import { describe, test, expect, beforeEach, afterEach } from 'vitest';
import { createClient } from '../src/api-client.js';
import { generateTestUserId, generateTestProjectId, TEST_CONFIG } from '../src/config.js';
import type { Conversation, Event } from '../src/types.js';

describe('场景名称', () => {
  const client = createClient();
  let conversationId: string | null = null;

  const userId = generateTestUserId();
  const projectId = generateTestProjectId();

  // 每个测试后清理资源
  afterEach(async () => {
    if (conversationId && TEST_CONFIG.autoCleanup) {
      try {
        await client.deleteConversation(conversationId);
      } catch (error) {
        console.warn(`Cleanup failed: ${conversationId}`);
      }
      conversationId = null;
    }
  });

  test('测试用例名称', async () => {
    // Given: 准备测试数据
    const createRequest = {
      userId,
      projectId,
      gitRepoUrl: TEST_CONFIG.testRepo.gitRepoUrl,
      branchName: TEST_CONFIG.testRepo.branchName
    };

    // When: 执行操作
    const conversation = await client.createConversation(createRequest);
    conversationId = conversation.conversationId;

    // Then: 验证结果
    expect(conversation.conversationId).toBeTruthy();
    expect(conversation.status).toBe('READY');
  });
});
```

#### SSE 事件测试

```typescript
test('应该通过 SSE 接收事件', async () => {
  const conversation = await client.createConversation(createRequest);
  conversationId = conversation.conversationId;

  const receivedEvents: Event[] = [];

  const eventPromise = new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(() => resolve(), 5000);

    const eventSource = client.subscribeToEvents(conversationId, {
      onEvent: (event) => {
        receivedEvents.push(event);
        if (event.kind === 'MESSAGE_SENT') {
          clearTimeout(timeout);
          eventSource.close();
          resolve();
        }
      },
      onError: (error) => {
        clearTimeout(timeout);
        resolve();
      }
    });

    // 触发事件
    client.sendMessage(conversationId, { content: 'Test' });
  });

  await eventPromise;
  expect(receivedEvents.length).toBeGreaterThan(0);
});
```

#### 并发测试

```typescript
test('应该能够并发发送消息', async () => {
  const conversation = await client.createConversation(createRequest);
  conversationId = conversation.conversationId;

  // 并发发送 10 条消息
  const messagePromises = [];
  for (let i = 0; i < 10; i++) {
    messagePromises.push(
      client.sendMessage(conversationId, {
        content: `Message ${i + 1}`
      }).catch(() => null)
    );
  }

  const results = await Promise.all(messagePromises);
  const successCount = results.filter(r => r !== null).length;

  expect(successCount).toBeGreaterThan(0);
});
```

#### 错误处理测试

```typescript
test('应该正确处理资源不存在错误', async () => {
  const nonExistentId = 'non-existent-' + Date.now();

  let errorCaught = false;
  try {
    await client.getConversation(nonExistentId);
  } catch (error: any) {
    errorCaught = error.response?.status === 404;
  }

  expect(errorCaught).toBe(true);
});
```

### 3. 运行测试

```bash
# 进入测试目录
cd addons/coding-agent/integration-tests

# 运行所有测试
npm test

# 运行指定测试文件
npm test -- tests/01-basic-flow.test.ts

# 运行匹配名称的测试
npm test -- -t "创建 Conversation"

# 观察模式（文件变化时自动重运行）
npm run test:watch
```

### 4. 调试测试

```bash
# 详细输出
npm test -- --reporter=verbose

# 只运行失败的测试
npm test -- --failed

# 显示测试覆盖率
npm test -- --coverage
```

## 约束条件

### 测试命名规范
- 文件名：`{序号}-{功能}.test.ts`（如 `01-basic-flow.test.ts`）
- 测试描述：使用中文，格式 `{序号} - {功能名称} ({英文名})`
- 用例名称：`应该{预期行为}`

### 资源清理
- 每个测试必须在 `afterEach` 中清理创建的资源
- 使用 `TEST_CONFIG.autoCleanup` 控制是否自动清理
- 清理失败不应导致测试失败

### 超时处理
- 默认超时 30 秒
- SSE 连接使用 5-10 秒超时
- 并发操作使用 Promise.all 并捕获错误

### 测试独立性
- 每个测试用例必须独立，不依赖其他测试的执行顺序
- 使用 `generateTestUserId()` 和 `generateTestProjectId()` 生成唯一 ID
- 不共享会话或资源

## 决策规则

- 如果测试需要会话 → 在测试内创建，在 `afterEach` 中清理
- 如果测试涉及 SSE → 使用 Promise + setTimeout 处理超时
- 如果测试涉及并发 → 使用 `Promise.all` 并捕获单个错误
- 如果测试验证错误 → 使用 try-catch 捕获并验证错误类型
- 如果测试依赖服务状态 → 添加适当的等待时间（`await new Promise(r => setTimeout(r, 500))`）
- 如果测试失败率高 → 检查是否是时序问题，增加等待或重试

## API 客户端方法

| 方法 | 描述 | 返回值 |
|-----|------|--------|
| `createConversation(request)` | 创建会话 | `Promise<Conversation>` |
| `getConversation(id)` | 获取会话 | `Promise<Conversation>` |
| `listConversations(params)` | 列出会话 | `Promise<Conversation[]>` |
| `deleteConversation(id)` | 删除会话 | `Promise<void>` |
| `stopConversation(id)` | 停止会话 | `Promise<void>` |
| `sendMessage(id, request)` | 发送消息 | `Promise<Message>` |
| `getMessages(id, limit?)` | 获取消息 | `Promise<Message[]>` |
| `getEvents(id, params?)` | 获取事件 | `Promise<Event[]>` |
| `subscribeToEvents(id, handlers, lastEventId?)` | SSE 订阅 | `EventSource` |

## 常用断言

```typescript
// 基本断言
expect(value).toBeTruthy();
expect(value).toBe(expected);
expect(value).toEqual(expected);  // 深度比较

// 数组
expect(array).toHaveLength(3);
expect(array).toContain(item);
expect(array.some(x => x.id === id)).toBe(true);

// 异常
expect(() => fn()).toThrow();
expect(() => fn()).toThrow('error message');

// 异步
await expect(promise).resolves.toBe(value);
await expect(promise).rejects.toThrow();

// 范围
expect(value).toBeGreaterThan(0);
expect(value).toBeLessThanOrEqual(100);
```

## 参考文件

详细的技术参考请查看：
- [reference.md](./reference.md) - API 客户端和配置参考
- [examples.md](./examples.md) - 完整测试用例示例
