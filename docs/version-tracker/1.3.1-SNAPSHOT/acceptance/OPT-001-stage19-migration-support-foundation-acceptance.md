---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 19 Migration Support Foundation
doc_role: acceptance-record
doc_purpose: 记录 Stage 19 migration support foundation 的功能级正式验收结论与证据摘要
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 7
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 Stage 19 对生产 schema migration 工具化 foundation 的签收结论、证据和剩余风险。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 19 Migration Support Foundation
- Owner: navigator-common / java-platform
- Goal: 在不引入 Flyway/Liquibase、不自动执行历史人工 SQL 的前提下，抽出公共 MySQL migration support，并重构既有启动迁移类，降低后续生产 schema migration 工具化的重复和风险。

## Acceptance Basis

- [workitem] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage19-migration-support-foundation.md`
- [quality] `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage19-implementation-quality.md`
- [coverage] `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage19-coverage-audit.md`
- [test] targeted regression and affected reactor results recorded in Stage 19 workitem

## Checklist

- [x] scope 内功能点已全部交付：公共 support 已新增，两个既有启动迁移已重构。
- [x] 原始 acceptance criteria 已逐项覆盖：support、重构、MySQL-only 语义、测试、历史 SQL 非自动执行均有证据。
- [x] 关键测试已通过：targeted 12 tests pass，launcher affected reactor 1669 tests pass。
- [x] 体验验证已完成，或明确标记 `N/A`：该切片无 UI。
- [x] 文档、配置、依赖项已闭环：workitem、quality、coverage、acceptance、README 和 governance 回写完成或随本签收同步完成。

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage19-migration-support-foundation.md`
- Implementation:
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseMigrationSupport.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigration.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigration.java`
- Test:
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/DatabaseMigrationSupportTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigrationTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigrationTest.java`
  - `mvn test -pl navigator-common -am "-Dtest=DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：12 tests pass。
  - `mvn test -pl launcher -am`：250 reports / 1669 tests，0 failures，0 errors，0 skipped。
- Static:
  - `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`：无匹配。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- Experience:
  - N/A。纯 Java 后端 migration support 重构，无 UI 行为变更。

## Failed Items

- none

## Risks / Open Items

- 本阶段没有实现完整 migration runner，也没有自动执行历史 `docs/migration/*.sql`；后续必须先做脚本分类、幂等校验、回滚策略、运维开关和真实 MySQL smoke。
- 既有启动迁移仍保持失败只 warn 的策略；后续如果 migration 成为生产 schema 前置条件，需要重新评估 fail-fast。
- 本阶段未运行根目录仓库级全量 `mvn test`；launcher affected reactor 已覆盖主应用聚合依赖链。

## Final Decision

Stage 19 验收结论为 `accepted-with-risks`。

本阶段达成了 migration support foundation 的目标，且没有扩大到高风险的历史数据搬迁自动执行。剩余风险均属于后续完整 migration runner / production migration process 设计范围，不阻断本阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md
- blocking_items: none
- follow_up_required: yes
