---
workitem: NAVI-CORE-001-S3-04B
status: IMPLEMENTED_REVIEWED
date: 2026-08-03
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
baseline: c5fb261b770f6c5baff083fcac412e9c7305cb77
---

# NAVI-CORE-001 S3-04B Codex SDK execution adapter

The exact `codex-worker` and `codex-biz-worker` SDK execution boundary is now the dedicated
`CodexSdkExecutionAdapter`. It accepts no aliases or AppServer identity, requires `SDK_EXEC`, and
proves both the exact Worker identity and exact `legacy-sdk:<workerId>` runtime binding before it
reads Worker configuration. Missing or blank configuration fails before client creation or any
provider call. A valid binding obtains the existing full three-argument SDK client from
`CodexWorkerClientFactory`; the controlled execution handle preserves the exact SDK versus Biz
provider identity.

The adapter forwards only the already-built request map or an existing Worker task subscription.
It neither copies nor interprets the request, and forwards reconnect `workerTaskId`, `ackSeq`, and
the recovery connection-settlement callback unchanged. It has no AppServer registry or acceptance,
persistence, transaction, lifecycle authority, terminal/failure policy, lock, retry, recovery
budget or logging dependency.

`CodexStreamRelay` continues to load and validate persisted provider/session/Worker affinity,
construct the full original request through `CodexWorkerClient`, obtain lifecycle evidence, commit
the provider-effect fence, add lifecycle context when authorized, own SSE/ACK/message/terminal
processing, and own locks and recovery policy. SDK initial dispatch now always sends the exact map
that Relay constructed, through the adapter. The Codex home key, business runtime context, output
schema, Codex config, sandbox/approval/network/web settings and additional directories reach the
request builder unchanged; none of those values is included in the dispatch log.

Manual and automatic SDK reconnects now cross the adapter while Relay still chooses the persisted
Worker task id, maximum durable/in-memory ACK, recovery lease and callback. The unchanged automatic
provider allowlist still excludes Codex Biz. All AppServer acceptance, subscription, status
reconciliation and instance-pinned client branches remain in Relay and use the existing four-arg
runtime client. SDK runtime resolution now preserves the persisted runtime id so the adapter's
exact-affinity proof cannot be bypassed by synthesizing a corrected binding.

`CodexTaskService`, `CodexWorkerClient`, the Biz provider, launcher, POM and protocol contracts were
not changed. Therefore the new-SDK-Ultra admission ban remains in the Service, while existing
validly bound SDK tasks, including historical Ultra tasks, can still drain and reconnect.

## Changed paths

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexSdkExecutionAdapter.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexSdkExecutionAdapterTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`
- this work item

## Focused validation

- Pre-change characterization selected Relay start/reconnect/admission/AppServer behavior,
  historical SDK Ultra drain, Codex Biz policy forwarding and Worker request/subscription behavior:
  `12/12` passed, failures/errors/skips `0`; wall `25.76 s` (`user 19.78 s`, `sys 4.69 s`).
- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am -DskipTests compile`: passed; wall
  `19.19 s` (`user 23.30 s`, `sys 3.69 s`).
- Direct `CodexSdkExecutionAdapterTest`: `8/8` passed, failures/errors/skips `0`; wall `30.79 s`
  (`user 31.66 s`, `sys 5.56 s`). It covers exact plain/Biz identity, rejected unknown/AppServer/
  normalized aliases, non-SDK and inexact legacy affinity, missing configuration with zero client
  effect, the exact three-arg factory, same-reference full request forwarding, and exact reconnect
  task/ACK/callback forwarding.
- Combined adapter plus selected Relay/TaskService/Biz/client focused command: `20/20` passed,
  failures/errors/skips `0`; wall `25.50 s` (`user 19.38 s`, `sys 4.76 s`). It preserves Biz
  request options, lifecycle admission-before-effect order and denial fence, AppServer acceptance,
  SDK reconnect, disabled-auto/manual recovery, historical SDK Ultra drain, Biz defaults and the
  concrete client request/subscription contract.
- After strengthening same-reference assertions for output schema, Codex config, business runtime
  context and directories, the two directly affected Relay start/admission cases passed `2/2`,
  failures/errors/skips `0`; wall `36.03 s` (`user 43.20 s`, `sys 6.78 s`).
- The final three directly affected recovery cases passed `3/3`, failures/errors/skips `0`; wall
  `23.67 s` (`user 12.02 s`, `sys 3.81 s`). They retain SDK automatic reconnect callback/lease
  settlement and exhaustion behavior, and prove that the Biz identity still has no automatic
  recovery capability or side effect.
- There was no characterization, compile, direct-test or selected-focused first-run failure.
- Static scans confirmed no AppServer, repository, transaction, lifecycle/recovery, TaskService,
  registry, acceptance or logger import/dependency in the adapter; no Worker configuration lookup
  remains in Relay; the dispatch log does not contain the protected runtime option names or values.
  `git diff --check` passed, and the authorized implementation contains exactly five changed paths.
- No affected-module lane, full, E2E, live, package, Spring context, service/Worker start-stop or
  historical-data validation was run in this slice.

## Independent review

Independent read-only review returned `ACCEPT` with no MAJOR or MINOR. It confirmed exact SDK/Biz
affinity and identity, the three-argument client and same-reference request/subscription
forwarding, protected business option handling, and retention in Relay of lifecycle/effect,
SSE/terminal, lock/recovery, Biz no-auto-recovery and all AppServer responsibilities. It also
confirmed that Ultra admission/drain behavior and Spring wiring remain stable. The reviewer reused
the compile and focused evidence and did not rerun tests or inspect `BOOT-INF/`.

## Affected validation

- `/usr/bin/time -p mvn -q -pl addons/codex-worker-agent -am
  -Dtest='*Test,!*E2ETest,!*IntegrationTest,!*Live*'
  -Dsurefire.failIfNoSpecifiedTests=false test`: PASS `2316/2316`, failures/errors/skipped `0`,
  wall `95.05 s` (`user 192.76 s`, `sys 25.17 s`). Surefire XML aggregation covered `255`
  suites: `navigator-common 128`, `navigator-spi 9`, `agent-framework 215`,
  `user-auth-module 173`, `session-module 493`, `business-agent-module 745`, and
  `codex-worker-agent 553` tests.
- WARN/ERROR and repair-oriented log lines came from expected negative or disposable test
  fixtures; the run did not connect to or mutate existing business/runtime data.
- This was the one post-review affected Codex lane for S3-04B. It was not a final joint full
  validation and consumed none of the user-authorized `0/3` final cycles.

## Residual boundary

No public DTO, reason code, provider identity, persistence schema, terminal contract or recovery
bound changed. No historical or existing business/runtime data was read or mutated, and no
disposable fixture or local process was created. The unrelated pre-existing untracked `BOOT-INF/`
tree was not inspected or changed.
