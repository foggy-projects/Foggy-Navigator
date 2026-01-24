# Coding Agent 集成测试

这是 Coding Agent 的集成测试套件，使用 Vitest + TypeScript 编写。

## 快速开始

### 1. 安装依赖

```bash
npm install
```

### 2. 启动后端服务

在运行测试前，确保 Spring Boot 应用正在运行：

```bash
cd ../
mvn spring-boot:run
```

等待服务启动，访问 http://localhost:8112/actuator/health 确认服务就绪。

### 3. 运行测试

```bash
# 运行所有测试
npm test

# 只运行一次（非watch模式）
npm run test:run

# 运行特定测试文件
npm test -- 01-basic-flow.test.ts

# 使用 UI 界面运行
npm run test:ui

# 生成覆盖率报告
npm run test:coverage
```

## 环境变量

可以通过环境变量配置测试：

```bash
# API 基础 URL（默认：http://localhost:8112）
API_BASE_URL=http://localhost:8112

# 跳过 Docker 相关测试
SKIP_DOCKER_TESTS=true

# 跳过 OpenHands 相关测试
SKIP_OPENHANDS_TESTS=true
```

示例：

```bash
API_BASE_URL=http://localhost:9090 npm test
```

## 测试文件结构

```
integration-tests/
├── src/                      # 源代码
│   ├── api-client.ts         # API 客户端
│   ├── config.ts             # 测试配置
│   └── types.ts              # 类型定义
├── tests/                    # 测试用例
│   ├── setup.ts              # 全局设置
│   ├── 01-basic-flow.test.ts # 基本流程测试
│   ├── 02-sse-events.test.ts # SSE 事件流测试
│   └── 03-error-handling.test.ts # 错误处理测试
└── docs/                     # 测试文档
    └── INTEGRATION_TESTS.md  # 测试设计文档
```

## 测试场景

### ✅ 01 - 基本流程测试 (Basic Flow)

测试 Conversation 的完整生命周期：
- 创建 Conversation
- 获取 Conversation
- 发送消息
- 获取消息列表
- 查询 Conversation 列表
- 删除 Conversation

**运行**: `npm test -- 01-basic-flow.test.ts`

### 🔄 02 - SSE 事件流测试 (待实现)

测试实时事件推送：
- 订阅事件流
- 接收 MESSAGE_SENT 事件
- 断线重连
- 取消订阅

### ⚠️ 03 - 错误处理测试 (待实现)

测试错误场景：
- 404 错误（资源不存在）
- 400 错误（参数错误）
- 500 错误（服务器错误）

## 调试技巧

### 查看详细日志

测试会输出详细的日志信息，包括：
- API 请求和响应
- 创建的资源 ID
- 错误信息

### 保留测试数据

如果需要手动检查测试创建的数据，可以禁用自动清理：

修改 `src/config.ts`:
```typescript
autoCleanup: false  // 改为 false
```

### 单步调试

在 VS Code 中，可以使用以下配置调试测试：

`.vscode/launch.json`:
```json
{
  "type": "node",
  "request": "launch",
  "name": "Debug Integration Tests",
  "runtimeExecutable": "npm",
  "runtimeArgs": ["test"],
  "console": "integratedTerminal"
}
```

## 常见问题

### Q: 测试失败：连接超时

**A**: 确保后端服务正在运行：
```bash
curl http://localhost:8112/actuator/health
```

### Q: 测试失败：Docker 错误

**A**: 检查 Docker Desktop 是否运行，并确保有足够的权限。

### Q: 如何跳过 Docker 测试？

**A**: 设置环境变量：
```bash
SKIP_DOCKER_TESTS=true npm test
```

### Q: 测试运行很慢

**A**: 集成测试涉及真实的 Docker 操作，需要较长时间。可以：
- 只运行特定测试文件
- 增加超时时间
- 使用并行测试（Vitest 默认并行）

## 持续集成

参考 `.github/workflows/integration-tests.yml` 查看 CI 配置。

## 贡献指南

添加新的测试场景时：

1. 在 `tests/` 目录创建新的测试文件
2. 使用序号前缀命名（如 `04-xxx.test.ts`）
3. 遵循现有的测试结构和命名规范
4. 确保测试资源被正确清理
5. 在 README 中更新测试场景列表

## 参考资料

- [Vitest 文档](https://vitest.dev/)
- [API 设计文档](../docs/API_DESIGN.md)
- [测试设计文档](../docs/testing/INTEGRATION_TESTS.md)
