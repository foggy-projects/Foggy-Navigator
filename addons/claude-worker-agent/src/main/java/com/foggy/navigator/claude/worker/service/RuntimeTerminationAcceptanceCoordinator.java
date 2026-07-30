package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.lifecycle.RuntimeTerminationIntentPort;
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
            String providerType,
            String physicalWorkerId) {
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
                    port.recordIntent(
                            clientRequestId, taskId, providerType, physicalWorkerId);
                }
            }
            return registration;
        });
    }
}
