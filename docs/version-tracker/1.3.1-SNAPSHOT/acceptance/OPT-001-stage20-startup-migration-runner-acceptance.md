---
acceptance_scope: feature
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 20 Startup Migration Runner / Manifest
doc_role: acceptance-record
doc_purpose: 记录 Stage 20 startup migration runner / manifest 的功能级正式验收结论与证据摘要
status: signed-off
decision: accepted-with-risks
signed_off_by: Codex
signed_off_at: 2026-06-26
reviewed_by: N/A
blocking_items: []
follow_up_required: yes
evidence_count: 8
---

# Feature Acceptance

## Document Purpose

- doc_type: acceptance
- intended_for: signoff-owner / reviewer / owning-module
- purpose: 记录 Stage 20 对 Java startup migration runner / manifest 的签收结论、证据和剩余风险。

## Background

- Version: 1.3.1-SNAPSHOT
- Target: OPT-001 Stage 20 Startup Migration Runner / Manifest
- Owner: navigator-common / java-platform
- Goal: 在不引入 Flyway/Liquibase、不自动执行历史人工 SQL 的前提下，把既有 Java startup migrations 纳入统一 runner、manifest、开关、dry-run 和失败降级语义。

## Acceptance Basis

- [workitem] `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage20-startup-migration-runner.md`
- [quality] `docs/version-tracker/1.3.1-SNAPSHOT/quality/OPT-001-stage20-implementation-quality.md`
- [coverage] `docs/version-tracker/1.3.1-SNAPSHOT/coverage/OPT-001-stage20-coverage-audit.md`
- [test] targeted regression and affected reactor results recorded in Stage 20 workitem

## Checklist

- [x] scope 内功能点已全部交付：startup migration contract、runner、manifest、properties、prod config 和既有 migration 迁移均已完成。
- [x] 原始 acceptance criteria 已逐项覆盖：统一 runner、稳定 id/description、兼容默认行为、disabled/dry-run、失败继续和历史 SQL 非自动执行均有证据。
- [x] 关键测试已通过：targeted 17 tests pass，launcher affected reactor 1674 tests pass。
- [x] 体验验证已完成，或明确标记 `N/A`：该切片无 UI。
- [x] 文档、配置、依赖项已闭环：workitem、quality、coverage、acceptance、README 和 governance 回写完成或随本签收同步完成。

## Evidence

- Requirement:
  - `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage20-startup-migration-runner.md`
- Implementation:
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigration.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationDescriptor.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationProperties.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationRunner.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigration.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigration.java`
  - `launcher/src/main/resources/application-prod.yml`
- Test:
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationRunnerTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigrationTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigrationTest.java`
  - `mvn test -pl navigator-common -am "-Dtest=DatabaseStartupMigrationRunnerTest,DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：17 tests pass。
  - `mvn test -pl launcher -am`：251 reports / 1674 tests，0 failures，0 errors，0 skipped。
- Static:
  - `rg -n "ApplicationReadyEvent|@EventListener|implements DatabaseStartupMigration|DatabaseStartupMigrationRunner|startup-migrations" ...`：startup migration event listener 只剩统一 runner，两个既有 migration 均实现 `DatabaseStartupMigration`。
  - `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`：无匹配。
  - `git diff --check`：无 whitespace error，仅 CRLF normalization warnings。
- Experience:
  - N/A。纯 Java 后端 startup migration 编排重构，无 UI 行为变更。

## Failed Items

- none

## Risks / Open Items

- 本阶段没有实现 migration version table、execution record 或 rollback；新增复杂 migration 前仍需设计执行记录。
- 本阶段没有自动执行历史 `docs/migration/*.sql`；这些脚本仍需人工分类、幂等校验和运维流程。
- 本阶段未连接真实 MySQL 执行 smoke；当前通过 unit/mock 与 launcher affected reactor 覆盖。
- runner 仍采用 warn-not-fail 策略；生产部署侧需要监控启动日志并核对 schema 结果。
- 本阶段未运行根目录仓库级全量 `mvn test`；launcher affected reactor 已覆盖主应用聚合依赖链。

## Final Decision

Stage 20 验收结论为 `accepted-with-risks`。

本阶段达成了 Java startup migration 编排入口统一、manifest 可审计、默认行为兼容和运维开关可配置的目标，且没有扩大到高风险的历史 SQL 自动执行。剩余风险属于后续 migration execution record、real MySQL smoke 和 SQL runner 设计范围，不阻断本阶段签收。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md
- blocking_items: none
- follow_up_required: yes
