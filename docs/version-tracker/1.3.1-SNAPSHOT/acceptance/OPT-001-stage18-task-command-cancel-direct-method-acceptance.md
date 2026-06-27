---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 18 Task Command Cancel Direct Method
doc_role: acceptance-record
doc_purpose: 记录 Stage 18 provider command cancel direct method 的功能级正式验收结论与证据摘要
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 10
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / java-platform
- purpose: 记录 Stage 18 对 `TaskCommandProvider#cancelTask` legacy fallback 的收敛、签收结论、证据和剩余风险。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 18 Task Command Cancel Direct Method
- Owner: java-platform
- Goal: 为 provider command cancel 建立非 deprecated `cancelTaskDirect` 主路径，保留 legacy `cancelTask(String, String)` 兼容入口，并保持 A2A / REST cancel 行为不变。

## Acceptance Basis

- [workitem] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage18-task-command-cancel-direct-method.md`
- [root governance] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- [quality] `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage18-implementation-quality.md`
- [coverage] `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage18-coverage-audit.md`
- [test] targeted regression and affected reactor results recorded in Stage 18 workitem
- [evidence] static scans for provider-route legacy cancel, direct method usage, annotation state, and `git diff --check`

## Checklist

- [x] scope 内功能点已全部交付：`cancelTaskDirect` 已新增，session/provider 主链路已迁移。
- [x] 原始 acceptance criteria 已逐项覆盖：SPI direct method、legacy `forRemoval=false`、session direct route、内置 provider direct implementation、A2A untouched、测试与静态扫描均有证据。
- [x] 关键测试已通过：targeted regression 159 tests pass；affected reactor 1545 tests pass。
- [x] 体验验证已完成，或明确标记 `N/A`：本阶段纯 Java SPI 兼容治理，UI/Playwright 为 N/A。
- [x] 文档、配置、依赖项已闭环：workitem、README、治理主文档、quality、coverage、acceptance 已回写。

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage18-task-command-cancel-direct-method.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Test:
  - targeted regression：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,CodexTaskServiceTest,CodexBizTaskProviderTest,GeminiTaskServiceAuthResolutionTest,LanggraphTaskServiceTest,LanggraphWorkerInnerA2aAgentTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`，Surefire XML 合计 11 reports / 159 tests / 0 failures / 0 errors / 0 skipped。
  - affected reactor：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`，Surefire XML 合计 223 reports / 1545 tests / 0 failures / 0 errors / 0 skipped。
  - static scan：`rg -n "provider\.cancelTask\(" session-module/src/main/java` 无匹配。
  - static scan：`rg -n "cancelTaskDirect\(" ...` 确认 direct method 和 route/provider 使用面。
  - static scan：`TaskCommandProvider` fixed-string annotation scan 确认 legacy `forRemoval=true` 无匹配，expected `forRemoval=false` 存在。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- Experience:
  - N/A。未新增或修改 UI 页面、表单、列表、弹窗、按钮、权限可见性或前端交互。
- Artifact:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskCommandProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`

## Failed Items

- none

## Risks / Open Items

- 外部插件、SDK 或未纳入本仓测试的调用方可能仍使用 legacy `cancelTask(String, String)`；removal 前必须提供迁移窗口与 release note。
- 本阶段未运行根目录仓库级全量 `mvn test`；已运行 direct affected reactor，覆盖 Stage 18 涉及模块及其 `-am` 依赖。
- Stage 18 不处理生产 schema migration 工具化或 Claude sync 本地投影 service 化。

## Final Decision

Stage 18 验收结论为 `accepted-with-risks`。

核心目标已达成：provider command cancel 主链路不再依赖 deprecated legacy 方法；内置 provider 的真实取消行为已迁移到 `cancelTaskDirect`；legacy `cancelTask(String, String)` 保留兼容入口且暂不标记 for removal；A2A / REST cancel 对外语义保持不变。剩余风险均为后续 removal 或跨版本迁移治理项，不阻断本阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage18-task-command-cancel-direct-method-acceptance.md
- blocking_items: none
- follow_up_required: yes
