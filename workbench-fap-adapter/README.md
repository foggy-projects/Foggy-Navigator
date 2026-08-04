# Workbench FAP Adapter

This optional module is the isolated compatibility backend for the owner's new
Foggy Agent Platform Workbench canary. It is absent from default/company builds and
is included only with the Maven profile `fap-workbench-canary`.

Two independent gates are required:

1. package with `-Pfap-workbench-canary`;
2. set `navigator.workbench.fap.enabled=true` and include the logged-in owner in
   `owner-user-ids`.

The module creates only new `FAP_V1` conversation bindings. It never reads or
migrates legacy Session/Task data, and it never falls back to a legacy execution
path after a FAP request has been bound. Its table stores product ownership, safe
resource refs, request IDs, Runtime IDs, and effective scopes—not transcripts,
tickets, routes, credentials, grants, or provider facts.

Example private personal-canary configuration (all secrets outside Git):

```yaml
navigator:
  workbench:
    fap:
      enabled: true
      owner-user-ids: ["replace-owner-user-id"]
      access-base-uri: http://127.0.0.1:4860
      runtime-base-uri: http://127.0.0.1:4850
      caller-application-ref: navigator-workbench
      access-bearer-token: ${FAP_WORKBENCH_ACCESS_TOKEN}
      runtime-bearer-token: ${FAP_WORKBENCH_RUNTIME_TOKEN}
      internal-principal-prefix: "navigator-user:"
      environment-class: DEV
```

Build the FAP Java client into the local Maven repository first, then run focused
adapter tests/builds with the profile. Default Navigator builds do not require the
new SDK. Private package-registry publication remains a promotion prerequisite,
not a reason to copy DTOs or client source into Navigator.

Development `ddl-auto=update` creates the new binding table for disposable canary
data. A forward migration is required before any shared/production promotion; no
old data backfill or legacy conversation import is planned.

The frontend counterpart is the separate `/workers/fap` route. Its navigation item
appears only when `/api/v1/workbench/fap/availability` reports that the packaged
module, runtime switch, and owner allowlist all admit the current user. It does not
import `useTaskPane`, the legacy Session transport, or unified SSE. Event polling is
active only while the FAP page is active, stops after a definitive terminal state,
and pauses after three consecutive failures until the user resumes it.
