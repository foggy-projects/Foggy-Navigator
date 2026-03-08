# Foggy Navigator 系统架构概览

> 个人 AI Agent 编排中枢 - 系统架构与设计原则

## 1. 系统定位与目标

### 1.1 核心愿景

**Foggy Navigator** 是一个个人 AI Agent 编排中枢，核心目标是将多种 AI 能力统一编排到一个平台中：

- **多 Agent 协作**：导师 Agent 统一入口，按需分派到专业 Agent（编程、语义层、分析等）
- **分布式能力**：调度多台主机上的 Claude Code 完成远程编程任务
- **插件化扩展**：以 Addon 模式接入新能力（编程、语义层服务、外部工具等）
- **AI 分身**：提供 AI 替身回答同事/朋友的常见问题
- **外部工具集成**：接入 ClawdBot 等第三方 AI 工具

### 1.2 设计原则

1. **平台化**：核心框架与业务能力分离，Agent/Addon 可插拔
2. **配置化**：Agent、Skill 通过 YAML + Markdown 定义，减少硬编码
3. **SPI 解耦**：模块间通过 SPI Facade 接口通信，松耦合
4. **可观察**：TraceId 全链路传播、结构化日志、LLM 调用指标
5. **渐进式**：MVP 优先，以 Addon 方式逐步扩展能力

---

## 2. 系统整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                     表现层 (Presentation)                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Navigator Frontend (Vue 3 + Element Plus)           │   │
│  │  - 聊天界面  - 会话管理  - 工人管理  - SSE 推送     │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                     API 层 (REST + SSE)                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Session API │  │  Auth API    │  │  Config API  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                   Agent 框架层 (Core)                        │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  agent-framework                                      │   │
│  │  - AgentRegistry    - SkillManager   - LlmAdapter    │   │
│  │  - AgentInvoker     - BuiltInTool    - ToolExecutor  │   │
│  │  - SessionRouter    - TraceId/MDC    - CircuitBreaker│   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ Tutor Agent  │  │ Coding Agent │  │Claude Worker │      │
│  │ (导师)       │  │ (OpenHands)  │  │  Agent       │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                     Addons（可插拔）                          │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                 基础模块层 (Foundation)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ session-     │  │ user-auth-   │  │ metadata-    │      │
│  │ module       │  │ module       │  │ config-module│      │
│  │ (会话+SSE)   │  │ (JWT认证)    │  │ (Skill配置)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│  ┌──────────────┐  ┌──────────────┐                        │
│  │ navigator-   │  │ navigator-   │                        │
│  │ common       │  │ spi          │                        │
│  │ (公共DTO)    │  │ (SPI接口)    │                        │
│  └──────────────┘  └──────────────┘                        │
└─────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────┐
│                 基础设施层 (Infrastructure)                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   MySQL      │  │  LLM API     │  │  Docker      │      │
│  │  (持久化)    │  │ (OpenAI兼容) │  │ (外部服务)    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心流程：用户对话

```
1. 用户发送消息
   ↓
2. SessionController 接收 → 创建/查找会话
   ↓
3. AgentRouter 路由到目标 Agent（默认 tutor-agent）
   ↓
4. DefaultAgentInvoker 执行 Agent 循环（最多 10 轮）：
   ├─ LLM 生成回复或 tool_call
   ├─ ToolExecutor 执行 BuiltInTool
   ├─ 将工具结果喂回 LLM
   └─ 重复直到 LLM 返回纯文本或达到上限
   ↓
5. SSE 实时推送 Agent 回复到前端
```

### 2.3 Agent 分派流程

```
用户 → Tutor Agent
         ├─ 检查系统状态 → check-system-status Skill
         ├─ 编程任务 → dispatch-coding-task → Coding Agent (OpenHands)
         ├─ Claude 任务 → Claude Worker Agent (远程主机)
         └─ 其他引导 → help-troubleshoot / suggest-next-step
```

---

## 3. 模块职责

### 3.1 核心模块

| 模块 | 职责 | 状态 |
|------|------|------|
| **navigator-common** | 公共 Entity、DTO、枚举、CredentialEncryptor | 已完成 |
| **navigator-spi** | SPI Facade 接口（CodingAgentFacade、ClaudeWorkerFacade、SkillConfigManager） | 已完成 |
| **agent-framework** | Agent 核心框架：LLM 调用、Skill 解析、工具执行、会话路由、熔断器 | 已完成 |
| **session-module** | 会话管理、消息持久化、SSE 推送、JpaAgentRegistry | 已完成 |
| **user-auth-module** | JWT 认证、用户管理、租户隔离 | 已完成 |
| **metadata-config-module** | Skill 配置管理 | 已完成 |
| **tutor-agent** | 导师 Agent：引导用户、分派任务、6 个 Skill、BuiltInTools | 已完成 |
| **launcher** | Spring Boot 启动器，聚合所有模块 | 已完成 |

### 3.2 Addon 模块

| Addon | 职责 | 状态 |
|-------|------|------|
| **coding-agent** | OpenHands 集成，Docker 容器化编程环境，Git 操作 | 已完成 |
| **claude-worker-agent** | 远程 Claude Code 工人管理，跨主机任务分发 | 已完成 |

### 3.3 前端模块

| 模块 | 职责 | 状态 |
|------|------|------|
| **foggy-chat** | 聊天组件库（ChatPanel、useChatStore、消息组件） | 已完成 |
| **navigator-frontend** | Navigator 前端应用（Vue 3 + Element Plus + Pinia） | 已完成 |

### 3.4 外部服务（Docker 容器）

| 服务 | 职责 | 集成方式 |
|------|------|---------|
| **foggy-data-mcp-bridge** | 语义层 MCP 服务（TM/QM 建模、数据查询） | Docker 端口 7108 |
| **Claude Code Worker** | 远程 Claude Code SDK 封装（FastAPI） | Docker 端口 3031 |
| **OpenHands** | 编程 Agent 运行时 | Docker 容器 |

---

## 4. 关键设计模式

### 4.1 SPI Facade 模式

模块间通过 `navigator-spi` 中的接口通信，避免直接依赖：

```
tutor-agent  ──→  CodingAgentFacade (SPI)  ←──  coding-agent (实现)
tutor-agent  ──→  ClaudeWorkerFacade (SPI)  ←──  claude-worker-agent (实现)
```

### 4.2 BuiltInTool 模式

Agent 的工具以 Spring Bean 形式注册：

```java
@Slf4j @Component @RequiredArgsConstructor
public class DispatchCodingTaskTool implements BuiltInTool {
    private final CodingAgentFacade codingAgentFacade; // 注入 SPI
    // ...
}
```

### 4.3 Agent 配置持久化

- `JpaAgentRegistry`（session-module）从数据库加载 Agent 配置
- `TutorAgentRegistrar` 从 YAML seed 数据初始化（DB 不存在时）
- `InMemoryAgentRegistry` 作为 `@ConditionalOnMissingBean` 回退

### 4.4 LLM 韧性

- **超时**：60 秒默认
- **重试**：最多 2 次，指数退避
- **熔断器**：5 次失败后开启，30 秒冷却期

---

## 5. 可观察性

### 5.1 当前已实现

| 能力 | 实现方式 |
|------|---------|
| **TraceId** | TraceIdFilter 生成 16 位 hex ID → MDC，跨异步线程传播 |
| **Agent 日志** | DefaultAgentInvoker 记录每轮 LLM 调用详情（model、duration、tokens、toolCalls） |
| **结构化日志** | SLF4J + MDC（traceId、sessionId、agentId） |

### 5.2 待实现

| 能力 | 计划 |
|------|------|
| **Micrometer 指标** | Agent 请求计数、LLM token 使用量、响应时间分布 |
| **Prometheus 导出** | `/actuator/prometheus` 端点 |

---

## 6. 技术栈

| 组件 | 技术选型 |
|------|---------|
| 后端框架 | Spring Boot 3.x |
| AI 框架 | LangChain4j |
| 数据库 | MySQL 8.0+ |
| 认证 | JWT (jjwt) |
| 前端 | Vue 3 + Element Plus + Pinia + Vite |
| 前端推送 | SSE (Server-Sent Events) |
| Skill 解析 | Commonmark (Markdown) |
| 加密 | Spring Security Crypto (AES-256) |
| 测试 | JUnit 5 + Mockito / Vitest |

---

## 7. 演进路线

### 已完成

- Agent 框架核心（配置化 Agent、Skill 系统、工具执行循环）
- 导师 Agent + 6 个 Skill
- OpenHands 编程 Agent 集成
- Claude Worker Agent（远程 Claude Code）
- 会话管理 + SSE 实时推送
- JWT 认证
- 前端聊天界面
- LLM 韧性（超时、重试、熔断）
- TraceId 全链路追踪

### 近期计划

- 语义层服务接入（foggy-data-mcp-bridge 作为 Docker Addon）
- Micrometer 指标采集
- AI 分身能力

### 远期展望

- 外部工具集成（ClawdBot 等）
- 记忆系统（长期记忆、向量检索）
- RAG 增强
- 多租户 / 企业级部署

---

## 8. 参考文档

| 文档 | 说明 |
|------|------|
| [Agent 框架设计](./agent-framework-requirements.md) | 公共 Agent 框架接口与实现要求 |
| [Agent 框架指南](./agent-framework-guide.md) | 框架使用指南 |
| [导师 Agent 设计](./tutor-agent-design.md) | 导师 Agent 详细设计 |
| [会话模块设计](./02-modules/session-module.md) | 会话与消息管理设计 |
| [编程 Agent 集成](./02-modules/coding-agent-integration.md) | OpenHands 集成方案 |
| [可观察性系统](./02-modules/observability-system.md) | 监控与追踪设计 |

---

**文档版本**: 3.0.0
**更新日期**: 2026-02-09
**重大变更**: 从"AI 数据分析平台"转型为"个人 AI Agent 编排中枢"，移除数据源/语义层配置模块
