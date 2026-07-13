# 1.4.2-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理“平台治理与历史能力收口迭代”的需求、工作项、阶段、证据和验收门禁。

## 版本状态

- status: planned
- primary_workitem: `REQ-001`
- implementation_started: no
- production_routing_changed: no
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
5. 恢复可复现的 Java、前端和 Worker 构建基线，冻结一个明确受支持的 Node 版本、根 lockfile/workspace 和全仓 CI 门禁；ODR-142-001 当前建议 Node `22.23.1`，尚待批准。
6. 对旧模块、孤儿代码、兼容 API 和失效文档实施分级清理，不在无引用、流量和回滚证据时直接删除。
7. 为超大类、模块边界和 Provider 状态契约建立渐进治理计划，不进行一次性重写。
8. 建立后续删除、迁移、覆盖审计、体验验证和正式签收的明确门禁。

## 信任边界摘要

### 内部控制面

- 保留当前轻量认证模式，不以全局 `SecurityConfig` 重构替代资源归属校验。
- Session、Task 等用户资源在统一 service/facade 层建立 ownership 不变量。
- 不允许仅凭 `sessionId` 或 `taskId` 跨用户读取、审批、恢复、取消或修改资源。
- 内部开发和管理员能力可以保留，但必须标注仅适用于可信内网的接口和配置。

### 外部运行面

- LangBizWorker、CodexBizWorker、Worker Gateway、ClientApp 和 upstream user 的调用主体必须可确认。
- tenant、ClientApp、upstream user mapping/grant、credential 和 task-scoped token 必须形成可追溯链路。
- 请求体中的 `userId`、`reviewedBy`、`tenantId` 等字段不得直接作为可信身份来源。
- task-scoped token 只能访问对应任务和被授权的 BusinessFunction。
- 审批、恢复、取消必须同时绑定任务归属和调用主体。
- 非 loopback 外部 Worker 缺少必要凭据时必须 fail closed 或保持 unready。
- 外部触发的 Agent/Worker 必须受工作目录与工具能力边界约束，并记录调用、审批、恢复、失败和拒绝审计。

## 证据边界

| 分类 | 本版本定义 | 当前使用规则 |
|---|---|---|
| 已确认事实 | 用户已确认的产品定位、目标、边界和非目标 | 可直接作为规划约束，不等同于实现或测试证据 |
| 静态搜索结论 | 当前输入中列出的源码、依赖、文件和构建线索 | 执行前必须复核引用和实际路径，不推断运行流量 |
| 需要运行态确认 | 外部调用、历史 API、Monitoring、metadata-query、code-review 等实际消费者和流量 | 未完成日志、部署、数据库和第三方审计前不得退役 |
| 决策项 | Node/包管理器精确版本、生产存储/部署、旧能力去留等 | 必须由对应 Owner 留下决策记录，不允许由执行 Agent 静默决定 |

本规划没有执行构建、流量审计、删除、生产迁移或外部路由变更。现有输入中的测试结果只作为待复核基线，不作为 `1.4.2-SNAPSHOT` 的新验收证据。

## 工作项总览

| Workitem | 范围 | 计划阶段 | 初始状态 |
|---|---|---|---|
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | 内部控制面与外部运行面信任边界 | P0、P2、P3 | planned |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | Biz Worker、ClientApp、upstream user、凭据与 task token | P2 | planned |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | Session/Task ownership 与审批、恢复、取消约束 | P3 | planned |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | Node、lockfile、Java/前端/Worker clean build 与 CI | P1 | planned |
| [OPT-002](./workitems/OPT-002-core-code-maintainability.md) | 超大类、模块边界和 Provider 状态 schema 渐进治理 | P6 | planned |
| [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) | 低风险孤儿文件、未引用导出和失效文档 | P4 | planned |
| [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) | Monitoring 完整功能切片退役审计 | P5 | planned |
| [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) | metadata-query 运行流量与外部依赖审计 | P5 | planned |
| [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) | code-review、echo、旧 Provider API 与兼容 SPI | P5、P6 | planned |
| [DOC-001](./workitems/DOC-001-documentation-alignment.md) | 产品定位、架构、部署和历史文档对齐 | P0、P4 | planned |

## 阶段总览

| 阶段 | 目标 | 初始状态 | 生产路由/外部契约影响 |
|---|---|---|---|
| P0 | 冻结目标、边界、术语、ownership 和代码清单 | not-started | 否；仅规划与文档基线 |
| P1 | 冻结 Node、包管理器、lockfile、全仓 clean build 和 CI 矩阵 | not-started | 否；构建与合并门禁会变化 |
| P2 | 治理外部 Biz Worker、Worker Gateway 和 upstream user 边界 | not-started | 可能；必须先冻结兼容、迁移和回滚方案 |
| P3 | 在 service/facade 层补齐 Session/Task ownership | not-started | 可能影响越权或依赖旧行为的调用；不得大范围改内部 UI |
| P4 | 清理低风险孤儿代码和失效文档 | not-started | 否；每项仍需引用扫描、验证和回滚证据 |
| P5 | 对 Monitoring、metadata-query、code-review、echo 作去留决策；仅对获批项独立退役 | not-started | 可能；获批退役者必须按完整功能切片和独立门禁执行 |
| P6 | 渐进治理超大类、Provider 状态 schema 和旧 API | not-started | 可能；必须版本化、兼容和分阶段迁移 |
| P7 | 执行质量检查、覆盖审计、体验验证和正式签收 | not-started | 不直接改变路由；隔离验收不等于生产批准 |

各阶段的输入、模块、实施内容、测试、手工验证、风险、回滚和完成判据以 [实施计划](./implementation-plan.md) 为准，执行状态统一回写到 [进度记录](./progress.md)。

## 明确非目标

1. 不在 1.4.2 重写 Spring Security。
2. 不要求所有内部 Controller 统一迁移到新的鉴权框架。
3. 不引入通用 RBAC/ABAC 平台。
4. 不在本版本实现多实例 SSE 事件总线。
5. 不在本版本实现动态插件加载。
6. 不在规划阶段直接删除 Monitoring、metadata-query 或旧 Provider API。
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

第 11 项不授权规划阶段直接删除：获批退役的能力必须完整切片退出；审计结论为保留、延后或证据不足时，必须如实记录并由 Owner 在签收范围中处理，不能以局部删除冒充退役完成。

## Owner 决策评审

八组核心建议已集中到 [Owner 决策评审稿](./owner-decision-review.md)。该文档当前为 `pending-owner-review`，建议值不等于批准，不改变本版本 `planned` 状态，也不授权实现、生产启用或退役。

| 决策 | 建议摘要 | 当前状态 |
|---|---|---|
| ODR-142-001 | Node `22.23.1`、pnpm `10.34.5`、单一根 lockfile 和三层 CI | pending-decision |
| ODR-142-002 | external-enabled 使用 signed assertion，ClientApp 代办仅受限兼容 | pending-decision |
| ODR-142-003 | 30 分钟 opaque task token，绑定完整授权 scope，并与 Worker principal/lease 双重校验 | pending-decision |
| ODR-142-004 | external-enabled 默认拒绝、`workspace-write`、任务工具 egress 默认拒绝、缺凭据 unready | pending-decision |
| ODR-142-005 | 本地关键状态事务 outbox、拒绝可靠落档、远程调用分段审计、遥测 best-effort | pending-decision |
| ODR-142-006 | 四类历史能力按不同目标态治理，当前均不授权物理退役 | pending-decision |
| ODR-142-007 | 1.4.2 不删旧 Provider API/SPI/DTO，按兼容窗口逐路由迁移 | pending-decision |
| ODR-142-008 | 当前文档修正、历史证据标记、失效 Skill 退出活跃发现 | pending-decision |

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
- [Owner 决策评审稿](./owner-decision-review.md)
- [执行提示词](./execution-prompt.md)
- [实施与门禁进度](./progress.md)
- [GOV-001 内外部信任边界](./workitems/GOV-001-internal-external-trust-boundary.md)
- [GOV-002 Biz Worker 与 upstream user 边界](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)
- [GOV-003 Session/Task 资源归属](./workitems/GOV-003-session-task-resource-ownership.md)
- [OPT-001 构建与 CI 基线](./workitems/OPT-001-build-and-ci-baseline.md)
- [OPT-002 核心代码可维护性](./workitems/OPT-002-core-code-maintainability.md)
- [CLEAN-001 低风险孤儿清理](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)
- [CLEAN-002 Monitoring 退役](./workitems/CLEAN-002-monitoring-retirement.md)
- [CLEAN-003 metadata-query 退役审计](./workitems/CLEAN-003-metadata-query-retirement-audit.md)
- [CLEAN-004 实验性与兼容能力治理](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)
- [DOC-001 文档对齐](./workitems/DOC-001-documentation-alignment.md)
