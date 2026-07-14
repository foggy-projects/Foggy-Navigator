---
type: governance
version: 1.4.2-SNAPSHOT
ticket: GOV-001
priority: high
status: in-progress
source: REQ-001
owner: platform-security-owner | session-owner | provider-owner
---

# 内部控制面与外部运行面信任边界

## 文档关联

- 版本索引：[1.4.2-SNAPSHOT](../README.md)
- 总需求：[REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- 实施阶段：[P0 边界冻结](../implementation-plan.md#p0目标边界术语和代码清单冻结)、[P2 外部边界](../implementation-plan.md#p2外部-biz-worker-与-upstream-user-边界治理)、[P3 ownership](../implementation-plan.md#p3sessiontask-定向-ownership-治理)
- 状态与证据：[Progress](../progress.md)
- 相关工作项：[GOV-002 Biz Worker 与 upstream user 边界](./GOV-002-biz-worker-and-upstream-user-boundary.md)、[GOV-003 Session/Task 资源归属](./GOV-003-session-task-resource-ownership.md)
- 代码与职责：[Code Inventory](../code-inventory.md)、[Module Responsibility](../module-responsibility.md)

## 当前状态

| 项目 | 状态 | 说明 |
|---|---|---|
| Workitem | in-progress | P2 显式外部门禁与 readiness 消费已部分落地；task token、identity、audit 与 P3 ownership 仍未实施 |
| Implementation | partial | implementation_started: yes；完成平台 Open API 默认关闭门禁、三类 Worker external profile 与平台 readiness 消费 |
| Automated test | partial-passed | Java 定向 74 tests，10/10 reactor SUCCESS；Codex SDK 163 pass/1 skip、app-server 272 pass/1 skip、LangGraph 766 pass，三类 Worker 构建通过 |
| Manual verification | not-run | 尚未在双用户、双 ClientApp 或外部 Worker 环境验证 |
| Experience verification | not-run | 尚未验证内部 UI 和外部配置/错误反馈 |
| Production routing | unchanged | production_routing_changed: no |
| External contract | scoped-change | 新增显式关闭/未就绪语义；未启用 external-enabled，不代表对外可用或生产批准 |

本文已包含 `EXEC-142-008` 本地实施证据；其余源码结论仍是静态扫描，不是运行流量、外部部署或生产批准证据。后续执行结果统一回写 [Progress](../progress.md)。

## 目标

1. 冻结 Foggy Navigator 的内部控制面、外部运行面和系统内部执行主体边界。
2. 让每类入口都有明确的可信主体、允许资源、凭据类型、拒绝语义和审计责任。
3. 在不重写 Spring Security、不引入通用 RBAC/ABAC 的前提下，将资源归属和外部授权收敛到稳定的 service/facade 不变量。
4. 保留显式的 loopback/internal-dev 开发模式，同时确保 external-enabled 模式缺少必要 credential 时 fail closed 或 unready。
5. 为 GOV-002、GOV-003 的实现、测试、迁移、灰度和回滚提供统一术语与门禁。

## 范围

### 内部控制面

- Navigator 内部 UI、可信内网 API、用户 Session/Task 操作。
- 内部开发者、可信管理员、系统任务和 A2A 调度主体。
- `AuthInterceptor`、`@RequireAuth`、`UserContext` 与 service/facade ownership 的组合边界。
- Session/Task 读取、消息、操作、取消、恢复、父子任务和 SSE 订阅。

### 外部运行面

- ClientApp runtime/control credential 与 Open API。
- LangBizWorker、CodexBizWorker、Worker Gateway、Open SDK 和 upstream user。
- BusinessTask、task-scoped token、BusinessFunction 调用、暂停、审批、恢复、拒绝和取消。
- 外部 Worker 的监听地址、认证模式、readiness、工作目录和工具能力边界。

### 系统内部执行主体

- Provider adapter、Worker launcher、异步恢复事件和系统维护任务。
- 这类主体不得伪装为普通用户；所需例外必须具名、最小授权、可审计。

## 非目标

1. 不在 1.4.2 重写 Spring Security 或统一迁移所有 Controller。
2. 不构建通用 RBAC/ABAC、策略语言或全平台 IAM。
3. 不关闭所有 Claude、Codex、Gemini、LangGraph 的本地开发模式。
4. 不在本工作项实现多实例 SSE 事件总线或动态插件加载。
5. 不为未投产的旧 Provider API/SPI/DTO 建立仓外兼容窗口；但本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 迁移、安全语义复核和 clean build 完成前不物理删除。
6. 不把内部可信内网假设扩展到 ClientApp、upstream user 或非 loopback Worker。

## 目标信任矩阵

| 调用面 | 可信主体来源 | 必须绑定的资源 | 凭据/上下文 | 默认失败行为 | 主要工作项 |
|---|---|---|---|---|---|
| 内部普通用户 | 已认证 `UserContext` | user -> session -> task/message | 内部登录态与 ownership | 资源不可见或拒绝，不泄露是否存在 | GOV-003 |
| 内部管理员 | 已认证管理员 principal | 显式管理范围 | 具名 role/scope，不接受请求体伪造主体 | 无明确例外则拒绝并审计 | GOV-003 |
| ClientApp runtime | runtime credential 解析结果 | tenant、ClientApp、route、upstream user grant、session/task | 短期 runtime token | 无效、过期、撤销或 scope 不匹配即拒绝 | GOV-002 |
| ClientApp control | control credential principal | tenant、ClientApp、审批/恢复对象 | control scope | scope 或绑定不匹配即拒绝并审计 | GOV-002 |
| Biz Worker | 由受控任务启动上下文派生 | task、session、worker pool、workspace、tool/function scope | task-scoped token | token 缺失、过期、终态或越权即拒绝 | GOV-002 |
| 系统/A2A 执行 | 具名 system principal 或可信调度上下文 | 明确的父子 session/task | 系统身份，不复用普通 userId 字段 | 未登记的系统例外拒绝 | GOV-003 |
| 旧 Provider API | 当前本仓兼容入口 | 本仓消费者与运行/暂停任务状态 | 现有轻量认证或旧参数 | 本仓迁移、安全复核和 clean build 后同版删除，无仓外窗口 | GOV-002/CLEAN-004 |

## 已确认事实

1. Foggy Navigator 当前是内部多 Worker 远程编程工作台，产品主线不是语义层或数据分析平台。
2. 本版本已确认保留内部轻量认证模式，不以大规模 Spring Security 重构作为治理方案。
3. `SecurityConfig`、`AuthInterceptor`、`@RequireAuth`、ClientApp credential 和 task-scoped token 共同构成当前多层认证/授权边界，不能只依据 Spring Security 的 URL 规则判断接口是否匿名。
4. `UnifiedSseController` 的订阅入口已经检查当前用户是否拥有目标 Session；`UnifiedSseEmitter` 本身仍是单 JVM 内存态。ownership 治理应保持现有正向校验，而不是重复设计 SSE 总线。
5. 新的 BusinessFunction 审批入口已经使用 control credential principal，恢复服务会保存和复核 tenant、ClientApp、upstream user、task/session、function/version 和 input hash 等绑定信息。
6. 1.3.3 已形成 credential lane、ClientApp 运行资源和隔离 smoke 的历史输入，但其未完成项和隔离结果不能替代 1.4.2 的运行态验证或生产批准。

## 静态搜索结论

### 认证与授权分层

- `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` 对 Session、Task、旧 Provider、Open API、Business Agent 和 Worker Gateway 等多类路径使用 `permitAll`。
- `user-auth-module/src/main/java/com/foggy/navigator/auth/interceptor/AuthInterceptor.java` 在凭据有效时建立上下文，但拦截器自身不统一阻断所有请求；强制认证还依赖 `@RequireAuth` 或入口专用 credential/token 校验。
- 因此 P0 必须建立“入口 -> principal resolver -> resource invariant -> audit”的清单，禁止把 `permitAll` 直接等同为匿名可调用，也禁止把 `@RequireAuth` 等同为已经完成资源归属校验。

### 内部资源边界

- `SessionController` 的删除路径带当前用户参数，但若干读取、消息和发送路径在 Controller 调用链中只传 `sessionId`。
- `TaskController` 的单任务路径存在当前用户上下文，而按 Session 列表路径调用 `TaskDispatchFacade.listTasksBySession(sessionId)`。
- `AgentTaskController` 和 `AgentTaskRepository.findByParentSessionId` 的静态签名未携带当前用户。
- 以上是“ownership 谓词不一致”的静态结论，不在没有负向测试时宣称已证实跨用户利用。

### 外部边界

- ClientApp runtime/control credential、upstream user grant、BusinessTask token 和 Worker Gateway 已有分层实现，但通用 Open API 的部分 task/session 查询静态谓词主要停留在 tenant + agent，未统一绑定 ClientApp/upstream user。
- 旧 `LanggraphTaskController` 的 GET 接受 `userId` 参数，审批入口按 `taskId` 和请求体处理；`LanggraphTaskService` 会使用请求体 `reviewedBy`。该路径与新的 control credential 审批链路安全语义不一致。
- LangGraph 和 Codex SDK Worker 在 internal-dev 下仍保留 Token 为空时跳过 bearer 认证、默认监听 `0.0.0.0` 的既有开发行为；本批已为显式 external-enabled 增加 fail-closed/unready 门禁和可观测 reason，但 internal-dev 并不是网络防火墙，仍必须依赖可信网络、ACL 或主机防护。
- Worker 的工作目录和工具策略已有实际校验实现，但空 allowlist、caller 可选的 sandbox/approval/network 参数和 external-enabled 默认上限仍需统一。

## 运行态待证

| 待证项 | 所需证据 | 未证实前的限制 |
|---|---|---|
| 当前 dev 部署是否存在非 loopback + 空 Worker Token | 部署配置、进程参数、监听地址、readiness 响应与网络/ACL | 不宣称已完成运行态隔离验证；external-enabled 保持未批准 |
| 同 tenant/Agent 下不同 ClientApp/upstream user 是否可读取相同 task/session | 双 ClientApp 负向 API 测试与访问日志 | 静态谓词不足不等于已复现漏洞 |
| 内部 sessionId/taskId 是否可跨用户枚举和操作 | 两账号集成测试、UI/API 复现记录 | 不宣称 exploitability 已确认 |
| 旧 Provider API 的本仓消费者 | PC、Mobile、SDK、CLI、L3、Worker、canary 的静态引用、契约测试与 clean build | 迁移和安全语义复核完成前不删除；完成后可同版物理删除，不等待仓外流量或兼容窗口 |
| credential/token 的实际撤销、轮换和传播 | 管理 API、数据库、缓存、重启、多实例演练 | 不宣称生命周期闭环 |
| 系统/A2A 任务所需 ownership 例外 | 任务样本、调用栈、Owner 说明 | 不允许通过宽泛 bypass 解决 |
| 审计持久化、留存和拒绝事件覆盖 | 数据库表、索引、日志、查询、失败演练 | best-effort 日志不等同验收证据 |

## 已批准治理基线与执行状态

| 决策 | 已批准基线 | 当前执行状态 |
|---|---|---|
| 运行模式 | ODR-142-004：`internal-dev` / `external-enabled` 双模式；external 配置意图必须显式、默认关闭，缺凭据或安全策略时 unready/fail closed | 平台与三类 Worker 的开关/readiness 骨架已实施；完整 execution policy 与 external enablement 未完成 |
| upstream user 证明强度 | ODR-142-002：dev/internal 使用 ClientApp credential + mapping/grant 并标记 delegated assurance；signed assertion 延后为未来真正外部开放门禁 | 不阻塞当前 P2；external enablement 保持 `no` |
| task token | ODR-142-003：服务端权威 opaque token、30 分钟租约、完整授权交集、task lease + Worker principal 双重绑定、暂停/终态失效、撤销和 generation 轮换 | 尚未实施，不发布新 token 契约 |
| 外部 Worker 安全上限 | ODR-142-004：目录/工具默认拒绝、`workspace-write`、任务工具 egress 默认拒绝，非 loopback 缺凭据 unready/fail closed | 仅模式门禁骨架完成；workspace/tool/sandbox/network 上限未完成，external 保持 unready |
| 审计保证级别 | ODR-142-005：本地关键状态事务 outbox、无状态拒绝可靠安全事件、远程调用意图/结果分段记录、高频遥测 best-effort | 尚未实施，不宣称关键审计链路完成 |

## 仍待实施级 Owner 决策

| 决策 | Owner | 最晚时间 | 未决处理 |
|---|---|---|---|
| 每类入口的 principal authority 和可信字段表 | Security/Module owners | 对应 P2/P3 切片设计前 | 对外入口保持默认关闭，不扩大可信字段 |
| 管理员、系统任务、A2A 的具名 ownership 例外 | Session/A2A owner | P3 设计前 | 不引入通用 bypass |
| ClientApp/upstream user mapping/grant 的权威数据源和迁移策略 | ClientApp/Upstream owner | P2 identity 切片设计前 | 沿用现有数据源但不宣称生命周期闭环；不得以请求体 actor 替代权威 mapping |

## 关键代码路径

### 认证与内部边界

- `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java`
- `user-auth-module/src/main/java/com/foggy/navigator/auth/interceptor/AuthInterceptor.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/AgentTaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java`
- `session-module/src/main/java/com/foggy/navigator/session/sse/UnifiedSseEmitter.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`

### 外部边界

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/config/ExternalSurfaceProperties.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/filter/ExternalSurfaceGateFilter.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/health/ExternalSurfaceHealthController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppRuntimeCredentialResolver.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppControlCredentialService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java`
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/controller/LanggraphTaskController.java`
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/auth.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/external_mode.py`
- `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/health.py`
- `tools/codex-agent-worker/src/auth.ts`
- `tools/codex-agent-worker/src/external-mode.ts`
- `tools/codex-agent-worker/src/routes/health.ts`
- `tools/codex-app-server-worker/src/external-mode.ts`
- `tools/codex-app-server-worker/src/routes/health.ts`

## 本批实施 Check-in（`EXEC-142-008`）

### 已完成内容

1. 提交 `12cbe697` 增加平台 `NAVIGATOR_EXTERNAL_ENABLED`，默认 `false`；门禁仅覆盖规范化后的 `/api/v1/open` 及其子路径，关闭时返回 HTTP 503 + `EXTERNAL_SURFACE_DISABLED`。路径 matrix/context/encoded 回归曾发现规范化绕过，已在同批修复并回补测试。
2. 平台健康入口 `/api/v1/health/external-surface` 输出开关与路由面就绪状态；`surfaceReady=true` 只表示 Open API routing gate 打开，不表示 Provider、外部执行策略或 production ready。
3. 提交 `5d62707b` 为 LangGraph Biz Worker、Codex SDK Worker 和 Codex app-server Worker 增加严格布尔开关 `BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`，仅接受 `true` / `false`，默认 `false`，mode 统一为 `internal-dev` / `external-enabled`。
4. 显式外部模式当前因 `EXTERNAL_EXECUTION_POLICY_PENDING` 始终 unready；Token 为空时叠加 `EXTERNAL_AUTH_TOKEN_REQUIRED`。除精确 `/health` 外，业务 API 统一返回 HTTP 503 + `EXTERNAL_WORKER_UNREADY`；`/health/` 不是豁免路径，显式外部模式下可返回 503。
5. 提交 `cce75f1b` 让平台消费 Worker `ready=false`：LangGraph 标记 `OFFLINE`，Codex SDK connection tester 判定 unready；对尚未输出 `ready` 字段的旧 Worker 继续保留 HTTP 200 兼容。

### 边界和未完成项

- 平台开关不覆盖 upstream-admin、`/internal/worker-gateway/v1/**` 或其他内部 Controller；它不是全局 SecurityConfig 替代品。
- internal-dev 不是网络防火墙。LangGraph/Codex SDK 默认 `0.0.0.0` 且空 Token 跳过 bearer 的开发行为未改，只能放在 loopback 或受信网络/ACL 中。
- task-scoped token、upstream identity authority、BusinessFunction 审批/恢复/取消审计、Session/Task ownership 尚未实施；本批不是 GOV-001/GOV-002 完成或生产就绪证据。
- production routing 未改变，没有启用 external-enabled；外部契约仅新增显式关闭和未就绪语义。

### 实施自检与测试

- Java 最终三模块定向矩阵：74 tests，10/10 reactor SUCCESS；其中平台门禁 8 项 + Open API mapping 40 项，平台批次合计 48 项。
- Codex SDK Worker：163 passed / 1 skipped，type-check 和 build 通过。
- Codex app-server Worker：272 passed / 1 skipped，type-check 和 build 通过。
- LangGraph Biz Worker：766 passed，build 通过。
- self_check_decision: `continue-in-progress`；跨模块实现证据已回写，但手工网络/部署验证、浏览器体验和正式质量闸门均未执行。

## 实施步骤

### 1. 冻结术语和入口清单

1. 为每个入口登记 URL/协议、调用面、principal resolver、resource resolver、scope、审计和 Owner。
2. 将入口标注为 `internal-dev`、`trusted-intranet`、`external-runtime` 或 `system-internal`，禁止模糊的“内部接口”标签。
3. 标出旧 Provider API 和仅适用于可信内网的管理员/开发能力。

### 2. 冻结 principal 与资源不变量

1. 明确 tenant、ClientApp、upstream user、Navigator user、system principal 的来源和禁止信任字段。
2. 定义 `requireOwnedSession`、`requireOwnedTask`、`requireAuthorizedBusinessTask` 或等价窄门面，不要求名称完全一致。
3. 确认 Controller、Provider adapter 和异步恢复处理器只能消费可信门面输出，不能自行信任请求体身份。

### 3. 建立模式和 readiness 契约

1. 已实施平台与三类 Worker 显式、默认 `false` 的外部开关；internal-dev 只能依赖 loopback 或受信开发网络/ACL，不能将 mode 标签当作网络隔离。
2. external-enabled 当前因外部执行策略未就绪而统一 unready；后续仍须实施 credential、监听地址、workspace/tool policy 和依赖 readiness 的完整求交集。
3. health 已区分 process-live、surface routing-ready 与 external-ready；任何外部门禁未齐不得通过进程存活冒充对外就绪。

### 4. 对接 GOV-002 与 GOV-003

1. GOV-002 负责外部 principal、token、Worker、BusinessFunction 和审批恢复链路。
2. GOV-003 负责内部 Session/Task ownership 及具名管理员/系统例外。
3. 共享错误语义和审计字段，防止同一 task 在不同入口使用不同归属规则。

### 5. 兼容、灰度和运行态审计

1. 旧 Provider API/SPI/DTO 先完成本仓 PC、Mobile、SDK、CLI、L3、Worker、canary 迁移、安全语义复核和 clean build，再于同版物理删除；不要求仓外流量审计或兼容窗口。
2. 外部 enforcement 按 ClientApp/Worker 显式 allowlist 灰度；不得用全局降级重新信任请求体字段。
3. 对所有拒绝路径验证不会暴露资源存在性或明文凭据。

### 6. 证据和签收

1. 自动化、手工和体验结果按 commit、环境、命令、退出码回写 Progress。
2. P2/P3 完成后执行实现质量检查和覆盖审计；正式验收仍在 P7。
3. isolated smoke 与 production enablement 分开记录。

## 自动化测试计划

当前状态：`partial-passed`；显式开关、路径门禁、Worker unready 与平台 readiness 消费已有 `EXEC-142-008` 自动化证据，资源归属、token、身份与审计测试仍未运行。

1. 入口契约测试：每类入口在缺 credential、错误 credential、错误 scope 下 fail closed。
2. 内部 ownership 负向测试：两用户交叉 sessionId/taskId 的读取、消息、操作、取消和恢复。
3. ClientApp 隔离测试：双 tenant、双 ClientApp、双 upstream user、双 task 的全组合拒绝矩阵。
4. Worker readiness 测试：internal-dev 的既有监听、空 Token 与业务入口认证行为保持，平台对显式 `ready=false` 的判定收紧；external-enabled 当前因执行策略未就绪而拒绝业务 API，空 Token 叠加认证缺失 reason。已完成单元/契约测试，非 loopback 真实部署验证仍 `not-run`。
5. 身份字段伪造测试：请求体/header 中的 `tenantId`、`userId`、`reviewedBy` 不得覆盖 credential principal。
6. 审计测试：成功、拒绝、过期、撤销、审批、恢复、取消和失败产生规定字段，且不包含明文 token。
7. 兼容测试：内部 UI、Provider 统一任务接口和明确保留的旧入口在兼容期行为可预期。

执行命令需在实现阶段按真实模块脚本确定，本规划不虚构命令结果。

## 手工验证计划

当前状态：`not-run`。

1. 使用两个 tenant、每个 tenant 两个 ClientApp、每个 ClientApp 两个 upstream user 创建独立任务。
2. 验证正确主体可读取和操作自己的任务；交换任一 tenant、ClientApp、user、task、function 后被拒绝。
3. 用两个 Navigator 内部账号验证 Session/Task 主链和跨用户拒绝。
4. 分别以 loopback internal-dev 和 non-loopback external-enabled 启动 LangGraph/Codex Worker，核对 health/readiness 和认证模式。
5. 检查日志与审计表只记录凭据标识/哈希或安全摘要，不记录明文 token 与完整敏感输入。

## 体验验证计划

当前状态：`not-run`。

1. 内部 UI：新建/继续会话、任务流式输出、刷新、深链、审批、恢复、取消不出现大面积回归。
2. 越权反馈：用户看到稳定且不泄露资源存在性的错误，不出现空白页或无限重试。
3. Worker 配置：缺凭据、非 loopback 和策略冲突在启动/readiness 中给出可理解的修复提示。
4. ClientApp 集成：token 过期、撤销、scope 不匹配的响应可被 SDK/上游系统区分并安全重试或停止。

## 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 把 `permitAll` 误判成完全匿名 | 规划错误或重复实现 | 按完整认证调用链登记入口 |
| 收紧边界破坏本仓孵化链路 | dev/internal 契约回归 | 保留 internal-dev；旧契约按本仓消费者矩阵迁移、安全复核和 clean build 后同版删除 |
| 宽泛 system/admin bypass | 形成新的横向越权面 | 例外具名、最小范围、强审计 |
| external/internal 模式配置混淆 | 空 Token 外部暴露 | 启动校验 + readiness + 部署策略三层门禁 |
| 审计 best-effort 丢失关键拒绝 | 无法追责或验收 | Owner 决定强保证级别并做失败演练 |
| 静态结论被当成生产事实 | 误删或错误签收 | 运行态待证项保持未完成状态 |

## 回滚方式

1. 术语、矩阵和代码清单用独立文档提交，可通过 `git revert` 回退错误规划并保留勘误记录。
2. 实现阶段将 principal resolver、resource invariant、readiness、审计和旧接口迁移分提交，禁止混成不可逆大提交。
3. 新 enforcement 使用显式配置或 ClientApp/Worker allowlist 灰度；回滚只能恢复已批准的旧兼容路径，不能恢复对 `userId`、`tenantId`、`reviewedBy` 等请求字段的直接信任。
4. 数据/schema 变更必须保留兼容读取与迁移回滚；凭据明文或已撤销凭据不得因回滚重新启用。
5. 生产路由或 external enablement 的回滚由独立 runbook 和审批控制，不由本 workitem 的 planned 状态自动授权。

## 完成判据

- [ ] 所有对内、对外和系统入口都有 principal、资源、scope、审计与 Owner 清单。
- [ ] internal-dev、trusted-intranet、external-enabled 和 system-internal 的配置边界冻结。
- [ ] 请求字段身份与可信 principal 的取值/校验规则明确，并被 GOV-002/GOV-003 采用。
- [ ] Session/Task ownership 和 BusinessTask/task-token 不变量在统一门面落地，不依赖各 Controller 自行解释。
- [x] 显式 external-enabled 在执行策略未齐时 unready，空 credential 会额外记录原因并 fail closed；已有本地契约测试，真实非 loopback 部署验证仍待执行。
- [ ] 旧 Provider API 的本仓消费者迁移、安全语义复核、clean build 和回滚证据完成，并在同版物理删除。
- [ ] 自动化、手工和体验矩阵均有实际结果；任何 `not-run` 都有阻塞或移出版本说明。
- [ ] 内部 UI/可信内网主链没有大面积回归，外部负向矩阵通过。
- [ ] isolated acceptance 与 production enablement 分别记录。
- [ ] Progress、质量检查、覆盖审计和正式签收引用完整。

## 生产路由与外部契约状态

- 当前：`production_routing_changed: no`；external contract 仅新增显式关闭/未就绪语义，未启用 external-enabled。
- 实施影响：平台默认关闭 `/api/v1/open` 路由面，三类 Worker 仅在显式 external-enabled 配置意图下新增 unready/503 语义；internal-dev 的监听、空 Token 与业务入口认证行为保留，但平台对 health 缺失或显式 `ready=false` 的路由判定已收紧，可能影响内部异常场景。P3 越权响应仍未实施。
- 启用门禁：任何 production routing、外部强制认证或旧入口下线必须有独立批准、灰度、监控和回滚证据，不能因本 workitem 完成而自动启用。
