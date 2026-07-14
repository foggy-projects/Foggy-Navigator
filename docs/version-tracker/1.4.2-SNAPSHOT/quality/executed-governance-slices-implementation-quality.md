---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.2-SNAPSHOT
target: executed-governance-slices-through-9d03bee9
status: reviewed
decision: ready-with-risks
reviewed_by: root-controller
reviewed_at: 2026-07-14
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：`1.4.2-SNAPSHOT` 自规划基线 `c75e0cc0` 至本次审计冻结的实现 head `9d03bee9` 已执行并自报完成的治理切片，包括构建基线、P2/P3 首批治理、dev-only 功能切片退役、旧 Provider 契约收口和隔离 Session ownership 浏览器验证。
- 当前阶段：execution check-in 已完成，准备进入版本级测试证据覆盖审计。
- 本次目标：判断“已交付切片”是否存在必须先返工的实现质量问题。未实施的 P2/P3 剩余项、P4 和 P6 state/schema/超大类治理不属于本报告宣称完成范围，仍会在版本级覆盖审计和验收中作为缺口处理。
- 结论边界：本报告不是版本验收、生产批准或 external enablement 决策。

## Check Basis

- requirement：[REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- implementation plan：[Implementation Plan](../implementation-plan.md)
- module responsibility：[Module Responsibility](../module-responsibility.md)
- code inventory：[Code Inventory](../code-inventory.md)
- progress / execution check-in：[Progress](../progress.md)，重点为 `EXEC-142-001` 至 `EXEC-142-018`
- bug work item：[BUG-001](../workitems/BUG-001-langgraph-progress-event-duplication.md)、[BUG-002](../workitems/BUG-002-open-sdk-clean-test-baseline.md)
- changed surface：`git diff c75e0cc0..9d03bee9`，共 420 个文件、28412 行新增、11734 行删除；大比例来自完整功能切片删除、迁移脚本、测试和版本文档。
- test result summary：本机 Java/前端/Worker clean 矩阵、两次 GitHub hosted repository CI 7/7 jobs、隔离 H2 Session ownership live Playwright 1 passed、mock Playwright 17 passed/1 opt-in live skipped；精确结果及限制见 [Progress](../progress.md#testing-progress)。

## Changed Surface

- 构建与仓库治理：`.nvmrc`、根 `package.json`、`pnpm-lock.yaml`、根/launcher POM、`.github/workflows/repository-ci.yml`、`.github/workflows/repository-nightly.yml`。
- 外部运行面：`business-agent-module` 的 task capability、Worker credential/principal/lease、Gateway 授权、ClientApp/pool 绑定、终态和审计；Claude/Codex/LangGraph Java Addon 与 Codex/LangGraph Worker 的 external-mode/readiness/secret 边界。
- 内部控制面：`session-module` 的 `SessionTaskResourceAccessService` 及 Session/Task/Agent/SSE/config/shared/forward/context/model-config 调用点。
- 契约收口：`navigator-spi`、Claude/Codex/LangGraph 旧 HTTP 入口、旧 form/DTO、PC/Mobile/SDK 仓内调用方；`navigator-common` 的 Navigator-owned `RX` wire-contract 兼容层。
- 清理切片：Monitoring、metadata-query、code-review-agent、Echo 默认装配及对应 UI/API/脚本/文档。
- 验证：Java/JPA/Worker/前端回归测试、SQL forward/rollback、`packages/navigator-frontend/e2e/ownership-live.spec.ts`。
- declared completed scope：上述已提交子切片及其 check-in；不包括 external 真正启用、完整 upstream user 强身份证明、可靠 outbox、真实 non-loopback/Provider Task、共享数据库、P4 候选清理、Provider state typed schema 和超大类拆分。

## Quality Checklist

- scope conformance：通过，带范围风险。代码和删除项与 Owner 批准的 dev/internal 边界一致，未修改生产路由或启用 external；未完成事项在 requirement、workitem 和 progress 中保持 `in-progress`/`not-run`，没有被表述为已交付。
- code hygiene：通过。`git diff --check c75e0cc0..9d03bee9` 无错误；新增行未发现 `TODO/FIXME/HACK/debugger/System.out` 临时残留。Worker 中既有/新增的控制台输出属于进程启动与诊断输出，不作为浏览器调试残留处理。
- duplication and consolidation：通过，带漂移风险。ownership 收敛到统一 service，Gateway principal/token 校验收敛到授权 service，避免 Controller 自行复制规则；多进程 Worker 各自保留 external-mode 实现，后续必须通过契约测试防止配置和 reason code 漂移。
- complexity and abstraction：带风险。`BusinessTaskScopedTokenLifecycleService` 已集中承载签发、绑定、终态和撤销生命周期，测试充分但体量较大；当前不阻断本切片审计，后续应按 policy、persistence、terminal lifecycle 职责小步拆分。既有 `TaskDispatchFacade`、Provider service 和 `ClaudeWorkerView.vue` 重类仍由 OPT-002 管理。
- error handling and edge cases：通过。strict Worker header 的空白/缺失/partial/legacy 组合 fail closed；task capability 覆盖 TTL、函数快照、撤销、终态 tombstone、late bind；ownership 对软删除、Provider 回填 sessionId 和非 owner 访问采用拒绝路径。
- readability and maintainability：通过，带风险。关键边界已有具名 service、DTO 和测试；高风险领域对象数量增加，命名和职责仍可追踪，但 token 生命周期与 Worker route 判断需要保持窄接口，禁止继续在同一 service 堆叠新策略分支。
- critical logic documentation：通过。internal-dev 不是防火墙、legacy header 仅用于拒绝、headerless token-only 只在 external-disabled 兼容、RX shim 不得扩张等非直观约束均有代码注释或版本文档说明。
- contract and compatibility：通过已声明切片。旧 Provider HTTP/SPI/DTO 在仓内迁移后物理移除，精准扫描未发现运行时回流；保留的 `X-Worker-Id` 常量只用于识别并拒绝 legacy header，不构成兼容入口。`RX` 使用既有 FQCN 和 wire contract 兼容，需由契约测试长期锁定。
- documentation and writeback：通过。实现提交、命令、测试数量、未运行项、生产/外部影响和回滚边界已回写到 requirement、plan、inventory、workitem 和 progress；正式 quality/coverage/acceptance 由本阶段新增。
- test alignment：通过，带覆盖缺口。单元/JPA/clean reactor/Worker/前端/hosted CI 与实际改动面匹配；真实 external 网络、Provider Task、共享数据库和完整 UI 主链仍缺证据，但属于覆盖审计和剩余实现，不是“测试与宣称完成内容失焦”。
- release readiness：仅已执行切片可进入覆盖审计；整个 `1.4.2-SNAPSHOT` 不具备验收或外部启用条件。

## Findings

1. 未发现必须先修复才能审计的新增实现缺陷；已提交切片的改动范围、check-in 和自动化结果能够相互映射。
2. 新增的 task capability/terminal/Worker binding 规则集中度较高。现阶段集中服务有利于保持不变量，但若继续叠加 pause/generation/outbox，应先拆出明确领域组件，避免形成新的超大类。
3. Navigator-owned `com.foggyframework.core.ex.RX` 是 clean-runner 兼容层，不是恢复整个历史 `foggy-core`。它必须保持最小实现并由 `RXContractTest` 锁定序列化契约。
4. 三个 Worker 的 external profile 是独立进程契约，当前测试已锁定默认关闭和 unready；未来解除 `EXTERNAL_EXECUTION_POLICY_PENDING` 时需要统一契约矩阵，不能分别手工放开。
5. P3 已建立统一 ownership 门面，但全列表 tenant、Provider taskId、显式 admin/system 通路和 Task live fixture 尚未闭合；这些是版本范围缺口，不应在本切片质量报告中降级为已完成。
6. 旧 Provider 目标路由/Controller/deprecated SPI/DTO 已退出；范围外的非 Provider `@Deprecated` 不在本次清零声明内。

## Risks / Follow-ups

- risk 1：420 文件的大范围版本 diff 提高人工复核成本；正式验收必须依赖逐工作项提交、check-in 和覆盖矩阵，不能只看总 diff。
- risk 2：task lifecycle service 和 Worker route 校验继续增长会削弱可维护性；由 OPT-002 在新增 pause/generation/outbox 前定义拆分点。
- risk 3：RX FQCN 兼容层可能被误当作通用框架继续扩张；只允许现用 wire contract，新增 API 必须另行评审。
- risk 4：hosted CI 通过不代表 required checks、branch protection 或 nightly 已生效；main 当前未配置 required checks/branch protection，修复后的 nightly 未实跑，仓库 Owner 仍需配置和执行。
- follow-up 1：按版本级覆盖审计逐项核对 13 个 AC 和两个已关闭 BUG。
- follow-up 2：补真实 Provider Task / external / non-loopback /共享数据库证据前保持 external 默认关闭。
- follow-up 3：P4、P6 剩余实现完成后重新执行本质量闸门，不沿用本报告替代未来代码审查。

## Recommended Next Skills

- `foggy-test-coverage-audit`：立即执行版本级 `pre-acceptance-check`，本报告允许进入。
- `foggy-bug-regression-workflow`：本轮未发现需要新增 BUG workitem 的稳定缺陷；如后续 live 验证发现缺陷再触发。
- `plan-evaluator`：仅在 token lifecycle 或 Provider state 拆分方案存在争议时使用，不替代当前覆盖审计。
- back to implementation：覆盖审计确认的关键缺口应回到 P2/P3/P4/P6 补实现和测试。

## Decision

- decision: ready-with-risks
- can_enter_coverage_audit: yes，限已执行切片进入版本证据覆盖审计；不代表版本可验收
- follow_up_required: yes
