# OPT-005 Codex App Server 独立 Provider 与 Worker 配置拆分

## 文档作用

- doc_type: architecture-workitem
- intended_for: execution-agent | reviewer | acceptance-owner
- purpose: 固化 Codex SDK Worker 与 Codex App Server Worker 的 Provider、模型后端、物理 Worker 配置和会话边界拆分方案。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P0
- status: design-confirmed-implementation-not-started
- source_type: architecture-refactor
- decision_date: `2026-07-12`
- owner: Agent Provider | Session | Codex Worker addon | Physical Worker management | Navigator PC
- supersedes_forward_design: OPT-001 中“`SDK_EXEC` 与 `APP_SERVER` 共用 `OPENAI_CODEX` / `codex-worker` Provider”的后续演进路线

## 背景与问题

当前实现把两套不能互相续接的执行协议放在同一个外部身份下：

- 模型配置统一使用 `workerBackend=OPENAI_CODEX`；
- 普通会话统一使用 `providerType=codex-worker`；
- Java 内部再选择 `SDK_EXEC` 或 `APP_SERVER` runtime；
- Session 接受首个任务后绑定具体 runtime，后续不得跨 runtime resume。

该设计适合隐藏式灰度，但不适合两种 Worker 长期并存并由用户明确选择。SDK Thread 与 App Server Thread/Turn 的创建、恢复、事件、能力和运维边界不同，却在模型配置和会话中表现成同一个 Provider，导致以下问题：

1. 用户看到的是同一个 OpenAI Codex 后端，但部分模型切换实际要求创建另一种会话。
2. `providerType=codex-worker` 不能直接表达任务真实执行协议。
3. 物理 Worker 的 Codex 配置无法区分 SDK Worker endpoint 与 App Server Worker endpoint。
4. Runtime 路由、Provider 路由和模型授权三层语义叠加，增加诊断与运维复杂度。
5. App Server 的独立发布、状态、能力和会话边界没有在平台领域模型中得到同等独立表达。

## 术语与命名

| 术语 | 稳定含义 | 不是 |
|---|---|---|
| Worker Backend | ModelConfig 选择的执行器类别 | OpenAI API 服务商或物理 Worker 实例 |
| A2A Provider | Navigator 中负责 Agent 发现、Session 绑定和 Task 分派的执行协议适配器 | LLM API Provider |
| Physical Worker | Navigator 管理的工作机/执行环境记录，可挂载多个执行能力 | 某一次 Codex runtime instance |
| Codex Worker | `tools/codex-agent-worker`，使用 SDK / `codex exec` | Codex App Server Worker |
| Codex App Server Worker | `tools/codex-app-server-worker`，使用 Codex app-server | SDK Worker 的一个运行模式 |
| App Server Runtime Instance | App Server Worker 内部用于承载 Thread/Turn 的受控进程实例 | 可由用户独立配置的第二个 Worker endpoint |

对外显示名、契约值和代码命名统一使用本文表格中的映射，不再用裸 `Provider` 表示 OpenAI 模型服务商。

## 已确认决策

### 1. 独立 Worker Backend

模型配置新增独立后端：

| 显示名称 | `workerBackend` | 执行器 |
|---|---|---|
| OpenAI Codex | `OPENAI_CODEX` | `tools/codex-agent-worker`，SDK / `codex exec` |
| OpenAI Codex App Server | `OPENAI_CODEX_APP_SERVER` | `tools/codex-app-server-worker`，Codex app-server |

两者属于同一 OpenAI Codex 产品族，但不是同一个可续接执行后端。

### 2. 独立 Provider Type

普通编程会话拆分为两个 Provider：

| `providerType` | Worker Backend | 会话协议 |
|---|---|---|
| `codex-worker` | `OPENAI_CODEX` | SDK Thread |
| `codex-app-server-worker` | `OPENAI_CODEX_APP_SERVER` | App Server Thread/Turn |

`CodexWorkerAgentProvider` 只处理 SDK Worker；新增 `CodexAppServerWorkerAgentProvider`，只处理 App Server Worker。两个 Provider 可以复用公共 Codex Task、SSE、消息投影和模型目录组件，但不得在 Provider 内互相 fallback。

`codex-biz-worker` 不在本事项中自动改为 App Server。后续若业务 Agent 需要 App Server，应单独定义业务 Provider 和能力验收，不复用普通编程 Provider 进行隐式切换。

### 3. 会话边界

- Session 首次创建时直接绑定 Provider，不再先绑定统一 Codex Provider、再选择内部 runtime。
- `codex-worker` 会话只能续接 SDK Thread。
- `codex-app-server-worker` 会话只能续接 App Server Thread/Turn。
- 两种会话不能原生切换、迁移 Thread 或自动重放 prompt。
- 用户切换到另一 Worker Backend 时必须创建新 Session。
- 允许未来提供显式“复制上下文到新会话”，但它不是 resume，也不得携带原生 Thread ID。

### 4. 物理 Worker 配置拆分

“添加/编辑物理 Worker”中的能力配置拆成独立页签：

| 页签 | 配置对象 | 主要字段 |
|---|---|---|
| Codex | `codexConfig` | SDK Worker `baseUrl`、`authToken`、默认模型 |
| Codex App Server | `codexAppServerConfig` | App Server Worker `baseUrl`、`authToken` |
| Gemini | `geminiConfig` | Gemini Worker `baseUrl`、`authToken`、默认模型 |

规则：

- `codexConfig` 只能服务 `OPENAI_CODEX` / `codex-worker`。
- `codexAppServerConfig` 只能服务 `OPENAI_CODEX_APP_SERVER` / `codex-app-server-worker`。
- 两个 endpoint、token、health、capability 和 readiness 独立保存、独立测试、独立展示。
- 不允许用一个 `codexConfig` 字段根据模型或 runtime 类型解释成两种 endpoint。
- `physicalWorker.codexAppServerConfig` 是 App Server endpoint/token 的唯一管理配置源。
- Runtime registry 可以保留 App Server Provider 内部的 capability、revision、instance affinity 和运行状态，但不得再提供另一套可独立编辑的 endpoint/token 配置真相。
- 现有独立 App Server endpoint CRUD 若与 Physical Worker 配置重复，实施时必须删除或收敛为由 Physical Worker 配置派生的内部只读状态。
- App Server 的模型与 reasoning 能力以 capability manifest 为准；物理 Worker 表单不维护第二份静态默认模型真相。
- App Server runtime revision/instance 管理只管理 App Server Provider 内部实例，不再承担 SDK 与 App Server 之间的路由选择。

### 5. 模型配置界面

“编辑 AI 模型”的 Worker 后端增加独立选项 `OpenAI Codex App Server`。

- 选择 `OpenAI Codex` 时，只展示 SDK Worker 已支持的模型与 reasoning 档位。
- 选择 `OpenAI Codex App Server` 时，只展示 App Server capability 与平台授权共同允许的模型与 reasoning 档位。
- Ultra 只允许配置在 `OPENAI_CODEX_APP_SERVER` 下。
- SDK 与 App Server 模型配置分别授权到具有对应物理能力配置的 Worker。
- 测试连接必须调用所选执行后端，不允许测试 SDK 成功后推断 App Server 可用，反之亦然。

## 目标路由

```text
ModelConfig.workerBackend
  ├─ OPENAI_CODEX
  │    -> providerType=codex-worker
  │    -> physicalWorker.codexConfig
  │    -> Codex SDK Worker
  │    -> SDK Thread
  │
  └─ OPENAI_CODEX_APP_SERVER
       -> providerType=codex-app-server-worker
       -> physicalWorker.codexAppServerConfig
       -> Codex App Server Worker
       -> App Server Thread/Turn
```

路由结果由模型配置和 Session Provider 直接确定，不再通过模型档位、灰度比例或 runtime registry 在两个执行协议之间二次选择。

## 数据与契约设计

### ModelConfig

- `workerBackend` 增加 `OPENAI_CODEX_APP_SERVER`。
- `OPENAI_CODEX` 与 `OPENAI_CODEX_APP_SERVER` 分别校验各自允许模型集合。
- 相同 API Base URL/API Key 可以由用户分别配置，但平台不自动复制或合并两个 ModelConfig。

### Physical Worker

- 新增独立 `CodexAppServerConfig` 数据结构。
- Worker DTO 分别返回 SDK/App Server endpoint 是否配置、认证是否配置及最小健康状态。
- Register/Update Form 分别接受 `codexConfig` 与 `codexAppServerConfig`。
- 两套配置分别加密 token，不共享“留空保持不变”状态判断。
- App Server endpoint/token 的写入口只存在于 Physical Worker Register/Update；内部 registry 通过 workerId 引用或派生，不重复接受人工配置。

### Session Provider State

- `codex-worker` 保存 SDK `codexThreadId`。
- `codex-app-server-worker` 保存 App Server `threadId`、必要的 `turnId`/任务绑定和 App Server runtime instance affinity。
- Provider state 不再用 `codexRuntimeType` 判断 SDK 或 App Server Provider。
- App Server Provider 内仍可保存 `runtimeId + revision + instanceId`，用于同 Provider 内的实例粘性和恢复。

### Task

- `providerType` 直接表达 SDK 或 App Server。
- SDK Task 不写 App Server runtime binding。
- App Server Task 必须写 App Server runtime/revision/instance/workerTaskId binding。
- subscribe/status/abort/delete 按 `providerType` 进入对应 Provider，不允许运行时改派。

## 不考虑兼容性与旧数据

本事项按用户确认采用新基线设计，明确不把以下内容纳入范围：

- 不兼容既有 Session、Task、Provider State 或 Thread 绑定。
- 不提供 `OPENAI_CODEX -> OPENAI_CODEX_APP_SERVER` 的 ModelConfig 自动迁移。
- 不提供旧 `codexConfig` 到 `codexAppServerConfig` 的自动复制或推断。
- 不做 legacy dual-read、backfill、N-1 数据兼容或旧 API 字段兼容。
- 不保留同 Provider 双 Runtime 的路由语义。
- 不要求升级后继续打开或续接旧 Codex 会话。

实施环境可以清理并重建相关开发数据，但任何实际清库或 destructive schema 操作仍须在执行阶段明确列出并单独确认；本文本身不执行数据修改。

## 模块职责与代码触点

| 模块 | 目标改动 |
|---|---|
| `navigator-common` | 增加 `OPENAI_CODEX_APP_SERVER` 后端常量及 `CodexAppServerConfig` 公共配置模型 |
| `navigator-spi` / `session-module` | 注册并解析 `codex-app-server-worker`；按 Provider 绑定和分派 Session/Task |
| `addons/codex-worker-agent` | 拆分 SDK Provider 与 App Server Provider；提取公共 Codex 投影能力；移除跨协议 runtime 选择 |
| `addons/claude-worker-agent` | Physical Worker Entity/Form/DTO/Service 增加独立 App Server 配置、加密、readiness 与诊断 |
| `tools/codex-agent-worker` | 只承担 SDK Provider；拒绝 App Server/Ultra 请求 |
| `tools/codex-app-server-worker` | 只承担 App Server Provider；保留自身 task store、pool 和 instance affinity |
| `packages/navigator-frontend` | 模型后端增加 App Server；物理 Worker 增加独立页签；任务与会话显示独立 Provider 标签 |
| `packages/foggy-chat-core` / `foggy-chat` / Mobile | 如展示 Provider/模型后端，补充 App Server 标签和新会话提示 |
| docs/tests | 更新架构说明、契约测试、Provider 分派测试和 UI E2E |

当前工作区已有未提交的 App Server endpoint entity/controller/service 与 runtime 字段改动。执行者必须先审阅并按“Physical Worker 配置是唯一 endpoint/token 配置源”的决策进行合并、改造或删除；禁止通过新增第二套配置页面同时保留两套可写配置源。

## UI 交互要求

### AI 模型设置

- Worker 后端单选项增加 `OpenAI Codex App Server`。
- 切换 Worker 后端时重新计算模型与 reasoning 可选集合。
- Ultra 不在 `OpenAI Codex` 下出现。
- 保存后列表明确展示 `Codex` 或 `Codex App Server`，不能都显示为 `Codex`。

### 物理 Worker 设置

- `Codex` 和 `Codex App Server` 为两个并列页签。
- 两个页签各自提供连接测试、状态与错误提示。
- 一个物理 Worker可以只配置其中一种，也可以同时配置两种。
- 未配置 App Server 的 Worker 不能被 App Server ModelConfig 授权或选中。
- 清空某一页签配置只删除该能力，不影响另一页签。

### 会话与任务

- 历史列表、任务详情和诊断信息显示真实 Provider 标签。
- 在已有会话中选择另一后端模型时，不提交续接请求；提示创建新会话。
- 创建新会话时保留工作目录和用户选择的模型配置，不携带原 Thread ID。

## 验收标准

### Provider 与路由

- `OPENAI_CODEX` 只解析为 `codex-worker`。
- `OPENAI_CODEX_APP_SERVER` 只解析为 `codex-app-server-worker`。
- 两个 Provider 均可独立发现、分派、续接、取消和查询状态。
- 任一 Provider 不可用时，不向另一个 Provider fallback。
- SDK Session 无法携带 App Server 模型继续；App Server Session 无法携带 SDK Provider 继续。

### Worker 配置

- Physical Worker 可分别保存、读取、更新和清除两套 Codex 配置。
- 两套认证令牌分别加密且不会在 DTO 中回显明文。
- 两套连接测试和 readiness 独立，错误不会互相覆盖。
- Worker 过滤严格按 ModelConfig 的 Worker Backend 匹配对应配置。
- 系统中不存在与 `codexAppServerConfig` 并列的第二套人工可写 App Server endpoint/token 配置。

### 模型配置

- 后端选项、模型目录、授权 Worker、测试连接和保存结果均能区分 SDK/App Server。
- Ultra 仅能绑定 App Server Backend。
- 同名模型配置在不同 Backend 下可并存且路由稳定。

### 会话与体验

- PC 桌面和窄屏均能清晰区分两个 Provider。
- 跨 Provider 切换出现“创建新会话”提示，不产生错误续接 Task。
- 新会话创建后 Provider、模型配置、物理 Worker endpoint 与原生 Thread 类型一致。
- 刷新、SSE 重连、取消、删除仍沿原 Provider 工作。

## 约束与非目标

- 本事项不实现 SDK Thread 与 App Server Thread 的转换。
- 本事项不自动复制旧会话上下文。
- 本事项不把 App Server endpoint 或 token 暴露给普通用户。
- 本事项不合并两套 Worker 发布物、端口、日志、状态目录或 Codex Home。
- 本事项不自动扩展 `codex-biz-worker`。
- 本事项不修改 Claude、Gemini 或 LangGraph Provider 的执行语义。

## Progress Tracking

### Development

- status: not-started
- [ ] 冻结 Backend、Provider、配置对象和标签命名。
- [ ] 拆分 Provider 注册与 Task/Session 分派。
- [ ] 拆分物理 Worker 配置和 readiness。
- [ ] 拆分模型配置与能力目录。
- [ ] 更新 PC、Chat/Mobile 展示与跨 Provider 新会话交互。
- [ ] 删除同 Provider 双 Runtime 路由入口。

### Testing

- status: not-run
- [ ] Provider resolver/registry 单元测试。
- [ ] Session binding 与跨 Provider 拒绝测试。
- [ ] Physical Worker 配置 CRUD、加密和独立清除测试。
- [ ] SDK/App Server client 与失败不 fallback 测试。
- [ ] 前端类型、模型目录、Worker 过滤和构建测试。
- [ ] Worker/Java/SSE 真实链路测试。

### Experience

- status: not-run
- 页面可达性：AI 模型设置和物理 Worker 编辑均能进入两种 App Server 配置入口。
- 核心交互：两套配置独立保存、测试、清除；跨 Provider 选择创建新会话。
- 表单验证：Backend 与 endpoint、模型能力、授权 Worker 一致。
- 异常状态：SDK/App Server 离线分别展示，不互相代替。
- 权限可见性：普通用户不看到 endpoint/token，Owner 可管理对应配置。
- 数据一致性：页面标签、Session provider、Task provider、实际 endpoint 和 Thread 类型一致。

| Playwright 用例 | 覆盖维度 | 状态 |
|---|---|---|
| AI 模型后端拆分 | 后端选项、模型集合、保存回显 | not-run |
| 物理 Worker 双 Codex 页签 | 独立保存、清除、连接状态 | not-run |
| 跨 Provider 创建新会话 | 阻止错误续接、创建新 Session | not-run |
| 桌面与 320px | 标签、表单和提示布局 | not-run |

## 后置流程

实现完成后依次执行：

1. `execution-checkin` 更新本页 Progress Tracking。
2. `foggy-implementation-quality-gate` 正式质量检查。
3. `foggy-test-coverage-audit` 测试证据覆盖审计。
4. `foggy-acceptance-signoff` 功能验收。

## 参考

- [版本索引](../README.md)
- [OPT-001 原独立 Worker 需求](./OPT-001-independent-codex-app-server-worker-requirement.md)
- [A2A Agent 架构](../../../a2a-agent-architecture.md)
