---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 17 Legacy Provider Method Deprecation Gate
doc_role: acceptance-record
doc_purpose: 记录 Stage 17 legacy provider method deprecation gate 的功能级正式验收结论与证据摘要
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 9
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / java-platform
- purpose: 记录 Stage 17 对 legacy listing / worker-session provider 方法 deprecation gate 的签收结论、证据和剩余风险。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 17 Legacy Provider Method Deprecation Gate
- Owner: java-platform
- Goal: 在 Stage 14/15 typed 主链路完成后，对 legacy provider methods 建立明确 deprecated 信号与 removal gate，同时不删除兼容面、不改变 REST payload。

## Acceptance Basis

- [workitem] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`
- [root governance] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- [quality] `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage17-implementation-quality.md`
- [coverage] `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage17-coverage-audit.md`
- [test] targeted regression and affected reactor results recorded in Stage 17 workitem
- [evidence] static scans for production fan-out legacy calls, deprecated annotation count, and `git diff --check`

## Checklist

- [x] scope 内功能点已全部交付：legacy SPI methods 与 provider wrappers 已标记 deprecated。
- [x] 原始 acceptance criteria 已逐项覆盖：SPI annotation、wrapper annotation、typed behavior unchanged、reflection test、targeted/affected tests、static scan 均有证据。
- [x] 关键测试已通过：targeted regression 131 tests pass；affected reactor 1528 tests pass。
- [x] 体验验证已完成，或明确标记 `N/A`：本阶段纯 Java SPI 兼容治理，UI/Playwright 为 N/A。
- [x] 文档、配置、依赖项已闭环：workitem、README、治理主文档、quality、coverage、acceptance 已回写。

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage17-legacy-provider-method-deprecation.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Test:
  - targeted regression：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am "-Dtest=TaskProviderLegacyContractTest,TaskDispatchFacadeTest,TaskQueryProviderRegistryTest,ClaudeTaskServiceAuthTest,ClaudeWorkerSessionQueryServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest,LanggraphWorkerSessionQueryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`，131 tests pass。
  - affected reactor：`mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/langgraph-biz-worker -am`，Surefire XML 合计 219 reports / 1528 tests / 0 failures / 0 errors / 0 skipped。
  - static scan：`rg provider\.(...)` 无生产 fan-out legacy 调用。
  - static scan：deprecated annotation fixed-string scan 命中 24 处 expected annotations。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- Experience:
  - N/A。未新增或修改 UI 页面、表单、列表、弹窗、按钮、权限可见性或前端交互。
- Artifact:
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskProviderLegacyContractTest.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/WorkerSessionQueryProvider.java`

## Failed Items

- none

## Risks / Open Items

- 外部插件、SDK 或未纳入本仓测试的调用方可能仍使用 legacy 方法；removal 前必须提供迁移窗口与 release note。
- 本阶段未运行根目录仓库级全量 `mvn test`；已运行 direct affected reactor，覆盖 Stage 17 涉及模块及其 `-am` 依赖。
- Stage 17 不处理 `TaskCommandProvider#cancelTask` deprecated fallback、生产 schema migration 工具化或 Claude sync 本地投影 service 化。

## Final Decision

Stage 17 验收结论为 `accepted-with-risks`。

核心目标已达成：legacy listing / worker-session provider 方法已进入明确 deprecated 迁移窗口，typed 主链路与 REST payload 兼容行为保持不变，并通过反射回归、受影响模块回归和静态扫描验证。剩余风险均为后续 removal 或跨版本迁移治理项，不阻断本阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage17-legacy-provider-method-deprecation-acceptance.md
- blocking_items: none
- follow_up_required: yes
