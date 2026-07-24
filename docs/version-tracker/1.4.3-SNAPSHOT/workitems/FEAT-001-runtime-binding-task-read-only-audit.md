---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: FEAT-001
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner
approved_at: 2026-07-24
open_questions: []
---

# Delivery Spec: Runtime Binding / Existing Task Read-Only Audit

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 SIM 以 ClientApp runtime credential 对 frozen binding 与既有任务 durable 终态执行零 token、零 dispatch、零资源变更审计的交付契约。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/FEAT-001-runtime-binding-task-read-only-audit.md`

## Goal

- version_goal: 在 1.4.3 ClientApp/runtime lane 上补齐 fail-closed、tenant-scoped 的权威只读复核能力。
- target_outcome: `navi upstream runtime binding-audit` 与 `task-audit` 使用 runtime key/secret 直接认证，不签发 access/runtime/task token，并只读取 Navigator durable binding、task、attempt/token/registration state。
- critical_outcomes:
  - binding 输出来自 server durable registration/binding，不回显 profile 或请求参数作为事实。
  - existing task 输出区分 `REVOKED`、token 不存在与不可确认，不创建或修复任何记录。
  - 两类审计显式返回全部副作用断言且均为 `false`。
  - 严格校验 ClientApp、tenant、upstream user 与 task ownership，不允许跨 scope 查询。
- success_is_sufficient_when: focused/affected 自动化测试、CLI package、server health/provenance、live frozen binding 与 `20260723-d9ab` 只读查询、写入计数/副作用证据和敏感值扫描均有可复核结果。

## Scope

- in_scope:
  - ClientApp runtime key/secret 的 no-token read-only request authentication。
  - binding audit API、DTO、SDK、CLI JSON/人类可读输出。
  - existing task audit API、DTO、provider durable-state contribution、SDK、CLI JSON/人类可读输出。
  - route authorization catalog、测试、CLI/server provenance、版本文档与 live delivery。
- affected_modules:
  - `business-agent-module`
  - `addons/claude-worker-agent`
  - `addons/codex-worker-agent`
  - `navigator-open-sdk`
  - `navigator-common`
  - `tools/navigator-upstream-cli`
  - `launcher` packaging/runtime only
- external_dependencies: 本机 Navigator MySQL durable state；现有 3131/3151 Worker registration 只作为已持久化 registration/config 的审计对象，不调用 Worker。

## Non-Goals

- out_of_scope:
  - task 创建、普通 ask、safe-ask、retry、resume、recovery、redispatch 或 synthetic smoke。
  - provisioning、reconcile、cleanup、repair、terminal correction、token issuance/refresh/recovery。
  - TMS、账号、浏览器、业务数据、ActorHome、prompt/response/task body/workspace path 访问。
  - external/Gateway/production enablement 或 typed-management authority 扩张。
- do_not_touch:
  - SIM provisioning resources、WorkerHost/Physical Worker/Agent/model/directory binding。
  - system-admin、control、platform、management credential lane。
  - 当前工作树中与本事项无关的用户改动。
- non_blocking_or_waivable_items: none；零 token、零 dispatch、ownership 与敏感信息边界不可豁免。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| runtime key/secret 直接只读认证 | 现有 resolver 可验证 owner 且不持久化 access token | 请求拒绝混用 admin/control/platform/access/task/worker credential |
| server 组合 durable state | binding/task 真相分布在 registration、binding、provider task、business task/token/terminal tables | 不以 CLI 参数/profile 或单表缺行推断 |
| token status 为显式多态 | token row 缺失不等于 revoked | 支持 `REVOKED`、`NOT_FOUND`、`UNCONFIRMED`、`ACTIVE`、`EXPIRED` |
| provider task audit 使用只读 contributor | 避免 business module 反向依赖 Worker addon | contributor 不得调用 Worker 或执行 reconciliation |
| 所有副作用字段固定由 server 返回 | 让验收方无需从沉默推断安全性 | 本能力的成功响应中全部必须为 `false` |
| task 不存在与无权访问 fail closed | 防止跨租户 existence oracle | 不回退到其他 task/ask/diagnostic 路径 |

## Acceptance Criteria

- [x] AC-1 binding audit 返回 required fields，值来自当前 durable binding/registration，并验证请求期望值与 observed state 一致。
- [x] AC-2 task audit 返回 required fields 与完整脱敏 terminal stages，终态、token、active registration、dispatch/retry/recovery 均来自 durable state。
- [x] AC-3 token revoked、token row 不存在、状态不可确认严格区分。
- [x] AC-4 两个 audit 不产生数据库写入，不签发任何 token，不创建 task/context/session，不 dispatch Worker/model/BusinessFunction，不触发 retry/resume/recovery/reconcile。
- [x] AC-5 ClientApp/tenant/upstream-user/task ownership fail closed；跨 scope 与不存在 task 返回稳定脱敏错误且无 fallback/retry。
- [x] AC-6 JSON 与人类可读输出不包含 secret、token、header/profile、prompt、response、task body、workspace path、业务数据或敏感堆栈。
- [x] AC-7 CLI package 与必要 server build 发布完成，并提供完整 provenance、hash、health/listener 与 migration/ddl-auto 状态。
- [x] AC-8 live 查询只读取 frozen binding 和 `20260723-d9ab`，不创建任何 smoke/test task，不修改 provisioning resource；若实际 durable state 与预期不符，原样返回并标记 blocker。

## Contract / Data / Security Constraints

- API or event contract: 新增 runtime-scoped GET audit endpoints；DTO 字段按用户要求固定；所有错误为稳定 sanitized code。
- data and migration: 优先零 schema migration；不得通过新增/回填审计记录制造证据。
- compatibility and rollback: additive API/CLI；移除 endpoint/DTO/CLI command 即可回滚，既有 runtime/ask 行为不变。
- permissions and secrets: 只接受 runtime key/secret；禁止 admin/control/platform/management/access/runtime-token/task-token/Worker credential 混用；响应和日志不得回显 credential 或 sensitive task content。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1 | must-pass | major | service/controller/CLI tests + live | existing frozen IDs | exact commands/results + redacted output |
| AC-2/3 | must-pass | critical | provider/business task tests + live | durable task `20260723-d9ab` | task required fields and terminal stages |
| AC-4 | must-pass | critical | interaction verification + DB before/after read-only snapshot | existing active task/token counts | zero writes/token issuance/dispatch proof |
| AC-5/6 | must-pass | critical | negative/security/output tests + sensitive scan | existing auth guards | stable errors and scan result |
| AC-7/8 | must-pass | major | affected Maven tests, package, health/listener, hashes, live audit | current local runtime | provenance and no-provisioning-change evidence |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused unit tests, compile, CLI help/output tests, route contract, source sensitive scan（单次 `<5m`）。
- medium_validation: affected Maven modules with dependencies, CLI clean package, launcher package/start/health and two live audits（单次预计 `5-30m`）。
- expensive_validation: none planned。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；本事项不运行 historical authority/replay/rehearsal。
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: only if focused/affected evidence cannot establish zero-side-effect security semantics; stop and replan before running。
- maximum_expensive_attempts: 0 without new approval
- reusable_evidence: frozen basis、当前 listener/health 与既有 durable task；仅在 artifact/runtime state 未变化时复用。
- stop_when_evidence_is_sufficient: required tests pass、package/provenance fixed、live outputs complete、before/after state unchanged、sensitive scan clean。
- validation_not_required: frontend build/Playwright、Worker live calls、new task smoke、TMS/SIM business flow、full root reactor。

## Waiver Policy

- waivable_items: none
- authorized_role: project owner
- non_waivable_guards: zero token issuance、zero mutation/dispatch、ownership、secret/content redaction、no provisioning change。
- required_risk_record: 任何环境依赖未执行或 live durable state 偏差必须在本 work item 和最终报告中明确 blocker/residual risk。

## Risks and Open Questions

- known_risks:
  - 当前 dirty worktree 包含相邻 Open SDK/runtime audit 改动，实施必须增量合并且不得覆盖。
  - provider task 与 business task correlation 可能存在历史缺行；输出必须保持 `UNKNOWN/NOT_FOUND`，不得修复。
  - live 8112 当前 artifact 与工作树不同；server 改动后必须重新构建并重新冻结 basis。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关模块规范和 `navigator-runtime-provisioning`。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 不运行大型 authority/replay/rehearsal/full-chain；不创建任何 live task。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 新增 `GET /api/v1/open/runtime/binding-audit` 与 `GET /api/v1/open/runtime/task-audit`，使用 ClientApp runtime long-term key/secret 直接认证，未经过 access/runtime/task token 签发链。
  - 新增 read-only audit service、durable repository queries、server/SDK DTO、SDK API、CLI JSON/人类可读输出、route manifest 与权限合同。
  - binding audit 组合 Agent/model/directory/WorkerHost/Physical Worker/role registration 与 active-task durable state；task audit 组合 task/terminal/error/token/active-registration/provider state，并返回脱敏 terminal stages。
  - 两个 endpoint 从 authorization shadow persistence 中精确排除；controller 仍执行强 runtime credential 与 ownership 校验，避免只读请求写入 `authorization_decision`。
  - CLI 更新为 1.0.26，并声明 `runtime-binding-audit`、`runtime-task-audit`、`runtime-audit-no-token-issuance`、`runtime-task-terminal-state-audit`、`runtime-audit-no-dispatch`。
- changed_paths:
  - server/auth: `addons/claude-worker-agent/.../OpenApiController.java`; `addons/claude-worker-agent/.../RuntimeStateAuditService.java`; three server audit DTOs; `business-agent-module/.../ClientAppRuntimeCredentialResolver.java`; three repository interfaces; `user-auth-module/.../WebMvcConfig.java`.
  - SDK/CLI: `navigator-open-sdk/.../BusinessAgentApi.java`; three SDK audit DTOs; `CliArguments.java`; `UpstreamCli.java`; CLI provenance and version; `tools/navigator-upstream-cli/dist/package.sh`; `package.ps1`; locally installed 1.0.26 distribution.
  - authorization/docs/runtime: canonical/evidence route manifests; authorization catalog/tests; this work item and version index; `scripts/start-launcher.sh`.
  - tests: `RuntimeStateAuditServiceTest`; `OpenApiControllerMessageMappingTest`; `ClientAppRuntimeCredentialResolverTest`; `WebMvcConfigTest`; `UpstreamCliTest`; authorization contract/regression tests.
- tests_and_results:
  - focused authentication, resolver, controller/service, WebMvc and CLI suites: all PASS; focused audit suite `64` tests and WebMvc suite `2` tests, exit `0`.
  - `mvn test -pl navigator-open-sdk`: `188` tests PASS, exit `0`.
  - `mvn test -pl addons/claude-worker-agent -am`: affected 8-module reactor PASS, exit `0`; Navigator Common `120` tests (`3` skipped), Business Agent `704`, Claude Worker Agent `404`.
  - `mvn package -pl launcher -am -DskipTests`: 14-module reactor PASS, exit `0`.
  - CLI Linux/Windows clean package and local install/version smoke: PASS, exit `0`.
- manual_or_experience_evidence:
  - final live window `2026-07-24T06:37:11Z..06:37:13Z`: both commands succeeded against the existing SIM-owned runtime profile and current 8112 server.
  - before/after checksums for 22 relevant durable tables were identical, including token/task/session/context/audit/authorization/binding/registration tables; `authorization_decision` count was unchanged.
  - frozen binding matched tenant/user/Agent/model/directory/Worker/3131/3151 registration and returned `activeTaskCount=0`.
  - existing task `20260723-d9ab` returned `terminal=true`, `FAILED`, `CODEX_WORKER_REMOTE_ERROR`, token `REVOKED`, no active registration, dispatch/retry/recovery `1/0/0`, physical worker `ddc45293`, variant `codex-luna:high`.
  - all ten side-effect assertions were `false` for both responses.
- deviations:
  - first live verification exposed one authorization-shadow audit write；the exact two audit routes were then excluded from shadow persistence, regression-tested, rebuilt, restarted and reverified with zero relevant-table changes.
  - durable agent-pool resolution can intentionally defer physical selection；binding audit accepts the directory-pinned physical Worker when no conflicting pool physical Worker exists and rejects an actual conflict.
- residual_risks:
  - repository remains dirty because it contains pre-existing unrelated work；changed-path reporting is scoped to this work item and no unrelated changes were reverted.
  - normal server startup independently schedules legacy background recovery checks for unrelated historical tasks；final audit-window request threads performed only durable reads, and the target task was neither recovered nor mutated.
- reused_evidence: frozen basis supplied by owner, subject to live revalidation
- omitted_validation_and_reason: no new task smoke, Worker/model/BusinessFunction live call, TMS/SIM business flow, browser or ActorHome access was run because those actions are explicitly prohibited by this contract.
- readiness: READY_FOR_SIGNOFF；implementation session has not self-assigned `ACCEPTED`.

## References

- requirement / issue: owner request dated 2026-07-24, runtime binding/task read-only audit
- architecture / glossary: `CLAUDE.md`; `docs/00-system-overview.md`; `docs/02-modules/task-governance.md`
- related work items: `GOV-001-dev-s1-s2-integration-mvp.md`; `BUG-013-codex-unverified-state-and-post-terminal-sse.md`; `BUG-017-runtime-request-audit-no-task-id.md`
