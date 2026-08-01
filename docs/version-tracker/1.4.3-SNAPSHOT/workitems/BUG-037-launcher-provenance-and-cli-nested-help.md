# BUG-037 Launcher provenance and CLI nested help

## Status

- State: `READY_FOR_SIGNOFF`
- Approved at: 2026-08-01
- Assurance: elevated
- Delivery type: cross-module bug fix

## Approved goal

Fix the launcher build provenance so a packaged candidate reports the exact repository commit and clean/dirty state used for that build, and make nested CLI help a parsing-only, zero-side-effect path.

## Scope

- Launcher Maven provenance generation, packaging safeguards, and focused regression tests.
- `navigator-open-sdk` CLI argument parsing/help dispatch and focused regression tests.
- A local CLI package and launcher package for verification.
- Controlled local restart of the current-workspace Navigator instance on port 8112.

## Non-goals and boundaries

- Do not modify the SIM repository.
- Do not modify or rebind Worker, Agent, model configuration, Directory, grants, or credentials.
- Do not operate on the 3151 Worker.
- Do not access TMS or replay an existing task.
- Do not print credentials, tokens, authorization headers, prompts, responses, or business data.
- Do not push, tag, upload, publish, or release.

## Decisions

- Help is intercepted after syntactic option validation and before profile/config loading or runtime-lane validation.
- Both `--help` and `-h` are normalized to the same pure help path.
- Provenance generation must be lifecycle-bound explicitly and packaging must reject a stale or mismatched generated resource.
- Final runtime evidence is collected only from a launcher built from the final local commit with a clean worktree.

## Acceptance criteria

1. A clean launcher package contains `git.commit.id.full` equal to the build commit and `git.dirty=false`.
2. The packaged build version and build time are non-empty.
3. A stale `target/classes/git.properties` cannot silently enter the launcher artifact.
4. `/actuator/health` is `UP`; `/actuator/info` matches the final local commit, reports clean, and has non-empty version/time.
5. `runtime token`, `runtime readiness`, `runtime owner-smoke`, and `config check` print command help and exit 0 for both `--help` and `-h`.
6. Required canonical help and corresponding legacy alias help do not load profiles, issue runtime tokens, create runtime request IDs/audits, or send HTTP requests.
7. Focused tests, affected Maven tests, launcher package, and local CLI package pass.
8. Final evidence includes the local commit, launcher SHA-256, CLI version/build ID/Linux SHA, installation/restart guidance, and boundary confirmations.

## Validation obligations and budget

- Lightweight: focused provenance and CLI help tests; at most 3 iterations per suite.
- Medium: affected Maven tests and clean package; at most 2 clean package iterations.
- Runtime: one controlled 8112 restart and read-only health/info/help observations; no task execution.
- No expensive live model submission or cross-system validation is authorized or required.

## Initial evidence

- Repository entered this work item at clean `main` commit `f488c4f58605ebe02c3cb695dcbb384c8b4002e6`.
- Running launcher SHA-256 was `8c7edbd77d635b7b6a0812a3466a549f8d18ec83dc7014a0eb1e6b6318a3d3fa`.
- Its packaged `git.properties` reported stale commit `fdef79c9c55e7de9a5b01822c3c9dc0c75ca2e00` and `dirty=true`, while build-info had a later timestamp.

## Implementation record

- Changed paths: `launcher/pom.xml`, `scripts/start-launcher.sh`, launcher metadata tests,
  `navigator-open-sdk` parser/help/version/provenance resources and CLI tests.
- Tests/evidence:
  - Required nested-help regression first failed because profile loading returned exit 2; after the fix its 14 canonical/legacy long/short help cases pass with an unreadable profile fixture, zero HTTP requests and zero client-request IDs.
  - The package guard was exercised with a deliberately stale `git.properties` and skipped generator; package failed at `prepare-package` with the expected stale-commit rejection.
  - Focused launcher provenance tests pass (2 tests).
  - `mvn -pl navigator-open-sdk test` passes (204 tests).
  - `mvn test -pl launcher -am` passes all 14 reactor modules; launcher has 24 tests, 0 failures/errors, and 2 environment-gated skips.
  - Clean `mvn clean package -pl launcher -am -DskipTests` passes all 14 reactor modules on implementation commit `44ead524fb5022e96fd9ea02c751ac68b6f653bb`; the packaged resource reports that exact full commit and `dirty=false` with non-empty version/time. Intermediate launcher SHA-256: `51d3d7310895be10d2146312f4b3700c2bce14bea49393ac90fabc65faddc2a8`.
  - The final doc-only delivery commit is rebuilt once more; its final artifact hashes and local runtime observations are returned in the handoff without changing tracked files again.
- Deviations: none.
- Residual risks: the local CLI package is not a published release and must be reinstalled explicitly by consumers of 1.0.34.
