# Java Architecture Governance Main Release Closure - 2026-06-26

## Release Conclusion

This release candidate is ready to merge into `main`.

- release_branch: `main`
- source_branch: `origin/qd-win11/dev`
- candidate_branch: `codex/release-1.3.1-main-candidate`
- base_main_before_merge: `f174c526`
- candidate_commit: `ab7b314f`
- release_scope: OPT-001 Java architecture governance, Stage 1/2 through Stage 10
- release_decision: ready-for-main

## Scope

Included changes:

1. Provider route registry and provider/backend capability consolidation.
2. `TaskDispatchFacade` responsibility split into registry, create target resolver, operation router, and projection service.
3. Provider state codec for `providerStateJson` and `taskStateJson`.
4. SSE single-instance / sticky-session deployment boundary and cleanup hardening.
5. Production profile hardening and startup guard.
6. `TaskQueryProvider` narrow port SPI and registry/facade injection narrowing.
7. Provider listing/search typed envelope.
8. LangGraph worker-session provider split.
9. LangGraph task service migration from aggregate `TaskQueryProvider` to lookup / command narrow ports.
10. Architecture, quality, coverage, and acceptance records under `docs/version-tracker/1.3.1-SNAPSHOT`.

Not included:

1. Maven artifact version migration from `1.0.0-SNAPSHOT` to a non-SNAPSHOT release version.
2. Non-sticky multi-instance SSE event bus implementation.
3. Full migration of Claude/Codex/Gemini task services away from aggregate `TaskQueryProvider`.
4. `TaskListingProvider` strictly typed method signature migration.
5. Production database migration tooling such as Flyway or Liquibase.

## Version Strategy

Current project release operation uses `main` as the release pointer. This release therefore merges the validated candidate into `main` without changing Maven module versions.

The Maven reactor still builds as `1.0.0-SNAPSHOT`. This is an existing repository convention and is intentionally not changed in this closure because changing it would require a separate repository-wide versioning pass, updates to build/run documentation, and another full release validation.

Tag policy:

- No existing Git tag convention was found in this repository.
- No release tag is created in this closure.
- If a tag is required later, create it from the final `main` commit after confirming the tag naming convention and whether artifact versions must be aligned.

## Verification

### Full Maven Test

Command:

```powershell
mvn test
```

Result:

- status: pass
- Surefire XML files: 251
- tests: 1774
- failures: 0
- errors: 0
- skipped: 0

### Diff Check

Command:

```powershell
git diff --check origin/main..HEAD
```

Result:

- status: pass
- whitespace errors: 0

### Launcher Package Check

Command:

```powershell
mvn -pl launcher -am package -DskipTests
```

Result:

- status: pass
- artifact: `launcher/target/launcher-1.0.0-SNAPSHOT.jar`
- size: 91,543,837 bytes
- sha256: `AAF122E115FFE545AC622D1065551FB94C66C64C199E94AE53925E57CAEFF933`

## Merge Readiness

The merge from `origin/main` to `origin/qd-win11/dev` was a fast-forward candidate with no conflicts.

After this record is committed, `main` can be fast-forwarded to the release closure commit and pushed.

## Rollback

If production deployment needs rollback after main merge, revert or reset the deployed checkout to `f174c526`, the `origin/main` commit before this release candidate. Database schema migration is not part of this release; production profile requires schema to already be compatible with the application.

## Follow-up

Recommended next engineering items:

1. Migrate Gemini/Codex/Claude task services away from aggregate `TaskQueryProvider`.
2. Add strictly typed `TaskListingProvider` methods and deprecate the legacy `Object` return path.
3. Decide repository-wide Maven release versioning policy.
4. Introduce database migration tooling before relying on `prod` profile for broader deployments.
