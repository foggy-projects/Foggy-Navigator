package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.SessionLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        TaskLifecycleOwnerVerticalIntegrationTest.Config.class,
        LifecycleIngressGate.class
})
class LifecycleIngressGateIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleIngressGate gate;
    @org.springframework.beans.factory.annotation.Autowired
    SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    WorkerLifecycleSnapshotRepository workers;

    @BeforeEach
    void setUp() {
        sessions.deleteAll();
        workers.deleteAll();
        worker("READY");
        session();
    }

    @Test
    void enforcedForegroundReservationIsAtomicSingleFlight() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> reserve(start, admitted, rejected));
            var second = executor.submit(() -> reserve(start, admitted, rejected));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(admitted).hasValue(1);
        assertThat(rejected).hasValue(1);
        assertThat(sessions.findById("session-gate").orElseThrow()
                .getForegroundLaneState()).isEqualTo("RESERVED");
    }

    @Test
    void enforcedOfflineWorkerRejectsBeforeReservation() {
        workers.deleteAll();
        worker("OFFLINE_FROZEN");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                gate.reserveBeforeEffect("session-gate", "worker-gate"))
                .hasMessage("WORKER_DEPENDENT_MUTATION_NOT_READY");
        assertThat(sessions.findById("session-gate").orElseThrow()
                .getForegroundTaskId()).isNull();
    }

    @Test
    void enforcedMissingLifecycleContextFailsClosed() {
        workers.deleteAll();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                gate.reserveBeforeEffect("session-gate", "worker-gate"))
                .hasMessage("LIFECYCLE_CONTEXT_GAP");
        assertThat(sessions.findById("session-gate").orElseThrow()
                .getForegroundTaskId()).isNull();
    }

    private void reserve(
            CountDownLatch start, AtomicInteger admitted, AtomicInteger rejected) {
        await(start);
        try {
            gate.reserveBeforeEffect("session-gate", "worker-gate");
            admitted.incrementAndGet();
        } catch (IllegalStateException occupied) {
            assertThat(occupied).hasMessage("SESSION_FOREGROUND_LANE_OCCUPIED");
            rejected.incrementAndGet();
        }
    }

    private void session() {
        SessionLifecycleSnapshotEntity entity =
                new SessionLifecycleSnapshotEntity();
        entity.setSessionId("session-gate");
        entity.setPhysicalWorkerId("worker-gate");
        entity.setOwnershipMode("ENFORCED");
        entity.setCanonicalPhase("OPEN");
        entity.setForegroundLaneState("FREE");
        entity.setAvailability("READY");
        entity.setConflictState("NONE");
        sessions.saveAndFlush(entity);
    }

    private void worker(String availability) {
        WorkerLifecycleSnapshotEntity entity =
                new WorkerLifecycleSnapshotEntity();
        entity.setPhysicalWorkerId("worker-gate");
        entity.setOwnershipMode("ENFORCED");
        entity.setStateGeneration("generation-gate");
        entity.setInstanceEpoch("epoch-gate");
        entity.setAvailability(availability);
        entity.setConflictState("NONE");
        entity.setFactCursor(0);
        entity.setPolicyVersion("ARCH-001-MVP-A");
        entity.setSnapshotJson("{}");
        workers.saveAndFlush(entity);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("fixture latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
