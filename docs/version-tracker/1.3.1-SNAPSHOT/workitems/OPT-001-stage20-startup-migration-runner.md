---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage20
severity: major
status: signed-off
owner: navigator-common/java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 20: Startup Migration Runner / Manifest

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 20 对生产 schema migration 风险的下一步收敛：把现有启动迁移纳入统一 runner、manifest、开关与 dry-run 语义。

## Background

Stage 19 已抽出 `DatabaseMigrationSupport`，并让 `CodingAgentTenantScopeMigration`、`GeminiFlashRuntimeBudgetMigration` 复用公共 MySQL / INFORMATION_SCHEMA helper。剩余风险是启动迁移仍分散由各自 `@EventListener(ApplicationReadyEvent.class)` 触发，缺少统一 manifest、执行顺序、禁用开关和 dry-run 语义。

历史 `docs/migration/*.sql` 仍包含一次性人工脚本，不适合在本阶段自动纳入启动执行。Stage 20 只治理已有 Java startup migrations 的执行编排，不扩大到 SQL 脚本 runner。

## Problem Statement

- 启动迁移触发点分散在具体 migration class，生产启动时无法从一个入口审计有哪些 migration 会运行。
- 现有迁移缺少统一排序规则；后续新增 migration 容易依赖 Spring event listener 的隐式顺序。
- 缺少统一 `enabled` / `dry-run` 开关，部署侧无法只打印 manifest 或在特殊维护窗口禁用 startup migrations。
- 异常降级策略目前在 migration class 内部重复，后续新增 migration 可能不一致。

## Target Outcome

- 新增 `DatabaseStartupMigration` 契约，现有 Java startup migration 显式声明 `id` 与 `description`。
- 新增 `DatabaseStartupMigrationRunner`，统一在 `ApplicationReadyEvent` 上按 manifest 顺序执行 migration。
- 新增 `DatabaseStartupMigrationProperties`，支持：
  - `navigator.database.startup-migrations.enabled=true|false`
  - `navigator.database.startup-migrations.dry-run=true|false`
- 默认行为保持兼容：默认 enabled、非 dry-run、MySQL 环境下继续应用现有两项启动迁移。
- runner 统一处理 MySQL-only guard、manifest 排序、异常 warn-not-fail 和 dry-run。

## Scope / Ownership

| Area | Owner | Touchpoints |
| --- | --- | --- |
| Migration contract | `navigator-common` | `common.migration.DatabaseStartupMigration` |
| Runner / properties | `navigator-common` | `DatabaseStartupMigrationRunner`、`DatabaseStartupMigrationProperties` |
| Existing startup migrations | `navigator-common` | `CodingAgentTenantScopeMigration`、`GeminiFlashRuntimeBudgetMigration` |
| Tests | `navigator-common` | runner tests + existing migration tests |
| Docs | `docs/version-tracker/1.3.1-SNAPSHOT` | Stage 20 workitem、README、OPT-001 governance、quality/coverage/acceptance |

## Implementation Plan

### Stage 20.1 - Contract and Runner

- [x] 新增 `DatabaseStartupMigration` interface，包含 `id()`、`description()`、`migrate()`。
- [x] 新增 runner manifest descriptor，支持按 id 排序输出。
- [x] 新增 `DatabaseStartupMigrationRunner`，统一监听 `ApplicationReadyEvent`。
- [x] runner 在 disabled、non-MySQL、dry-run、单个 migration 失败时均保持可预期行为。

### Stage 20.2 - Existing Migration Migration

- [x] `CodingAgentTenantScopeMigration` 移除自身 `ApplicationReadyEvent` listener，改为 `DatabaseStartupMigration` bean。
- [x] `GeminiFlashRuntimeBudgetMigration` 移除自身 `ApplicationReadyEvent` listener，改为 `DatabaseStartupMigration` bean。
- [x] 两个 migration 的表/列/index/DML 业务语义保持不变。

### Stage 20.3 - Tests and Check-in

- [x] 补 runner unit tests，覆盖 disabled、non-MySQL、dry-run、排序、失败继续。
- [x] 调整现有 migration behavior tests。
- [x] 运行 `navigator-common` targeted tests。
- [x] 运行 `launcher -am` affected reactor。
- [x] 回写 quality、coverage、acceptance 和 OPT-001 governance。

## Acceptance Criteria

| Criteria | Status | Evidence |
| --- | --- | --- |
| 启动迁移由统一 runner 触发 | done | `DatabaseStartupMigrationRunner` is the only `ApplicationReadyEvent` listener for startup migrations |
| migration manifest 有稳定 id / description | done | `DatabaseStartupMigration` implementations + `DatabaseStartupMigrationRunnerTest#manifestIsSortedByIdAndDescriptive` |
| 默认行为兼容现有 startup migrations | done | existing migration tests + `mvn test -pl launcher -am` |
| 支持 disabled 和 dry-run | done | `DatabaseStartupMigrationRunnerTest` disabled / dry-run cases |
| 单个 migration 失败不阻断后续 migration 和应用启动 | done | `DatabaseStartupMigrationRunnerTest#appliesMigrationsInIdOrderAndContinuesAfterFailure` |
| 历史人工 SQL 仍未被自动执行 | done | static scan confirms no main code path reads `docs/migration/*.sql` |

## Constraints / Non-Goals

- 不引入 Flyway/Liquibase。
- 不自动执行 `docs/migration/*.sql`。
- 不创建 migration version table 或 rollback 机制；这些保留给后续 Stage。
- 不改变生产 profile 的 `ddl-auto=validate` 策略。
- 不新增真实数据库连接要求；本阶段以 unit / affected reactor 覆盖 runner 行为。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 20 子计划落档 | done | 本文档记录范围、验收标准和非目标。 |
| Contract and runner | done | 新增 `DatabaseStartupMigration`、`DatabaseStartupMigrationDescriptor`、`DatabaseStartupMigrationProperties` 和 `DatabaseStartupMigrationRunner`。 |
| Existing migration migration | done | 两个既有 startup migration 已迁移为 `DatabaseStartupMigration` bean，自身不再监听 `ApplicationReadyEvent`。 |
| 主 OPT-001 回写 | done | Stage 20 summary、测试证据、质量门、覆盖审计和签收记录已回写。 |

### Testing Progress

| Scope | Command summary | Result |
| --- | --- | --- |
| navigator-common targeted tests | `mvn test -pl navigator-common -am "-Dtest=DatabaseStartupMigrationRunnerTest,DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` | PASS：17 tests，0 failures，0 errors，0 skipped |
| launcher affected reactor | `mvn test -pl launcher -am` | PASS：Surefire XML 合计 251 reports / 1674 tests，0 failures，0 errors，0 skipped |
| static scan / diff check | `rg -n "ApplicationReadyEvent|@EventListener|implements DatabaseStartupMigration|DatabaseStartupMigrationRunner|startup-migrations" ...`; `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`; `git diff --check` | PASS：startup migration event listener 只剩统一 runner；main code 未读取历史 SQL；diff check 无 whitespace error，仅 CRLF normalization warnings |

### Experience Progress

experience: N/A。该切片为 Java 后端 startup migration runner 编排；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

| Item | Status | Notes |
| --- | --- | --- |
| Completed work summary | done | 已新增 startup migration contract、manifest runner、enabled/dry-run 配置；既有两个启动迁移已统一由 runner 编排。 |
| Touched code paths listed | done | `navigator-common/src/main/java/com/foggy/navigator/common/migration/*StartupMigration*`、两个既有 migration、对应 tests、`launcher/src/main/resources/application-prod.yml`。 |
| Self-review completed | done | 检查确认默认 enabled/non-dry-run 行为兼容，非 MySQL / disabled / dry-run / 单项失败语义可预期，历史 SQL 仍未自动执行。 |
| Test status recorded | done | targeted 17 tests pass；launcher affected reactor 1674 tests pass；static scan 与 diff check 通过。 |
| Remaining risks recorded | done | 未做真实 MySQL smoke、未做 migration version table / rollback、未自动分类执行历史 SQL、warn-not-fail 策略仍需部署侧监控。 |
| Acceptance readiness | ready-with-risks | 质量门与覆盖审计均允许进入功能级验收。 |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage20-startup-migration-runner-acceptance.md
- blocking_items: none
- follow_up_required: yes
