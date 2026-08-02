package com.foggy.navigator.business.agent.event;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessTaskScopedTokenTerminalListener {

    static final String REVOKED_BY = "system:task-lifecycle";
    private static final Set<String> DEFINITIVE_TERMINAL_STATUSES = Set.of(
            "COMPLETED", "FAILED", "REJECTED", "TIMED_OUT", "TIMEOUT",
            "ABORTED", "CANCELLED", "CANCELED");

    private final BusinessTaskScopedTokenLifecycleService tokenLifecycleService;
    private final RuntimeRequestAuditService runtimeRequestAuditService;

    @TransactionalEventListener(
            phase = TransactionPhase.BEFORE_COMMIT,
            fallbackExecution = true)
    @Order(0)
    public void recordTerminalStateBeforeCommit(TaskStatusChangeEvent event) {
        TerminalTransition terminal = terminalTransition(event);
        if (terminal == null) {
            return;
        }
        tokenLifecycleService.recordTerminalState(
                terminal.tenantId(),
                terminal.workerTaskId(),
                terminal.providerTaskUserId(),
                terminal.sourceAgentId(),
                terminal.status());
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    @Order(1)
    public void materializeRevocationAfterCommit(TaskStatusChangeEvent event) {
        TerminalTransition terminal = terminalTransition(event);
        if (terminal == null) {
            return;
        }
        try {
            tokenLifecycleService.materializeTerminalRevocation(
                    terminal.tenantId(),
                    terminal.workerTaskId(),
                    REVOKED_BY);
            runtimeRequestAuditService.taskTerminalRecorded(
                    terminal.workerTaskId(),
                    terminal.status(),
                    safeErrorCode(event),
                    event.getRuntimeDispatched(),
                    event.getModelDispatched(),
                    event.getDispatchCount());
        } catch (RuntimeException e) {
            // The durable tombstone written before commit remains the
            // authorization authority. Replaying the event retries this row
            // materialization without reopening the capability.
            log.error("Failed to materialize terminal task token revocation; " +
                            "the durable terminal tombstone remains fail-closed: " +
                            "tenantId={}, workerTaskId={}, status={}, errorType={}",
                    terminal.tenantId(), terminal.workerTaskId(), terminal.status(),
                    e.getClass().getSimpleName());
        }
    }

    private TerminalTransition terminalTransition(TaskStatusChangeEvent event) {
        if (event == null || !StringUtils.hasText(event.getTaskId())
                || !StringUtils.hasText(event.getTenantId())
                || !StringUtils.hasText(event.getUserId())
                || !StringUtils.hasText(event.getStatus())) {
            return null;
        }
        String status = event.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!DEFINITIVE_TERMINAL_STATUSES.contains(status)
                || !Boolean.FALSE.equals(event.getRecoverable())) {
            return null;
        }
        return new TerminalTransition(
                event.getTenantId().trim(),
                event.getTaskId().trim(),
                event.getUserId().trim(),
                trimToNull(event.getAgentId()),
                status);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeErrorCode(TaskStatusChangeEvent event) {
        String value = event != null && event.getError() != null
                ? event.getError().getErrorCode()
                : event != null ? event.getErrorMessage() : null;
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
        return candidate.matches("[A-Z][A-Z0-9_]{2,127}")
                ? candidate : null;
    }

    private record TerminalTransition(
            String tenantId,
            String workerTaskId,
            String providerTaskUserId,
            String sourceAgentId,
            String status) {
    }
}
