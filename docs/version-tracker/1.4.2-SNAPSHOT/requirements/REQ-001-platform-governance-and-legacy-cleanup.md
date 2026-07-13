---
type: requirement
version: 1.4.2-SNAPSHOT
ticket: REQ-001
priority: high
status: planned
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

本需求是 `1.4.2-SNAPSHOT` 的总需求，统领 10 个工作项和 P0-P7 八个阶段。各工作项可以独立实施和签收，但不得偏离本文冻结的产品定位、信任边界、非目标与验收门禁。需要 Owner 拍板的八组建议集中记录在 [Owner 决策评审稿](../owner-decision-review.md)；其当前状态为 `pending-owner-review`，不构成已批准实现或生产授权。

## 产品定位

Foggy Navigator 当前是内部系统，核心目标是：

1. 多 Worker 远程编程工作台；
2. 统一 Session、Task 和 A2A 治理；
3. Claude、Codex、Gemini、LangGraph 等 Provider 接入；
4. 文件、Git、工作目录、终端和跨项目协作；
5. Business Agent、Open SDK、ClientApp 和 upstream user 集成。

当前不是语义层或数据分析平台。历史 metadata 能力是否保留，应按实际 Navigator 运行职责和消费者审计决定，不能反向改变产品主线。

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
| 用户提供的构建基线 | launcher 主干依赖链 clean test 当前可通过 | 本轮未复跑；P1 必须从 clean 环境形成新证据 |
| 需要运行态确认 | Monitoring、metadata-query、code-review、echo 和旧 Provider API 的真实消费者、流量与部署 | 未确认前不得删除或宣布退役 |
| 决策项 | 精确工具链版本、credential authority、mapping/grant 权威、状态 schema、退役窗口 | 必须记录 Owner、决定和日期，不得由聊天结论替代 |

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

### Credential 与 token

1. credential/token 必须具备作用域、有效期、撤销和轮换能力。
2. task-scoped token 只能访问对应任务及允许的 BusinessFunction，不能读取或操作其他任务、Session 或函数。
3. token 签发、使用、拒绝、撤销和过期必须可审计，不在日志中记录明文凭据。

### 任务动作与恢复

1. 审批、恢复、取消等操作必须同时绑定任务归属、当前状态、调用主体和允许动作。
2. 外部审批不得只接受可伪造的 `reviewedBy`；审阅主体从可信调用上下文派生。
3. BusinessFunction/BusinessTask 的调用与审批恢复链路必须保持任务级关联，拒绝跨任务复用。

### Worker 与工具边界

1. 外部模式下，非 loopback Worker 不允许因空 Token 意外关闭认证。
2. 缺少必要 credential 的非 loopback 外部 Worker 必须 fail closed 或保持 unready。
3. 内部开发模式和外部启用模式必须有明确配置、readiness 与诊断差异。
4. 外部触发的 Agent/Worker 必须受工作目录、允许工具、BusinessFunction 和附加目录边界约束。
5. 必须记录必要的调用、审批、恢复、取消、失败和拒绝审计，且审计不泄露 token 或完整敏感输入。

## 构建与 CI 基线需求

1. P1 必须选择并机器校验一个与当前前端工具链兼容的明确 Node、pnpm 与 Corepack 版本；ODR-142-001 建议 Node `22.23.1`、pnpm `10.34.5`，评审通过前不视为需求已冻结。
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

### 第二档：运行流量与外部依赖审计后成组退役

1. Monitoring：`monitoring-module`、`tools/foggy-monitor`、`MonitoringView.vue`、`api/monitoring.ts`、Security 放行项、`scripts/start-all.sh` 安装步骤及相关部署和文档作为一个完整功能切片。
2. `metadata-query-module`：检查根 reactor、launcher、运行日志、外部流量、数据库、部署配置、第三方调用和旧 Foggy Dataset/FSScript 依赖。
3. `addons/code-review-agent`：确认 GitLab webhook 或其他外部消费者后再决定去留。
4. `echo-agent`：评估从生产 launcher 移出并转为 test/dev fixture，不能直接删除测试基线。
5. 旧 Provider API：`/claude-tasks`、`/codex-tasks`、`/langgraph-tasks`、deprecated SPI 和兼容 DTO；必须审计 PC、Mobile、SDK、CLI 和外部客户调用。

每个功能切片必须独立建退役门禁，不允许通过单次大提交混合删除。

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
| CLEAN-003 | metadata-query 退役审计 | metadata-query / launcher owners | [工作项](../workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| CLEAN-004 | code-review、echo、旧 Provider API | provider / SDK / client owners | [工作项](../workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| DOC-001 | 产品、架构、部署和历史文档对齐 | root documentation owner | [工作项](../workitems/DOC-001-documentation-alignment.md) |

最终职责和具体代码触点以 [模块职责](../module-responsibility.md) 与 [代码清单](../code-inventory.md) 为准。

## 阶段要求

| 阶段 | 必须完成的需求输出 | 状态 |
|---|---|---|
| P0 | 产品定位、信任边界、术语、ownership、代码清单和证据分类冻结 | not-started |
| P1 | 明确 Node 支持线、精确工具版本、lockfile、Java/前端/Worker clean build 与 CI 矩阵 | not-started |
| P2 | 外部 Biz Worker/upstream user 身份、credential、task token、审计和 fail-closed 边界 | not-started |
| P3 | Session/Task ownership、审批/恢复/取消主体校验和内部 UI 回归 | not-started |
| P4 | 第一档孤儿项逐项扫描、验证、删除与回滚记录；失效文档对齐 | not-started |
| P5 | 第二档能力完成运行态审计、Owner 决策和相互独立的退役计划 | not-started |
| P6 | 重类、状态 schema 与旧 API 按兼容窗口渐进治理 | not-started |
| P7 | 实现质量检查、测试覆盖审计、体验验证和正式签收 | not-started |

详细输入、非目标、测试、风险、回滚和完成判据见 [实施计划](../implementation-plan.md)。

## 明确非目标

1. 不在 1.4.2 重写 Spring Security。
2. 不要求所有内部 Controller 统一迁移到新的鉴权框架。
3. 不引入通用 RBAC/ABAC 平台。
4. 不在本版本实现多实例 SSE 事件总线。
5. 不在本版本实现动态插件加载。
6. 不在规划阶段直接删除 Monitoring、metadata-query 或旧 Provider API。
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
11. Monitoring、metadata-query 等凡获批退役者必须按完整功能切片退出；retain、migrate 或 defer 必须有 Owner 决策、范围、后续版本和生产影响记录。
12. 当前文档不得继续把 tutor、旧 chat-first 或语义层写成产品主线。
13. 不把隔离验收等同于生产批准。

其中第 11 项约束的是退役粒度：凡 1.4.2 批准退役的能力，必须按完整功能切片执行；若运行态审计得出 `retain`、`defer` 或证据不足，则不得伪报“已退役”，必须由 Owner 明确移出本版本退役范围并在正式签收中记录。

## 约束与风险

- 本需求只授权规划和后续按工作项实施，不授权本轮删除、业务代码修改、生产配置、数据库迁移或外部路由变更。
- 静态无引用不能证明无运行时消费者；反射、配置、脚本、webhook、SDK 和外部客户必须单独审计。
- 外部调用约束可能影响依赖旧宽松行为的消费者，必须先形成兼容、迁移和回滚方案。
- ownership 校验应集中建立不变量；若散落在 Controller，容易出现不同入口语义漂移。
- 构建工具升级和 lockfile 纳管可能暴露隐藏依赖差异，必须从 clean 环境验证而不是沿用本地缓存。
- 大型类拆分和状态 schema 迁移具有高回归风险，必须按小步阶段签收。

## Progress Tracking

- development: not-started
- testing: not-run
- experience: not-run
- implementation_plan: [1.4.2 implementation plan](../implementation-plan.md)
- progress_record: [1.4.2 progress](../progress.md)
- acceptance_status: not-started

## 相关文档

- [版本索引](../README.md)
- [模块职责](../module-responsibility.md)
- [代码清单](../code-inventory.md)
- [实施计划](../implementation-plan.md)
- [执行提示词](../execution-prompt.md)
- [进度记录](../progress.md)
