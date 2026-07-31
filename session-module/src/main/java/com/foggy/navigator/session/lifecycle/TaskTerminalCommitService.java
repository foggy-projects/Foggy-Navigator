package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanEntity;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalCleanupPlanId;
import com.foggy.navigator.session.lifecycle.persistence.TaskTerminalTombstoneEntity;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalCleanupPlanRepository;
import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.session.lifecycle.repository.LifecycleEffectOutboxRepository;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupPort;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneParticipant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TaskTerminalCommitService {

    private final TaskTerminalTombstoneRepository tombstones;
    private final TaskTerminalCleanupPlanRepository cleanupPlans;
    private final TaskLifecycleSnapshotRepository snapshots;
    private final List<TerminalTombstoneParticipant> participants;
    private final TerminalCleanupPlanFactory planFactory;
    private final List<TerminalCleanupPort> cleanupPorts;
    private final LifecycleEffectOutboxRepository effectOutbox;

    @Autowired
    public TaskTerminalCommitService(
            TaskTerminalTombstoneRepository tombstones,
            TaskTerminalCleanupPlanRepository cleanupPlans,
            TaskLifecycleSnapshotRepository snapshots,
            List<TerminalTombstoneParticipant> participants,
            TerminalCleanupPlanFactory planFactory,
            List<TerminalCleanupPort> cleanupPorts,
            LifecycleEffectOutboxRepository effectOutbox) {
        this.tombstones = tombstones;
        this.cleanupPlans = cleanupPlans;
        this.snapshots = snapshots;
        this.participants = List.copyOf(participants);
        this.planFactory = planFactory;
        this.cleanupPorts = List.copyOf(cleanupPorts);
        this.effectOutbox = effectOutbox;
    }

    TaskTerminalCommitService(
            TaskTerminalTombstoneRepository tombstones,
            TaskTerminalCleanupPlanRepository cleanupPlans,
            TaskLifecycleSnapshotRepository snapshots,
            List<TerminalTombstoneParticipant> participants,
            TerminalCleanupPlanFactory planFactory) {
        this(tombstones, cleanupPlans, snapshots, participants, planFactory,
                List.of(), null);
    }

    @Transactional
    public void commit(TerminalCommitCommand command) {
        if (tombstones.existsById(command.taskId())) return;
        List<TerminalCleanupPlanEntry> frozenPlan =
                planFactory.freeze(resolveCleanupResources(command));
        String idempotencyKey = "terminal-fence:" + command.taskId();

        for (TerminalTombstoneParticipant participant : participants) {
            if (participant.applicability(command.tombstoneContext())
                    .capabilityDomainSupported()) {
                participant.recordAuthoritativeTombstone(
                        command.tombstoneContext(),
                        command.outcome().name(),
                        command.source().name(),
                        idempotencyKey);
            }
        }

        tombstones.save(tombstone(command));
        cleanupPlans.saveAll(frozenPlan.stream()
                .map(entry -> cleanup(command.taskId(), entry))
                .toList());
        snapshots.save(terminalSnapshot(command));
    }

    private TerminalCleanupResources resolveCleanupResources(
            TerminalCommitCommand command) {
        if (effectOutbox == null) {
            return command.cleanupResources();
        }
        TerminalCleanupContext context = new TerminalCleanupContext(
                command.tombstoneContext().taskId(),
                command.tombstoneContext().sessionId(),
                command.tombstoneContext().providerType(),
                command.tombstoneContext().tenantId(),
                command.tombstoneContext().providerTaskId(),
                command.tombstoneContext().providerTaskUserId(),
                command.tombstoneContext().sourceAgentId(),
                command.tombstoneContext().operationId(),
                command.outcome().name());
        boolean tokenIssued = resourcePresent(
                TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE, context);
        boolean receiptPersisted = resourcePresent(
                TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT, context);
        boolean terminationAccepted =
                context.operationId() != null
                && effectOutbox.findByAggregateIdAndOperationId(
                        command.taskId(), context.operationId()).stream()
                .anyMatch(effect -> !"REJECTED".equals(
                        effect.getEffectState()));
        boolean capabilitySupported = participants.stream()
                .anyMatch(participant -> participant
                        .applicability(command.tombstoneContext())
                        .capabilityDomainSupported());
        return new TerminalCleanupResources(
                tokenIssued, terminationAccepted,
                receiptPersisted, capabilitySupported);
    }

    private boolean resourcePresent(
            TerminalCleanupParticipant participant,
            TerminalCleanupContext context) {
        return cleanupPorts.stream()
                .filter(port -> port.supports(participant.name(), context))
                .anyMatch(port -> port.resourcePresent(
                        participant.name(), context));
    }

    private TaskTerminalTombstoneEntity tombstone(TerminalCommitCommand command) {
        TaskTerminalTombstoneEntity entity = new TaskTerminalTombstoneEntity();
        entity.setTaskId(command.taskId());
        entity.setSessionId(command.tombstoneContext().sessionId());
        entity.setProviderType(command.tombstoneContext().providerType());
        entity.setTenantId(command.tombstoneContext().tenantId());
        entity.setProviderTaskId(command.tombstoneContext().providerTaskId());
        entity.setProviderTaskUserId(command.tombstoneContext().providerTaskUserId());
        entity.setSourceAgentId(command.tombstoneContext().sourceAgentId());
        entity.setOperationId(command.tombstoneContext().operationId());
        entity.setTerminalOutcome(command.outcome().name());
        entity.setTerminalSource(command.source().name());
        entity.setTerminalFactId(command.terminalFactId());
        entity.setWriterGenerationId(command.writerGenerationId());
        return entity;
    }

    private TaskTerminalCleanupPlanEntity cleanup(
            String taskId, TerminalCleanupPlanEntry entry) {
        TaskTerminalCleanupPlanEntity entity = new TaskTerminalCleanupPlanEntity();
        entity.setId(new TaskTerminalCleanupPlanId(taskId, entry.participant().name()));
        entity.setApplicability(entry.applicability().name());
        entity.setNotApplicableReason(entry.reasonCode());
        entity.setCheckpointState(
                entry.applicability() == CleanupApplicability.NOT_APPLICABLE
                        || entry.participant() == TerminalCleanupParticipant.TERMINAL_TOMBSTONE
                        ? "COMPLETED" : "PENDING");
        if (entry.participant() == TerminalCleanupParticipant.TERMINAL_TOMBSTONE) {
            entity.setCheckpointFactId("terminal-fence:" + taskId);
        }
        return entity;
    }

    private TaskLifecycleSnapshotEntity terminalSnapshot(TerminalCommitCommand command) {
        TaskLifecycleSnapshotEntity entity = snapshots.findForUpdate(command.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "LIFECYCLE_TASK_BINDING_REQUIRED_BEFORE_TERMINAL"));
        entity.setOwnershipMode("ENFORCED");
        entity.setCanonicalPhase(TaskCanonicalPhase.TERMINAL.name());
        entity.setTerminalOutcome(command.outcome().name());
        entity.setTerminalSource(command.source().name());
        entity.setAvailability(LifecycleAvailability.READY.name());
        entity.setConflictState(LifecycleConflictState.NONE.name());
        entity.setCleanupState(TaskCleanupState.PENDING.name());
        if (entity.getFactCursor() == null) entity.setFactCursor(0L);
        entity.setPolicyVersion("ARCH-001-MVP-A");
        entity.setWriterGenerationId(command.writerGenerationId());
        return entity;
    }
}
