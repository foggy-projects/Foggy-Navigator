---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 20 Startup Migration Runner / Manifest
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Implementation Quality Gate

## Background

- 检查对象：Stage 20 startup migration runner / manifest。
- 当前阶段：Stage 19 已抽出 `DatabaseMigrationSupport`，本阶段继续把既有 Java startup migrations 统一纳入 manifest runner。
- 本次目标：不引入 Flyway/Liquibase，不自动执行历史人工 SQL，先统一 Java startup migration 的入口、排序、开关、dry-run 和失败降级语义。

## Check Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage20-startup-migration-runner.md`
- implementation plan: Stage 20 workitem `Implementation Plan`
- progress: Stage 20 workitem `Progress Tracking`
- execution check-in: Stage 20 workitem `Execution Check-in`
- test result summary:
  - `mvn test -pl navigator-common -am "-Dtest=DatabaseStartupMigrationRunnerTest,DatabaseMigrationSupportTest,CodingAgentTenantScopeMigrationTest,GeminiFlashRuntimeBudgetMigrationTest,CommonAutoConfigurationTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`：17 tests pass。
  - `mvn test -pl launcher -am`：251 reports / 1674 tests，0 failures，0 errors，0 skipped。

## Changed Surface

- changed files:
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigration.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationDescriptor.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationProperties.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationRunner.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigration.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigration.java`
  - `launcher/src/main/resources/application-prod.yml`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/DatabaseStartupMigrationRunnerTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/CodingAgentTenantScopeMigrationTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/migration/GeminiFlashRuntimeBudgetMigrationTest.java`
- changed modules: `navigator-common`, `launcher` config
- declared completed scope: startup migration contract、manifest runner、prod profile properties、existing startup migrations migration、runner behavior tests 和 affected launcher reactor。

## Quality Checklist

- scope conformance: pass。本阶段只治理既有 Java startup migrations，没有自动执行 `docs/migration/*.sql`，没有引入外部 migration framework。
- code hygiene: pass。未发现 debug 代码、临时分支或未闭环 TODO。
- duplication and consolidation: pass。启动事件监听、MySQL-only guard、manifest 排序、dry-run 和异常 warn-not-fail 语义从具体 migration class 收敛到 runner。
- complexity and abstraction: pass。新增契约保持小接口，runner 只负责编排，不接管具体 DDL/DML 业务逻辑。
- error handling and edge cases: pass-with-risk。disabled、non-MySQL、dry-run、单项失败继续均有测试；失败仍为 warn-not-fail，需要部署侧检查日志和 schema 结果。
- readability and maintainability: pass。migration class 现在表达自身 id、description 和具体迁移动作，启动编排从业务迁移中移除。
- critical logic documentation: pass。workitem 明确历史 SQL 不自动纳入启动执行，runner 是 Java startup migration 编排层而不是 SQL runner。
- contract and compatibility: pass。默认 enabled、非 dry-run，MySQL 环境下继续执行既有两项 startup migrations；生产 profile 增加环境变量可控开关。
- documentation and writeback: pass。Stage 20 workitem、quality、coverage、acceptance、README 和 governance 已统一回写。
- test alignment: pass。runner unit tests 覆盖新增编排行为，既有 migration tests 覆盖业务语义，launcher affected reactor 覆盖主应用依赖链。
- release readiness: ready-with-risks。可进入覆盖审计，剩余风险不阻断本阶段验收。

## Findings

- no blocking findings。
- non-blocking finding: 当前 runner 没有 migration version table，无法记录某个 migration 是否已应用，只能依赖 migration 自身幂等性。本阶段只迁移既有幂等 startup migrations，后续如果新增更复杂 schema/data migration，应先设计版本表或执行记录。

## Risks / Follow-ups

- risk 1: 未运行真实 MySQL smoke；INFORMATION_SCHEMA 与 DDL/DML 执行仍通过 unit/mock 与 launcher affected reactor 间接覆盖。
- risk 2: 历史 `docs/migration/*.sql` 仍为人工脚本；后续要自动化必须先分类、幂等化、设计 rollback / version table。
- risk 3: runner 继续采用单 migration 失败只 warn 的策略；生产部署必须监控日志并核对 schema 结果。
- risk 4: 未运行根目录仓库级全量 `mvn test`；已运行 launcher affected reactor，覆盖主应用聚合依赖链。

## Recommended Next Skills

- `foggy-test-coverage-audit`: required，检查 Stage 20 acceptance item 与测试证据映射。
- `foggy-bug-regression-workflow`: not required，未发现回归缺陷。
- `plan-evaluator`: optional，后续设计 migration version table / SQL runner 前可复核方案。
- back to implementation: not required for Stage 20；后续另起 migration execution record / real MySQL smoke 设计项。

## Decision

- decision: ready-with-risks
- can_enter_coverage_audit: yes
- follow_up_required: yes
