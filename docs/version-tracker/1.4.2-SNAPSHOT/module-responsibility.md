# 1.4.2 模块职责与依赖边界

## 文档作用

- doc_type: module-responsibility
- intended_for: root-controller | execution-agent | reviewer | module-owner
- purpose: 冻结 1.4.2 的模块责任、依赖方向、实施顺序和禁止越界事项。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: in-progress
- owner_decision_status: review-complete
- operation_mode: single-root-delivery
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)

## 责任划分原则

1. Foggy Navigator 是多 Worker 远程编程工作台与 Session/Task/A2A 治理平台，不以语义层或数据分析为当前主线。
2. 内部控制面保持轻量认证；资源归属不变量放在统一 service/facade 层，不通过重写全局 Spring Security 解决。
3. 外部运行面必须通过默认关闭的显式 `external-enabled` 开关启用；身份、租户、ClientApp、upstream user、任务和函数作用域由服务端可信上下文决定，不接受请求体身份字段作为授权依据。
4. `launcher` 只负责装配、配置和部署入口，不承载业务规则或跨模块编排。
5. Provider Addon 当前是编译期模块化单体；1.4.2 不把它包装成动态插件，也不引入动态加载。
6. Owner 已批准 Monitoring、metadata-query、code-review、echo 和旧 Provider 契约在 dev-only 范围内物理删除且开发数据可丢弃；清理仍须以完整功能切片为单位，确认没有共享/生产资源，处理仓内引用并留下验证与回滚证据。
7. `internal-dev` 是依赖可信网络隔离的兼容 profile，不是防火墙或 external readiness 声明；默认监听与空 Token 的既有行为可在该 profile 保留，但不得暴露到不可信网络。

## 模块责任矩阵

| 模块/目录 | 当前责任 | 1.4.2 责任 | 允许的依赖方向 | 禁止事项 | 主要工作项 |
|---|---|---|---|---|---|
| `navigator-common` | 通用实体/模型、状态编解码与基础工具，包含 `CodingAgentEntity`；当前还承载 Navigator-owned `com.foggyframework.core.ex` 最小 REST envelope 兼容实现 | 维护 Provider 状态 envelope v1；定义兼容读取、版本验证和迁移契约；保留通用 Agent 注册实体与已冻结 wire contract，逐步消除不可从 clean runner 获取的私有基础依赖 | 被上层模块依赖 | 放入 Provider 编排、Controller 或业务授权逻辑；扩张 clean-room shim 到未使用的历史 API | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `navigator-spi` | Provider、Agent、Task 等扩展契约 | 只承载稳定窄接口；为 Provider 状态/能力契约提供边界 | `common <- spi <- framework/feature` | 反向依赖具体 Addon；为删除旧 API 扩张新兼容层 | [OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `agent-framework` | Agent/Provider 公共框架与扩展协调；Provider legacy bridge 已完成仓内迁移并退出 | 维护公共框架边界和统一窄契约；剩余非 Provider deprecated 能力按各自工作项处理 | 依赖 `common`、`spi` | 反向依赖具体 Addon；被描述成动态插件加载器；为已删 Provider bridge 重建兼容层 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `user-auth-module` | 轻量用户认证、上下文与安全装配 | 维持可信内网模式；只补必要的 principal/credential 接入和配置说明 | 为上层提供认证上下文 | 1.4.2 重写 Spring Security、引入通用 RBAC/ABAC | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) |
| `session-module` | Session/Task 生命周期、统一查询操作、SSE | 建立 Session/Task ownership 不变量；保持单 JVM SSE 边界透明 | 依赖 framework/common/auth；Provider Addon 依赖它 | Controller 各自复制归属规则；本版本实现多实例事件总线 | [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) |
| `business-agent-module` | ClientApp、upstream user grant、BusinessTask、task token、Worker Gateway、审批恢复与审计 | 成为外部运行面服务端授权事实源；以 ClientApp credential + mapping/grant 作为 1.4.2 身份基线，冻结 task token 函数作用域和生命周期；signed assertion 降为低优先级后续项 | 依赖低层公共模块，不反向依赖具体 Provider | 信任请求体中的 userId/tenantId/reviewedBy；把代办身份虚报为独立 signed subject；把授权只交给 Worker | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `addons/claude-worker-agent` | Claude Provider、Open API、ClientApp 外部入口，并承载当前 `/api/v1/coding-agents` Controller/service | 已为 `/api/v1/open` 与子路径增加默认关闭的平台 routing gate 和独立健康状态；继续收窄 `OpenApiController`、统一外部 principal/ownership，并保留通用 Coding Agent 注册 API | 可依赖 session/business/framework；门禁装配在本 Addon，launcher 只提供配置 | 把 `surfaceReady` 当作 Provider/生产 ready；把门禁扩张到 upstream-admin、Worker Gateway 或内部 Controller；在 Controller 内形成第二套授权模型 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/codex-worker-agent` | Codex Provider、任务与流式能力，并检查 Codex SDK Worker 连接；旧 task HTTP Controller/form/DTO 已退出 | 已消费 Worker `ready`：显式 `false` 阻断连接，缺字段兼容旧 Worker；统一任务入口使用 shared task routes，继续收紧外部任务约束并渐进拆分 `CodexTaskService` | 可依赖 session/business/framework；平台消费 Worker 健康契约，不反向决定 Worker execution policy | 把缺少 `ready` 的兼容行为当作 external-ready；恢复已删旧契约；误删当前 Provider/OpenAPI/runtime 能力 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/langgraph-biz-worker` | LangGraph Biz Provider、Worker 健康/工具授权；旧 task HTTP Controller/form 已退出 | 已接收 Worker 外部状态并将显式 `ready=false` 映射为 `OFFLINE`，缺字段兼容旧 Worker；审批响应使用 shared task `respond`，reviewer 从认证主体派生；继续完成 external task/ClientApp 绑定 | 可依赖 session/business/framework；平台只消费 Worker readiness，不替 Worker 声明策略完成 | 恢复请求体 reviewer 授权；把旧 Worker 兼容路径当 external-ready；删除新受控审批能力 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `addons/gemini-worker-agent` | Gemini Provider | 纳入统一 readiness、状态契约、工作目录和工具边界验证 | 可依赖 session/business/framework | 以 Provider 特例绕过外部边界 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/echo-agent` | 已物理退役的示例 Provider；5 个 tracked files 及 reactor/launcher 装配已移除 | 保持默认制品无 Echo；用 `UnifiedAgentResolverTest` 的 test-only fixture 回归 A2A lifecycle | 当前运行时无依赖；测试 fixture 仅在 test scope | 误删 `LocalEchoBusinessFunctionAdapterInvoker`；把本机测试当成 hosted/浏览器/正式验收 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `addons/code-review-agent` | 未进入根构建的历史 GitLab code review Addon；22 个 tracked files 已在本轮删除 | 保持从 reactor/launcher/CI/scripts 和当前源码退出；如未来恢复，按新集成重新设计 | 当前无运行时依赖；历史证据只读 | 误把测试中的通用 `CodeReview` 命名当作已删 Addon；发现共享/生产 GitLab 资源后擅自操作 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `metadata-config-module` | 元数据配置能力；23 个 tracked files 保留、本批业务树 diff 为 0 | 保留；metadata-query 删除后定向 clean test 已通过，继续维护边界标注 | 由明确消费者依赖 | 因名称或 Java package 前缀相似而随 metadata-query 误删 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `metadata-query-module` | 旧 Foggy Dataset/FSScript 查询链路；模块、根 reactor 条目、launcher 依赖/专属 bean 断言、专属 Skill 与当前文档已移除 | 保持切片退出；启动/浏览器 smoke 和模块级签收后续补证 | metadata-query 删除当时根 reactor 从 17 收缩到 16，后续 Echo 退出后当前为 15 个模块；删除后 metadata-config/launcher clean test 15/15 `SUCCESS`，依赖树与 clean target 无旧查询依赖；版本签收已拒绝 | 把本地/hosted clean 结果冒充专项启动体验或验收通过；误删 `metadata-config-module`；未确认目标环境时执行数据操作 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `monitoring-module`、`tools/foggy-monitor` | 旧自研 Monitoring 代码切片已删除，原本未进 root/launcher | 保持 Java/Python、UI/API、放行项、启动脚本和当前文档完整退出；保留日志、health、有限指标与安全审计 | 当前无运行时依赖；任何仓外资源动作独立处理 | 把旧 Monitoring 删除解释为取消所有观测；擅自删除未知 RabbitMQ/DB/部署资源 | [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) |
| `navigator-open-sdk` | 上游系统接入 SDK | 已通过显式 Surefire/JUnit Platform 恢复 142 项 clean test 基线；继续固化外部身份/任务/错误语义，删除旧调用面时只迁移仓内 SDK 引用 | 面向当前受支持契约；独立 POM 不应依赖根插件继承 | 暴露服务端内部 token 或可信身份字段写入口；静默跳过 Jupiter 测试；为已删除旧 API 新增兼容层 | [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `tools/langgraph-biz-worker`、`tools/codex-agent-worker`、`tools/codex-app-server-worker` | 本批三个 Worker 进程 | 已实现各自默认关闭的显式 external 开关、脱敏 readiness 和 unready ingress 503；external-enabled 当前固定因 execution policy pending 不接业务请求 | 接收服务端签发且有作用域的上下文；平台只消费显式 readiness | 把 Token 已配置当作 external-ready；在 pending 未解除时接任务；把 internal-dev 当网络隔离；自行扩大工具权限 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `tools/claude-agent-worker`、`tools/gemini-agent-worker` | 尚未纳入本批门禁的 Worker 进程 | 后续对齐显式 external 开关、readiness、ingress 与 execution policy；当前不得写成已治理 | 与平台/Business Agent 契约对齐 | 用其他三个 Worker 的证据代替自身测试；在外部边界未完成时启用 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `packages/navigator-frontend` | 主控制台；既有 P1 类型错误和旧 Provider HTTP 调用已收口 | 保持 shared task API，渐进拆分 `ClaudeWorkerView.vue`；维护 `/c/:id` ownership 深链回归 | 通过 API 层访问后端 | 一次性重写超大页面；删除 `/c/:id` 深链；恢复已删旧 Provider 路由 | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `packages/navigator-chat-widget`、`packages/foggy-mobile` | 外部集成交付物与移动端；仓内旧 Provider API 调用已迁移 | 纳入可复现构建并保持当前 unified 契约交付物 | 依赖当前公开 SDK/契约 | 作为“无引用前端包”删除；删除 mobile `uni_modules`；恢复已删除旧 API | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `launcher` | Spring Boot 部署壳与模块装配 | 通过 `NAVIGATOR_EXTERNAL_ENABLED:false` 装配平台 Open API routing gate；只处理显式启停、profile 和依赖装配；按已批准 dev-only 退役切片移除依赖 | 依赖需部署的功能模块 | 把 launcher 开关解释为 Worker/身份/生产 readiness；新增业务 service、授权规则或兼容控制器；留下已删除模块装配 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md)、[CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `docs`、`CLAUDE.md`、根 `README.md` | 当前架构、开发约定与版本证据 | 统一产品定位、事实时态和历史归档边界 | 文档引用代码与版本证据 | 把历史验收复制成当前证据；把 tutor/语义层写成主线 | [DOC-001](./workitems/DOC-001-documentation-alignment.md) |

## 信任边界责任

| 边界 | 身份事实源 | 资源归属事实源 | 授权执行点 | 必要审计 |
|---|---|---|---|---|
| 内部控制面 | `AuthInterceptor` / `@RequireAuth` 建立的当前用户 | Session/Task 统一 ownership service/facade | service/facade，不依赖 URL 是否 `permitAll` | 越权拒绝、管理员例外与可信内网接口清单 |
| ClientApp Open API | runtime/control credential principal；1.4.2 使用 ClientApp 代办身份并明确 assurance，signed assertion 为低优先级后续项 | tenant + ClientApp + upstream user grant + task/session binding | `NAVIGATOR_EXTERNAL_ENABLED` 当前只门禁 `/api/v1/open` 路由；OpenAPI facade / business service 继续负责认证授权 | 开关变化、凭据换发、拒绝、查询、取消与失败 |
| Business Worker | task-scoped token 的服务端 claims | task + session + tenant + ClientApp + upstream user + function scope | Worker Gateway + Worker 双层约束 | invoke、拒绝、暂停、审批、恢复、取消、失败 |
| Worker 进程 | 配置的 Worker credential 与显式部署模式 | 服务端注入的工作目录、工具和任务上下文 | 首批三个 Worker 已有 readiness/ingress gate；完整 auth/execution policy 仍待完成 | 启动模式、unready 原因、认证失败与策略拒绝 |

## P2 首批 execution check-in（非正式验收）

| 提交 | 责任边界 | 已实施事实 | 未完成边界 |
|---|---|---|---|
| `12cbe697` | 平台 Open API routing surface | `ExternalSurfaceProperties`、`ExternalSurfaceGateFilter`、`ExternalSurfaceHealthController` 与 launcher 默认关闭配置已落码；只覆盖 `/api/v1/open` 及子路径 | 不覆盖 upstream-admin、`/internal/worker-gateway/v1/**`、内部 Controller、Worker readiness、身份或生产批准 |
| `5d62707b` | LangGraph Biz、Codex SDK、Codex App Server Worker | 三个独立开关分别为 `BIZ_WORKER_EXTERNAL_ENABLED`、`CODEX_WORKER_EXTERNAL_ENABLED`、`CODEX_APP_SERVER_EXTERNAL_ENABLED`，默认 `false`；external-enabled 当前始终 unready 并拒绝业务 ingress | execution policy、task token、upstream identity、审计、Claude/Gemini 对齐和 production readiness 未完成 |
| `cce75f1b` | Java 平台健康状态消费者 | LangGraph/Codex 消费者尊重显式 `ready=false`；旧 Worker 缺少 `ready` 字段按兼容路径处理 | 兼容路径不是 external-ready 证明，后续是否收紧须单独决策和迁移 |

本表保留 GOV-001/GOV-002 首批路由与 readiness 门禁的历史 check-in；后续已增加 task capability/终态门禁、Worker principal/lease、P3 ownership、LangGraph trusted approval、旧 Provider 契约删除和 hosted CI 证据。外部 identity、可靠审计、完整工具/目录/网络上限、真实 Provider Task 与生产/外部启用仍未完成，因此状态保持 `in-progress`，不得据此进入正式签收。

## 依赖与实施顺序

```text
P0 术语/边界/清单冻结
  -> P1 可复现构建与 CI
  -> P2 外部运行面可信上下文
  -> P3 内部 Session/Task ownership
  -> P4 低风险清理
  -> P5 dev-only 功能切片独立物理清理
  -> P6 大类/状态契约治理 + 仓内迁移后直接删除旧 API
  -> P7 质量、覆盖、体验与签收
```

- P2 与 P3 的实现可以分支并行，但必须共享同一 principal/ownership 术语和负向测试口径。
- P4 只处理已经完成引用扫描且具备独立回滚的候选；P5 使用单独的 dev-only 物理删除授权，仍须逐切片确认环境与仓内引用。
- P6 的旧 API 子切片已在 P2/P3 新入口与仓内调用方迁移后完成；P6 剩余超大类和状态 schema 仍按小步门禁推进，不因旧契约删除完成而自动签收。
- 每个跨模块阶段完成 implementation self-check 后，依次进入正式实现质量闸门、测试覆盖审计和正式验收；隔离验证不等于生产批准。

## Owner 决策落地

| 决策 | 状态 | 实施门禁 |
|---|---|---|
| ODR-142-002 upstream user 证明 | approved-with-constraints | ClientApp credential + mapping/grant 为当前基线；signed assertion 降为低优先级；external 显式开关默认关闭且启用时 fail closed |
| ODR-142-003 task token | approved | 函数 scope、TTL、撤销、轮换、Worker principal/lease 和负向测试必须真实完成 |
| ODR-142-004 Worker 上限 | approved | workdir/tool/sandbox/approval/egress 只能由服务端上限收紧，external 开关不得隐式开启 |
| ODR-142-005 审计 | approved | 关键状态/拒绝/远程调用审计按批准的可靠性分层实现并验证 |
| ODR-142-006 历史切片 | approved-dev-only | 数据可丢弃且允许物理删除；命中共享/生产资源立即停止，每切片独立提交和验证 |
| ODR-142-007 旧 Provider 契约 | approved-direct-removal | 取消上游/生产兼容窗口；先迁移或删除全部仓内引用，再直接删除旧 API/SPI/DTO |

credential authority、mapping/grant 权威数据源、Provider state 具体 schema 演进和超大类拆分顺序仍是实施级 Owner 交接点，不得因决策评审完成而虚构为已冻结。
