# FEAT-003 Implementation Evidence

Date: 2026-07-26

## Outcome

- Runtime-only `task-completion-readiness` server/API/SDK/CLI and Codex SDK
  Worker evidence support are implemented and clean released.
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
- After the target Worker was configured from the gitignored operator-supplied
  provider profile and restarted in `api_key` mode, bounded ASK
  `20260726-6700` naturally completed in about six seconds. It produced a
  durable, recoverable V2 final-output receipt and authoritative provider
  terminal-success signal without tools or BusinessFunctions.
- Delivery is `READY_FOR_SIGNOFF`; it is not marked `ACCEPTED`.

## Provenance

- Source:
  - implementation/release commit:
    `d5a9e97fb677cb77c22ee7101abef71e19891618`
  - CLI provenance contract patch:
    `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`
  - branch: `main`
  - both commits pushed to `origin/main`
- Running Navigator:
  - endpoint: local 8112
  - health on 2026-07-26: `UP`
  - embedded commit: `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`
  - embedded git dirty: `false`
  - listener PID after the final restart: `184140`
- CLI:
  - official version: `1.0.34`
  - build ID: `1.0.34+9a4bbd7a08a5`
  - git dirty: `false`
  - feature count: `73`
  - `runtime-task-completion-readiness`: present
  - Linux SHA-256:
    `666d321e237a25457ea65f6aeb37edcbde9fbf48bc7c116a8f4b3258f47d7405`
  - Windows SHA-256:
    `69d176986586cd0ed1a77816965e9631e4f7fa5b2a1cc57c1ae714c1263c9055`
  - independent remote downloads matched release metadata
- Bound Codex SDK Worker:
  - distribution: host `Ubuntu-24.04`
  - installation: `/home/navigator/.codex-worker`
  - run user: `navigator`
  - version: `1.0.25`
  - health: `ok`, `ready=true`, `active_tasks=0`
  - Codex SDK: `0.145.0`, compatible
  - auth mode after operator-authorized provider configuration: `api_key`
  - termination readiness: ready, identity/auth/replay ledger configured
- Worker release artifacts:
  - Linux/macOS SHA-256:
    `b55fd8595e498845f974308010a3b7e4c5e9701e2b1f1353616d6267b811a4d8`
  - Windows SHA-256:
    `d5b34461ec5d321a2930ae474e6902b2adfc8e8e80384c5897f835011a91527d`
- Clean launcher JAR SHA-256:
  `0a6a824f085b42e4bfd1649b96f254a3ad6da790033341e99b0eb6f544407611`

Worker 1.0.25 and CLI 1.0.34 were published from independent clean clones.
CLI 1.0.33 was an intermediate release whose provenance test still expected
published 1.0.32; it was superseded by 1.0.34 after the contract was corrected
and all 153 CLI tests passed.

## Automated Validation

- Codex SDK Worker:
  - `npm test`: 247 total, 246 passed, 1 Windows-only skipped, 0 failed.
  - `npm run typecheck`: passed.
  - `npm run build`: passed.
  - full Worker 1.0.25 release smoke: passed.
- Navigator completion-readiness and affected surfaces:
  - independent clean clone authorization: 11 tests passed.
  - Web MVC authorization exclusion: 2 tests passed.
  - Claude runtime/completion surface: 74 tests passed.
  - Codex provider/client: 175 tests passed.
  - upstream CLI 1.0.34 contract: 153 tests passed.
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
    successful from clean commit `9a4bbd7a`.
  - Worker 1.0.25 full release smoke and package verification: passed.
  - CLI 1.0.34 package, upload, remote installer smoke and independent
    Linux/Windows digest checks: passed.
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

## Natural Completion Live Evidence

The operator supplied a gitignored provider profile with an API key and base
URL. The profile was validated without printing either value, restricted to
mode `0600`, and installed into the exact owned 3151 Worker by replacing only
the two provider configuration fields. No Navigator model configuration,
Agent, Directory, grant or binding was changed. `NAVI_TEST_MODEL` was omitted;
the existing allowed alias `codex-luna:high` was used and resolved to
`gpt-5.6-luna`.

The exact host `Ubuntu-24.04` Worker was restarted using its installed
`stop.sh` and `start.sh`. Post-restart and post-ASK health both reported:

- Worker `1.0.25`, ready and `active_tasks=0`;
- Codex SDK `0.145.0`, compatible;
- auth mode `api_key`;
- termination readiness enabled.

One bounded SIM runtime ASK was then submitted using the existing runtime
profile:

- task: `20260726-6700`
- expected Physical Worker: `ddc45293`
- requested model variant: `codex-luna:high`
- effective model variant: `gpt-5.6-luna`
- max turns: `1`
- requested/effective tool count: `0/0`
- requested/effective BusinessFunction count: `0/0`
- tool/function scope source: `REQUEST_EXPLICIT_EMPTY`
- task-token function scope empty: `true`
- created: `2026-07-26T11:57:30.684185+08:00`
- completed: `2026-07-26T11:57:36.706822+08:00`

The natural terminal task facts were:

- terminal/status: `true/COMPLETED`
- task token: `REVOKED`
- active registration: absent
- dispatch/retry/recovery: `1/0/0`
- runtime/model dispatched: `true/true`
- BusinessFunction dispatched: `false`

Completion-readiness observed:

- Worker reachable/task known/state: `true/true/COMPLETED`
- provider process present/state: `false/ABSENT`
- provider active/terminal/status: `false/true/COMPLETED`
- last progress:
  `2026-07-26T03:57:36.668Z`
- final output present/durable/recoverable: `true/true/true`
- final output digest:
  `sha256:c2e3ac47f4a325469c1a2d5f117e463ec943c721986d5d9f09ac4540b7d80526`
- final output recorded:
  `2026-07-26T03:57:36.658Z`
- structured output present: `false`
- completion signal present/source:
  `true/PROVIDER_TERMINAL_EVENT`
- completion signal recorded:
  `2026-07-26T03:57:36.658Z`

The assessment returned:

- stale registration suspected: `false`
- worker process absent: `true`
- completion candidate/authoritative: `true/true`
- completion reconciliation supported: `false`
- termination reconciliation supported: `false`
- reconcile required: `false`
- reason/action: `TASK_ALREADY_TERMINAL/NO_ACTION_ALREADY_TERMINAL`
- source:
  `DURABLE_TASK+CODEX_COMPLETION_RECEIPT_V2+WORKER_PROCESS_SNAPSHOT`

Two completion-readiness queries produced identical durable facts, Worker
terminal identity, evidence facts and assessment semantics except for their
observation/assessment timestamps. Two task-audit queries differed only in
`observedAt`. Both readiness responses returned every audit side-effect field
as `false`; task token, active registration and dispatch/retry/recovery counts
remained `REVOKED`, absent and `1/0/0`.

This live sample proves that provider process absence after a real successful
completion is distinguished from stale registration by the authoritative,
identity-bound durable result/receipt pair. It also proves that the readiness
query does not need to read or return model content.

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

None for implementation handoff. Independent signoff is still required before
the delivery can be marked `ACCEPTED`.

Final status: `READY_FOR_SIGNOFF`.
