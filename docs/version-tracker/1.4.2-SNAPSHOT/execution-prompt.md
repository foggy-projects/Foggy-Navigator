# 1.4.2 平台治理迭代执行提示词

## 文档作用

- doc_type: execution-prompt
- intended_for: root-controller | execution-agent | reviewer
- purpose: 为后续实际实施提供可直接使用的范围、阶段、证据和停止条件；必须与 [Progress](./progress.md) 配套使用。

## 基本信息

- version: `1.4.2-SNAPSHOT`
- status: planned
- operation_mode: single-root-delivery
- implementation_started: no
- requirement: [REQ-001](./requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation_plan: [Implementation Plan](./implementation-plan.md)
- module_responsibility: [Module Responsibility](./module-responsibility.md)
- code_inventory: [Code Inventory](./code-inventory.md)
- owner_decision_review: [Owner Decision Review](./owner-decision-review.md)
- progress: [Progress](./progress.md)

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

Owner 决策评审稿中的建议只有在单项 `review_result=approved` 或 `approved-with-constraints` 且签署表包含 Owner、日期和约束后，才能作为实施决策。`pending-owner-review`、`pending-decision`、`deferred` 或空签署项不得被执行 Agent 当作已批准；但不改变生产状态的只读扫描、证据收集和方案补充可以按 workitem 继续。

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
- task-scoped token 必须绑定任务和允许的 BusinessFunction，并具备明确 TTL、撤销/轮换和终态失效策略。
- external-enabled 的非 loopback Worker 缺必要凭据时必须 fail closed 或 unready；显式 loopback internal-dev 可保留。
- 外部工作目录、工具、sandbox、approval、network 上限由服务端策略约束，Worker 不得扩大权限。
- Provider 状态已经有 envelope schema v1；本版本只做版本验证、typed adapter、迁移和可观测性增量，不重复建设 v1。
- UnifiedSseEmitter 仍是单 JVM 内存态；本版本不实现多实例事件总线。
- Addon 是编译期模块化单体；本版本不实现动态插件加载。
- ClaudeWorkerView.vue、OpenApiController、ClaudeTaskService、CodexTaskService、TaskDispatchFacade 只能渐进拆分，禁止一次性重写。
- 不得删除 CodingAgentEntity、/api/v1/coding-agents、ProfileView.vue、/c/:id、navigator-chat-widget、mobile uni_modules、keystore 或 metadata-config-module。
- Monitoring、metadata-query、code-review、echo、旧 Provider API 只有在各自运行态/消费者/数据/部署/迁移/回滚门禁齐备且 Owner 批准后，才能按独立功能切片退役。
- 静态未发现引用不等于无运行流量；不得虚构测试、流量、审批或生产证据。

执行顺序：
P0 目标/边界/术语/清单冻结
P1 clean build、Node/pnpm/lockfile、全仓 CI
P2 外部 Biz Worker/upstream user 治理
P3 Session/Task ownership
P4 第一档清理
P5 第二档审计与独立去留/退役
P6 大类、Provider 状态、旧 API 渐进治理
P7 质量、覆盖、体验和正式签收

每个实现阶段都必须：
1. 先补足能证明问题或约束的测试；安全边界至少有正向、伪造身份、跨租户/ClientApp/upstream user/任务/函数、过期/撤销等负向用例。
2. 保持 launcher 为部署壳，不把业务逻辑放入 launcher。
3. 将代码改动、测试命令、环境版本、结果、手工体验、风险、阻塞和生产影响实时回写 progress.md。
4. 对计划外变更先更新 code-inventory.md 并说明原因；不得静默扩范围。
5. 一个删除/迁移功能切片一个可回滚提交，记录引用扫描、替代、数据/配置处理和 git revert 路径。
6. UI 改动运行有效的类型检查、单测、构建和相关 Playwright/手工体验；不能以 vue-tsc 空检查作为通过。
7. 阶段结束运行 git diff --check、git status --short、Markdown 相对链接检查，并确认改动路径符合清单。

阶段收口顺序：
- Implementation Self-Check
- 对跨模块或高风险改动执行正式 foggy-implementation-quality-gate
- 执行 foggy-test-coverage-audit
- 执行 foggy-acceptance-signoff

只有当 requirement、workitem、implementation plan、progress、代码、测试和体验证据一致时才可申请签收。隔离 smoke 不等于生产批准；production enablement 必须单独记录。

遇到以下情况立即停止当前危险动作，回写 blocked/pending-decision，并请求 Owner：
- upstream user 证明方式或 task token scope 未决定；
- 删除候选仍有静态消费者、运行流量或未知部署；
- 需要改变生产路由、删除外部 API、执行数据删除/不可逆迁移；
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
| CLEAN-003 | [metadata-query 退役审计](./workitems/CLEAN-003-metadata-query-retirement-audit.md) |
| CLEAN-004 | [实验性 Addon 与旧 API 治理](./workitems/CLEAN-004-experimental-and-legacy-addon-governance.md) |
| DOC-001 | [文档对齐](./workitems/DOC-001-documentation-alignment.md) |

## 使用说明

- 本提示词不授权一次性执行全部阶段；实际请求必须指定阶段或 workitem。
- 如果执行 Agent 使用 `foggy-versioned-doc-tracking`，应继续沿用本版本目录并实时维护 `progress.md`。
- 规划执行完成后，质量、覆盖和验收材料在对应门禁真正执行时再创建；不得预写 `passed` 或 `accepted`。
