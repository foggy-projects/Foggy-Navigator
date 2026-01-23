# Coding Agent 集成测试文档

## 概述

本文档描述 Coding Agent 的集成测试策略和测试用例。集成测试使用 Vitest + TypeScript 编写，通过 HTTP 请求测试真实的 API 端点。

## 测试环境

### 前置条件

1. **Java 后端服务已启动**
   - Spring Boot 应用运行在 `http://localhost:8080`
   - 数据库已配置（H2 或 MySQL）

2. **Docker 环境**
   - Docker Desktop 已启动
   - 可以创建和管理容器

3. **OpenHands 服务**（可选）
   - 如果需要测试真实的 OpenHands 集成
   - 本地运行在 `http://localhost:3000`

### 测试工具

- **Vitest**: 快速的单元测试框架
- **TypeScript**: 类型安全的测试代码
- **axios**: HTTP 客户端
- **EventSource**: SSE 客户端

## 测试策略

### 测试层级

1. **Smoke Tests（冒烟测试）**
   - 验证服务是否启动
   - 验证基本端点可访问

2. **Happy Path Tests（正常流程测试）**
   - 创建 → 发送消息 → 获取事件 → 删除
   - 完整的用户场景

3. **Edge Cases（边界情况测试）**
   - 错误处理
   - 并发场景
   - 资源清理

4. **Performance Tests（性能测试）**
   - 响应时间
   - 并发容量

## 核心测试场景

### 场景 1: 基本流程（Happy Path）

**目标**: 验证完整的 Conversation 生命周期

**步骤**:
1. 创建 Conversation
2. 验证 Conversation 状态为 READY
3. 发送一条 Message
4. 验证 Message 已保存
5. 获取 Conversation 的历史事件
6. 删除 Conversation
7. 验证 Conversation 已删除

**预期结果**: 所有步骤成功，无错误

---

### 场景 2: 消息发送与事件流

**目标**: 验证消息发送和事件推送

**步骤**:
1. 创建 Conversation
2. 订阅 SSE 事件流
3. 发送一条 Message
4. 监听并验证接收到 `MESSAGE_SENT` 事件
5. 清理资源

**预期结果**:
- 消息成功发送
- SSE 事件流推送了 `MESSAGE_SENT` 事件

---

### 场景 3: 多个 Conversation 隔离

**目标**: 验证多个 Conversation 之间相互隔离

**步骤**:
1. 创建 Conversation A
2. 创建 Conversation B
3. 向 A 发送消息
4. 向 B 发送消息
5. 验证 A 和 B 的消息列表不同
6. 验证 A 和 B 的命名空间不同
7. 清理资源

**预期结果**:
- 两个 Conversation 独立运行
- 消息不会混淆

---

### 场景 4: 错误处理

**目标**: 验证 API 错误处理

**步骤**:
1. 尝试获取不存在的 Conversation
2. 尝试向不存在的 Conversation 发送消息
3. 尝试创建无效的 Conversation（缺少必填字段）

**预期结果**:
- 返回正确的 HTTP 状态码（404, 400）
- 返回清晰的错误信息

---

### 场景 5: Conversation 恢复

**目标**: 验证 Conversation 恢复机制

**步骤**:
1. 创建 Conversation
2. 停止 Conversation
3. 恢复 Conversation
4. 验证 Conversation 状态变为 READY
5. 清理资源

**预期结果**:
- Conversation 成功恢复
- 可以继续发送消息

---

## 测试数据

### 测试 Git 仓库

```typescript
const TEST_REPO = {
  gitRepoUrl: "https://github.com/test/semantic-layer.git",
  branchName: "main"
};
```

### 测试用户

```typescript
const TEST_USER = {
  userId: "test-user-" + Date.now(),
  projectId: "test-project-" + Date.now()
};
```

## 测试配置

```typescript
// test.config.ts
export const TEST_CONFIG = {
  baseURL: process.env.API_BASE_URL || "http://localhost:8080",
  timeout: 30000,
  retries: 2,

  // 测试标志
  skipDockerTests: process.env.SKIP_DOCKER_TESTS === "true",
  skipOpenHandsTests: process.env.SKIP_OPENHANDS_TESTS === "true",

  // 资源清理
  autoCleanup: true,
  cleanupTimeout: 5000
};
```

## 测试执行

### 运行所有测试

```bash
npm run test:integration
```

### 运行特定场景

```bash
npm run test:integration -- --grep "基本流程"
```

### 生成测试报告

```bash
npm run test:integration -- --reporter=html
```

## 测试最佳实践

### 1. 资源清理

每个测试必须清理自己创建的资源：

```typescript
afterEach(async () => {
  if (conversationId) {
    await deleteConversation(conversationId);
  }
});
```

### 2. 超时处理

设置合理的超时时间：

```typescript
test('创建 Conversation', async () => {
  // ...
}, { timeout: 30000 }); // 30秒超时
```

### 3. 错误日志

记录详细的错误信息：

```typescript
try {
  await createConversation(request);
} catch (error) {
  console.error('创建失败:', error.response?.data);
  throw error;
}
```

### 4. 幂等性

测试应该可以重复运行：

```typescript
const userId = `test-user-${Date.now()}`;
const projectId = `test-project-${Date.now()}`;
```

## 持续集成

### GitHub Actions 配置

```yaml
name: Integration Tests
on: [push, pull_request]
jobs:
  integration-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Start Backend
        run: cd addons/coding-agent && mvn spring-boot:run &
      - name: Wait for Backend
        run: npx wait-on http://localhost:8080/actuator/health
      - name: Run Integration Tests
        run: npm run test:integration
```

## 故障排查

### 常见问题

1. **连接超时**
   - 检查后端服务是否启动
   - 检查端口是否正确

2. **Docker 错误**
   - 检查 Docker Desktop 是否运行
   - 检查 Docker 权限

3. **数据库错误**
   - 检查数据库连接配置
   - 清理测试数据

## 性能基准

| 操作 | 目标响应时间 | 最大响应时间 |
|------|-------------|-------------|
| 创建 Conversation | < 5s | < 10s |
| 发送 Message | < 500ms | < 2s |
| 获取事件 | < 200ms | < 1s |
| 删除 Conversation | < 2s | < 5s |

## 下一步

- [ ] 实现基本流程测试
- [ ] 实现 SSE 事件流测试
- [ ] 实现错误处理测试
- [ ] 添加性能测试
- [ ] 集成到 CI/CD
