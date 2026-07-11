---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-004 Codex rate-limit awareness and Mini retirement
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Implementation Quality Gate

## Background

OPT-004 removes Mini from active Codex surfaces and adds owner-only, advisory account quota visibility for the app-server runtime. This gate covers isolated implementation quality only and does not approve production routing.

## Check Basis

- [Workitem](../workitems/OPT-004-codex-rate-limit-awareness-no-fallback.md)
- Worker protocol/cache/error tests and `0.3.0` release package.
- Java owner proxy, compatibility tests, PC unit/build and live Playwright evidence.
- [Durable evidence](../evidence/OPT-004-rate-limit-awareness-v1.json)

## Changed Surface

- `tools/codex-agent-worker`: default/direct/post-alias Mini rejection.
- `tools/codex-app-server-worker`: sanitized quota protocol, runtime read, cache/invalidation, stable error, Mini startup fail-fast, model-nudge suppression and `0.3.0` capability.
- `addons/codex-worker-agent`: owner-only proxy, identity validation and old Worker compatibility.
- `packages/navigator-frontend`: multi-bucket quota panel, polling, refresh, stale protection and responsive layout.
- `launcher`: controller ownership integration test constructor alignment.

## Quality Checklist

| Check | Result | Notes |
|---|---|---|
| scope conformance | pass | quota remains advisory-only; routing, queue and task state are untouched |
| code hygiene | pass | no debug branch, fallback model or temporary switch action remains |
| duplication/consolidation | pass | Worker parser owns sanitization; Java owns trust-boundary projection; PC consumes typed DTO |
| complexity/abstraction | pass | lane cache and runtime endpoint stay within existing pool/executor boundaries |
| error handling | pass-with-risk | unsupported/unknown/stale are explicit; only structured usage-limit/429 maps to account-limited |
| privacy | pass | plan, credits, reset-credit ids, auth, home and raw payload are excluded |
| compatibility | pass | old Worker 404 becomes typed `UNSUPPORTED`; Mini aliases fail closed |
| concurrency | pass | TTL cache is per lane and singleflight; updates colliding with reads use versioned reread; failed-lane versions are reclaimed; PC requests are instance/sequence guarded |
| documentation | pass | active docs, workitem, release version and acceptance evidence updated |
| release readiness | pass-isolated | full Worker package and launcher package complete; production remains gated |

## Findings

- App-server sparse update cannot safely replace a full multi-bucket snapshot, so the implementation invalidates and rereads instead of merging partial fields.
- Initial PC live smoke exposed 500 errors from N-1 Workers without the endpoint. Java now normalizes only HTTP 404 to `UNSUPPORTED`; auth and other failures still propagate.
- Launcher ownership integration used the old controller constructor. The test assembly was fixed and launcher packaging rerun successfully.
- The optional provider `limitName` is documented as user-facing and is allowed through; plan, credit and authentication fields remain blocked.
- Independent review found and closed: missing strict instance proof, default quota reads inheriting service API credentials, update/read invalidation loss, Mini alias/default configuration gaps, failed-lane version retention and same-revision PC instance rotation.
- Playwright found a 320px runtime id wrapping one character per line. The mobile header now uses a fixed action column and a single-line ellipsis with the full id in `title`.
- App-server threads pin `notice.hide_rate_limit_model_nudge=true` after request overrides, so the removed Mini prompt cannot reappear while ordinary `request_user_input` remains enabled.

## Risks / Follow-ups

- The contract is pinned to CLI `0.144.1`; any CLI upgrade requires schema and live reread validation.
- Cache is process memory with 60-second TTL. Restart causes a fresh read and does not preserve a stale disk snapshot.
- Real quota exhaustion was not manufactured; structured mapping and PC terminal states are automated, but no account credits were intentionally consumed.
- Per-task API key and Biz Codex Home quota views are deliberately out of scope.

## Recommended Next Skills

- Proceed to `foggy-test-coverage-audit`, then isolated `foggy-acceptance-signoff`.
- Use `foggy-bug-regression-workflow` if a future CLI emits a new structured limit error or quota shape.

## Decision

- decision: ready-with-risks
- decision_scope: OPT-004 isolated quota observability and Mini retirement
- production_routing_change: no
- production_enablement: not-approved
- follow_up_required: yes
