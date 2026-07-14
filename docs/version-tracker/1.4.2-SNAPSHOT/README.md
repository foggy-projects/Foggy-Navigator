# 1.4.2-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理“平台治理与历史能力收口迭代”的需求、工作项、阶段、证据和验收门禁。

## 版本状态

- status: in-progress
- primary_workitem: `REQ-001`
- implementation_started: yes
- implementation_started_at: `2026-07-14`
- production_routing_changed: no
- external_contract_changed: yes
- external_enablement: no
- production_enablement: not-applicable
- acceptance_status: not-started

## 版本定位

`1.4.2-SNAPSHOT` 是 Foggy Navigator 的“平台治理与历史能力收口迭代”。Foggy Navigator 当前定位为内部多 Worker 远程编程工作台，核心能力包括：

- 多 Worker 远程编程工作台；
- 统一 Session、Task 与 A2A 治理；
- Claude、Codex、Gemini、LangGraph 等 Provider 接入；
- 文件、Git、工作目录、终端和跨项目协作；
- Business Agent、Open SDK、ClientApp 与 upstream user 集成。

当前产品主线不是语义层或数据分析平台。本版本不重写全平台鉴权体系，而是在内部控制面保持轻量认证的前提下，对外部运行面和关键用户资源补齐定向治理。

## 版本目标

1. 统一当前设计目标、产品定位和术语，停止沿用 tutor、旧 chat-first 或语义层主线叙述。
2. 明确内部控制面与外部运行面的信任边界、配置模式和 readiness 差异。
3. 在不重构整个鉴权框架的情况下，补齐 Session、Task 等用户资源的归属不变量和外部调用约束。
4. 治理 LangBizWorker、CodexBizWorker 与 upstream user 的凭据、身份、tenant、ClientApp、资源作用域和审计链路。
5. 恢复可复现的 Java、前端和 Worker 构建基线，按 ODR-142-001 冻结 Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile/workspace 和全仓 CI 门禁。
6. 对旧模块、孤儿代码、兼容 API 和失效文档实施分级清理；dev-only 切片可在仓内引用迁移、构建验证和独立回滚门禁后直接物理移除，不设置生产流量或客户兼容等待期。
7. 为超大类、模块边界和 Provider 状态契约建立渐进治理计划，不进行一次性重写。
8. 建立后续删除、迁移、覆盖审计、体验验证和正式签收的明确门禁。

## 信任边界摘要

### 内部控制面

- 保留当前轻量认证模式，不以全局 `SecurityConfig` 重构替代资源归属校验。
- Session、Task 等用户资源在统一 service/facade 层建立 ownership 不变量。
- 不允许仅凭 `sessionId` 或 `taskId` 跨用户读取、审批、恢复、取消或修改资源。
- 内部开发和管理员能力可以保留，但必须标注仅适用于可信内网的接口和配置。

### 外部运行面

- LangBizWorker、CodexBizWorker、Worker Gateway、ClientApp 和 upstream user 的调用主体必须可确认；独立 signed assertion 降为未来外部开放门禁，不阻塞当前 internal-dev。
- tenant、ClientApp、upstream user mapping/grant、credential 和 task-scoped token 必须形成可追溯链路。
- 请求体中的 `userId`、`reviewedBy`、`tenantId` 等字段不得直接作为可信身份来源。
- task-scoped token 只能访问对应任务和被授权的 BusinessFunction。
- 审批、恢复、取消必须同时绑定任务归属和调用主体。
- 非 loopback 外部 Worker 缺少必要凭据时必须 fail closed 或保持 unready。
- 外部触发的 Agent/Worker 必须受工作目录与工具能力边界约束，并记录调用、审批、恢复、失败和拒绝审计。
- 外部模式必须由单一显式开关开启且默认关闭；空 Token、监听地址或其他开发配置均不得隐式开启。

P2 首批模式门禁已经本机落地，但只完成了“默认关闭、显式声明 external-enabled 配置意图、readiness 可观察、平台不误路由”这一层；配置意图不等于 `external_ready`，更不等于 external enablement 或 production enablement：

- 平台通过 `NAVIGATOR_EXTERNAL_ENABLED=false` 默认关闭 `/api/v1/open` 路径根及其子路径；关闭时在进入 Controller 前返回 `503 / EXTERNAL_SURFACE_DISABLED`。该开关不覆盖 `/api/v1/upstream-admin/**`、`/internal/worker-gateway/v1/**` 或其他内部 Controller，`/api/v1/health/external-surface` 的 `surfaceReady` 也只表示 HTTP 路由门禁状态，不表示 Provider 或生产 readiness。
- LangGraph Biz Worker、Codex SDK Worker、Codex app-server Worker 分别使用 `BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`，只接受显式 `true/false` 且默认 `false`。当前即使显式开启，也会因 `EXTERNAL_EXECUTION_POLICY_PENDING` 保持 unready；空 Token 还会增加 `EXTERNAL_AUTH_TOKEN_REQUIRED`，除精确规范路径 `GET /health` 外的业务请求返回 `503 / EXTERNAL_WORKER_UNREADY`。
- 平台消费端已经识别 Worker 显式 `ready=false`，不会再将其提升为可路由状态；未返回 `ready` 的旧 Worker 暂按原行为兼容。此兼容只用于仓内渐进迁移，不代表允许 external-enabled Worker 绕过 readiness。
- `internal-dev` 不是网络防火墙。LangGraph/Codex SDK Worker 的 `0.0.0.0` 监听和空 Token 仅适用于可信开发网络，部署侧仍须通过 loopback、ACL 或等价网络边界阻断非可信访问。

## 证据边界

| 分类 | 本版本定义 | 当前使用规则 |
|---|---|---|
| 已确认事实 | 用户已确认的产品定位、目标、边界和非目标 | 可直接作为规划约束，不等同于实现或测试证据 |
| 静态搜索结论 | 当前输入中列出的源码、依赖、文件和构建线索 | 执行前必须复核引用和实际路径，不推断运行流量 |
| 需要运行态确认 | 与“仅 dev、本机共同孵化”前提冲突的共享/生产资源或活跃独立部署 | 一旦静态扫描发现此类证据，停止对应删除并重新请示；不为已授权 dev 清理虚构生产流量证据 |
| 决策项 | Provider state 演进、credential authority、mapping/grant 权威源、超大类拆分顺序等尚未关闭事项 | 必须由对应 Owner 留下决策记录，不允许由执行 Agent静默决定 |

Owner 决策于 `2026-07-14` 完成后已启动实施。当前已经形成以下本机实施与验证证据，但尚未进入覆盖审计、体验验证或正式验收：

- P1 已落地 Node `22.23.1`、pnpm `10.34.5`、单一根 `pnpm-lock.yaml`、前端 workspace 矩阵和仓库级 CI workflow；本机使用精确版本完成 frozen install，前端类型检查、测试和构建均通过。
- Java 删除前历史基线继续保留；metadata-query 删除后另行执行 `mvn -B -pl metadata-config-module,launcher -am clean test`，15/15 reactor project 全部 `SUCCESS`，launcher 7 tests、0 failure/error。
- P2 首批提交后执行根 `mvn -B clean test`，发现并关闭 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md)：Open SDK 恢复可信的 JUnit 5 clean 基线，最终 17/17 reactor project 全部 `SUCCESS`，2304 tests、0 failure/error/skipped，exit 0；GitHub hosted runner 与根 `clean verify` 仍未执行。
- P2 首批由提交 `12cbe697`、`5d62707b`、`cce75f1b` 落地平台/三类 Worker 默认关闭门禁、readiness 诊断及平台消费约束。Java 定向矩阵 74 tests、10/10 reactor 通过；Codex SDK Worker 163 passed/1 skipped、Codex app-server Worker 272 passed/1 skipped、LangGraph Biz Worker 766 passed，Node type-check/build 与 Python build 均通过。平台路径 matrix parameter、context path、encoded path 回归覆盖并修复了实际门禁绕过。
- P5 已物理移除 Monitoring 和 `addons/code-review-agent` 两个 dev-only 完整切片；metadata-query 的模块、reactor/launcher 装配、launcher 专属 bean 断言、专属 Skill 与当前文档也已收口，dependency tree 和 clean target 无旧查询依赖。CLEAN-003 为 `completed-local`，但启动/浏览器 smoke、hosted CI 与正式验收未完成；Echo Agent 和旧 Provider API/SPI/DTO 尚未开始移除。
- 五类 Worker 已在独立 clean worktree 完成 P1 本机等价矩阵，nightly workflow 已建立；P2 的 task token 函数 scope/生命周期、ClientApp/upstream identity、审批恢复主体绑定、审计和 ownership 尚未完成。GitHub runner、分支保护、nightly 实际执行和真实浏览器体验仍未完成，因此 P1、P2、P5 和版本整体保持 `in-progress`，`acceptance_status` 仍为 `not-started`。

命令、结果、限制和后续补证统一登记在 [进度记录](./progress.md) 及对应 workitem 中；这里的本机通过不代表 GitHub 合并门禁已生效，也不代表验收或生产批准。

## 工作项总览

| Workitem | 范围 | 计划阶段 | 当前状态 |
|---|---|---|---|
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | 内部控制面与外部运行面信任边界 | P0、P2、P3 | in-progress |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | Biz Worker、ClientApp、upstream user、凭据与 task token | P2 | in-progress |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | Session/Task ownership 与审批、恢复、取消约束 | P3 | planned-reviewed |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | Node、lockfile、Java/前端/Worker clean build 与 CI | P1 | in-progress |
| [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | LangGraph 实时工具进度事件重复 | P1 | closed |
| [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) | Open SDK 测试编译、JUnit 5 与跨平台 clean 基线 | P1 | closed |
| [OPT-002](./workitems/OPT-002-core-code-maintainability.md) | 超大类、模块边界和 Provider 状态 schema 渐进治理 | P6 | planned |
| [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) | 低风险孤儿文件、未引用导出和失效文档 | P4 | planned |
| [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) | Monitoring dev-only 完整功能切片移除 | P5 | in-progress |
| [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | metadata-query dev-only 完整功能切片移除 | P5 | completed-local |
| [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | code-review、echo、旧 Provider API 与兼容 SPI | P5、P6 | in-progress |
| [DOC-001](./workitems/DOC-001-documentation-alignment.md) | 产品定位、架构、部署和历史文档对齐 | P0、P4 | in-progress |

## 阶段总览

| 阶段 | 目标 | 当前状态 | 生产路由/外部契约影响 |
|---|---|---|---|
| P0 | 冻结目标、边界、术语、ownership 和代码清单 | in-progress | 否；仅规划与文档基线 |
| P1 | 冻结 Node、包管理器、lockfile、全仓 clean build 和 CI 矩阵 | in-progress | 否；构建与合并门禁会变化 |
| P2 | 治理外部 Biz Worker、Worker Gateway 和 upstream user 边界 | in-progress | 生产路由未改变、external 未启用；开发树的默认关闭、503 和 readiness 契约已收紧 |
| P3 | 在 service/facade 层补齐 Session/Task ownership | not-started | 可能影响越权或依赖旧行为的调用；不得大范围改内部 UI |
| P4 | 清理低风险孤儿代码和失效文档 | not-started | 否；每项仍需引用扫描、验证和回滚证据 |
| P5 | 按 dev-only 授权独立移除 Monitoring、metadata-query、code-review，并迁移 Echo fixture 后退出生产装配 | in-progress | 当前无生产路由；发现共享/生产资源即停止 |
| P6 | 渐进治理超大类和 Provider 状态 schema；仓内迁移后直接移除旧 API/SPI/DTO | not-started | 当前无生产契约；替代入口安全语义仍是硬门 |
| P7 | 执行质量检查、覆盖审计、体验验证和正式签收 | not-started | 不直接改变路由；隔离验收不等于生产批准 |

各阶段的输入、模块、实施内容、测试、手工验证、风险、回滚和完成判据以 [实施计划](./implementation-plan.md) 为准，执行状态统一回写到 [进度记录](./progress.md)。

## 明确非目标

1. 不在 1.4.2 重写 Spring Security。
2. 不要求所有内部 Controller 统一迁移到新的鉴权框架。
3. 不引入通用 RBAC/ABAC 平台。
4. 不在本版本实现多实例 SSE 事件总线。
5. 不在本版本实现动态插件加载。
6. 不把“已授权 dev 删除”扩大成无引用扫描、无仓内迁移、无测试或跨切片批量删除。
7. 不把 Claude/Codex/Gemini 内部 Worker 的所有开发模式一刀切关闭。
8. 不进行无明确收益的大范围代码重构。

## 版本验收门禁

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
11. Monitoring、metadata-query 等凡获批退役者必须按完整功能切片退出；retain、migrate 或 defer 必须有 Owner 决策、范围和后续处理记录。
12. 当前文档不得继续把 tutor、旧 chat-first 或语义层写成产品主线。
13. 不把隔离验收等同于生产批准。

第 11 项的 dev-only 物理删除授权已由 Owner 给出；获批能力仍必须完整切片退出。发现共享/生产资源、迁移缺口或构建失败时必须如实停止并记录，不能以局部删除冒充完成。

## Owner 决策

八组核心建议和 `2026-07-14` Owner 结论已集中到 [Owner 决策记录](./owner-decision-review.md)。该文档为 `review-complete`，已经授权 dev 阶段实施，但不等于实现完成、验收通过、生产启用或外部开放。

| 决策 | Owner 结论摘要 | 当前状态 |
|---|---|---|
| ODR-142-001 | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile和三层 CI | approved |
| ODR-142-002 | internal-dev 保留 ClientApp 代办；signed assertion 延后到外部开放，external 必须显式且默认关闭 | approved-with-constraints |
| ODR-142-003 | 30 分钟 opaque task token，绑定完整授权 scope，并与 Worker principal/lease 双重校验 | approved |
| ODR-142-004 | external-enabled 默认拒绝、`workspace-write`、任务工具 egress 默认拒绝、缺凭据 unready | approved-with-constraints |
| ODR-142-005 | 本地关键状态事务 outbox、拒绝可靠落档、远程调用分段审计、遥测 best-effort | approved |
| ODR-142-006 | dev-only 切片安全后物理移除，旧数据可丢弃；Echo 先迁移 fixture | approved-with-constraints |
| ODR-142-007 | 仓内消费者迁移后在 1.4.2 直接删除旧 Provider API/SPI/DTO，无外部兼容窗口 | approved-with-constraints |
| ODR-142-008 | 当前文档修正、历史证据标记、失效 Skill 退出活跃发现 | approved |

以下事项不属于本次八组建议的完整决策，仍单独保持待确认：

- LangBizWorker、CodexBizWorker 与 Worker Gateway 的具体 credential authority、轮换责任人和撤销传播实现。
- upstream user mapping/grant 的权威数据源、tenant 迁移策略和最终审计留存要求。
- Provider state envelope v1 的严格校验、typed schema 演进、未知版本策略、兼容窗口和迁移 Owner。
- 超大类拆分优先级及可接受的阶段性边界，特别是 `ClaudeWorkerView.vue` 的渐进拆分顺序。

## 文档清单

- [REQ-001 平台治理与历史能力收口需求](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [模块职责](./module-responsibility.md)
- [代码清单](./code-inventory.md)
- [实施计划](./implementation-plan.md)
- [Owner 决策记录](./owner-decision-review.md)
- [执行提示词](./execution-prompt.md)
- [实施与门禁进度](./progress.md)
- [GOV-001 内外部信任边界](./workitems/GOV-001-internal-external-trust-boundary.md)
- [GOV-002 Biz Worker 与 upstream user 边界](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)
- [GOV-003 Session/Task 资源归属](./workitems/GOV-003-session-task-resource-ownership.md)
- [OPT-001 构建与 CI 基线](./workitems/OPT-001-build-and-ci-baseline.md)
- [BUG-001 LangGraph 实时工具进度事件重复](./workitems/BUG-001-langgraph-progress-event-duplication.md)
- [BUG-002 Open SDK clean test 基线](./workitems/BUG-002-open-sdk-clean-test-baseline.md)
- [OPT-002 核心代码可维护性](./workitems/OPT-002-core-code-maintainability.md)
- [CLEAN-001 低风险孤儿清理](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)
- [CLEAN-002 Monitoring 退役](./workitems/CLEAN-002-monitoring-retirement.md)
- [CLEAN-003 metadata-query 退役审计](./workitems/CLEAN-003-metadata-query-retirement-audit.md)
- [CLEAN-004 实验性与兼容能力治理](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)
- [DOC-001 文档对齐](./workitems/DOC-001-documentation-alignment.md)
