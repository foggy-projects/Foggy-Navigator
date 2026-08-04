package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.command.VerifiedCommandAuthorizationDecision;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import com.foggy.navigator.spi.task.RuntimeTaskClosureProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class RuntimeTerminationAcceptanceCoordinator {
    private final RuntimeRequestAuditService audits;
    private final List<RuntimeTerminationIntentPort> intentPorts;
    private final TransactionTemplate transactions;
    private final VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority;
    private final RuntimeStateAuditService stateAuditService;

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeTerminationAcceptanceCoordinator(
            RuntimeRequestAuditService audits,
            List<RuntimeTerminationIntentPort> intentPorts,
            PlatformTransactionManager transactionManager,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority,
            RuntimeStateAuditService stateAuditService) {
        this.audits = audits;
        this.intentPorts = List.copyOf(intentPorts);
        this.transactions = new TransactionTemplate(transactionManager);
        this.serverAuthority = java.util.Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.stateAuditService = java.util.Objects.requireNonNull(
                stateAuditService, "stateAuditService must not be null");
    }

    public RuntimeTerminationAcceptanceCoordinator(
            RuntimeRequestAuditService audits,
            List<RuntimeTerminationIntentPort> intentPorts,
            PlatformTransactionManager transactionManager,
            VerifiedCommandAuthorizationDecision.ServerAuthority serverAuthority) {
        this.audits = audits;
        this.intentPorts = List.copyOf(intentPorts);
        this.transactions = new TransactionTemplate(transactionManager);
        this.serverAuthority = java.util.Objects.requireNonNull(
                serverAuthority, "serverAuthority must not be null");
        this.stateAuditService = null;
    }

    public RuntimeRequestAuditService.TaskOperationRegistration accept(
            String clientRequestId,
            String appKey,
            String appSecret,
            String upstreamUserId,
            String reason,
            RuntimeTaskClosureProvider provider,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTerminationCommandAuthorization commandAuthorization) {
        return transactions.execute(status -> {
            commandAuthorization.require(
                    serverAuthority, owned, upstreamUserId, clientRequestId);
            RuntimeRequestAuditService.TaskOperationRegistration registration =
                    audits.beginTaskOperationIdempotentAtomic(
                            clientRequestId,
                            RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                            appKey,
                            appSecret,
                            null,
                            upstreamUserId,
                            owned.taskId());
            if (!registration.existing()) {
                throw new IllegalStateException(
                        "TERMINATION_REQUEST_RECEIPT_REQUIRED");
            }
            RuntimeTaskClosureProvider.TerminationAdmission admission =
                    provider.prepareTerminationAdmission(
                            owned.taskId(), owned.ownerUserId(), owned.tenantId(),
                            owned.physicalWorkerId(), reason, clientRequestId);
            if (admission == null) {
                throw new IllegalStateException(
                        "TERMINATION_EXACT_ADMISSION_UNAVAILABLE");
            }
            for (RuntimeTerminationIntentPort port : intentPorts) {
                port.recordIntent(new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                        clientRequestId,
                        owned.taskId(),
                        owned.sessionId(),
                        owned.providerType(),
                        owned.physicalWorkerId(),
                        owned.providerTaskId(),
                        admission.dispatchId(),
                        admission.operationId(),
                        admission.ownershipMode(),
                        admission.stateGeneration(),
                        admission.instanceEpoch(),
                        admission.bindingDigestVersion(),
                        admission.bindingDigest(),
                        owned.ownerUserId(),
                        owned.tenantId(),
                        RuntimeTerminationIntentPort.RuntimeTerminationIntent
                                .LEGACY_AUTHORIZATION_BINDING_CLAIM));
            }
            return registration;
        });
    }

    /**
     * Accepts the access-token Agent compatibility ingress into the existing
     * lifecycle termination outbox. The outbox is the durable idempotency and
     * effect authority for this ingress; no synthetic ClientApp secret audit
     * receipt is created.
     */
    public RuntimeTerminationIntentPort.RuntimeTerminationDelivery acceptAgent(
            String clientRequestId,
            String appKey,
            String accessToken,
            String upstreamUserId,
            String pathAgentId,
            String reason,
            RuntimeTaskClosureProvider provider,
            RuntimeStateAuditService.OwnedRuntimeTask owned,
            RuntimeTerminationCommandAuthorization commandAuthorization) {
        return transactions.execute(status -> {
            RuntimeStateAuditService.OwnedRuntimeTask current =
                    requiredStateAuditService()
                            .requireOwnedAgentTaskByAccessToken(
                                    appKey, accessToken, upstreamUserId,
                                    pathAgentId, owned.taskId());
            commandAuthorization.requireRuntimeAccessAgent(
                    serverAuthority, current, upstreamUserId,
                    pathAgentId, clientRequestId);
            RuntimeTaskClosureProvider.TerminationAdmission admission =
                    provider.prepareTerminationAdmission(
                            current.taskId(), current.ownerUserId(), current.tenantId(),
                            current.physicalWorkerId(), reason, clientRequestId);
            if (admission == null) {
                throw new IllegalStateException(
                        "TERMINATION_EXACT_ADMISSION_UNAVAILABLE");
            }
            return requiredPort().recordIntent(
                    new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                            clientRequestId,
                            current.taskId(),
                            current.sessionId(),
                            current.providerType(),
                            current.physicalWorkerId(),
                            current.providerTaskId(),
                            admission.dispatchId(),
                            admission.operationId(),
                            admission.ownershipMode(),
                            admission.stateGeneration(),
                            admission.instanceEpoch(),
                            admission.bindingDigestVersion(),
                            admission.bindingDigest(),
                            current.ownerUserId(),
                            current.tenantId(),
                            commandAuthorization.authorizationBindingClaim()));
        });
    }

    public RuntimeTerminationIntentPort.RuntimeTerminationAuthorization authorize(
            String clientRequestId) {
        return requiredPort().authorizeEffect(clientRequestId);
    }

    public RuntimeTerminationIntentPort.RuntimeTerminationDelivery delivery(
            String clientRequestId) {
        return requiredPort().find(clientRequestId);
    }

    public List<RuntimeTerminationIntentPort.RuntimeTerminationDelivery>
    prepared(int limit) {
        return requiredPort().findPrepared(limit);
    }

    public void resultObserved(String clientRequestId, String safeResultCode) {
        requiredPort().resultObserved(clientRequestId, safeResultCode);
    }

    private RuntimeTerminationIntentPort requiredPort() {
        if (intentPorts.size() != 1) {
            throw new IllegalStateException(
                    "TERMINATION_DELIVERY_PORT_CARDINALITY_INVALID");
        }
        return intentPorts.get(0);
    }

    private RuntimeStateAuditService requiredStateAuditService() {
        if (stateAuditService == null) {
            throw new IllegalStateException(
                    "RUNTIME_AGENT_TERMINATION_AUTHORITY_UNAVAILABLE");
        }
        return stateAuditService;
    }

}
