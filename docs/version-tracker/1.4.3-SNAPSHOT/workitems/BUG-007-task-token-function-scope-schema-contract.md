---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-007
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-20
open_questions: []
---

# Delivery Spec: Task capability function-scope schema contract

## Document Purpose

- intended_for: implementation / local-runtime-integration / independent-signoff
- purpose: 修复 task-scoped capability token 的实体映射、迁移和启动预检之间的物理字段契约冲突，使本机 trusted runtime 联调能在 fail-closed 前提下继续。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-007-task-token-function-scope-schema-contract.md`

## Goal

- version_goal: 让开发期 S1/S2 本机 runtime 路径在不放宽权限、不变更 Worker 路由的条件下，可靠持久化 task capability 的 function scope。
- target_outcome: `business_task_scoped_token.function_scope_json` 的 JPA 映射、既有 MySQL migration 和 startup preflight 一致为 `LONGTEXT NOT NULL`；修复后可用 runtime-only lane 完成受限 TMS safe ask 的本机验证。

## Scope

- in_scope:
  - 对齐 `BusinessTaskScopedTokenEntity` 的 `functionScopeJson` 物理 MySQL 映射与既有 versioned migration 的 `LONGTEXT NOT NULL` 契约。
  - 扩展 startup schema preflight，使 MySQL 的缺失、过小/错误类型或 nullable `function_scope_json` 在健康就绪前 fail-closed，且不自动执行 DDL。
  - 增加稳定回归测试；重放既有幂等 migration，使用 `ddl-auto=validate` 启动已确认归属的本机 launcher。
  - 在本机、trusted、runtime-only credential lane 内重试同一静态 safe ask；仅在正向成功后执行既有 fixture 的跨 ClientApp/tenant 负向验证。
- affected_modules:
  - `business-agent-module`
  - `launcher`（仅构建和本机运行验证，不改变运行配置语义）
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - 已确认归属的本机 Navigator MySQL 和 8112 launcher；已有 TMS runtime tuple/私有 profile 仅在进程内 runtime-only 白名单投影使用。

## Non-Goals

- out_of_scope:
  - 变更 tenant、owner、ClientApp、runtime/task capability、Worker Gateway 或授权判定语义。
  - 新建、替换或重绑 Worker、BizWorkerIdentity、WorkerPool、Directory、Agent、ClientApp、tenant 或上游资源。
  - 启用 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`、Gateway strict、Worker external、production 或真实上游业务访问。
  - 修改历史 1.4.2 `BUG-015` 的状态或证据。
- do_not_touch:
  - 现有 GOV-001 脏工作树改动、同级上游仓库、私有 profile 内容、凭据、账号、token 或 token 行数据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| `function_scope_json` 固定为 `LONGTEXT NOT NULL` | 既有 2026-07-14 migration 已声明此物理契约，不能由 Hibernate dev update 回退为 `tinytext` | 列名与现有数据不变；只重放现有幂等 migration，不新增自动 DDL |
| startup 预检检查 MySQL 类型和 non-null | 列名/索引完整仍可能在 token 持久化时失败，必须在健康前拒绝不兼容 schema | MySQL 要求 exact `LONGTEXT NOT NULL`；非 MySQL test dialect 使用等价 large-object metadata 验证 |
| runtime 验收使用 runtime-only 白名单投影 | 管理面 credential 不得参与 ask 或掩盖资源授权问题 | 不读取或回显 profile/token；静态 prompt 不访问业务、网络、文件或上游 |
| 正向成功后才做负向隔离 | 未创建 task 的正向失败不能证明 Worker/owner/isolation 语义 | 只使用已有 `CLIENT_APP`-owned fixture；没有 fixture 时记录 `NOT_RUN` |

## Acceptance Criteria

- [ ] AC-1: 实体映射、migration 和实际 MySQL metadata 均为 `function_scope_json LONGTEXT NOT NULL`，默认 `ddl-auto=update` 不再将其降为过小类型。
- [ ] AC-2: startup preflight 对 MySQL 的缺失、错误/过小类型和 nullable 字段均 fail-closed 并指向既有 migration；兼容 schema 通过，应用不执行自动 DDL。
- [ ] AC-3: 相关 focused test、`business-agent-module` 回归和 launcher 构建实际通过；重放既有 migration 后，`ddl-auto=validate` 的已确认本机 launcher 健康为 `UP`。
- [ ] AC-4: TMS runtime-only 正向 safe ask 在 readiness 和 owner-smoke 后返回 taskId、取得预期终态/静态标记，并保留既有 `LANGGRAPH_BIZ / BIZ_WORKER_IDENTITY` 路由语义。
- [ ] AC-5: 若已有合格 fixture，同 tenant 跨 ClientApp 与跨 tenant runtime 请求均稳定拒绝，且无 task 创建/Worker dispatch；若没有合格 fixture，记录 `NOT_RUN` 而不创建资源。
- [ ] AC-6: 未启用 Gateway/external/production，未访问真实业务数据，未泄露凭据，未变更 Worker、BizWorkerIdentity、Pool、Directory 或 sibling workspace。

## Contract / Data / Security Constraints

- API or event contract: 不新增 endpoint、请求字段或 credential lane；Open API 路由 gate、Gateway strict 和 task capability 的授权语义不变。
- data and migration: 使用 `docs/migration/2026-07-14-business-task-token-v2.sql` 的既有幂等 DDL；不得读取、导出或记录 token 行值。应用 startup 不执行 DDL。
- compatibility and rollback: 这是既有 migration 的映射修复；若回滚应用，表仍保持较宽 `LONGTEXT`，不删除列、索引或数据。
- permissions and secrets: 仅 local/trusted 环境；ask 进程只注入 runtime 白名单字段，admin/control lane 必须缺席。`NAVIGATOR_EXTERNAL_ENABLED` 只作为 Open API 路由 gate，绝不视为 Gateway、Provider 或 production ready。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| 映射与预检 | critical | focused schema-preflight regression tests | Maven command/result；错误/兼容 metadata 断言 |
| 模块回归 | major | `mvn test -pl business-agent-module -am` | Surefire result |
| packaged runtime | critical | `mvn package -pl launcher -am -DskipTests`；migration 后以 `ddl-auto=validate` 启动 | build result、metadata-only type/nullability、health |
| runtime 正向 | critical | runtime-token → readiness → owner-smoke → 静态 safe ask | 脱敏 task/context 指纹、terminal status、route kind；无正文/secret |
| isolation | critical | 已有 fixture 的同 tenant cross-ClientApp 与 cross-tenant deny | deny reason 类别、taskCreated/dispatch=false；或 `NOT_RUN` 理由 |
| hygiene | major | `git diff --check`、changed-surface secret scan | command result；无 secret finding |

## Bug Context

- bug_source: acceptance-found
- severity: major
- environment: local trusted Navigator instance, MySQL `coding_agent` schema, 8112 launcher, 2026-07-20 internal TMS runtime rehearsal.
- current_behavior: migration produces `LONGTEXT NOT NULL`, but `@Lob String` is interpreted as MySQL CLOB/tinytext. `ddl-auto=update` reverts the column, while `ddl-auto=validate` rejects the migration-compatible `LONGTEXT`; the old preflight checked only names/indexes and therefore could report a schema ready before token persistence failed.
- expected_behavior: mapping and migration agree, incompatible physical schema fails before readiness, and compatible schema starts with `validate` before any safe ask.
- reproduction_steps: use the current migration-compatible MySQL table; start with `ddl-auto=validate` to observe the mapping mismatch, or start with development `update` and submit the existing static runtime-only ask to observe token persistence failure after downgrade.
- reproduction_status: confirmed
- existing_evidence: `GOV-001-dev-internal-runtime-rehearsal-2026-07-20.md`; metadata-only column check; historical `BUG-015` / GitHub #152.
- existing_tests: `BusinessTaskScopedTokenSchemaPreflightTest` covers missing columns/indexes but not type/capacity/nullability.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - H2 metadata cannot itself prove MySQL `LONGTEXT`; local MySQL migration + validate startup is mandatory evidence.
  - A positive runtime task may reveal a distinct Worker/runtime blocker. That outcome must remain separate from this schema repair and must not cause permission or Worker-route expansion.
  - Existing negative fixtures may not exist; absence is an evidence limitation, not authority to create resources.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `navigator-runtime-provisioning` / `mysql-docker-client` SKILL。
- 在本契约范围内自主选择最小实现；若需变更授权、Worker route、credential delivery、Gateway/external/production 边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 对稳定 BUG 先形成可失败的自动化回归，再修复并运行通过。
- 记录精确命令、结果、迁移/启动状态及脱敏 runtime 证据；不得写入或回显 secret。
- 完成后填写 `Implementation Result`，状态改为 `READY_FOR_SIGNOFF`；不得自行写 `ACCEPTED`。

## Implementation Result

- implementation_summary: `function_scope_json` 的 JPA mapping 已改为 MySQL `LONGTEXT NOT NULL`；startup preflight 现在对 MySQL/MariaDB 精确要求 `LONGTEXT NOT NULL`，并在 H2 仅允许 CLOB 或 capacity-gated large VARCHAR。短 VARCHAR、nullable、缺列和缺索引全部 fail-closed。该修复不改变 credential、owner、Worker 或 Gateway 语义。
- changed_paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessTaskScopedTokenEntity.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenSchemaPreflight.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessTaskScopedTokenSchemaPreflightTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/test-records/BUG-007-runtime-retry-credential-safety-gate-2026-07-20.md`
- tests_and_results:
  - PASS: `mvn -q -pl business-agent-module -am -Dtest=BusinessTaskScopedTokenSchemaPreflightTest -Dsurefire.failIfNoSpecifiedTests=false test`（8 tests）。
  - PASS: `mvn -q -pl launcher -am -Dtest=CommonRepositoryOwnershipContextTest -Dsurefire.failIfNoSpecifiedTests=false test`。
  - PASS: `mvn -q -pl business-agent-module -am test`；首次被 `agent-framework` 的时间敏感 `LlmCircuitBreakerTest` 偶发失败中断，单独复跑与随后完整 module run 均通过。
  - PASS: `mvn -q -pl launcher -am -DskipTests package`。
- manual_or_experience_evidence:
  - PASS: 已确认归属的本机 MySQL launcher 以 `ddl-auto=validate`、startup migration disabled、Open API route gate enabled、Gateway external disabled 启动；`/actuator/health=UP`，external surface 为 `surfaceReady=true, productionReady=false`。该 launcher 随后按 PID/cwd/JAR 归属安全停止。
  - BLOCKED: 见 [BUG-007 runtime retry credential safety gate](../test-records/BUG-007-runtime-retry-credential-safety-gate-2026-07-20.md)。候选 TMS private profile 的 `0744` mode 在 secret 内容读取前被拒绝，因此未执行 runtime-token、readiness、owner-smoke、ask 或 Worker dispatch。
- deviations:
  - 未重放 `docs/migration/2026-07-14-business-task-token-v2.sql`：现有 schema 已被实际 `ddl-auto=validate` 启动验证为兼容；该幂等脚本仍包含 token 行 UPDATE、ALTER TABLE 和临时存储过程，重放会引入不必要的本地数据/DDL 写入风险。本项不是“已执行 migration”的证据。
- residual_risks:
  - 正向 runtime-only safe ask 与既有 fixture 的跨 ClientApp/tenant deny 尚未执行；必须先由 TMS owner 提供合规 `0600` runtime-only profile 和当前 explicit tuple。
  - H2 regression 不能替代 MySQL physical metadata；当前 MySQL validate startup 是 schema 兼容证据，但没有以 root socket 查询绕过凭据边界。
- readiness: ULTRA_EXECUTING

## References

- issue: GitHub #152
- related historical work item: `../../1.4.2-SNAPSHOT/workitems/BUG-015-task-token-v2-schema-preflight.md`
- related active MVP: `GOV-001-dev-s1-s2-integration-mvp.md`
- runtime record: `../test-records/GOV-001-dev-internal-runtime-rehearsal-2026-07-20.md`
- migration: `../../../migration/2026-07-14-business-task-token-v2.sql`
