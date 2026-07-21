---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001-DEV-MVP
status: ACCEPTED
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-20
open_questions: []
---

# Delivery Spec: 开发期 S1/S2 上游联调 MVP

## Document Purpose

- intended_for: implementation / local-integration / independent-signoff
- purpose: 让 `foggy-world-sim` 和 `tms-x3` 尽快使用现有凭据 lane 进入本地联调，同时不把开发期便利误写成最终权限或 production 能力。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-dev-s1-s2-integration-mvp.md`

## Goal

- version_goal: 用一个可验证的最小路径接通 SIM 专属实例与 TMS 平台/租户本地联调。
- target_outcome: TMS 系统级 upstream-admin 可以列出自己派生的 `nav_tms-x3_<tenant>` ClientApp；CLI 和手册清楚区分 bootstrap/control/runtime；两类上游可完成 readiness、owner-smoke 和受限 safe ask 的前置准备。

## Scope

- in_scope:
  - 修复无 `tenantId` 的 upstream ClientApp list：只在同 `upstreamSystemId + namespace` 的候选中，逐条使用现有 fail-closed tenant 授权谓词过滤。
  - 为系统授权、source-tenant 授权、显式动态 tenant 和显式拒绝补自动化回归。
  - 在 CLI help、runtime provisioning SKILL 和 runbook 中说明 upstream-admin、ClientApp control、runtime credential 的用途及 `ensure-tenant` profile 拆分要求。
  - 提供 SIM 专属实例与 TMS 平台/租户的本地接入步骤、负面边界和 live-smoke 前置条件。
- affected_modules:
  - `business-agent-module`
  - `navigator-open-sdk`
  - `.agents/skills/navigator-runtime-provisioning`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - SIM/TMS 的 gitignored local profile、实际 WorkerHost、模型、Agent、Directory 和上游用户由各自上游所有者提供；本任务不读取、创建或回显真实凭据。

## Non-Goals

- out_of_scope:
  - 不实现或签发 S1 `INSTANCE_ROOT`、S2 typed `SAAS_PLATFORM`、real P1B-B、P2/P3/P4、S3 onboarding、Gateway strict、Worker external 或 production 发布边界。
  - 不修改 tenant 授权语义、资源 owner、跨 upstream/namespace 权限、数据库 schema 或现有 runtime/task capability 判定。
  - 不自动把 `ensure-tenant` 的 combined private profile 拆分或向租户分发凭据；当前只明确人工/平台侧安全流程。
  - 不通过新建 Worker、BizWorkerIdentity、WorkerPool member 或替代 Worker 修复 Codex Physical Worker 路由。
- do_not_touch:
  - 不读取或记录真实 admin/control/runtime key、access token、上游账号或业务数据。
  - 不修改 `foggy-world-sim`、`tms-x3` 或其他 sibling workspace。
  - 不启用或重新解释 `NAVIGATOR_EXTERNAL_ENABLED`、`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 无 tenant filter 的 TMS list 查询同 upstream + namespace 候选，再逐条复用 `isTenantAuthorized` | 支持 `nav_tms-x3_<tenant>` 动态 tenant，且不放宽授权 | 显式 tenant query 保持原有精确路径；不使用 `findAll()` |
| SIM 先使用专属实例中的 legacy upstream-admin 联调 | 让当前功能先进入调试 | 这不是最终 `INSTANCE_ROOT`，不得声称获得任意非自有资源的完整 root 权限 |
| TMS 平台保管 admin/control，租户只交付 runtime lane | 保持最小权限并与现有 runtime resolver 对齐 | `ensure-tenant` 当前会把两 lane 写入同一私有 profile，平台必须先拆分 |
| readiness/owner-smoke 在 ask 前通过 | `READY` provisioning response 不是运行态能力证明 | ask 仍取 runtime、Agent、task capability、Worker route 和执行策略的交集 |

## Acceptance Criteria

- [x] AC-1: 系统级 `tms-x3` upstream-admin 在未给 `tenantId` 时能列出其授权的动态 Navigator tenant ClientApp；不同 source tenant 的 scoped credential 只能看见自身派生 tenant。
- [x] AC-2: 显式动态 tenant list 仍经既有授权检查并走精确查询；未授权动态 tenant 拒绝且不查询资源。
- [x] AC-3: CLI help、SKILL 和 runbook 明示 admin/control/runtime lane、combined bootstrap profile 风险、SIM dev-only root 限制以及 external/Gateway 红线。
- [x] AC-4: 文档提供可执行但不含密钥的 SIM/TMS 本地联调顺序；TMS tenant 仅在 runtime-only profile 后进入 runtime smoke。
- [x] AC-5: 相关 unit/CLI tests 实际通过，且没有外部 flag、Gateway、Worker route 或 sibling workspace 改动。

## Contract / Data / Security Constraints

- API or event contract: 既有 `GET /api/v1/upstream-admin/client-apps` list contract 不变；仅修正未过滤 list 的授权候选选择。
- data and migration: 无 schema/data migration。repository query 使用既有 `client_app(upstreamSystemId, upstreamClientAppNamespace, tenantId, upstreamRef)` 索引前缀。
- compatibility and rollback: 显式 tenant query 不变；若候选查询在未来需要分页，应另立 work item，不能改成无边界全表扫描。
- permissions and secrets: `NAVI_ADMIN_API_KEY` 仅 bootstrap/管理，`NAVI_CONTROL_API_KEY` 仅 ClientApp control，runtime key/secret/access token 仅 runtime。所有 profile 必须 gitignored；不将 control key 交付 tenant。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | cross-tenant visibility | `UpstreamClientAppManagementServiceTest` 与 credential service tests | Maven output；exact list/deny assertions |
| AC-3 | operator credential misuse | `UpstreamCliTest` help assertion；SKILL/runbook review | CLI test output；no secret content diff review |
| AC-4 | false runtime readiness | source-only runbook review；live smoke deferred to upstream owner | runbook path；live prerequisites recorded |
| AC-5 | accidental scope expansion | `git diff --check`、changed-surface secret scan | command output and changed-path list |

## Risks and Open Questions

- known_risks:
  - current installed upstream CLI wrapper may lag source (`1.0.18` vs source `1.0.21`); local integration must use a source-matched build before relying on `ensure-tenant` or its help.
  - real SIM/TMS smoke needs their owners' gitignored profiles and runtime resources; code completion does not prove a live ask.
  - the combined `ensure-tenant` profile is a development bootstrap compromise; automatic split/delivery is a later scoped improvement.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `navigator-runtime-provisioning` SKILL。
- 在 scope 内自主决定具体文件、类和实现结构；不得更改 tenant authorization semantics 或增设 final typed principals。
- 如需改变 S1/S2 final permission model、credential delivery policy、Gateway/external/production boundary，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 无 tenant filter 的 ClientApp list 改为同 `upstreamSystemId + upstreamClientAppNamespace` 的候选查询，并逐条复用既有 `isTenantAuthorized`；空授权集仍保持旧的 fail-closed 空结果且不查询。显式 tenant 路径没有改变。CLI help、SKILL 与本地手册明确 bootstrap/control/runtime 分层、combined profile 风险、SIM dev-only 限制和 external/Gateway/Codex 红线。
- changed_paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/repository/ClientAppRepository.java`
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamClientAppManagementService.java`
  - `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/UpstreamClientAppManagementServiceTest.java`
  - `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java`
  - `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
  - `.agents/skills/navigator-runtime-provisioning/SKILL.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/{README.md,workitems/GOV-001-upstream-permission-and-trust-boundary.md,workitems/GOV-001-dev-s1-s2-integration-mvp.md,runbooks/GOV-001-dev-s1-s2-local-integration.md}`
- tests_and_results:
  - `mvn test -pl business-agent-module -am -Dtest=UpstreamClientAppManagementServiceTest,UpstreamClientAppAdminCredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS，21 tests，0 failure/error/skip。
  - `mvn test -pl business-agent-module -am -Dtest=BusinessFunctionRuntimeAuditRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS，2 tests，0 failure/error/skip；`@DataJpaTest` 启动并扫描 33 个 Spring Data JPA repository，新增 derived query 无创建错误。
  - `mvn test -pl navigator-open-sdk -am -Dtest=UpstreamCliTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS，125 tests，0 failure/error/skip。
  - `mvn package -pl navigator-open-sdk -am -DskipTests` — PASS，生成本地 `navigator-open-sdk-1.0.21.jar`。
  - `java -cp "$NAVI_ROOT/navigator-open-sdk/target/navigator-open-sdk-1.0.21.jar:$NAVI_ROOT/tools/navigator-upstream/lib/*" com.foggy.navigator.sdk.cli.UpstreamCli upstream client-app --help` — PASS；确认 current help 的 admin/control/runtime-only 提示，无网络请求、无凭据读取。
  - `git diff --check` 与新增文档 whitespace/link target review — PASS；changed-surface secret-pattern review 未发现真实凭据。
- manual_or_experience_evidence: 只完成源代码、CLI help 和手册的本地验证；未读取 profile、未启动服务、未改 sibling workspace、未执行 SIM/TMS readiness、owner-smoke 或 live ask。
- deviations: 实现中发现空授权集仍会查询 scoped candidates 的防御性问题；已在本合同内补为 early return 和 regression test。未改变授权模型、schema、external/Gateway/Worker route。
- residual_risks:
  - 真实 SIM/TMS readiness、owner-smoke 和 safe ask 需要上游 owner 提供 gitignored profile 及实际资源，尚未执行，不能由本次代码验证替代。
  - `ensure-tenant` 仍将 control 与 runtime 写入同一平台私有 bootstrap profile；runtime-only 交付仍需平台侧人工拆分，自动化拆分不在本 MVP。
  - 目录/Agent/WorkerHost 等 runtime tuple 的实际归属与启动状态未在本次验证；`READY` provisioning response 不等于 ask-ready。
  - CLI 的 profile safety check 只属本地 advisory；若 profile 位于当前项目根目录外，上游 owner 仍须独立确认其 gitignore/secret-store 边界。
  - 无 tenant list 的候选查询按 upstream + namespace 有界；若将来该范围需要分页，应另立 work item，不能改为全表查询。
- readiness: ACCEPTED；仅代表本地 preflight MVP 的代码、CLI 与手册签收，非 live runtime、external 或 production ready。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-20
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/GOV-001-dev-s1-s2-integration-mvp-independent-signoff.md`
- blocking_items: none
- follow_up_required: yes

## References

- architecture / glossary: `GOV-001-upstream-permission-and-trust-boundary.md`
- runbook: `../runbooks/GOV-001-dev-s1-s2-local-integration.md`
- related implementation: `business-agent-module/.../UpstreamClientAppManagementService`, `navigator-open-sdk/.../UpstreamCli`
