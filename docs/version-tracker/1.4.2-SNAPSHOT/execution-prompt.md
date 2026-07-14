# 1.4.2 平台治理迭代执行提示词

## 文档作用

- doc_type: execution-prompt
- intended_for: root-controller | execution-agent | reviewer
- purpose: 为后续实际实施提供可直接使用的范围、阶段、证据和停止条件；必须与 [Progress](./progress.md) 配套使用。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: in-progress
- owner_decision_status: review-complete
- operation_mode: single-root-delivery
- implementation_started: yes
- production_routing_changed: no
- external_contract_changed: yes
- external_enablement: no
- production_enablement: not-applicable
- acceptance_status: not-started
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- progress: [Progress](./progress.md)
- last_execution_checkin: `2026-07-14 / P2-first-external-gate-readiness`

当前检查点：`2026-07-14` 已实施 P1 本地基线以及 Monitoring/code-review 两个独立删除切片；metadata-query 的模块、reactor/launcher 装配、专属 bean 断言、专属 Skill 与当前文档已收口，CLEAN-003 状态为 `completed-local`。P2 首批平台 Open API routing gate、LangGraph Biz/Codex SDK/Codex App Server Worker external gate/readiness，以及 Java 健康状态消费者已分别落在 `12cbe697`、`5d62707b`、`cce75f1b`。根 Java clean test 随后发现并由 `a2317ae2` 关闭 [BUG-002](./workitems/BUG-002-open-sdk-clean-test-baseline.md)，当前 17/17 reactor、2304 tests 全通过；launcher 仍有 Surefire fork JVM 退出超时告警，hosted CI 与 `clean verify` 未执行。这只是 execution check-in：task token、可信 upstream identity、审计、完整 execution policy、Claude/Gemini Worker、生产 readiness 和外部开放均未完成。启动/浏览器与正式验收未执行。后续执行者必须先读 Progress，不得重复实施已落码门禁、把本地结果冒充生产/验收证据，或把 Echo、旧 Provider 契约和 P2/P3 治理写成已完成。

## P2 首批实现交接

| 边界 | 已实施 | 后续执行必须保持的限制 |
|---|---|---|
| 平台 Open API surface | `NAVIGATOR_EXTERNAL_ENABLED=false`；只门禁 `/api/v1/open` 与 `/api/v1/open/**`；开关关闭返回 `503 / EXTERNAL_SURFACE_DISABLED` | `/api/v1/health/external-surface` 的 `surfaceReady` 只表示 routing gate open；不覆盖 upstream-admin、`/internal/worker-gateway/v1/**` 或内部 Controller，也不证明 Provider/生产 ready |
| LangGraph Biz Worker | `BIZ_WORKER_EXTERNAL_ENABLED=false`；仅精确 `GET /health` 保持可见；external-enabled unready 时其他 HTTP ingress 返回 `503 / EXTERNAL_WORKER_UNREADY` | 当前无论 Token 是否存在都包含 `EXTERNAL_EXECUTION_POLICY_PENDING`；`/health/` 不属于豁免路径；不得删除 pending 或开始接外部任务 |
| Codex SDK Worker | `CODEX_WORKER_EXTERNAL_ENABLED=false`；external middleware 在既有 auth 前执行；精确 `GET /health` 输出非敏感原因 | external-enabled 当前始终 unready；`/health/` 不等价于 canonical probe；不得把现有 allowed cwd、空 Token 或开发默认值解释为完整外部策略 |
| Codex App Server Worker | `CODEX_APP_SERVER_EXTERNAL_ENABLED=false`；auth 和 runtime capability/readiness 共同尊重 external 状态；仅精确 `GET /health` 豁免 | external-enabled 当前始终 unready；`/health/` 可能返回 503；完整 tool/sandbox/approval/network policy 未完成 |
| Java 消费者 | LangGraph/Codex 平台消费者尊重显式 `ready=false`；旧 Worker 缺 `ready` 字段仍兼容 | 缺字段兼容只是滚动升级行为，不是 external-ready 证明 |

`internal-dev` 是可信网络 profile，不是防火墙。三个 Worker 在 external 开关为 `false` 时保留既有默认监听和空 Token 行为，部署者仍须用网络边界限制可达性；不得通过把该 profile 暴露到不可信网络来绕过 external 门禁。

## 可复制执行提示词

```text
你位于 Foggy Navigator 根仓库。请实施 1.4.2-SNAPSHOT“平台治理与历史能力收口迭代”。

开始前必须完整读取：
1. docs/version-tracker/1.4.2-SNAPSHOT/README.md
2. docs/version-tracker/1.4.2-SNAPSHOT/requirements/REQ-001-platform-governance-and-legacy-cleanup.md
3. docs/version-tracker/1.4.2-SNAPSHOT/module-responsibility.md
4. docs/version-tracker/1.4.2-SNAPSHOT/code-inventory.md
5. docs/version-tracker/1.4.2-SNAPSHOT/owner-decision-review.md
6. docs/version-tracker/1.4.2-SNAPSHOT/implementation-plan.md
7. docs/version-tracker/1.4.2-SNAPSHOT/progress.md
8. 本次指定阶段对应的 workitem
9. 当前代码、配置和同类测试；不得只按规划文本猜实现

执行方式：single-root-delivery。一次只实施一个已明确授权的阶段或工作项，不得把 P0-P7 一次性混成大提交。

Owner 决策评审已经 `review-complete`。ODR-142-001/003/004/005/008 按批准结论执行；ODR-142-002 按“signed assertion 降为低优先级、external 显式开关保持硬门”执行；ODR-142-006 已授权 dev-only 数据丢弃和完整切片物理删除；ODR-142-007 已取消上游/生产兼容窗口，仓内消费者迁移后可直接删除旧 API/SPI/DTO。批准只解除设计/删除决策门，不表示实现、测试、验收或生产启用完成。

先报告：
- 本次阶段/工作项；
- 当前基线与工作树状态；
- 已确认事实、静态搜索结论、运行态待证、Owner 决策；
- 预计修改路径、明确不修改路径；
- 自动化与手工验证矩阵；
- 是否可能改变生产路由、外部契约、数据 schema 或凭据行为。

实施约束：
- 内部控制面保持轻量认证，在统一 service/facade 建立 Session/Task ownership 不变量；不要重写 Spring Security。
- 外部运行面使用服务端可信 principal 和绑定，不信任请求体中的 userId、tenantId、reviewedBy 等身份字段。
- 1.4.2 以 ClientApp credential + upstream user mapping/grant 作为当前身份基线，并在审计中标记 delegated assurance；independent signed assertion 是低优先级后续项，不阻塞 P2/P7，也不得被虚报为已实现。
- task-scoped token 必须绑定任务和允许的 BusinessFunction，并具备明确 TTL、撤销/轮换和终态失效策略。
- external-enabled 必须由默认关闭的显式配置开关启用；不得由监听地址、请求参数或空 Token 自动推断。首批三个 Worker 的开关启用后当前一律因 `EXTERNAL_EXECUTION_POLICY_PENDING` unready/fail closed，缺 Token 还必须报告 `EXTERNAL_AUTH_TOKEN_REQUIRED`。开关关闭时保留既有监听/空 Token 行为，但 `internal-dev` 只适用于可信网络，不是防火墙或外部安全 profile。
- 平台 `NAVIGATOR_EXTERNAL_ENABLED` 只控制 `/api/v1/open` routing surface；`surfaceReady` 不得作为 Worker、身份、审计、外部契约或生产 readiness。upstream-admin、Worker Gateway 和内部 Controller 继续按各自治理工作项处理。
- LangGraph/Codex 平台健康消费者必须拒绝显式 `ready=false`；对缺少 `ready` 的旧 Worker 保持当前兼容，不能把兼容结果写成 external-ready 证据。
- 外部工作目录、工具、sandbox、approval、network 上限由服务端策略约束，Worker 不得扩大权限。
- Provider 状态已经有 envelope schema v1；本版本只做版本验证、typed adapter、迁移和可观测性增量，不重复建设 v1。
- UnifiedSseEmitter 仍是单 JVM 内存态；本版本不实现多实例事件总线。
- Addon 是编译期模块化单体；本版本不实现动态插件加载。
- ClaudeWorkerView.vue、OpenApiController、ClaudeTaskService、CodexTaskService、TaskDispatchFacade 只能渐进拆分，禁止一次性重写。
- 不得删除 CodingAgentEntity、/api/v1/coding-agents、ProfileView.vue、/c/:id、navigator-chat-widget、mobile uni_modules、keystore 或 metadata-config-module。
- Monitoring、metadata-query、code-review、echo 已获 dev-only 物理删除授权，开发数据可丢弃；按完整功能切片独立执行，不等待生产流量静默或数据备份/保留。执行前必须确认目标不是共享/生产资源，发现此类资源立即停止。
- 旧 Provider API/SPI/DTO 不设上游/生产弃用或兼容窗口；必须先迁移或删除 PC、L3、Worker/canary、stream relay 等全部仓内引用，再在 P6 直接删除旧契约。
- 静态引用命中必须处理；不得虚构测试、流量、审批、环境范围或生产证据。

执行顺序：
P0 目标/边界/术语/清单冻结
P1 clean build、Node/pnpm/lockfile、全仓 CI
P2 已完成首批平台/三个 Worker explicit external routing/readiness gate；继续实施 ClientApp/grant 身份基线、task token、完整 Worker policy 与审计；signed assertion 为低优先级后续项
P3 Session/Task ownership
P4 第一档清理
P5 dev-only 第二档完整切片独立物理清理
P6 大类、Provider 状态治理；仓内迁移后直接删除旧 API/SPI/DTO
P7 质量、覆盖、体验和正式签收

每个实现阶段都必须：
1. 先补足能证明问题或约束的测试；安全边界至少有正向、伪造身份、跨租户/ClientApp/upstream user/任务/函数、过期/撤销等负向用例。
2. 保持 launcher 为部署壳，不把业务逻辑放入 launcher。
3. 将代码改动、测试命令、环境版本、结果、手工体验、风险、阻塞和生产影响实时回写 progress.md。
4. 对计划外变更先更新 code-inventory.md 并说明原因；不得静默扩范围。
5. 一个删除/迁移功能切片一个可回滚提交，记录引用扫描、仓内消费者处理、开发数据/配置处理和 git revert 路径；数据丢弃获批时明确写 `backup: not-required-by-owner`，不能伪造备份。
6. UI 改动运行有效的类型检查、单测、构建和相关 Playwright/手工体验；不能以 vue-tsc 空检查作为通过。
7. 阶段结束运行 git diff --check、git status --short、Markdown 相对链接检查，并确认改动路径符合清单。

阶段收口顺序：
- Implementation Self-Check
- 对跨模块或高风险改动执行正式 foggy-implementation-quality-gate
- 执行 foggy-test-coverage-audit
- 执行 foggy-acceptance-signoff

只有当 requirement、workitem、implementation plan、progress、代码、测试和体验证据一致时才可申请签收。隔离 smoke 不等于生产批准；production enablement 必须单独记录。

遇到以下情况立即停止当前危险动作，回写 blocked/pending-decision，并请求 Owner：
- explicit external 开关无法保证默认关闭，或启用时无法满足 credential、task scope、Worker policy、readiness 和审计硬门；
- task token 的函数 scope、Worker principal/lease 或撤销/终态语义需要偏离已批准 ODR-142-003；
- dev-only 删除命中共享/生产数据库、RabbitMQ、部署、webhook、credential 或上游消费者；
- 旧 Provider 契约仍有未处理的仓内编译、运行、测试或持久链接引用；
- 需要改变生产路由、生产数据或生产外部契约；本次 dev-only 授权不得外推；
- 需要扩大 Worker 工具/目录/网络权限；
- 无法在 clean 环境复现基线；
- 现有用户改动与本阶段路径冲突。

最终交付需汇总：实际文件、阶段和 workitem、事实与决策、测试/体验证据、未决项、风险与回滚、生产路由/外部契约/启用状态。
```

## 工作项入口

| 工作项 | 执行入口 |
|---|---|
| GOV-001 | [内部控制面与外部运行面信任边界](./workitems/GOV-001-internal-external-trust-boundary.md) |
| GOV-002 | [Biz Worker 与 upstream user 边界](./workitems/GOV-002-biz-worker-and-upstream-user-boundary.md) |
| GOV-003 | [Session/Task 资源归属](./workitems/GOV-003-session-task-resource-ownership.md) |
| OPT-001 | [构建与 CI 基线](./workitems/OPT-001-build-and-ci-baseline.md) |
| OPT-002 | [核心代码可维护性](./workitems/OPT-002-core-code-maintainability.md) |
| CLEAN-001 | [低风险孤儿清理](./workitems/CLEAN-001-low-risk-orphan-cleanup.md) |
| CLEAN-002 | [Monitoring 退役](./workitems/CLEAN-002-monitoring-retirement.md) |
| CLEAN-003 | [metadata-query dev-only 完整退役](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| CLEAN-004 | [实验性 Addon 与旧 API 治理](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| DOC-001 | [文档对齐](./workitems/DOC-001-documentation-alignment.md) |
| BUG-001 | [LangGraph progress 事件重复（实施期缺陷，closed）](./workitems/BUG-001-langgraph-progress-event-duplication.md) |
| BUG-002 | [Open SDK clean test 基线（实施期缺陷，closed）](./workitems/BUG-002-open-sdk-clean-test-baseline.md) |

## 使用说明

- 本提示词不授权一次性执行全部阶段；实际请求必须指定阶段或 workitem。
- 如果执行 Agent 使用 `foggy-versioned-doc-tracking`，应继续沿用本版本目录并实时维护 `progress.md`。
- 规划执行完成后，质量、覆盖和验收材料在对应门禁真正执行时再创建；不得预写 `passed` 或 `accepted`。
