---
name: testing-guide
description: Foggy Navigator 全局测试规范与参考手册。查询测试分类标准、文件组织规范、命名约定、覆盖状态。不负责具体编写测试（由 Agent Teams 或模块专属技能执行）。触发词：/testing-guide, /tg, 提及"测试规范"、"测试在哪"、"测试标准"、"覆盖状态"。
---

# Foggy Navigator 全局测试规范

项目级测试标准、文件组织、命名约定和质量目标的统一定义。

## ⚡ 技能选择指南

> **本技能是"参考手册"，不负责具体执行。** 根据你的意图选择正确的技能/Agent：

| 你想做什么 | 应该使用 | 触发词 |
|-----------|---------|--------|
| 查询测试规范、文件位置、覆盖状态 | **本技能**（testing-guide） | `/tg` |
| 编写 Coding Agent 的 L3 API 集成测试 | coding-agent-integration-tests | `/ca-tests` |
| 编写 Session Module 的 L3 API 集成测试 | session-integration-tests | `/st` |
| 浏览器自动化测试 Coding Agent 前端 | coding-agent-e2e-browser | `/test-coding-agent` |
| 规划某模块的测试方案 | test-planner Agent | Agent Teams 调用 |
| 编写测试代码并红绿循环 | test-author Agent | Agent Teams 调用 |
| 修复测试发现的生产代码 bug | prod-fixer Agent | Agent Teams 调用 |

## 使用场景

- 了解测试文件放在哪、如何组织
- 查阅测试框架和依赖版本
- 确认命名规范和代码风格
- 检查当前测试覆盖状态和下一步计划
- 为 Agent Teams 和模块专属技能提供基础规范参考

## 测试分类

本项目的测试分为四层，由快到慢：

| 层级 | 类型 | 运行环境 | 速度 | 职责 |
|------|------|---------|------|------|
| L1 | 单元测试 (Unit) | Mockito / happy-dom | < 1s | 单个类/函数的逻辑正确性 |
| L2 | 组件集成测试 (Spring) | Spring + H2 | 2-10s | Repository、Service 层与 JPA/数据库的交互 |
| L3 | API 集成测试 (HTTP) | Vitest + 真实后端 | 5-30s | REST API 端到端验证（含认证、SSE） |
| L4 | E2E 测试 (Browser) | Playwright | 10-60s | 用户视角的完整功能链路 |

### 什么时候写哪一层

- **纯逻辑、无副作用** → L1 单元测试（Mockito / Vitest）
- **涉及数据库交互** → L2 `@SpringBootTest` + H2
- **涉及 HTTP 接口** → L3 集成测试（独立 Vitest 项目调真实服务）
- **涉及用户操作流** → L4 Playwright E2E

## 后端测试规范（Java / Maven）

### 文件位置

```
{module}/
└── src/
    ├── main/java/com/foggy/navigator/{module}/
    │   └── ...                          # 生产代码
    └── test/
        ├── java/com/foggy/navigator/{module}/
        │   ├── service/                 # Service 层单元/集成测试
        │   │   ├── XxxServiceTest.java          # L1 单元测试
        │   │   └── XxxServiceIntegrationTest.java  # L2 集成测试（如需区分）
        │   ├── controller/              # Controller 层测试
        │   │   └── XxxControllerTest.java       # L1 MockMvc 测试
        │   └── {其他包}/               # 与生产代码包结构镜像
        └── resources/
            └── application.yml          # 测试专用配置（H2 数据源等）
```

### 命名规范

| 项目 | 规范 | 示例 |
|------|------|------|
| 测试类 | `{被测类}Test.java` | `SkillConfigManagerTest.java` |
| 集成测试类（L2） | `{被测类}Test.java`（当前统一） | `AgentModelServiceTest.java` |
| 测试方法 | `@DisplayName("中文描述")` + 方法名用英文 | `@DisplayName("应该成功创建配置") void shouldCreateConfig()` |
| 嵌套分组 | `@Nested class 功能分组` | `@Nested class CreateTests { }` |

### 测试注解选择

```java
// L1 - 纯 Mockito 单元测试（首选，最快）
@ExtendWith(MockitoExtension.class)
class XxxServiceTest {
    @Mock SomeDependency dep;
    @InjectMocks XxxService service;
}

// L2 - Spring 集成测试（需要真实 Bean 注入 / JPA）
@SpringBootTest(classes = TestConfig.class)
@ActiveProfiles("test")
@Transactional  // 测试后自动回滚
class XxxServiceIntegrationTest {
    @Autowired XxxService service;
}

// L1 - Controller 层 MockMvc 测试
@WebMvcTest(XxxController.class)
class XxxControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean XxxService service;
}
```

### TestConfig 类模板

当模块需要 L2 集成测试时，在 `src/test/java` 下创建：

```java
@EnableAutoConfiguration
@EntityScan(basePackages = "com.foggy.navigator.common.entity")
@EnableJpaRepositories(basePackages = "com.foggy.navigator.{module}.repository")
@ComponentScan(basePackages = {
    "com.foggy.navigator.{module}.service",
    "com.foggy.navigator.common.security"
})
class TestConfig { }
```

### application.yml 模板（测试）

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MYSQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
```

### 后端测试依赖

所有模块统一使用（已在各模块 pom.xml 中声明）：

```xml
<!-- JUnit 5 + Mockito + Spring Test + AssertJ（通过 starter） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- 集成测试用 H2 内存数据库 -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

### 后端测试运行命令

```bash
# 运行全部后端测试
mvn test -pl launcher -am

# 运行单个模块测试
mvn test -pl agent-framework
mvn test -pl session-module
mvn test -pl addons/coding-agent

# 运行单个测试类
mvn test -pl agent-framework -Dtest=SkillMatcherTest

# 运行匹配的测试方法
mvn test -pl agent-framework -Dtest="SkillMatcherTest#shouldMatch*"

# 编译 + 测试（推荐日常使用）
mvn compile test -pl launcher -am
```

## 前端测试规范（TypeScript / Vitest）

### 文件位置

```
packages/{package}/
├── src/
│   ├── __tests__/                    # 通用测试（composables、stores、utils）
│   │   ├── useXxx.test.ts            # Composable 测试
│   │   ├── xxxStore.test.ts          # Store 测试
│   │   └── xxxAdapter.test.ts        # Adapter/工具 测试
│   ├── components/
│   │   └── __tests__/                # 组件测试（紧邻组件）
│   │       └── XxxComponent.test.ts
│   └── views/
│       └── __tests__/                # 视图级集成测试
│           └── XxxView.integration.test.ts
└── vite.config.ts                    # 内嵌 Vitest 配置
```

### 命名规范

| 项目 | 规范 | 示例 |
|------|------|------|
| 单元测试 | `{被测模块}.test.ts` | `chatState.test.ts` |
| 集成测试 | `{被测视图}.integration.test.ts` | `ClaudeWorkerView.integration.test.ts` |
| 辅助函数 | `make{Entity}()` 工厂函数 | `makeWorker()`, `makeTask()` |

### 前端测试框架

| 工具 | 版本 | 用途 |
|------|------|------|
| Vitest | ^4.0.18 | 测试运行器 |
| @vue/test-utils | ^2.4.6 | Vue 组件挂载与交互 |
| happy-dom | ^20.6.1 | 轻量 DOM 模拟环境 |
| Playwright | ^1.58.0 | E2E 浏览器测试 |

### 前端测试代码模式

```typescript
// 单元测试 - Composable
import { describe, test, expect, vi, beforeEach } from 'vitest';

vi.mock('@/api/session', () => ({
  sessionApi: { create: vi.fn(), list: vi.fn() }
}));

describe('useSession', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  test('应该创建新会话', async () => {
    // arrange → act → assert
  });
});
```

```typescript
// 组件测试 - Vue Test Utils
import { mount } from '@vue/test-utils';
import MyComponent from '../MyComponent.vue';

test('应该渲染标题', () => {
  const wrapper = mount(MyComponent, {
    props: { title: 'Hello' }
  });
  expect(wrapper.text()).toContain('Hello');
});
```

### 前端测试运行命令

```bash
# 运行全部前端测试
pnpm -r run test

# 运行单个包的测试
cd packages/foggy-chat && pnpm test
cd packages/navigator-frontend && pnpm test

# 观察模式
cd packages/navigator-frontend && pnpm test:watch

# 运行匹配文件
cd packages/navigator-frontend && pnpm vitest run src/__tests__/useSession.test.ts
```

## L3 集成测试规范（独立 Vitest 项目 + Mock Service）

L3 集成测试是独立的 Node.js 项目，通过 HTTP 调用真实后端服务。
**核心原则：所有外部依赖必须通过 Mock Service 替代，测试不得依赖任何真实的第三方服务。**

### 已有 L3 测试项目

| 项目路径 | 测试对象 | 对应技能 |
|----------|---------|---------|
| `addons/coding-agent/integration-tests/` | Coding Agent REST + SSE API | `/ca-tests` |
| `session-module/integration-tests/` | Session Module REST + SSE API | `/session-tests` |

### L3 测试项目结构

```
{module}/integration-tests/
├── mock/                           # ★ Mock Service 目录（必须）
│   ├── llm-mock-server.ts          # LLM API Mock（OpenAI-compatible）
│   ├── claude-worker-mock.ts       # Claude Worker Python 服务 Mock
│   ├── openhands-mock.ts           # OpenHands API Mock（如模块需要）
│   ├── git-provider-mock.ts        # GitHub/GitLab API Mock（如模块需要）
│   ├── fixtures/                   # 响应数据固件
│   │   ├── llm-responses/          # LLM 返回的完整 JSON
│   │   │   ├── simple-text.json    # 纯文本回复
│   │   │   ├── tool-call.json      # 工具调用回复
│   │   │   └── streaming-chunks/   # SSE 流式分块
│   │   ├── worker-events/          # Worker SSE 事件序列
│   │   └── ...
│   └── README.md                   # Mock Service 使用说明 + 数据采集记录
├── src/
│   ├── api-client.ts               # HTTP 客户端封装（含认证）
│   ├── config.ts                   # 测试配置（baseUrl、mock端口等）
│   └── types.ts                    # TypeScript 类型定义
├── tests/
│   ├── setup.ts                    # 全局 setup（启动 Mock + 健康检查 + 登录）
│   ├── 01-{功能}.test.ts           # 按序号组织测试文件
│   ├── 02-{功能}.test.ts
│   └── ...
├── package.json
├── vitest.config.ts
├── tsconfig.json
└── .env                            # 环境变量（baseUrl、mock端口、凭证等）
```

### Mock Service 规范（L3 核心要求）

#### 基本原则

1. **凡是调用外部服务的地方，都必须先在 `mock/` 目录实现 Mock Service**
2. Mock Service 是独立的 HTTP 服务进程，监听指定端口，模拟外部系统的真实 API 行为
3. 后端通过环境变量或配置将外部服务地址指向 Mock Service（如 `LLM_BASE_URL=http://localhost:{mock-port}`）
4. **禁止在集成测试中直接调用真实的第三方 API**（LLM、Git Provider、OpenHands 等）

#### 需要 Mock 的外部服务清单

| 外部服务 | 协议 | 被调用方 | Mock 优先级 | 说明 |
|----------|------|---------|------------|------|
| **LLM API**（OpenAI-compatible） | HTTP POST `/chat/completions` | agent-framework `LangChain4jAdapter` | 🔴 最高 | 所有 Agent 的智能核心，含流式/工具调用 |
| **Claude Worker**（Python 服务） | HTTP/SSE | claude-worker-agent `ClaudeWorkerClient` | 🔴 最高 | `/api/v1/query` SSE 流、权限交互、文件操作 |
| **OpenHands V1 API** | HTTP REST | coding-agent `OpenHandsClient` | 🟡 中 | 容器管理、事件轮询 |
| **GitHub API** | HTTP REST | coding-agent `GitHubClient` | 🟡 中 | 仓库列表、分支查询 |
| **GitLab API** | HTTP REST | coding-agent `GitLabClient` | 🟡 中 | 仓库列表、分支查询 |
| **Validation Service** | HTTP REST | coding-agent `ValidationServiceClient` | 🔵 低 | 代码校验（可选功能） |

#### Mock Service 实现模式

每个 Mock Service 是一个轻量 Express/Fastify 应用：

```typescript
// mock/llm-mock-server.ts
import express from 'express';
import { readFileSync } from 'fs';
import { join } from 'path';

const app = express();
app.use(express.json());

// 加载 fixture 数据
const fixtures = {
  simpleText: JSON.parse(readFileSync(join(__dirname, 'fixtures/llm-responses/simple-text.json'), 'utf-8')),
  toolCall: JSON.parse(readFileSync(join(__dirname, 'fixtures/llm-responses/tool-call.json'), 'utf-8')),
};

// Mock: POST /chat/completions（OpenAI-compatible）
app.post('/chat/completions', (req, res) => {
  const { messages, tools, stream } = req.body;

  if (stream) {
    // SSE 流式响应
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    const chunks = loadStreamChunks(messages);
    chunks.forEach((chunk, i) => {
      setTimeout(() => {
        res.write(`data: ${JSON.stringify(chunk)}\n\n`);
        if (i === chunks.length - 1) {
          res.write('data: [DONE]\n\n');
          res.end();
        }
      }, i * 50); // 模拟延迟
    });
    return;
  }

  // 非流式：根据请求内容匹配 fixture
  if (tools && tools.length > 0) {
    return res.json(fixtures.toolCall);
  }
  return res.json(fixtures.simpleText);
});

// 启动
export function startLlmMock(port: number = 18080) {
  return new Promise<void>((resolve) => {
    app.listen(port, () => {
      console.log(`LLM Mock Server started on port ${port}`);
      resolve();
    });
  });
}
```

#### Fixture 数据管理

Fixture 是 Mock Service 返回的真实数据快照，**必须来自真实 API 采集**而非手工编写。

```
mock/fixtures/
├── llm-responses/
│   ├── simple-text.json              # 纯文本完成（非流式）
│   ├── tool-call.json                # 含 tool_calls 的完成
│   ├── multi-turn-tool.json          # 多轮工具调用
│   ├── streaming-chunks/
│   │   ├── text-stream.jsonl         # 流式文本分块序列
│   │   └── tool-stream.jsonl         # 流式工具调用分块序列
│   └── errors/
│       ├── rate-limit-429.json       # 限流错误
│       └── auth-error-401.json       # 认证错误
├── worker-events/
│   ├── query-text-response.jsonl     # Worker SSE：纯文本任务事件序列
│   ├── query-tool-use.jsonl          # Worker SSE：含工具使用的事件序列
│   ├── query-permission-request.jsonl # Worker SSE：需要权限确认的事件序列
│   └── query-error.jsonl             # Worker SSE：错误事件序列
├── git-provider/
│   ├── github-repos.json             # GitHub 仓库列表
│   ├── github-branches.json          # GitHub 分支列表
│   ├── gitlab-projects.json          # GitLab 项目列表
│   └── gitlab-branches.json          # GitLab 分支列表
└── README.md                         # 数据采集记录（采集时间、API版本、采集方法）
```

**Fixture 采集规则**：
- 每个 fixture 文件头部以注释记录采集来源、时间、API 版本
- 敏感数据（API Key、Token、真实用户名）必须脱敏替换
- 数据结构变更时重新采集，更新 README.md 记录

#### 全局 Setup 集成 Mock

```typescript
// tests/setup.ts
import { startLlmMock } from '../mock/llm-mock-server.js';
import { startWorkerMock } from '../mock/claude-worker-mock.js';

export async function setup() {
  // 1. 启动 Mock Services
  await startLlmMock(18080);
  await startWorkerMock(18031);

  // 2. 等待后端服务就绪（后端已配置指向 Mock 端口）
  await waitForHealth('http://localhost:8112/actuator/health');

  // 3. 登录获取 JWT
  await login();
}

export async function teardown() {
  // 关闭 Mock Services
  await stopAllMocks();
}
```

#### 后端配置指向 Mock

集成测试启动后端时，通过环境变量将外部服务地址切换到 Mock：

```bash
# .env（集成测试环境变量）
LLM_BASE_URL=http://localhost:18080      # → LLM Mock
LLM_API_KEY=test-mock-key
CLAUDE_WORKER_BASE_URL=http://localhost:18031  # → Worker Mock
OPENHANDS_API_URL=http://localhost:18090  # → OpenHands Mock（如需要）
```

或在 `application-test.yml` 中配置：

```yaml
foggy:
  llm:
    base-url: http://localhost:18080
    api-key: test-mock-key
  claude-worker:
    base-url: http://localhost:18031
```

### L3 测试命名

- 文件名：`{两位序号}-{功能英文}.test.ts`（如 `01-basic-flow.test.ts`）
- describe：`{序号} - {功能中文} ({功能英文})`
- test：`应该{预期行为}`

### L3 前置条件

```bash
# 1. Mock Services 由 tests/setup.ts 自动启动（无需手动）

# 2. 启动后端服务（指向 Mock 端口）
powershell -ExecutionPolicy Bypass -File start-launcher-mock.ps1

# 3. 验证后端就绪
curl http://localhost:8112/actuator/health

# 4. 运行集成测试
cd {module}/integration-tests
npm install
npm test
```

### Mock Service 开发流程

当需要为新的外部依赖创建 Mock 时：

1. **采集真实数据** — 调用真实 API，保存完整的请求/响应为 fixture
2. **分析数据结构** — 识别必要字段、可选字段、枚举值
3. **实现 Mock Server** — 在 `mock/` 下创建 Express 应用，加载 fixture 返回
4. **实现场景匹配** — 根据请求参数返回不同 fixture（正常/错误/边界）
5. **集成到 setup** — 在 `tests/setup.ts` 中启动/关闭
6. **记录采集信息** — 更新 `mock/README.md` 记录数据来源和版本

## L4 E2E 测试规范（Playwright）

### 文件位置

```
packages/navigator-frontend/
├── e2e/                              # 推荐 E2E 测试目录（待建）
│   ├── login.spec.ts
│   └── ...
├── playwright.config.ts              # Playwright 配置（待建）
└── tooltip-test.spec.ts              # 已有示例
```

### E2E 测试规范

- 测试文件：`{功能}.spec.ts`
- 测试前确保前端 (`localhost:5174`) 和后端 (`localhost:8112`) 均已启动
- 登录账号：`root / root123`

## 各模块测试现状

### 后端模块覆盖清单

| 模块 | 测试文件数 | 覆盖层级 | 状态 | 备注 |
|------|-----------|---------|------|------|
| agent-framework | 15 | L1 + L2 | ✅ 较完善 | 核心框架，覆盖最广 |
| addons/coding-agent | 9 | L1 + L2 + L3 | ✅ 较完善 | 含独立集成测试项目 |
| addons/claude-worker-agent | 6 | L1 + L2 | 🟡 基本 | 核心流程已覆盖 |
| session-module | 5 | L1 + L2 + L3 | 🟡 基本 | 含独立集成测试项目 |
| addons/task-assistant | 3 | L1 | 🟡 基本 | — |
| metadata-config-module | 3 | L2 | 🟡 基本 | — |
| metadata-query-module | 2 | L1 + L2 | 🔴 偏少 | — |
| user-auth-module | 1 | L2 | 🔴 偏少 | 仅 JWT 测试 |
| tutor-agent | 1 | L2 | 🔴 偏少 | 仅引导卡片测试 |
| navigator-common | 0 | — | ⬜ 无 | Entity/DTO 模块，优先级低 |
| navigator-spi | 0 | — | ⬜ 无 | 接口定义模块，无需测试 |
| monitoring-module | 0 | — | ⬜ 无 | 待补充 |

### 前端模块覆盖清单

| 包 | 测试文件数 | 覆盖层级 | 状态 |
|----|-----------|---------|------|
| foggy-chat | 2 | L1 | 🟡 基本（chatState + interactionCards） |
| navigator-frontend | 5 | L1 + L2 | 🟡 基本（composables + 集成测试） |
| foggy-chat-core | 0 | — | ⬜ 无 |

## 测试优先级规划

### 第一优先级：补齐核心模块单元测试

1. **user-auth-module** — 认证/授权是安全基础
2. **metadata-config-module** — 配置管理影响全局
3. **metadata-query-module** — 查询逻辑正确性关键

### 第二优先级：增强已有测试

4. **session-module** — 补充 SSE 推送、消息持久化的单元测试
5. **claude-worker-agent** — 补充任务分派、状态流转的边界测试

### 第三优先级：E2E 与前端

6. **navigator-frontend** — Playwright E2E 核心流程
7. **foggy-chat** — 组件交互测试

## 约束条件

### 通用原则

1. **测试与生产代码同步**：新增/修改功能时必须同步编写或更新对应测试
2. **测试独立性**：每个测试用例独立运行，不依赖其他测试的执行顺序
3. **资源清理**：`@Transactional`（Java）或 `afterEach`（TS）确保不留脏数据
4. **不 Mock 过度**：只 Mock 外部依赖（LLM、远程服务），不 Mock 被测类的内部方法
5. **断言明确**：每个 test 至少有一个有意义的 assert/expect

### Java 特定

- 使用 `@DisplayName` 提供中文描述
- L1 测试用 `@ExtendWith(MockitoExtension.class)`，不启动 Spring 容器
- L2 测试用 `@SpringBootTest` + `@Transactional`
- 优先使用 AssertJ 风格断言（`assertThat(...).isEqualTo(...)`）
- 禁止在测试中使用 `Thread.sleep()`，使用 `Awaitility` 等待异步操作

### TypeScript 特定

- 使用 `vi.mock()` 模拟模块，`vi.fn()` 模拟函数
- 工厂函数以 `make` 前缀命名（`makeWorker()`、`makeTask()`）
- `beforeEach` 中调用 `vi.clearAllMocks()` 重置状态
- 异步测试使用 `async/await`，避免回调风格

## 决策规则

- 如果修改了 Service 层逻辑 → 必须有对应的 L1 单元测试
- 如果修改了 Controller 端点 → 必须有 `@WebMvcTest` 或 L3 集成测试覆盖
- 如果修改了 Repository 查询 → 用 L2 `@SpringBootTest` + H2 验证
- 如果修改了前端 Composable → 必须有 Vitest 单元测试
- 如果修改了 SSE/WebSocket 通信 → 用 L3 集成测试验证
- 如果新建模块 → 同时创建 `src/test/` 目录结构和 `application.yml`
- 如果编写 L3 集成测试 → 参考已有技能 `/ca-tests` 或 `/session-tests`
- 如果需要数据库测试 → 使用 H2 内存数据库，不连接真实 MySQL

## 相关技能与 Agent

### 模块专属测试技能（负责具体执行）

| 技能 | 触发词 | 层级 | 职责 |
|------|--------|------|------|
| coding-agent-integration-tests | `/ca-tests` | L3 API | Coding Agent REST + SSE 集成测试 |
| session-integration-tests | `/st` | L3 API | Session Module REST + SSE 集成测试 |
| coding-agent-e2e-browser | `/test-coding-agent` | L4 浏览器 | Playwright 自动化 Coding Agent 前端 |

### Agent Teams（负责测试工作流编排）

| Agent | 职责 | 配置文件 |
|-------|------|---------|
| test-planner | 读代码出测试计划（只读不写） | `.claude/agents/test-planner.md` |
| test-author | 写测试 + 跑测试 + 修测试（不改生产代码） | `.claude/agents/test-author.md` |
| prod-fixer | 修生产代码 bug（不写测试） | `.claude/agents/prod-fixer.md` |

## 常用命令速查

```bash
# === 后端 ===
mvn compile test -pl launcher -am          # 全量编译+测试
mvn test -pl agent-framework               # 单模块测试
mvn test -pl session-module -Dtest=XxxTest  # 单类测试

# === 前端 ===
pnpm -r run test                           # 全量前端测试
cd packages/navigator-frontend && pnpm test # 单包测试
cd packages/foggy-chat && pnpm test:watch   # 观察模式

# === L3 集成测试 ===
cd addons/coding-agent/integration-tests && npm test
cd session-module/integration-tests && npm test

# === E2E ===
npx playwright test                        # 运行 Playwright 测试
npx playwright test --headed               # 有头模式（可视）
```
