---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 20 Startup Migration Runner / Manifest
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：Stage 20 startup migration runner / manifest。
- 当前阶段：实现质量门结论为 `ready-with-risks`。
- 审计目标：确认统一 runner、manifest、enabled/dry-run、失败继续、既有 migration 迁移和历史 SQL 非自动执行是否有足够证据进入功能级验收。

## Audit Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage20-startup-migration-runner.md`
- implementation plan: Stage 20 workitem `Implementation Plan`
- progress: Stage 20 workitem `Progress Tracking`
- acceptance basis: Stage 20 workitem `Acceptance Criteria`
- test records:
  - targeted regression command in Stage 20 workitem `Testing Progress`
  - affected reactor command in Stage 20 workitem `Testing Progress`
  - static scan in Stage 20 workitem `Testing Progress`
- manual evidence: code review and static scan evidence in Stage 20 workitem

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1 启动迁移由统一 runner 触发 | critical | yes | yes | no | no | yes | `DatabaseStartupMigrationRunnerTest`; static scan for `ApplicationReadyEvent` | covered |
| AC-2 migration manifest 有稳定 id / description | major | yes | no | no | no | yes | `DatabaseStartupMigrationRunnerTest#manifestIsSortedByIdAndDescriptive`; migration implementations | covered |
| AC-3 默认行为兼容现有 startup migrations | critical | yes | yes | no | no | yes | existing migration tests; `mvn test -pl launcher -am` 1674 tests pass | covered |
| AC-4 支持 disabled 和 dry-run | major | yes | no | no | no | yes | `DatabaseStartupMigrationRunnerTest` disabled / dry-run cases | covered |
| AC-5 单个 migration 失败不阻断后续 migration 和应用启动 | major | yes | no | no | no | yes | `DatabaseStartupMigrationRunnerTest#appliesMigrationsInIdOrderAndContinuesAfterFailure` | covered |
| AC-6 历史人工 SQL 未被自动执行 | critical | no | no | no | no | yes | static scan: no `docs/migration`, `ClassPathResource`, `ResourceDatabasePopulator`, `ScriptUtils` path in main startup code | covered |

## Evidence Summary

- 已有自动化测试：
  - `DatabaseStartupMigrationRunnerTest` 覆盖 disabled、non-MySQL、dry-run、id 排序、失败继续和 manifest descriptor。
  - `CodingAgentTenantScopeMigrationTest` 覆盖表缺失 skip、`agent_profile` 补列、legacy single-column unique index drop、tenant+agent composite index create。
  - `GeminiFlashRuntimeBudgetMigrationTest` 覆盖表缺失 skip 和 runtime budget update。
  - `DatabaseMigrationSupportTest` 保留 Stage 19 helper 行为回归。
- 已有 affected regression：
  - `mvn test -pl launcher -am` 通过，251 reports / 1674 tests，0 failures，0 errors，0 skipped。
- 已有静态证据：
  - startup migration event listener 只剩 `DatabaseStartupMigrationRunner`。
  - main code 未读取或自动执行 `docs/migration/*.sql`。

## Gaps

- gap 1: 未运行真实 MySQL smoke；runner 编排已覆盖，但实际 MySQL DDL/DML 执行仍缺少环境级验证。
- gap 2: 未建立 migration version table / execution record；测试依赖 migration 自身幂等语义。
- gap 3: 未运行根目录仓库级全量 `mvn test`；launcher affected reactor 已覆盖主应用聚合依赖链，但不等同于所有仓库模块。
- gap 4: 历史人工 SQL runner 仍为非目标；未来若自动化 SQL，需要单独测试用例和运维验证。

## Recommended Next Skills

- `integration-test`: optional。后续应为 startup migrations 增加真实 MySQL smoke / integration test。
- `playwright-cli`: not applicable。该切片无 UI。
- `foggy-bug-regression-workflow`: not required。未发现 BUG 修复场景。
- `foggy-acceptance-signoff`: required，可进入功能级验收。
- `plan-evaluator`: optional。后续 version table / SQL runner 方案可先复核。

## Conclusion

- conclusion: ready-with-gaps
- can_enter_acceptance: yes
- follow_up_required: yes
