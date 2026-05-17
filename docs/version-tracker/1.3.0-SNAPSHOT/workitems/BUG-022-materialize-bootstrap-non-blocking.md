# BUG-022 Materialize Should Not Block First Bootstrap

## Status

- Version: `1.3.0-SNAPSHOT`
- Status: implemented locally, targeted tests passed
- Date: 2026-05-17

## Background

During the first platform bootstrap for the TMS X3 ClientApp/BizWorker integration, upstream bundle sync may request `materialize=true` before real Skill markdown, resource files, or Function resources are ready. The previous `syncSkillBundle` path called Worker materialize synchronously and propagated Worker errors, so a transient Worker/materialize failure could roll back ClientApp skill registration and make first bootstrap fragile.

The bootstrap path should persist platform metadata first. Materialize is a runtime convenience step and must be safe to retry later.

## Requirements

1. `syncSkillBundle` / upstream bundle sync must not roll back when materialize has no real content or the Worker materialize endpoint is not ready.
2. If the bundle has no markdown, no resources, and no functions, materialize should be skipped with status `SKIPPED_NO_CONTENT`.
3. The empty-content skip should emit only one `warn` log for the sync attempt.
4. If the Worker materialize call fails in the sync path, return a `FAILED` materialize result and emit one `warn`, but keep the bundle, legacy skill index, grant, and allowlist writes committed.
5. Explicit operator materialize APIs should remain strict so manual remediation still surfaces failures clearly.

## Implementation

- `SkillRegistryService.syncSkillBundle` now uses a best-effort materialize wrapper for bundle sync.
- Empty bundles are detected before calling Worker materialize.
- Worker HTTP failures are converted to a materialize result and returned to the caller instead of escaping the sync transaction.
- The existing strict materialize implementation is preserved for explicit materialize operations.

## Tests

Added focused unit coverage:

- Empty sync bundle with `materialize=true` returns `SKIPPED_NO_CONTENT` and does not call the Worker.
- Worker `503` during sync materialize returns `FAILED` but still persists bundle, skill, and grant data.

Command to run:

```bash
mvn -pl business-agent-module -am "-Dtest=SkillRegistryServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## Open Items

- Add a later retry or reconciliation command for bundles whose sync materialize result is `FAILED`.
- Decide whether `SKIPPED_NO_CONTENT` should be exposed in upstream CLI output as a soft warning.
- After real TMS Skill/Function resources are available, run a live materialize smoke on dev-kvm-x3.
- Keep production deployment pull-and-run only; build/materialize recovery remains a platform operation, not a production server build step.
