---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-008
status: ACCEPTED
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-20
accepted_by: Independent Signoff Reviewer (Codex)
accepted_at: 2026-07-21
acceptance_record: ../evidence/BUG-008-independent-signoff.md
open_questions: []
---

# Delivery Spec: Open API upstream physical LangGraph identity context

## Document Purpose

- intended_for: project-owner decision / ultra-implementation / independent-signoff
- purpose: 将 INT-001 disposable exercise 发现的 physical `LANGGRAPH_BIZ` owner-context 丢失单列，避免以 WorkerPool、额外 Worker 或宽松 fallback 规避授权缺口。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md`

## Goal

- version_goal: 让已认证的 ClientApp runtime Open API 路径在既有 owner/binding 模型下，对 `UPSTREAM_SYSTEM` physical LangGraph Biz identity 只能解析其 exact upstream system 所拥有的 identity；既有 canonical `PLATFORM/platform` compatibility 在 upstream scope 到达 lookup 时仍可被接受。
- target_outcome: 对同一 upstream system 的合法 ClientApp，Open API task capability 预绑定可完成 physical identity visibility lookup；缺失、不同或调用方伪造的 upstream scope 仍 fail closed，且不创建 task 或 Worker dispatch。

## Scope

- in_scope:
  - 在 server-side task capability preparation choke point 从已认证的 active ClientApp 解析 `upstreamSystemId`，并在 Worker resolve 前覆盖写入 internal launch selection context。
  - 保持既有 canonical `PLATFORM/platform` compatibility 路径（upstream scope 到达 lookup 时仍可被接受）；对 `UPSTREAM_SYSTEM` identity 只接受 exact owner match。
  - 形成自动化回归：server-resolved scope 覆盖任意 caller/internal supplied value、exact upstream physical route 可解析、missing/mismatch scope 拒绝且无 task/dispatch。
  - 修复后以 fresh disposable INT-001 exercise 验证正向 marker 和全部 deny probe，再回填两个 work item 的脱敏证据。
- affected_modules:
  - `business-agent-module`
  - `addons/langgraph-biz-worker`
  - `business-agent-module/integration-tests`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: INT-001 owned disposable stack only；不使用真实 TMS/SIM profile、8112、共享数据库或既有 Worker。

## Non-Goals

- out_of_scope:
  - 重新设计 S1/S2 owner model、跨 upstream/tenant/ClientApp 授权、runtime credential、task capability、Gateway principal 或 external/production gate。
  - 任何 Open API body/header/metadata 新字段、ClientApp runtime credential claim 扩张或客户端自报 upstream scope。
  - 创建、改绑、借用 WorkerPool/member、额外 Worker 或 BizWorkerIdentity；尤其不得以此修复 Codex Physical Worker 路由。
  - Codex、Worker Gateway external、Worker external、真实上游业务调用或 sibling workspace 改动。
- do_not_touch:
  - 真实 TMS/SIM、共享 8112/数据库、真实 `.navigator` profile、凭据、既有 Worker/WorkerHost/Pool/identity 和不相关 GOV/BUG 脏工作树改动。

## Confirmed Facts and Proposed Decision

| Item | Evidence / rationale | Constraint |
|---|---|---|
| Open API runtime ClientApp/tenant 来自已验证 runtime access token | Controller 在 route/resource resolve 前取得 credential；调用方 worker metadata 随后被移除并由 server resolver 重建 | 不信任 request body/header/metadata 中的 upstream scope |
| `UPSTREAM_SYSTEM` physical `LANGGRAPH_BIZ` identity visibility 需要 exact owner | identity service 对 `UPSTREAM_SYSTEM` 要求同 type、同 owner id；既有 canonical `PLATFORM/platform` 是独立 compatibility exception | 不将缺失 scope 当作任意 upstream 或通用 allow |
| 当前 task capability preparation 丢弃 active ClientApp entity | 它重新验证 ClientApp 后只写 tenant/clientApp/user/skill/session/model，没有写 upstream scope | 这是 `UPSTREAM_SYSTEM` route current false deny 的直接根因 |
| Proposed minimal behavior | server 在 Worker resolve 前无条件以 active ClientApp 的 normalized `upstreamSystemId` 覆盖 internal selection context | `UPSTREAM_SYSTEM` 仅 exact match 才能 allow；既有 `PLATFORM/platform` compatibility 在 upstream scope 到达 lookup 时仍可接受；`UPSTREAM_SYSTEM` mismatch rejects |

## Acceptance Criteria

- [x] AC-1: only server-resolved active ClientApp `upstreamSystemId` can control `UPSTREAM_SYSTEM` physical `LANGGRAPH_BIZ` visibility selection; a pre-populated or caller-controlled value cannot influence it.
- [x] AC-2: an exact `UPSTREAM_SYSTEM/<id>` identity resolves for a ClientApp owned by `<id>`; a different `<id>` and absent/invalid `UPSTREAM_SYSTEM` owner context remain rejected before task capability persistence or Worker dispatch.
- [x] AC-3: existing canonical `PLATFORM/platform` compatibility remains covered, including when upstream scope reaches the lookup; no pool/member/extra identity is created or used.
- [x] AC-4: focused module/addon tests and a fresh INT-001 disposable exercise pass: static positive marker plus all deny probes report no task creation/dispatch; Gateway external remains false.
- [x] AC-5: no Open API, credential, schema, config, external, Gateway or production contract is widened; changed-surface secret scan and `git diff --check` pass.

## Contract / Data / Security Constraints

- API or event contract: no request/response/schema change. The owner context is server-internal and recomputed from the authenticated ClientApp immediately before Worker resolution.
- data and migration: no migration or persisted scope expansion. Existing task token fields retain their current meaning.
- compatibility and rollback: blank upstream scope preserves existing canonical platform compatibility; that compatibility is not blank-scope-only and may remain accepted when upstream scope reaches the lookup. Rollback removes the server-internal propagation and returns to current false-deny behavior without data cleanup.
- permissions and secrets: fail closed; exact owner match only. `NAVIGATOR_EXTERNAL_ENABLED=true` stays a disposable Open API routing gate only. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false` throughout this work and does not participate in the repair.

## Test and Evidence Obligations

| Item | Risk | Required validation | Required evidence |
|---|---|---|---|
| server-derived scope | critical spoof/cross-owner escalation | focused service test captures the launcher selection context after active ClientApp lookup | exact owner and overwrite assertions |
| physical identity visibility | critical false allow/deny | LangGraph launcher tests for exact upstream, mismatch, blank platform compatibility | Maven output |
| Open API route composition | critical missing propagation | controller/integration test proves runtime credential -> resolver -> preparation uses server-derived context | Maven output |
| disposable runtime | critical real-chain regression | fresh INT-001 exercise after code tests | redacted marker/status/deny evidence only |
| hygiene | major secret/scope drift | `git diff --check`, changed-surface secret scan | commands/results |

## Bug Context

- bug_source: acceptance-found
- severity: major
- environment: INT-001 owned disposable local runtime; synthetic upstream-owned `LANGGRAPH_BIZ` physical identity; Gateway external disabled.
- current_behavior: readiness and owner-smoke pass, but positive Open API ask fails before task creation because the second identity lookup sees no ClientApp upstream owner context and falls back to platform-only compatibility.
- expected_behavior: a runtime-authenticated ClientApp may use only an exact matching upstream-owned physical identity that prior resource resolution has already selected; wrong/missing/malicious scope must remain denied.
- reproduction_steps: execute the INT-001 disposable bootstrap and runtime audit using its synthetic upstream fixture; observe positive ask rejection with `taskCreated=false` after readiness/owner-smoke.
- reproduction_status: confirmed
- existing_evidence: INT-001 redacted disposable audit summary; source trace of Open API selection and LangGraph physical lookup; no private carrier was read.
- existing_tests: launcher physical route unit test exists; current Open API preparation test does not assert server-derived upstream scope.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - This changes a valid exact-owner route from false deny to allow; it must be explicitly approved as a bug correction and independently signed off.
  - If a controller-level workaround or caller-provided field is chosen instead, it would weaken the trusted-source boundary and is not acceptable.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、`AGENTS.md`、`CLAUDE.md`、`navigator-runtime-provisioning` SKILL 和相关现有测试；先形成可失败回归，再做最小修复。
- 不得通过 Pool/member/额外 Worker/identity、Codex 路径或配置 gate 绕过。
- 如发现需要改变 owner model、Open API contract、credential lane、Gateway/external/production 边界，设为 `NEEDS_REPLAN` 并停止。
- 完成后记录具体改动、命令、脱敏 evidence 与残余风险，状态最多为 `READY_FOR_SIGNOFF`；不得自行写 `ACCEPTED`。

## Implementation Result

- implementation_summary: `BusinessAgentTaskService` re-reads the authenticated active ClientApp at the task-capability choke point, normalizes its `upstreamSystemId` and unconditionally overwrites the internal launch selection before Worker resolution. `LanggraphBusinessAgentWorkerTaskLauncher` passes a nonblank scope only as exact `UPSTREAM_SYSTEM/<id>` to the second physical identity lookup; blank scope keeps the existing canonical `PLATFORM/platform` compatibility, which may also remain accepted when upstream scope reaches the lookup. The normal `createTask` launch construction carries the same server-resolved scope as consistency coverage; it does not expose a caller field or widen authorization.
- changed_paths:
  - `business-agent-module/.../BusinessAgentTaskService.java` and `.../BusinessAgentWorkerTaskLaunchRequest.java`
  - `addons/langgraph-biz-worker/.../LanggraphBusinessAgentWorkerTaskLauncher.java` and `LanggraphWorkerService.java`
  - focused service/launcher tests and `addons/claude-worker-agent/.../OpenApiControllerMessageMappingTest.java`
  - this work item, INT-001 work item, runbook and redacted test record
- tests_and_results:
  - PASS: `mvn -q -pl business-agent-module -am -Dtest=BusinessAgentTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
  - PASS: `mvn -q -pl addons/langgraph-biz-worker -am -Dtest=LanggraphBusinessAgentWorkerTaskLauncherTest,LanggraphWorkerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
  - PASS: `mvn -q -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest#askAgent_doesNotForwardCallerUpstreamSystemIdIntoServerResolvedWorkerSelection -Dsurefire.failIfNoSpecifiedTests=false test`。
  - PASS: fresh INT-001 owned disposable exercise `int001-bug008-20260721-a1b2c3`；positive ask 完成，七项 deny 均无 task/dispatch，Gateway external 保持 `false`。
  - PASS: `git diff --check` 与 29-file changed-surface secret scan（无明文 secret；唯一初筛项为 compose 环境变量引用）。
- manual_or_experience_evidence: Open API composition regression 向 caller metadata 注入 `upstreamSystemId` 和 `workerId`，断言它们不进入 selection；server route/root/skill/physical Worker 仍保留。动态证据只读取 root-level redacted receipts；未访问真实 TMS/SIM、共享 8112/数据库、真实 profile、既有 Worker 或 `private/` carrier。
- deviations: 无 Open API request/response、credential lane、schema、Gateway/external 或 production 合同偏离。普通 `createTask` 的同源 scope propagation 是授权一致性覆盖，非额外权限能力。
- residual_risks: `PLATFORM/platform` 是既有 physical compatibility exception，不能把本修复描述为所有 physical route 都必须 upstream-owned。完整 launcher reactor 仍暴露共享脏树中不属于本 BUG 的 termination route-catalog drift；相关 focused suites 和 disposable exercise 已通过。此项不证明 Codex route、Worker Gateway external、Provider 或 production readiness。
- readiness: ACCEPTED_WITH_RISKS；2026-07-21 独立签核确认 server-derived exact upstream identity 修复。INT-001 的 forced-SIGNAL cleanup 失败已另立 BUG-009，不被计为本 BUG 的通过证据或修复完成。

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-21
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-008-independent-signoff.md`
- blocking_items: none for BUG-008; `BUG-009` remains an INT-001 harness follow-up
- follow_up_required: yes

## References

- discovered by: `INT-001-synthetic-upstream-integration-harness.md`
- related owner model: `GOV-001-upstream-permission-and-trust-boundary.md`
- related local integration MVP: `GOV-001-dev-s1-s2-integration-mvp.md`
