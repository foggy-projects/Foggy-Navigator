package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.session.command.CommandReceiptTransactionFence;
import com.foggy.navigator.session.lifecycle.persistence.TaskLifecycleSnapshotEntity;
import com.foggy.navigator.session.lifecycle.repository.TaskLifecycleSnapshotRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Transaction fence that keeps Navigator management cancellation in the NON_ENFORCED domain.
 */
@Service
final class OpenApiManagementTerminationDomainFence
        implements CommandReceiptTransactionFence {

    static final String AUTHORITY_CONFLICT =
            "TERMINATION_MANAGEMENT_AUTHORITY_CONFLICT";
    static final String RESOURCE_CONFLICT =
            "TERMINATION_MANAGEMENT_RESOURCE_CONFLICT";
    static final String DOMAIN_NOT_NON_ENFORCED =
            "TERMINATION_MANAGEMENT_DOMAIN_NOT_NON_ENFORCED";

    private final SessionTaskRepository canonicalTasks;
    private final SessionRepository sessions;
    private final TaskLifecycleSnapshotRepository lifecycleTasks;
    private final EntityManager entityManager;

    OpenApiManagementTerminationDomainFence(
            SessionTaskRepository canonicalTasks,
            SessionRepository sessions,
            TaskLifecycleSnapshotRepository lifecycleTasks,
            EntityManager entityManager) {
        this.canonicalTasks = Objects.requireNonNull(
                canonicalTasks, "canonicalTasks must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.lifecycleTasks = Objects.requireNonNull(
                lifecycleTasks, "lifecycleTasks must not be null");
        this.entityManager = Objects.requireNonNull(
                entityManager, "entityManager must not be null");
    }

    @Override
    public boolean claims(CanonicalCommandEnvelope.CommandBinding binding) {
        return CommandReceiptTransactionFence
                .requiresOpenApiAgentTaskTerminationFence(binding);
    }

    @Override
    public LockedDomain lock(CanonicalCommandEnvelope.CommandBinding binding) {
        if (!claims(binding)) {
            throw new IllegalArgumentException(
                    "OpenAPI management termination fence does not claim command");
        }
        if (!managementAuthority(binding) || !managementCommandShape(binding)) {
            return LockedDomain.rejected(AUTHORITY_CONFLICT);
        }

        CanonicalCommandEnvelope.Target target = binding.target();
        String taskId = target.taskId();
        TaskLifecycleSnapshotEntity observed = lifecycleTasks.findById(taskId)
                .orElse(null);
        if (observed != null) {
            String observedMode = observed.getOwnershipMode();
            entityManager.detach(observed);
            if (!"SHADOW".equals(observedMode)) {
                return LockedDomain.rejected(DOMAIN_NOT_NON_ENFORCED);
            }
        }

        SessionTaskEntity task = canonicalTasks.findByTaskIdForUpdate(taskId)
                .orElse(null);
        if (!exactCanonicalTask(binding, task)) {
            return LockedDomain.rejected(RESOURCE_CONFLICT);
        }

        SessionEntity session = sessions.findByIdAndUserIdAndTenantId(
                        task.getSessionId(), task.getUserId(), task.getTenantId())
                .orElse(null);
        if (!exactLiveSession(task, session)) {
            return LockedDomain.rejected(RESOURCE_CONFLICT);
        }

        TaskLifecycleSnapshotEntity lifecycle = lifecycleTasks
                .findForUpdate(taskId)
                .orElse(null);
        if (lifecycle == null) {
            return LockedDomain.allowed();
        }
        if (!taskId.equals(lifecycle.getTaskId())
                || (lifecycle.getSessionId() != null
                && !task.getSessionId().equals(lifecycle.getSessionId()))) {
            return LockedDomain.rejected(RESOURCE_CONFLICT);
        }
        return "SHADOW".equals(lifecycle.getOwnershipMode())
                ? LockedDomain.allowed()
                : LockedDomain.rejected(DOMAIN_NOT_NON_ENFORCED);
    }

    private static boolean managementAuthority(
            CanonicalCommandEnvelope.CommandBinding binding) {
        CanonicalCommandEnvelope.Actor actor = binding.actor();
        CanonicalCommandEnvelope.Ownership ownership = binding.ownership();
        return actor.kind() == CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL
                && actor.principalType() == AuthorizationPrincipalType.NAVIGATOR_USER
                && (actor.lane() == AuthorizationCredentialLane.NAVIGATOR_JWT
                || actor.lane() == AuthorizationCredentialLane.NAVIGATOR_API_KEY)
                && ownership.clientAppReference() == null
                && ownership.upstreamReference() == null;
    }

    private static boolean managementCommandShape(
            CanonicalCommandEnvelope.CommandBinding binding) {
        CanonicalCommandEnvelope.Target target = binding.target();
        return target.kind() == CanonicalCommandEnvelope.TargetKind.TASK
                && hasText(target.taskId())
                && target.taskId().equals(target.targetId())
                && CommandReceiptTransactionFence.TASK_TERMINATE_ACTION.equals(
                binding.effect().actionId());
    }

    private static boolean exactCanonicalTask(
            CanonicalCommandEnvelope.CommandBinding binding,
            SessionTaskEntity task) {
        if (task == null
                || !hasText(task.getTaskId())
                || !hasText(task.getSessionId())
                || !hasText(task.getUserId())
                || !hasText(task.getTenantId())
                || !hasText(task.getAgentId())) {
            return false;
        }
        CanonicalCommandEnvelope.Target target = binding.target();
        return task.getTaskId().equals(target.taskId())
                && task.getTaskId().equals(target.targetId())
                && task.getSessionId().equals(target.sessionId())
                && task.getAgentId().equals(target.logicalAgentId())
                && Objects.equals(task.getProviderType(), target.providerType())
                && Objects.equals(task.getWorkerId(), target.physicalWorkerId())
                && Objects.equals(task.getModelConfigId(), target.modelConfigId())
                && task.getUserId().equals(binding.ownership().ownerReference())
                && TaskTerminationCommandCoordinator.canonicalTenantReference(
                task.getTenantId()).equals(
                binding.ownership().tenantReference());
    }

    private static boolean exactLiveSession(
            SessionTaskEntity task,
            SessionEntity session) {
        return session != null
                && task.getSessionId().equals(session.getId())
                && task.getUserId().equals(session.getUserId())
                && task.getTenantId().equals(session.getTenantId())
                && session.getDeletedAt() == null
                && !"DELETED".equalsIgnoreCase(session.getStatus());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
