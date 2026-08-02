package com.foggy.navigator.session.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupPort;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneParticipant;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import com.foggy.navigator.spi.lifecycle.TombstoneApplicability;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskTerminalCommitServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void productionCommitRecomputesActiveTokenForMissingTombstoneRepairPlan() {
        TaskTerminalTombstoneRepository tombstones = mock(TaskTerminalTombstoneRepository.class);
        TaskTerminalCleanupPlanRepository plans = mock(TaskTerminalCleanupPlanRepository.class);
        TaskLifecycleSnapshotRepository snapshots = mock(TaskLifecycleSnapshotRepository.class);
        LifecycleEffectOutboxRepository effectOutbox = mock(LifecycleEffectOutboxRepository.class);
        TerminalCleanupPort tokenPort = mock(TerminalCleanupPort.class);
        TerminalTombstoneContext context = new TerminalTombstoneContext(
                "task-1", "session-1", "codex-biz-worker", "tenant-1",
                null, "user-1", "agent-1", null, null);
        TaskLifecycleSnapshotEntity snapshot = new TaskLifecycleSnapshotEntity();
        snapshot.setTaskId("task-1");

        when(tombstones.existsById("task-1")).thenReturn(false);
        when(snapshots.findForUpdate("task-1")).thenReturn(Optional.of(snapshot));
        when(tokenPort.supports("PHYSICAL_TOKEN_REVOKE", new com.foggy.navigator.spi.lifecycle.TerminalCleanupContext(
                "task-1", "session-1", "codex-biz-worker", "tenant-1",
                null, "user-1", "agent-1", null, null, "FAILED")))
                .thenReturn(true);
        when(tokenPort.resourcePresent("PHYSICAL_TOKEN_REVOKE",
                new com.foggy.navigator.spi.lifecycle.TerminalCleanupContext(
                        "task-1", "session-1", "codex-biz-worker", "tenant-1",
                        null, "user-1", "agent-1", null, null, "FAILED")))
                .thenReturn(true);

        TaskTerminalCommitService service = new TaskTerminalCommitService(
                tombstones, plans, snapshots, List.of(), new TerminalCleanupPlanFactory(),
                List.of(tokenPort), effectOutbox);

        service.commit(new TerminalCommitCommand(
                context, "fact-1", "generation-1", TaskTerminalOutcome.FAILED,
                TaskTerminalSource.WORKER_PRE_EFFECT_REJECTION,
                new TerminalCleanupResources(false, false, false, false)));

        ArgumentCaptor<Iterable> frozenPlans = ArgumentCaptor.forClass(Iterable.class);
        verify(plans).saveAll(frozenPlans.capture());
        List<TaskTerminalCleanupPlanEntity> entries = new java.util.ArrayList<>();
        frozenPlans.getValue().forEach(entry -> entries.add((TaskTerminalCleanupPlanEntity) entry));
        TaskTerminalCleanupPlanEntity tokenPlan = entries.stream()
                .filter(entry -> TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE.name()
                        .equals(entry.getId().getParticipant()))
                .findFirst()
                .orElseThrow();
        assertThat(tokenPlan.getApplicability()).isEqualTo("REQUIRED");
        assertThat(tokenPlan.getNotApplicableReason()).isNull();
    }

    @Test
    void participantTombstoneFailurePreventsCanonicalSnapshotCommit() {
        TaskTerminalTombstoneRepository tombstones = mock(TaskTerminalTombstoneRepository.class);
        TaskTerminalCleanupPlanRepository plans = mock(TaskTerminalCleanupPlanRepository.class);
        TaskLifecycleSnapshotRepository snapshots = mock(TaskLifecycleSnapshotRepository.class);
        TerminalTombstoneParticipant participant = mock(TerminalTombstoneParticipant.class);
        TerminalTombstoneContext context = new TerminalTombstoneContext(
                "task-1", "codex-biz-worker", "tenant-1", "provider-task-1",
                "owner-1", "codex-biz-worker");
        when(participant.applicability(context))
                .thenReturn(new TombstoneApplicability(true, null));
        doThrow(new IllegalStateException("FIXTURE_TOMBSTONE_WRITE_FAILED"))
                .when(participant).recordAuthoritativeTombstone(
                        context, "CANCELLED", "WORKER_EVIDENCE", "terminal-fence:task-1");

        TaskTerminalCommitService service = new TaskTerminalCommitService(
                tombstones, plans, snapshots, List.of(participant),
                new TerminalCleanupPlanFactory());

        assertThrows(IllegalStateException.class, () -> service.commit(
                new TerminalCommitCommand(
                        context, "fact-1", "generation-1",
                        TaskTerminalOutcome.CANCELLED, TaskTerminalSource.WORKER_EVIDENCE,
                        new TerminalCleanupResources(true, true, true, true))));

        verify(tombstones, never()).save(any());
        verify(plans, never()).saveAll(any());
        verify(snapshots, never()).save(any());
    }
}
