---
name: coding-agent-integration-tests
description: Coding Agent L3 API 集成测试开发指导（REST + SSE + OpenHands）。当用户需要编写、运行或调试 coding-agent 模块专属的 L3 集成测试时使用。注意：本技能仅覆盖 coding-agent 模块，session-module 请使用 /st。触发词：/ca-tests, 提及"coding-agent 集成测试"、"coding-agent API 测试"。
---

# Coding Agent 集成测试指导

为 `addons/coding-agent/integration-tests` 模块提供 L3 API 集成测试编写、运行、调试的标准化指导。

> **范围限定**：本技能仅覆盖 coding-agent 模块。
> - Session Module 集成测试 → `/st`（session-integration-tests）
> - 浏览器 E2E 测试 → `/test-coding-agent`（coding-agent-e2e-browser）
> - 全局测试规范 → `/tg`（testing-guide）

## 使用场景

当用户明确针对 **coding-agent 模块** 进行以下操作时激活：
- 编写 coding-agent 的 L3 集成测试用例
- 运行和调试 coding-agent 集成测试
- 验证 coding-agent REST API 端点
- 测试 coding-agent SSE 事件流
- 测试 OpenHands 集成场景

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
│   ├── 04-error-handling.test.ts       # 错误处理测试
│   └── 05-e2e-openhands.test.ts        # E2E OpenHands 真实交互测试
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

## E2E OpenHands 测试

### 概述

`05-e2e-openhands.test.ts` 验证完整的 OpenHands 集成流程：创建会话 → 发送编码任务 → agent 执行 → 验证 GitLab 提交。

### 环境变量

| 变量 | 用途 | 必需 |
|------|------|------|
| `SKIP_OPENHANDS_TESTS=true` | 跳过 OH E2E 测试 | 否 |
| `GITLAB_TOKEN=glpat-xxx` | GitLab API Token，用于验证 agent 的 git push | 否（无则跳过验证） |

`GITLAB_TOKEN` 也会被嵌入到 git repo URL 中，让 agent 能够 push：
```
http://root:{GITLAB_TOKEN}@gitlib.foggysource.com/test/coding-test.git
```

### 测试结构

```typescript
test('完整流程', async () => {
  // Step 1: 创建 Conversation（启动 OH sandbox）
  const conversation = await client.createConversation({
    userId, projectId,
    gitRepoUrl: GIT_REPO_URL,  // 含 token 的 URL
    branchName: 'main',
  });

  // Step 2: 等待会话 READY（最多 120 秒）
  await pollUntilStatus(client, conversationId, ['READY', 'RUNNING'], 120_000);

  // Step 3: 发送编码任务
  await client.sendMessage(conversationId, { content: taskContent });

  // Step 4: 轮询事件直到 Agent 完成（最多 240 秒）
  const events = await pollForAgentCompletion(client, conversationId, 240_000);

  // Step 5: 通过 GitLab API 验证文件已提交
  const verified = await verifyGitLabBranch(GITLAB_URL, GITLAB_TOKEN, ...);
  expect(verified.branchExists).toBe(true);
  expect(verified.fileContent).toContain('Hello World');
}, 300_000);
```

### 关键 Helper 函数

#### `pollUntilStatus` - 等待会话状态

每 2 秒查询会话状态，直到匹配目标状态或超时。遇到 ERROR/STOPPED 立即返回 false。

```typescript
async function pollUntilStatus(
  client, conversationId, targetStatuses: string[], timeoutMs: number
): Promise<boolean>
```

#### `pollForAgentCompletion` - 等待 Agent 完成

每 2 秒查询事件列表，累积新事件。终止条件：
- `CONVERSATION_STATUS` 事件的 `data.status === 'IDLE'`
- `ERROR` 事件（非 ValidationService 来源）
- 连续 60 秒无新事件（`stableCount > 30`，每次 2 秒）

```typescript
async function pollForAgentCompletion(
  client, conversationId, timeoutMs: number
): Promise<Event[]>
```

#### `verifyGitLabBranch` - GitLab 验证

通过 GitLab REST API 验证分支和文件是否存在。带 3 次重试（git push 可能有延迟）。

### 超时调优经验

| 参数 | 推荐值 | 原因 |
|------|--------|------|
| 会话创建等待 | 120s | OH 容器启动 + agent server 初始化 |
| Agent 完成等待 | 240s | 每次 LLM 调用约 30-50 秒，多步任务需要多次调用 |
| 无新事件超时 | 60s (`stableCount > 30`) | LLM 单次调用可能需要 40+ 秒，20s 太短会误判 |
| 测试总超时 | 300s (5 分钟) | 包含容器启动 + agent 执行 + GitLab 验证 |
| GitLab push 生效等待 | 3s + 3 次重试（每次 5s） | git push 到 GitLab 有传播延迟 |

### 常见问题

**Q: Agent 只执行了 1 步就超时？**
A: DashScope 等 LLM 每次调用 30-50 秒。确保 `stableCount` 阈值足够大（推荐 30 即 60s）。

**Q: 容器启动失败，端口绑定错误？**
A: Windows Hyper-V 会保留某些端口范围。属于偶发问题，重试通常能解决。

**Q: GitLab 验证找不到分支？**
A: 确保 `GITLAB_TOKEN` 有写权限，并且嵌入到了 git repo URL 中。agent 需要 push 权限。

**Q: ValidationService 报错但测试没失败？**
A: 这是预期行为。E2E 测试中 ValidationService 可能不可用，其错误被标记为非终止事件。

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
