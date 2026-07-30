package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneParticipant;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import com.foggy.navigator.spi.lifecycle.TombstoneApplicability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskTerminalCommitServiceTest {

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
