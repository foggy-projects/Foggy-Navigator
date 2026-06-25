---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001-stage7-provider-listing-envelope
doc_role: acceptance-record
doc_purpose: 说明本文件用于 Stage 7 Provider listing/search typed envelope 治理的功能级正式验收与签收结论记录
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-25
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 6
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 OPT-001 Stage 7 Provider listing/search typed envelope 治理的功能级正式验收结论与证据摘要。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 7 Provider listing/search typed envelope 治理
- Owner: navigator-spi / session-module / Worker Provider
- Goal: 在保持旧 Provider 和外部响应兼容的前提下，为 listing/search 聚合结果建立 typed envelope 主路径，降低 `UnifiedSessionTaskProjectionService` 对 Map key / JavaBean getter 反射读取的依赖。

## Acceptance Basis

- Requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
- Implementation plan / progress: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage7-provider-listing-envelope.md`
- Quality gate: `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage7-implementation-quality.md`
- Coverage audit: `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage7-coverage-audit.md`
- Test records:
  - `mvn test -pl session-module,addons/claude-worker-agent,addons/codex-worker-agent -am "-Dtest=UnifiedSessionTaskProjectionServiceTest,TaskDispatchFacadeTest,ClaudeTaskServiceTest,CodexTaskServiceTest,CodexBizTaskProviderTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: session-module 58 tests pass; codex-worker-agent 28 tests pass.
  - `mvn test -pl navigator-spi,session-module,addons/claude-worker-agent,addons/codex-worker-agent,addons/gemini-worker-agent,addons/langgraph-biz-worker -am`: affected reactor pass; Surefire XML total 221 reports / 1524 tests / 0 failures / 0 errors / 0 skipped.
  - `git diff --check`: no whitespace errors; only CRLF normalization warnings.

## Checklist

- [x] scope 内功能点已全部交付
- [x] 原始 acceptance criteria 已逐项覆盖
- [x] 关键测试已通过
- [x] 体验验证已完成，或明确标记 `N/A`
- [x] 文档、配置、依赖项已闭环

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-java-architecture-risk-governance.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage7-provider-listing-envelope.md`
- Implementation:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskPageResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskSearchResult.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/TaskListingProvider.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionService.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProvider.java`
- Test:
  - `session-module/src/test/java/com/foggy/navigator/session/service/UnifiedSessionTaskProjectionServiceTest.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - affected Java reactor command listed above
- Quality:
  - `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage7-implementation-quality.md`
  - `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage7-coverage-audit.md`
- Experience:
  - N/A。该切片为 Java SPI 和后端聚合 contract 治理，未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Failed Items

- none

## Risks / Open Items

- `TaskListingProvider` 仍保留 `Object` 返回类型。Owner: navigator-spi / java-platform。Follow-up: 后续阶段评估将 listing/search 方法签名进一步收紧到 typed envelope，或提供新的 strictly typed SPI。
- `UnifiedSessionTaskProjectionService` 仍保留 legacy Map / JavaBean getter fallback。Owner: session-module。Follow-up: 等外部 Provider 迁移完成后，再规划 fallback 删除或降级为 deprecated compatibility path。
- Claude SPI wrapper 缺少直接行为单测。Owner: claude-worker-agent。Follow-up: 若后续继续收紧 provider wrapper 或拆分 Claude TaskService，应补专门单测。

## Final Decision

本功能签收结论为 `accepted-with-risks`。

Stage 7 已完成 typed envelope 定义、session projection typed-first 解析、Claude/Codex/Codex Biz SPI 返回迁移、legacy fallback 回归和受影响 reactor 回归。遗留风险均为后续兼容收敛项，不阻断当前版本验收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-25
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage7-provider-listing-envelope-acceptance.md
- blocking_items: none
- follow_up_required: yes
