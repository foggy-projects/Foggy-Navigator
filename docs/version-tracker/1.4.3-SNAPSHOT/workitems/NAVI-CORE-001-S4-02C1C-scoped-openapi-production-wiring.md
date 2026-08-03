---
workitem: NAVI-CORE-001-S4-02C1C
status: IMPLEMENTED_REVIEWED
baseline: b71dec38
stage: 4
prerequisite: NAVI-CORE-001-S4-02C1B1@b71dec38
full_validation_cycle: 0/3
historical_data_mutation: not-authorized-not-performed
---

# NAVI-CORE-001 S4-02C1C scoped OpenAPI production wiring

This slice connects the production OpenAPI ask facade to the accepted C1A immutable Worker
preflight and C1B1 scoped canonical create-command adapter. It moves fresh-only admission, task
token, dispatch audit, client-context and BusinessAgent session mutations behind the coordinator's
participant permit without changing the public OpenAPI path, Form/DTO/RX mapping or credential
precedence.

## Frozen boundary

The production facade now creates one caller-owned launch metadata map, one Worker selection
request and one exact `AgentTaskSubmitRequest`. The request identity is the canonical
`RuntimeRequestAuditService.AuditHandle.clientRequestId()`, never a second copy of the raw header.
When the BusinessAgent Worker capability applies, the facade runs the read-only service-minted
preflight before opening the scoped command lane, validates its model and selected Worker, and
projects that Worker into launch metadata, the exact submit request and the scope target. The
scope also binds the verified tenant, Agent owner, ClientApp, nullable upstream-system,
upstream-user, credential and runtime-access authentication evidence.

The controller no longer writes admission before submission. It delegates the complete mutation
boundary to the facade, maps the response client request ID from the audit handle and applies only
non-null scope diagnostics so an empty recorded-replay overlay cannot erase durable task facts.

An independent review found that the original five-path test boundary only simulated the scoped
adapter. The coordination gate was therefore amended by commit `06f046c` to authorize one bounded
composition-test path. That test uses the real `DefaultAgentSubmitPipeline`, scoped adapter,
Coordinator and `CommandOnceReceiptService`, while replacing only the transaction/repository and
Provider/service persistence-effect boundaries with stateful in-memory mocks.

## Fresh and replay behavior

After the canonical coordinator grants a valid fresh permit, preparation performs exactly one
admission audit and, only for a non-null immutable preflight, calls
`prepareOpenApiTaskScopedTokenAfterPreflight`. The legacy token-preparation overload is not used.
The returned plaintext task token and lease are injected only into a fresh top-level metadata copy
and a fresh nested `runtimeContext` copy owned by the canonical `TaskDispatchRequest`; the outer
message, original submit metadata and response diagnostics never receive the plaintext token or
lease.

Before the once-effect receipt is recorded, fresh completion performs token binding, strict
immediate-terminal revocation, dispatch audit, client-context update and BusinessAgent session
binding against the exact `DispatchTaskDTO`. `TerminalTaskBindingException` means the binding
transaction already committed terminal revocation, so it is a known completion branch and is not
revoked a second time. Any other post-permit failure, including immediate-terminal revoke failure,
propagates to the coordinator's ambiguous outcome. The facade does not compensate with a blind
revoke, mark the audit failed, retry, fall back or submit a second task.

Recorded replay still performs the allowed read-only preflight needed to reconstruct the same
authority binding, but invokes no fresh participant. It therefore issues/revokes no token or lease,
writes no admission or dispatch audit, updates no client context and binds no BusinessAgent
session. Its response overlay is empty; only the owner-aware durable task hydration is returned.

## Validation record

- production compile: `mvn -pl addons/claude-worker-agent -am -DskipTests compile` —
  `BUILD SUCCESS` in 19.949s after one bounded compile-only signature correction
- affected test compile: `mvn -pl addons/claude-worker-agent -am -DskipTests test-compile` —
  `BUILD SUCCESS` in 26.581s
- facade exact selectors after terminal-revoke review correction: 7/7 passed,
  failure/error/skip 0, `BUILD SUCCESS` in 36.383s;
  covers no-launcher scoped fresh, immutable preflight, canonical nested-copy token isolation,
  recorded replay zero mutation, post-permit failure without compensation/retry, the known
  terminal-binding-race branch without redundant revoke, and immediate-terminal revoke failure
  without false success or local completion writes
- real stateful-receipt composition selectors: final 2/2 passed, failure/error/skip 0,
  `BUILD SUCCESS` in 36.171s; proves a first real fresh create records the receipt and its second
  same-request execution is a replay with zero fresh mutation/Provider dispatch. It also proves
  immediate-terminal revoke failure drives the real receipt service to `AMBIGUOUS` with no result,
  and that this persisted state rejects the second attempt without redispatch. Two initial focused
  fixture-calibration runs failed before the intended branch
  (`TASK_CREATE_FRESH_EFFECT_INPUT_REQUIRED`, then identity mismatch). A later 2/2 run in 35.358s
  was explicitly superseded after review found its whole receipt service was mocked and its second
  state scripted; the fixture was replaced with the real service plus a stateful repository/tx
  boundary, with no production change or broad rerun
- unchanged real adapter and Coordinator exact selectors: 2/2 passed, failure/error/skip 0,
  `BUILD SUCCESS` in 21.725s; reconfirms real participant ordering and completion-failure
  `AMBIGUOUS`/no-result semantics
- controller affected selectors: 14/14 passed, failure/error/skip 0; covers admission/dispatch
  ownership, token and terminal branches, non-Business provider compatibility, ambiguity, missing
  task ID, preflight denial, root-route derivation, untrusted metadata sanitization, server-owned
  Worker composition and busy-context rejection
- controller identity/replay-overlay selectors: 2/2 passed, failure/error/skip 0; proves response
  identity comes from the audit handle and empty/null replay overlays preserve durable diagnostics
- controller terminal delta selectors after review correction: 2/2 passed,
  failure/error/skip 0, `BUILD SUCCESS` in 26.873s; proves immediate-terminal strict revoke and
  terminal-binding-race no-second-revoke mapping
- independent read-only reviews: three final reviews accepted the corrected six-path snapshot with
  no remaining P1/P2. The reviews independently covered authority/order, token/terminal/secret
  handling, and the real pipeline/Coordinator/receipt composition evidence
- whole class/module/reactor, E2E, live/provider and final joint full cycle: not run; not part of
  this focused slice

No service or Worker was started. No business or historical data was read or modified. No repair,
backfill, replay, reconciliation, deletion or historical fact synthesis was performed. The
untracked `BOOT-INF/` directory was not read or changed.
