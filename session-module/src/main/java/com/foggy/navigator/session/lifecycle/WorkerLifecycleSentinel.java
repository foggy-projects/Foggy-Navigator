package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.WorkerLifecycleIdentity;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePort;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleReadiness;
import com.foggy.navigator.spi.lifecycle.WorkerLifecycleSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Provider-neutral SHADOW reconciler. It never executes a task or owner effect.
 */
public final class WorkerLifecycleSentinel {

    private final String holderId;
    private final SentinelLeaseStore leases;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    public WorkerLifecycleSentinel(
            String holderId,
            SentinelLeaseStore leases,
            Clock clock,
            Duration leaseDuration) {
        this.holderId = holderId;
        this.leases = leases;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public SentinelReconcileResult reconcile(
            String physicalWorkerId,
            SentinelTrigger trigger,
            WorkerLifecyclePort port) {
        return reconcile(physicalWorkerId, trigger, port, ignored -> {
        });
    }

    public SentinelReconcileResult reconcile(
            String physicalWorkerId,
            SentinelTrigger trigger,
            WorkerLifecyclePort port,
            Consumer<WorkerLifecycleSnapshot> beforeAcknowledge) {
        if (leases.tryAcquire(
                physicalWorkerId, holderId, clock.instant(), leaseDuration).isEmpty()) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.LEASE_NOT_ACQUIRED,
                    "SENTINEL_LEASE_HELD");
        }

        WorkerLifecycleReadiness readiness = port.probe(physicalWorkerId);
        if (!readiness.ready()) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.WORKER_UNAVAILABLE,
                    readiness.reasonCodes().isEmpty()
                            ? "LIFECYCLE_WORKER_UNAVAILABLE"
                            : readiness.reasonCodes().get(0));
        }

        Cursor prior = cursors.get(physicalWorkerId);
        WorkerLifecycleIdentity expected = prior == null ? readiness.identity() : prior.identity();
        WorkerLifecycleSnapshot inventory;
        try {
            inventory = port.inventory(expected, prior == null ? 0 : prior.throughSequence());
        } catch (IllegalStateException rejected) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.IDENTITY_CHANGED,
                    "LIFECYCLE_IDENTITY_FENCE_REJECTED");
        }
        if (prior != null
                && !prior.identity().stateGeneration()
                .equals(inventory.identity().stateGeneration())) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.STATE_GENERATION_RESET,
                    "LIFECYCLE_STATE_GENERATION_RESET");
        }
        if (!physicalWorkerId.equals(inventory.identity().physicalWorkerId())
                || (prior != null && !prior.identity().instanceEpoch()
                .equals(inventory.identity().instanceEpoch()))) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.IDENTITY_CHANGED,
                    "LIFECYCLE_INSTANCE_IDENTITY_CHANGED");
        }
        if (!inventory.completeActiveTaskSet()) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.COVERAGE_GAP,
                    "LIFECYCLE_INVENTORY_INCOMPLETE");
        }
        if (prior != null && inventory.minAvailableSequence() > prior.throughSequence() + 1) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.COVERAGE_GAP,
                    "LIFECYCLE_CURSOR_COVERAGE_GAP");
        }
        beforeAcknowledge.accept(inventory);
        long ack = port.acknowledge(inventory.identity(), inventory.throughSequence());
        if (ack < inventory.throughSequence()) {
            return SentinelReconcileResult.blocked(
                    SentinelReconcileState.COVERAGE_GAP,
                    "LIFECYCLE_ACK_NOT_MONOTONIC");
        }
        cursors.put(physicalWorkerId, new Cursor(inventory.identity(), ack));
        return new SentinelReconcileResult(
                SentinelReconcileState.READY,
                inventory.identity(),
                ack,
                inventory.facts(),
                true,
                "SHADOW_RECONCILE_" + trigger.name());
    }

    public void seedCursor(
            String physicalWorkerId,
            WorkerLifecycleIdentity identity,
            long throughSequence) {
        if (identity != null && throughSequence >= 0) {
            cursors.putIfAbsent(physicalWorkerId, new Cursor(identity, throughSequence));
        }
    }

    private record Cursor(WorkerLifecycleIdentity identity, long throughSequence) {
    }
}
