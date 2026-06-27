# Coding Agent 集成测试技术参考

## 测试配置

### config.ts

```typescript
export const TEST_CONFIG = {
  // API 基础 URL
  baseURL: process.env.API_BASE_URL || 'http://localhost:8112',

  // 超时配置
  timeout: 30000,
  retries: 2,

  // 测试标志
  skipDockerTests: process.env.SKIP_DOCKER_TESTS === 'true',
  skipOpenHandsTests: process.env.SKIP_OPENHANDS_TESTS === 'true',

  // 资源清理
  autoCleanup: true,
  cleanupTimeout: 5000,

  // 测试数据
  testRepo: {
    gitRepoUrl: 'https://github.com/test/semantic-layer.git',
    branchName: 'main'
  }
} as const;
```

### 环境变量 (.env)

```bash
API_BASE_URL=http://localhost:8112
SKIP_DOCKER_TESTS=false
SKIP_OPENHANDS_TESTS=false
```

## API 类型定义

### 请求类型

```typescript
interface CreateConversationRequest {
  userId: string;
  projectId: string;
  gitRepoUrl: string;
  branchName: string;
  gitCredentials?: {
    username: string;
    token: string;
  };
  initialMessage?: string;
}

interface SendMessageRequest {
  content: string;
}
```

### 响应类型

```typescript
interface Conversation {
  conversationId: string;
  sandboxId?: string;
  userId: string;
  projectId: string;
  status: ConversationStatus;
  namespace?: string;
  gitRepoUrl: string;
  branchName: string;
  createdAt: string;
  updatedAt?: string;
}

type ConversationStatus =
  | 'STARTING'
  | 'READY'
  | 'RUNNING'
  | 'IDLE'
  | 'ERROR'
  | 'STOPPED';

interface Message {
  messageId: string;
  conversationId: string;
  content: string;
  timestamp: string;
}

interface Event {
  id: string;
  conversationId: string;
  kind: EventKind;
  timestamp: string;
  data: Record<string, any>;
}

type EventKind =
  | 'CONVERSATION_STATUS'
  | 'MESSAGE_SENT'
  | 'AGENT_ACTION'
  | 'AGENT_OBSERVATION'
  | 'VALIDATION_TRIGGERED'
  | 'VALIDATION_RESULT'
  | 'ERROR';
```

## API 客户端实现

### 核心方法

```typescript
export function createClient() {
  const axios = createAxiosInstance();

  return {
    // 会话管理
    async createConversation(request: CreateConversationRequest): Promise<Conversation> {
      const response = await axios.post('/api/v1/conversations', request);
      return response.data;
    },

    async getConversation(conversationId: string): Promise<Conversation> {
      const response = await axios.get(`/api/v1/conversations/${conversationId}`);
      return response.data;
    },

    async listConversations(params: { userId?: string; status?: string }): Promise<Conversation[]> {
      const response = await axios.get('/api/v1/conversations', { params });
      return response.data;
    },

    async deleteConversation(conversationId: string): Promise<void> {
      await axios.delete(`/api/v1/conversations/${conversationId}`);
    },

    async stopConversation(conversationId: string): Promise<void> {
      await axios.post(`/api/v1/conversations/${conversationId}/stop`);
    },

    // 消息
    async sendMessage(conversationId: string, request: SendMessageRequest): Promise<Message> {
      const response = await axios.post(
        `/api/v1/conversations/${conversationId}/messages`,
        request
      );
      return response.data;
    },

    async getMessages(conversationId: string, limit = 50): Promise<Message[]> {
      const response = await axios.get(
        `/api/v1/conversations/${conversationId}/messages`,
        { params: { limit } }
      );
      return response.data;
    },

    // 事件
    async getEvents(conversationId: string, params?: { kind?: string; limit?: number }): Promise<Event[]> {
      const response = await axios.get(
        `/api/v1/conversations/${conversationId}/events`,
        { params }
      );
      return response.data;
    },

    // SSE 订阅
    subscribeToEvents(
      conversationId: string,
      handlers: {
        onOpen?: () => void;
        onEvent?: (event: Event) => void;
        onError?: (error: any) => void;
      },
      lastEventId?: string
    ): EventSource {
      const url = new URL(`${TEST_CONFIG.baseURL}/api/v1/events/stream`);
      url.searchParams.set('conversationId', conversationId);
      if (lastEventId) {
        url.searchParams.set('lastEventId', lastEventId);
      }

      const eventSource = new EventSource(url.toString());

      eventSource.onopen = () => handlers.onOpen?.();
      eventSource.onmessage = (e) => {
        const event = JSON.parse(e.data);
        handlers.onEvent?.(event);
      };
      eventSource.onerror = (e) => handlers.onError?.(e);

      return eventSource;
    }
  };
}
```

## 测试模式

### 完整流程测试

```typescript
test('完整流程：创建 → 消息 → 事件 → 删除', async () => {
  // Step 1: 创建
  const conversation = await client.createConversation(createRequest);
  conversationId = conversation.conversationId;
  expect(conversation.status).toBeTruthy();

  // Step 2: 等待就绪
  await new Promise(resolve => setTimeout(resolve, 500));

  // Step 3: 发送消息
  const message = await client.sendMessage(conversationId, {
    content: 'Test message'
  });
  expect(message.messageId).toBeTruthy();

  // Step 4: 获取消息
  const messages = await client.getMessages(conversationId);
  expect(messages.length).toBeGreaterThan(0);

  // Step 5: 获取事件
  const events = await client.getEvents(conversationId);
  expect(events.length).toBeGreaterThan(0);

  // Step 6: 删除
  await client.deleteConversation(conversationId);
  conversationId = null;
});
```

### 并发操作测试

```typescript
test('并发创建和操作', async () => {
  // 并发创建 5 个会话
  const createPromises = Array(5).fill(null).map((_, i) =>
    client.createConversation({
      ...createRequest,
      projectId: `${projectId}-${i}`
    }).catch(() => null)
  );

  const conversations = await Promise.all(createPromises);
  const created = conversations.filter(c => c !== null);

  // 记录以便清理
  created.forEach(c => createdConversations.push(c.conversationId));

  expect(created.length).toBeGreaterThan(0);

  // 并发操作
  const operations = created.flatMap(c => [
    client.sendMessage(c.conversationId, { content: 'Test' }),
    client.getMessages(c.conversationId),
    client.getEvents(c.conversationId)
  ]);

  const results = await Promise.all(
    operations.map(op => op.catch(() => null))
  );

  const successRate = results.filter(r => r !== null).length / results.length;
  expect(successRate).toBeGreaterThan(0.5);
});
```

### 错误场景测试

```typescript
test('无效请求处理', async () => {
  const invalidRequests = [
    { userId: '', projectId, gitRepoUrl, branchName },  // 空 userId
    { userId, projectId: '', gitRepoUrl, branchName },  // 空 projectId
    { userId, projectId, gitRepoUrl: '', branchName },  // 空 gitRepoUrl
  ];

  let errorCount = 0;
  for (const request of invalidRequests) {
    try {
      await client.createConversation(request as any);
    } catch (error) {
      errorCount++;
    }
  }

  expect(errorCount).toBeGreaterThan(0);
});
```

## Vitest 配置

### vitest.config.ts

```typescript
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    testTimeout: 60000,
    hookTimeout: 30000,
    setupFiles: ['./src/setup.ts'],
    include: ['tests/**/*.test.ts'],
    reporters: ['verbose'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html']
    }
  }
});
```

### setup.ts

```typescript
import { beforeAll, afterAll } from 'vitest';
import { createClient } from './api-client.js';
import { TEST_CONFIG } from './config.js';

beforeAll(async () => {
  console.log('=== Integration Tests Setup ===');
  console.log(`API Base URL: ${TEST_CONFIG.baseURL}`);

  // 检查服务是否就绪
  const client = createClient();
  try {
    await client.healthCheck();
    console.log('✓ Service is ready');
  } catch (error) {
    console.error('✗ Service not available');
    throw error;
  }

  console.log('=== Setup Complete ===\n');
});

afterAll(async () => {
  console.log('\n=== Integration Tests Cleanup ===');
  console.log('All tests completed');
});
```

## 常用命令

```bash
# 安装依赖
npm install

# 运行所有测试
npm test

# 运行指定文件
npm test -- tests/01-basic-flow.test.ts

# 运行匹配的测试
npm test -- -t "创建"

# 观察模式
npm run test:watch

# 覆盖率报告
npm test -- --coverage

# 详细输出
npm test -- --reporter=verbose

# 只运行失败的测试
npm test -- --failed

# 更新快照
npm test -- --update
```

## E2E OpenHands 测试参考

### 环境变量

```bash
# .env 或运行时设置
SKIP_OPENHANDS_TESTS=false    # 设为 true 跳过 OH 测试
GITLAB_TOKEN=glpat-xxx        # GitLab API Token，用于验证 git push
```

### 运行 E2E 测试

```bash
# 不带 GitLab 验证（跳过 Step 5）
cd addons/coding-agent/integration-tests
npx vitest run tests/05-e2e-openhands.test.ts --reporter=verbose

# 带 GitLab 验证
GITLAB_TOKEN="glpat-xxx" npx vitest run tests/05-e2e-openhands.test.ts --reporter=verbose

# 跳过 OH 测试
SKIP_OPENHANDS_TESTS=true npm test
```

### GitLab 验证 API

```typescript
// 检查分支是否存在
GET ${GITLAB_URL}/api/v4/projects/${encodedProject}/repository/branches/${encodedBranch}
Headers: { 'PRIVATE-TOKEN': token }

// 检查文件是否存在（返回 base64 编码内容）
GET ${GITLAB_URL}/api/v4/projects/${encodedProject}/repository/files/${encodedFile}?ref=${encodedBranch}
Headers: { 'PRIVATE-TOKEN': token }

// 解码文件内容
Buffer.from(response.data.content, 'base64').toString('utf-8')
```

### E2E 事件流示例

典型的完整 E2E 事件序列：

```
1. CONVERSATION_STATUS  { status: "STARTING" }
2. CONVERSATION_STATUS  { status: "READY" }
3. ERROR                { source: "ValidationService", ... }  ← 非终止，可忽略
4. MESSAGE_SENT         { content: "Please do..." }            ← 用户消息
5. CONVERSATION_STATUS  { status: "RUNNING" }
6. MESSAGE_SENT         { id: "uuid", source: "user", ... }   ← OH 回显
7. CONVERSATION_STATUS  { key: "execution_status", ... }       ← agent 开始
8. AGENT_ACTION         { kind: "TerminalAction", command: "git clone ..." }
9. AGENT_OBSERVATION    { kind: "TerminalObservation", content: "Cloning..." }
10. CONVERSATION_STATUS { key: "state", value: "finished" }    ← 终止事件
11. CONVERSATION_STATUS { status: "IDLE" }                     ← 我们的状态更新
```

### 测试超时配置

```typescript
// vitest test-level timeout
test('E2E test', async () => { ... }, 300_000);  // 5 分钟

// pollUntilStatus timeout
await pollUntilStatus(client, id, ['READY'], 120_000);  // 2 分钟

// pollForAgentCompletion timeout
const events = await pollForAgentCompletion(client, id, 240_000);  // 4 分钟

// 内部空闲检测: stableCount > 30 (每 2 秒检查一次 = 60 秒无新事件则认为完成)
```

## 常见问题

### Hook 超时

**问题**：`Hook timed out in 30000ms`

**解决**：
1. 增加超时时间：`hookTimeout: 60000`
2. 并行清理资源而非顺序
3. 减少测试创建的资源数量

### SSE 事件接收失败

**问题**：SSE 连接建立但没有接收到事件

**解决**：
1. 在发送消息前建立 SSE 连接
2. 增加等待时间
3. 使用 `lastEventId` 获取历史事件

### 并发测试不稳定

**问题**：并发测试有时通过有时失败

**解决**：
1. 降低并发数量
2. 增加操作间隔
3. 使用 `.catch(() => null)` 捕获单个失败
4. 验证成功率而非 100% 成功
