package com.foggy.navigator.session.service;

import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Sharing Key authority adapter for the canonical task-termination command lane. */
@Service
public final class ScopedSharedTaskTerminationCommandAdapter {

    static final String SHARED_SOURCE = "SHARED_API";
    static final String SHARED_SURFACE = "NAVIGATOR_SHARED_API";
    static final String SHARED_TERMINATION_ROUTE =
            "/api/v1/shared/tasks/{taskId}/cancel";

    private static final String ACTOR_FINGERPRINT_DOMAIN =
            "navi.shared-agent-capability-fingerprint.v1";
    private static final Pattern STRICT_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> TERMINAL_STATES =
            Set.of("COMPLETED", "FAILED", "ABORTED");
    private static final String TERMINAL_PREFIX = "TASK_ALREADY_TERMINAL_";

    private final SharingKeyService sharingKeyService;
    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskTerminationCommandCoordinator commandCoordinator;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;

    ScopedSharedTaskTerminationCommandAdapter(
            SharingKeyService sharingKeyService,
            TaskDispatchFacade taskDispatchFacade,
            TaskTerminationCommandCoordinator commandCoordinator,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        this.sharingKeyService = Objects.requireNonNull(
                sharingKeyService, "sharingKeyService must not be null");
        this.taskDispatchFacade = Objects.requireNonNull(
                taskDispatchFacade, "taskDispatchFacade must not be null");
        this.commandCoordinator = Objects.requireNonNull(
                commandCoordinator, "commandCoordinator must not be null");
        this.serverAuthority = Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
    }

    /**
     * Terminates one Shared Task with a server-owned Sharing Key authority.
     * Caller-controlled routing, ownership and force intent are intentionally absent.
     */
    public TerminationResult terminateTask(
            String plainSharingKey,
            String taskId,
            @Nullable String suppliedClientRequestId) {
        SharingKeyService.SharedTaskTerminationAuthority authority =
                mintAuthority(plainSharingKey);
        String clientRequestId = canonicalClientRequestId(
                suppliedClientRequestId);
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(authority.ownerUserId())
                .tenantId(authority.tenantId())
                .requestSource(SHARED_SOURCE)
                .build();
        TaskTerminationCommandCoordinator.TerminationExecutionPlan plan =
                resolvePlan(taskId, context);
        requirePlanIdentity(taskId, authority, plan);
        revalidateAuthority(authority);

        TaskTerminationCommandCoordinator.PlanBinding planBinding =
                TaskTerminationCommandCoordinator.PlanBinding.from(plan);
        CanonicalCommandEnvelope.CommandBinding binding =
                new CanonicalCommandEnvelope.CommandBinding(
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        new CanonicalCommandEnvelope.Ingress(
                                CanonicalCommandEnvelope.CommandIngress.SHARED,
                                SHARED_SURFACE,
                                SHARED_TERMINATION_ROUTE),
                        new CanonicalCommandEnvelope.Request(
                                clientRequestId,
                                clientRequestId,
                                clientRequestId),
                        new CanonicalCommandEnvelope.Actor(
                                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                                AuthorizationPrincipalType.SHARE_GRANTEE,
                                AuthorizationCredentialLane.SHARING_KEY_CAPABILITY,
                                actorFingerprint(authority),
                                null),
                        new CanonicalCommandEnvelope.Ownership(
                                planBinding.tenantReference(),
                                authority.ownerUserId(),
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

    private SharingKeyService.SharedTaskTerminationAuthority mintAuthority(
            String plainSharingKey) {
        try {
            return sharingKeyService.mintTaskTerminationAuthority(plainSharingKey);
        } catch (IllegalArgumentException rejection) {
            throw new SharedTerminationAdmissionRejectedException(rejection);
        }
    }

    private TaskTerminationCommandCoordinator.TerminationExecutionPlan resolvePlan(
            String taskId,
            AgentResolveContext context) {
        try {
            return taskDispatchFacade.resolveTerminationExecutionPlan(
                    taskId, context, false);
        } catch (IllegalArgumentException rejection) {
            if (("Task not found: " + taskId).equals(rejection.getMessage())) {
                throw new SharedTerminationAdmissionRejectedException(rejection);
            }
            throw rejection;
        }
    }

    private void revalidateAuthority(
            SharingKeyService.SharedTaskTerminationAuthority authority) {
        try {
            sharingKeyService.requireCurrentTaskTerminationAuthority(authority);
        } catch (IllegalArgumentException rejection) {
            throw new SharedTerminationAdmissionRejectedException(rejection);
        }
    }

    private static void requirePlanIdentity(
            String requestedTaskId,
            SharingKeyService.SharedTaskTerminationAuthority authority,
            TaskTerminationCommandCoordinator.TerminationExecutionPlan plan) {
        TaskTerminationCommandCoordinator.TerminationIdentity identity = plan.identity();
        if (!Objects.equals(requestedTaskId, identity.taskId())) {
            throw new SharedTerminationAdmissionRejectedException(
                    "Task not found: " + requestedTaskId);
        }
        if (!authority.ownerUserId().equals(identity.ownerUserId())
                || !authority.tenantId().equals(identity.tenantId())) {
            throw new SecurityException("shared resource is not accessible");
        }
        if (!authority.agentId().equals(identity.logicalAgentId())) {
            throw new SharedTerminationAdmissionRejectedException(
                    "Task not found: " + requestedTaskId);
        }
    }

    private static String canonicalClientRequestId(@Nullable String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.trim();
        if (!STRICT_UUID.matcher(trimmed).matches()) {
            throw new SharedTerminationAdmissionRejectedException(
                    "clientRequestId must be a canonical UUID");
        }
        return UUID.fromString(trimmed).toString();
    }

    private static String actorFingerprint(
            SharingKeyService.SharedTaskTerminationAuthority authority) {
        return digest(
                ACTOR_FINGERPRINT_DOMAIN,
                authority.tenantId(),
                authority.ownerUserId(),
                authority.sharingKeyId());
    }

    private static String digest(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, domain);
            for (String field : fields) {
                updateDigestField(digest, field);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /** Safe Shared admission rejection that the Controller may expose as fail-A. */
    public static final class SharedTerminationAdmissionRejectedException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public SharedTerminationAdmissionRejectedException(String safeMessage) {
            super(Objects.requireNonNull(safeMessage, "safeMessage must not be null"));
        }

        private SharedTerminationAdmissionRejectedException(
                IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Safe result projection; fresh and replay are deliberately indistinguishable. */
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
            return "SharedTerminationResult[safe]";
        }
    }
}
