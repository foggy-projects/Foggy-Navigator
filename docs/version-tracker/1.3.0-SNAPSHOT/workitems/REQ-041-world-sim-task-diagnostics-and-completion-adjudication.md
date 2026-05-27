---
type: requirement
version: 1.3.0-SNAPSHOT
ticket: REQ-041
severity: major
status: first-feedback-received
owner: upstream-integration + session-module + biz-worker-runtime + navigator-open-sdk
source_project: foggy-world-sim
source_version: v0.0.120
github_issue: https://github.com/foggy-projects/Foggy-Navigator/issues/134
---

# REQ-041: World-Sim Task Diagnostics and Completion Adjudication Contract

## Document Purpose

- doc_type: requirement
- intended_for: navigator-maintainer / upstream-integration-owner / world-sim-owner
- purpose: Request Navi-side feedback on whether current task/session diagnostics can support world-sim recovery, completion evidence adjudication, and long-running LLM task governance.

## Background

`foggy-world-sim` v0.0.120 is designing a `WorldTaskRecovery` capability. The
world engine needs to advance or pause a world tick based on task evidence, not
only on the external process status returned by Navi or a worker process.

During design review, world-sim recorded several real execution patterns:

- An external task can stay `RUNNING` even after the LLM produced a final answer,
  marker, or required artifact, because the underlying process did not exit.
- A task can stay `SUBMITTED` because no worker picked it up; this should be
  treated as queue/routing/capacity trouble, not as LLM rumination.
- A long coding task can legitimately run for hours, so total wall-clock runtime
  alone is not a good timeout signal.
- Some weaker LLM runs can keep emitting messages while making no meaningful
  progress toward the task contract.
- For ambiguous cases, world-sim may need an independent judge agent or local
  world-engine LLM evaluation to decide whether the task is complete, still
  progressing, rumination, or blocked.

World-sim has recorded the initial design under:

- `foggy-data-mcp/foggy-world-sim/docs/versions/v0.0.120/README.md`
- `foggy-data-mcp/foggy-world-sim/docs/versions/v0.0.120/workitems/P0-world-task-recovery-and-intervention.md`
- `foggy-data-mcp/foggy-world-sim/docs/versions/v0.0.120/acceptance/world-task-recovery-design-signoff.md`

No credentials, local profiles, runtime tokens, or private payloads are included
in this request.

## Requested Navi Feedback

Please confirm which parts are already supported by current Navi OpenAPI, SDK,
CLI, task/session APIs, or BizWorker reports, and which parts require a Navi
follow-up implementation.

### 1. Task Diagnostics Snapshot

Can Navi expose a safe task diagnostics view for an upstream ClientApp/session
caller?

Desired fields:

- `taskId`
- `contextId`
- external task status: `SUBMITTED`, `RUNNING`, `COMPLETED`, `FAILED`,
  `CANCELLED`, or equivalent
- `submittedAt`
- `startedAt`
- `workerStartedAt`, if a worker has picked up the task
- `lastObservedAt`: last message, heartbeat, log, ack, tool event, or task
  detail update
- `lastMeaningfulProgressAt`: last artifact delta, structured output, final
  answer candidate, tool success, report update, or useful failure diagnosis
- `providerTaskId`
- `workerTaskId`
- `physicalWorkerId` or other safe worker reference, if available
- `messagesCount`
- `lastAckedSeq` or equivalent message cursor
- `failureStage`
- `failureSummary`
- active retry/backoff/deadline/interruption reason, when applicable

Access-control expectation:

- A tenant ClientApp/control profile that can ask and poll the task should be
  able to read diagnostics for that same task.
- Full worker inventory can remain admin-only.
- No token, secret, Authorization header, provider key, raw prompt, or private
  attachment URL should be returned.

### 2. Message and Progress Event Stream

World-sim needs to distinguish:

- no activity
- activity but no meaningful progress
- retry/backoff progress
- useful tool or artifact progress
- final response or completion evidence

Please confirm whether current messages/events include enough durable metadata:

- per-message timestamp
- stable cursor or sequence
- role/type/event kind
- retry/progress/heartbeat events
- tool-call and tool-result summaries
- structured output or final response marker
- task/report references that can be reloaded after reconnect

If these are split across multiple APIs, please indicate the recommended query
sequence.

### 3. Completion Evidence and Artifact Access

For tasks that remain externally `RUNNING`, world-sim may still accept the world
task if the expected contract is satisfied.

Needed evidence sources:

- assistant final answer, if available
- structured output payload, if available
- execution report / frame report reference
- artifact path or artifact summary, if Navi tracks it
- artifact/content hash or last-modified metadata, if available
- safe file/report read API for upstream-owned task artifacts

Question:

- Does Navi already expose enough evidence for an upstream caller to verify
  "task contract satisfied even though external task is still running"?
- If not, should Navi expose a task evidence/report endpoint, or should
  world-sim only inspect its own workspace artifacts?

### 4. Continuation and Correlation

World-sim recovery may issue bounded `continue` or retry actions.

Please confirm the recommended contract for:

- continue same `contextId` while creating a new task id
- preserving relationship to `originalTaskId`
- passing a recovery correlation key or attempt number
- preventing duplicate side effects when continue/retry is replayed
- retrieving all tasks/messages under a `contextId` after continuation

This overlaps with historical session continuation work, but world-sim needs the
task-level correlation semantics to be explicit.

### 5. Cancel, Cleanup, and Running-After-Acceptance

World-sim may accept completion evidence while Navi still reports the external
process as `RUNNING`.

Please clarify:

- Is there a supported task cancel/interrupt API for upstream callers?
- If cancel is not supported for a worker/backend, can Navi report this as an
  explicit unsupported capability?
- Can Navi expose a cleanup-pending or still-running-after-accepted state, or
  should world-sim track this locally?
- What is the recommended polling behavior after upstream has accepted evidence
  but external execution is still running?

World-sim does not want to kill or reap external worker processes unless Navi
has an explicit supported capability.

### 6. Independent Judge Agent

For ambiguous long-running tasks, world-sim may ask an independent judge to
classify the evidence as:

- `ACCEPT_COMPLETION_EVIDENCE`
- `CONTINUE_OBSERVING`
- `RETRY_WITH_CLARIFIED_CONTRACT`
- `PAUSE_NEEDS_HUMAN`

Please advise whether Navi can provide this as a dedicated read-only judge agent
or skill:

- input: task contract, recent messages, artifact/report summary, current
  external status, recovery attempts
- output: fixed schema verdict with confidence and reason
- no tools, no file edits, no provider/task mutation
- deterministic checks still take precedence over judge output

If this should remain outside Navi, world-sim can run the judge locally or start
with manual-only review.

## Desired State Classes

World-sim wants to classify external task observations into these stable cases:

| Class | Meaning |
| --- | --- |
| `TASK_NOT_PICKED_UP` | `SUBMITTED` beyond queue/startup grace with no worker pickup, ack, or provider task |
| `RUNNING_WITH_COMPLETION_EVIDENCE` | task remains `RUNNING`, but final answer, marker, structured output, report, or required artifact exists |
| `RUNNING_NO_ACTIVITY` | no message, heartbeat, log, ack, or task-detail update within grace |
| `RUNNING_NO_MEANINGFUL_PROGRESS` | messages continue, but no artifact/tool/result/failure-diagnosis progress within grace |
| `RUNNING_RUMINATION_SUSPECTED` | recent messages repeat planning or self-analysis without advancing the contract |

Please confirm whether current Navi APIs can provide enough signal to support
these classes, or recommend narrower first-slice classes.

## Proposed First-Slice Contract

If a full contract is too large, the minimum useful first slice is:

1. Task diagnostics snapshot with `submittedAt`, `workerStartedAt`,
   `lastObservedAt`, status, message cursor, and safe worker reference.
2. Message/event query with timestamps and durable cursor.
3. Execution report or structured-output reference that can be queried by
   upstream task/context.
4. Explicit continue-same-context behavior and task correlation.
5. Explicit answer on whether cancellation/cleanup is supported by each worker
   backend.

World-sim can keep `lastMeaningfulProgressAt` and completion adjudication local
at first if Navi provides enough timestamped messages and report/artifact
references.

## Related Navi Work

- GitHub issue #106: upstream systems load historical sessions and continue a
  `contextId`.
- GitHub issue #122: world-sim live smoke failed with max iterations without
  valid submit.
- GitHub issue #131: expose resolved PhysicalWorker details in upstream CLI
  diagnostics.
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/OPT-029-upstream-timeout-governance.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/19-biz-worker-frame-execution-report-design.md`
- `docs/version-tracker/1.3.0-SNAPSHOT/workitems/BUG-033-biz-worker-continue-context-injection-gap.md`

## Response Requested

Please respond with one of:

- supported now, with API/SDK/CLI references
- partially supported, with suggested first-slice additions
- not supported in Navi; world-sim should implement locally
- needs a separate Navi implementation issue

The world-sim next step depends on this feedback before selecting its first
implementation package and ports.

## First Feedback Snapshot

Navi first feedback confirms the direction is reasonable and that the current
architecture can support a first-stage capability.

Agreed ownership boundary:

- Navigator provides factual data: task status, message stream, progress
  events, worker pickup/execution diagnostics, failure stage and summary,
  completion evidence, report references, artifact references, and same-context
  recovery attempt lineage.
- World-sim performs final adjudication based on its own task contract, tick
  semantics, and recovery policy.

Navigator can help distinguish:

- whether the task was picked up by a worker;
- whether the task has messages, progress, or heartbeat;
- whether final marker, structured output, report, or artifact evidence has
  appeared;
- whether the task terminally failed, including failure stage and summary;
- which later recovery attempts belong to the same `contextId`.

Navigator should not decide in the core platform:

- whether the task is business-complete for a world-sim contract;
- whether the current output is meaningful progress;
- whether the run is LLM rumination;
- whether world-sim should advance the tick, pause, or retry.

World-sim next step is to implement a deterministic first slice around a
facts-only diagnostics/evidence boundary.
