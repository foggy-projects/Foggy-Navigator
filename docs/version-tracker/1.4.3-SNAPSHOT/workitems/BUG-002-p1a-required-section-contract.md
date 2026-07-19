---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-002
status: ACCEPTED
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-19
bug_source: acceptance-found
acceptance_record: ../evidence/BUG-002-p1a-required-section-independent-resignoff.md
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
observer_bff_p1a_disposition: catalog-and-test-only
open_questions: []
---

# Delivery Spec: P1A action required-section 合同缺失

## Document Purpose

- intended_for: ultra-implementation / independent-signoff / Project Owner
- purpose: 冻结独立签核发现的 P1A-3 阻断缺陷修复契约，并记录 Owner 对 P1A-6 Observer BFF 的 catalog/test-only 范围修订。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-002-p1a-required-section-contract.md`

## Goal

- version_goal: 使 `navi.authorization.v1` 的 action catalog 成为最小 context section 的唯一权威来源，并让缺失/未知 section 产生稳定、fail-closed 的 shadow decision。
- target_outcome: 修复当前以 action 前缀猜测 capability 的行为，使 runtime、Worker Gateway 和后续 management action 按批准合同声明、构造和校验所需 section。

## Scope

- in_scope: route/action manifest 的显式 required-section 合同；canonical context 的 typed sparse sections；catalog loader、validator/evaluator 与 legacy adapter 的相应表达；action-family 分类、稳定 fail-closed reason 和负向回归测试；catalog checksum/evidence 同步。
- affected_modules: `navigator-common`、`user-auth-module`、`launcher`；`tools/navigator-chat-observer-bff` 仅允许维持 test-scope catalog/context coverage，不得修改其 runtime 主路径。
- external_dependencies: none。

## Non-Goals

- 不实现 P1B/P1C、typed credential issuance、management API、CLI/SKILL 或 enforcement cutover。
- 不开启 `NAVIGATOR_EXTERNAL_ENABLED`、Worker Gateway strict/external、Worker external 或 production。
- 不创建 Worker、BizWorkerIdentity、WorkerPool member，不改变 Codex Physical Worker 路由。
- 不向 Observer BFF `src/main` 增加 evaluator、interceptor、audit store 或主路径依赖，不做 observer session、attachment capability、CSRF/origin、bind-address 或 production hardening。
- 不改变数据库 schema/migration、当前 route 数量、HTTP API/event contract 或 `/actuator` 404 兼容决策。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| required-section policy 只能来自 source-controlled catalog | 避免 evaluator/adapter 再以 action prefix、path、surface 或 risk tier 猜测所需 section | 表达形式由 Ultra 决定，但 415 条 entry 必须均有显式、可机器校验的声明；无额外 section 时也必须显式表示，不得留空 |
| envelope 校验与 action-required typed section 校验分层 | `schema/policy/catalog/build/correlation/deployment/action/route` 是合同 envelope；`principal`、`credential`、`trust`、`authority`、`delegation/grant`、`target`、`platformGrant/tenantAuthority`、`capability`、`workerRoute` 是可稀疏构造的 typed sections | 未被 action 要求的 section 可以缺席；被要求的 section 缺失、未验证或冲突时必须稳定 fail closed |
| 相同 `canonical_action` 的 required-section set 必须一致 | required section 是 action 语义，不应由 deployment 或 route 偶然分叉 | 若真需不同语义，应另立 action contract；本 BUG 不授权改名或拆分现有 action |
| `runtime.ask` 显式要求 capability，其他 19 个当前非 ask `runtime.*` 不因前缀自动要求 capability | 直接关闭签核确认的误分类 | Worker Gateway 必须在 catalog 中显式要求 task capability 与 worker route；其他 action 若需 capability，也必须逐 action 明示，不得使用前缀特判 |
| legacy adapter 只构造可由服务端或当前 legacy 事实支撑的 section | P1A 不能为了减少 shadow divergence 伪造 authority、grant、owner、capability 或 Worker route | 无权威来源的 section 保持 absent/unverified，不从 header、path、request body 或 legacy owner 字段补造 |
| Observer BFF 在 P1A 修订为 catalog/test-only | 其 runtime shadow/audit 需要独立的 ingress/session/capability 安全设计，不应与 required-section 修复捆绑 | 12 条 BFF route 仍必须在 deployment-aware catalog 中声明 required sections 并通过测试；不因本 amendment 变为 external/production ready |

## Bug Context

- severity: critical
- current_behavior: manifest/entry 没有 required-section 声明；`requiresCapability()` 对 Worker Gateway 或所有 `runtime.*` 返回 true。当前 20 个 `runtime.*` ingress 中只有 `runtime.ask` 按冻结合同需要额外 capability intent，另 19 个动作被错误分类。
- expected_behavior: 每个 action 由 source-controlled catalog 显式声明最小 section；validator/evaluator 不按 action 名称、路径或 surface 前缀猜测；未知/缺失 section 返回稳定 deny/unknown shadow reason。
- impact: 产生无意义的 `AUTHZ_LEGACY_CAPABILITY_UNVERIFIED` divergence，掩盖真实 principal/target/grant 缺口，并使 P1A foundation 不适合作为后续 enforcement cutover 基线。
- current_enforcement_impact: none；P1A 仍为 non-binding shadow。未来 cutover 风险为 blocking。
- environment: 当前 1.4.3-SNAPSHOT P1A 未提交工作树；Launcher shadow 已接入，Observer BFF 仅 catalog/test coverage。
- reproduction_steps: 检查 manifest header/entry model 无 required-section 字段；向 `AuthorizationRouteManifestEntry.requiresCapability()` 传入任一非 ask `runtime.*` action，仍返回 true；统计当前 20 个 `runtime.*` ingress，其中仅 `runtime.ask` 有 capability intent。
- reproduction_status: confirmed by independent signoff。
- existing_evidence: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p1a-independent-signoff.md`。
- existing_tests: 当前 contract tests 覆盖版本、catalog count/hash、unknown route/action，但不覆盖 required-section schema、`runtime.ask` 与非 ask runtime 差异、未知 section 或 action-class completeness。
- regression_protection: required。
- waiver_reason_and_risk: none。

## Acceptance Criteria

- [x] AC-1: 415 条 route/action catalog entry 均具有可机器校验的显式 required-section 声明；未知 section、空声明、重复 action 的 section set 冲突或 catalog/action 不一致在测试或加载时 fail closed。
- [x] AC-2: `runtime.ask` 要求 capability intent；当前其他 19 个非 ask `runtime.*` 动作不因 `runtime.` 前缀自动要求 capability。任何额外 capability 要求必须在 catalog 中逐 action 明示并有合同依据。
- [x] AC-3: `AuthorizationContextV1` 能表达 catalog 当前引用的 authority、delegation/grant、platformGrant/tenantAuthority、capability、workerRoute 等 section；sparse 不等于从 schema 中删除所需 section。
- [x] AC-4: evaluator/validator 对缺失、未验证、冲突和未知 required section 产生稳定 deny/unknown shadow outcome 与 reason code；不得抛异常、默认 allow 或降级为名称启发式。
- [x] AC-5: legacy adapter 按 catalog 构造必需 section，但只能将有权威证据的 section 标为 verified；无证据的 authority/grant/target/capability/workerRoute 保持 absent/unverified，不得伪造。
- [x] AC-6: 自动化覆盖 public/framework、introspection、management、runtime ask/non-ask、Worker Gateway、Observer BFF catalog continuity、unknown section/action/route，以及 legacy adapter 的 section 构造；测试实际运行通过。
- [x] AC-7: 仍保持 shadow-only，不改变 legacy HTTP status/body/副作用，不 seed/签发任何 principal/grant/credential/token，不改变 external/Gateway/Worker/production 配置或路由；Observer BFF 仍为 local/trusted-dev、production blocked。

## Contract / Data / Security Constraints

- API or event contract: 无新增 HTTP API/event；`navi.authorization.v1` 仅在 v1 允许的 optional typed section 范围内补齐表达，不改变既有字段含义。
- data and migration: 不改数据库 schema、JPA aggregate 或 P1A forward/rollback migration；如实现发现必须迁移数据，设置 `NEEDS_REPLAN`。
- compatibility and rollback: catalog 仍为 415 条，但因 required-section 声明 checksum 必然变化；必须同步 source manifest、版本 evidence CSV、静态 review 和所有 hash/count assertions。`GET /actuator` 继续 404，子 endpoint 行为不变。
- permissions and secrets: required-section policy 只有一个 source-controlled 权威来源；不得在 evaluator、adapter、CLI、SKILL 或 deployment 中维护第二套分类；不读取或记录 secret/token 原文。
- 缺失权威 section 必须保留为 missing/unverified 并 fail closed；不得从 header、path、请求体或 legacy owner 字段补造 authority/grant。
- Observer BFF 仅保持 manifest/test-scope 依赖和测试；不得因“统一 shadow”修改 BFF runtime、网络或认证边界。
- 若实现需要改变 `navi.authorization.v1` 已冻结字段语义、P1A scope、route/action 语义或 Observer BFF 部署架构，先将本 work item 标为 `NEEDS_REPLAN`。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical | catalog parse/completeness；duplicate-action consistency；20 个 runtime action 的 ask/non-ask 分类断言；Worker Gateway 显式 section 断言 | 更新后的 source/evidence manifest、hash、逐 action required-section snapshot |
| AC-3/AC-4/AC-5 | critical | context serialization/validation；missing/unverified/conflict/unknown section fail-closed matrix；legacy adapter section construction | exact reason/outcome assertions；不含 secret 的 context/decision evidence |
| AC-6 | critical | `mvn test -pl navigator-common`；`mvn test -pl user-auth-module -am`；`mvn test -pl launcher -am`；Observer BFF 定向 catalog/context tests | exact commands、test counts、exit code；未运行项不得声称通过 |
| AC-7 | critical | before/after response contract、`/actuator` contract、forbidden config/BFF-runtime/Worker/CLI mutation scan、secret scan | scoped diff、`git diff --check` 和负向结果 |

## Risks and Open Questions

- known_risks: 如果仅把 `requiresCapability()` 改成特判 `runtime.ask` 而不建立通用 required-section contract，仍不满足 P1A-3。
- open_questions: none。
- architecture_decisions_required: none；Owner 已批准 required-section 目标与 Observer BFF catalog/test-only amendment，具体 Java/CSV/DTO 组织由 Ultra 在本契约内决定。
- next_gate: 已完成独立 re-signoff；BUG-002 与 GOV-001 P1A foundation/shadow 已 accepted。P1B/P1C 仍须 Project Owner 单独授权。

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、GOV-001 canonical spec 和 P1A 独立签核记录。
- 优先建立能重现当前缺陷的失败测试，再实现通用 required-section contract；不得以 `runtime.ask` 特判代替通用机制。
- 可在 scope 内自主决定类、enum、CSV 字段、DTO 嵌套和测试 fixture，但必须保持单一 catalog 权威源与关闭的 section vocabulary。
- 不得修改 Observer BFF runtime `src/main`；如现有 BFF test 因 catalog 格式变化需要调整，仅限 test-scope 连续性修改。
- 不得修改 external/Gateway/Worker/production 开关、Worker 资源、Codex Physical Worker 路由、CLI/SKILL 或数据库 schema/migration。
- 如需改变已确认 action 语义、route 数量、HTTP 兼容、P1A 范围或安全边界，将本文件设为 `NEEDS_REPLAN` 并停止扩展。
- 完成后回写精确 changed paths、manifest count/hash、测试命令/结果、偏差和残余风险，状态最多更新为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED` 或启动 P1B/P1C。

## Implementation Result

> 已完成实现、实施侧验证与独立 re-signoff；历史 `rejected` 结论未被改写，当前 accepted 范围仅为 BUG-002 与 GOV-001 P1A foundation/shadow。

- implementation_summary: 将 `required_sections` 加入 source-controlled route/action catalog 并使其成为 sparse typed context policy 的唯一运行时权威。loader 对空值、未知/重复 token、`NONE` 混用和同一 `canonical_action` 的 section-set 分叉 fail closed；evaluator 只消费 catalog declaration，不按 action prefix、path、surface 或 risk tier 推断。`AuthorizationContextV1` 补齐可稀疏表达的 authority、delegation、platformGrant、tenantAuthority、capability 与 workerRoute section；validator/evaluator 对 missing/unverified/conflict/unknown 产生稳定的 non-binding deny/unknown reason。`runtime.ask` 显式要求 capability，另外 19 条当前 runtime ingress 不因 `runtime.` 前缀获得该要求；4 条 Worker Gateway action 均显式要求 capability 与 workerRoute。legacy adapter 不伪造 authority/grant/owner/capability/Worker route；Observer BFF 保持 catalog-and-test-only，未修改其 `src/main`。
- changed_paths:
  - `navigator-common/src/main/resources/authorization/route-manifest-v1.csv`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationContextV1.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRequiredSection.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRequiredSectionValidationResult.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationContextValidator.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRouteCatalog.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationRouteManifestEntry.java`
  - `navigator-common/src/main/java/com/foggy/navigator/common/authorization/AuthorizationShadowEvaluator.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationContractTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationRequiredSectionCatalogRegressionTest.java`
  - `navigator-common/src/test/java/com/foggy/navigator/common/authorization/AuthorizationRequiredSectionValidationTest.java`
  - `user-auth-module/src/main/java/com/foggy/navigator/auth/authorization/LegacyAuthorizationContextAdapter.java`
  - `user-auth-module/src/test/java/com/foggy/navigator/auth/authorization/LegacyAuthorizationContextAdapterTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest.csv`
  - `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-p0.5-method-route-manifest-review.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`（仅同步 BUG-002 为 `READY_FOR_SIGNOFF`、仍须独立 re-signoff）
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-upstream-permission-and-trust-boundary.md`（仅澄清 `d036...` 是 pre-BUG-002 历史 hash；未改变 P1A historical submission 或独立签核结论）
  - 本 work item；共享 dirty worktree 中其他 P1A/P1B 类文件及 `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` 未改动、未纳入本 BUG。
- manifest_count_and_sha256: source 与 evidence CSV 字节一致；416 行（含 header）/ 415 entries；SHA-256 均为 `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`。
- tests_and_results:
  - `mvn test -pl navigator-common` — exit 0；94 tests，0 failures，0 errors，3 skipped。BUG-002 专项：`AuthorizationRequiredSectionCatalogRegressionTest` 7/0/0/0，覆盖 parse/completeness、duplicate canonical-action consistency、20 条 runtime snapshot、4 条 Gateway 显式 section 与 source/evidence count/hash；`AuthorizationRequiredSectionValidationTest` 4/0/0/0，覆盖所有 typed section 的 missing/unverified/conflict/unknown fail-closed matrix、runtime ask/non-ask 与 Gateway propagation。
  - `mvn test -pl user-auth-module -am` — exit 0；reactor 中 navigator-common 94 tests（3 skipped）、user-auth-module 103 tests（0 skipped），合计 197 tests，0 failures，0 errors，3 skipped。`LegacyAuthorizationContextAdapterTest` 9/0/0/0、`AuthorizationShadowInterceptorTest` 8/0/0/0 与 `AuthorizationIngressRouteResolverTest` 3/0/0/0 验证 legacy adapter、shadow-only 和 ingress continuity。
  - `mvn test -pl launcher -am` — exit 0；14-module reactor 共 2,652 tests，0 failures，0 errors，5 skipped。包含 `AuthorizationRouteManifestCoverageTest` 3/0/0/0（launcher/public/framework/introspection/management/SSH catalog continuity）以及 `ActuatorDiscoveryContractTest` 与 `ActuatorDiscoveryRouteContractTest` 各 1/0/0/0（`GET /actuator` 仍为 404，子 endpoint 合同不变）。Surefire 仅记录 shutdown-delay warning，Maven build 为 SUCCESS。
  - `mvn test -pl tools/navigator-chat-observer-bff -am -Dtest=ObserverBffRouteManifestCoverageTest,ObserverBffContextContinuityTest -Dsurefire.failIfNoSpecifiedTests=false` — exit 0；3-module reactor SUCCESS；BFF 3 tests，0 failures，0 errors，0 skipped（route manifest 1、context continuity 2）。
- manual_or_experience_evidence:
  - source/evidence manifest `cmp` 通过；count/hash 与 `AuthorizationRouteCatalog.EXPECTED_ENTRY_COUNT`、`EXPECTED_SHA_256`、`AuthorizationContractTest` 和 static review 一致。
  - 静态复核确认所有 415 行均有 parseable explicit declaration（其中 6 行为 explicit `NONE`）；生产 evaluator 仅将 `entry.requiredSections()` 交给 validator，未发现 prefix/path/surface/risk-tier inference。
  - scoped forbidden-surface review：Observer BFF `src/main` 0 changed paths；Worker/Codex routing 0；CLI/SKILL 0；external flag diff 0。path-only high-confidence changed-file secret scan 为 0；`git diff --check` exit 0。
- deviations: approved BUG-002 runtime scope 无偏差。续接时共享 dirty worktree 已处于修复后的源码状态；未为重放历史失败而回退或覆盖该工作树。回归 fixtures 明确编码了被拒绝的旧缺陷（缺少 declaration、runtime prefix 误分类与 catalog 分叉），本次记录的是修复后实际运行结果。
- residual_risks: legacy adapter 对无权威来源的 required typed section 故意保留 missing/unverified，因此仍会产生 shadow deny/unknown 而非伪造 allow；这不是 enforcement cutover。共享 dirty worktree 仍包含继承的两份 `docs/migration` 文件与一份 launcher config 变更，以及用户的 frontend 文件；本 BUG 未修改它们，签核已按路径隔离审查。未执行 external/production/Worker runtime 验证，因其不在授权范围内。P1B/P1C 仍未获授权或启动。
- readiness: ACCEPTED；2026-07-19 独立 re-signoff 已通过。该结论仅关闭 BUG-002 与 GOV-001 P1A gate，不授权 P1B/P1C、enforcement、external、Gateway strict、Worker external 或 production。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer
- signed_off_at: 2026-07-19
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-002-p1a-required-section-independent-resignoff.md`
- blocking_items: none
- follow_up_required: no

## References

- [GOV-001 canonical delivery spec](./GOV-001-upstream-permission-and-trust-boundary.md)
- [P1A historical rejected signoff](../evidence/GOV-001-p1a-independent-signoff.md)
- [BUG-002 / P1A accepted re-signoff](../evidence/BUG-002-p1a-required-section-independent-resignoff.md)
