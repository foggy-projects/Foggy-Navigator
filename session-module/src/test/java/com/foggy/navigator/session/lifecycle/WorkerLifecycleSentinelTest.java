package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.NormalizedLifecycleFact;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLifecycleSentinelTest {

    private static final WorkerLifecycleIdentity ID =
            new WorkerLifecycleIdentity("worker-fixture", "generation-1", "epoch-1");

    @Test
    void staleLeasePreventsEndpointStorm() {
        AtomicInteger calls = new AtomicInteger();
        WorkerLifecycleSentinel sentinel = sentinel((worker, holder, now, duration) ->
                Optional.empty());
        SentinelReconcileResult result =
                sentinel.reconcile("worker-fixture", SentinelTrigger.TIMER, port(calls, ID, 1, 1));
        assertThat(result.state()).isEqualTo(SentinelReconcileState.LEASE_NOT_ACQUIRED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void lifecycleStreamLossReconcilesOnceAtWorkerLevel() {
        AtomicInteger calls = new AtomicInteger();
        WorkerLifecycleSentinel sentinel = sentinel((worker, holder, now, duration) ->
                Optional.of(new SentinelLease(worker, holder, 1)));
        SentinelReconcileResult result = sentinel.reconcile(
                "worker-fixture", SentinelTrigger.LIFECYCLE_STREAM_LOSS,
                port(calls, ID, 1, 3));
        assertThat(result.state()).isEqualTo(SentinelReconcileState.READY);
        assertThat(result.canonicalMutationSuppressed()).isTrue();
        assertThat(calls).hasValue(3);
    }

    @Test
    void coverageGapFreezesInsteadOfInventingFacts() {
        AtomicInteger calls = new AtomicInteger();
        WorkerLifecycleSentinel sentinel = sentinel((worker, holder, now, duration) ->
                Optional.of(new SentinelLease(worker, holder, 1)));
        sentinel.reconcile("worker-fixture", SentinelTrigger.STARTUP, port(calls, ID, 1, 2));
        SentinelReconcileResult second = sentinel.reconcile(
                "worker-fixture", SentinelTrigger.TIMER, port(calls, ID, 5, 6));
        assertThat(second.state()).isEqualTo(SentinelReconcileState.COVERAGE_GAP);
        assertThat(second.facts()).isEmpty();
    }

    private WorkerLifecycleSentinel sentinel(SentinelLeaseStore leases) {
        return new WorkerLifecycleSentinel(
                "fixture-owner", leases,
                Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private WorkerLifecyclePort port(
            AtomicInteger calls,
            WorkerLifecycleIdentity identity,
            long min,
            long through) {
        return new WorkerLifecyclePort() {
            public WorkerLifecycleReadiness probe(String worker) {
                calls.incrementAndGet();
                return new WorkerLifecycleReadiness(true, identity, Set.of("INVENTORY_V1"), List.of());
            }
            public WorkerLifecycleSnapshot inventory(
                    WorkerLifecycleIdentity expected, long after) {
                calls.incrementAndGet();
                return new WorkerLifecycleSnapshot(
                        identity, min, through, true, List.of(),
                        List.<NormalizedLifecycleFact>of());
            }
            public long acknowledge(WorkerLifecycleIdentity expected, long sequence) {
                calls.incrementAndGet();
                return sequence;
            }
        };
    }
}
