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

/** Trusted, currently dormant adapter for same-tenant OpenAPI management termination. */
@Service
public final class ScopedOpenApiManagementTaskTerminationCommandAdapter {

    private static final Set<String> TERMINAL_STATES =
            Set.of("COMPLETED", "FAILED", "ABORTED");
    private static final String TERMINAL_PREFIX = "TASK_ALREADY_TERMINAL_";
    private static final String REQUEST_SOURCE = "OPEN_API";
    private static final TrustedNavigatorCommandIngressAuthority.IngressDescriptor INGRESS =
            TrustedNavigatorCommandIngressAuthority.IngressDescriptor
                    .OPEN_API_MANAGEMENT_TASK_TERMINATE;

    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskTerminationCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final TrustedNavigatorCommandIngressAuthority ingressAuthority;
    private final SessionTaskResourceAccessService resources;

    ScopedOpenApiManagementTaskTerminationCommandAdapter(
            TaskDispatchFacade taskDispatchFacade,
            TaskTerminationCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            TrustedNavigatorCommandIngressAuthority ingressAuthority,
            SessionTaskResourceAccessService resources) {
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.ingressAuthority = Objects.requireNonNull(
                ingressAuthority, "ingressAuthority must not be null");
        this.resources = Objects.requireNonNull(resources, "resources must not be null");
    }

    public TerminationResult terminate(
            String pathAgentId,
            String taskId,
            @Nullable String suppliedClientRequestId) {
        AgentResolveContext actorContext = currentActorContext();
        TrustedNavigatorCommandIngressAuthority.VerifiedIngress verified =
                ingressAuthority.require(
                        actorContext,
                        List.of(INGRESS),
                        "TRUSTED_NAVIGATOR_OPEN_API_TERMINATION_ROUTE_SOURCE_CONFLICT");
        String clientRequestId = ingressAuthority.canonicalTerminationClientRequestId(
                suppliedClientRequestId);
        SessionTaskResourceAccessService.ManagedTaskIdentity managed =
                resources.requireTenantTask(taskId, verified.tenantId());
        AgentResolveContext planContext = AgentResolveContext.builder()
                .userId(managed.ownerUserId())
                .tenantId(managed.tenantId())
                .sessionId(managed.sessionId())
                .requestSource(REQUEST_SOURCE)
                .build();
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                taskDispatchFacade.resolveTerminationExecutionPlan(
                        managed.taskId(), planContext, false);
        requirePlanIdentity(pathAgentId, managed, plan);

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
                                managed.ownerUserId(),
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

    @Nullable
    private static AgentResolveContext currentActorContext() {
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null) {
            return null;
        }
        return AgentResolveContext.builder()
                .userId(currentUser.getUserId())
                .tenantId(currentUser.getTenantId())
                .requestSource(REQUEST_SOURCE)
                .build();
    }

    private static void requirePlanIdentity(
            String pathAgentId,
            SessionTaskResourceAccessService.ManagedTaskIdentity managed,
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity = plan.identity();
        if (!managed.taskId().equals(identity.taskId())) {
            throw new IllegalStateException("TERMINATION_PLAN_TASK_CONFLICT");
        }
        if (!managed.ownerUserId().equals(identity.ownerUserId())
                || !managed.tenantId().equals(identity.tenantId())
                || !managed.sessionId().equals(identity.sessionId())) {
            throw new SecurityException(
                    "TRUSTED_NAVIGATOR_OPEN_API_TERMINATION_PLAN_OWNER_CONFLICT");
        }
        if (pathAgentId == null
                || pathAgentId.isBlank()
                || !pathAgentId.equals(managed.logicalAgentId())
                || !pathAgentId.equals(identity.logicalAgentId())) {
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
