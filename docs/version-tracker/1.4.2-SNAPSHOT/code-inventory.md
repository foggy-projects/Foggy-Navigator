# 1.4.2 代码与文档清单

## 文档作用

- doc_type: code-inventory
- intended_for: root-controller | execution-agent | reviewer | module-owner
- purpose: 以可核对路径冻结 1.4.2 的创建、更新、只读审计和禁止触碰清单。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: in-progress
- owner_decision_status: review-complete
- authorized_cleanup_scope: development-only; data-discard-approved
- requirements: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md), [REQ-002](./requirements/REQ-002-structured-error-diagnostics-and-share-links.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- inventory_rule: 路径为当前静态扫描与执行结果；计划动作和已执行动作分别标注，真实状态以 [Progress](./progress.md) 为准。

## 变更分类

| 分类 | 含义 |
|---|---|
| `create` | 1.4.2 规划或执行时新增的文档、测试或窄边界实现 |
| `update` | 已有文件的定向变更；实施前需重新确认行级上下文 |
| `read-only-analysis` | 只做引用、配置、数据或依赖审计，未满足门禁不得修改 |
| `delete-authorized` | Owner 已批准在明确的 dev-only 范围内物理删除；仍须处理仓内引用、确认不命中共享/生产资源并完成测试和回滚记录 |
| `removed-pending-verification` | 授权切片已从工作树移除，但删除后构建、定向回归、启动/体验或文档收口尚未完成；不得标记 completed |
| `in-progress-implemented` | 工作项已有一部分实现和本地证据，但剩余范围或后置门禁尚未完成 |
| `code-slice-removed` | 完整代码切片已在独立提交中物理移除并完成已登记的本地静态/自动化回归；外部资源、体验、hosted CI 或正式验收按说明保留未完成状态 |
| `create-completed-local` / `update-completed-local` | 对应新增或更新路径已在当前批次落地，并完成已登记的本地自动化验证；不表示整个工作项或版本完成 |
| `completed-local` | 代码、装配、当前文档与本地自动化门禁已经收口；启动/浏览器、hosted CI 或正式验收若未执行仍须单独标记 |
| `do-not-touch` | 1.4.2 明确保留，或必须先完成迁移/备份/轮换 |

## 本轮实际文档落档

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `docs/version-tracker/1.4.2-SNAPSHOT/README.md` | 版本索引 | create | 版本状态、范围、工作项和门禁 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/requirements/REQ-001-platform-governance-and-legacy-cleanup.md` | 需求基线 | create | 产品定位、治理边界、验收标准 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/module-responsibility.md` | 模块职责 | create | 依赖方向与 Owner 交接点 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/code-inventory.md` | 代码清单 | create | 本文件 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/implementation-plan.md` | 阶段计划 | create | P0-P7 执行与回滚门禁 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/owner-decision-review.md` | Owner 决策评审 | create | 八组决策已完成评审；批准不等于实现、测试或生产启用 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/execution-prompt.md` | 开工提示 | create | 后续执行 Agent 的范围和记录要求 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/progress.md` | 进度模板 | create | 与 execution prompt 配套，当前不写虚假证据 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/workitems/*.md` | 工作项 | create | 10 个计划工作项：3 个治理、2 个优化、4 个清理、1 个文档；另有实施期 BUG-001、BUG-002 两个缺陷记录 |
| root | `docs/version-tracker/README.md` | 总版本索引 | update | 只增加 `1.4.2-SNAPSHOT` 链接 |

## 治理与授权触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` | 内外 API 安全装配 | read-only-analysis | 记录可信内网和外部入口，不以全局重构解决 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/SessionTaskResourceAccessService.java` | Session/Task ownership 窄门面 | create-completed-local | 强制非空 `userId + tenantId`；Task 同时校验关联 Session；owner 缺失/冲突、不存在或软删除统一 fail closed；无隐式 admin/system bypass |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java` | Session API | update-completed-local | create parent、get/delete/messages/latest/send 与 tenant-aware list 首批路径先经 ownership；未覆盖所有 page/search/directory 列表 |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`、`session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java` | Task 查询与操作 | update-completed-local | get/list/respond/reconnect/resync/rewind/resume/cancel/delete/scan 首批路径传递可信 user/tenant；cancel route 取自已授权持久化 Task，不信任请求体 agent 字段 |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java`、`addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/repository/LanggraphApprovalRepository.java` | LangGraph 审批/恢复路由 | update-completed-local | `9f3f1422` 改由统一 `/api/v1/tasks/{taskId}/respond` 调用；Task 先按已认证 `userId + tenantId` 授权，Provider 再按同一 user 解析 pending approval，`reviewedBy` 强制取认证主体，不信任请求体身份字段 |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/AgentTaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java` | Agent 子资源与委派入口 | update-completed-local | 按 Session 查询子资源前授权父 Session；任务查询/取消校验 Task 归属和 agent route |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java` | SSE 与 Session 配置 | update-completed-local | 单项及批量操作先校验完整资源集合，再订阅或读写；当前批量校验的查询数量/性能仍待基准 |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SharedTaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java` | Sharing Key 调用 | update-completed-local | 从 key owner 解析 user/tenant；授权和 Agent readiness 后再原子校验 operation/消费 quota，拒绝或 unready 不应消耗额度 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SessionRelationController.java` | Session 转发与关系 | update-completed-local | source/target/root/incoming relation 及 latest target task 使用 user/tenant 归属，不以裸 Session ID 读取或修改 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java`、`session-module/src/main/java/com/foggy/navigator/session/service/AgentContextOwnershipClaimWriter.java`、`session-module/src/main/java/com/foggy/navigator/session/repository/AgentConversationContextRepository.java` | context owner/agent 绑定 | create/update-completed-local | assigned-ID 初始声明使用 `REQUIRES_NEW persist + flush`；冲突重读胜出者；后续 owner/agent 条件更新，避免无条件 merge 的 TOCTOU 覆盖 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java` | Session metadata/model credential | update-completed-local | 显式 model config 必须存在、enabled、tenant 一致、owner metadata 完整并通过 Worker grant 后才解析凭据；service-level tenant invariant 与 owner/grant 语义仍待收敛 |
| root | `session-module/src/main/java/com/foggy/navigator/session/repository/SessionRepository.java`、`navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java` | owner-aware 查询 | update-completed-local | 增加 user/tenant 联合查询支撑窄门面和首批列表；`active/page/search/directory` 全路径 tenant 贯穿尚未完成 |
| root | `session-module/src/main/java/com/foggy/navigator/session/sse/UnifiedSseEmitter.java` | 单 JVM SSE | read-only-analysis | 记录限制；多实例总线不在本版本实现 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | ClientApp Open API | update-in-progress | Biz Provider 在 submit 前 preselect/prebind exact Worker/lease 并注入 runtime capability；非 Biz Provider 不签发 Gateway capability；submit/bind 失败 best-effort 撤销 token，但 bind 失败尚未取消可能已创建的远端任务；查询/操作 ownership 仍待收敛 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppRuntimeCredentialResolver.java` | runtime credential 解析 | update | 复核 TTL、撤销、轮换和 scope |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppUserGrantService.java` | upstream user grant | update | 1.4.2 维持 ClientApp credential + mapping/grant 基线并记录 delegated assurance；signed assertion 降为低优先级后续项 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BizWorkerIdentityEntity.java`、`repository/BizWorkerIdentityRepository.java`、`service/BizWorkerCredentialService.java` | Worker credential v1 schema 与生命周期 | in-progress-implemented | 已落 `@Version`、credential version/签发/过期/撤销/轮换时间、owner-scoped 写锁、服务端一次性 `bwc_` secret、hash-only 存储和严格校验；strict verifier 已接入 Gateway HTTP principal 链，OS 隔离与 credential 生命周期审计仍待办 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BizWorkerCredentialController.java`、`controller/UpstreamAdminWorkerCredentialController.java` | Worker credential 管理 API | in-progress-implemented | 平台 SUPER_ADMIN 与 upstream `WORKER_MANAGE` 可按 owner rotate/revoke；rotate 响应禁缓存，legacy v0 不被 strict auth 接受；外部入口仍默认关闭/unready |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/BusinessTaskScopedTokenProperties.java` | task token TTL 配置 | create-completed-local | 默认 30 分钟、硬上限 60 分钟；schema/运行态总门禁尚未完成 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java` | task token 持久 claims | update-completed-local | v2 claims、结构化 function snapshot、`@Version`、worker/lease 预留与撤销字段已落地；forward/rollback 脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证（含幂等及回滚前撤销 ACTIVE token），共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/BusinessTaskScopedTokenDTO.java` | Gateway token claims DTO | update-completed-local | 携带 v2 claims；不含 token 明文/hash |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/BusinessTaskScopedTokenRepository.java` | task token 查询 | update-completed-local | tenant/task 与 tenant/workerTask 写锁查询支撑批量撤销、终态关联和 late-bind 收口 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenPolicyService.java` | capability v2 签发与校验 | create-completed-local | 快照 ENABLED ClientApp function grants；Gateway 校验 version/generation/audience/assurance/scope |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | BusinessTask 创建/恢复/token 生命周期 | update-completed-local | 新签发、单 token/按 task 撤销、alias cleanup、definitive terminal、Biz launcher DB preselect/prebind 与 `bwl_` lease 已实现；暂停、generation 轮换、可信管理入口与远端孤儿取消仍待办 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskTerminalStateEntity.java`、`repository/BusinessTaskTerminalStateRepository.java`、`event/BusinessTaskScopedTokenTerminalListener.java` | definitive terminal tombstone | in-progress-implemented | Provider 明确发布 `recoverable=false` 的终态会在提交前写授权权威 tombstone，提交后 best-effort 物化 token REVOKED；event-before-bind 由 late bind 持久绑定并撤销后再抛专用异常；可恢复 pause/interruption 不进入该链路 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskScopedTokenRuntimeStore.java` | JVM runtime token aliases | update-completed-local | 使用结构化 `tenant/session/task` record key 避免分隔符碰撞，并新增 hash-conditional removal；仍未解决重启/多实例 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java`、`config/WorkerGatewayProperties.java`、`service/WorkerGatewayRequestAuthorizationService.java` | Worker Gateway 入口授权 | update-completed-local | 四类 HTTP 路由统一先鉴权；strict header 为 `X-Navigator-Worker-Id/Credential/Lease-Id`，partial/blank/legacy fail closed；external 默认 false，仅完全无 header 的 internal-dev 保留 token-only 兼容 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java` | 函数授权与执行 | update-completed-local | list/schema/invoke 已 enforce token snapshot 与当前授权交集；request authorization 另校验 exact Worker/lease、tenant、active ClientApp、pool/member/backend/owner/route；tool-message 精确函数 scope 和拒绝 outbox 待办 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionRuntimeAuditWriter.java`、`service/BusinessFunctionRuntimeAuditService.java` | runtime audit 写入 | in-progress-implemented | writer 以独立 `REQUIRES_NEW + saveAndFlush` 隔离提交/flush 失败，facade 捕获代理异常；仍是 best-effort telemetry，不是强保证审计或 outbox |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java` | 审批控制面 | update | 保持 credential principal；补全 task/subject 绑定负向验证 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java` | 暂停/恢复绑定 | update | 统一审批、恢复、取消归属和审计语义 |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/TaskScopedTokenResolver.java` | Worker token 注入 | update | 禁止跨任务 fallback；明确重启/恢复行为 |

## `2026-07-14` 执行快照

| 批次 | 实际路径/切片 | 状态 | 已有验证 | 尚未验证/未操作 |
|---|---|---|---|---|
| P1 构建基线 | `.nvmrc`、根 `package.json`、`.gitignore`、根 `pnpm-lock.yaml`、前端 package/scripts、`navigator-open-sdk/pom.xml` 与测试、required/nightly workflows、Navigator-owned RX shim、现有 Codex RC workflow、README/CLAUDE | in-progress-implemented | 精确 Node/pnpm frozen 校验、frontend、五类 Worker clean 等价矩阵 passed；[BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) 关闭后本机根 `mvn -B clean test` 17/17 reactor、2304 tests 全通过；`2a859336` 移除不可获取的 `foggy-core` 构建依赖；截至正式闸门的最新已验证实现 head `9d03bee9` 对应 Repository CI `29324741945` 7/7 jobs 成功，但 Java lane 只覆盖 launcher 依赖链，不含 `navigator-open-sdk` 和 `tools/navigator-chat-observer-bff` | branch protection/required checks 当前未配置；修复后 nightly、根 reactor `clean verify`、Windows/WSL clean checkout 与更完整浏览器体验未运行 |
| P2 首批外部门禁/readiness | 平台 `/api/v1/open` 路由门禁、LangGraph Biz Worker、Codex SDK Worker、Codex App Server Worker 及 Java 健康状态消费者 | in-progress-implemented | 三个独立提交：`12cbe697`、`5d62707b`、`cce75f1b`；默认关闭的显式开关、脱敏健康状态、external-enabled 503 门禁与旧 Worker 健康响应兼容逻辑已落码 | 这三个提交本身未覆盖后续 capability/credential/terminal 切片；完整 execution policy、Claude/Gemini Worker readiness、生产 readiness 与外部开放仍未完成 |
| P2 task capability v2 / Codex Biz route | `business-agent-module` token entity/DTO/config/policy/lifecycle/task/runtime store/Gateway、Open API 失败补偿、SQL migration/rollback、launcher TTL 配置、Codex Business Agent launcher 与 tests | in-progress-implemented | 5 个 reactor、770 tests 全通过，其中 business-agent 510；H2 JPA 2 tests 提供外层回滚与 bind/revoke 组合时序下的最终状态证据，不证明确定性的悲观锁交错；Open API mapping 43；LangGraph E2E 2 + Codex route/provider/service 92；forward/rollback 脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证（含幂等/安全回滚） | 共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行；本切片形成时 Worker principal/lease 尚未覆盖，后续已由 Gateway 切片接通；暂停/generation、跨实例、Open API ownership、outbox、真实 Worker/体验仍未完成；definitive terminal 已由下一行切片接通 |
| P2 Worker credential / definitive terminal / pool identity route | Worker credential v1、terminal tombstone/listener、Claude tenant、Provider terminal event、audit writer、LangGraph pool/identity route | in-progress-implemented | 当前范围最终 11/11 reactor clean test、2186 tests 全通过；credential/pool/terminal/Provider 定向 suites 通过；三组新增 migration 均在 MySQL 8.0.44/8.4.8 验证 forward×2、rollback×2、reapply；后续截至正式闸门的最新已验证实现 head 对应 hosted CI 通过 | 未执行根 `clean verify`、共享/项目数据库 migration、launcher `ddl-auto=validate`、真实 Worker/浏览器；本切片形成时未覆盖的 Gateway Worker principal/lease/prebind 已由下一行接通，pause/generation、关键审计 outbox 与外部启用仍未完成 |
| P2 Gateway principal / lease / secret boundary | Gateway properties/request authorization/controller、BusinessTask lifecycle/launch request、Open API、LangGraph/Codex Biz launcher、LangGraph Python credential/subprocess/askpass、Codex health/query/MCP/SDK env | in-progress-implemented | strict Worker headers、partial/legacy fail closed、DB preselect/prebind、exact resource binding、非 Biz 无 capability、LangGraph secret boundary 与 Codex configured-credential unready 已落地；launcher 15/15 clean reactor、2357 tests，LangGraph 780 pytest + ruff，Codex 174 pass/1 Windows skip + typecheck；后续截至正式闸门的最新已验证实现 head 对应 hosted CI 通过 | 平台/Gateway 开关组合、OS 隔离、Codex 安全转发、Java LangGraph headerless client、Open API 远端孤儿取消、pause/generation/outbox/P3/L3、routeKind/schema/存量冲突扫描、真实网络/外部浏览器未完成 |
| P3 Session/Task ownership 首批 | ownership service、Session/Task/Agent/SSE/config/shared/forward Controller/service/repository、context claim writer、model config credential guard、LangGraph unified respond 及 isolated browser test | in-progress-implemented | `2a705e09` 完成统一 `userId + tenantId`、软删除 fail closed、父资源先授权、route 不信任请求体、context conditional claim/update、Provider sessionId 再授权、model config 与 shared quota 顺序；`9f3f1422` 收口 LangGraph 审批主体；P3 定向 176 tests、launcher clean 15/15 reactor/2426 tests、隔离 H2 双用户 Session Playwright 与最新 hosted CI 均通过 | 真实 Provider Task fixture/L3、共享 DB、`active/page/search/directory` 全列表 tenant、SessionMetadata service invariant、model owner/grant 语义、Provider taskId trust 与 admin/system 显式通路仍待完成；不是正式验收 |
| Monitoring | `monitoring-module/**`、`tools/foggy-monitor/**`、PC View/API、SecurityConfig 放行、`scripts/start-all.sh` 与当前权威文档 | code-slice-removed | tracked 源码及 repo-local ignored `target/.venv/.pytest_cache` 均移除；静态扫描、shell syntax、Java clean、frontend full matrix passed | RabbitMQ/DB/deployment 等外部资源未操作；启动/浏览器 smoke 未跑 |
| Code Review | `addons/code-review-agent/**` 共 22 个 tracked files、当前开发指引 | code-slice-removed | root/launcher/CI/scripts/source 扫描与 Java clean passed | GitLab webhook、DB、独立 deployment 未操作/未做运行态读取 |
| metadata-query | `metadata-query-module/**`、根 `pom.xml`、`launcher/pom.xml`、launcher context test、`.agents/skills/metadata-query-module/**`、当前 README/架构文档 | completed-local | 模块、装配、断言、Skill 与当前文档已收口；metadata-query 删除当时根 reactor 为 16 个模块，后续 Echo 退出后当前为 15；删除后 clean test 15/15 `SUCCESS`，依赖树与 clean target 无旧查询依赖；截至正式闸门的最新已验证实现 head 对应 hosted CI 通过，版本签收已执行并拒绝 | 启动/浏览器 smoke 与模块级签收未运行；外部资源未操作 |
| Echo / 旧 Provider 契约 | `EXEC-142-015` / `50351ada`、`73d31a19`、`97240642`、`fb11137d`、`9f3f1422`、`edee0fc4`、`9008c554` | Echo `completed-local / verification-partial`；旧 Provider API/SPI/DTO 子切片 `code-slice-removed` | Echo 定向 16/16；旧契约分 Provider clean 测试、前端/L3 定向验证和最新 Repository CI 7/7 jobs 成功；版本正式门禁已执行并拒绝 | Echo 的 PowerShell parser/模块签收未运行；旧契约的真实 Provider Task/手工体验未运行；P6 巨类与 state schema 切片未完成 |

### P3 Session/Task ownership 首批（`EXEC-142-014`）

| 代码切片 | 准确路径 | 当前结论 | 未完成边界 |
|---|---|---|---|
| 统一资源归属 | `session-module/src/main/java/com/foggy/navigator/session/service/SessionTaskResourceAccessService.java`、`session-module/src/main/java/com/foggy/navigator/session/repository/SessionRepository.java`、`navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionTaskRepository.java` | Session/Task 使用 user/tenant 联合谓词；Task 关联 Session 和软删除状态再次校验；资源不存在、owner 缺失/冲突统一 fail closed | 无 admin/system bypass；历史数据、索引/性能和全列表 tenant 贯穿待证 |
| 内部入口 | `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/AgentTaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/AgentDiscoveryController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SessionConfigController.java` | 首批单资源、父子资源、Task 操作、SSE 与配置入口先授权；批量操作先校验完整集合 | 隔离 H2 真实双账号已覆盖 Session list/深链/history/SSE/direct read；真实 Provider Task、共享 DB 与 `active/page/search/directory` 等列表未闭环 |
| 转发与共享 | `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SessionRelationController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`、`session-module/src/main/java/com/foggy/navigator/session/controller/SharedTaskController.java`、`session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java` | forward/relation 使用 user/tenant；Sharing Key 映射 owner tenant，授权/readiness 后原子消费 quota | 真实 Sharing Key/Provider L3 与并发数据库矩阵未运行 |
| Context 与 Provider 返回 ID | `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java`、`session-module/src/main/java/com/foggy/navigator/session/service/AgentContextOwnershipClaimWriter.java`、`session-module/src/main/java/com/foggy/navigator/session/repository/AgentConversationContextRepository.java`、`session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java` | assigned-ID 用独立事务 insert/flush，已有记录用 owner/agent 条件更新；context/Provider 返回 sessionId 后重新授权 | Provider 返回 taskId 仍需可信绑定和一致性治理 |
| Model credential | `session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java` | explicit config 存在/enabled、tenant 一致、owner metadata 完整、Worker grant 通过后才可解析订阅或密钥 | service-level tenant invariant 和 owner/grant 的最终语义待 Owner 收敛 |

本切片的本地定向 Maven 矩阵共计 176 tests，命令通过；随后 `mvn -B -pl launcher -am clean test` 15/15 reactor `SUCCESS`，全 reactor 2426 tests、0 failure/error/skipped，launcher 7 tests，总耗时 05:24。日志有测试 JVM 退出后 30 秒 fork kill 非失败诊断提示，命令 exit 0。`9d03bee9` 的 `packages/navigator-frontend/e2e/ownership-live.spec.ts` 在隔离 H2 完成真实双用户 Session 浏览器正负向场景；同一 HEAD 的 Repository CI [`29324741945`](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 7/7 jobs 成功。版本正式门禁已执行并拒绝；Provider Task L3/fixture、共享 DB、历史数据/性能仍未运行，不能据此把 GOV-003 标记完成或批准生产。

## Worker 与外部执行触点

### P2 task capability v2 与 Codex Biz route fix（切片形成时历史快照）

本节保留 `EXEC-142-011` 形成时的边界；其后 `EXEC-142-013` 已接通 Gateway strict Worker principal/lease 与 Biz Provider DB preselect/prebind，当前剩余项以本文件顶部执行快照和更晚证据为准。

| 仓库 | 路径 | 角色 | 实施状态 | 准确变化与限制 |
|---|---|---|---|---|
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/BusinessTaskScopedTokenProperties.java` | TTL 配置 | create-completed-local | `navigator.business-agent.task-token`；默认 TTL `PT30M`，配置最大值和实现硬上限均不超过 `PT60M` |
| root | `launcher/src/main/resources/application.yml` | TTL launcher 配置 | update-completed-local | 新增 `NAVIGATOR_TASK_TOKEN_TTL`、`NAVIGATOR_TASK_TOKEN_MAX_TTL`；不改变 external routing 开关 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java` | v2 持久 schema | update-completed-local | 新增 `@Version`、非空 version/generation/audience/assurance/结构化 function scope/issuedAt，nullable worker/lease/revocation 字段 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/BusinessTaskScopedTokenDTO.java` | v2 claims DTO | update-completed-local | 映射新增 claims；继续不返回 token hash 或明文 token |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/BusinessTaskScopedTokenRepository.java` | token 查询/并发锁 | update-completed-local | bind/revoke 使用 `PESSIMISTIC_WRITE` 精确查询；tenant/task 与 tenant/workerTask 锁定列表支撑批量撤销和 definitive terminal 关联 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenPolicyService.java` | capability snapshot 与 Gateway 校验 | create-completed-local | 新签发写 `v2/generation=1/WORKER_GATEWAY/client-app-delegated`，快照排序后的 `{functionId, version}` 字段对；不是 per-intent 最小 scope，也未校验 Worker lease |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenLifecycleService.java` | token 事务生命周期 | create-completed-local | `REQUIRES_NEW` 签发/绑定/撤销；写锁和固定 token→terminal 锁序保护；definitive terminal tombstone 为授权权威，late bind 会提交绑定+REVOKED 安全写入后抛 `TerminalTaskBindingException`；runtime alias 在 after-commit 变更 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | task 编排与 token 补偿 | update-completed-local | 32 字节 SecureRandom `btt_` token；先独立提交再 dispatch；launcher 异常、Open API submit/bind 失败和外层 rollback 独立撤销；token resolve 已检查 definitive tombstone，暂停/generation 接线待办 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/SecretTokenSupport.java` | token 生成/hash 工具 | read-only-analysis | 复用既有 32 字节 `SecureRandom` Base64URL 与 SHA-256；本批未修改该工具 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskScopedTokenRuntimeStore.java` | runtime alias 生命周期 | update-completed-local | 仅接受结构化 `tenant + session + task` 精确 record key，缺 taskId fail closed；按 token hash 条件清理 task aliases；仍为单 JVM 内存态 |
| root | `docs/migration/2026-07-14-business-task-token-v2.sql`、`docs/migration/2026-07-14-business-task-token-v2-rollback.sql` | MySQL schema 前向/回滚 | create-completed-local | forward 幂等回填 legacy fail-closed claims；rollback 在删 v2 字段前先撤销 ACTIVE token；均已在一次性 MySQL 8.0.44/8.4.8 容器验证；共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java` | Gateway capability enforcement | update-completed-local | list 过滤 token snapshot；schema/invoke 需要 snapshot 与当前授权同时满足；tool message 尚未按具体函数版本校验 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java` | Codex Biz 创建路由 | update-completed-local | 从默认 `CodexTaskService.createTaskDirect` 切换为 `CodexBizTaskProvider.createTaskDirect`，固定 `codex-biz-worker` route；只修路由正确性，不解除 external execution policy pending |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenPolicyServiceTest.java` | v2 policy 测试 | create-completed-local | 覆盖 TTL cap、函数快照、旧版本/错误 audience/畸形 scope 拒绝 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenLifecycleServiceTest.java` | lifecycle 单元测试 | create-completed-local | 15 tests 覆盖 after-commit、rollback、plain/hash 不变量、secret/tenant 不匹配、绑定 tuple 不可变与单/批/按明文撤销 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/repository/BusinessTaskScopedTokenLifecycleJpaTest.java` | lifecycle JPA 测试 | create-completed-local | 2 tests 提供外层 rollback 补偿及 bind/revoke 组合时序下 token 最终不复活的证据；不声称确定性复现或证明悲观锁交错 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java` | 签发/绑定/撤销测试 | update-completed-local | 覆盖 policy initializer、SecureRandom token 形态、单/批量撤销、三类 alias cleanup、幂等和 launcher 异常清理 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskScopedTokenRuntimeStoreTest.java` | runtime store 测试 | update-completed-local | 10 tests 覆盖精确匹配、过期、匹配删除、旧 hash 不删除新 alias 及含冒号 identity 不碰撞 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/WorkerGatewayServiceTest.java` | Gateway scope 测试 | update-completed-local | 旧“仅 ClientApp grant”夹具已改为 token snapshot 与当前授权交集语义 |
| root | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/e2e/BusinessAgentE2ESampleTest.java`、`business-agent-module/src/test/java/com/foggy/navigator/business/agent/e2e/RestAdapterUpstreamE2ETest.java` | Business Agent 夹具 | update-completed-local | 对齐 v2 policy service；未替代真实 Worker/数据库验证 |
| root | `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/e2e/BusinessAgentLanggraphLaunchE2ETest.java` | LangGraph 跨模块 E2E | update-completed-local | 2 tests 定向通过；仍是测试环境 E2E，不是 external enablement |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java` | 既有 Codex Biz 专用 route | read-only-analysis | 本批复用其 `createTaskDirectForProvider(codex-biz-worker, ...)` 与 Biz 参数规范化，Provider 本身未修改 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncherTest.java` | Codex route 回归 | update-completed-local | 断言只走 `createTaskDirectForProvider(codex-biz-worker, ...)`，不调用默认 direct route |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`、`addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java` | Codex Provider/service 回归 | update-completed-local | 与 launcher suite 合计 92 tests 定向通过；internal-dev 默认策略仍需后续治理 |

### P2 Worker credential v1、definitive terminal 与 pool identity route（切片形成时历史快照）

本节保留 `EXEC-142-012` 形成时的边界；其中尚未接入 Gateway 的 `BizWorkerPrincipal`、preselect/prebind 和 lease 后续已由 `EXEC-142-013` 接通，当前剩余项以本文件顶部执行快照和更晚证据为准。

| 仓库 | 路径 | 角色 | 实施状态 | 准确变化与限制 |
|---|---|---|---|---|
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BizWorkerIdentityEntity.java`、`repository/BizWorkerIdentityRepository.java` | credential v1 持久模型 | in-progress-implemented | 增加 row version、credential version 及 issued/expires/revoked/rotated 时间；owner-scoped rotate/revoke 查询使用悲观写锁；全局 `workerId` 不允许被另一 owner/backend 重注册 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/BizWorkerCredentialDTO.java`、`model/dto/BizWorkerPrincipal.java`、`model/form/RotateWorkerCredentialForm.java`、`service/BizWorkerCredentialService.java` | credential v1 API/service 契约 | in-progress-implemented | 服务端生成一次性 `bwc_` secret，DB 仅存 SHA-256；默认 TTL 30 天、范围 60 秒至 365 天、无 grace；strict 校验拒绝 unknown/wrong、legacy v0、disabled、expired、revoked，外部错误保持通用；`BizWorkerPrincipal` 尚未进入 Gateway 请求链 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BizWorkerCredentialController.java`、`controller/UpstreamAdminWorkerCredentialController.java`、`controller/UpstreamAdminWorkerIdentityController.java` | owner-scoped credential 管理 | in-progress-implemented | 平台端要求 SUPER_ADMIN；upstream identity/credential 管理要求精确 `WORKER_MANAGE`，rotate 响应设置 `Cache-Control: no-store` / `Pragma: no-cache`；这是管理面，不是 external Worker ingress 鉴权完成声明 |
| root | `docs/migration/2026-07-14-biz-worker-credential-v1.sql`、`docs/migration/2026-07-14-biz-worker-credential-v1-rollback.sql` | credential schema migration | create-completed-local | MySQL 8.0.44/8.4.8 已验证 forward×2、rollback×2、reapply；rollback 先禁用并清空 modern credential，再移除 lifecycle 字段；未迁移共享/项目数据库，未做 launcher schema validate |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskTerminalStateEntity.java`、`repository/BusinessTaskTerminalStateRepository.java` | terminal tombstone 持久模型 | in-progress-implemented | tenant+workerTask 唯一 tombstone 持久 provider task owner、可选 BusinessTask/capability actor、终态与 retention；tombstone 是 Gateway authorization authority，物理 token REVOKED 只是可重试物化结果 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/event/BusinessTaskScopedTokenTerminalListener.java`、`service/BusinessTaskScopedTokenLifecycleService.java`、`service/TerminalTaskBindingException.java`、`config/BusinessAgentAutoConfiguration.java` | definitive terminal 接线 | in-progress-implemented | 仅消费 Provider 明确给出 `recoverable=false` 的 definitive terminal：BEFORE_COMMIT 写 tombstone，AFTER_COMMIT best-effort 物化撤销；event-before-bind 的 late bind 仍会持久绑定、撤销 token、补全 marker 后抛专用异常；pause/可恢复 interruption 不生成永久 tombstone |
| root | `docs/migration/2026-07-14-business-task-terminal-state.sql`、`docs/migration/2026-07-14-business-task-terminal-state-rollback.sql` | terminal schema migration | create-completed-local | MySQL 8.0.44/8.4.8 已验证 forward×2、rollback×2、reapply；rollback 为 dev-only 破坏性操作并先撤销 ACTIVE token；共享/项目数据库与 launcher validate 未执行 |
| root | `agent-framework/src/main/java/com/foggy/navigator/agent/framework/event/TaskStatusChangeEvent.java`、Claude/Codex/Gemini/LangGraph `*TaskService.java` | Provider terminal 事件 | in-progress-implemented | 事件携带可信 tenant 与显式 recoverable；Claude/Codex 可恢复 FAILED 保持 `true`，Codex acceptance 尚未开始且无可重连 worker task 的 FAILED 显式 `false`，LangGraph complete/fail 为 `false`、可恢复 cancel/interruption 保持 `true`，Gemini 当前实际终态为 `false` |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java`、`service/ClaudeTaskService.java` | Claude task tenant 来源 | in-progress-implemented | create/resume/tracked-sync/local-sync 新任务持久化 tenant；legacy 更新按 task entity→Session→Worker 回填；definitive terminal 仍无法解析 tenant 时抛 `CLAUDE_TASK_TENANT_MISSING`，不静默发布 tenant=null；FAILED 仍可 reset/resync，不写永久 tombstone |
| root | `docs/migration/2026-07-14-claude-task-tenant.sql`、`docs/migration/2026-07-14-claude-task-tenant-rollback.sql` | Claude tenant migration | create-completed-local | MySQL 8.0.44/8.4.8 已验证 forward×2、rollback×2、reapply；先按 Session、再按 Worker 回填，无法证明归属的 legacy 行保持 NULL 并由 definitive terminal fail closed；未迁移共享/项目数据库 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionRuntimeAuditWriter.java`、`service/BusinessFunctionRuntimeAuditService.java` | audit 事务隔离 | in-progress-implemented | 独立 bean 以 `REQUIRES_NEW + saveAndFlush` 写单条记录，facade 捕获 flush/commit 异常，避免影响主操作；当前仍是 best-effort telemetry，不提供强保证、outbox 或关键决策审计承诺 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerPoolService.java`、`repository/BizWorkerPoolRepository.java`、平台/upstream pool controller | pool owner/identity 不变量 | in-progress-implemented | pool 查询、成员、状态和 runtime availability 按 tenant+owner scope；成员必须引用 ENABLED+HEALTHY 且 backend/visibility 匹配的 identity；状态仅允许 ENABLED/DISABLED |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java`、`service/LanggraphWorkerService.java` | LangGraph pool/identity route | in-progress-implemented | pool route 校验 tenant/backend/status/health/owner 与 enabled member；runtime 只从治理后的 BizWorkerIdentity 解析并优先于同名 legacy endpoint，UPSTREAM_SYSTEM 必须精确匹配 pool owner，兼容 physical-only 路径只接受 canonical `(PLATFORM, platform)` identity；hash credential 不会被误作 outbound Bearer secret |

本切片只建立 credential 管理原语、definitive terminal fail-closed、best-effort audit 事务隔离和 LangGraph owner-scoped route。它没有把 `BizWorkerPrincipal` 接入 Worker Gateway，没有完成 Worker preselect/prebind/lease、client headers、pause/generation token 轮换、关键审批/拒绝/恢复 outbox，也没有解除任何 external-enabled readiness pending。不得据此声称外部调用已启用或审计达到强保证。

Schema 精确新增字段如下；forward/rollback 脚本已在一次性 MySQL 8.0.44/8.4.8 容器验证（含幂等及回滚前撤销 ACTIVE token）；共享/项目数据库迁移和 launcher `ddl-auto=validate` 尚未执行：

| 字段 | JPA 约束 | 当前写入/校验 |
|---|---|---|
| `rowVersion` | JPA `@Version` | 与悲观写锁共同保护 bind/revoke |
| `tokenVersion`、`generation` | 非空 Integer | 新签发 `2` / `1`；Gateway fail closed；旧 token legacy 回填脚本已验证，轮换未实施 |
| `audience`、`identityAssurance` | 非空，长度 64 | `WORKER_GATEWAY` / `client-app-delegated`；后者不表示 upstream user 独立强证明 |
| `functionScopeJson` | 非空 LOB | ENABLED ClientApp grants 的 `{functionId, version}` JSON 快照 |
| `workerId`、`workerLeaseId` | nullable，长度 128 | launcher 成功后可写 `workerId`；lease 仅预留 |
| `issuedAt`、`expiresAt` | 非空时间 | policy initializer 同时写入；30 分钟默认、60 分钟硬上限 |
| `revokedAt` | nullable 时间 | 单/批量撤销写入 |
| `revokedBy`、`revokeReason` | nullable，长度 128 / 512 | 单/批量撤销写入；可信控制面 actor/outbox 未完成 |

测试证据口径：task capability 首轮 4 个失败来自旧夹具与新 v2 claims/function snapshot 契约不一致，修正后统一全量回归。`mvn -B -pl business-agent-module -am test` 的已登记基线为 5/5 reactor、770 tests 通过，其中 business-agent 510；Open API 43 + LangGraph E2E 2 + Codex route/provider/service 92 共 137 tests 在同一 10/10 reactor 定向矩阵通过。后续当前范围最终执行 `mvn -B -pl business-agent-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am clean test`，11/11 reactor、2186 tests、0 failure/error/skip；Claude+Codex follow-up 121 tests、Gemini terminal 11 tests 等定向证据包含在最终覆盖范围内。该命令不是根全仓或 launcher `clean verify`。H2 JPA 证据只覆盖已登记的最终状态/事务语义，不声称确定性证明所有数据库锁交错。三组新增 migration 分别在一次性 MySQL 8.0.44/8.4.8 容器完成 forward×2、rollback×2、reapply。本段命令形成时共享/项目数据库迁移、launcher `ddl-auto=validate`、真实 Worker、双 ClientApp/user 手工矩阵、浏览器体验、hosted CI 和正式验收均未执行；后续 hosted 已通过、版本签收已拒绝，其余运行态缺口仍在。

### P2 Gateway principal / lease / secret boundary（`EXEC-142-013`）

| 仓库 | 路径 | 角色 | 实施状态 | 准确变化与限制 |
|---|---|---|---|---|
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/config/WorkerGatewayProperties.java`、`service/WorkerGatewayRequestAuthorizationService.java`、`controller/WorkerGatewayController.java` | Gateway 请求主体 | create/update-completed-local | Gateway external 默认 false；严格三 header，partial/blank/legacy fail closed；校验 credential + capability + exact worker/lease/tenant/ClientApp/pool/member/backend/owner/route |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java`、`service/BusinessTaskScopedTokenLifecycleService.java`、`service/worker/BusinessAgentWorkerTaskLaunchRequest.java`、`BusinessAgentWorkerTaskLauncher.java` | DB preselect/prebind | update-completed-local | Biz Provider 签发前解析 Worker、生成 `bwl_` lease、独立事务预绑定；launch 结果 mismatch fail closed；非 Biz Open API 返回无 Gateway capability |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`、LangGraph/Codex `*BusinessAgentWorkerTaskLauncher.java` | Open API/Provider 接线 | update-completed-local | runtime 只注入 task token、worker/lease，不注入长期 credential；Provider 只重验预选 Worker；bind 失败撤销 token 但远端取消待办 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BizWorkerPoolService.java` | route collision guard | update-completed-local | 新建 pool 时拒绝同名 worker，新注册 worker 时拒绝同名 pool；存量扫描、跨表并发唯一性和显式 `routeKind`/schema 待办 |
| root | `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py`、`tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/business_function_tools.py`、`tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/subprocess_env.py`、`tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/command_tool.py`、`tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_git_sync.py` | LangGraph credential 与 secret boundary | update/create-completed-local | 成对配置本地 Worker ID/credential；仅可信 runtime worker/lease 匹配时发严格 header；模型可控子进程环境 allowlist、无 profile shell、credential-free Git URL 与临时 askpass；仍无 OS 级同 UID 隔离 |
| root | `tools/codex-agent-worker/src/config.ts`、`routes/health.ts`、`routes/query.ts`、`business-mcp/navigator-business-mcp-server.ts`、`codex/sdk-wrapper.ts` | Codex fail-closed readiness | update-completed-local | 配置长期 credential 后 readiness=false；Business MCP preflight 在副作用前 503；通用 task env 移除 ambient task/Worker secret。安全转发尚未实现，不能视为严格客户端 ready |
| root | `launcher/src/main/resources/application.yml`、`launcher/.env.example`、两类 Worker `.env.example` | 显式配置 | update-completed-local | `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`；LangGraph/Codex Worker ID/credential 成对配置。平台/Gateway 两个 external 开关的组合 invariant 待办 |

本切片验证：`mvn -B -pl launcher -am clean test` 15/15 reactor、2357 tests；LangGraph 780 pytest + ruff；Codex 175 tests 中 174 passed、1 Windows-only skipped，typecheck 通过。上述是本切片形成时的本机证据；后续 Repository CI 已在实现快照执行通过。真实 L3、网络、浏览器、共享数据库与生产启用仍未执行。

### P2 首批已实施的平台路由门禁（`12cbe697`）

| 仓库 | 路径 | 角色 | 实施状态 | 说明 |
|---|---|---|---|---|
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/config/ExternalSurfaceProperties.java` | 平台 external surface 配置 | create-completed-local | 绑定 `navigator.external.enabled`；Java 默认值为 `false` |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/filter/ExternalSurfaceGateFilter.java` | Open API 路由门禁 | create-completed-local | 仅匹配 `/api/v1/open` 与 `/api/v1/open/**`；开关关闭时返回 `503 / EXTERNAL_SURFACE_DISABLED`，不改写既有 Open API 鉴权 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/health/ExternalSurfaceHealthController.java` | 平台路由状态 | create-completed-local | `/api/v1/health/external-surface` 输出非敏感状态；`surfaceReady` 只表示平台路由开关已打开，不表示 Provider 或生产 ready |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/config/ClaudeWorkerAutoConfiguration.java` | 门禁装配 | update-completed-local | 注册配置属性和高优先级 Filter；未重构全局 `SecurityConfig` |
| root | `launcher/src/main/resources/application.yml` | launcher 显式开关 | update-completed-local | `navigator.external.enabled: ${NAVIGATOR_EXTERNAL_ENABLED:false}`；默认不开放 Open API surface |
| root | `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/filter/ExternalSurfaceGateFilterTest.java` | 路由门禁负向/范围测试 | create-completed-local | 覆盖默认关闭、显式开启、精确路径边界及非目标路由不受影响 |

### P2 首批已实施的 Worker 门禁/readiness（`5d62707b`）

| Worker | 路径 | 角色 | 实施状态 | 说明 |
|---|---|---|---|---|
| LangGraph Biz | `tools/langgraph-biz-worker/src/langgraph_biz_worker/config.py` | 运行模式配置 | update-completed-local | 严格解析 `BIZ_WORKER_EXTERNAL_ENABLED`，默认 `false` |
| LangGraph Biz | `tools/langgraph-biz-worker/src/langgraph_biz_worker/external_mode.py` | 外部模式状态机 | create-completed-local | 输出 `internal-dev` / `external-enabled`、auth 状态与原因码；external-enabled 当前固定包含 `EXTERNAL_EXECUTION_POLICY_PENDING` |
| LangGraph Biz | `tools/langgraph-biz-worker/src/langgraph_biz_worker/main.py` | 全局 HTTP ingress 门禁 | update-completed-local | 仅精确 `GET /health` 保持可观测；external-enabled 且 unready 时其他 HTTP 路由返回 `503 / EXTERNAL_WORKER_UNREADY`，`/health/` 不属于豁免路径 |
| LangGraph Biz | `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py` | 健康响应契约 | update-completed-local | 增加 mode、external/auth/readiness 与 reasons 字段 |
| LangGraph Biz | `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/health.py` | Worker readiness | update-completed-local | external-enabled 未就绪时 `ready=false`、`status=degraded`；不输出 Token |
| Codex SDK | `tools/codex-agent-worker/src/config.ts` | 运行模式配置 | update-completed-local | 严格解析 `CODEX_WORKER_EXTERNAL_ENABLED`，默认 `false` |
| Codex SDK | `tools/codex-agent-worker/src/external-mode.ts` | 外部模式状态与 middleware | create-completed-local | 仅精确 `GET /health` 例外；external-enabled 未就绪时统一返回 503，并记录非敏感原因码；`/health/` 不等价 |
| Codex SDK | `tools/codex-agent-worker/src/index.ts` | middleware 装配 | update-completed-local | 在 JSON body 和既有 auth middleware 前装配 external gate |
| Codex SDK | `tools/codex-agent-worker/src/models.ts` | 健康响应契约 | update-completed-local | 增加 mode、external/auth/readiness 与 reasons 字段 |
| Codex SDK | `tools/codex-agent-worker/src/routes/health.ts` | Worker readiness | update-completed-local | 合并 Codex SDK 运行条件与 external 原因；任一原因存在均不 ready |
| Codex App Server | `tools/codex-app-server-worker/src/config.ts` | 运行模式配置 | update-completed-local | 严格解析 `CODEX_APP_SERVER_EXTERNAL_ENABLED`，默认 `false` |
| Codex App Server | `tools/codex-app-server-worker/src/external-mode.ts` | 外部模式状态机 | create-completed-local | external-enabled 当前固定因完整执行策略未就绪而 fail closed |
| Codex App Server | `tools/codex-app-server-worker/src/auth.ts` | HTTP ingress/auth 门禁 | update-completed-local | 仅精确 `GET /health` 例外；在原空 Token 放行逻辑前拒绝 external-enabled unready 请求；`/health/` 在 external-enabled 下可能返回 503 |
| Codex App Server | `tools/codex-app-server-worker/src/routes/health.ts` | Worker readiness | update-completed-local | 返回 mode、external/auth/readiness 和原因；不输出凭据 |
| Codex App Server | `tools/codex-app-server-worker/src/runtime-capabilities.ts` | runtime readiness/capability | update-completed-local | 将 external 原因并入 runtime readiness，并在 capability manifest 暴露非敏感状态 |
| 三类 Worker | 对应 `.env.example`、README/集成说明及 tests | 配置说明与回归保护 | update-completed-local | 明确开关默认关闭并覆盖严格布尔解析、健康字段和 external-enabled 503 行为 |

### 平台健康状态消费者（`cce75f1b`）

| 仓库 | 路径 | 角色 | 实施状态 | 说明 |
|---|---|---|---|---|
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/model/dto/LanggraphWorkerHealthDTO.java` | LangGraph 健康契约消费者 | update-completed-local | 接收 `ready`、mode、external/auth/readiness 与 reasons；字段保持 nullable 兼容旧 Worker |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java` | LangGraph 平台状态映射 | update-completed-local | 显式 `ready=false` 映射为 `OFFLINE`；旧 Worker 缺少 `ready` 时仍按既有 200 健康响应兼容为在线 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexSdkBackendConnectionTester.java` | Codex SDK 后端连接检查 | update-completed-local | 显式 `ready=false` 报 `CODEX_SDK_WORKER_UNREADY`；缺少 `ready` 的旧 Worker 继续按原契约处理 |

### 首批实现限制与后续触点

| 类别 | 当前事实 | 后续要求 |
|---|---|---|
| `internal-dev` | 是可信网络内的兼容 profile，不是防火墙或外部安全声明；三个 Worker 在 external 开关为 `false` 时保留原监听地址和空 Token 行为 | 部署侧仍须限制网络可达性；不得把 `internal-dev` 暴露到不可信网络 |
| `external-enabled` | 三个 Worker 当前即使配置了 Token，也固定因 `EXTERNAL_EXECUTION_POLICY_PENDING` 而 `external_ready=false`；缺 Token 时另有 `EXTERNAL_AUTH_TOKEN_REQUIRED` | 完成目录、工具、sandbox、approval、network、task token、身份和审计策略及负向测试后，才可设计解除 pending 的条件 |
| 平台 surface | `NAVIGATOR_EXTERNAL_ENABLED=true` 只打开 `/api/v1/open` 路由门禁；`surfaceReady=true` 只表示 routing gate open | 不覆盖 upstream-admin、`/internal/worker-gateway/v1/**` 或内部 Controller；这些边界须由各自 principal/ownership 方案治理 |
| 兼容 | 平台消费者仅在健康响应显式 `ready=false` 时判 unready；缺字段按旧 Worker 兼容 | 兼容逻辑不等于外部安全；升级期结束后是否收紧须另行决策 |
| 未完成能力 | task token scope/TTL/撤销与 definitive terminal tombstone 已实现；Worker credential v1 仍停留在 schema/API/service，Gateway principal/lease/prebind、pause/generation 轮换、upstream independent identity、调用/审批/恢复可靠 outbox、生产 readiness 或外部启用仍未实现 | 保持 GOV-001/GOV-002 为 `in-progress`，不得据此签收 P2、宣称强保证审计或批准生产路由 |

### 尚未实施的 Worker 治理触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `tools/claude-agent-worker/src/agent_worker/auth.py` | Claude Worker HTTP auth | update | 后续对齐默认关闭的 explicit external 开关、readiness 和负向测试 |
| root | `tools/gemini-agent-worker/src/auth.ts` | Gemini Worker HTTP auth | update | 后续对齐默认关闭的 explicit external 开关、readiness 和负向测试 |
| root | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/execution_policy.py` | LangGraph workdir/tool policy | update | 外部模式服务端限制优先，明确缺失 policy 与空 allowlist 语义 |
| root | `tools/codex-agent-worker/src`、`tools/codex-app-server-worker/src` | Codex 执行策略 | update | 冻结 allowed cwd/tool/sandbox/approval/network 上限并补完整负向矩阵 |

## 构建与 CI 触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `pom.xml` | Maven reactor | update | ODR-142-006 已批准 dev-only 切片移除；每次调整后执行 Java clean gate |
| root | `launcher/pom.xml` | 部署装配 | update | 按独立 dev-only 退役切片移除 dependency，不将业务逻辑放入 launcher |
| root | `package.json` | pnpm workspace 根入口 | update | 增加 `packageManager`、`engines` 和全包脚本 |
| root | `pnpm-workspace.yaml` | 前端 workspace | update | 明确纳入 chat-core、chat、PC、widget、mobile |
| root | `.gitignore` | lockfile 跟踪规则 | update | 解除根 `pnpm-lock.yaml` 的全局忽略，保留生成物排除 |
| root | `pnpm-lock.yaml` | 根依赖锁 | create | 使用已批准的 Node `22.23.1`、pnpm `10.34.5` 重建并提交 |
| root | `scripts/build-frontend.sh` | 前端聚合构建 | update | 覆盖全部交付包或明确分 lane 调用 |
| root | `packages/foggy-chat-core/package.json` | chat-core lane | update | 补齐一致的 type/test/build 入口或在矩阵显式声明 |
| root | `packages/foggy-chat/package.json` | chat lane | update | frozen install 后 test/build |
| root | `packages/navigator-frontend/package.json` | PC lane | update | `type-check`、test、`build:check` |
| root | `packages/navigator-chat-widget/package.json` | widget lane | update | test/build；需要时单列 Playwright |
| root | `packages/foggy-mobile/package.json` | mobile lane | update | 至少 type/test 与目标平台 build |
| root | `.github/workflows/codex-worker-release-candidate.yml` | 现有 Codex 发布流程 | read-only-analysis | 不把单 Worker 发布流当全仓 CI |
| root | `.github/workflows/` | 全仓 CI | create | Java、pnpm、Node Worker、Python Worker 矩阵 |
| root | `README.md` | 环境说明 | update | 从 Node 18+ 修正为 Owner 已批准的 Node `22.23.1`、pnpm `10.34.5` 支持线 |
| root | `navigator-common/src/main/java/com/foggyframework/core/ex/RX.java`、`ExRuntimeException.java`、`ExRuntimeExceptionImpl.java`、`navigator-common/src/test/java/com/foggyframework/core/ex/RXContractTest.java` | Navigator-owned REST response compatibility shim | create-completed-local | `2a859336` 在原 FQCN 下固化仓内实际使用的 `ok/failA/failB/error/throwB` 线上序列化契约，并以 `GlobalExceptionHandlerTest` 覆盖异常边界；这是 clean-room 兼容层，不是对原外部库的通用重建；最新 hosted Java job 已通过 |
| root | `user-auth-module/pom.xml`、`metadata-config-module/pom.xml`、`session-module/pom.xml`、`business-agent-module/pom.xml`、`addons/task-assistant/pom.xml` | 不可获取 `foggy-core` 依赖退出 | update-completed-local | 移除 `com.foggysource:foggy-core:8.1.10.beta` 直接依赖，转用 `navigator-common` 内部 shim；不改变 HTTP wire contract |

## 渐进维护性触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` | 超大主页面 | update | 按状态、组合式函数、面板和 API adapter 渐进拆分，禁止一次性重写 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | 超大控制器 | update | 先提取 query/command/security facade，再减薄 Controller |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java` | 超大 Provider service | update | 按生命周期/流式/恢复职责拆分，保持行为测试 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java` | 超大 Provider service | update | 同上，保留兼容 envelope |
| root | `navigator-common/src/main/java/com/foggy/navigator/common/util/ProviderStateCodec.java` | 状态 envelope v1 | update | 补版本校验、失败可观测性、typed adapter 和迁移链 |
| root | `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionEntity.java` | `providerStateJson` | update | 禁止继续新增裸 Map 读写；迁移需兼容旧数据 |
| root | `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java` | `taskStateJson` | update | 同上 |
| root | `navigator-common/src/main/java/com/foggy/navigator/common/repository/SessionEntityRepository.java` | JSON `LIKE` 查询 | update | 设计可迁移的索引/字段方案，不在无数据评估时直接改 schema |

### P6 旧 Provider API/SPI/DTO 子切片（已物理收口）

| 切片 | 准确路径 | 当前状态 | 替代/保留边界 |
|---|---|---|---|
| deprecated SPI bridge | 已删除 `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskQueryProvider.java`、`session-module/src/main/java/com/foggy/navigator/session/registry/DefaultA2aAgentRegistry.java` 及对应 legacy contract tests；更新 `TaskCommandProvider.java`、`TaskListingProvider.java`、`WorkerSessionQueryProvider.java`、`TaskQueryCapability.java` | code-slice-removed（`50351ada`） | 当前统一 registry/facade 使用分离的 lookup/list/search/command/session-query ports；`TaskQueryProviderRegistry` 是当前内部 registry 名称，不是已删除的 legacy SPI |
| Claude 旧 HTTP 与 DTO/form | 已删除 `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/ClaudeTaskController.java`、`model/dto/TaskDTO.java`、`model/form/CreateTaskForm.java` | code-slice-removed（`73d31a19`、`edee0fc4`） | 仓内调用迁移到 `session-module/.../TaskController.java`、`DispatchTaskDTO` 和内部 `model/command/ClaudeTaskCreateCommand.java` |
| Codex 旧 HTTP 与 DTO/form | 已删除 `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java`、`model/dto/CodexTaskDTO.java`、`model/form/CreateCodexTaskForm.java` | code-slice-removed（`97240642`、`fb11137d`、`9008c554`） | 通用操作迁到 `session-module/.../TaskController.java`；Codex file hints/generated image/canary 迁到 `addons/codex-worker-agent/.../controller/CodexTaskExtensionController.java`；内部创建使用 `model/command/CodexTaskCreateCommand.java` |
| LangGraph 旧 HTTP approval | 已删除 `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/controller/LanggraphTaskController.java` 和 `model/form/ApproveTaskForm.java` | code-slice-removed（`9f3f1422`） | 审批迁到 `/api/v1/tasks/{taskId}/respond`，使用已认证主体和 ownership；`model/dto/LanggraphTaskDTO.java` 与 `model/form/CreateLanggraphTaskForm.java` 仍是 A2A/Business launcher/service 的活跃内部模型，未删除且不得误标 deprecated |

当前业务源码与活跃测试对三组旧 HTTP 前缀、已删 Controller/form/DTO 和旧 SPI 的引用扫描无命中（文档历史/迁移证据除外）。分切片本地验证为：LangGraph 8/8 reactor、68 Java tests，主前端 type-check + 1 Vitest 与 Business Agent L3 TypeScript typecheck；Claude 8/8 reactor、Claude 367 tests；Codex 8/8 reactor、全矩阵 1757 tests（其中 Codex 371）。截至正式闸门的最新已验证实现 head `9d03bee9` 对应 Repository CI [`29324741945`](https://github.com/foggy-projects/Foggy-Navigator/actions/runs/29324741945) 7/7 jobs 成功。这只完成 P6 的旧契约子切片；`ClaudeWorkerView.vue`、大型 service/controller 拆分与 Provider state schema/version 强化仍未完成。

## 第一档清理候选

下列条目只是候选。实际命令、验证与回滚记录见 [CLEAN-001](./workitems/CLEAN-001-low-risk-orphan-cleanup.md)。

| 仓库 | 路径/切片 | 角色 | 预期变更 | 当前限制 |
|---|---|---|---|---|
| root | `addons/coding-agent/integration-tests/package-lock.json` | 孤立 lockfile | read-only-analysis -> delete | 先核对模块历史和自动化引用 |
| root | `test-memory-e2e.ps1` | tutor 旧脚本 | read-only-analysis -> delete | 先确认无 runbook/CI 调用 |
| root | `test_memory_e2e.py` | tutor 旧脚本 | read-only-analysis -> delete | 同上 |
| root | `packages/navigator-frontend/test-tooltip.ts` | 手工测试文件 | read-only-analysis -> delete | 先核对配置/技能引用 |
| root | `packages/navigator-frontend/tooltip-test.spec.ts` | 旧 tooltip spec | read-only-analysis -> delete | 当前仍有配置/技能文本引用，删除需同步 |
| root | `test-worker-tab.spec.ts` | 根级旧 UI spec | read-only-analysis -> delete | 先确认有效测试套件已有替代 |
| root | `packages/foggy-mobile/src/components/TaskCard.vue` | 未见业务引用组件 | read-only-analysis -> delete | 项目技能仍有引用，需同步治理 |
| root | `packages/navigator-frontend/no-attention.png` | 手工截图 | read-only-analysis -> delete | 引用扫描后逐项处理 |
| root | `packages/navigator-frontend/refactored.png` | 手工截图 | read-only-analysis -> delete | 同上 |
| root | `packages/navigator-frontend/workers-fixed.png` | 手工截图 | read-only-analysis -> delete | 同上 |
| root | `tools/claude-agent-worker/src/claude_agent_worker.egg-info/` | Python 生成物 | read-only-analysis -> delete | 确认打包不依赖源码树生成物 |
| root | 前端 API 导出、旧测试 mock | 待生成精确清单 | read-only-analysis | 禁止以泛化名称批量删除 |
| root | tutor-agent/OpenHands addon 的旧技能和文档 | 失效指引 | read-only-analysis | 区分历史版本证据与当前指引；历史证据不篡改 |

## 第二档 dev-only 完整功能切片

下列切片已获 Owner 物理删除授权，开发数据可丢弃且不设置上游/生产兼容窗口。Monitoring、code-review、metadata-query、Echo 与旧 Provider API/SPI/DTO 都已按独立切片物理收口，但各自的运行态、共享资源或正式门禁限制仍按表中证据保留。未来新增的 `delete-authorized` 切片仍必须确认 dev-only 边界、处理仓内引用并在独立批次验证。

| 仓库 | 路径/切片 | 角色 | 预期变更 | 删除前门禁 |
|---|---|---|---|---|
| root | `monitoring-module/`、`tools/foggy-monitor/`、`packages/navigator-frontend/src/views/MonitoringView.vue`、`packages/navigator-frontend/src/api/monitoring.ts`、`scripts/start-all.sh`、`SecurityConfig` 放行项、相关当前文档 | Monitoring | code-slice-removed | Java/Python/UI/API/auth/script 及 repo-local ignored 构建残留已删除并通过本地构建；后续 Repository CI 7-job 矩阵已通过；外部 RabbitMQ/DB/deployment 未操作，专项启动/浏览器体验待跑 |
| root | `metadata-query-module/`、根 reactor、`launcher/pom.xml`、launcher context test、专属 Skill、当前 README/架构文档 | 旧语义查询 | completed-local | 模块、装配、专属断言、Skill 与当前文档已收口；`metadata-config-module` 23 个 tracked files 保留、业务树 diff 为 0；删除后 clean test、依赖树和 clean target 扫描通过。hosted CI 与版本签收已执行，签收为 `rejected`；启动/浏览器和模块级签收仍未运行 |
| root | `addons/code-review-agent/`、专属源码/配置/测试和当前开发指引 | GitLab code review | code-slice-removed | 22 个 tracked files 已删除；仓内扫描和 Java clean 通过；GitLab/DB/独立 deployment 未操作 |
| root | `addons/echo-agent/`、根 reactor、`launcher/pom.xml`、`session-module/src/test/java/com/foggy/navigator/session/registry/UnifiedAgentResolverTest.java`、`tests/integration/test_unified_task_dispatch.sh`、`tests/migration/test-codex-runtime-affinity.ps1` | 示例 Provider | completed-local / verification-partial | addon 5 个 tracked files 和 root/launcher 装配已删除；test-only fixture 覆盖 discovery/resolve/send/query/cancel；Shell 静态运行引用为 0；`LocalEchoBusinessFunctionAdapterInvoker` 无 diff。hosted 与版本正式门禁已执行，签收为 `rejected`；专项 browser/PS parser/模块级签收未运行 |
| root | `/api/v1/claude-tasks`、`/api/v1/codex-tasks`、`/api/v1/langgraph-tasks` 对应 Controller、deprecated SPI bridge、无剩余用途 DTO/form、前端/L3/Worker canary 消费端 | 旧 Provider 契约 | code-slice-removed | `50351ada`、`73d31a19`、`97240642`、`fb11137d`、`9f3f1422`、`edee0fc4`、`9008c554` 已完成仓内迁移和物理删除；分 Provider clean/前端/L3 定向验证通过，截至正式闸门的最新已验证实现 head 对应 hosted CI 7/7 jobs 成功。保留活跃 LangGraph 内部 DTO/form；版本正式门禁已执行且签收拒绝，真实 Provider Task 仍待执行 |

## 明确保留/禁止触碰

| 仓库 | 路径/能力 | 角色 | 预期变更 | 原因 |
|---|---|---|---|---|
| root | `navigator-common/src/main/java/com/foggy/navigator/common/entity/CodingAgentEntity.java`、`addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/CodingAgentController.java`、`/api/v1/coding-agents` | 跨 common/Claude Addon 的通用 Agent 注册 | do-not-touch | 当前仍是平台能力，不等同已删除 OpenHands addon |
| root | `packages/navigator-frontend/src/views/ProfileView.vue` | 用户 Profile | do-not-touch | 倾向恢复路由，需单独 UX 决策 |
| root | `/c/:id` | 会话深链 | do-not-touch | 当前仍有深链使用 |
| root | `packages/navigator-chat-widget/` | 上游集成交付物 | do-not-touch | 外部集成范围 |
| root | `packages/foggy-mobile/src/uni_modules/` | 移动端依赖交付物 | do-not-touch | 不按孤儿目录清理 |
| root | `packages/foggy-mobile/keystore/foggy-navigator.keystore` | 移动端签名材料 | do-not-touch | 必须先迁移、备份、访问收敛和轮换，不能直接删除 |
| root | `metadata-config-module/` | 配置模块 | do-not-touch | 与旧 metadata-query 不是同一退役结论 |
| root | `docs/version-tracker/1.3.*/`、`1.4.0-SNAPSHOT/`、`1.4.1-SNAPSHOT/` | 历史证据 | do-not-touch | 可加更正文档链接，不篡改既有验收事实 |

## REQ-002 P8 实施代码清单

`2026-07-16` 实施复核后的归属如下：跨模块错误枚举、信封、输入模型和脱敏器位于 `agent-framework/.../diagnostic`；JPA 实体及 startup migration 位于 `navigator-common` 以符合现有统一实体扫描；repository/service/controller/config 位于 `session-module`；Codex addon 只调用窄诊断服务。匿名页面由后端输出自包含 HTML，因此没有新增公开前端路由或第三方资源。下列最初计划路径仍保留用于展示计划到实现的对应关系，实际新增文件以 Git diff 与 `EXEC-142-021` 为准。

```yaml
code_inventory:
  - module: agent-framework
    path: agent-framework/src/main/java/com/foggy/navigator/agent/framework/protocol/WorkerEvent.java
    role: Worker 到平台的通用事件契约
    expected_change: update
    notes: 增加可选安全错误字段，保留 error 字符串兼容
  - module: agent-framework
    path: agent-framework/src/main/java/com/foggy/navigator/agent/framework/protocol/AgentMessageBuilder.java
    role: ERROR AgentMessage 构造
    expected_change: update
    notes: 统一写入结构化错误信封，不携带 share token
  - module: session-module
    path: session-module/src/main/java/com/foggy/navigator/session
    role: Task/Session 诊断资源、ownership、留存和访问 API
    expected_change: create
    notes: 新增 provider-neutral entity/repository/service/controller；具体 package 按现有分层确定
  - module: navigator-common
    path: navigator-common/src/main/java/com/foggy/navigator/common
    role: 公共 DTO、枚举和必要 migration support
    expected_change: update
    notes: 只放跨模块稳定类型，不放 Provider 逻辑或匿名授权
  - module: migration
    path: docs/migration
    role: 诊断快照与分享 token schema
    expected_change: create
    notes: 包含 forward migration 及 rollback 或明确 forward-only 验证方案
  - module: session-module
    path: session-module/src/main/java/com/foggy/navigator/session/controller
    role: 登录态详情、分享签发/撤销和匿名只读接口
    expected_change: create
    notes: 匿名 route 必须精确匹配且由 token hash、expiry、revoke 校验保护
  - module: launcher
    path: launcher/src/main/resources/application.yml
    role: retention、share TTL 和 feature flag 默认配置
    expected_change: update
    notes: 分享能力默认关闭，90 天快照、7 天默认分享、30 天上限
  - module: user-auth-module
    path: user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java
    role: 匿名诊断 surface 精确安全配置
    expected_change: update
    notes: 只放行精确匿名诊断 route；不得扩大既有 open/shared route
  - module: codex-worker-addon
    path: addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java
    role: Codex Worker ERROR 中继、稳定化和诊断创建
    expected_change: update
    notes: 诊断创建失败不覆盖任务终态；原始错误不得直接发布
  - module: codex-worker-addon
    path: addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java
    role: Task 失败持久化与统一 Task DTO/SSE 适配
    expected_change: update
    notes: errorMessage 保持兼容，诊断数据进入独立表
  - module: navigator-common
    path: navigator-common/src/main/java/com/foggy/navigator/common/dto/DispatchTaskDTO.java
    role: 统一 Task 查询错误摘要
    expected_change: update
    notes: 增加可选错误信封/diagnosticRef，不返回 share token
  - module: agent-framework
    path: agent-framework/src/main/java/com/foggy/navigator/agent/framework/event/TaskStatusChangeEvent.java
    role: Task 状态变化事件
    expected_change: update
    notes: 传播可选结构化错误摘要
  - module: session-module
    path: session-module/src/main/java/com/foggy/navigator/session/sse/TaskUpdateNotifier.java
    role: Task SSE 状态通知
    expected_change: update
    notes: 保持旧 errorMessage 并透传安全诊断字段
  - module: codex-sdk-worker
    path: tools/codex-agent-worker/src/codex/sdk-wrapper.ts
    role: SDK 原始失败捕获点
    expected_change: update
    notes: 分类和脱敏后再输出 safe metadata
  - module: codex-sdk-worker
    path: tools/codex-agent-worker/src/codex/event-mapper.ts
    role: WorkerEvent ERROR 映射
    expected_change: update
    notes: 保留 error 兼容字段并增加可选结构化字段
  - module: codex-app-server-worker
    path: tools/codex-app-server-worker/src/app-server/event-bridge.ts
    role: App Server failure classifier 与事件桥
    expected_change: update
    notes: 保留 code/kind/status/phase；原始捕获必须经过统一脱敏
  - module: codex-app-server-worker
    path: tools/codex-app-server-worker/src/task-manager.ts
    role: App Server Worker ERROR 终态输出
    expected_change: update
    notes: 向后兼容并输出 safe metadata
  - module: foggy-chat-core
    path: packages/foggy-chat-core/src/store/chatState.ts
    role: ERROR payload 解析和状态归一化
    expected_change: update
    notes: 新旧 payload 均可用，token 不持久化
  - module: foggy-chat-core
    path: packages/foggy-chat-core/src/types/aip.ts
    role: 错误 payload 类型
    expected_change: update
    notes: 增加 Provider 无关的可选诊断字段
  - module: foggy-chat
    path: packages/foggy-chat/src/components/ErrorBlock.vue
    role: 错误卡片与诊断操作入口
    expected_change: update
    notes: 保留当前具体化文案改动，增加详情/复制/分享交互
  - module: navigator-frontend
    path: packages/navigator-frontend/src
    role: 登录态详情页、匿名分享页、API adapter 与路由
    expected_change: create
    notes: 匿名页无第三方资源，不执行任务动作
  - module: documentation
    path: docs/02-modules/observability-system.md
    role: 当前观测与诊断边界说明
    expected_change: update
    notes: 明确诊断快照不是通用日志/Monitoring 平台
```

实现已用 `rg` 复核 SecurityConfig、Task DTO、SSE 状态通知和前端消费落点。与初始清单相比的明确偏差是：SDK Worker 分类器落在独立 `src/diagnostics.ts` 并由 `event-mapper.ts` 调用；App Server 分类器同样独立；匿名页面由 `ErrorDiagnosticSharePageController` 服务端渲染；JPA 实体继续放 `navigator-common`，治理逻辑归 `session-module`。

## 清单维护规则

1. 执行阶段发现新路径时，先更新本清单和 [Progress](./progress.md)，再修改代码。
2. 第一档从 `read-only-analysis` 提升为 `update/delete` 必须附对应 workitem 的证据；第二档已经取得 dev-only `delete-authorized`，但仍须记录实际环境、精确范围、仓内引用和验证结果。
3. 删除使用独立、可回滚提交；不得把多个第二档功能切片混成一个提交。
4. 任何生产路由或外部契约变化都必须回写版本状态；本授权仅覆盖 dev-only 范围，本规划落档本身不改变生产路由。
5. 第二档不再以生产流量审计或兼容窗口作为 dev-only 删除 blocker；但静态搜索命中的仓内引用必须全部处理，发现共享/生产资源或上游消费者时必须停止并重新评审。

## GOV-004 实施增量（2026-07-16）

| 模块/目录 | 关键路径 | 本轮职责 | 本地结果与边界 |
|---|---|---|---|
| `navigator-common`、`session-module` | `TerminationOperationEntity`、`TerminationOperationCapability`、`TerminationOperationTablesMigration`、termination operation service/repository/controller | 保存服务端签发的终止能力、操作审计、显式状态与观察到的退出证据 | 代码与迁移脚本已完成；目标环境迁移尚未执行，不能据此声明生产审计可用 |
| `addons/codex-worker-agent`、`addons/claude-worker-agent` | Provider task service 与控制器 | 仅允许管理员/上游管理员发起人工 PID kill；核验 actor、tenant、任务、Worker、操作类型及观察证据后落审计 | 本地正负向回归已覆盖；真实控制面授权与审计查询仍待目标环境验证 |
| `tools/codex-agent-worker` | `src/termination-operation.ts`、`src/codex/sdk-wrapper.ts` | SDK Worker 的签名终止操作、无自动终止、跨重启 durable receipt replay fence | ledger 依赖稳定 Worker ID 和持久卷；删除或回滚 ledger 会削弱防重放保证 |
| `tools/codex-app-server-worker` | `src/termination-operation.ts`、`src/task-manager.ts` | App Server Worker 的签名终止操作、终态栅栏与 durable receipt replay fence | 同上；同一 Worker ID 不能在独立卷的多实例间共享，除非另行验证共享原子 claim 存储 |
| `tools/claude-agent-worker` | `termination_operation.py`、`routes/processes.py` | Claude Worker 的签名取消/PID 操作、无自动终止与 durable receipt replay fence | 同上；实际 Claude CLI 五态矩阵仍待隔离环境执行 |
| `docs/migration`、`docs/version-tracker/1.4.2-SNAPSHOT` | GOV-004 migration、rollback、workitem、runbook、quality/coverage/acceptance 记录 | 部署前置、回退边界和证据口径 | 隔离 MySQL 正反迁移已验证；正式签收受真实 CLI、目标环境、告警与多实例证据门禁约束 |
