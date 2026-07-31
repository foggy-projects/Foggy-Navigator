package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.lifecycle.persistence.SessionLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.*;
import com.foggy.navigator.spi.lifecycle.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(TaskLifecycleOwnerVerticalIntegrationTest.Config.class)
class TaskLifecycleOwnerVerticalIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = {
            SessionTaskEntity.class,
            com.foggy.navigator.session.lifecycle.persistence.LifecycleFactEntity.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            SessionTaskRepository.class,
            LifecycleFactRepository.class
    })
    @Import({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            TaskLifecycleOwnerService.class,
            TaskTerminalCommitService.class,
            TerminalCleanupHandler.class,
            TerminalCleanupFinalizer.class,
            LifecycleEnrollmentRetirementService.class,
            WriterExclusivityProofService.class,
            TerminalCleanupStepExecutor.class,
            CompatibilityTaskProjectionCleanupAction.class,
            PhysicalTokenCleanupAction.class,
            TerminationReceiptCleanupAction.class,
            SessionForegroundLaneService.class,
            TaskLifecycleProjectionService.class
    })
    static class Config {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean TerminalCleanupPlanFactory terminalCleanupPlanFactory() {
            return new TerminalCleanupPlanFactory();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleOwnerService owner;
    @org.springframework.beans.factory.annotation.Autowired
    SessionTaskRepository tasks;
    @org.springframework.beans.factory.annotation.Autowired
    SessionLifecycleSnapshotRepository sessions;
    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleSnapshotRepository snapshots;
    @org.springframework.beans.factory.annotation.Autowired
    TaskTerminalTombstoneRepository tombstones;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleEffectOutboxRepository outbox;
    @org.springframework.beans.factory.annotation.Autowired
    LifecycleFactRepository facts;
    @org.springframework.beans.factory.annotation.Autowired
    TaskTerminalCleanupPlanRepository cleanupPlans;
    @org.springframework.beans.factory.annotation.Autowired
    TaskLifecycleProjectionPort projection;

    private final WorkerLifecycleIdentity identity = new WorkerLifecycleIdentity(
            "worker-1", "generation-1", "epoch-1");
    private final WorkerLifecycleTask workerTask = new WorkerLifecycleTask(
            "task-1", "provider-task-1", LifecycleOwnershipMode.ENFORCED,
            "dispatch-1", "JCS_SHA256_V1", "binding-digest-1",
            "EFFECT_STARTED", 1);

    @BeforeEach
    void fixture() {
        cleanupPlans.deleteAll();
        tombstones.deleteAll();
        facts.deleteAll();
        snapshots.deleteAll();
        outbox.deleteAll();
        tasks.deleteAll();
        sessions.deleteAll();
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-1");
        task.setSessionId("session-1");
        task.setProviderType("codex-worker");
        task.setProviderTaskId("provider-task-1");
        task.setWorkerId("worker-1");
        task.setUserId("user-1");
        task.setTenantId("tenant-1");
        task.setAgentId("codex-worker");
        task.setStatus("RUNNING");
        tasks.saveAndFlush(task);

        SessionLifecycleSnapshotEntity lane = new SessionLifecycleSnapshotEntity();
        lane.setSessionId("session-1");
        lane.setPhysicalWorkerId("worker-1");
        lane.setOwnershipMode("ENFORCED");
        lane.setCanonicalPhase("OPEN");
        lane.setForegroundTaskId("task-1");
        lane.setForegroundLaneState("OCCUPIED");
        lane.setAvailability("READY");
        lane.setConflictState("NONE");
        sessions.saveAndFlush(lane);
        owner.enrollInventoryTask(identity, workerTask, "writer-generation-1");
    }

    @Test
    void workerFactConvergesThroughReducerTombstoneCleanupAndTypedTerminal() {
        TaskLifecycleDecision decision = owner.ingestNormalizedBatch(
                "task-1", List.of(terminal(
                        "fact-terminal", 2, "CANCELLED")));

        assertThat(decision.snapshot().canonicalTerminal()).isTrue();
        assertThat(tombstones.existsById("task-1")).isTrue();
        assertThat(tasks.findByTaskId("task-1").orElseThrow().getStatus())
                .isEqualTo("ABORTED");
        assertThat(snapshots.findById("task-1").orElseThrow().getCleanupState())
                .as("cleanup plans: %s",
                        cleanupPlans.findByIdTaskIdOrderByIdParticipant("task-1")
                                .stream()
                                .map(plan -> plan.getId().getParticipant()
                                        + "=" + plan.getCheckpointState())
                                .toList())
                .isEqualTo("COMPLETED");
        assertThat(sessions.findById("session-1").orElseThrow()
                .getForegroundLaneState()).isEqualTo("FREE");
        assertThat(sessions.findById("session-1").orElseThrow()
                .getForegroundTaskId()).isNull();
        assertThat(projection.find("task-1").orElseThrow().typedTerminal())
                .isTrue();
    }

    @Test
    void conflictingTerminalFactsQuarantineWithoutTombstoneOrEffect() {
        TaskLifecycleDecision decision = owner.ingestNormalizedBatch(
                "task-1", List.of(
                        terminal("fact-success", 2, "COMPLETED"),
                        terminal("fact-failed", 3, "FAILED")));

        assertThat(decision.snapshot().canonicalTerminal()).isFalse();
        assertThat(decision.snapshot().availability())
                .isEqualTo(LifecycleAvailability.AUTHORITY_QUARANTINED);
        assertThat(tombstones.existsById("task-1")).isFalse();
        assertThat(outbox.findAll()).isEmpty();
        assertThat(tasks.findByTaskId("task-1").orElseThrow().getStatus())
                .isEqualTo("RUNNING");
    }

    @Test
    void exactDurableNeverAcceptedFactUsesSameTerminalCleanupAndLaneRelease() {
        TaskLifecycleDecision decision = owner.ingestNormalizedBatch(
                "task-1", List.of(preEffectRejection("fact-never-accepted", 2)));

        assertThat(decision.snapshot().canonicalTerminal()).isTrue();
        assertThat(decision.snapshot().terminalOutcome())
                .isEqualTo(TaskTerminalOutcome.FAILED);
        assertThat(decision.snapshot().terminalSource())
                .isEqualTo(TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION);
        assertThat(decision.snapshot().executionObservation())
                .isEqualTo(TaskExecutionObservation.NOT_STARTED);
        assertThat(tombstones.findById("task-1").orElseThrow().getTerminalFactId())
                .isEqualTo("fact-never-accepted");
        assertThat(snapshots.findById("task-1").orElseThrow().getCleanupState())
                .isEqualTo("COMPLETED");
        assertThat(sessions.findById("session-1").orElseThrow()
                .getForegroundLaneState()).isEqualTo("FREE");
    }

    private NormalizedLifecycleFact terminal(
            String factId, long sequence, String outcome) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new NormalizedLifecycleFact(
                factId,
                "TASK_PROVIDER_TERMINAL_OBSERVED",
                1,
                "TASK",
                "task-1",
                "session-1",
                "task-1",
                "provider-task-1",
                null,
                identity,
                LifecycleOwnershipMode.ENFORCED,
                "dispatch-1",
                "JCS_SHA256_V1",
                "binding-digest-1",
                sequence,
                "generation-1:" + sequence,
                now,
                now,
                "PROVIDER_RESULT_OBSERVED",
                outcome);
    }

    private NormalizedLifecycleFact preEffectRejection(
            String factId, long sequence) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new NormalizedLifecycleFact(
                factId,
                "TASK_NEVER_ACCEPTED_CONFIRMED",
                1,
                "TASK",
                "task-1",
                "session-1",
                "task-1",
                "provider-task-1",
                null,
                identity,
                LifecycleOwnershipMode.ENFORCED,
                "dispatch-1",
                "JCS_SHA256_V1",
                "binding-digest-1",
                sequence,
                "generation-1:" + sequence,
                now,
                now,
                "WORKER_TASK_ADMISSION_CAPACITY_REJECTED",
                null);
    }
}
