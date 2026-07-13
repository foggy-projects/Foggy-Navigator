# 1.4.2 代码与文档清单

## 文档作用

- doc_type: code-inventory
- intended_for: root-controller | execution-agent | reviewer | module-owner
- purpose: 以可核对路径冻结 1.4.2 的创建、更新、只读审计和禁止触碰清单。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: planned
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- inventory_rule: 路径为当前静态扫描结果；`update` 表示后续阶段的预期，不表示本轮已修改业务代码。

## 变更分类

| 分类 | 含义 |
|---|---|
| `create` | 1.4.2 规划或执行时新增的文档、测试或窄边界实现 |
| `update` | 已有文件的定向变更；实施前需重新确认行级上下文 |
| `read-only-analysis` | 只做引用、流量、配置、数据或依赖审计，未满足门禁不得修改 |
| `do-not-touch` | 1.4.2 明确保留，或必须先完成迁移/备份/轮换 |

## 本轮实际文档落档

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `docs/version-tracker/1.4.2-SNAPSHOT/README.md` | 版本索引 | create | 版本状态、范围、工作项和门禁 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/requirements/REQ-001-platform-governance-and-legacy-cleanup.md` | 需求基线 | create | 产品定位、治理边界、验收标准 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/module-responsibility.md` | 模块职责 | create | 依赖方向与 Owner 交接点 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/code-inventory.md` | 代码清单 | create | 本文件 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/implementation-plan.md` | 阶段计划 | create | P0-P7 执行与回滚门禁 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/owner-decision-review.md` | Owner 决策评审 | create | 八组建议、替代方案、签署状态与实施门禁；建议不等于批准 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/execution-prompt.md` | 开工提示 | create | 后续执行 Agent 的范围和记录要求 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/progress.md` | 进度模板 | create | 与 execution prompt 配套，当前不写虚假证据 |
| root | `docs/version-tracker/1.4.2-SNAPSHOT/workitems/*.md` | 工作项 | create | 3 个治理、2 个优化、4 个清理、1 个文档工作项 |
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
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/ClientAppUserGrantService.java` | upstream user grant | update | 冻结 upstream subject 证明模式 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java` | task token 持久 claims | update | 增加函数 scope/version 与撤销语义，需迁移设计 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | BusinessTask 创建/恢复 | update | 服务端固化 tenant/ClientApp/upstream user/task/function scope |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/WorkerGatewayController.java` | Worker Gateway 入口 | update | 只接受 task token principal，不信任身份字段 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/WorkerGatewayService.java` | 函数授权与执行 | update | enforce task-level function scope、跨任务拒绝与拒绝审计 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/controller/BusinessFunctionApprovalController.java` | 审批控制面 | update | 保持 credential principal；补全 task/subject 绑定负向验证 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessFunctionSuspensionService.java` | 暂停/恢复绑定 | update | 统一审批、恢复、取消归属和审计语义 |
| root | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/tool/TaskScopedTokenResolver.java` | Worker token 注入 | update | 禁止跨任务 fallback；明确重启/恢复行为 |

## Worker 与外部执行触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `tools/claude-agent-worker/src/agent_worker/auth.py` | Claude Worker HTTP auth | update | external-enabled 非 loopback 空凭据 fail closed/unready |
| root | `tools/codex-agent-worker/src/auth.ts` | Codex Worker HTTP auth | update | 同上，保留显式 loopback internal-dev |
| root | `tools/codex-app-server-worker/src/auth.ts` | Codex app-server auth | update | 对齐部署模式与 readiness |
| root | `tools/gemini-agent-worker/src/auth.ts` | Gemini Worker auth | update | 对齐统一模式和负向测试 |
| root | `tools/langgraph-biz-worker/src/langgraph_biz_worker/auth.py` | LangGraph Worker auth | update | 默认外部绑定不得因空 Token 跳过认证 |
| root | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/execution_policy.py` | workdir/tool policy | update | 外部模式服务端限制优先，空 allowlist 语义明确 |
| root | `tools/codex-agent-worker/src` | Codex 执行策略 | update | 冻结 allowed cwd/tool/sandbox/approval/network 上限 |
| root | `tools/gemini-agent-worker/src` | Gemini 执行策略 | read-only-analysis | 确认实际工作目录、工具和配置边界 |

## 构建与 CI 触点

| 仓库 | 路径 | 角色 | 预期变更 | 说明 |
|---|---|---|---|---|
| root | `pom.xml` | Maven reactor | update | 仅在模块去留决策获批后调整；Java clean gate 以此为基线 |
| root | `launcher/pom.xml` | 生产装配 | update | 仅在独立退役/装配决策后调整 |
| root | `package.json` | pnpm workspace 根入口 | update | 增加 `packageManager`、`engines` 和全包脚本 |
| root | `pnpm-workspace.yaml` | 前端 workspace | update | 明确纳入 chat-core、chat、PC、widget、mobile |
| root | `.gitignore` | lockfile 跟踪规则 | update | 解除根 `pnpm-lock.yaml` 的全局忽略，保留生成物排除 |
| root | `pnpm-lock.yaml` | 根依赖锁 | create | 在已冻结 Node/pnpm 环境重建并提交 |
| root | `scripts/build-frontend.sh` | 前端聚合构建 | update | 覆盖全部交付包或明确分 lane 调用 |
| root | `packages/foggy-chat-core/package.json` | chat-core lane | update | 补齐一致的 type/test/build 入口或在矩阵显式声明 |
| root | `packages/foggy-chat/package.json` | chat lane | update | frozen install 后 test/build |
| root | `packages/navigator-frontend/package.json` | PC lane | update | `type-check`、test、`build:check` |
| root | `packages/navigator-chat-widget/package.json` | widget lane | update | test/build；需要时单列 Playwright |
| root | `packages/foggy-mobile/package.json` | mobile lane | update | 至少 type/test 与目标平台 build |
| root | `.github/workflows/codex-worker-release-candidate.yml` | 现有 Codex 发布流程 | read-only-analysis | 不把单 Worker 发布流当全仓 CI |
| root | `.github/workflows/` | 全仓 CI | create | Java、pnpm、Node Worker、Python Worker 矩阵 |
| root | `README.md` | 环境说明 | update | 从 Node 18+ 修正为 Owner 最终批准的明确支持线；当前提案为 Node `22.23.1` |

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

## 第二档完整功能切片

| 仓库 | 路径/切片 | 角色 | 预期变更 | 删除前门禁 |
|---|---|---|---|---|
| root | `monitoring-module/`、`tools/foggy-monitor/`、`packages/navigator-frontend/src/views/MonitoringView.vue`、`packages/navigator-frontend/src/api/monitoring.ts`、`scripts/start-all.sh`、`SecurityConfig` 放行项、相关文档/部署 | Monitoring | read-only-analysis | 流量、部署、替代、配置、数据和回滚齐备，成组退役 |
| root | `metadata-query-module/`、根 reactor、`launcher/pom.xml`、配置/数据库/文档 | 旧语义查询 | read-only-analysis | 运行日志、外部流量、DB、部署、第三方调用核对，独立决策 |
| root | `addons/code-review-agent/` | GitLab code review | read-only-analysis | webhook、外部消费者、配置、持久数据审计 |
| root | `addons/echo-agent/`、根 reactor、`launcher/pom.xml` | 示例 Provider | read-only-analysis | 替代 smoke/dev fixture 与生产装配影响确认 |
| root | `/api/v1/claude-tasks`、`/api/v1/codex-tasks`、`/api/v1/langgraph-tasks` 对应 Controller、DTO、SPI、前端/Worker/SDK/CLI 调用 | 旧 Provider API | read-only-analysis | 消费者迁移、流量静默、兼容窗口和逐路由回滚 |

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
2. 从 `read-only-analysis` 提升为 `update/delete` 必须附对应 workitem 的证据与 Owner 决策。
3. 删除使用独立、可回滚提交；不得把多个第二档功能切片混成一个提交。
4. 任何生产路由或外部契约变化都必须回写版本状态；本规划落档本身不改变生产路由。
5. 静态搜索没有命中只代表“未发现静态引用”，不代表无运行流量或外部消费者。
