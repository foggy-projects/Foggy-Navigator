---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-031
status: APPROVED
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-28
implementation_authorized: false
open_questions: []
---

# Delivery Spec: Clean runtime provenance and task-scoped caller token

## Document Purpose

- intended_for: normal-analysis / future-ultra-implementation / independent-signoff
- purpose: 固化 GitHub issue #153 的 clean runtime provenance 要求，并将 task token 定义为调用者既有能力在单个 Task 与允许 function/tool scope 上的短期受限委托切片。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-031-clean-runtime-provenance-and-task-scoped-caller-token.md`
- canonical_status: 本文是该事项唯一项目级执行契约，取代先前的 strict-lock revoke、异步 capability closure 和独立 token lifecycle 方案。当前只授权文档与计划，未授权代码、部署、GitHub 修改或 live smoke。

## Goal

- version_goal: 解除 `foggy-world-sim v0.0.831` controlled smoke 的 clean runtime provenance 阻断，并以本文批准的 task-scoped caller-token 语义取代 issue #153 原独立 revoke 要求。
- target_outcome:
  - 部署方可通过受控运行时元数据确认运行中 launcher 报告的内嵌 SCM/build metadata 与预期完整 commit 一致、包含非空 build version/time，且内嵌构建源状态为 `dirty=false`。
  - task token 只表达当前 caller authority 在一个 Navigator instance、Task 和允许 function/tool scope 上的短期缩减投影，不能提权或跨 instance/Task/owner 使用。
  - Task 不存在或进入 Navigator 权威终态后，旧 token 不再可用；具体持久化标记、缓存和清理机制由 Ultra 基于现有实现选择。
  - FSScript 等待用户授权和 transport/UI detach 不是 Task 终态。Token 可以保持有效，但当前操作仍必须服从 approval/control policy。
  - Task 仍有效而 token 到期时，可重新验证 caller、Task 和 scope 后签发新 token。Task 已终态时旧 token 不复用；后续继续、重试或恢复按现有 Task governance 重新授权并新签 token。
- critical_outcomes:
  - provenance 缺失、dirty 或 commit 不匹配时 controlled smoke preflight 拒绝继续。
  - token 每次有效授权都不得超过调用当时 caller authority、Navigator instance、Task 与 function/tool scope 的交集。
  - caller authority 被撤销或缩减后，未过期 token 的后续使用不得保留已移除权限。
  - missing/terminal Task 不得继续使用旧 token。
  - pause、等待授权、页面刷新、CLI 退出、SSE/HTTP 断连和 UI wait timeout 不被误判为 Task terminal。
- success_is_sufficient_when: focused/affected tests 证明 clean provenance、使用时 caller-authority 求交、精确 instance/Task/owner/scope、missing/terminal fail-closed、pause/detach 非终态、到期后重新验证签发和无敏感信息泄露；经单独批准的 fixture-only controlled smoke 可作为最终运行证据。

## Scope

- in_scope:
  - clean runtime provenance 的 release/deploy preflight 与运行实例比对。
  - task token 从 caller authority 向单个 Task/function scope 的缩减投影语义。
  - token issuance 与实际使用路径中的 current caller authority，以及 exact Navigator instance、tenant、ClientApp、upstream user、Task 和 operation scope 校验。
  - Task missing/权威终态后的旧 token 拒绝。
  - waiting-user-authorization、pause、transport detach 与 UI wait timeout 的非终态语义。
  - active Task 的 token 到期后重新验证并签发；terminal 后旧 token 不复用，后续行为服从现有 Task governance。
  - focused/affected regression tests 与 operator/architecture documentation。
- affected_modules:
  - release/deployment scripts and launcher build metadata
  - `business-agent-module`
  - 实际执行 task-token issuance 和有效授权判定的现有模块
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - `foggy-world-sim` 仅作为 issue #153 需求方和后续 fixture-only consumer；未经单独授权不修改 sibling repository。
  - controlled deployment 需要 operator-only/deployment-network provenance probe；不得据此公开 Actuator。

## Non-Goals

- out_of_scope:
  - 不提供独立 task-token revoke/closure OpenAPI、SDK 或 CLI。
  - 不引入独立 token/capability 生命周期状态机、closure aggregate、reconciler、Session capability 或 convergence SLO。
  - 不为 token 新建 execution capability、attempt、lease 或其他业务实体；后续继续/重试/恢复复用现有 Task governance。
  - 不把 Worker/lease 绑定提升为 task token 的产品生命周期；Worker route 与执行身份继续由既有 Gateway/runtime policy 独立治理。
  - 不规定 terminal、token storage、runtime cache 或清理的具体物理实现。
  - 不实现或改变 task terminate、cancel、retry、recovery、fallback、redispatch 行为。
  - 不实现 JAR/image byte-level identity、image digest、签名、SBOM 或 SLSA attestation。
  - 不支持本事项的应用版本 rollback；该决定不授权破坏性数据库迁移。
  - 不因本事项开放 production `/actuator/info`、Gateway external 或其他 external/production 开关。
- do_not_touch:
  - 不读取、记录或返回 token plaintext/hash、ClientApp secret、Authorization header、prompt、model response、业务数据或敏感物理路径。
  - 不修改 sibling repositories、真实上游资源、真实 Worker/Directory/Pool/credential 或 production 数据。
  - 不以 token cleanup、分布式锁、长事务或通用 capability/IAM 平台扩大范围。
- non_blocking_or_waivable_items:
  - artifact digest/signing、真实浏览器 disconnect drill、历史 token 物理清理和跨节点 cache 删除证明属于后续 hardening，不阻塞本项 MVP。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| Task token 是 caller authority 的 Task-scoped 切片 | token 是受限委托凭据，不是新的权限主体 | 只能缩小权限，不能增加 caller 未拥有的能力 |
| caller authority 在每次有效使用时重新求交 | token 不是在签发时冻结的独立长期授权 | caller grant/credential 被撤销或缩减后，未过期 token 的后续使用不得保留已移除权限 |
| 权限范围是 current caller、Navigator instance、Task 与 function/tool scope 的交集 | 一个 Task 只应获得完成自身工作的最小权限 | 不得跨 instance、tenant、ClientApp、upstream user 或 Task 使用 |
| Task missing/权威终态使旧 token 失效 | token 不应在所属业务对象结束后继续存在授权意义 | Ultra 复用或修补现有权威判定，不冻结具体存储实现 |
| pause/waiting-user-authorization 不是 terminal | 等待确认只暂停当前动作，不代表 Task 消失 | token 有效不等于当前操作获批；操作仍需通过 approval/control policy |
| active Task 的过期 token 可重新签发 | TTL 限制凭据窗口，不应强迫仍有效的 Task 终止 | 新签发必须重新验证 caller、Task 和 scope，不要求自动续签 |
| terminal 后旧 token 不复用 | 已结束 Task 的旧委托不能被重新激活 | 后续继续、重试或恢复按现有 Task governance 重新授权并签发新 token |
| Worker route 与 token scope 正交 | Worker/lease 是执行与路由事实，不是 caller capability 的所有者 | 既有 Gateway/runtime policy 可独立拒绝不合法 Worker route |
| 不提供独立 token revoke/lifecycle | 当前没有“Task 继续但单独关闭其 caller token”的必要业务场景 | Issue #153 的旧 revoke 描述由本文已批准决策取代 |
| provenance MVP 是 embedded SCM/build identity | full commit + `dirty=false` + build version/time 能证明内嵌构建来源元数据 | 不声称 byte-level artifact attestation |
| rollback 不支持 | 为此设计逆向业务语义代价过高 | 如实现需要破坏性迁移，必须 `NEEDS_REPLAN` |

## Task-Scoped Authority Contract

```text
effective task authority =
    current caller authority recognized by existing authorization governance
    INTERSECT Navigator instance scope
    INTERSECT Task scope
    INTERSECT token-delegated function/tool scope
```

```text
request allowed =
    token credential is valid and not expired
    AND current caller authority still permits the requested operation
    AND Navigator instance + tenant + ClientApp + upstream user ownership matches
    AND referenced Task exists and is not in Navigator authoritative terminal state
    AND requested operation is within the delegated Task/function/tool scope
    AND Task current control state permits the operation
    AND existing Gateway/runtime route policy permits the execution path
```

- Token 只能缩小 caller authority；不能把 caller 本身无权执行的 operation 变为允许。该交集在每次有效使用时按服务端当前授权事实重新判断，而不是仅在签发时冻结。
- Token 有效与“当前动作可执行”是两个判断。等待用户授权时 Token 可以仍有效，但被暂停的动作必须等待 approval/control policy 放行。
- provider raw status、短暂空状态、UI timeout 或 transport detach 本身不定义 terminal；以 Navigator 现有权威 Task lifecycle 为准。
- Worker、lease、runtime route、缓存和持久化清理属于独立实现/执行治理事实，不改变上述 caller-to-Task 权限切片定义。

## Acceptance Criteria

- [ ] AC-1: controlled release/deployment preflight rejects tracked/untracked/submodule dirtiness, carries the resolved full commit into deployment verification, and requires exact commit, `dirty=false`, nonblank build version and build time through a private runtime metadata path; malformed or mismatched data fails closed without raw response or secret leakage.
- [ ] AC-2: on every effective use, task token cannot authorize any Navigator instance、tenant、ClientApp、upstream user、Task、function 或 tool operation outside the intersection of current caller authority and the delegated Task scope; caller authority reduction or revocation constrains the next use of an otherwise unexpired token.
- [ ] AC-3: every effective token-use path rejects an expired token and rejects an old token when its Task is missing or in Navigator authoritative terminal state, regardless of implementation-specific storage or cache state.
- [ ] AC-4: FSScript waiting for user authorization and transport/UI detach remain nonterminal; the token may remain valid while approval/control policy blocks the paused operation. An active Task may receive a new token after expiry only after current caller、Navigator instance、Task and scope are revalidated.
- [ ] AC-5: a terminal Task never reuses its old token. Any later continue、retry or recovery follows existing Task governance and must obtain a newly authorized token; no independent revoke/lifecycle system is added.

## Contract / Data / Security Constraints

- API or event contract:
  - no new public token revoke/closure endpoint, status endpoint, request/response contract or CLI command.
  - existing task lifecycle APIs and Worker routing contracts remain unchanged unless a concrete defect requires `NEEDS_REPLAN`.
- data and migration:
  - no new token lifecycle/capability table or convergence schema.
  - no destructive migration is approved. Ultra may reuse existing persistence or make a narrow compatible correction; destructive/broad migration requires `NEEDS_REPLAN`.
- compatibility and rollback:
  - rollback is not supported by this work item.
- permissions and secrets:
  - assurance is `elevated` because this is a task-scoped permission boundary, not because strict transactions or exhaustive validation are required.
  - evidence may contain only sanitized identifiers, scopes, boolean decisions, stable reason categories and timestamps; no token plaintext/hash, credential, prompt, model output, business payload or sensitive path.

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1 provenance | must-pass | major | release-script subprocess/static tests plus launcher metadata contract test | existing build metadata tests and release-script patterns | exact commands, pass/fail matrix and sanitized sample output |
| AC-2 scope/no elevation | must-pass | critical | focused authorization tests for current caller、instance、owner、Task and function/tool scope intersections, including caller-authority reduction after issuance | current ownership and function-scope tests | test names/results and denied cross-instance/out-of-scope/revoked-authority matrix |
| AC-3 Task/expiry boundary | must-pass | critical | effective-use-path tests for expired、missing and authoritative-terminal Task | existing task-token lifecycle tests | old-token denial independent of incidental storage/cache details |
| AC-4 pause/reissue | must-pass | major | waiting-user-authorization、detach and active-Task reissue tests | OPT-029 detach semantics and existing issuance tests | state/control assertions and revalidation evidence |
| AC-5 terminal continuation | must-pass | major | regression covering old-token denial and newly authorized token through existing continuation governance | existing task lifecycle tests | old token rejected; later token issued only after new authorization |
| docs/security | must-pass | minor | documentation review, changed-surface review and scoped secret scan | this canonical spec | final references and no secret finding |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused unit/contract tests, documentation lint where available, scoped secret scan and `git diff --check`; each expected under 5 minutes.
- medium_validation: affected Maven module tests and release-script subprocess suite; each expected 5–30 minutes.
- expensive_validation: clean build/deploy plus private provenance probe and one fixture-only Task/token lifecycle observation may take 30–60 minutes; it is not automatically authorized.
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: final issue #153 release candidate where focused/affected evidence cannot establish deployed provenance and actual Task-bound token rejection.
- estimated_full_chain_wall_clock: 30–60 minutes based on clean build, deploy, health/provenance preflight and one fixture-only Task lifecycle check.
- full_chain_prerequisites: explicitly approved environment, clean candidate commit, fixture-only Task, no real business data and operator-owned deployment window.
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation; deployed smoke remains `NOT_RUN` and cannot be claimed as runtime signoff.
- expensive_validation_trigger: product authorization logic, deployment artifact or provenance scripts changed after reusable evidence was produced.
- maximum_expensive_attempts: one approved full run; an environment/non-product failure stops the run and requires a new validation decision.
- reusable_evidence: unchanged focused authorization tests, release-script harness results and exact artifact/commit evidence remain reusable until their direct inputs change.
- stop_when_evidence_is_sufficient: AC-1 through AC-5 have passing focused/affected evidence, no permission-elevation、stale caller-authority、cross-instance or missing/terminal Task bypass remains, and all deviations are disclosed.
- validation_not_required: independent token revoke、capability convergence、Session cascade、Worker/lease lifecycle binding、bulk token cleanup、cross-node cache deletion、rollback、artifact signing、deterministic LLM replay or unrelated full repository chains.

## Waiver Policy

- waivable_items: artifact digest/signing, real browser disconnect drill, production exposure/auth redesign for `/actuator/info` and implementation-specific token/cache cleanup evidence.
- authorized_role: Project Owner.
- non_waivable_guards: no privilege elevation, use-time caller-authority enforcement, exact Navigator instance/owner/Task/scope isolation, expired/missing/terminal rejection and no secret/token leakage.
- required_risk_record: any waiver must identify affected AC、environment、mitigation and follow-up owner/date; a failed non-waivable guard cannot be described as passing.

## Bug Context

- bug_source: acceptance-found
- severity: critical
- environment: `foggy-world-sim v0.0.831` controlled-smoke preflight against a deployed Navigator runtime.
- current_behavior:
  - deployed runtime reported `dirty=true`, so the consumer could not attribute it to an accepted clean source state.
  - issue #153 requested an independent timeout token revoke. Project review determined that task token is only a caller-authority slice for one Task, so an independent lifecycle would add disproportionate complexity.
- expected_behavior:
  - deployed provenance reports exact clean embedded source/build metadata through a private/operator path.
  - task token is constrained on every use by current caller authority and exact Navigator instance/Task/function scope, expires by TTL, and cannot be used after the Task is missing or terminal. Pause/detach remains nonterminal; later work obtains a newly authorized token when required.
- reproduction_steps:
  1. Inspect controlled-deployment runtime metadata for dirty/missing/mismatched provenance.
  2. Exercise an owned Task token against allowed、cross-instance and out-of-scope Task/function operations.
  3. Verify behavior while active, after caller authority is reduced/revoked, waiting for authorization, expired, missing and terminal.
  4. Confirm whether any effective authorization path permits caller privilege elevation, cross-instance replay or old-token use after the Task boundary ends.
- reproduction_status: partial; dirty runtime evidence is confirmed, while exact authorization gaps must be located during authorized implementation.
- existing_evidence: GitHub issue #153, current build metadata configuration, task-token function-scope schema contract and existing task/token lifecycle tests.
- existing_tests: build metadata, ownership, function-scope, task-token lifecycle and detach-related tests exist but do not yet constitute complete evidence for AC-1 through AC-5.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - one effective token-use path may validate the credential without applying current caller authority or the same Navigator instance、Task and function/tool intersection; implementation must trace the actual authorization path rather than infer it from storage models.
  - token validity and approval/control state may currently be conflated, causing a waiting Task either to continue prematurely or to be incorrectly treated as terminal.
  - removing independent revoke means an observation timeout alone does not close a still-running Task token. The caller either stops observing while the Task continues or uses existing Task governance to stop the Task.
  - current TTL remains the active-Task credential-window limit; changing its default is a separate optimization.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目根 `AGENTS.md`、相关模块规范、issue #153 和关联 work items。
- 当前 `implementation_authorized=false`；只有用户后续明确要求实施时，才可将状态改为 `ULTRA_EXECUTING` 并开始修改代码。
- 先定位真实 token issuance 和 effective authorization path，再选择最小修补；不得把 Worker/lease、terminal storage、cache 或 cleanup 的当前实现提升为新的产品模型。
- 对稳定可复现的 permission-elevation、stale caller-authority、cross-instance/scope bypass 或 missing/terminal Task bypass 优先形成失败回归，再修复并运行通过。
- 如实现需要破坏性迁移、新公共 revoke API、独立 capability 生命周期或 Session lifecycle，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的 focused/affected 验证，记录精确命令、结果和未运行原因。
- 未经用户明确批准，不得部署、运行 live smoke、修改 GitHub issue、启动大型 authority/replay/full-chain 或修改 sibling repositories。
- 达到 evidence sufficiency 后停止，不因实现细节清理或非核心 route/Worker 证明扩大任务。
- 完成后填写 `Implementation Result`，将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

> 由 Ultra 执行会话填写。

- implementation_summary:
- changed_paths:
- tests_and_results:
- manual_or_experience_evidence:
- deviations: none
- residual_risks: none
- reused_evidence:
- omitted_validation_and_reason:
- readiness: READY_FOR_SIGNOFF | NEEDS_REPLAN | BLOCKED

## References

- requirement / issue: [GitHub issue #153](https://github.com/foggy-projects/Foggy-Navigator/issues/153); where its original independent-revoke wording conflicts with this document, this project-owner-approved canonical decision governs implementation.
- architecture / glossary:
  - `docs/terminology-glossary.md`
  - `docs/02-modules/task-governance.md`
- related work items:
  - `BUG-007-task-token-function-scope-schema-contract.md`
  - `GOV-001-upstream-permission-and-trust-boundary.md`
  - `../../1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- implementation surfaces to inspect during authorized execution:
  - launcher build metadata and dev-kvm release/deploy scripts
  - task-token issuance and effective caller/Task/function-scope authorization
  - existing Task lifecycle and approval/control-state handling
