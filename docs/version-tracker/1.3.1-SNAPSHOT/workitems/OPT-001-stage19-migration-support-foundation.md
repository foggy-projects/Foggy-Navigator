---
type: implementation-plan
version: 1.3.1-SNAPSHOT
ticket: OPT-001-stage19
severity: major
status: signed-off
owner: navigator-common/java-platform
created_at: 2026-06-26
---

# OPT-001 Stage 19: Migration Support Foundation

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 Stage 19 对生产 schema migration 工具化的第一步收敛：抽出公共 MySQL migration support，并重构既有启动迁移类。

## Background

Stage 5 已将生产 profile 的 `spring.jpa.hibernate.ddl-auto` 收敛为 `validate`，并明确生产 schema 需要通过 migration 或人工流程预先准备。后续 Stage 18 收口后，剩余高优风险重新回到生产 schema migration 工具化。

当前项目已有两类迁移资产：

- `navigator-common/src/main/java/com/foggy/navigator/common/migration/*Migration.java`：启动时执行的 MySQL-only 小步迁移。
- `docs/migration/*.sql`：历史人工 SQL，其中包含表重命名、会话数据搬迁、特定 dev worker 修正等一次性脚本。

Review 判断：历史 SQL 里包含需要停机、备份或人工确认的数据搬迁，不能在本阶段直接接入启动自动执行。Stage 19 先把既有启动迁移的公共能力抽出，形成可测试、可复用的 migration foundation。

## Problem Statement

- `CodingAgentTenantScopeMigration` 与 `GeminiFlashRuntimeBudgetMigration` 重复实现 MySQL 检测、DataSource 读取、表存在检查和 INFORMATION_SCHEMA 查询。
- 迁移 SQL identifier escaping 分散在具体迁移类里，后续新增迁移容易复制出不一致逻辑。
- 当前缺少针对公共 migration helper 的单元测试，迁移类容易在后续演进中继续扩大手写 JDBC 片段。

## Target Outcome

- 新增公共 `DatabaseMigrationSupport`，集中处理 MySQL 检测、表/列/index 查询、单列唯一索引发现和 identifier escaping。
- 既有启动迁移类改为依赖公共 support，保留现有业务语义、启动时机和 MySQL-only 边界。
- 为 support 补单元测试，覆盖 MySQL 检测、表/列/index 查询和 identifier escaping。
- 明确本阶段不自动执行 `docs/migration/*.sql`，后续如做 runner 必须先完成脚本分类、幂等校验、回滚策略和运维开关设计。

## Scope / Ownership

| Area | Owner | Touchpoints |
| --- | --- | --- |
| Migration support | `navigator-common` | `common.migration.DatabaseMigrationSupport` |
| Existing startup migrations | `navigator-common` | `CodingAgentTenantScopeMigration`、`GeminiFlashRuntimeBudgetMigration` |
| Tests | `navigator-common` | `DatabaseMigrationSupportTest`、`CodingAgentTenantScopeMigrationTest`、`GeminiFlashRuntimeBudgetMigrationTest` |
| Docs | `docs/version-tracker/1.3.1-SNAPSHOT` | Stage 19 workitem、OPT-001 governance、quality/coverage/acceptance |

## Implementation Plan

### Stage 19.1 - Support Extraction

- [x] 新增 `DatabaseMigrationSupport` component。
- [x] 支持 `isMySql()`，DataSource 缺失或非 MySQL 时返回 false。
- [x] 支持 `tableExists(table)`、`findColumn(table, names...)`、`columnExists(table, column)`。
- [x] 支持 `indexExists(table, index)`、`singleColumnUniqueIndexes(table, column)`。
- [x] 支持 `escapeIdentifier(identifier)` / `quoteIdentifier(identifier)`。

### Stage 19.2 - Existing Migration Refactor

- [x] `CodingAgentTenantScopeMigration` 改用 support 处理 MySQL、表、列、索引检查和 identifier escaping。
- [x] `GeminiFlashRuntimeBudgetMigration` 改用 support 处理 MySQL 与表存在检查。
- [x] 保持 `@EventListener(ApplicationReadyEvent.class)` 启动时机不变。
- [x] 保持异常降级策略不变：迁移失败只 warn，不阻断启动。

### Stage 19.3 - Tests and Check-in

- [x] 新增 `DatabaseMigrationSupportTest`。
- [x] 新增 `CodingAgentTenantScopeMigrationTest` 与 `GeminiFlashRuntimeBudgetMigrationTest`，覆盖既有迁移类行为。
- [x] 运行 `navigator-common` targeted tests。
- [x] 运行 launcher affected reactor。
- [x] 回写 quality、coverage、acceptance 和 OPT-001 governance。

## Acceptance Criteria

| Criteria | Status | Evidence |
| --- | --- | --- |
| 公共 migration support 已抽出 | done | `DatabaseMigrationSupport` |
| 既有启动迁移复用 support | done | `CodingAgentTenantScopeMigration`、`GeminiFlashRuntimeBudgetMigration` |
| 原有 MySQL-only 与异常降级语义保持不变 | done | code review + `DatabaseMigrationSupportTest` / migration tests |
| support helper 有自动化测试 | done | `DatabaseMigrationSupportTest` 7 tests |
| 历史人工 SQL 未被自动执行 | done | static scan confirms no main code path reads `docs/migration/*.sql` |

## Constraints / Non-Goals

- 不引入 Flyway/Liquibase。
- 不自动执行 `docs/migration/*.sql`。
- 不改变生产 profile 的 `ddl-auto=validate` 策略。
- 不新增真实数据库连接要求；测试以 mock / unit coverage 为主。
- 不改变任何业务表结构或数据迁移内容。

## Progress Tracking

### Development Progress

| Item | Status | Notes |
| --- | --- | --- |
| Stage 19 子计划落档 | done | 本文档记录范围、验收标准和非目标。 |
| Migration support extraction | done | 新增 `DatabaseMigrationSupport`，集中 MySQL 检测、INFORMATION_SCHEMA 查询、单列唯一索引发现和 identifier escaping。 |
| Existing migration refactor | done | `CodingAgentTenantScopeMigration` 与 `GeminiFlashRuntimeBudgetMigration` 已改为依赖 support，迁移 SQL 业务语义不变。 |
| 主 OPT-001 回写 | done | Stage 19 summary、测试证据、质量门、覆盖审计和签收记录已回写。 |

### Testing Progress

| Scope | Command summary | Result |
| --- | --- | --- |
| navigator-common targeted tests | `mvn test -pl navigator-common -am "-Dtest=DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` | PASS：12 tests，0 failures，0 errors，0 skipped |
| launcher affected reactor | `mvn test -pl launcher -am` | PASS：Surefire XML 合计 250 reports / 1669 tests，0 failures，0 errors，0 skipped |
| static scan / diff check | `rg -n "docs/migration|migration/.*\.sql|ClassPathResource|ResourceDatabasePopulator|ScriptUtils" navigator-common/src/main/java launcher/src/main/java -S`; `git diff --check` | PASS：main code 未读取历史 SQL；diff check 无 whitespace error，仅 CRLF normalization warnings |

### Experience Progress

experience: N/A。该切片为 Java 后端 migration support 与启动迁移重构；未新增或修改 UI 页面、表单、按钮、权限可见性或前端交互。

## Execution Check-in

| Item | Status | Notes |
| --- | --- | --- |
| Completed work summary | done | 已抽出 `DatabaseMigrationSupport`；既有两个启动迁移类已改为复用 support；补充 support 与 migration behavior 单测；未自动执行历史人工 SQL。 |
| Touched code paths listed | done | `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseMigrationSupport.java`、`CodingAgentTenantScopeMigration.java`、`GeminiFlashRuntimeBudgetMigration.java`、对应 migration tests。 |
| Self-review completed | done | 检查确认启动时机、MySQL-only 边界、warn-not-fail 策略、生产 `ddl-auto=validate` 策略和历史 SQL 非自动执行均保持。 |
| Test status recorded | done | targeted 12 tests pass；launcher affected reactor 1669 tests pass；static scan 与 diff check 通过。 |
| Remaining risks recorded | done | 未做完整 migration runner、未跑真实 MySQL integration test、未运行根目录仓库级全量 `mvn test`。 |
| Acceptance readiness | ready-with-risks | 质量门与覆盖审计均允许进入功能级验收。 |

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-26
- acceptance_record: docs/version-tracker/1.3.1-SNAPSHOT/acceptance/OPT-001-stage19-migration-support-foundation-acceptance.md
- blocking_items: none
- follow_up_required: yes
