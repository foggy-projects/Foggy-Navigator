---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: INT-001
status: REJECTED
canonical: true
execution_mode: ultra
approved_by: project-owner-user-confirmed
approved_at: 2026-07-20
acceptance_record: ../evidence/INT-001-independent-signoff.md
open_questions: []
---

# Delivery Spec: Synthetic Upstream Integration Harness

## Document Purpose

- intended_for: implementation / local-integration / independent-signoff
- purpose: 建立一个可销毁、可重复的内部 synthetic upstream runtime harness，用于在真实 TMS/SIM 联调之前稳定发现和复现 Navigator 的权限、资源归属和运行时链路问题。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/INT-001-synthetic-upstream-integration-harness.md`

## Goal

- version_goal: 降低每次基础权限/runtime 故障都依赖真实上游人工联调的成本，同时不降低真实上游验收门槛。
- target_outcome: 在独立 disposable Navigator 环境中，可由一条安全、显式 opt-in 的 harness 执行 synthetic ClientApp 的 runtime-token → readiness → owner-smoke → static no-tool ask → diagnostics，并稳定证明关键拒绝路径无 task/Worker dispatch。

## Scope

- in_scope:
  - 在 `tools/navigator-upstream` 建立可重复的 synthetic harness、fixture 和零写入 doctor/prepare 路径；每次运行使用唯一 `runId`、隔离端口、数据源、临时 profile、日志和 `temp/test-artifacts/INT-001/<runId>/`。
  - P1 使用 disposable MySQL/MariaDB、Mock LLM、当前源码构建的 Launcher 和专用 synthetic `LANGGRAPH_BIZ` runtime fixture；不复用当前 8112、当前数据库、真实上游 profile 或既有 Physical Worker。
  - 在 disposable 环境中显式 bootstrap synthetic upstream、至少两 ClientApp（同 tenant）及第二 tenant fixture；admin/bootstrap、control、runtime lane 分开保存与投影。
  - 覆盖 runtime-only 正向链路、runtime lane control/admin mutation 拒绝、同 tenant 跨 ClientApp 拒绝、跨 tenant 拒绝，以及 owner/grant/directory readiness-only 拒绝。
  - 复用已有 Mock LLM 的确定性 marker 和现有 E2E 的协议断言；补齐 run cleanup、redaction、失败分类和简短运行手册。
- affected_modules:
  - `tools/navigator-upstream`
  - `tools/mock-llm-service`（优先复用，只有 hermetic P1 所需时才最小修改）
  - `business-agent-module/integration-tests`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - Docker/Compose、当前源码构建产物和本仓 LangGraph Biz Worker runtime；它们只在明确启动的 disposable 目标中使用。

## Non-Goals

- out_of_scope:
  - 真实 `foggy-world-sim` / `tms-x3` 凭据、业务数据、业务 API、profile、资源或最终验收。
  - S1 `INSTANCE_ROOT`、S2 `SAAS_PLATFORM`、S3 onboarding、真实 credential lifecycle、Gateway strict、Worker external、Provider readiness 或 production readiness。
  - BUG-007 的真实 TMS runtime-only profile 安全门和其真实 upstream smoke；本 harness 不得用 synthetic 成功绕过或替代它。
  - Codex runtime/Physical Worker 路由修复或验证。
- do_not_touch:
  - 现有 8112、共享本机数据库、既有 Worker/WorkerHost、TMS/SIM sibling workspace、真实 `.navigator/` profile、账户、凭据或业务数据。
  - 不得通过创建额外 Worker、BizWorkerIdentity、WorkerPool member 或替代 Worker 修复任何 Codex Physical Worker 路由问题。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| P1 采用隔离 disposable stack，不复用当前开发栈 | 动态 E2E/selftest 会创建资源且当前 8112/DB 可能承载真实调试状态 | 启动、停止、清理只能管理 harness 自己创建且可按 cwd/runId 证明归属的进程、容器、卷和文件 |
| MySQL/MariaDB 是 P1 必需项 | H2 不能暴露 BUG-007 类 physical schema 问题 | 不触碰共享数据库；每个 run 有唯一数据库/Compose project/端口命名空间 |
| Mock LLM 仅为 Provider 替身 | 需要确定性 no-tool ask 与诊断 marker | 不模拟 TMS/SIM identity、credential、业务 API 或最终验收 |
| synthetic runtime 使用独立 `LANGGRAPH_BIZ` fixture | 覆盖当前通用 runtime 路径且避免 Codex 路由混淆 | fixture 仅属于 disposable stack，绝不接管或替代既有 Physical Worker |
| 目录 bootstrap 使用专属 disposable directory facade | 当前 ClientApp directory init 会经 `ClaudeWorkerFacade` 调用 Worker 的 `/api/v1/init-directory`；直写数据库或借用 Biz Worker 都会绕过真实目录归属流程 | facade 只实现 loopback `health` 与 `init-directory`，仅为本 run 注册一个同 tenant 的 directory-only Claude Worker；它不提供 ask/task/Codex/Gateway 能力，不创建或加入 BizWorkerIdentity/WorkerPool，也绝不用来修复 Codex 路由 |
| Open API 仅在 isolated target 开启路由 gate | runtime-token 与 ask 需要 `/api/v1/open/**` | `NAVIGATOR_EXTERNAL_ENABLED=true` 仍只表示 route gate；Gateway external 始终 `false`，不代表 Provider/Gateway/production ready |
| 默认零写入与 fail-closed | 防止误对真实环境/配置执行 bootstrap | 只有明确 `--allow-create` 才可创建 synthetic 资源；target/loopback/run ownership 任何不确定均拒绝 |

## Acceptance Criteria

- [x] AC-1: `doctor/prepare` 默认不写入；它验证 source-matched CLI/build、loopback-only disposable target、唯一 runId/workspace、profile mode/内容白名单、Gateway external=false，并拒绝当前 8112、非 loopback、TMS/SIM profile 或不可证明归属的 target。
- [ ] AC-2: P1 以独立 MySQL/MariaDB、Launcher、Mock LLM 和 synthetic `LANGGRAPH_BIZ` fixture 运行；所有启动/停止/cleanup 仅作用于 harness 自己创建的资源，成功和失败均无 secret/profile/容器/卷残留。**Independent signoff failed:** the forced owned parent-TERM receipt is `FAILED_CLEANUP/SIGNAL`; BUG-009 remains required.
- [x] AC-3: runtime-only 子进程以 `env -i` 启动，只接收 `PATH`、`HOME` 与 allow-list 的 `INT001_*` 投影：A 的 tenant/clientApp/runtime key-secret/agent/upstream user/model/directory，及 B/C 的 public target agent ID；不得接收 B/C tenant、ClientApp、credential 或任何 admin/control/runtime token。它实际完成 runtime-token → readiness → owner-smoke → `maxTurns=1` static no-tool ask → terminal/diagnostics，得到精确 `INT001_STATIC_NO_TOOL_<runId>` Mock LLM marker。
- [x] AC-4: runtime credential 的 control/admin mutation、同 tenant 跨 ClientApp、跨 tenant、缺 grant/错误 directory/owner 的请求均 fail closed；每项证实 `taskCreated=false`、`dispatch=false`，并记录稳定 deny 类别或显式环境 blocker。
- [x] AC-5: 不打印、写入或提交 admin/control/runtime key、token、完整 profile、完整 prompt、账号或业务数据；durable evidence 只含 runId、脱敏/匿名 identifier、状态、route kind、deny 分类和命令结果。
- [x] AC-6: 实际执行匹配的 Maven/Node/Python/脚本测试、harness smoke、`git diff --check` 和 changed-surface secret scan；work item、runbook 和 test record 回写精确结果。完成状态最多为 `READY_FOR_SIGNOFF`。

## Contract / Data / Security Constraints

- API or event contract: 不新增生产 Open API 或改变 Navigator 授权/owner/task capability/Gateway/Codex 路由语义。Harness 使用既有公开/测试协议；若需要正式产品 endpoint、权限模型或 schema 变化，设置 `NEEDS_REPLAN`。
- data and migration: disposable target 的 schema/数据只由该 target 生命周期管理；不得对共享/真实 MySQL 执行 DDL、seed、cleanup 或导出。
- compatibility and rollback: 删除 harness 新增的工具和测试即可回退；运行态 cleanup 只删除由 runId 归属的 disposable resources。失败保留仅限脱敏 manifest/诊断摘要。
- permissions and secrets: bootstrap/admin、control、runtime credential 分层。runtime child 采用 allow-list projection / `env -i` 等等价机制；任何多 lane、未知字段、非 `0600` secret carrier 或不能证明 gitignore/owned 的 profile 必须拒绝。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| doctor/target safety | accidental real-environment write | automated target/profile/ownership rejects | focused test output and redacted doctor report |
| disposable lifecycle | shared-state contamination | fresh run start, normal cleanup, forced-failure cleanup | runId, resource ownership, cleanup report |
| positive runtime | critical path regression | runtime-token → readiness → owner-smoke → no-tool ask → diagnostics | marker, terminal state, route kind; no body/secret |
| negative isolation | cross-owner/tenant dispatch | runtime/control, same-tenant ClientApp, cross-tenant, binding deny | deny category plus taskCreated/dispatch=false |
| hygiene | credential leak / scope drift | `git diff --check`, changed-surface secret scan | exact commands/results |

## Risks and Open Questions

- known_risks:
  - 本机 Docker/Compose、Java build 或 dedicated Biz fixture 可能不可用；这必须报告为环境 blocker，不能退回共享 8112 或真实 profile。
  - 现有 E2E API 可能缺少某个无副作用的 deny assertion；不得为 harness 猜测或放宽授权，应复用实际契约或标记 `NEEDS_REPLAN`。
  - 自动 cleanup 必须避免删除同名但无 run ownership 证明的资源。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、`navigator-runtime-provisioning` SKILL 及相关现有 selftest/E2E/Mock LLM 实现。
- 在 scope 内自主选择合理文件、编排和测试结构；优先复用现有构件，避免无出口的通用平台。
- 如需真实上游输入、共享栈写入、权限语义/Worker route/Gateway/external/production 改变、Codex route 工作或跨仓更改，立即设为 `NEEDS_REPLAN` 并停止扩展。
- 不得启动、停止或修改任何无法通过 command line/cwd/runId 证明为 harness-owned 的进程或服务。
- 完成后回填 `Implementation Result`、精确验证、偏差和残余风险，将状态设为 `READY_FOR_SIGNOFF`；不得自行写 `ACCEPTED`。

## Implementation Result

> INT-001 基础 harness 已完成。首次 disposable run 发现的 owner-context false deny 已以独立 BUG-008 修复；本 work item 仅记录 harness 与其 post-fix runtime evidence，不把该修复混同为真实上游验收。

- implementation_summary: 已建立一次性 disposable harness、隔离 bootstrap、runtime-only allow-list audit、redacted receipt、fixture、运行手册和离线安全回归；bootstrap 要求 harness lifecycle lock 与只读 `verify-running` ownership proof，避免直接 carrier 调用绕过。BUG-008 修复后，fresh owned exercise 完成 runtime-token、readiness、owner-smoke、static no-tool ask、terminal/diagnostics、七项 deny probe 和 owned cleanup。
- changed_paths:
  - `tools/navigator-upstream/scripts/synthetic-upstream-{harness,bootstrap,runtime-audit}.sh`
  - `tools/navigator-upstream/fixtures/synthetic-integration/`
  - `business-agent-module/integration-tests/{src,tests,vite*.ts,package.json}`
  - `docs/version-tracker/1.4.3-SNAPSHOT/{workitems,runbooks,test-records}`
- tests_and_results:
  - PASS: `env -u INT001_SYNTHETIC_UPSTREAM_HARNESS -u INT001_RUNTIME_PROBE npm run test:synthetic` — 76 passed, 1 skipped；只执行离线 safety/config/receipt contract，不连接 8112 或任何真实上游。
  - PASS: `npm run typecheck`（`business-agent-module/integration-tests`）。
  - PASS: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v test_directory_facade test_biz_ingress_proxy`（synthetic fixture，13 tests）。
  - PASS: `bash -n` 覆盖三个 `synthetic-upstream-{harness,bootstrap,runtime-audit}.sh` 脚本。
  - PASS: fresh owned disposable exercise，runId `int001-bug008-20260721-a1b2c3`：runtime token/readiness/owner-smoke 为 `PASS`；positive ask `taskCreated=true`、Mock LLM submission `1`、Biz ingress `1`；七项 deny 均 `taskCreated=false`、model submission/ingress `0`；cleanup receipt 为 `CLEANED`。
  - PASS: BUG-008 focused Maven 回归、`git diff --check` 与 changed-surface secret scan；详见 `BUG-008` 和本项 test record。
- manual_or_experience_evidence: runId `int001-bug008-20260721-a1b2c3` 的 root-level redacted receipt 为 `PASS`、`secretsRedacted=true`，cleanup receipt 为 `CLEANED`、`secretsRedacted=true`。private carrier 未读取；未接触真实 TMS/SIM、共享 8112/数据库、真实 profile 或既有 Worker。
- deviations: 首次 exercise 在 `OpenApiController -> BusinessAgentTaskService.prepareOpenApiTaskScopedToken -> LanggraphBusinessAgentWorkerTaskLauncher` 的第二次 physical identity lookup 前丢失 server-resolved ClientApp `upstreamSystemId`。这不是 WorkerPool/member/额外 Worker 的环境缺失；已按独立 BUG-008 的最小 trusted-source 修复并重新执行 fresh run。INT-001 未为此创建或改变 Codex Worker、BizWorkerIdentity、Pool member 或 Gateway。
- residual_risks: canonical `PLATFORM/platform` physical identity 是现有共享基础设施兼容例外；只有 `UPSTREAM_SYSTEM/<id>` identity 走 exact upstream owner match。完整 `mvn test -pl launcher -am` 仍因共享脏树中未归属本项的 termination route-catalog drift 失败，见 test record；该失败没有被掩盖或在本项内修复。任何成功都不表示 real SIM/TMS acceptance、Gateway external、Provider ready 或 production ready。
- readiness: REJECTED；2026-07-21 独立签核确认 AC-2 的 forced-SIGNAL cleanup 失败。正常 disposable replay 仍是有效诊断证据，但不能替代成功的 forced-failure cleanup proof。

## Acceptance Status

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-21
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/INT-001-independent-signoff.md`
- blocking_items: `INT-001 AC-2 forced-SIGNAL cleanup`, `BUG-009`
- follow_up_required: yes

## References

- existing selftest: `tools/navigator-upstream/scripts/navigator-provisioning-selftest.ps1`
- Mock LLM: `tools/mock-llm-service/README.md`
- existing E2E: `business-agent-module/integration-tests/tests/02-upstream-tenant-client-app-provisioning.test.ts`, `03-langgraph-biz-worker-mock-llm.test.ts`, `04-openapi-task-diagnostics-evidence-contract.test.ts`
- related MVP: `GOV-001-dev-s1-s2-integration-mvp.md`
- related live-runtime blocker: `BUG-007-task-token-function-scope-schema-contract.md`
- discovered owner-context blocker: `BUG-008-openapi-upstream-physical-langgraph-identity-context.md`
