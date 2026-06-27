---
name: session-integration-tests
description: Session Module L3 API 集成测试指导（会话 CRUD + SSE + Agent 委派）。当用户需要编写、运行或调试 session-module 模块专属的 L3 集成测试时使用。注意：本技能仅覆盖 session-module，coding-agent 请使用 /ca-tests。触发词：/session-tests, /st, 提及"session 集成测试"、"会话 API 测试"。
---

# Session Module 集成测试指导

为 session-module 的 REST API 和 SSE 事件流提供 L3 集成测试标准化指导。

> **范围限定**：本技能仅覆盖 session-module 模块。
> - Coding Agent 集成测试 → `/ca-tests`（coding-agent-integration-tests）
> - 全局测试规范 → `/tg`（testing-guide）

## 使用场景

当用户明确针对 **session-module 模块** 进行以下操作时激活：
- 编写 session-module 的 L3 集成测试用例
- 运行和调试 session-module 集成测试
- 验证会话 CRUD API
- 测试 SSE 事件流推送
- 测试消息发送与 Agent 异步调用链
- 测试错误处理和边界场景

## 模块结构

```
session-module/integration-tests/
├── src/
│   ├── api-client.ts       # Session API 客户端封装
│   ├── config.ts           # 测试配置
│   └── types.ts            # 类型定义
├── tests/
│   ├── setup.ts                        # 全局设置（健康检查+登录）
│   ├── 01-session-crud.test.ts         # 会话 CRUD 测试
│   ├── 02-message-flow.test.ts         # 消息收发测试
│   ├── 03-sse-events.test.ts           # SSE 事件流测试
│   ├── 04-guide-cards.test.ts          # 引导卡片测试
│   └── 05-error-handling.test.ts       # 错误处理测试
├── package.json
├── vitest.config.ts
├── tsconfig.json
└── .env
```

## 关键差异（与 coding-agent 集成测试对比）

| 项目 | coding-agent | session-module |
|------|-------------|----------------|
| API 路径 | `/api/v1/conversations` | `/api/v1/sessions` |
| 响应格式 | 裸数据 `response.data` | RX 包装 `response.data.data` |
| SSE 端点 | `/api/v1/conversations/{id}/events/stream` | `/api/v1/sessions/{id}/stream` |
| SSE 事件名 | 按类型命名（`CONVERSATION_STATUS` 等） | 统一命名 `event` |
| SSE 事件体 | `Event` 对象 | `AgentMessage` JSON |
| 会话创建 | 需要 gitRepoUrl 等 | 只需 title + agentId |
| Agent 依赖 | OpenHands Docker 容器 | AgentInvoker（可能无 LlmAdapter） |
| 认证 | 相同（JWT via `/api/v1/auth/login`） | 相同 |

## 执行流程

### 1. 环境准备

```bash
# 确保后端服务运行中
curl http://localhost:8112/actuator/health

# 如果未启动：
powershell -ExecutionPolicy Bypass -File start-launcher.ps1
```

### 2. 初始化测试项目

```bash
cd session-module/integration-tests
npm install
```

### 3. 运行测试

```bash
# 运行所有测试
npm test

# 运行指定测试文件
npm test -- tests/01-session-crud.test.ts

# 运行匹配名称的测试
npm test -- -t "创建会话"

# 观察模式
npm run test:watch

# 详细输出
npm test -- --reporter=verbose
```

## API 客户端设计

### RX 响应格式

session-module 使用 `RX<T>` 统一返回，响应体结构：

```typescript
interface RXResponse<T> {
  code: number;    // 0=成功
  msg?: string;    // 错误信息
  data?: T;        // 业务数据
}
```

客户端需要从 `response.data.data` 提取业务数据：

```typescript
async createSession(request: CreateSessionRequest): Promise<Session> {
  const response = await this.client.post<RXResponse<Session>>(
    '/api/v1/sessions',
    request
  );
  return response.data.data!;
}
```

### SSE 事件监听

session-module 使用 `.name("event")` 统一命名事件，客户端监听方式：

```typescript
// 监听命名事件 "event"
eventSource.addEventListener('event', (e: MessageEvent) => {
  const agentMessage = JSON.parse(e.data);
  // agentMessage 结构：AgentMessage { messageId, sessionId, agentId, type, payload, ... }
  callbacks.onEvent?.(agentMessage);
});

// 也监听默认 message 事件（连接确认等）
eventSource.onmessage = (e: MessageEvent) => {
  const data = JSON.parse(e.data);
  // { type: "connected", sessionId: "..." }
};
```

### AgentMessage 类型

SSE 推送的事件体是 `AgentMessage`：

```typescript
interface AgentMessage {
  messageId: string;
  sessionId: string;
  agentId: string;
  timestamp: number;
  version: string;
  type: MessageType;    // TEXT_CHUNK | TEXT_COMPLETE | ERROR | TOOL_CALL_START | ...
  payload: any;         // 类型相关的数据
}

type MessageType =
  | 'TEXT_CHUNK'            // 流式文本片段
  | 'TEXT_COMPLETE'         // 文本完成
  | 'TOOL_CALL_START'       // 工具调用开始
  | 'TOOL_CALL_RESULT'      // 工具调用结果
  | 'TOOL_CALL_ERROR'       // 工具调用错误
  | 'ERROR'                 // 错误
  | 'SESSION_START'         // 会话开始
  | 'SESSION_END'           // 会话结束
  | 'HEARTBEAT';            // 心跳
```

## 测试模式

### 基本 CRUD 测试

```typescript
import { describe, test, expect, beforeAll, afterEach } from 'vitest';
import { createAuthenticatedClient, SessionClient } from '../src/api-client.js';
import { TEST_CONFIG } from '../src/config.js';

describe('01 - 会话 CRUD 测试', () => {
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
    const session = await client.createSession({
      title: '测试会话',
      agentId: 'tutor-agent'
    });
    sessionId = session.id;

    expect(session.id).toBeTruthy();
    expect(session.status).toBe('ACTIVE');
    expect(session.agentId).toBe('tutor-agent');
  });
});
```

### 消息流测试

```typescript
test('应该成功发送消息并持久化', async () => {
  // 创建会话
  const session = await client.createSession({
    title: '消息测试', agentId: 'tutor-agent'
  });
  sessionId = session.id;

  // 发送消息（会触发 AgentInvoker，可能因无 LlmAdapter 而报错，但用户消息应已持久化）
  const message = await client.sendMessage(sessionId, {
    content: '你好，请帮我分析数据'
  });

  expect(message.id).toBeTruthy();
  expect(message.role).toBe('USER');
  expect(message.content).toBe('你好，请帮我分析数据');

  // 验证消息已持久化
  const messages = await client.getMessages(sessionId);
  expect(messages.length).toBeGreaterThanOrEqual(1);
  expect(messages.some(m => m.content === '你好，请帮我分析数据')).toBe(true);
});
```

### SSE 事件流测试

```typescript
test('应该能建立 SSE 连接并接收心跳', async () => {
  const session = await client.createSession({
    title: 'SSE测试', agentId: 'tutor-agent'
  });
  sessionId = session.id;

  let connected = false;

  const eventPromise = new Promise<void>((resolve) => {
    const timeout = setTimeout(() => resolve(), 5000);

    const eventSource = client.subscribeToStream(sessionId!, {
      onOpen: () => {
        connected = true;
      },
      onConnected: (data) => {
        // 收到 { type: "connected", sessionId: "..." }
        expect(data.sessionId).toBe(sessionId);
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
  expect(connected).toBe(true);
});
```

### 错误处理测试

```typescript
test('获取不存在的会话应返回错误', async () => {
  try {
    await client.getSession('non-existent-id');
    expect.fail('Should have thrown');
  } catch (error: any) {
    // RX.throwB 返回的错误
    expect(error.response?.status).toBeGreaterThanOrEqual(400);
  }
});

test('向不存在的会话发送消息应返回错误', async () => {
  try {
    await client.sendMessage('non-existent-id', { content: 'hello' });
    expect.fail('Should have thrown');
  } catch (error: any) {
    expect(error.response?.status).toBeGreaterThanOrEqual(400);
  }
});
```

### 引导卡片测试

```typescript
test('应该返回 tutor-agent 的引导卡片', async () => {
  const cards = await client.getGuideCards('tutor-agent');

  expect(cards).toBeDefined();
  expect(cards.length).toBe(3);
  expect(cards[0].title).toBe('数据查询');
});

test('应该返回默认引导卡片', async () => {
  const cards = await client.getGuideCards();

  expect(cards).toBeDefined();
  expect(cards.length).toBe(2);
});
```

## API 客户端方法清单

| 方法 | 描述 | 返回值 |
|------|------|--------|
| `login(username, password)` | JWT 登录 | `Promise<void>` |
| `createSession(request)` | 创建会话 | `Promise<Session>` |
| `getSession(id)` | 获取会话 | `Promise<Session>` |
| `listSessions(agentId?)` | 列出会话 | `Promise<Session[]>` |
| `deleteSession(id)` | 删除会话 | `Promise<void>` |
| `getMessages(sessionId)` | 获取消息列表 | `Promise<Message[]>` |
| `sendMessage(sessionId, request)` | 发送消息 | `Promise<Message>` |
| `subscribeToStream(sessionId, handlers)` | SSE 订阅 | `EventSource` |
| `getGuideCards(agentId?)` | 获取引导卡片 | `Promise<GuideCard[]>` |
| `healthCheck()` | 健康检查 | `Promise<boolean>` |

## 类型定义

```typescript
interface CreateSessionRequest {
  title?: string;
  agentId: string;
  parentSessionId?: string;
}

interface Session {
  id: string;
  userId: string;
  tenantId: string;
  agentId: string;
  parentSessionId?: string;
  status: 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'DELEGATED';
  taskName?: string;
  createdAt: string;
  updatedAt: string;
}

interface SendMessageRequest {
  content: string;
}

interface Message {
  id: string;
  sessionId: string;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';
  content: string;
  metadata?: Record<string, any>;
  createdAt: string;
}

interface GuideCard {
  title: string;
  description: string;
  icon: string;
}
```

## 约束条件

### 测试命名
- 文件名：`{序号}-{功能}.test.ts`
- 描述：`{序号} - {功能名称} ({英文名})`
- 用例：`应该{预期行为}`

### 资源清理
- 每个测试在 `afterEach` 中清理创建的会话
- 清理失败不应导致测试失败

### Agent 调用
- `POST /sessions/{id}/messages` 会触发 `AgentInvoker.invokeAsync()`
- 如果没有可用的 `LlmAdapter`，Agent 调用会失败并通过 SSE 推送 ERROR 事件
- 用户消息本身的持久化不受影响
- 测试中验证用户消息持久化即可，不强制要求 Agent 响应成功

### 超时
- 默认测试超时：30 秒
- SSE 连接测试：5-10 秒超时
- 健康检查等待：最多 20 秒

## 决策规则

- 如果测试需要会话 → 在测试内创建，在 `afterEach` 中清理
- 如果测试涉及 SSE → 使用 Promise + setTimeout，监听 `event` 命名事件
- 如果测试验证 Agent 响应 → 预期可能收到 ERROR（无 LlmAdapter 环境），做 best-effort 断言
- 如果测试验证错误码 → 用 try-catch 捕获，检查 `error.response.status`
- 如果新增 API → 先在 `api-client.ts` 添加方法，再写测试用例
- 如果修改 RX 响应结构 → 更新 `api-client.ts` 中的数据提取逻辑

## 常用命令

```bash
# 安装依赖
cd session-module/integration-tests && npm install

# 运行所有测试
npm test

# 运行指定文件
npm test -- tests/01-session-crud.test.ts

# 运行匹配的测试
npm test -- -t "创建会话"

# 观察模式
npm run test:watch

# 详细输出
npm test -- --reporter=verbose
```
