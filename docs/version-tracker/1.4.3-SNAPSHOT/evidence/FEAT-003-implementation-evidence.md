# FEAT-003 Implementation Evidence

Date: 2026-07-26

## Outcome

- Runtime-only `task-completion-readiness` server/API/SDK/CLI and Codex SDK
  Worker evidence support are implemented locally.
- The exact host `Ubuntu-24.04` Worker on port 3151 was upgraded in place to
  Worker `1.0.25` with Codex SDK `0.145.0`. The current WSL instances
  `/home/sa/.codex-worker` and `/home/sa/.claude-worker` were not stopped,
  modified or upgraded.
- The readiness endpoint can distinguish a stale Navigator registration from
  the absence of the real provider process without forging completion.
- A new ASK after the Worker upgrade failed terminally in about 18 ms when its
  delegated cwd was unavailable. It did not remain falsely `RUNNING`.
- After explicit authorization, the exact delegated cwd was created in the
  target WSL without changing Directory or binding data. Two further ASK
  attempts crossed cwd validation and created provider tasks, then failed
  terminally in the only allowed `codex-luna:high` channel with the sanitized
  code `CODEX_AUTH_REQUIRED`.
- Natural terminal-success V2 result/receipt evidence is not yet proven live
  because the configured provider channel did not reach terminal success.
- Delivery remains `BLOCKED`; it is not ready for independent signoff.

## Provenance

- Source:
  - commit: `429a8ab768e9731da1e8e51ed9c9e7ddde48262a`
  - branch: `main`
  - worktree: dirty
- Running Navigator:
  - endpoint: local 8112
  - health on 2026-07-26: `UP`
  - deployed from the current dirty source on 2026-07-26
  - listener PID after the final restart: `132692`
- CLI:
  - local source/package version: `1.0.33`
- Bound Codex SDK Worker:
  - distribution: host `Ubuntu-24.04`
  - installation: `/home/navigator/.codex-worker`
  - run user: `navigator`
  - version: `1.0.25`
  - health: `ok`, `ready=true`, `active_tasks=0`
  - Codex SDK: `0.145.0`, compatible
  - auth mode: `codex_login`
  - termination readiness: ready, identity/auth/replay ledger configured
- Worker release artifacts:
  - Linux/macOS SHA-256:
    `b55fd8595e498845f974308010a3b7e4c5e9701e2b1f1353616d6267b811a4d8`
  - Windows SHA-256:
    `d5b34461ec5d321a2930ae474e6902b2adfc8e8e80384c5897f835011a91527d`
- Local launcher JAR SHA-256:
  `8b62689ca688c558564482fd489f08bb372f79130e678eca7e281c0578e5e73d`

These are dirty local artifacts and were not published.

## Automated Validation

- Codex SDK Worker:
  - `npm test`: 247 total, 246 passed, 1 Windows-only skipped, 0 failed.
  - `npm run typecheck`: passed.
  - `npm run build`: passed.
  - full Worker 1.0.25 release smoke: passed.
- Navigator completion-readiness and affected surfaces:
  - authorization: 11 tests passed.
  - Web MVC authorization exclusion: 2 tests passed.
  - completion service/controller: 63 tests passed.
  - Codex provider/client: 174 tests passed.
  - upstream CLI: 153 tests passed.
- Additional fast-terminal correctness:
  - command:
    `mvn -pl addons/claude-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest,RuntimeStateAuditServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - result: 69 tests passed.
  - coverage: immediate-terminal task token revoke, stable safe error-code
    fallback and rejection of free-form task errors.
  - command:
    `mvn -pl addons/claude-worker-agent,addons/codex-worker-agent -am -Dtest=OpenApiControllerMessageMappingTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
  - result: 206 tests passed.
  - coverage: terminal-marker/task-token binding race, definitive
    `CODEX_WORKING_DIRECTORY_UNAVAILABLE` closure and preservation of
    recoverability for other failed Codex tasks.
- Packaging:
  - `mvn package -pl launcher -am -DskipTests`: passed, 14 reactor modules
    successful.
  - `git diff --check`: passed.
  - scoped sensitive-value scan: zero matches.

An earlier affected multi-module run reached 710 tests before nine errors in
the pre-existing `BusinessTaskScopedTokenLifecycleJpaTest` JPA slice because
`RuntimeRequestAuditService` is absent from that slice. FEAT-003 did not modify
that listener/test fixture; every focused FEAT-003 Java surface passed.

## Historical Task Read-Only Observation

Task `20260725-6a2e` was never terminated, reconciled, retried, recovered,
resumed, redispatched or finalized.

- durable facts:
  - terminal/status: `false/RUNNING`
  - task token: `ACTIVE`
  - active registration: `true`
  - dispatch/retry/recovery: `1/0/0`
  - completedAt: `null`
- Worker/provider observed facts:
  - Worker reachable: `true`
  - Worker task known: `false`
  - provider process present/state: `false/ABSENT`
  - provider active/terminal status: `null`
  - heartbeat/progress/process exit: `null`
- completion evidence:
  - presence, time, digest and recoverability fields: `null`
- assessment:
  - stale registration suspected: `true`
  - worker process absent: `true`
  - completion candidate/authoritative: `false/false`
  - completion reconciliation supported: `false`
  - termination reconciliation supported: `true`
  - recommended action: `TERMINATE_AND_RECONCILE`
  - reason: `PROVIDER_PROCESS_ABSENT_NO_AUTHORITATIVE_COMPLETION`

This proves registration residue/process absence discrimination. It does not
prove that the historical task completed.

## New ASK Diagnostics

User authorization on 2026-07-26 allowed multiple new test-owned ASK attempts.
All attempts were single-turn and explicitly disabled tools and
BusinessFunctions. No old task was retried, resumed or recovered.

- Earlier diagnostic tasks remained `RUNNING` before the cwd fail-fast patch.
  Read-only correlation found:
  - delegated cwd digest:
    `e25e164f1d7c8451b487bf5874f165ad29f16ecf2ebb06b53921391fa17ea479`
  - cwd exists/isDirectory: `false/false`
  - actual path: not recorded or returned.
- The exact 3131 Claude role in the target `Ubuntu-24.04` distribution was
  verified and started. This did not create the delegated cwd and did not
  change the diagnosis.
- After deploying Worker 1.0.25, task `20260726-9bf3` returned:
  - terminal/status: `true/FAILED`
  - failure stage: `DISPATCH`
  - fixed error: `CODEX_WORKING_DIRECTORY_UNAVAILABLE`
  - elapsed time: about 18 ms
  - active registration: absent
  - dispatch/retry/recovery: `0/0/0`
  - provider task/process: not created
  - BusinessFunction: not dispatched.
- Readiness for the terminal failure returned:
  - provider facts: `UNKNOWN/null`
  - completion evidence: `UNKNOWN/null`
  - completion candidate/authoritative: `false/false`
  - recommended action: `NO_ACTION_ALREADY_TERMINAL`.

This is sufficient to prove that the missing cwd no longer creates a new
`RUNNING` fake-death task. It is not a natural-success receipt sample.

After explicit authorization, the exact bound cwd was created in the target
WSL with owner `navigator` and mode `0750`. The actual path was not recorded in
evidence; its existing digest remains
`e25e164f1d7c8451b487bf5874f165ad29f16ecf2ebb06b53921391fa17ea479`.
No Directory row, binding, Worker identity or model configuration was changed.

Two subsequent test-owned ASK attempts crossed cwd validation:

- `20260726-f13a` became terminal `FAILED` after creating a real provider task;
  its sanitized error was `CODEX_AUTH_REQUIRED`, Worker task state was
  `FAILED`, provider process was absent after terminal failure, and
  dispatch/retry/recovery remained `1/0/0`.
- `20260726-2188` reached the same terminal failure in the same configured
  provider channel.

Both used the only model variant allowed by the bound model configuration,
`codex-luna:high`, which resolves in the Worker to the configured
`gpt-5.6-luna` lane. A no-task-created validation request using a different
variant was rejected by Navigator before task creation because that variant
is not allowed by the model configuration. No model configuration mutation,
credential issuance/refresh or login mutation was performed.

Readiness for `20260726-f13a` reported Worker reachable, Worker task known and
terminal `FAILED`, provider process absent, no final-output/completion receipt,
`completionCandidate=false`, and all fourteen query side effects false. This
proves that the cwd blocker was removed, but it is not a natural-success V2
receipt sample.

## Query Side-Effect Assertions

The completion-readiness queries explicitly returned all fourteen fields as
false:

- `accessTokenIssued`
- `runtimeTokenIssued`
- `taskTokenIssued`
- `taskCreated`
- `contextCreated`
- `sessionCreated`
- `workerCommandDispatched`
- `modelDispatched`
- `businessFunctionDispatched`
- `retryTriggered`
- `recoveryTriggered`
- `terminationTriggered`
- `reconciliationTriggered`
- `provisioningResourceChanged`

## Fast-Terminal Server Follow-up

The first live 8112 audits exposed two narrow server gaps:

- the task token remained `ACTIVE` after the submission returned an immediate
  terminal `FAILED` task;
- `sanitizedErrorCode` was `null` even though the task carried the fixed safe
  code `CODEX_WORKING_DIRECTORY_UNAVAILABLE`.

The first server patch fixed error projection and the synchronous
immediate-terminal path. ASK `20260726-3461` then exposed a remaining race:
the provider terminal marker could arrive before task-token binding, leaving
the newly bound token `ACTIVE`.

The deployed source now:

- revokes the newly bound task token when submission immediately returns
  `COMPLETED`, `FAILED` or `CANCELED`;
- promotes only strict uppercase stable codes with approved runtime/provider
  prefixes to `sanitizedErrorCode`;
- marks the exact deterministic cwd dispatch rejection as non-recoverable and
  handles terminal-marker/task-token binding races idempotently;
- preserves existing recoverability for other `FAILED` Codex tasks;
- never promotes arbitrary/free-form task error text.

The final patch passed 206 focused tests and the full launcher package, then
was deployed to 8112. ASK `20260726-a7b8` returned:

- terminal/status: `true/FAILED`
- fixed error: `CODEX_WORKING_DIRECTORY_UNAVAILABLE`
- task token: `REVOKED`
- active registration: absent
- dispatch/retry/recovery: `0/0/0`
- BusinessFunction: not dispatched.

Its task-audit and completion-readiness queries returned all fourteen query
side effects as false. After both authorized launcher restarts, historical task
`20260725-6a2e` remained `RUNNING`, token `ACTIVE`, registration present and
dispatch/retry/recovery `1/0/0`; no retry, recovery, termination or
reconciliation was observed.

## Completion Reconciliation Assessment

No completion mutation was implemented or executed.

A future `task-completion-reconcile` is needed if Navigator must preserve an
authoritative original provider success instead of converging through the
existing abort/cancel closure path. Its minimum safety contract is:

- exact `taskId` plus equal explicit `confirmTaskId`;
- expected Physical Worker, dispatch count, provider task/attempt identity,
  evidence schema/version and evidence digest as CAS inputs;
- server-side re-read and verification of the durable result/receipt pair;
- accepted evidence must bind identity, terminal success, recoverability,
  recorded time and digest and must have no conflicting terminal evidence;
- one atomic operation preserves immutable original completion evidence,
  records the real provider terminal status, revokes the task token and removes
  active registration;
- idempotent identical replay and fail-closed conflicting replay;
- no task/context/session creation, token issuance, redispatch, retry, recovery,
  model call or BusinessFunction call.

## Remaining Blockers

1. The only model variant allowed by the bound model configuration reaches a
   provider/auth-class terminal failure, so a natural terminal-success ASK and
   V2 durable result/receipt have not been observed live.
2. Server, CLI and Worker artifacts currently have dirty provenance and are not
   publishable/signoff-ready.

Final status: `BLOCKED`.
