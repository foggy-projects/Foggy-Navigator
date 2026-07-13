# 1.4.2 模块职责与依赖边界

## 文档作用

- doc_type: module-responsibility
- intended_for: root-controller | execution-agent | reviewer | module-owner
- purpose: 冻结 1.4.2 的模块责任、依赖方向、实施顺序和禁止越界事项。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: planned
- operation_mode: single-root-delivery
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)

## 责任划分原则

1. Foggy Navigator 是多 Worker 远程编程工作台与 Session/Task/A2A 治理平台，不以语义层或数据分析为当前主线。
2. 内部控制面保持轻量认证；资源归属不变量放在统一 service/facade 层，不通过重写全局 Spring Security 解决。
3. 外部运行面的身份、租户、ClientApp、upstream user、任务和函数作用域由服务端可信上下文决定，不接受请求体身份字段作为授权依据。
4. `launcher` 只负责装配、配置和部署入口，不承载业务规则或跨模块编排。
5. Provider Addon 当前是编译期模块化单体；1.4.2 不把它包装成动态插件，也不引入动态加载。
6. 清理必须以完整功能切片为单位，并在引用、流量、配置、数据、部署和回滚证据齐备后执行。

## 模块责任矩阵

| 模块/目录 | 当前责任 | 1.4.2 责任 | 允许的依赖方向 | 禁止事项 | 主要工作项 |
|---|---|---|---|---|---|
| `navigator-common` | 通用实体/模型、状态编解码与基础工具，包含 `CodingAgentEntity` | 维护 Provider 状态 envelope v1；定义兼容读取、版本验证和迁移契约；保留通用 Agent 注册实体 | 被上层模块依赖 | 放入 Provider 编排、Controller 或业务授权逻辑 | [OPT-002](./workitems/OPT-002-core-code-maintainability.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `navigator-spi` | Provider、Agent、Task 等扩展契约 | 只承载稳定窄接口；为 Provider 状态/能力契约提供边界 | `common <- spi <- framework/feature` | 反向依赖具体 Addon；为删除旧 API 扩张新兼容层 | [OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `agent-framework` | Agent/Provider 公共框架与扩展协调 | 维护公共框架边界；核对 deprecated SPI 的真实消费者 | 依赖 `common`、`spi` | 反向依赖具体 Addon；被描述成动态插件加载器 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `user-auth-module` | 轻量用户认证、上下文与安全装配 | 维持可信内网模式；只补必要的 principal/credential 接入和配置说明 | 为上层提供认证上下文 | 1.4.2 重写 Spring Security、引入通用 RBAC/ABAC | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md) |
| `session-module` | Session/Task 生命周期、统一查询操作、SSE | 建立 Session/Task ownership 不变量；保持单 JVM SSE 边界透明 | 依赖 framework/common/auth；Provider Addon 依赖它 | Controller 各自复制归属规则；本版本实现多实例事件总线 | [GOV-003](./workitems/GOV-003-session-task-resource-ownership.md) |
| `business-agent-module` | ClientApp、upstream user grant、BusinessTask、task token、Worker Gateway、审批恢复与审计 | 成为外部运行面服务端授权事实源；冻结 task token 函数作用域和生命周期 | 依赖低层公共模块，不反向依赖具体 Provider | 信任请求体中的 userId/tenantId/reviewedBy；把授权只交给 Worker | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `addons/claude-worker-agent` | Claude Provider、Open API、ClientApp 外部入口，并承载当前 `/api/v1/coding-agents` Controller/service | 收窄 `OpenApiController`；统一外部 principal、查询/操作归属与安全配置；保留通用 Coding Agent 注册 API | 可依赖 session/business/framework | 在 Controller 内形成第二套授权模型；一次性重写大类；随 OpenHands 残留误删通用注册 API | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/codex-worker-agent` | Codex Provider、任务与流式能力 | 统一外部任务约束；渐进拆分 `CodexTaskService`；迁移旧 API 消费者 | 可依赖 session/business/framework | 直接删除仍被 PC/Worker 消费的兼容路径 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/langgraph-biz-worker` | LangGraph Biz Provider、工具授权与审批兼容链路 | 将旧 taskId/reviewedBy 审批链路隔离并迁移到受控入口 | 可依赖 session/business/framework | 继续以请求体 reviewer 作为审计主体 | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `addons/gemini-worker-agent` | Gemini Provider | 纳入统一 readiness、状态契约、工作目录和工具边界验证 | 可依赖 session/business/framework | 以 Provider 特例绕过外部边界 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `addons/echo-agent` | 示例/测试 Provider，当前进入 reactor 和 launcher | 评估移出生产装配并成为 dev/test fixture；先提供 smoke 替代 | 仅开发/测试依赖目标 | 在替代基线前直接移除 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `addons/code-review-agent` | 未进入根构建的 GitLab code review Addon | 审计 webhook、配置和外部消费者后决定保留或成组退役 | 若保留则显式纳入所有权和构建 | 仅因未进入 reactor 就判定无用 | [CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `metadata-config-module` | 元数据配置能力 | 保留；只做引用和边界标注 | 由明确消费者依赖 | 因名称相似而随 metadata-query 删除 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `metadata-query-module` | 旧 Foggy Dataset/FSScript 查询链路，当前在 reactor/launcher | 审计流量、数据、配置和第三方消费者；独立作去留决策 | 删除前维持现有方向 | 规划阶段删除；与 metadata-config 捆绑处理 | [CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `monitoring-module`、`tools/foggy-monitor` | 残留监控功能切片；未进 root/launcher，但仍有脚本、前端和放行项 | 作为完整切片完成运行态审计、替代方案和独立退役 | 退役前只修正残留装配 | 只删 UI 或只删脚本造成半退役 | [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md) |
| `navigator-open-sdk` | 上游系统接入 SDK | 固化外部身份/任务/错误语义，配合兼容迁移 | 面向稳定外部契约 | 暴露服务端内部 token 或可信身份字段写入口 | [GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `tools/*worker` | Claude/Codex/Gemini/LangGraph Worker 进程 | 区分 internal-dev 与 external-enabled；非 loopback 空凭据 fail closed/unready；落实 workdir/tool policy | 接收服务端签发且有作用域的上下文 | 外部模式以空 Token 关闭认证；自行扩大工具权限 | [GOV-001](./workitems/GOV-001-internal-external-trust-boundary.md)、[GOV-002](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| `packages/navigator-frontend` | 主控制台 | 修复既有类型错误；渐进拆分 `ClaudeWorkerView.vue`；迁移兼容 API | 通过 API 层访问后端 | 一次性重写超大页面；删除 `/c/:id` 深链 | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md)、[OPT-002](./workitems/OPT-002-core-code-maintainability.md) |
| `packages/navigator-chat-widget`、`packages/foggy-mobile` | 外部集成交付物与移动端 | 纳入可复现构建和兼容 API 消费审计 | 依赖公开 SDK/契约 | 作为“无引用前端包”删除；删除 mobile `uni_modules` | [OPT-001](./workitems/OPT-001-build-and-ci-baseline.md)、[CLEAN-004](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| `launcher` | Spring Boot 部署壳与模块装配 | 只处理显式启停、profile 和依赖装配；反映独立退役决策 | 依赖需部署的功能模块 | 新增业务 service、授权规则或兼容控制器 | [CLEAN-002](./workitems/CLEAN-002-monitoring-retirement.md)、[CLEAN-003](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| `docs`、`CLAUDE.md`、根 `README.md` | 当前架构、开发约定与版本证据 | 统一产品定位、事实时态和历史归档边界 | 文档引用代码与版本证据 | 把历史验收复制成当前证据；把 tutor/语义层写成主线 | [DOC-001](./workitems/DOC-001-documentation-alignment.md) |

## 信任边界责任

| 边界 | 身份事实源 | 资源归属事实源 | 授权执行点 | 必要审计 |
|---|---|---|---|---|
| 内部控制面 | `AuthInterceptor` / `@RequireAuth` 建立的当前用户 | Session/Task 统一 ownership service/facade | service/facade，不依赖 URL 是否 `permitAll` | 越权拒绝、管理员例外与可信内网接口清单 |
| ClientApp Open API | runtime/control credential principal | tenant + ClientApp + upstream user grant + task/session binding | OpenAPI facade / business service | 凭据换发、拒绝、查询、取消与失败 |
| Business Worker | task-scoped token 的服务端 claims | task + session + tenant + ClientApp + upstream user + function scope | Worker Gateway + Worker 双层约束 | invoke、拒绝、暂停、审批、恢复、取消、失败 |
| Worker 进程 | 配置的 Worker credential 与部署模式 | 服务端注入的工作目录、工具和任务上下文 | readiness、auth middleware、execution policy | 启动模式、unready 原因、认证失败与策略拒绝 |

## 依赖与实施顺序

```text
P0 术语/边界/清单冻结
  -> P1 可复现构建与 CI
  -> P2 外部运行面可信上下文
  -> P3 内部 Session/Task ownership
  -> P4 低风险清理
  -> P5 功能切片去留与独立退役
  -> P6 大类/状态契约/兼容 API 渐进治理
  -> P7 质量、覆盖、体验与签收
```

- P2 与 P3 的实现可以分支并行，但必须共享同一 principal/ownership 术语和负向测试口径。
- P4 只处理已经完成引用扫描且具备独立回滚的候选；P5 不得借用 P4 的低风险结论。
- P6 的旧 API 删除必须等待 P2/P3 新入口稳定、调用方迁移和流量静默窗口。
- 每个跨模块阶段完成 implementation self-check 后，依次进入正式实现质量闸门、测试覆盖审计和正式验收；隔离验证不等于生产批准。

## Owner 交接点

| 决策 | 建议 Owner | 决策前阻塞项 |
|---|---|---|
| upstream user 采用 ClientApp 代办 + grant，还是 signed subject assertion | Platform Security + Open SDK Owner | 调用方能力、兼容成本、审计要求 |
| task token 的函数 scope、TTL、撤销与轮换 | Business Agent Owner + Security | 函数快照/动态 grant 语义、任务恢复策略 |
| 外部 Codex 的 sandbox/approval/network 上限 | Codex Provider Owner + Security | 当前客户工作流与风险分级 |
| 关键审计 best-effort 还是强保证/outbox | Platform Owner + SRE | 留存、告警、失败容忍目标 |
| Monitoring、metadata-query、code-review、echo 的去留 | Product/Module Owner + SRE | 运行流量、部署、数据与替代能力证据 |
| 旧 Provider API 的兼容窗口 | PC/Mobile/SDK/CLI Owner | 消费者清单、迁移版本、静默窗口 |
