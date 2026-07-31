package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

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
            String providerTaskId) {
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
                for (RuntimeTerminationIntentPort port : intentPorts) {
                    port.recordIntent(new RuntimeTerminationIntentPort.RuntimeTerminationIntent(
                            clientRequestId,
                            taskId,
                            sessionId,
                            providerType,
                            physicalWorkerId,
                            providerTaskId,
                            operationId(clientRequestId),
                            bindingDigest(taskId, providerType, physicalWorkerId,
                                    providerTaskId, clientRequestId)));
                }
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

    private String operationId(String clientRequestId) {
        return java.util.UUID.nameUUIDFromBytes(
                ("termination-operation:" + clientRequestId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String bindingDigest(
            String taskId,
            String providerType,
            String workerId,
            String providerTaskId,
            String clientRequestId) {
        String material = String.join("\n",
                Objects.toString(taskId, ""),
                Objects.toString(providerType, ""),
                Objects.toString(workerId, ""),
                Objects.toString(providerTaskId, ""),
                Objects.toString(clientRequestId, ""));
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
