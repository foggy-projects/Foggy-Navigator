---
workitem: NAVI-CORE-001-S4-02B0
status: IMPLEMENTED_FOCUSED_GREEN
date: 2026-08-03
baseline: 8bfb6dcd0534
scope: EXACT_PROVIDER_AGENT_RESOLUTION_PREREQUISITE
---

# NAVI-CORE-001 S4-02B0 exact provider Agent resolution

This narrow prerequisite adds one provider-exact, single-pass runtime Agent resolution seam for
the owner-proven create target plan. It does not connect the S4-02B1 plan to an ingress or Provider
effect.

## Frozen contract

- `providerType`, `agentId` and `AgentResolveContext` are required.
- Resolution considers only `A2aAgentProvider` instances whose provider type exactly matches the
  supplied provider identity. It does not use model mapping, global scans or first-match fallback.
- Each exact-type candidate receives at most one `resolveAgent` call.
- No exact provider, or exact providers with no matching Agent, returns `Optional.empty()` so the
  guarded Facade can emit its existing explicit not-available failure before effect.
- More than one successful exact provider is ambiguous configuration and fails closed with
  `IllegalStateException`; no candidate is selected by registration order.

## Changed paths

- `session-module/src/main/java/com/foggy/navigator/session/registry/UnifiedAgentResolver.java`
- `session-module/src/test/java/com/foggy/navigator/session/registry/UnifiedAgentResolverTest.java`
- this work item

No SPI, addon, Facade/B1 path, POM, ingress, runtime process or business/historical data is changed.

## Focused validation

The only executed test command was the exact three-method selector:

`/usr/bin/time -p mvn -q -pl session-module -am
-Dtest='UnifiedAgentResolverTest#resolveAgentByProviderTypeExact_returnsUniqueMatchWithoutFallbackOrSecondResolution+resolveAgentByProviderTypeExact_returnsEmptyWhenExactProviderOrAgentIsMissing+resolveAgentByProviderTypeExact_rejectsAmbiguityAndInvalidInput'
-Dsurefire.failIfNoSpecifiedTests=false test`

It exited `0` in `30.77 s` (`user 38.33 s`, `sys 5.36 s`). Surefire ran exactly `3` tests with
failures, errors and skips all zero. The selector proves unique single-pass resolution, missing
provider/Agent as `Optional.empty()`, ambiguity denial, required input, no cross-provider fallback
and zero model-manager interaction.

No entire test class, affected lane, full module/reactor, E2E, live/provider or joint full
validation was run.

## Rollback and data boundary

Rollback is a three-path code/test/document revert. It requires no repair, backfill, replay,
reconciliation or historical-data mutation.
