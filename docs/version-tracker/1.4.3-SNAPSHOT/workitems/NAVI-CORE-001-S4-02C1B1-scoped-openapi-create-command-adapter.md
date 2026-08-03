---
workitem: NAVI-CORE-001-S4-02C1B1
status: IMPLEMENTED_REVIEWED
baseline: 46d390e1
stage: 4
prerequisite: NAVI-CORE-001-S4-02C1B0B-gamma@46d390e1
full_validation_cycle: 0/3
historical_data_mutation: not-authorized-not-performed
---

# NAVI-CORE-001 S4-02C1B1 scoped OpenAPI create command adapter

This slice adds the process-local authority adapter needed to route one authenticated OpenAPI
create through the accepted canonical task-create coordinator. It does not connect a production
OpenAPI caller yet; token/preflight/audit/session relocation remains the separately gated C1C
slice.

## Frozen boundary

The adapter is an `AgentSubmitPipelineStage` immediately before the trusted Navigator JWT stage.
An active scope is always intercepted, even when the request source or object was changed, so a
mismatch cannot fall through to the legacy terminal stage. An unscoped `OPEN_API` submission keeps
its prior behavior until its owner is migrated in C1C.

The public synchronous scope entry binds:

- the exact submit-request object and canonical client request ID;
- tenant, resolved Agent owner, ClientApp, upstream-system/upstream-user and credential safe
  references;
- non-empty authenticated runtime-access evidence, which is validated and then discarded;
- expected logical Agent, context, Provider, physical Worker, model and directory facts;
- process-local fresh preparation and completion callbacks.

The stable actor fingerprint is a domain-separated, length-prefixed SHA-256 over tenant,
ClientApp and credential references. It deliberately excludes runtime access-token identity, so a
legitimate access-token rotation does not change the command binding. The envelope contains no app
key, access token, task token, prompt, message, attachment, metadata or client context.
The optional upstream-system and upstream-user references share a null-tagged, length-prefixed
digest so a recorded replay cannot ignore a change in either ownership dimension.

## Canonical execution

The adapter reuses the single composition-root `ServerAuthority`, the existing
`TaskCreateCommandCoordinator.PlanBinding` and the coordinator participants overload. Its envelope
is fixed to:

- command `CREATE`;
- ingress `OPENAPI`, surface `NAVIGATOR_OPEN_API`, route
  `/api/v1/open/agents/{agentId}/ask`;
- actor `AUTHENTICATED_PRINCIPAL / CLIENT_APP / CLIENT_APP_RUNTIME_ACCESS`;
- canonical plan target and effect.

Recorded results are hydrated through the owner-aware task query and checked exactly against the
bound task, Provider, Agent, Worker, model configuration, model, Session and Directory. Replay and
all pre-permit conflicts invoke neither callback and never call the legacy chain.

Fresh execution preserves the accepted coordinator order:

1. content-free plan/envelope and receipt preparation;
2. valid effect permit and route preparation;
3. fresh preparation callback;
4. guarded Provider artifact, persistence and lifecycle confirmation;
5. exact result and fresh completion callback;
6. result recheck and receipt recording.

The ordinary `ThreadLocal` scope is non-nestable, non-inheritable and single-use. Nested calls
poison the outer scope. Success, replay and every failure remove the frame in `finally`; a swallowed
mismatch cannot turn into a successful scoped response.

## Validation record

- production compile: `mvn -pl session-module -am -DskipTests compile` — `BUILD SUCCESS`
  in 15.970s
- initial seven exact selectors: 7/7 passed, failure/error/skip 0, `BUILD SUCCESS` in
  26.183s
- authority review found one P1: recorded replay did not bind the C1A upstream-system fact; the
  scope now binds nullable upstream-system and upstream-user values with explicit null tags and
  length prefixes
- final seven exact selectors after the bounded correction: 7/7 passed, failure/error/skip 0,
  `BUILD SUCCESS` in 30.252s
- independent reviews: ThreadLocal/callback ordering `ACCEPT`; pipeline/legacy/C1C bridge
  `ACCEPT`; authority/envelope review `ACCEPT` after the upstream-system P1 correction; no
  remaining P1/P2
- reused unchanged evidence: the accepted C1B0A/B0B coordinator tests already prove non-permit
  participant zero-effect, completion-before-record and post-permit ambiguity semantics
- whole module/reactor, E2E, live/provider and final joint full cycle: not run; not part of this
  slice

No service or Worker was started. No business or historical data was read or modified. No repair,
backfill, replay, reconciliation, deletion or historical fact synthesis was performed. The
untracked `BOOT-INF/` directory was not read or changed.
