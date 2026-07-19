# GOV-001 P1C-A Typed-management CLI Operator Runbook

- version: `1.4.3-SNAPSHOT`
- related work item: [GOV-001 P1C-A](../workitems/GOV-001-p1c-a-cli-skill-operator-ux.md)
- status: operator UX / fixture-only typed-management inspection
- scope: read-only CLI preflight; no seed, issuance, route cutover, external enablement, Worker change, or release

## Use this lane only for typed-management inspection

`navi upstream auth whoami` and `navi upstream inspect permissions` use exactly one
`NAVI_PRINCIPAL_CREDENTIAL`, optionally selected by
`--principal-credential-env <name>`. They send that value only in
`X-Navi-Principal-Credential` to the typed-management namespace:

```text
GET  /api/v1/management/v1/auth/whoami
GET  /api/v1/management/v1/auth/permissions
POST /api/v1/management/v1/auth/explain
```

Never put a credential value in a command line, log, test fixture, issue, or tracked
profile. Use a gitignored local profile or process environment. The CLI rejects before
HTTP dispatch when a typed principal credential is absent, an explicit source is
ambiguous, or any legacy credential lane is present.

The P1B-A fixture endpoint currently does **not** provide `schemaVersion` or a
credential fingerprint in `whoami`/`permissions`. The CLI prints
`NOT_SUPPLIED_BY_SERVER` for those fields; it does not compute, infer, or substitute
them from local metadata. This is an endpoint-contract limitation, not evidence that a
real S1/S2 principal exists.

## S1 / S2 interpretation

| Situation | Allowed interpretation | Do not infer |
| --- | --- | --- |
| S1 `foggy-world-sim` dedicated Navigator instance | A future `INSTANCE_ROOT` typed principal may show its own authority ceiling and the presented credential's effective actions separately. | A legacy `NAVI_ADMIN_API_KEY` is S1 root authority, or a capability/task token is management authority. |
| S2 `tms-x3` platform management | A future `SAAS_PLATFORM` typed principal may manage platform-scoped resources only through the typed lane granted by Navigator. | A tenant ClientApp control/runtime credential becomes platform/security authority. |
| Tenant ClientApp / upstream user | Runtime and control credentials stay resource/tenant scoped and are not accepted for typed management inspection. | Matching tenant, owner text, ClientApp ID, or local profile metadata proves a server grant. |

`authorityCeilingActions` describes the subject-wide maximum defined by the server;
`effectiveCredentialActions` describes the exact credential currently presented. Do not
union them. `config check` emits only `VALID`, `INVALID`, or `UNVERIFIED` local states
and always reports `authorization=UNVERIFIED`; it is never an allow decision.

## Non-binding explain preflight

Use the explicit form only for a registered typed-management route/action:

```text
navi upstream inspect permissions --explain-auth \
  --route-id <registered-route-id> --action-id <registered-action-id> \
  [--target-reference <safe-ref> --impact-reference <safe-ref> --reason-reference <safe-ref>]
```

The three references are all-or-none opaque identifiers. They are redacted from CLI
output and never become a legacy target resolver. A successful response must say
`preflight=PREFLIGHT` and `nonBinding=true`; its result cannot authorize a mutation.
The mutation path must re-authorize exact owner, grant, tenant, ClientApp, action, and
current credential facts server-side.

The CLI derives this narrow input guard from the build-time packaged canonical route
manifest. A missing, malformed, or checksum/count-mismatched manifest fails closed.
That catalog only prevents an invalid preflight request; it never grants access or
replaces the server's route/action/target authorization decision.

## Boundary reminders

- `NAVIGATOR_EXTERNAL_ENABLED` only gates `/api/v1/open/**` routing. It is not Provider,
  Worker Gateway, Worker, or production readiness.
- `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` is strict Worker-principal validation, not
  a network-exposure switch. It remains unavailable until every caller propagates the
  complete Worker principal and lease headers.
- Codex stays on the existing Physical Worker path: `worker-host verify`, then
  `worker-host update --worker-id <physicalWorkerId>` with `claudeCode.codexConfig`.
  Do not create a BizWorkerIdentity, direct Codex identity, WorkerPool member, or extra
  Worker to work around a Codex route.
- Source CLI `1.0.21` is newer than the published `tools/navigator-upstream` `1.0.18`
  archive. `SOURCE_NEWER_THAN_PUBLISHED` is drift information only; it does not claim a
  new package has been published.

## Explicitly out of scope

This runbook does not approve a real principal/credential inventory, credential
issuance/rotation/revocation, legacy route enforcement, `NAVIGATOR_EXTERNAL_ENABLED`,
Gateway strictness, Worker external execution, production traffic, or a release. Each
requires its separately approved gate and independent signoff.
