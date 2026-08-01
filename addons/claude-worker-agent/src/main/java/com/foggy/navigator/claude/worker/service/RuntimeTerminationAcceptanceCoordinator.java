package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
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

    public RuntimeTerminationAcceptanceCoordinator(
            RuntimeRequestAuditService audits,
            List<RuntimeTerminationIntentPort> intentPorts,
            PlatformTransactionManager transactionManager) {
        this.audits = audits;
        this.intentPorts = List.copyOf(intentPorts);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RuntimeRequestAuditService.TaskOperationRegistration accept(
            String clientRequestId,
            String appKey,
            String appSecret,
            String upstreamUserId,
            String taskId,
            String sessionId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String ownerUserId,
            String tenantId,
            String reason,
            RuntimeTaskClosureProvider provider) {
        return transactions.execute(status -> {
            RuntimeRequestAuditService.TaskOperationRegistration registration =
                    audits.beginTaskOperationIdempotentAtomic(
                            clientRequestId,
                            RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                            appKey,
                            appSecret,
                            null,
                            upstreamUserId,
                            taskId);
            if (!registration.existing()) {
                throw new IllegalStateException(
                        "TERMINATION_REQUEST_RECEIPT_REQUIRED");
            }
            RuntimeTaskClosureProvider.TerminationAdmission admission =
                    provider.prepareTerminationAdmission(
                            taskId, ownerUserId, tenantId,
                            physicalWorkerId, reason, clientRequestId);
            if (admission == null) {
                throw new IllegalStateException(
                        "TERMINATION_EXACT_ADMISSION_UNAVAILABLE");
            }
            for (RuntimeTerminationIntentPort port : intentPorts) {
                port.recordIntent(new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                        clientRequestId,
                        taskId,
                        sessionId,
                        providerType,
                        physicalWorkerId,
                        providerTaskId,
                        admission.dispatchId(),
                        admission.operationId(),
                        admission.ownershipMode(),
                        admission.stateGeneration(),
                        admission.instanceEpoch(),
                        admission.bindingDigestVersion(),
                        admission.bindingDigest()));
            }
            return registration;
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

}
