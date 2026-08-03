# NAVI-CORE-001 S4-02D2A1 — Immutable forward plan binding

- Status: `IMPLEMENTED_REVIEWED`
- Parent: `NAVI-CORE-001`
- Stage: `4`
- Final joint full-cycle budget: `0/3 consumed`

## Purpose

Freeze one server-derived, immutable semantic plan for `NEW_SESSION` forward before D0 Session
reservation, D1 canonical command execution, relation persistence, or Provider effect. This slice is
dormant and effect-free; production wiring remains in D2B/D2C.

## Exact scope

- Add package-local `SessionForwardNewSessionPlan`.
- Bind owned source provenance, authoritative owner/tenant/root, effective target execution facts,
  source content, prompt, title, images and source Session snapshots.
- Project directly to the existing D0 `ReservationSpec` and existing SPI
  `AgentTaskSubmitRequest`; do not create a second target DTO or command path.
- Generate a domain-separated SHA-256 semantic fingerprint with explicit tags, null markers,
  lengths, integer and list encoding.
- Bind the server canonical request UUID and deterministic D0 Session ID only when producing the
  submit request; neither derived identity enters the semantic digest.

## Invariants

- The plan has no credential, token, header, caller digest, caller target Session ID, entity,
  repository, transaction, pipeline, Provider, relation, receipt or runtime dependency.
- `MESSAGE` and `TASK_RESULT`, source reference/task/content, all D0 reservation fields, every
  Provider-affecting submit field, source Session snapshots and
  `initializeRuntimeAffinity=true` are digest-bound.
- Provider type remains unresolved at this layer. Attachments and context fields remain null because
  the existing forward API does not supply them.
- When a logical Agent is present, the plan requires an already resolved, owned effective directory.
  D2B must resolve the Agent default before plan construction; this prevents the existing pre-factory
  directory stage from changing a digest-bound null directory. A directory-free plan is allowed only
  when no logical Agent is supplied, so that stage has no default-Agent lookup source.
- Image wire content is trimmed once and wrapped as a singleton exactly as the current forward path;
  it is not parsed, reordered or reserialized. Internal lists are defensive immutable copies.
- Nullable `maxTurns` remains compatibility-preserving; zero and negative values are not newly
  rejected by this dormant slice.
- `toString()` on the plan and its nested source/target values redacts prompt, source content, cwd,
  images and teams JSON. The existing D0 `ReservationSpec` record is outside this three-path
  redaction boundary and contains the prompt-derived title; D2B must never log that projection.
- Every digest-bound string must be well-formed Unicode. Malformed surrogate input fails before
  projection or digest generation rather than being replaced by the Java UTF-8 encoder.
- No historical or existing data is read or mutated. `BOOT-INF/` is out of scope.

## Validation budget

1. Compile `session-module` production sources with dependencies and tests skipped.
2. Run only `SessionForwardNewSessionPlanTest`.
3. Obtain three independent read-only P1/P2 reviews of the exact three-path diff.

Do not run a whole module/reactor, DB/E2E/live Provider lane, or a final joint full cycle in this
slice.

## Implementation evidence

- Production compile: `mvn -pl session-module -am -DskipTests compile` — PASS (`15.927s`).
- Focused test: `SessionForwardNewSessionPlanTest` — PASS (`8/8`, `26.307s`).
- The first focused run intentionally carried a `GOLDEN` placeholder; all other seven tests passed
  and exposed the computed digest. The literal was then frozen as
  `b45fadfa1565054c5d4865efe1cf8263e4a78313c60382ec97c4145923678577` and the same bounded class
  passed. This was not a product failure and did not expand validation scope.
- Final review hardening added well-formed Unicode rejection and the Agent/effective-directory
  stability invariant; surrogate-safe title truncation was then added and the same focused class
  passed finally (`8/8`, `29.662s`).
- Three independent final read-only reviews accepted the exact staged three-path diff with no open
  P1/P2 after the Unicode, title-boundary and pre-pipeline directory findings were closed.
- Whole module/reactor, DB/E2E/live Provider and final joint full validation were not run. Final
  joint budget remains `0/3 consumed`.

## Stop conditions

Stop and replan if implementation needs a fourth path, public DTO/SPI/schema changes, a second
target model, mutable request/entity retention, Provider resolution, a database/runtime effect,
historical data mutation, or request/target identity inside the semantic digest.
