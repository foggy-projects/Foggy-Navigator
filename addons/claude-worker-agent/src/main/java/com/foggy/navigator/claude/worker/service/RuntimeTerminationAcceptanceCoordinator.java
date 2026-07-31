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
            String operationId = operationId(
                    providerType, clientRequestId);
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
                            dispatchId(operationId),
                            operationId,
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

    private String operationId(
            String providerType, String clientRequestId) {
        if (providerType != null
                && providerType.startsWith("codex-")) {
            String compact = clientRequestId.replaceAll(
                    "[^A-Za-z0-9]", "");
            if (compact.isEmpty()) {
                throw new IllegalArgumentException(
                        "CLIENT_REQUEST_ID_INVALID");
            }
            return "rt_" + compact.substring(
                    0, Math.min(56, compact.length()));
        }
        return java.util.UUID.nameUUIDFromBytes(
                ("termination-operation:" + clientRequestId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private String dispatchId(String operationId) {
        return java.util.UUID.nameUUIDFromBytes(
                ("codex-lifecycle:TERMINATION_CANCEL:" + operationId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
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
