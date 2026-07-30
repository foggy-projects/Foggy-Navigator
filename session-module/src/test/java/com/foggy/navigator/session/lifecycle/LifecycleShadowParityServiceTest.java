package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleShadowParityServiceTest {

    private final LifecycleShadowParityService.CanaryTuple canary =
            new LifecycleShadowParityService.CanaryTuple(
                    "codex-biz-worker", "fixture-tenant", "fixture-worker");

    @Test
    void sharedRuntimeDoesNotEnrollCodexWorkerProvider() {
        LifecycleParityReport report = new LifecycleShadowParityService().compare(
                "codex-worker", "fixture-tenant", "fixture-worker", canary,
                "IN_PROGRESS", "IN_PROGRESS", true, true, 0);
        assertThat(report.exactCanaryTuple()).isFalse();
        assertThat(report.passes()).isTrue();
    }

    @Test
    void durabilityFailureBlocksParityWithoutOwnerEffect() {
        LifecycleParityReport report = new LifecycleShadowParityService().compare(
                "codex-biz-worker", "fixture-tenant", "fixture-worker", canary,
                "IN_PROGRESS", "IN_PROGRESS", true, false, 0);
        assertThat(report.blockers()).containsExactly("LIFECYCLE_DURABILITY_UNAVAILABLE");
        assertThat(report.ownerEffectCount()).isZero();
    }
}
