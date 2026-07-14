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
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
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
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java` | Session API | update | 调用统一 ownership 门面；不在 Controller 复制规则 |
| root | `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java` | Task API | update | 查询、列表、响应、恢复、取消均传递可信主体 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/SessionMetadataService.java` | 已有 owned-session 逻辑 | update | 复用/抽取一致的资源归属不变量 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java` | 统一 Task 查询与操作 | update | 收敛 ownership 调用；按职责渐进拆分 |
| root | `session-module/src/main/java/com/foggy/navigator/session/sse/UnifiedSseEmitter.java` | 单 JVM SSE | read-only-analysis | 记录限制；多实例总线不在本版本实现 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | ClientApp Open API | update | 收窄可信 principal、查询/操作归属；渐进拆分 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppRuntimeCredentialResolver.java` | runtime credential 解析 | update | 复核 TTL、撤销、轮换和 scope |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppUserGrantService.java` | upstream user grant | update | 1.4.2 维持 ClientApp credential + mapping/grant 基线并记录 delegated assurance；signed assertion 降为低优先级后续项 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java` | task token 持久 claims | update | 增加函数 scope/version 与撤销语义，需迁移设计 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | BusinessTask 创建/恢复 | update | 服务端固化 tenant/ClientApp/upstream user/task/function scope |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java` | Worker Gateway 入口 | update | 只接受 task token principal，不信任身份字段 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java` | 函数授权与执行 | update | enforce task-level function scope、跨任务拒绝与拒绝审计 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java` | 审批控制面 | update | 保持 credential principal；补全 task/subject 绑定负向验证 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java` | 暂停/恢复绑定 | update | 统一审批、恢复、取消归属和审计语义 |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/TaskScopedTokenResolver.java` | Worker token 注入 | update | 禁止跨任务 fallback；明确重启/恢复行为 |

## `2026-07-14` 执行快照

| 批次 | 实际路径/切片 | 状态 | 已有验证 | 尚未验证/未操作 |
|---|---|---|---|---|
| P1 构建基线 | `.nvmrc`、根 `package.json`、`.gitignore`、根 `pnpm-lock.yaml`、前端 package/scripts、`navigator-open-sdk/pom.xml` 与测试、required/nightly workflows、现有 Codex RC workflow、README/CLAUDE | in-progress-implemented | 精确 Node/pnpm frozen 校验、frontend、五类 Worker clean 等价矩阵 passed；[BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md) 关闭后根 `mvn -B clean test` 17/17 reactor、2304 tests 全通过；nightly 已配置 | hosted CI、branch protection、nightly 实跑、根 reactor `clean verify`、跨 checkout 与浏览器体验 |
| P2 首批外部门禁/readiness | 平台 `/api/v1/open` 路由门禁、LangGraph Biz Worker、Codex SDK Worker、Codex App Server Worker 及 Java 健康状态消费者 | in-progress-implemented | 三个独立提交：`12cbe697`、`5d62707b`、`cce75f1b`；默认关闭的显式开关、脱敏健康状态、external-enabled 503 门禁与旧 Worker 健康响应兼容逻辑已落码 | task token、可信 upstream identity、授权交集、审计 outbox、完整 execution policy、Claude/Gemini Worker、生产 readiness 与外部开放均未完成 |
| Monitoring | `monitoring-module/**`、`tools/foggy-monitor/**`、PC View/API、SecurityConfig 放行、`scripts/start-all.sh` 与当前权威文档 | code-slice-removed | tracked 源码及 repo-local ignored `target/.venv/.pytest_cache` 均移除；静态扫描、shell syntax、Java clean、frontend full matrix passed | RabbitMQ/DB/deployment 等外部资源未操作；启动/浏览器 smoke 未跑 |
| Code Review | `addons/code-review-agent/**` 共 22 个 tracked files、当前开发指引 | code-slice-removed | root/launcher/CI/scripts/source 扫描与 Java clean passed | GitLab webhook、DB、独立 deployment 未操作/未做运行态读取 |
| metadata-query | `metadata-query-module/**`、根 `pom.xml`、`launcher/pom.xml`、launcher context test、`.agents/skills/metadata-query-module/**`、当前 README/架构文档 | completed-local | 模块、装配、断言、Skill 与当前文档已收口；根 reactor 当前为 16 个模块；删除后 clean test 15/15 `SUCCESS`，依赖树与 clean target 无旧查询依赖 | 启动/浏览器 smoke、hosted CI 与正式验收未运行；外部资源未操作 |
| Echo / 旧 Provider 契约 | 对应后续独立切片 | not-started | 删除前 Java clean 基线 passed | 仓内迁移、物理删除和删除后回归均未运行 |

## Worker 与外部执行触点

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
| 未完成能力 | 本批未实现 task token 函数 scope/生命周期、upstream identity、调用/审批/恢复审计、生产 readiness 或外部启用 | 保持 GOV-001/GOV-002 为 `in-progress`，不得据此签收 P2 或批准生产路由 |

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

下列切片已获 Owner 物理删除授权，开发数据可丢弃且不设置上游/生产兼容窗口。Monitoring、code-review 和 metadata-query 已开始执行；其中 metadata-query 已完成代码/装配/Skill/当前文档收口和本地自动化验证。尚未执行的 `delete-authorized` 切片仍不是“立即删除”：必须确认 dev-only 边界、处理仓内引用并在独立批次验证。

| 仓库 | 路径/切片 | 角色 | 预期变更 | 删除前门禁 |
|---|---|---|---|---|
| root | `monitoring-module/`、`tools/foggy-monitor/`、`packages/navigator-frontend/src/views/MonitoringView.vue`、`packages/navigator-frontend/src/api/monitoring.ts`、`scripts/start-all.sh`、`SecurityConfig` 放行项、相关当前文档 | Monitoring | code-slice-removed | Java/Python/UI/API/auth/script 及 repo-local ignored 构建残留已删除并通过本地构建；外部 RabbitMQ/DB/deployment 未操作，体验/hosted CI 待跑 |
| root | `metadata-query-module/`、根 reactor、`launcher/pom.xml`、launcher context test、专属 Skill、当前 README/架构文档 | 旧语义查询 | completed-local | 模块、装配、专属断言、Skill 与当前文档已收口；`metadata-config-module` 23 个 tracked files 保留、业务树 diff 为 0；删除后 clean test、依赖树和 clean target 扫描通过。启动/浏览器、hosted CI 和正式验收仍未运行 |
| root | `addons/code-review-agent/`、专属源码/配置/测试和当前开发指引 | GitLab code review | code-slice-removed | 22 个 tracked files 已删除；仓内扫描和 Java clean 通过；GitLab/DB/独立 deployment 未操作 |
| root | `addons/echo-agent/`、根 reactor、`launcher/pom.xml`、discovery 与已知测试引用 | 示例 Provider | delete-authorized | 迁移或删除仓内 smoke/test 引用；保留 `LocalEchoBusinessFunctionAdapterInvoker` |
| root | `/api/v1/claude-tasks`、`/api/v1/codex-tasks`、`/api/v1/langgraph-tasks` 对应 Controller、DTO、SPI、前端/Worker/SDK/CLI 调用 | 旧 Provider API | delete-authorized | 迁移或删除 PC、L3、Worker/canary、stream relay 等全部仓内引用后直接删除；无需外部静默或兼容窗口 |

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

## 清单维护规则

1. 执行阶段发现新路径时，先更新本清单和 [Progress](./progress.md)，再修改代码。
2. 第一档从 `read-only-analysis` 提升为 `update/delete` 必须附对应 workitem 的证据；第二档已经取得 dev-only `delete-authorized`，但仍须记录实际环境、精确范围、仓内引用和验证结果。
3. 删除使用独立、可回滚提交；不得把多个第二档功能切片混成一个提交。
4. 任何生产路由或外部契约变化都必须回写版本状态；本授权仅覆盖 dev-only 范围，本规划落档本身不改变生产路由。
5. 第二档不再以生产流量审计或兼容窗口作为 dev-only 删除 blocker；但静态搜索命中的仓内引用必须全部处理，发现共享/生产资源或上游消费者时必须停止并重新评审。
