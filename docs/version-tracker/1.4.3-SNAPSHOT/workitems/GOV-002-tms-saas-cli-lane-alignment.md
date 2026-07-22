---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-002
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-22
execution_started_at: 2026-07-22
open_questions: []
---

# Delivery Spec: TMS SaaS CLI 三 Lane 对齐与 Profile 隔离

## Document Purpose

- intended_for: ultra-implementation / CLI operators / independent-signoff
- purpose: 将确认的 TMS 服务商、租户 ClientApp control、租户 runtime 三层模型落成可安全操作、可迁移的 `navi upstream` CLI；不得把历史 credential 误表现为最终 typed authority。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-002-tms-saas-cli-lane-alignment.md`

## Goal

- version_goal: 让 TMS 平台操作者和租户操作者能从命令、profile、凭据提示和本地拒绝中清楚区分 platform、ClientApp control、runtime 三条 lane。
- target_outcome: `navi upstream` 提供 `platform`、`app`、`runtime` 命令入口；TMS tenant provisioning 默认将 platform-control 与 tenant-runtime 写入独立 gitignored profile；旧命令保持兼容但明确迁移方向和当前 legacy authority。

## Scope

- in_scope:
  - 增加 `platform`、`app`、`runtime` 三个规范命令域，并将现有可用的开通/系统资源、精确 ClientApp 配置、runtime token/readiness/ask/task 操作映射到相应 lane。
  - 提供 `platform tenant ensure`，复用现有服务端 provisioning 契约，但要求将 control 和 runtime credential 分别写入平台私有 control profile 和租户 runtime-only profile；终端不得输出明文 credential。
  - 为每个命令域提供 fail-fast credential-lane guard、help、profile 说明和可机器读取的命令结果字段；明确当前 platform lane 是 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`，不是 `SAAS_PLATFORM`。
  - 保留 `client-app ensure-tenant`、`system-*` 和既有 runtime 顶层命令的行为兼容；旧入口输出迁移提示，但不得改变既有 HTTP route、header 或服务端授权语义。
  - 更新 CLI tests、provisioning SKILL、TMS runbook 与版本索引；记录 source/published artifact drift，但不发布 CLI。
- affected_modules:
  - `navigator-open-sdk`
  - `.agents/skills/navigator-runtime-provisioning`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - 既有 legacy upstream-admin、ClientApp control、runtime API 与 P1C-A typed-management inspection endpoint。
  - 真实 `SAAS_PLATFORM` seed/lifecycle 仍受 `GOV-001-P1B-B` named-owner/four-eyes gate 约束，不能作为本事项测试输入。

## Non-Goals

- out_of_scope:
  - 不 seed、签发、轮换、撤销真实 `SAAS_PLATFORM` principal、typed management credential、platform grant 或 tenant authority；不读取真实 `.navigator` profile、环境变量、数据库、KMS 或上游系统。
  - 不改 legacy API route-family enforcement、数据 schema、tenant ownership、Worker ownership/binding、Gateway、Open API/Worker external 或 production 配置。
  - 不将 `NAVI_ADMIN_API_KEY` 自动提升为 `SAAS_PLATFORM`、`SAAS_SECURITY_ADMIN` 或 `INSTANCE_ROOT`；最终 typed platform mutation/lifecycle command 是后续 gated work。
  - 不发布、上传或篡改 CLI archive/release metadata。
- do_not_touch:
  - sibling workspaces、真实凭据和业务数据。
  - 既有 Codex Physical Worker 路由、BizWorkerIdentity、WorkerPool 和 Observer BFF runtime。
  - 当前 worktree 中与本事项无关的 `scripts/local-dev-stack.sh`、`tools/codex-agent-worker/**` 及 `BUG-011-*` 改动。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| CLI 以 `platform`、`app`、`runtime` 表达三层模型 | TMS 是 SaaS 服务商；tenant 是隔离边界，ClientApp 是 tenant 内接入应用 | `platform` 不称为 Navigator root/operator；输出 current actual principal/lane |
| `platform tenant ensure` 是新开通入口 | 将 TMS 开通职责归入服务商平台 | `client-app ensure-tenant` 保留 alias，调用同一实现并给出迁移提示 |
| provisioning 分写两份 profile | tenant runtime 不应收到 control credential | platform-control profile 保留 `NAVI_CONTROL_API_KEY`；tenant-runtime profile 禁止 admin/control/typed credential |
| runtime access token 不持久化到 profile | token 短期且 ask 仍须实时授权 | runtime profile 只保存 ClientApp key/secret 与允许的非 secret metadata |
| 旧命令 HTTP/授权行为不变 | legacy APIs 是当前兼容面 | 新 CLI 不得借命名改变 header、scope 或身份；typed authority 显示 `NOT_CONFIGURED`/fail closed |
| CLI 只能防误用，服务端才是授权源 | profile、URL、命令名不能证明 owner/grant/platform authority | local guard 不输出 `ALLOW`，mutation 仍由服务端重新授权 |

## Acceptance Criteria

- [ ] AC-1: `navi upstream platform`、`app`、`runtime` 均有可发现的 help 和明确 lane/credential/profile 说明；不含 secret，且不将 `NAVI_ADMIN_API_KEY` 描述为 typed `SAAS_PLATFORM`。
- [ ] AC-2: 新规范命令覆盖现有 TMS 平台开通、ClientApp control 配置、runtime token/readiness/owner-smoke/ask 的等价可用路径；每条请求继续发送已有唯一正确的 header/credential，不出现跨 lane header 泄漏。
- [ ] AC-3: `platform tenant ensure` 强制不同的 platform-control 与 tenant-runtime 输出目标；成功后 control key 仅在平台 profile，runtime profile 不含 admin/control/typed credential；缺少、相同、不可写或非 gitignored 路径均 fail closed。
- [ ] AC-4: runtime 命令检测到 admin/control/typed management material 时拒绝；app 命令要求 exact ClientApp control material；platform legacy command 要求 legacy upstream-admin material。混合/缺失/冲突不得 fallback 或权限并集。
- [ ] AC-5: 旧 `client-app ensure-tenant` 与历史 `system-*`/runtime 入口保持功能兼容，并输出一次无 secret 迁移提示；相关 HTTP fixture regression tests 通过。
- [ ] AC-6: `whoami`/permissions/config check 将 current credential lane、expected lane、profile safety state 与 typed authority availability 分开显示；不得由本地信息宣称 `SAAS_PLATFORM` 已启用。
- [ ] AC-7: focused CLI tests、`mvn test -pl navigator-open-sdk -am`、文档/Skill consistency review、`git diff --check` 和 scoped secret scan 实际执行并记录结果。不得读取真实 profile/credential、调用 live TMS 或启用 external/production。

## Contract / Data / Security Constraints

- API or event contract: 只新增/重组 CLI command 与本地 profile output contract；现有 server API path、header、请求体和授权语义保持不变。若需要新的 typed management mutation endpoint 或 service-side split response，设置 `NEEDS_REPLAN`。
- data and migration: 不新增 schema 或持久化数据。profile 是 gitignored local secret material；CLI 写入前必须校验其安全位置/忽略规则，不得将 secret 回显到 stdout、错误、测试快照或 durable evidence。
- compatibility and rollback: 新入口 additive；旧入口至少保持一个发布周期。若安全写入失败，不能输出 credential 或留下带 secret 的可用 combined profile。
- permissions and secrets: client app key/secret 和 control key 只能接收一次并写入指定 profile；`platform-control`、`app-control`、`tenant-runtime` 的字段白名单/禁止清单必须测试。owner/grant/tenant/route 仍由服务端解析。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-5 | major | CLI help and deprecated-alias snapshot/HTTP fixture tests | exact Surefire test names and output assertions |
| AC-2/AC-4 | critical | synthetic HTTP fixture tests for request path/header isolation, missing/mixed-lane rejection and no fallback | request capture assertions; secret-free stdout/stderr |
| AC-3 | critical | temporary gitignored fixture profiles: split success, same-path reject, unsafe path reject, write failure/no partial secret leakage | file-content assertions using synthetic tokens only |
| AC-6 | critical | config/whoami/permissions tests for legacy versus typed availability and no local authorization claim | stable field/value assertions |
| AC-7 | critical | focused tests then `mvn test -pl navigator-open-sdk -am`, `git diff --check`, scoped secret scan | exact commands/results plus unrun reason |

Verification cost: focused tests are `<5m`; affected-reactor test/build are expected `5-30m`. Run focused tests after relevant changes, then one affected-reactor test near completion. If an affected-reactor validation exceeds 30m or fails twice for non-product environment reasons, set `NEEDS_REPLAN` before another full attempt. Documentation-only changes invalidate only corresponding help/consistency evidence.

## Risks and Open Questions

- known_risks:
  - Current backend provisioning returns control and runtime material together to the authenticated platform caller. CLI-side split reduces delivery risk but cannot replace future server-side split issuance semantics.
  - Final typed `SAAS_PLATFORM` lifecycle is blocked by `GOV-001-P1B-B`; this work must visibly distinguish current legacy platform operations from that future authority.
  - Published CLI artifact may lag source; this work records drift but does not publish.
- open_questions: none

## Ultra Execution Contract

- Read this work item, root `AGENTS.md`, `CLAUDE.md`, GOV-001 trust boundary, P1C-A CLI contract, and `navigator-runtime-provisioning` safety rules before implementation.
- Within scope, choose file/class/function details and a minimal command alias strategy. Do not create a second authorization engine or duplicate server policy in the CLI.
- If safe two-profile persistence cannot be made atomic enough with the current one-time response contract, set `NEEDS_REPLAN`; do not emit credentials or silently write a combined profile.
- If implementation requires typed principal seed/lifecycle, a new backend route/schema, changing resource ownership, real profile access, external/Gateway/production change, artifact publication, or Codex topology changes, set `NEEDS_REPLAN` and stop that expansion.
- Record changed paths, exact verification commands/results, deviations and residual risks below. Finish only at `READY_FOR_SIGNOFF`; do not self-assign `ACCEPTED`.

## Implementation Result

- implementation_summary: 已将 `navi upstream` 对齐为 `platform`、`app`、`runtime` 三个规范命令域；新 `platform tenant ensure` 复用既有 provisioning API，但强制以不同的、gitignored 的 platform-control 和 tenant-runtime profile 分写 one-time credential。各 lane 在本地先拒绝混合凭据；旧入口保留兼容并打印无 secret 的迁移提示。当前 platform help/config 明确说明它是 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`，不是 typed `SAAS_PLATFORM`。
- changed_paths:
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/CliArguments.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCliConfig.java`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `.agents/skills/navigator-runtime-provisioning/SKILL.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/runbooks/GOV-001-dev-s1-s2-local-integration.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
- tests_and_results:
  - `mvn -pl navigator-open-sdk -Dtest=UpstreamCliTest test` — PASS, 129 tests, 0 failures/errors (2026-07-22).
  - `mvn -pl navigator-open-sdk -am test` — PASS, 170 tests, 0 failures/errors (2026-07-22).
  - `git diff --check` — PASS (no whitespace errors).
  - Scoped non-test diff secret scan — no assigned credential value found; four matches were source-code reads of credential key names only.
- manual_or_experience_evidence:
  - Checked canonical help and updated provisioning Skill/runbook all use `platform tenant ensure --platform-control-profile ... --tenant-runtime-profile ...` and state that the current lane is not typed `SAAS_PLATFORM`.
  - Synthetic HTTP fixtures verify platform, app and runtime canonical commands retain only their existing expected header; split-profile success, same-path rejection, unsafe path rejection, mixed-lane rejection and runtime-profile write failure are covered without printing synthetic credentials.
- deviations: none
- residual_risks:
  - The current backend still returns control and runtime one-time material together to the authenticated legacy platform caller; CLI-side split protects local delivery only and is not a replacement for future server-side split issuance.
  - Typed `SAAS_PLATFORM` principal/lifecycle remains unimplemented and gated by `GOV-001-P1B-B`; this delivery intentionally does not claim it is active.
  - Source CLI changes have not been published as an artifact; consumers of an existing release need a later release task.
- readiness: READY_FOR_SIGNOFF

## References

- architecture / glossary: `GOV-001-upstream-permission-and-trust-boundary.md`
- real seed gate: `GOV-001-p1b-b-real-inventory-owner-operator-seed-activation-gate.md`
- existing CLI contract: `GOV-001-p1c-a-cli-skill-operator-ux.md`
- local integration runbook: `../runbooks/GOV-001-dev-s1-s2-local-integration.md`
