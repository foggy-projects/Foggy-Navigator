# 1.4.2-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理“平台治理与历史能力收口迭代”的需求、工作项、阶段、证据和验收门禁。

## 版本状态

- status: in-progress
- primary_workitem: `REQ-001`
- additional_requirements: `REQ-002`
- implementation_started: yes
- implementation_started_at: `2026-07-14`
- production_routing_changed: no
- launcher_default_agent_inventory_changed: yes
- external_contract_changed: yes
- external_enablement: no
- production_enablement: not-applicable
- formal_quality_gate: reviewed-ready-with-risks
- coverage_audit: reviewed-needs-more-tests
- acceptance_status: rejected
- gov004_acceptance_status: verification-blocked

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
9. 增加 Provider 无关的结构化错误诊断、90 天诊断快照、登录态详情和按需生成的临时匿名分享链接。

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
- 外部模式必须由显式开关开启且默认关闭；空 Token、监听地址或其他开发配置均不得隐式开启。当前平台 Open API 与 Worker Gateway 分别使用独立开关，二者的组合启动/readiness 约束仍是待办，不能只打开其中之一就宣称 external-ready。

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

Owner 决策于 `2026-07-14` 完成后已启动实施。当前已经形成以下本机、隔离浏览器与 GitHub hosted runner 证据，并已据此执行正式质量闸门、覆盖审计和版本签收：

- P1 已落地 Node `22.23.1`、pnpm `10.34.5`、单一根 `pnpm-lock.yaml`、前端 workspace 矩阵和仓库级 CI workflow；本机使用精确版本完成 frozen install，前端类型检查、测试和构建均通过。
- Java 删除前历史基线继续保留；metadata-query 删除后另行执行 `mvn -B -pl metadata-config-module,launcher -am clean test`，15/15 reactor project 全部 `SUCCESS`，launcher 7 tests、0 failure/error。
- P2 首批提交后执行根 `mvn -B clean test`，发现并关闭 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md)：Open SDK 恢复可信的 JUnit 5 clean 基线，最终 17/17 reactor project 全部 `SUCCESS`，2304 tests、0 failure/error/skipped，exit 0。该条保留为当时的本机历史基线；`EXEC-142-017` 的 hosted Java lane 只覆盖 launcher 依赖链，不包含 `navigator-open-sdk` 和 `tools/navigator-chat-observer-bff`，因此不能作为 Open SDK 或根 reactor 的 hosted 复验；根 `clean verify` 仍未执行。
- P2 首批由提交 `12cbe697`、`5d62707b`、`cce75f1b` 落地平台/三类 Worker 默认关闭门禁、readiness 诊断及平台消费约束。Java 定向矩阵 74 tests、10/10 reactor 通过；Codex SDK Worker 163 passed/1 skipped、Codex app-server Worker 272 passed/1 skipped、LangGraph Biz Worker 766 passed，Node type-check/build 与 Python build 均通过。平台路径 matrix parameter、context path、encoded path 回归覆盖并修复了实际门禁绕过。
- `EXEC-142-012` 已完成 Worker credential v1 schema/API 与 owner-scoped rotate/revoke、pool owner/identity route、definitive terminal tombstone 与 late-bind 撤销、Claude tenant 持久化，以及 audit writer 独立 bean + `REQUIRES_NEW/saveAndFlush` 事务隔离。最终 11 reactor clean test 共 2186 tests，0 failure/error/skip；三组 SQL migration 已在一次性 MySQL `8.0.44` 与 `8.4.8` 完成 forward×2、rollback×2、reapply；Node `22.23.1` / pnpm `10.34.5` 的 Business Agent integration TypeScript typecheck exit 0。
- `EXEC-142-013` 已将 Worker Gateway HTTP 调用收紧为 task token 与 Worker principal/lease 双重校验：严格头为 `X-Navigator-Worker-Id`、`X-Navigator-Worker-Credential`、`X-Navigator-Worker-Lease-Id`，partial/blank 组合及 legacy `X-Worker-Id` 均 fail closed；external-enabled 默认 `false`，只有完全无 Worker header 的 internal-dev 请求保留 token-only 兼容。BusinessTask/Open API 的 Biz Provider 会在签发前完成 DB preselect/prebind，Gateway 再校验 exact worker/lease、tenant、active ClientApp、pool/member/backend/owner 或精确 physical route；非 Biz Open API 不签发 Worker Gateway capability。LangGraph 已传播 Worker credential 并收紧子进程 allowlist/临时 askpass；Codex 长期 credential 尚无安全隔离转发通道，因此配置后 readiness=false，Business MCP 在创建任务副作用前返回 503。最终 `mvn -B -pl launcher -am clean test` 15/15 reactor、2357 tests 全通过；LangGraph 780 pytest + ruff 通过；Codex 175 tests 中 174 通过、1 个 Windows-only 跳过，typecheck 通过。
- `EXEC-142-014` 已启动 P3 Session/Task ownership：新增统一 `userId + tenantId` 资源门面，Session/Task/Agent/SSE/config/shared/forward 首批路径先授权；Task route 不信任请求体 agent；context assigned-ID 使用独立事务 `persist + flush` 与 owner 条件更新；Provider 返回 sessionId 后重新授权；显式 model config 校验 enabled/tenant/owner metadata/Worker grant；Sharing Key quota 在授权和 readiness 后原子消费；软删除资源 fail closed。P3 定向 Maven 176 tests 通过；随后 `mvn -B -pl launcher -am clean test` 15/15 reactor、2426 tests 全通过，exit 0（日志有测试 JVM 退出后 30 秒 fork kill 非失败提示）。`EXEC-142-018` 又以隔离 H2、真实 UI/API/SSE 和同 tenant 双账号验证 Session owner 可访问、非 owner 列表不可见且 deep-link/history/SSE/direct read 被拒绝；Task live Provider fixture、共享数据库、L3、全列表 tenant、SessionMetadata service invariant、model owner/grant 语义、Provider taskId 和 admin/system 显式通路仍未完成，因此 GOV-003 只标记 `in-progress / partial`。
- `EXEC-142-019` 记录并修复 [BUG-003](./workitems/BUG-003-tenantless-session-owner-access-regression.md)：P3 首批门面曾把非空 tenantId 当成所有主体的前置条件，导致按设计 tenantId 为 null 的平台账号无法访问自己的无租户 Session。修复新增 tenantless exact-owner scope，只允许同 userId 且资源 tenantId 为 null，不形成 SUPER_ADMIN 跨主体旁路；`/sessions/configs` 只读批次过滤无权/失效 ID，写批次继续原子拒绝。定向 27 tests 与 session 依赖链 clean 748 tests 均通过；dev PC 三个报告入口尚待使用新令牌复测，BUG 状态为 `ready-for-verification`。
- `EXEC-142-020` 记录并修复 [BUG-004](./workitems/BUG-004-blank-tenant-task-ownership-regression.md)：legacy JWT/API Key 中的空字符串 tenant 曾原样进入 `CurrentUser` 并写入新 Task/Session，而 tenantless ownership 只查询 `tenant_id IS NULL`，导致同一用户刚创建的 Codex Task 随即无法读取。新签发与认证入口将空白 tenant 规范为 null，tenantless repository 同时兼容历史 NULL/空白行且继续精确匹配 userId；定向 66 tests 与 session 依赖链 clean 753 tests 均通过。该缺陷与 Codex model config 的 `availableModels` grant 拒绝相互独立，尚待部署后使用新令牌复测 create/get/respond/cancel。
- `EXEC-142-021` 已完成 REQ-002 的实现切片：Provider 无关错误信封和版本化脱敏、Codex SDK/App Server 安全错误元数据、Task/SSE/chat 兼容、90 天 owner-scoped 快照、默认关闭且可撤销的 7/30 天 hash-token 分享，以及无脚本匿名页面和安全响应头。Java 定向 19 tests、两类 Worker 全量测试/typecheck、前端 typecheck 与 chatState 51 tests 通过；当前 Node 18 不满足项目 Node 22 基线，ErrorBlock 组件/build、MySQL migration、launcher clean、浏览器矩阵和正式门禁重验仍待执行，因此状态为 `ready-for-verification`，分享仍未启用。
- P5 已物理移除 Monitoring、`addons/code-review-agent` 和 Echo Agent 三个 dev-only 切片；metadata-query 也已 `completed-local`。Echo 的 5 个 tracked addon 文件及 root reactor/launcher 装配已退出，`UnifiedAgentResolverTest` 的 test-only 内存 fixture 覆盖 discovery/resolve/send/query/cancel，定向 16/16 tests 和 launcher 定向 14 modules、6/6 tests 通过。P6 的旧契约子切片也已完成仓内消费者迁移和物理收口：提交 `50351ada`、`73d31a19`、`97240642`、`fb11137d`、`9f3f1422`、`edee0fc4`、`9008c554` 移除 Claude/Codex/LangGraph 旧 Provider HTTP 入口、deprecated SPI/DTO，并将审批等调用迁入统一 Task API；更广泛的 Provider state schema 和超大类治理仍未完成。
- `2a859336` 以 Navigator 自有 clean-room 兼容层固定现用 REST `RX` wire contract，并移除 clean runner 无法解析的 `foggy-core` 外部 Maven 依赖。head `9008c554` 的 [Repository CI run 29323068427](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29323068427) 首次在 hosted runner 完成 Repository CI 7-job 矩阵；浏览器测试提交后的、截至正式闸门的最新已验证实现 head `9d03bee9` 又由 [run 29324741945](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 复验。两次均为 Java launcher 依赖链、前端、Codex SDK Worker、Codex app-server Worker、Gemini Worker、Claude Worker、LangGraph Biz Worker 共 7 个 jobs 全部 `success`；Java lane 不含 `navigator-open-sdk` 和 `tools/navigator-chat-observer-bff`。main 当前未配置 required check/分支保护，修复后的 nightly 也未实跑。
- `9d03bee9` 新增受显式环境变量保护的 Session ownership live Playwright；隔离 H2 + loopback 前后端下 1 passed（Playwright `2.9s`，编排总时 `3.9s`），全量 mock Playwright 为 17 passed、1 skipped（`35.2s`）。mock suite 不构成运行态 ownership 证据，hosted workflow 的成功也不代表 guarded live 用例在 hosted 环境执行；共享数据库因没有明确隔离目标和授权而 `not-run`，Task live Provider fixture 也为 `not-run`。隔离验证不等于生产批准。
- 五类 Worker 已在独立 clean worktree 完成 P1 本机等价矩阵，Repository CI 7-job hosted 矩阵也已通过；nightly workflow 已建立，但旧版本曾在 workflow 校验阶段失败且未启动 job，语法修复后的矩阵尚未实际运行。P2 仍为 `partial`：Gateway strict Worker principal/lease 与 Biz Provider preselect/prebind 已有自动化证据，但平台/Gateway 开关组合约束、Codex credential 安全转发、OS 级隔离、task pause/generation、关键拒绝与状态的 reliable audit/outbox、L3 集成验证，以及 ClientApp 双主体和外部审批/恢复/取消运行态矩阵仍未完成。P3 也只完成首批 ownership 与隔离 Session 双账号验证，尚未闭合全列表、系统主体、Task live fixture 和共享数据库证据；external-enabled 继续默认关闭且未启用。正式质量闸门对已执行切片给出 `ready-with-risks`，版本覆盖审计为 `needs-more-tests`，因此正式签收为 `rejected`；P1、P2、P3、P5、P6 和版本整体仍保持 `in-progress`。

命令、结果、限制和后续补证统一登记在 [进度记录](./progress.md) 及对应 workitem 中；本机与 hosted runner 通过不代表 GitHub required check/分支保护已生效，也不代表验收或生产批准。

### GOV-004 增量状态（2026-07-16）

GOV-004 的 Java 控制面与 Claude、Codex SDK、Codex app-server 三条 Worker 路径已完成本地实现和针对性自动化：自动超时、watchdog、stream/探测不确定性只写 attention，不得主动终止受管 CLI；显式取消或人工 PID 终止改为受签名、一次性、可审计的 operation。Java 使用持久 `termination_operations` 审计账本，三个 Worker 分别保留重启后仍有效的本地 receipt ledger。该 workitem 的正式状态是 `verification-blocked`，而非 accepted：真实隔离 CLI 五态矩阵、目标环境数据库迁移/回滚和告警部署/送达证据均尚未完成；跨主机或多实例也不能把各自本地 ledger 当作共享防重放存储。详见 [workitem](./workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md)、[质量记录](./quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)、[覆盖审计](./coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md)、[验收记录](./acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md) 和 [运行手册](./runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)。这不改变版本级既有 `rejected` 结论，也不改变生产路由或 external enablement。

## 工作项总览

| Workitem | 范围 | 计划阶段 | 当前状态 |
|---|---|---|---|
| [REQ-002](./requirements/REQ-002-structured-error-diagnostics-and-share-links.md) | 结构化错误、诊断快照、内部详情与临时分享链接 | P8 | ready-for-verification |
| [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) | 内部控制面与外部运行面信任边界 | P0、P2、P3 | in-progress |
| [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) | Biz Worker、ClientApp、upstream user、凭据与 task token | P2 | in-progress |
| [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) | Session/Task ownership 与审批、恢复、取消约束 | P3 | in-progress |
| [GOV-004](./workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md) | Java 与三类 Worker 的 CLI 非主动终止原则、显式终止审计与生命周期可观测性 | P2 | verification-blocked |
| [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md) | Node、lockfile、Java/前端/Worker clean build 与 CI | P1 | in-progress |
| [BUG-001](./workitems/BUG-001-langgraph-progress-event-duplication.md) | LangGraph 实时工具进度事件重复 | P1 | closed |
| [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) | Open SDK 测试编译、JUnit 5 与跨平台 clean 基线 | P1 | closed |
| [BUG-003](./workitems/BUG-003-tenantless-session-owner-access-regression.md) | 无租户账号访问自有 Session 被 ownership 门禁误拒绝 | P3 | ready-for-verification |
| [BUG-004](./workitems/BUG-004-blank-tenant-task-ownership-regression.md) | 空字符串 tenant 导致新建 Task 无法读取 | P3 | ready-for-verification |
| [BUG-010](./workitems/BUG-010-session-forward-app-server-runtime-affinity.md) | NEW_SESSION 转发误绑定 legacy SDK runtime | P3 | READY_FOR_SIGNOFF |
| [BUG-013](./workitems/BUG-013-codex-app-server-long-thread-tool-loss.md) | Codex App Server 长 Thread 工具退化、原生压缩与历史分支 | P3 | NEEDS_REPLAN |
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
| P1 | 冻结 Node、包管理器、lockfile、全仓 clean build 和 CI 矩阵 | in-progress（hosted baseline passed） | 否；Repository CI 7-job 矩阵已通过，main required check/分支保护未配置，修复后 nightly、根 reactor hosted 验证仍待完成 |
| P2 | 治理外部 Biz Worker、Worker Gateway 和 upstream user 边界 | in-progress（partial；GOV-004 verification-blocked） | 生产路由未改变、external 默认关闭且未启用；credential v1、pool identity route、Gateway strict principal/lease、Biz Provider preselect/prebind、definitive terminal、Claude tenant 与 audit writer 事务隔离已落地。GOV-004 已在本地收敛三类 Worker 的非主动终止、签名 operation 和 durable receipt；仍缺真实 CLI 五态、目标 DB、告警及多实例部署证据。Codex 安全转发、开关组合、OS 隔离、pause/generation、reliable audit/outbox 和 L3 仍待完成 |
| P3 | 在 service/facade 层补齐 Session/Task ownership | in-progress（partial） | 首批 userId+tenantId 门面及 Session/Task/Agent/SSE/config/shared/forward/context/model-config 已落地；隔离 Session 双账号 UI/API/SSE 已通过，越权行为收紧但生产路由未改变。全列表 tenant、Provider taskId、admin/system、Task live Provider fixture、共享数据库与 L3 仍待完成 |
| P4 | 清理低风险孤儿代码和失效文档 | not-started | 否；每项仍需引用扫描、验证和回滚证据 |
| P5 | 按 dev-only 授权独立移除 Monitoring、metadata-query、code-review，并用 test-only fixture 替代 Echo 后退出默认装配 | in-progress | 无生产环境，`production_routing_changed: no`；但 `launcher_default_agent_inventory_changed: yes`，默认制品不再注册 Echo；hosted CI 和版本正式门禁已执行，签收为 `rejected`，切片专项浏览器/PowerShell/模块级签收仍待补 |
| P6 | 渐进治理超大类和 Provider 状态 schema；仓内迁移后直接移除旧 API/SPI/DTO | in-progress（legacy contract slice completed） | 旧 HTTP/SPI/DTO 已迁入统一入口后物理收口，当前 dev 外部契约发生变化但生产路由未改变；Provider state schema 和超大类渐进治理仍未完成 |
| P7 | 执行质量检查、覆盖审计、体验验证和正式签收 | completed-formal-review / rejected | 质量闸门 `ready-with-risks`、覆盖审计 `needs-more-tests`、签收 `rejected`；不改变路由，隔离验收不等于生产批准 |
| P8 | 实施结构化错误诊断、诊断快照、登录态详情和按需临时分享链接 | implementation-complete / verification-partial | 可选错误契约、诊断数据、登录态详情和匿名只读 surface 已落地；分享默认关闭。Node 22 前端组件/build、migration、浏览器和正式门禁待补，不自动启用 external runtime |

各阶段的输入、模块、实施内容、测试、手工验证、风险、回滚和完成判据以 [实施计划](./implementation-plan.md) 为准，执行状态统一回写到 [进度记录](./progress.md)。

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: root-controller
- signed_off_at: 2026-07-14
- acceptance_record: [Version Signoff](./acceptance/version-signoff.md)
- blocking_items: external-runtime-boundary-incomplete, task-ownership-live-matrix-incomplete, p4-and-p6-scope-incomplete, coverage-audit-needs-more-tests
- follow_up_required: yes

正式门禁材料：已执行切片的 [Implementation Quality Gate](./quality/executed-governance-slices-implementation-quality.md) 为 `ready-with-risks`；版本级 [Test Coverage Audit](./coverage/1.4.2-coverage-audit.md) 为 `needs-more-tests`。拒绝结论表示当前版本不能签收，不否定已完成切片，也不改变 `external_enablement: no` 或生产路由。

`REQ-002` 于 `2026-07-15` 在上述签收之后新增并获方案确认，并于 `2026-07-16` 完成实现切片；它不受既有质量、覆盖和签收结果覆盖。完成 Node 22 构建、migration、浏览器矩阵后仍必须重新执行实现质量、覆盖审计和正式签收。

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
| ODR-142-006 | dev-only 切片安全后物理移除，旧数据可丢弃；Echo 已以 test-only fixture 替代并退出默认 launcher | implementation-partial |
| ODR-142-007 | 仓内消费者迁移后在 1.4.2 直接删除旧 Provider API/SPI/DTO，无外部兼容窗口 | implementation-complete-verification-partial |
| ODR-142-008 | 当前文档修正、历史证据标记、失效 Skill 退出活跃发现 | approved |

`EXEC-142-012`、`EXEC-142-013` 是对 ODR-142-003、ODR-142-005 的阶段性实施，不改变这两项决策的约束：Worker credential v1、pool identity route、Gateway strict Worker principal/lease、Biz Provider preselect/prebind、definitive terminal、Claude tenant 与 best-effort audit writer 事务隔离已有本机证据，但不构成 P2 完成或 external enablement 批准。以下事项不属于本次八组建议的完整决策，或虽有方向结论但实现仍未闭环，继续保持待确认/待实施：

- 平台 `NAVIGATOR_EXTERNAL_ENABLED` 与 Gateway `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 的组合启动/readiness 约束，以及 task token pause/generation、轮换责任和撤销传播实现。
- Codex Worker credential 需要与模型可控 CLI/MCP/工具进程隔离后才能安全转发；LangGraph 的进程内 credential、临时 askpass 和同 UID 子进程仍不构成 OS 级隔离，external-enabled 继续保持未批准。
- Java LangGraph Gateway client 仍是 internal/headerless 路径；Open API prebind 后远端 submit 成功但本地 bind 失败时只撤销 token，尚未取消可能存在的远端孤儿任务。
- pool/worker 双向命名冲突已对新写入做 service guard；存量冲突扫描、并发唯一性与显式 `routeKind`/schema 仍待设计和验证。
- 关键审批、拒绝、恢复和终态事件的 reliable audit/outbox；当前 audit writer 的事务隔离仍只是 best-effort telemetry，不可替代可靠落档。
- P2 L3 集成矩阵、共享数据库 migration、launcher `ddl-auto=validate`、真实网络与外部 Worker 浏览器反馈证据；Repository CI 7-job hosted 矩阵已经通过，但不能替代这些运行态证据。补齐前 external-enabled 必须保持默认关闭和 unready。
- upstream user mapping/grant 的权威数据源、tenant 迁移策略和最终审计留存要求。
- Provider state envelope v1 的严格校验、typed schema 演进、未知版本策略、兼容窗口和迁移 Owner。
- 超大类拆分优先级及可接受的阶段性边界，特别是 `ClaudeWorkerView.vue` 的渐进拆分顺序。

## 文档清单

- [REQ-001 平台治理与历史能力收口需求](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [REQ-002 结构化错误诊断与临时分享链接](./requirements/REQ-002-structured-error-diagnostics-and-share-links.md)
- [模块职责](./module-responsibility.md)
- [代码清单](./code-inventory.md)
- [实施计划](./implementation-plan.md)
- [Owner 决策记录](./owner-decision-review.md)
- [执行提示词](./execution-prompt.md)
- [实施与门禁进度](./progress.md)
- [GOV-001 内外部信任边界](./workitems/GOV-001-internal-external-trust-boundary.md)
- [GOV-002 Biz Worker 与 upstream user 边界](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)
- [GOV-003 Session/Task 资源归属](./workitems/GOV-003-session-task-resource-ownership.md)
- [GOV-004 CLI 非主动终止与生命周期可观测性](./workitems/GOV-004-cli-non-termination-and-lifecycle-observability.md)
- [GOV-004 实现质量记录](./quality/GOV-004-cli-non-termination-and-lifecycle-observability-implementation-quality.md)
- [GOV-004 覆盖审计](./coverage/GOV-004-cli-non-termination-and-lifecycle-observability-coverage-audit.md)
- [GOV-004 验收记录](./acceptance/GOV-004-cli-non-termination-and-lifecycle-observability-acceptance.md)
- [GOV-004 运行手册](./runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md)
- [OPT-001 构建与 CI 基线](./workitems/OPT-001-build-and-ci-baseline.md)
- [BUG-001 LangGraph 实时工具进度事件重复](./workitems/BUG-001-langgraph-progress-event-duplication.md)
- [BUG-002 Open SDK clean test 基线](./workitems/BUG-002-open-sdk-clean-test-baseline.md)
- [BUG-003 无租户 Session owner 访问回归](./workitems/BUG-003-tenantless-session-owner-access-regression.md)
- [BUG-004 空 tenant Task ownership 回归](./workitems/BUG-004-blank-tenant-task-ownership-regression.md)
- [BUG-010 NEW_SESSION 转发 App Server runtime affinity](./workitems/BUG-010-session-forward-app-server-runtime-affinity.md)
- [BUG-010 红绿测试证据](./evidence/BUG-010-session-forward-app-server-runtime-affinity.md)
- [BUG-013 Codex App Server 长 Thread 工具退化与原生压缩](./workitems/BUG-013-codex-app-server-long-thread-tool-loss.md)
- [BUG-013 运行态与固定 CLI 证据](./evidence/BUG-013-codex-app-server-long-thread-tool-loss-20260717.md)
- [OPT-002 核心代码可维护性](./workitems/OPT-002-core-code-maintainability.md)
- [CLEAN-001 低风险孤儿清理](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)
- [CLEAN-002 Monitoring 退役](./workitems/CLEAN-002-monitoring-retirement.md)
- [CLEAN-003 metadata-query 退役审计](./workitems/CLEAN-003-metadata-query-retirement-audit.md)
- [CLEAN-004 实验性与兼容能力治理](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)
- [DOC-001 文档对齐](./workitems/DOC-001-documentation-alignment.md)
