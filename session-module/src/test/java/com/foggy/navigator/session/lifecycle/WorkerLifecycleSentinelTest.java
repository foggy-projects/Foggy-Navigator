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
        assertThat(calls).hasValue(4);
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

    @Test
    void expiredLeaseCanBeTakenOverByAnotherSentinel() {
        AtomicInteger attempts = new AtomicInteger();
        SentinelLeaseStore leases = (worker, holder, now, duration) ->
                attempts.incrementAndGet() == 1
                        ? Optional.empty()
                        : Optional.of(new SentinelLease(worker, holder, 2));
        AtomicInteger calls = new AtomicInteger();

        assertThat(sentinel(leases).reconcile(
                "worker-fixture", SentinelTrigger.TIMER,
                port(calls, ID, 1, 1)).state())
                .isEqualTo(SentinelReconcileState.LEASE_NOT_ACQUIRED);
        assertThat(sentinel(leases).reconcile(
                "worker-fixture", SentinelTrigger.TIMER,
                port(calls, ID, 1, 1)).state())
                .isEqualTo(SentinelReconcileState.READY);
        assertThat(calls).hasValue(4);
    }

    @Test
    void generationResetFailsClosedButNewEpochRebindsAndConsumesEvents() {
        AtomicInteger calls = new AtomicInteger();
        WorkerLifecycleSentinel generationSentinel = sentinel(acquired());
        generationSentinel.reconcile(
                "worker-fixture", SentinelTrigger.STARTUP,
                port(calls, ID, 1, 1));
        SentinelReconcileResult generation = generationSentinel.reconcile(
                "worker-fixture", SentinelTrigger.TIMER,
                port(calls, new WorkerLifecycleIdentity(
                        "worker-fixture", "generation-2", "epoch-1"), 2, 2));
        assertThat(generation.state())
                .isEqualTo(SentinelReconcileState.STATE_GENERATION_RESET);

        WorkerLifecycleSentinel epochSentinel = sentinel(acquired());
        epochSentinel.reconcile(
                "worker-fixture", SentinelTrigger.STARTUP,
                port(calls, ID, 1, 1));
        int beforeEpochMismatch = calls.get();
        SentinelReconcileResult epoch = epochSentinel.reconcile(
                "worker-fixture", SentinelTrigger.TIMER,
                port(calls, new WorkerLifecycleIdentity(
                        "worker-fixture", "generation-1", "epoch-2"), 2, 2));
        assertThat(epoch.state()).isEqualTo(SentinelReconcileState.READY);
        assertThat(epoch.identity().instanceEpoch()).isEqualTo("epoch-2");
        assertThat(calls.get() - beforeEpochMismatch).isEqualTo(4);
    }

    @Test
    void incompleteInventoryIsCoverageGapAndIsNeverAcknowledged() {
        AtomicInteger calls = new AtomicInteger();
        WorkerLifecyclePort incomplete = new WorkerLifecyclePort() {
            @Override
            public WorkerLifecycleReadiness probe(String worker) {
                calls.incrementAndGet();
                return new WorkerLifecycleReadiness(
                        true, ID, Set.of("INVENTORY_V1"), List.of());
            }

            @Override
            public WorkerLifecycleSnapshot inventory(
                    WorkerLifecycleIdentity expected, long after) {
                calls.incrementAndGet();
                return new WorkerLifecycleSnapshot(
                        ID, 1, 1, false, List.of(), List.of());
            }

            @Override
            public long acknowledge(
                    WorkerLifecycleIdentity expected, long sequence) {
                calls.incrementAndGet();
                return sequence;
            }
        };
        SentinelReconcileResult result = sentinel(acquired()).reconcile(
                "worker-fixture", SentinelTrigger.TIMER, incomplete);
        assertThat(result.state()).isEqualTo(SentinelReconcileState.COVERAGE_GAP);
        assertThat(calls).hasValue(2);
    }

    private SentinelLeaseStore acquired() {
        return (worker, holder, now, duration) ->
                Optional.of(new SentinelLease(worker, holder, 1));
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
            public WorkerLifecycleSnapshot events(
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
