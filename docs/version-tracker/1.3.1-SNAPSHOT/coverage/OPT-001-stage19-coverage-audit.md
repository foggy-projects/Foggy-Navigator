---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.3.1-SNAPSHOT
target: OPT-001 Stage 19 Migration Support Foundation
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-06-26
follow_up_required: yes
---

# Test Coverage Audit

## Background

- 审计对象：Stage 19 migration support foundation。
- 当前阶段：实现质量门结论为 `ready-with-risks`。
- 审计目标：确认公共 migration support、既有迁移类重构、非自动执行历史 SQL 的验收项是否有足够证据进入功能级验收。

## Audit Basis

- requirement: `docs/version-tracker/1.3.1-SNAPSHOT/workitems/OPT-001-stage19-migration-support-foundation.md`
- implementation plan: Stage 19 workitem `Implementation Plan`
- progress: Stage 19 workitem `Progress Tracking`
- acceptance basis: Stage 19 workitem `Acceptance Criteria`
- test records:
  - targeted regression command in Stage 19 workitem `Testing Progress`
  - affected reactor command in Stage 19 workitem `Testing Progress`
  - static scan in Stage 19 workitem `Testing Progress`
- manual evidence: code review and static scan evidence in Stage 19 workitem

## Coverage Matrix

| Item | Risk | Unit | Integration | E2E | Playwright | Manual | Evidence Path | Coverage |
|------|------|------|-------------|-----|------------|--------|---------------|----------|
| AC-1 公共 `DatabaseMigrationSupport` 已抽出 | major | yes | no | no | no | yes | `DatabaseMigrationSupportTest`; code review | covered |
| AC-2 既有启动迁移复用 support | major | yes | no | no | no | yes | `CodingAgentTenantScopeMigrationTest`; `GeminiFlashRuntimeBudgetMigrationTest`; static scan | covered |
| AC-3 原有 MySQL-only 与异常降级语义保持不变 | critical | yes | no | no | no | yes | `DatabaseMigrationSupportTest#isMySqlReturnsFalseWhenDataSourceMissing`; migration skip tests; code review | covered |
| AC-4 support helper 有自动化测试 | major | yes | no | no | no | yes | `DatabaseMigrationSupportTest` 7 tests | covered |
| AC-5 历史人工 SQL 未被自动执行 | critical | no | no | no | no | yes | static scan: no `docs/migration`, `ClassPathResource`, `ResourceDatabasePopulator`, `ScriptUtils` path in main startup code | covered |
| AC-6 主应用依赖链不因 common migration 注入变更而断裂 | critical | yes | yes | no | no | no | `mvn test -pl launcher -am` 250 reports / 1669 tests pass | covered |

## Evidence Summary

- 已有自动化测试：
  - `DatabaseMigrationSupportTest` 覆盖 DataSource 缺失、MySQL 产品名检测、table/column/index 查询和 identifier escaping。
  - `CodingAgentTenantScopeMigrationTest` 覆盖非 MySQL skip，以及 agent_profile column、legacy single-column index drop、tenant+agent composite index creation SQL。
  - `GeminiFlashRuntimeBudgetMigrationTest` 覆盖表缺失 skip 和 runtime budget update。
- 已有 affected regression：
  - `mvn test -pl launcher -am` 通过，250 reports / 1669 tests，0 failures，0 errors，0 skipped。
- 已有静态证据：
  - main code 未读取或自动执行 `docs/migration/*.sql`。
  - migration support 调用集中在 `navigator-common/src/main/java/com/foggy/navigator/common/migration`。

## Gaps

- gap 1: 未运行真实 MySQL integration test；当前 INFORMATION_SCHEMA 行为通过 mock/unit 与 launcher affected reactor 间接覆盖。
- gap 2: 未运行根目录仓库级全量 `mvn test`；launcher affected reactor 已覆盖主应用聚合依赖链，但不等同于所有仓库模块。
- gap 3: 未形成完整 migration runner 的端到端验收；这是本阶段明确非目标。

## Recommended Next Skills

- `integration-test`: optional。后续 migration runner 或生产迁移执行前，建议补真实 MySQL smoke / integration test。
- `playwright-cli`: not applicable。该切片无 UI。
- `foggy-bug-regression-workflow`: not required。未发现 BUG 修复场景。
- `foggy-acceptance-signoff`: required，可进入功能级验收。
- `plan-evaluator`: optional。后续完整 migration runner 设计前可复核方案。

## Conclusion

- conclusion: ready-with-gaps
- can_enter_acceptance: yes
- follow_up_required: yes
