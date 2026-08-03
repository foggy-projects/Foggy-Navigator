---
workitem: NAVI-CORE-001-S4-BUS-D0E2
status: IMPLEMENTED_REVIEWED
date: 2026-08-04
baseline: 379c67a6
coordination_freeze: 309fec3
canonical_contract: /home/sa/ultra/sim-navi/docs/decisions/NAVI-CORE-001-core-runtime-scope-and-complexity-convergence-delivery-spec.md
scope: BUSINESS_CREATE_SDK_ADDITIVE_REQUEST_IDENTITY
---

# NAVI-CORE-001 S4-BUS-D0E2 SDK additive request identity

This consumer-side slice adds an explicit request-identity overload without changing the existing
Business Task create method or its fresh, one-time-token behavior.

## Exact paths

1. `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/BusinessAgentApi.java`
2. `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/api/BusinessAgentApiSmokeTest.java`
3. `navigator-open-sdk/pom.xml`
4. `navigator-open-sdk/src/main/resources/com/foggy/navigator/sdk/cli/authorization-provenance.properties`
5. `navigator-open-sdk/src/test/java/com/foggy/navigator/sdk/cli/UpstreamCliTest.java`
6. This work item.

## Compatibility and identity

- The existing one-argument method retains its exact descriptor and body and sends no request-ID
  header. Existing TMS and other consumers therefore retain source, binary and wire compatibility.
- The new overload requires a nonblank caller-supplied ID before HTTP, then transports the value
  unchanged in `X-Navigator-Client-Request-Id` on the same POST path with the same Form and DTO.
- The SDK does not trim, parse, canonicalize or generate identity. Navigator D0D remains the only
  UUID-validation and canonical identity authority.
- The local snapshot version is advanced from `1.0.41-SNAPSHOT` to `1.0.42-SNAPSHOT`; the artifact
  is not installed, published or adopted by TMS in this slice.
- Final review found that the first four-path draft left packaged CLI provenance at source version
  `1.0.41-SNAPSHOT`. D0E2R therefore synchronizes only that source version and its exact regression
  assertion. Published version, drift classification, manifest count/digest and manifest bytes do
  not change.

## Focused validation

- Affected production compile: PASS (`1.0.42-SNAPSHOT`, `8.477s`).
- Three exact create selectors: PASS (`3/3`, failures/errors/skips all zero, `9.606s`), covering
  the existing one-argument method, explicit identity transport and null/blank pre-network reject.
- Skip-tests package and artifact-name check: PASS (`7.704s`), producing
  `navigator-open-sdk-1.0.42-SNAPSHOT{,-sources}.jar`; no install or publish occurred.
- Initial final reviews: one P2 and two accepts. The P2 correctly identified the packaged source
  version mismatch; the initial four-path draft was not committed.
- Revised exact provenance selector: PASS (`1/1`, failures/errors/skips all zero, `6.836s`).
- Revised skip-tests package: PASS (`7.244s`). Direct JAR inspection confirms source
  `1.0.42-SNAPSHOT`, published `1.0.40-SNAPSHOT`, drift `SOURCE_AHEAD_OF_PUBLISHED`, and unchanged
  manifest count/digest.
- Revised independent final read-only reviews: PASS (`3/3 ACCEPT`, no P1/P2). The reviews confirmed
  exact six-path scope, old/new SDK descriptor compatibility, pre-network missing-ID rejection,
  unchanged server authority, synchronized POM/JAR provenance, unchanged published/manifest facts,
  and package-only/no-install discipline.
- No whole test class/module/reactor, TMS, E2E, live runtime or final joint full validation is run.
  Final joint budget remains `0/3 consumed`.
- Tests use a local disposable HTTP server only. No service/Worker or historical/existing data is
  read or mutated.

## Stop conditions

Stop and replan if a seventh path is required; the old method changes; the SDK interprets or mints
identity; server, DTO, HttpHelper, CLI production code, TMS, schema or another POM/resource changes;
published/drift/manifest provenance or route/body/DTO wire drifts; an install/publish is required;
historical data or `BOOT-INF/` is touched.
