---
acceptance_scope: feature
version: 1.4.3-SNAPSHOT
target: ARCH-001
status: rejected
decision: rejected
signed_off_by: Independent Signoff Reviewer (Codex)
signed_off_at: 2026-07-31
reviewed_by: Independent source, contract and focused-validation audit
blocking_items:
  - ARCH-001-B1-owner-vertical-chain-not-integrated
  - ARCH-001-B2-receipt-admission-not-recoverable
  - ARCH-001-B3-terminal-authority-and-cleanup-incomplete
  - ARCH-001-B4-worker-v1-cross-runtime-contract-broken
  - ARCH-001-B5-writer-proof-and-enforced-fixture-not-representative
  - ARCH-001-B6-schema-validation-obligation-not-met
follow_up_required: yes
evidence_count: 12
assurance_level: elevated
---

# ARCH-001 Independent Delivery Signoff

## Document Purpose

- intended_for: project owner / ARCH-001 repair owner / future independent reviewer
- purpose: 对提交 `fac98161d5e59b54d8f605061af1adae6f4b6415` 的 ARCH-001
  source implementation 形成独立、可复核的签收结论。

## Background

- delivery_spec:
  `../workitems/ARCH-001-unified-session-task-lifecycle-owner.md`
- reviewed_range:
  `d3eb7f76d31d6dfd2a78009d30caff9f8307284d..fac98161d5e59b54d8f605061af1adae6f4b6415`
- candidate_commit: `fac98161d5e59b54d8f605061af1adae6f4b6415`
- candidate_parent: `0b0b2f1659317eab90c7137eaafe5b0cb67d568a`
- range_note: 指定 baseline 到 candidate 还包含先行的
  `0b0b2f1659317eab90c7137eaafe5b0cb67d568a`（仅根 `AGENTS.md` 一行流程说明）；
  未将其视为 ARCH-001 产品实现证据或 blocker。
- signoff_scope: source、additive migration、Java/Node Worker contract、repo-owned
  fixture 和 public compatibility。真实 controller/process、首次 non-fixture
  `ENFORCED`、live SIM、部署、重启和发布均不在本次签收范围。
- assurance_level: `elevated`，沿用 canonical spec；terminal authority、token deny、
  cross-runtime replay 和 writer fence 均属于不可豁免项。

## Final Decision

- decision: `rejected`
- rationale: focused tests 能证明若干孤立 helper/store 行为，但生产调用链没有接通
  lifecycle owner；同时已确认 terminal conflict 仍产生 canonical terminal effect、
  Java/Node ACK method 不一致、receipt admission 存在不可恢复提交窗口、cleanup 无实际
  participant action，以及 writer-proof fixture 未使用 production fencing chain。这些是
  已确认的 source defects，不是缺少真实 activation evidence。
- blocking_items: B1–B6，见下。
- follow_up: 在原 ARCH-001 scope 内修复后重新置为 `READY_FOR_SIGNOFF`，重新执行独立
  signoff；不得据此开启真实 activation。

## Contract Conformance

| Contract item | Result | Independent evidence |
|---|---|---|
| AC-1/2 canonical reducer and terminal authority | **fail** | `TaskLifecycleFact` 不携带 Worker identity、provider Task、operation、mode 或 binding；两个冲突 terminal facts 实际得到 `TERMINAL + EVIDENCE_CONFLICT` 并生成 terminal effect。 |
| AC-3/4/22 receipt idempotency and convergence | **fail** | receipt/intent 先于 Task/provider preflight 提交；outbox 固定为 suppressed SHADOW 且无 handler，提交后崩溃由 same-ID replay 只读返回，不会补 dispatch。测试绕过了实际 coordinator。 |
| AC-5/6 tombstone and cleanup | **fail** | terminal commit 未被任何产品路径调用；所有 REQUIRED plan 均为 `PENDING`，仓库中没有 `TerminalCleanupAction` 实现，也没有 cleanup scheduler/handler 调用。 |
| AC-7/8/10 offline, Sentinel and foreground lane | **fail** | lane observation 发生在 legacy provider dispatch 之后且返回值被忽略；offline gate、Sentinel service 和 lane release 无产品调用点；Codex lifecycle adapter 未被构造。 |
| AC-9/15/19/20/25/26 Worker v1 | **fail** | Java ACK 使用 POST、Node route 只接受 PUT；Java 不发送 lifecycle context/status fence；query 在 `PREPARED` 后直接调用 provider且不写 `EFFECT_STARTED/RESULT_OBSERVED`，ENFORCED duplicate 在 PREPARED 也直接结束；Worker terminal facts 从未 append。 |
| AC-11/14 deferred authority and safety boundary | pass | 未新增 admin logical-close authority；本次验收未操作历史 Task、sibling repo、真实进程或外部业务数据。 |
| AC-12/17/21 public compatibility | partial | focused typed/Open SDK tests通过，BUG-035 disabled one-shot 未见回归；但 owner projection/legacy mapping 没有接入，因此完整 AC-12/17 不能通过。 |
| AC-13 additive migration/readiness | **fail** | SQL 是 additive，但 migration test 只统计 12 个 `CREATE TABLE`；readiness test 仅创建一列 dummy tables，未执行 forward schema、JPA `validate` 或 rollback fixture。 |
| AC-16/24/27/28 writer proof | **fail** | effect authorization 先锁 outbox 再锁 proof，未锁/核验 aggregate reference、generation 或 inventory digest；release predicate 是 caller boolean；测试是顺序 Mockito，不是同 proof row 的并发 CAS。 |
| AC-18 module direction | partial | Maven 依赖方向保持，但 SPI/adapter/participant 未形成 owner-controlled vertical chain，不能证明 sole authority。 |
| AC-23 exact `codex-biz-worker` closure | **fail** | 新测试只断言 facade `supports=true`；真实 completion-readiness method 仍拒绝非 `CODEX_PROVIDER_TYPE`，未执行 readiness/terminate/reconcile vertical flow。 |
| AC-29 normalized blocker vocabulary | partial | isolated reducer vocabulary test通过；未接入 normalized Worker facts，且 terminal conflict 行为违反 authority quarantine。 |

### Reproducible source anchors

| Finding | Candidate source anchors |
|---|---|
| B1 | `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java:206-213,359-362,1028-1043`；legacy dispatch 先发生，lane observation 后发生且 decision 被忽略。 |
| B2 | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/RuntimeTaskClosureService.java:160-213`；receipt/intent transaction 在 preflight 与 provider call 前独立完成。`session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskTerminationIntentRecorder.java:23-40` 固定写 suppressed SHADOW payload。 |
| B3 | `session-module/src/main/java/com/foggy/navigator/session/lifecycle/TaskLifecycleFact.java:5-10`、`TaskLifecycleReducer.java:52-75,97-104`、`TerminalCleanupStepExecutor.java:33-37`。 |
| B4 | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/lifecycle/CodexWorkerLifecycleHttpAdapter.java:93-106` 使用 POST；`tools/codex-agent-worker/src/routes/lifecycle.ts:120-130` 只注册 PUT。`tools/codex-agent-worker/src/routes/query.ts:209-236,279-307` 显示 PREPARED duplicate early return 与 provider call 前缺少 effect-start transition。 |
| B5 | `session-module/src/main/java/com/foggy/navigator/session/lifecycle/WriterExclusivityProofService.java:68-80,92-94`；`session-module/src/test/java/com/foggy/navigator/session/lifecycle/IsolatedEnforcedLifecycleContractTest.java:30-65` 使用独立 `fixture_proof` 表。 |
| B6 | `session-module/src/test/java/com/foggy/navigator/session/lifecycle/LifecycleMigrationContractTest.java:12-25` 仅做 SQL 文本检查；`session-module/src/test/java/com/foggy/navigator/session/lifecycle/LifecycleSchemaReadinessTest.java:28-44` 仅用单列 dummy table name。 |

## Blocking Findings

### B1 — Owner vertical chain was not integrated

`TaskDispatchFacade` 仅在 legacy create/resume 已经完成后调用
`observeLifecycleLane`。产品源码中没有外部调用
`TaskLifecycleShadowService.ingest`、`TaskTerminalCommitService.commit`、
`TerminalCleanupHandler.resume`、`WorkerLifecycleSentinelService.reconcile`、
`OfflineCommandGate.evaluate`、`LifecycleEnrollmentGate.evaluate` 或
`WriterExclusivityProofService.authorizeEffect`；`CodexWorkerLifecycleHttpAdapter`
也没有 bean/factory call site。typed reconciliation 继续读取
`RuntimeStateAuditService`/legacy receipt，而不是 owner snapshot/cleanup plan。

影响：Source Slice 1–8 只是若干 component/unit fixture，不能形成
`Worker fact -> reducer -> canonical terminal -> cleanup -> typed TERMINAL` 的产品链。

所需修复：

1. 建立实际 SHADOW ingestion/Sentinel scheduling、normalized fact validation 和 parity。
2. 在所有 create/resume ingress 的接受前原子执行 lane/offline gate。
3. 接通 terminal decision、同步 tombstone、cleanup scheduler/actions、lane release 和
   typed owner projection。
4. 以一条真实 repo-owned vertical integration test 证明上述链路，而不是分别调用纯
   helper。

### B2 — Receipt-enabled admission can still become accepted without convergence

`RuntimeTaskClosureService` 在 Task fields、confirm ID、ownership、Worker 和 provider
preflight 之前调用 acceptance coordinator，并传入 `providerType=null`。recorder 只写一个
固定 `SHADOW/executionSuppressed` outbox；它不保存 exact provider/worker binding，也没有
执行 handler。transaction commit 后到同步 provider call 之间崩溃时，same-ID duplicate
进入 `replayTermination`，不会消费 outbox或重新 dispatch，因而仍可永久
`ACCEPTED/PROCESSING`。

新增 typed test 使用三参数 compatibility constructor，使
`acceptanceCoordinator=null`，并 mock 旧
`beginTaskOperationIdempotent`；它没有执行或验证 production atomic coordinator。

影响：AC-3、AC-4、AC-22 失败，并保留了本架构要消除的 accepted-without-convergence
窗口。

所需修复：先完成 exact Task/provider/worker/enrollment preflight，再在同 transaction
写 public receipt、owner operation/facts 和可恢复的 exact effect outbox；由幂等 handler
消费 outbox。增加 commit-success-before-dispatch crash、duplicate same-ID、outbox
redelivery、receipt/intent/outbox failure injection。

### B3 — Terminal authority and cleanup cannot satisfy the security gate

`TaskLifecycleFact` 只有 fact ID、type、sequence、outcome，reducer 无法验证 exact Worker
identity、provider Task、operation、mode 或 binding。冲突 terminal evidence 虽设置
`EVIDENCE_CONFLICT`，仍保留首个 outcome、置为 `TERMINAL` 并生成 terminal commit effect。

即使手工调用 terminal service，plan factory 把 terminal tombstone、token revoke、
compatibility projection 和 accepted receipt 均标为 REQUIRED；commit service又把所有
REQUIRED checkpoint 置为 `PENDING`。仓库没有任何 `TerminalCleanupAction`
implementation，因此 cleanup executor 对首个 REQUIRED step 即报
`TERMINAL_CLEANUP_ACTION_MISSING_*`。token revoke、legacy projection、receipt checkpoint、
derived registration=false 和 lane release均没有实现链。

影响：AC-2、AC-5、AC-6 及不可豁免 token/authorization safety gate 失败。

所需修复：只允许 validated normalized fact 形成 terminal candidate；conflict 必须阻止
terminal commit。同步 tombstone 在 commit transaction 内直接完成对应 checkpoint；实现并
接线 token/projection/receipt participant、restart-safe runner、cleanup-complete fact 和
lane release。

### B4 — Codex Worker lifecycle v1 is not an executable Java/Node contract

已实际复现 Java/Node ACK method drift：Java adapter 发 POST，Node 只注册 PUT；repo-owned
fixture返回 `POST=404, PUT=200`。Java adapter只提供 probe/inventory/ack，未实现
events/status/command，也未被构造；Java产品源码没有 `lifecycle_context` command。

Node query 会持久 `PREPARED` 后直接调用 `runQuery`，没有调用
`markEffectStarted`；ENFORCED duplicate 无论 prior phase 是否 PREPARED 都直接返回并关闭
SSE。result/terminal path 不调用 `appendFact` 或写 `RESULT_OBSERVED`。因此 crash 后既不能
安全 continuation，也不能由 Worker facts 驱动终态。

此外 facade test 只断言 `supportsCompletionReadiness=true`，而
`CodexTaskService.inspectRuntimeCompletionReadiness` 仍对
`codex-biz-worker` 返回 `RUNTIME_COMPLETION_READINESS_UNSUPPORTED`。

影响：AC-8、AC-9、AC-15、AC-19、AC-20、AC-23、AC-25、AC-26 失败。

所需修复：冻结并实现同一 method/path/header/envelope；Java发送 exact context并能 fenced
reread；query/abort 在 provider call 前耐久 `EFFECT_STARTED`，完成后耐久 disposition/fact；
PREPARED continuation 与 later-phase no-replay 分开；增加 actual Java adapter ↔ Node
router、query/abort phase、restart/response-loss 和 exact codex-biz vertical tests。

### B5 — Writer proof and Slice 8 fixture do not exercise the approved fence

`authorizeEffect` 的锁序是 outbox → proof，未锁 aggregate reference，且未检查
generation、inventory digest、reference exact binding 或 effect class/claim。reference
release 只相信 caller boolean；proof release 检查的是全库 unfinished outbox count而不是
exact proof。

所谓 Slice 8 fixture 创建独立 `fixture_proof(proof_id,status,refs,unfinished_outbox)`
表，直接 update 数字后调用纯 enrollment gate；它不使用 migration schema、
`WriterExclusivityProofService`、production repositories、outbox handler、Task/Session/
Worker references或 Codex Worker。两条 proof race test 只是顺序 Mockito call，不是规范
要求的同一 proof row lock/CAS 并发顺序。

影响：AC-16、AC-24、AC-27、AC-28 失败；fixture isolation 本身保持，但不能作为首次
ENFORCED source evidence。

所需修复：按 proof → exact reference → outbox 固定锁序和 conditional state transition
实现；校验 generation/inventory/reference/effect claim；以真实 JPA schema和并发事务测试
两种线性化顺序，并让 isolated vertical fixture走实际 enrollment/effect/quarantine chain。

### B6 — Migration validation is only lexical

forward SQL 本身为 additive，但测试仅检查没有若干字符串及 `CREATE TABLE` 数量；
readiness fixture 创建 12 张只有 `fixture_id` 的表即可通过。没有执行 forward SQL、
Hibernate/JPA `validate`、索引/约束核验或 rollback-floor fixture。

影响：AC-13 的 source must-pass evidence不足。

所需修复：在 repo-owned disposable compatible database上实际应用 forward migration，
运行 entity schema validation和关键 unique/index contract；在无 ENFORCED aggregate 的
fixture验证 rollback，在存在 enforcement marker 时验证 rollback gate fail closed。真实
shared/production MySQL 仍留在 separately authorized activation。

## Focused Validation Actually Run

1. `mvn -q -pl session-module -am -Dtest='*Lifecycle*Test,TerminalCleanupPlanFactoryTest,TaskTerminalCommitServiceTest,TerminalCleanupStepExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false test`
   — exit 0。
2. `cd tools/codex-agent-worker && npm run typecheck && node --import tsx --test tests/lifecycle-contract.test.ts tests/lifecycle-route.test.ts tests/health.test.ts`
   — exit 0；16/16。
3. `mvn -q -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
   — exit 0。
4. `mvn -q -pl addons/codex-worker-agent -am -Dtest=CodexWorkerFacadeRuntimeClosureProviderTest,CodexWorkerLifecycleHttpAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`
   — exit 0。
5. `mvn -q -pl navigator-open-sdk -am test`
   — exit 0。
6. `git diff --check d3eb7f76d31d6dfd2a78009d30caff9f8307284d..fac98161d5e59b54d8f605061af1adae6f4b6415`
   — exit 0。
7. Repo-owned ephemeral ACK route probe against `createLifecycleRouter`
   — `{"post_status":404,"put_status":200}`。
8. JShell reducer conflict probe against compiled candidate classes
   — `phase=TERMINAL, conflict=EVIDENCE_CONFLICT, effects=1`。
9. Production call-site scans for lifecycle owner services, Java
   `lifecycle_context`, Node `appendFact/markEffectStarted`, and
   `TerminalCleanupAction` implementations — confirmed the missing links described above。

两条补充探针的精确命令为：

```bash
cd tools/codex-agent-worker
node --import tsx --input-type=module -e "import express from 'express'; import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path'; import { once } from 'node:events'; import { createLifecycleRouter } from './src/routes/lifecycle.js'; import { LifecycleStore } from './src/lifecycle/store.js'; const dir=fs.mkdtempSync(path.join(os.tmpdir(),'arch001-ack-probe-')); const token='fixture-token'; const store=LifecycleStore.open({directory:dir,physicalWorkerId:'fixture-worker',workerToken:token,instanceEpoch:'fixture-epoch'}); const app=express(); app.use(express.json()); app.use(createLifecycleRouter({store,workerToken:token})); const server=app.listen(0,'127.0.0.1'); await once(server,'listening'); const port=server.address().port; const headers={Authorization:'Bearer '+token,'X-Navigator-Expected-Physical-Worker-Id':'fixture-worker','X-Navigator-Expected-State-Generation':store.identity.state_generation,'Content-Type':'application/json'}; const body=JSON.stringify({schema:'NAVIGATOR_WORKER_LIFECYCLE_V1',physical_worker_id:'fixture-worker',state_generation:store.identity.state_generation,through_sequence:0}); const post=await fetch('http://127.0.0.1:'+port+'/api/v1/lifecycle/ack',{method:'POST',headers,body}); const put=await fetch('http://127.0.0.1:'+port+'/api/v1/lifecycle/ack',{method:'PUT',headers,body}); console.log(JSON.stringify({post_status:post.status,put_status:put.status})); await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve())); fs.rmSync(dir,{recursive:true,force:true});"
```

```bash
jshell --class-path session-module/target/classes:navigator-spi/target/classes <<'EOF'
import com.foggy.navigator.session.lifecycle.*;
import java.util.*;
var decision = new TaskLifecycleReducer().recompute("fixture-task", List.of(TaskLifecycleFact.terminal("terminal-1", 1, TaskTerminalOutcome.COMPLETED), TaskLifecycleFact.terminal("terminal-2", 2, TaskTerminalOutcome.FAILED)), Set.of(), "fixture-policy", 0);
System.out.println("phase=" + decision.snapshot().canonicalPhase() + ", conflict=" + decision.snapshot().conflictState() + ", effects=" + decision.requiredEffects().size());
/exit
EOF
```

上述 passing tests 证明 helper/store 的局部行为和 public DTO 编译兼容，不证明 AC 已覆盖。
没有独立 durable log 可验证 Implementation Result 声称的 final launcher full run，因此该
自报结果未作为签收依据。已确认的源码 blocker 足以决定 rejection，继续运行 launcher
全量不会改变结论。

## Evidence Sufficiency and Omitted Validation

- evidence_sufficiency: sufficient for `rejected`。缺陷可由 source和 repo-owned focused
  probes直接确认，不依赖真实环境。
- full launcher reactor: not run；已确认的 non-waivable defects 不会因全量编译通过而
  消失。
- real controller/process、non-fixture ENFORCED、live SIM、shared MySQL、restart/deploy/
  publish: not authorized and not run；它们是后续 activation gate，不是本次 rejection
  原因。
- historical Task `20260730-0e01`: not accessed or mutated。
- SIM/TMS/sibling repo/business data: not accessed or modified。

## Risks / Follow-ups

- 当前默认 `shadow-enabled=false` 和 activation-disabled 降低了即时运行暴露面，但不能把
  缺失 source vertical chain签为 accepted；receipt-enabled path 已经调用新增 coordinator，
  因此也不能把所有问题界定为“尚未 activation”。
- BUG-035 receipt-disabled one-shot 和 published SDK typed contract focused tests仍通过；
  修复时必须继续保留。
- 修复不需要扩大批准的 architecture或真实 activation authority；若选择改变 terminal
  authority、public semantics、Worker v1 wire、migration strategy或 proof model，必须先
  将 canonical spec置为 `NEEDS_REPLAN`。

## Signoff Marker

- acceptance_status: rejected
- acceptance_decision: rejected
- signed_off_by: Independent Signoff Reviewer (Codex)
- signed_off_at: 2026-07-31
- acceptance_record:
  `docs/version-tracker/1.4.3-SNAPSHOT/evidence/ARCH-001-independent-signoff-2026-07-31.md`
- blocking_items: `ARCH-001-B1` through `ARCH-001-B6`
- follow_up_required: yes
