---
type: requirement
version: 1.4.2-SNAPSHOT
ticket: REQ-001
priority: high
status: in-progress
decision_status: review-complete
source: project-governance-iteration
owner: root-workspace
---

# 平台治理与历史能力收口

## 文档作用

- doc_type: requirement
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 冻结 1.4.2 的产品定位、信任边界、构建与清理目标、非目标和验收标准。

## 背景

Foggy Navigator 已形成覆盖多 Provider、多 Worker、Session/Task、文件、Git、工作目录、终端、A2A、Open SDK、ClientApp 和 upstream user 的内部远程编程工作台。历史迭代同时留下了产品定位漂移、内外部信任边界不够清晰、构建不可完全复现、兼容面持续累积、模块职责偏重和旧文档失效等问题。

本需求不把这些问题收敛为一次全平台重写，而是建立一组可分阶段执行、可回滚、可审计的治理不变量：内部控制面继续保持适合内部系统的轻量模式；外部运行面必须具备明确身份、资源作用域和审计链路；删除和迁移必须有证据门禁；大型模块和状态契约按版本渐进治理。

## 与版本目标的关系

本需求是 `1.4.2-SNAPSHOT` 的总需求，统领 10 个计划工作项、实施期间按证据新增的缺陷记录和 P0-P7 八个阶段；当前另有 BUG-001、BUG-002 两个已关闭缺陷记录。各工作项可以独立实施和签收，但不得偏离本文冻结的产品定位、信任边界、非目标与验收门禁。八组 Owner 决策集中记录在 [Owner 决策评审稿](../owner-decision-review.md)，当前状态为 `review-complete`：ODR-142-002 以“signed assertion 降为低优先级后续项、external 显式开关保持硬门”为约束，其余事项按 Owner 结论批准。Owner 决策已经触发 P0、P1、P2 首批模式门禁和 P5 开工，版本状态为 `in-progress`；这不表示全部实现完成、验收通过、生产启用或外部开放。

## 产品定位

Foggy Navigator 当前是内部系统，核心目标是：

1. 多 Worker 远程编程工作台；
2. 统一 Session、Task 和 A2A 治理；
3. Claude、Codex、Gemini、LangGraph 等 Provider 接入；
4. 文件、Git、工作目录、终端和跨项目协作；
5. Business Agent、Open SDK、ClientApp 和 upstream user 集成。

当前不是语义层或数据分析平台。Owner 已批准物理删除旧 `metadata-query-module`，同时明确保留 `metadata-config-module`；二者不得因相邻命名被混为同一能力，也不能反向改变产品主线。

## 术语

| 术语 | 定义 | 本版本边界 |
|---|---|---|
| 内部控制面 | Navigator 内部 UI、可信内网 API、开发与管理员操作面 | 保持轻量认证，补资源 ownership，不做全局安全框架重写 |
| 外部运行面 | Biz Worker、Worker Gateway、ClientApp、Open SDK、upstream user 触发的调用面 | 身份可确认、凭据有作用域、任务/函数受限、默认 fail closed |
| LangBizWorker / CodexBizWorker | LangGraph/Codex 面向 BusinessTask 的外部 Worker 运行模式 | 受 external-enabled、task token、目录/工具和 Gateway 约束；不等同内部开发模式 |
| upstream user | 由上游系统映射到 Navigator tenant/ClientApp 语境的外部用户 | 不直接信任请求字段，映射和 grant 必须有权威来源 |
| task-scoped token | 仅允许访问指定任务与允许 BusinessFunction 的短期凭据 | 必须有有效期、撤销、轮换和审计，不得横向访问 |
| ownership 不变量 | 资源操作必须同时满足资源归属和调用主体约束 | 优先落在统一 service/facade 层，不散落到每个 Controller |
| 完整功能切片退役 | 同一能力的模块、前端、API、配置、脚本、部署和文档成组退出 | 禁止只删局部文件造成残留或隐式故障 |

## 证据分类与使用规则

| 分类 | 当前内容 | 使用限制 |
|---|---|---|
| 已确认事实 | 本文产品定位、版本目标、治理边界和非目标 | 可作为需求基线，不代表代码已实现 |
| 用户提供的静态线索 | 单 JVM SSE、addon 编译期依赖、重类、前端类型错误、lockfile 忽略、候选孤儿文件等 | 执行 Agent 必须复核路径、引用和当前分支状态 |
| 本轮构建实施与可复现证据 | 已落地 Node `22.23.1`、pnpm `10.34.5`、根 lockfile、前端 workspace、required 候选/nightly workflow；精确 frozen install、前端、五类 Worker clean 等价矩阵通过；Navigator-owned `RX` 兼容层消除了 hosted clean runner 无法解析私有 `foggy-core` 的阻塞；Repository CI run `29323068427` 在 head `9008c554` 的 Java launcher 依赖链、前端、三类 Node Worker、两类 Python Worker共 7 个 job 全部 `success` | hosted 成功证明该提交的 Repository CI 基线；main required checks/branch protection 未配置，修复后 nightly、根 `clean verify` 和生产批准均未完成；本机 Java 日志仍有 launcher Surefire fork JVM 退出超时告警 |
| 本轮浏览器与隔离数据库证据 | `9d03bee9` 新增显式 opt-in 的双用户 ownership live 用例；一次性 H2、真实注册/登录 UI、Session API、深链、history 和 SSE 上 1 test passed（2.9s，整次 3.9s）；同轮 mock Playwright 为 17 passed、1 live test skipped（35.2s） | 仅覆盖 Session ownership 的 loopback 隔离链路；未使用共享数据库，未建立真实 Provider Task fixture，不代表共享环境、外部网络、生产路由或正式验收通过 |
| P2/P3 分阶段实施与测试证据 | P2 已落地平台/三类 Worker default-off、external-enabled、readiness、task capability/终态门禁、Worker principal/lease 与 Gateway约束；P3 `2a705e09` 已落地 Session/Task ownership 首批，`9f3f1422` 将 LangGraph 审批切到 unified respond 的可信主体；本机 clean Java、Worker 矩阵、hosted Repository CI 与 Session 双用户隔离浏览器均已有通过证据 | external 仍未启用；ClientApp/upstream user 外部强身份、可靠审计/outbox、external 工具/目录/网络上限、真实 Provider Task、共享数据库和显式 admin/system 通路尚未完成。局部通过不能提升 external readiness 或版本验收状态 |
| Owner 已确认的开发环境边界 | Monitoring、metadata-query、code-review、echo 与旧 Provider API 不承担上游或生产兼容义务；开发数据可丢弃，允许按完整切片物理删除 | 免除生产流量静默、数据备份/保留和兼容窗口；不免除 dev-only 环境确认、完整 inventory、仓内引用迁移、测试和回滚记录 |
| 需要运行态确认 | 执行中发现的共享基础设施、生产部署、外部 webhook/调用方或无法归属的数据库、队列和凭据 | 一旦发现即停止对应破坏性动作并重新取得 Owner 授权，不把本次 dev-only 结论扩展到生产 |
| 决策项 | 八组 ODR 已完成评审；credential authority、mapping/grant 权威源、Provider state 具体迁移和超大类拆分顺序仍属于实施级决策 | 已批准项按评审约束执行；剩余实施级决定必须记录 Owner、日期和影响，不得由执行 Agent静默决定 |

## 总体目标

1. 统一当前设计目标和产品定位。
2. 明确内部控制面与外部运行面的信任边界。
3. 在不重构整个鉴权框架的情况下，补齐必要的资源归属和外部调用约束。
4. 治理 LangBizWorker、CodexBizWorker、upstream user 的凭据、身份、tenant、ClientApp、资源作用域和审计链路。
5. 恢复可复现的 Java、前端和 Worker 构建基线。
6. 对旧模块、孤儿代码、兼容 API 和失效文档分级清理。
7. 为超大类、模块边界和 Provider 状态契约制定渐进治理计划。
8. 建立后续删除、迁移和验收的明确门禁。

## 内部控制面需求

1. 保留当前轻量认证模式，不通过大规模 Spring Security 或全局 `SecurityConfig` 重构解决本版本问题。
2. Session、Task 等用户数据必须增加必要的 ownership 校验。
3. 不允许仅凭 `sessionId` 或 `taskId` 跨用户读取、恢复、取消、审批或修改资源。
4. ownership 规则优先收敛到统一 service/facade 层，Controller 只负责调用和请求边界，不各自发明归属语义。
5. 内部开发与管理员能力可以保留，但必须标注可信内网假设、配置前提和不适合外部暴露的接口。
6. 内部 UI 和现有可信内网工作流必须有回归矩阵，避免治理导致大面积不可用。

## 外部运行面需求

### 身份与映射

1. LangBizWorker、CodexBizWorker、Worker Gateway、ClientApp 和 upstream user 的调用方身份必须可确认。
2. tenant、ClientApp 与 upstream user mapping/grant 必须有明确权威来源、唯一性规则和失效语义。
3. 不得直接信任请求体中的 `userId`、`reviewedBy`、`tenantId` 或同类身份字段；这些字段只能作为业务数据，并与可信主体交叉校验。
4. 1.4.2 以 ClientApp credential 与 upstream user mapping/grant 作为当前身份基线；独立 signed assertion 降为低优先级后续能力，不作为本版本 P2 或 P7 的阻塞项。审计必须如实标记 `client-app-delegated`，不得把代办身份虚报为独立用户签名证明。

### Credential 与 token

1. credential/token 必须具备作用域、有效期、撤销和轮换能力。
2. task-scoped token 只能访问对应任务及允许的 BusinessFunction，不能读取或操作其他任务、Session 或函数。
3. token 签发、使用、拒绝、撤销和过期必须可审计，不在日志中记录明文凭据。

### 任务动作与恢复

1. 审批、恢复、取消等操作必须同时绑定任务归属、当前状态、调用主体和允许动作。
2. 外部审批不得只接受可伪造的 `reviewedBy`；审阅主体从可信调用上下文派生。
3. BusinessFunction/BusinessTask 的调用与审批恢复链路必须保持任务级关联，拒绝跨任务复用。

### Worker 与工具边界

1. `external-enabled` 必须由显式配置开关启用，默认关闭；不得根据监听地址、请求参数或空 Token 自动推断进入外部模式。
2. 外部模式下，非 loopback Worker 不允许因空 Token 意外关闭认证。
3. 缺少必要 credential 的非 loopback 外部 Worker 必须 fail closed 或保持 unready。
4. 内部开发模式和外部启用模式必须有明确配置、readiness 与诊断差异；开关关闭时不得报告 external ready 或开放外部路由。
5. 外部触发的 Agent/Worker 必须受工作目录、允许工具、BusinessFunction 和附加目录边界约束。
6. 必须记录必要的调用、审批、恢复、取消、失败和拒绝审计，且审计不泄露 token 或完整敏感输入。

### P2 首批实施事实与剩余边界

以下是 `2026-07-14` 的本机实施事实，不是需求完成或正式验收结论：

1. 平台 `NAVIGATOR_EXTERNAL_ENABLED` 已默认 `false`，只控制 `/api/v1/open` 路径根及子路径；关闭时返回 `503 / EXTERNAL_SURFACE_DISABLED`。它不控制 `/api/v1/upstream-admin/**`、`/internal/worker-gateway/v1/**` 或其他内部 Controller。
2. 平台 `/api/v1/health/external-surface` 的 `surfaceReady` 只表达 platform routing gate，不评估 Provider 或生产 readiness，不能单独作为 external enablement 条件。
3. LangGraph Biz、Codex SDK、Codex app-server Worker 分别使用 `BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`；三个开关只接受显式 `true/false` 且默认 `false`。当前显式开启仍固定包含 `EXTERNAL_EXECUTION_POLICY_PENDING` 并保持 unready；空 Token 另含 `EXTERNAL_AUTH_TOKEN_REQUIRED`，精确规范路径 `GET /health` 保持可观察，其他业务请求返回 `503 / EXTERNAL_WORKER_UNREADY`。
4. 平台已拒绝将显式 `ready=false` 的 Worker 提升为可路由状态；未返回 `ready` 的旧 Worker 暂时兼容原行为。此兼容是仓内迁移措施，不允许覆盖显式 unready。
5. `internal-dev` 只是应用模式，不是网络防火墙。LangGraph/Codex SDK Worker 的 `0.0.0.0` 监听和空 Token 仅可在可信网络使用，部署必须另配 loopback、ACL 或等价网络隔离。
6. 尚未完成的 P2 核心需求包括：task token 函数 scope、TTL/终态失效/撤销与 Worker 绑定，ClientApp/upstream user 身份链，审批/恢复/取消主体绑定，外部目录/工具/sandbox/approval/network 上限，以及调用与拒绝审计。因此 external-enabled 业务面不得启用，P2 保持 `in-progress`。

## 构建与 CI 基线需求

1. P1 使用 Owner 已批准的 Node `22.23.1` 与 pnpm `10.34.5`，并机器校验 Node、pnpm 与实际 Corepack/bootstrap 版本。
2. 根 workspace 必须提交并使用可复现 lockfile；当前 `pnpm-lock.yaml` 被全局忽略的问题必须关闭。
3. 根构建必须明确覆盖主前端、chat-core、chat/widget、mobile 及纳入交付的其他前端包，不得用局部成功代表全仓成功。
4. Java 必须从 clean 环境执行 launcher 主依赖链编译和测试，禁止依赖旧构建产物。
5. 主前端现有 `ClaudeWorkerView.vue` TypeScript 错误必须进入 P1 基线修复，不能通过跳过类型检查建立绿色 CI。
6. GitHub Actions 必须补全 Java、前端和 Worker 的 clean build 矩阵；发布流水线不能替代合并门禁。
7. 每条 CI lane 必须记录输入版本、命令、结果和产物边界；平台条件跳过项必须显式说明。

## 架构与可维护性需求

1. `UnifiedSseEmitter` 当前单 JVM 内存态的事实必须写入架构边界；多实例事件总线留待后续版本。
2. Addon 当前直接依赖 `session-module`，属于编译期模块化单体，不宣称已支持真正动态插件加载。
3. `providerStateJson`、`taskStateJson` 已具备 `ProviderStateCodec` envelope v1；本版本必须补充严格版本校验、Provider typed adapter、兼容读取、迁移与回滚，不重复建设首版 schema。
4. `TaskDispatchFacade`、`OpenApiController`、`ClaudeTaskService`、`CodexTaskService` 等重类按职责和风险渐进拆分。
5. `ClaudeWorkerView.vue` 采用可回归的小步拆分，不允许一次性重写超过一万行的现有工作台。
6. 每次结构治理必须有行为基线、测试映射和回滚点，不以行数下降作为唯一完成标准。

## 清理分级

### 第一档：引用扫描后可独立清理

以下均为候选项，不代表已获删除批准：

- `addons/coding-agent/integration-tests/package-lock.json`
- `test-memory-e2e.ps1`
- `test_memory_e2e.py`
- `packages/navigator-frontend/test-tooltip.ts`
- `packages/navigator-frontend/tooltip-test.spec.ts`
- 根 `test-worker-tab.spec.ts`
- `packages/foggy-mobile/src/components/TaskCard.vue`
- `packages/navigator-frontend` 下无引用手工截图
- `tools/claude-agent-worker/src/claude_agent_worker.egg-info`
- 未引用的前端 API 导出和旧测试 mock
- 已删除 tutor-agent/OpenHands addon 对应的旧技能和文档

每项删除前必须记录引用扫描、验证命令、迁移或替代说明、恢复来源和回滚方式。不得只写“删除文件”。

### 第二档：Owner 已批准的 dev-only 完整切片清理

Owner 已确认当前范围没有上游或生产兼容义务，开发数据允许丢弃，以下切片可在 P5/P6 物理删除。该授权不允许命令命中共享或生产资源；执行前必须再次确认环境、资源名称与仓内引用。

1. Monitoring：`monitoring-module`、`tools/foggy-monitor`、`MonitoringView.vue`、`api/monitoring.ts`、Security 放行项和 `scripts/start-all.sh` 安装步骤已作为 dev-only 切片物理移除；当前状态为已实施、待随 P5 完成剩余文档与门禁收口，开发数据库与 RabbitMQ 资源无需备份保留。
2. `metadata-query-module`：本地实施完成；模块目录、根 reactor 条目、launcher 依赖、launcher 专属 bean 断言、专属 Skill 与当前文档已退出现行树。删除后 metadata-config/launcher clean test 15/15 `SUCCESS`，dependency tree 与 clean target 无旧查询依赖；`metadata-config-module` 23 个 tracked files 保留、业务树 diff 为 0。状态为 `completed-local`；hosted CI 已通过、版本签收已执行并拒绝，专项启动/浏览器 smoke 和模块级签收仍未完成。
3. `addons/code-review-agent`：源码与切片内配置已成组物理移除；当前状态为已实施、待随 P5 完成剩余文档与门禁收口。如后续扫描发现遗留 GitLab webhook 或 credential，仍须撤销或删除，禁止遗留可调用入口。
4. `echo-agent`：已从生产 reactor、launcher 与运行时物理移除，A2A 生命周期由 test-only 内存 fixture 回归；`LocalEchoBusinessFunctionAdapterInvoker` 保留。状态为 `completed-local / verification-partial`，hosted CI 和版本正式质量/覆盖/验收已执行，版本结论为 `rejected`；Echo 专项体验、PowerShell parser 和模块级签收仍待执行。
5. 旧 Provider API：三组 `/claude-tasks`、`/codex-tasks`、`/langgraph-tasks` Controller 已在仓内消费者迁移到 unified task routes 后物理移除；Provider legacy bridge、Claude/Codex deprecated task form/DTO 同步退出，LangGraph 审批迁移到可信主体派生的 unified respond。当前 hosted CI 已通过；本项不宣称全仓所有非 Provider `@Deprecated` 均已清零，也不替代后续正式覆盖审计。

每个功能切片必须独立提交、独立验证和独立回滚，不允许通过单次大提交混合删除。静态引用命中是必须处理的仓内依赖，不再被解释为需要外部兼容窗口。

### 暂时保留

- `CodingAgentEntity` 和 `/api/v1/coding-agents`：当前属于通用 Agent 注册能力。
- `ProfileView.vue`：优先评估恢复路由。
- `/c/:id`：保留当前深链能力。
- `navigator-chat-widget`、mobile `uni_modules`：属于外部集成交付物。
- keystore：必须先迁移、备份和轮换，不能直接删除。
- `metadata-config-module`：不得因名称与旧语义层相似而误删。

## 工作项与 Ownership

| Workitem | 需求范围 | 建议 Owner 边界 | 链接 |
|---|---|---|---|
| GOV-001 | 内外部信任边界、模式和 readiness | root / agent framework / session | [工作项](../workitems/GOV-001-internal-external-trust-boundary.md) |
| GOV-002 | Biz Worker、ClientApp、upstream user、credential/token | Biz Worker / gateway / ClientApp owners | [工作项](../workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| GOV-003 | Session/Task ownership 与任务动作 | session-module / task services | [工作项](../workitems/GOV-003-session-task-resource-ownership.md) |
| OPT-001 | clean build、Node、lockfile、CI | root build / frontend / Worker owners | [工作项](../workitems/OPT-001-build-and-ci-baseline.md) |
| OPT-002 | 重类、模块边界、Provider state schema | owning modules | [工作项](../workitems/OPT-002-core-code-maintainability.md) |
| CLEAN-001 | 低风险孤儿候选 | root + owning module | [工作项](../workitems/CLEAN-001-low-risk-orphan-cleanup.md) |
| CLEAN-002 | Monitoring 功能切片 | monitoring / deployment owners | [工作项](../workitems/CLEAN-002-monitoring-retirement.md) |
| CLEAN-003 | metadata-query dev-only 完整退役 | metadata-query / launcher owners | [工作项](../workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| CLEAN-004 | code-review、echo、旧 Provider API | provider / SDK / client owners | [工作项](../workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| DOC-001 | 产品、架构、部署和历史文档对齐 | root documentation owner | [工作项](../workitems/DOC-001-documentation-alignment.md) |

最终职责和具体代码触点以 [模块职责](../module-responsibility.md) 与 [代码清单](../code-inventory.md) 为准。

## 阶段要求

| 阶段 | 必须完成的需求输出 | 状态 |
|---|---|---|
| P0 | 产品定位、信任边界、术语、ownership、代码清单和证据分类冻结 | in-progress |
| P1 | 明确 Node 支持线、精确工具版本、lockfile、Java/前端/Worker clean build 与 CI 矩阵 | in-progress |
| P2 | external 显式开关、ClientApp/grant 身份基线、credential、task token、审计和 fail-closed 边界；signed assertion 为低优先级后续项 | in-progress；模式/门禁/readiness 第一批已实施，其余边界未完成 |
| P3 | Session/Task ownership、审批/恢复/取消主体校验和内部 UI 回归 | in-progress；统一 ownership 首批与 Session 双用户隔离浏览器链路已通过，真实 Provider Task、共享数据库及显式 admin/system 通路仍待闭合 |
| P4 | 第一档孤儿项逐项扫描、验证、删除与回滚记录；失效文档对齐 | not-started |
| P5 | 在 dev-only 授权范围内完成 Monitoring、metadata-query、code-review、echo 的完整 inventory、仓内引用处理和相互独立的物理删除 | in-progress |
| P6 | 重类与状态 schema 渐进治理；旧 Provider API/SPI/DTO 在仓内消费者迁移后直接删除 | in-progress；旧 Provider 契约子切片已完成并通过 hosted CI，超大类与 Provider state schema 仍未实施 |
| P7 | 实现质量检查、测试覆盖审计、体验验证和正式签收 | completed-formal-review / rejected |

详细输入、非目标、测试、风险、回滚和完成判据见 [实施计划](../implementation-plan.md)。

## 明确非目标

1. 不在 1.4.2 重写 Spring Security。
2. 不要求所有内部 Controller 统一迁移到新的鉴权框架。
3. 不引入通用 RBAC/ABAC 平台。
4. 不在本版本实现多实例 SSE 事件总线。
5. 不在本版本实现动态插件加载。
6. 不在已批准的 dev-only 切片、仓内引用迁移和独立回滚边界之外扩大物理删除范围。
7. 不把 Claude/Codex/Gemini 内部 Worker 的所有开发模式一刀切关闭。
8. 不进行无明确收益的大范围代码重构。

## 验收标准

1. 内部 UI 和现有可信内网工作流不因治理发生大面积回归。
2. 外部 LangBizWorker/CodexBizWorker 请求可追溯到 tenant、ClientApp、upstream user 和具体任务。
3. 外部审批、恢复、取消不能只凭 `taskId` 完成。
4. 外部身份不得直接取自可伪造的请求字段。
5. task-scoped token 不得越权访问其他任务或函数。
6. 非 loopback 外部 Worker 缺少必要凭据时必须 fail closed 或 unready。
7. Java 必须从 clean 环境构建测试通过。
8. 主前端及纳入范围的其他前端包类型检查、测试和构建通过。
9. Node 和包管理器版本明确，lockfile 可复现。
10. 所有删除项都有引用扫描、迁移/替代说明和回滚证据。
11. Monitoring、metadata-query、code-review、echo 和旧 Provider 契约按已批准 dev-only 范围完整退出；仓内引用、装配、配置、开发资源和文档无半退役残留，明确保留项无误删。
12. 当前文档不得继续把 tutor、旧 chat-first 或语义层写成产品主线。
13. 不把隔离验收等同于生产批准。

其中第 11 项约束的是退役粒度和环境边界：dev-only 数据丢弃授权免除生产流量静默、备份和兼容等待，但不免除环境防误删、仓内消费者迁移、完整切片检查、clean build/test 与回滚记录。发现共享或生产资源时必须停止并重新取得授权。

## 约束与风险

- 本需求已完成 Owner 评审并进入实施；当前改动必须受对应工作项、完整切片、仓内引用迁移和独立回滚约束，不得触碰生产配置、共享资源或外部路由。
- P2 首批代码没有改变生产路由，也没有启用 external。平台开关只覆盖 Open API surface，Worker `internal-dev` 也不提供网络隔离；在后续门禁完成并独立批准前，禁止把 `surfaceReady`、HTTP 200 health、已配置 Token 或监听地址解释为生产就绪。
- dev-only 物理删除前仍须处理反射、配置、脚本、webhook、SDK 和仓内调用；发现共享/生产部署或上游消费者时，本次授权立即停止适用。
- 旧 Provider 契约不设上游/生产兼容窗口，但仓内消费者必须先迁移或随切片删除，并保留可执行的 Git 回滚说明。
- ownership 校验应集中建立不变量；若散落在 Controller，容易出现不同入口语义漂移。
- 构建工具升级和 lockfile 纳管可能暴露隐藏依赖差异，必须从 clean 环境验证而不是沿用本地缓存。
- 大型类拆分和状态 schema 迁移具有高回归风险，必须按小步阶段签收。

## Progress Tracking

- development: in-progress；P1 clean 基线、P2 外部边界首批、P3 ownership 首批、Monitoring/metadata-query/code-review/Echo 完整切片和旧 Provider 契约子切片已实施；P2 的外部身份/可靠审计/工具上限、P3 真实 Provider Task 与系统主体、P4 低风险清理、P6 超大类/state schema 仍未完成
- testing: partial-passed-local-and-hosted；Repository CI run `29323068427` 的 7 个 job 全部成功，Session ownership 隔离 H2 live 浏览器 1 passed，mock Playwright 17 passed/1 opt-in live skipped；main 分支保护/required checks 未配置，修复后 nightly、根 reactor `clean verify`、共享数据库、真实 Provider Task/L3 和完整 P2 负向矩阵仍未完成；正式门禁已执行并因这些关键缺口给出 `rejected`
- experience: partial-passed-isolated；Session 双用户深链/拒绝反馈已在 loopback H2 验证，不代表共享环境或生产体验批准
- implementation_plan: [1.4.2 implementation plan](../implementation-plan.md)
- progress_record: [1.4.2 progress](../progress.md)
- quality_gate: [ready-with-risks](../quality/executed-governance-slices-implementation-quality.md)
- coverage_audit: [needs-more-tests](../coverage/1.4.2-coverage-audit.md)
- acceptance_status: rejected

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: root-controller
- signed_off_at: 2026-07-14
- acceptance_record: [Version Signoff](../acceptance/version-signoff.md)
- blocking_items: external-runtime-boundary-incomplete, task-ownership-live-matrix-incomplete, p4-and-p6-scope-incomplete, coverage-audit-needs-more-tests
- follow_up_required: yes

本次签收拒绝的依据是 critical AC 和计划范围存在已知未完成项，不是证据无法判断；补齐阻断项后必须重新执行质量闸门、覆盖审计与版本验收。

## 相关文档

- [版本索引](../README.md)
- [模块职责](../module-responsibility.md)
- [代码清单](../code-inventory.md)
- [实施计划](../implementation-plan.md)
- [执行提示词](../execution-prompt.md)
- [进度记录](../progress.md)
