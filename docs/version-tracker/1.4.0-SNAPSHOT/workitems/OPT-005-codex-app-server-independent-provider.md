# OPT-005 Codex App Server 独立 Provider 与 Worker 配置拆分

## 文档作用

- doc_type: architecture-workitem
- intended_for: execution-agent | reviewer | acceptance-owner
- purpose: 固化 Codex SDK Worker 与 Codex App Server Worker 的 Provider、模型后端、Endpoint/Runtime 控制面和会话边界拆分方案。

## 基本信息

- version: `1.4.0-SNAPSHOT`
- priority: P0
- status: isolated-signed-off-production-not-approved
- source_type: architecture-refactor
- decision_date: `2026-07-12`
- owner: Agent Provider | Session | Codex Worker addon | Physical Worker management | Navigator PC
- supersedes_forward_design: OPT-001 中“`SDK_EXEC` 与 `APP_SERVER` 共用 `OPENAI_CODEX` / `codex-worker` Provider”的后续演进路线

## 2026-07-12 配置源决策修订

独立 Worker Backend、Provider 与 Session 拆分已完成隔离签收；Endpoint/Runtime 控制面沿用 [OPT-006](./OPT-006-codex-app-server-endpoint-runtime-sync.md) 已建立的基线，并已纳入本事项最终验收：

- App Server Endpoint Profile 作为独立于 Runtime 的 owner-bound 配置资源，保存 endpoint 与加密服务令牌，支持增删改；
- 点击“同步”从 endpoint 读取 capability manifest，以配置/能力指纹决定保留 Runtime 或创建新 revision；
- Runtime 只保存同步后不可变连接快照、能力状态和会话 affinity，不能反向编辑 Endpoint；
- 新 revision 固定 Disabled + Dark，旧同步 revision 停止新任务路由。

最终配置源规则为：`CodexAppServerEndpoint` 是 App Server endpoint/token 的唯一人工写源；Physical Worker 只提供 owner/capability 归属和 Endpoint 管理入口，不新增 `physicalWorker.codexAppServerConfig`；Runtime 不提供手工 endpoint/token 注册或编辑入口。OPT-005 与 OPT-006 不再作为互斥方案。

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

### 4. 物理 Worker 与 Endpoint 配置拆分

“添加/编辑物理 Worker”中的能力配置拆成独立页签：

| 页签 | 配置对象 | 主要字段 |
|---|---|---|
| Codex | `codexConfig` | SDK Worker `baseUrl`、`authToken`、默认模型 |
| Codex App Server | `CodexAppServerEndpoint` 管理入口 | owner-bound App Server `endpoint`、加密服务令牌、同步状态 |
| Gemini | `geminiConfig` | Gemini Worker `baseUrl`、`authToken`、默认模型 |

规则：

- `codexConfig` 只能服务 `OPENAI_CODEX` / `codex-worker`。
- `CodexAppServerEndpoint` 只能服务 `OPENAI_CODEX_APP_SERVER` / `codex-app-server-worker`。
- SDK Worker 配置与 App Server Endpoint Profile 的 endpoint、token、health、capability 和 readiness 独立保存、独立测试、独立展示。
- 不允许用一个 `codexConfig` 字段根据模型或 runtime 类型解释成两种 endpoint，也不允许再新增与 Endpoint Profile 并列的 App Server 写源。
- 当前基线使用 `CodexAppServerEndpoint` 作为按 physical worker 归属的独立 Endpoint Profile；它是 App Server endpoint/token 的人工管理配置源。
- Runtime registry 保留同步后 capability、revision、instance affinity、连接快照和运行状态；Runtime 页面不提供 Endpoint/token 编辑入口。
- Endpoint Profile 与 Physical Worker 生命周期/权限关联，但不把 endpoint/token 嵌入 Physical Worker 的单一配置对象。
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
       -> owner-bound CodexAppServerEndpoint
       -> endpoint-synced APP_SERVER runtime revision
       -> Codex App Server Worker
       -> App Server Thread/Turn
```

路由结果由模型配置和 Session Provider 直接确定，不再通过模型档位、灰度比例或 runtime registry 在两个执行协议之间二次选择。

## 数据与契约设计

### ModelConfig

- `workerBackend` 增加 `OPENAI_CODEX_APP_SERVER`。
- `OPENAI_CODEX` 与 `OPENAI_CODEX_APP_SERVER` 分别校验各自允许模型集合。
- 相同 API Base URL/API Key 可以由用户分别配置，但平台不自动复制或合并两个 ModelConfig。

### Physical Worker 与 Endpoint Profile

- 当前实现不新增 `CodexAppServerConfig`；Physical Worker 通过 `workerId` 归属 Endpoint Profile。
- Endpoint DTO 只返回 token 是否已配置，不回显明文；更新时留空保持 token，显式清除才删除 token。
- Physical Worker 编辑窗口中的 App Server 页签直接管理或链接 Endpoint Profile，不能重新引入第二个可写 endpoint/token 真相。

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

## 迁移边界与旧数据

本事项按用户确认采用新基线设计，明确不把以下内容纳入范围：

- 不保证既有 Session、Provider State 或 Thread 在跨 Provider 拆分后可继续恢复。
- 不提供 `OPENAI_CODEX -> OPENAI_CODEX_APP_SERVER` 的 ModelConfig 自动迁移。
- 不提供旧 `codexConfig` 到 App Server Endpoint Profile 的自动复制或推断。
- 不做 legacy dual-read、N-1 API 兼容或运行时推断；允许通过一次性迁移把既有 Task 按已持久化 runtime 类型分类回填 `provider_type`，该回填不是兼容路由或 fallback。
- 不保留同 Provider 双 Runtime 的路由语义。
- 不要求升级后继续打开或续接旧 Codex 会话。

实施环境可以清理并重建相关开发数据，但任何实际清库或 destructive schema 操作仍须在执行阶段明确列出并单独确认；本文本身不执行数据修改。

## 模块职责与代码触点

| 模块 | 目标改动 |
|---|---|
| `navigator-common` | 增加 `OPENAI_CODEX_APP_SERVER` 后端常量及 Backend/Provider 一一映射 |
| `navigator-spi` / `session-module` | 注册并解析 `codex-app-server-worker`；按 Provider 绑定和分派 Session/Task |
| `addons/codex-worker-agent` | 拆分 SDK Provider 与 App Server Provider；提取公共 Codex 投影能力；移除跨协议 runtime 选择 |
| `addons/claude-worker-agent` | 保持 SDK Worker 配置；Physical Worker 只承载 App Server Endpoint Profile 的 owner/capability 归属，不新增第二套写源 |
| `tools/codex-agent-worker` | 只承担 SDK Provider；拒绝 App Server/Ultra 请求 |
| `tools/codex-app-server-worker` | 只承担 App Server Provider；保留自身 task store、pool 和 instance affinity |
| `packages/navigator-frontend` | 模型后端增加 App Server；物理 Worker 增加独立页签；任务与会话显示独立 Provider 标签 |
| `packages/foggy-chat-core` / `foggy-chat` / Mobile | 如展示 Provider/模型后端，补充 App Server 标签和新会话提示 |
| docs/tests | 更新架构说明、契约测试、Provider 分派测试和 UI E2E |

OPT-006 已将 App Server endpoint entity/controller/service、Runtime 同步字段和 PC 管理界面提交为 `37dff8b9`。本事项在该基线上继续实施独立 Provider，并移除手工 Runtime endpoint/token 写入口；不得退回到同 Provider 双 Runtime 或 Physical Worker 内嵌第二份 App Server 配置。

## UI 交互要求

### AI 模型设置

- Worker 后端单选项增加 `OpenAI Codex App Server`。
- 切换 Worker 后端时重新计算模型与 reasoning 可选集合。
- Ultra 不在 `OpenAI Codex` 下出现。
- 保存后列表明确展示 `Codex` 或 `Codex App Server`，不能都显示为 `Codex`。

### 物理 Worker 设置

- `Codex` 和 `Codex App Server` 为两个并列页签。
- Codex 页签提供 SDK Worker 配置与状态；Codex App Server 页签提供 Endpoint Profile 管理、连接/同步状态与错误提示。
- 一个物理 Worker 可以只具备其中一种能力，也可以同时具备两种能力。
- 未配置 App Server 的 Worker 不能被 App Server ModelConfig 授权或选中。
- 删除 App Server Endpoint Profile 只停止对应 App Server 新路由，不影响 SDK Worker 配置；历史 Runtime affinity 仍按控制面规则保留。

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

- Physical Worker 可保存 SDK Worker 配置，并在独立页签管理其 owner-bound App Server Endpoint Profile。
- SDK 认证与 App Server Endpoint 服务令牌独立；App Server 令牌加密保存且 DTO 不回显明文。
- SDK 连接测试与 App Server Endpoint 探测/readiness 独立，错误不会互相覆盖。
- Worker 过滤严格按 ModelConfig 的 Worker Backend 匹配 SDK 配置或 App Server Endpoint/capability。
- 系统中不存在与 `CodexAppServerEndpoint` 并列的第二套人工可写 App Server endpoint/token 配置；Runtime endpoint/token 只能由同步派生。

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

- status: completed-reviewed
- [x] 冻结 Backend/Provider 一一映射与 Endpoint Profile 单一写源规则。
- [x] 拆分 SDK/App Server Provider 注册，并在 Task/Session 分派中增加 Provider 边界与 fail-closed 约束。
- [x] 保留 OPT-006 Endpoint Profile，移除面向用户的手工 Runtime endpoint/token 注册入口。
- [x] 拆分 SDK/App Server 模型配置与能力目录；Ultra 仅属于 App Server Backend。
- [x] 完成并复核 PC/Mobile Provider 标签、跨 Provider 新会话、物理 Worker App Server 页签及异常状态。
- [x] 完成全仓旧同 Provider 双 Runtime 活动路由与第二写源清理审计。
- [x] ModelConfig、Agent defaultModel、OpenAPI readiness 均按实际 workspace Worker/具体模型执行 fail-closed capability 与授权校验。
- [x] 最终独立审查关闭 workspace Worker 路由分叉、Agent 保存竞态、Mobile 续接测试和 SPI 源码兼容问题；无剩余 blocker。

### Testing

- status: passed
- [x] Java 16-module reactor：`2095` tests，`0` failure/error；其中 Session `322`、Codex addon `337`、Claude addon `347`、Metadata Config `51`、Launcher `6`。
- [x] SDK Worker `124/124`，typecheck/build 通过；App Server Worker `251 total / 244 passed / 7 skipped / 0 failed`，typecheck/build 通过。
- [x] Navigator PC `237/237`、type-check、workspace production build 通过；Mobile `55/55`、type-check、H5 与微信小程序构建通过。
- [x] Playwright `7/7`：Backend/模型目录、跨 Provider 新会话、Endpoint/Runtime、Ultra 子任务、安装帮助、desktop/320px 全通过。
- [x] MySQL `8.0.44` 与 `8.4.8` Provider/runtime affinity migration harness 全通过，各迁移 `25` 个 Task。
- [x] 当前源码真实双链：SDK 与 App Ultra 均 COMPLETED；统一 SSE 同时观测两 Provider；双向故障均无 fallback。
- [x] Launcher `mvn package -pl launcher -am -DskipTests` 通过，制品 `90,191,742` bytes。

### Experience

- status: isolated-accepted
- 页面可达性：模型设置与物理 Worker Tab 能清晰区分 SDK 和 App Server，Endpoint 是唯一 App Server 人工写源。
- 核心交互：跨 Provider 续接会先提示并创建新 Session；取消不发送请求；同 Provider 仍原位续接。
- 表单验证：PC capability 请求 pending 时禁止保存；后端对具体模型和 workspace Worker 再次 fail-closed。
- 异常状态：App Runtime Dark 时创建被拒绝且未产生 SDK Session；SDK endpoint 故障时 Task 固定为 SDK Provider，App Worker 查询为 `404`。
- 权限可见性：token 只显示配置状态；OpenAPI readiness 与实际 launch 使用相同 workspace Worker 和 ModelConfig grant。
- 数据一致性：刷新后历史同时保留 `Codex SDK` 与 `Codex App Server` badge，原生 Thread/Runtime 类型与 Provider 一致。

| Playwright 用例 | 覆盖维度 | 状态 |
|---|---|---|
| AI 模型后端拆分 | 后端选项、模型集合、保存回显 | passed |
| 物理 Worker SDK/Endpoint 页签 | 独立保存、清除、连接与同步状态 | passed |
| 跨 Provider 创建新会话 | 阻止错误续接、创建新 Session | passed |
| 桌面与 320px | 标签、表单和提示布局 | passed |

体验截图见 `../evidence/OPT-005-*.png`、`../evidence/OPT-006-*.png`；真实链路与刷新证据见 `../evidence/opt-005-provider-fullchain-20260712-033210-be7f26ac/`。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-07-12
- acceptance_record: `docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md`
- blocking_items: none
- follow_up_required: yes
- production_enablement: not-approved

## 后置流程

实现完成后依次执行：

1. `execution-checkin` 更新本页 Progress Tracking。
2. `foggy-implementation-quality-gate` 正式质量检查，写入 `../quality/OPT-005-codex-app-server-independent-provider-implementation-quality.md`。
3. `foggy-test-coverage-audit` 测试证据覆盖审计，写入 `../coverage/OPT-005-codex-app-server-independent-provider-coverage-audit.md`。
4. `foggy-acceptance-signoff` 功能验收，写入 `../acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md`；只有在前两项和体验证据完成后才可写签收结论。
5. OPT-006 的 Endpoint/Runtime 专项证据可合并进入上述联合文档，但必须在矩阵中保留独立 requirement 行；如需单独结论，使用相同目录下 `OPT-006-codex-app-server-endpoint-runtime-sync-*` 文件名。

## 参考

- [版本索引](../README.md)
- [OPT-001 原独立 Worker 需求](./OPT-001-independent-codex-app-server-worker-requirement.md)
- [A2A Agent 架构](../../../a2a-agent-architecture.md)
