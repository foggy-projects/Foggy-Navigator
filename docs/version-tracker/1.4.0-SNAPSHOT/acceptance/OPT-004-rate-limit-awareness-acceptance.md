---
acceptance_scope: feature
version: 1.4.0-SNAPSHOT
target: OPT-004 Codex rate-limit awareness and Mini retirement
doc_role: acceptance-record
status: signed-off
decision: accepted-with-risks
production_enablement: not-approved
signed_off_by: Codex
signed_off_at: 2026-07-11
production_signed_off_by: null
production_signed_off_at: null
reviewed_by: Codex
reviewed_at: 2026-07-11
blocking_items: []
follow_up_required: yes
evidence_count: 3
---

# Feature Acceptance

## Background

This record signs off Mini retirement and advisory default-Codex-Home quota visibility in the isolated app-server runtime. It does not approve production routing or change the legacy SDK Worker design beyond rejecting the retired model.

## Acceptance Basis

- [Workitem](../workitems/OPT-004-codex-rate-limit-awareness-no-fallback.md)
- [Implementation quality gate](../quality/OPT-004-rate-limit-awareness-implementation-quality.md)
- [Coverage audit](../coverage/OPT-004-rate-limit-awareness-coverage-audit.md)
- [Evidence](../evidence/OPT-004-rate-limit-awareness-v1.json)

## Checklist

- [x] Direct, default-configured, effort-suffixed and alias-resolved Mini fails closed in both Workers; invalid startup configuration fails fast.
- [x] App-server capability and PC model surfaces do not advertise Mini.
- [x] Quota read exposes only the approved bucket/window/reset fields and is absent from public health.
- [x] Sparse updates invalidate and reread; they do not overwrite a complete snapshot with partial fields.
- [x] Quota state does not gate leases, route tasks, change models or create interaction requests.
- [x] App-server thread hides the local rate-limit model nudge without disabling real business `request_user_input`.
- [x] Worker bearer/instance proof and Java physical-owner checks protect the runtime endpoint.
- [x] Old Workers render typed unsupported instead of browser 500 errors.
- [x] PC shows multiple buckets, primary/secondary windows, reset times, manual refresh and stale states.
- [x] Desktop and 320px views have no horizontal overflow or incoherent overlap.
- [x] Worker `0.3.0`, Java, SDK Worker and PC automated/build evidence passed.

## Evidence

- Worker revision 4 is `READY`, `DARK`, CLI `0.144.1`, instance `codex-store-f203dcb6-2d64-42d8-9d75-3bdc82c06d23`.
- Real owner-only read returned `codex` and `codex_bengalfox` buckets with 5-hour and 7-day windows; the durable evidence records both ids.
- Rev1-3 returned HTTP 200 `UNSUPPORTED`; revision 4 returned `AVAILABLE`; unauthenticated Java returned 401.
- Live Mini POST returned HTTP 400 `UNSUPPORTED_CODEX_MODEL`, created no task journal, and the manifest contained no Mini.
- PC at 1280px and 320px displayed the rev4 buckets; no retired Mini or switch prompt was present. Quota request/page/application errors were zero, document/body width stayed 320, and the long runtime id used a readable ellipsis. See the [desktop](../evidence/OPT-004-pc-desktop-1280.png) and [320px](../evidence/OPT-004-pc-mobile-320.png) screenshots.
- Worker archive SHA-256 is `8C8CB446C861F5AD0AF04DDAAA37EA98670B515B435A6BA881AFC675CF3FA5C5`.

## Failed Items

- OPT-004 isolated core scope: none.
- Real quota exhaustion: not forced, because acceptance must not consume account credits merely to create an error.

## Risks / Open Items

- CLI upgrades require schema, parser and live read revalidation.
- The quota cache is in-memory and rereads after Worker restart.
- Per-task/Biz Codex Home quota and earned reset credits remain out of scope.
- Production P3 gates and samples have not started.

## Final Decision

OPT-004 is accepted with risks for the isolated runtime. Mini is retired without fallback, and app-server quota updates are control-plane observations rather than user questions. Navigator shows the current account windows but never switches models automatically. Production enablement remains not approved.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- acceptance_record: docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-004-rate-limit-awareness-acceptance.md
- isolated_experience: accepted
- blocking_items: none
- production_enablement: not-approved
- P3_entry: not-approved
- signed_off_by: Codex
- signed_off_at: 2026-07-11
- follow_up_required: yes
