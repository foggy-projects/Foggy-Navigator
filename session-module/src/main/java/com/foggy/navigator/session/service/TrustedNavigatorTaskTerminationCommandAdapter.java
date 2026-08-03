package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Trusted Navigator MVC adapter for the canonical Task termination command pipeline.
 *
 * <p>The two public methods fix their own ingress and resolve context. Callers cannot supply
 * ownership, routing, Provider identity, an envelope, or an authorization decision.</p>
 */
@Service
public final class TrustedNavigatorTaskTerminationCommandAdapter {

    private static final Set<String> TERMINAL_STATES =
            Set.of("COMPLETED", "FAILED", "ABORTED");
    private static final String TERMINAL_PREFIX = "TASK_ALREADY_TERMINAL_";
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor UI_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.TASK_TERMINATE_DIRECT;
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor A2A_INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor.A2A_TASK_TERMINATE;

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskTerminationCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final TrustedNavigatorCommandIngressAuthority ingressAuthority;

    TrustedNavigatorTaskTerminationCommandAdapter(
            TaskDispatchFacade taskDispatchFacade,
            TaskTerminationCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            TrustedNavigatorCommandIngressAuthority ingressAuthority) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.ingressAuthority = Objects.requireNonNull(
                ingressAuthority, "ingressAuthority must not be null");
    }

    public TerminationResult terminateUiTask(
            String taskId,
            boolean force,
            @Nullable String suppliedClientRequestId) {
        return terminate(
                taskId,
                null,
                false,
                force,
                suppliedClientRequestId,
                UI_INGRESS,
                "UI",
                "TRUSTED_NAVIGATOR_TERMINATION_ROUTE_SOURCE_CONFLICT");
    }

    public TerminationResult terminateA2aTask(
            String pathAgentId,
            String taskId,
            @Nullable String suppliedClientRequestId) {
        return terminate(
                taskId,
                pathAgentId,
                true,
                false,
                suppliedClientRequestId,
                A2A_INGRESS,
                "A2A",
                "TRUSTED_NAVIGATOR_A2A_TERMINATION_ROUTE_SOURCE_CONFLICT");
    }

    private TerminationResult terminate(
            String taskId,
            @Nullable String pathAgentId,
            boolean pathAgentRequired,
            boolean force,
            @Nullable String suppliedClientRequestId,
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor descriptor,
            String requestSource,
            String routeSourceConflictCode) {
        AgentResolveContext context = currentContext(requestSource);
        TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified =
                ingressAuthority.require(
                        context,
                        List.of(descriptor),
                        routeSourceConflictCode);
        String clientRequestId = ingressAuthority.canonicalTerminationClientRequestId(
                suppliedClientRequestId);
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                taskDispatchFacade.resolveTerminationExecutionPlan(taskId, context, force);
        requirePlanIdentity(taskId, pathAgentId, pathAgentRequired, verified, plan);

        TaskTerminationCommandCoordinator.PlanBinding planBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        new CanonicalCommandEnvelope.Ingress(
                                verified.commandIngress(),
                                verified.clientSurface(),
                                verified.routeId()),
                        new CanonicalCommandEnvelope.Request(
                                clientRequestId,
                                clientRequestId,
                                clientRequestId),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.NAVIGATOR_USER,
                                verified.credentialLane(),
                                verified.principalFingerprint(),
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                planBinding.tenantReference(),
                                verified.ownerUserId(),
                                null,
                                null),
                        planBinding.target(),
                        planBinding.effect());
        VerifiedCommandAuthorizationDecision decision = serverAuthority.issue(binding);
        CanonicalCommandEnvelope envelope = new CanonicalCommandEnvelope(
                CanonicalCommandEnvelope.SCHEMA_VERSION,
                binding,
                decision.metadata());
        TaskTerminationCommandCoordinator.TerminationCommandResult result =
                commandCoordinator.execute(plan, envelope, decision);
        TaskTerminationCommandCoordinator.Outcome outcome = Objects.requireNonNull(
                result, "termination command result must not be null").outcome();
        return new TerminationResult(outcome.safeCode(), outcome.terminalStatus());
    }

    private static AgentResolveContext currentContext(String requestSource) {
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return AgentResolveContext.builder()
                .userId(currentUser.getUserId())
                .tenantId(currentUser.getTenantId())
                .requestSource(requestSource)
                .build();
    }

    private static void requirePlanIdentity(
            String requestedTaskId,
            @Nullable String pathAgentId,
            boolean pathAgentRequired,
            TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified,
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity = plan.identity();
        if (!Objects.equals(requestedTaskId, identity.taskId())) {
            throw new IllegalStateException("TERMINATION_PLAN_TASK_CONFLICT");
        }
        if (!verified.ownerUserId().equals(identity.ownerUserId())
                || !Objects.equals(verified.tenantId(), identity.tenantId())) {
            throw new SecurityException(
                    "TRUSTED_NAVIGATOR_TERMINATION_PLAN_OWNER_CONFLICT");
        }
        if (pathAgentRequired
                && (pathAgentId == null
                || pathAgentId.isBlank()
                || !pathAgentId.equals(identity.logicalAgentId()))) {
            throw new SecurityException("Resource access denied");
        }
    }

    public record TerminationResult(
            String safeCode,
            @Nullable String terminalStatus) {
        public TerminationResult {
            if (terminalStatus == null) {
                if (!TaskTerminationCommandCoordinator.TERMINATION_REQUEST_ACCEPTED
                        .equals(safeCode)) {
                    throw new IllegalArgumentException(
                            "termination result safe code is invalid");
                }
            } else if (!TERMINAL_STATES.contains(terminalStatus)
                    || !(TERMINAL_PREFIX + terminalStatus).equals(safeCode)) {
                throw new IllegalArgumentException(
                        "termination result terminal status is invalid");
            }
        }

        @Override
        public String toString() {
            return "TerminationResult[safe]";
        }
    }
}
