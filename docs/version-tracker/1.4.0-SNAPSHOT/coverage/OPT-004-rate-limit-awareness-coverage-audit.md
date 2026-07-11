---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.4.0-SNAPSHOT
target: OPT-004 Codex rate-limit awareness and Mini retirement
status: reviewed
conclusion: ready-with-gaps
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Test Coverage Audit

## Background

This audit maps OPT-004 requirements to protocol, integration, release and browser evidence for the isolated CLI `0.144.1` runtime.

## Audit Basis

- [Workitem](../workitems/OPT-004-codex-rate-limit-awareness-no-fallback.md)
- [Implementation quality gate](../quality/OPT-004-rate-limit-awareness-implementation-quality.md)
- [Durable evidence](../evidence/OPT-004-rate-limit-awareness-v1.json)
- [Desktop screenshot](../evidence/OPT-004-pc-desktop-1280.png)
- [320px screenshot](../evidence/OPT-004-pc-mobile-320.png)

## Coverage Matrix

| Requirement | Risk | Evidence layers | Evidence | Conclusion |
|---|---|---|---|---|
| Mini direct/default/alias retirement | major | config, unit, HTTP, live | both Workers startup fail-fast; live 400/no journal; manifest scan | covered |
| sanitized full snapshot | critical | protocol, HTTP, live | allowlist/privacy tests and two real buckets | covered-isolated |
| sparse update semantics | major | runtime, pool | notification invalidation, in-flight version reread and failed-lane cleanup tests | covered |
| lane TTL/singleflight isolation | major | unit/integration | pool lane/cache/concurrency tests | covered |
| no routing/model/task mutation | critical | unit/integration | limited lane still acquires; exact Ultra model retained | covered |
| stable quota error | major | unit/task-store | structured usage limit/429 positive and false-positive tests | covered-with-live-gap |
| owner/instance boundary | critical | Worker HTTP, Java unit, live | strict proof 400/409, owner 200, unauthenticated Java 401; authenticated non-owner is not live-forced | covered-with-live-gap |
| N-1 Worker compatibility | major | Java regression/live | 404 -> 200 `UNSUPPORTED` for rev1-3 | covered |
| PC multi-bucket/stale/refresh | major | component/integration/live | 208 PC tests, instance-rotation regression and owner endpoint browser flow | covered-isolated |
| desktop/320px layout | major | Playwright/manual | durable screenshots, 320/320 scroll-width and runtime-id ellipsis check | covered-isolated |
| release artifact | major | full suite/package | Worker `0.3.0` archive and launcher package | covered-isolated |

## Evidence Summary

- App-server Worker: `232 total / 225 passed / 7 skipped / 0 failed`; schema verify, typecheck, build and release package passed.
- Archive: SHA-256 `8C8CB446C861F5AD0AF04DDAAA37EA98670B515B435A6BA881AFC675CF3FA5C5`, `1,724,854` bytes, `190` entries.
- SDK Worker: `121/121`, typecheck and build passed.
- Java Codex dependency reactor: `283/283` (including the N-1 compatibility regression); launcher ownership `1/1`; launcher package passed.
- Navigator PC: `208/208`; `vue-tsc` and production Vite build passed.
- Live runtime revision 4 returned two buckets through Worker and Java; rev1-3 returned typed unsupported, unauthenticated Java returned 401.
- Browser DOM contained no retired Mini or model-switch prompt; 320px document/body scroll width stayed 320, and the long runtime id remained a single ellipsized line.

## Gaps

- A real exhausted account was not forced. Stable terminal mapping is automated but not backed by a destructive live quota-consumption test.
- Per-task API-key and Biz Codex Home lanes are not displayed by design.
- Authenticated same-tenant non-owner rejection is covered by Java mocks, not a second live user fixture; live evidence contains owner 200 and unauthenticated 401.
- Browser has one pre-existing `/favicon.ico` 404; quota requests, page errors and application console errors are zero.
- P3 production samples, 72-hour soak and rotation evidence remain absent.

## Recommended Next Skills

- The isolated core can proceed to `foggy-acceptance-signoff` with the live-exhaustion and production gaps recorded.
- Future protocol drift or production failures should enter `foggy-bug-regression-workflow` before expanding support.

## Conclusion

- conclusion: ready-with-gaps
- mini_retirement: covered
- advisory_quota_visibility: covered-isolated
- real_exhaustion: gap-nondestructive
- can_enter_feature_acceptance: yes-isolated
- production_enablement: not-approved
