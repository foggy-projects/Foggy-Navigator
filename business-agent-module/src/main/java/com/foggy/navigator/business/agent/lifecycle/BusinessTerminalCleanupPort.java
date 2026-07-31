package com.foggy.navigator.business.agent.lifecycle;

import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupPort;
import org.springframework.stereotype.Component;

@Component
public class BusinessTerminalCleanupPort implements TerminalCleanupPort {
    private static final String TOKEN = "PHYSICAL_TOKEN_REVOKE";
    private static final String RECEIPT = "TERMINATION_COMPAT_RECEIPT";

    private final BusinessTaskScopedTokenLifecycleService tokens;
    private final RuntimeRequestAuditService audits;

    public BusinessTerminalCleanupPort(
            BusinessTaskScopedTokenLifecycleService tokens,
            RuntimeRequestAuditService audits) {
        this.tokens = tokens;
        this.audits = audits;
    }

    @Override
    public boolean supports(String participant, TerminalCleanupContext context) {
        if (context == null) return false;
        if (TOKEN.equals(participant)) {
            return text(context.tenantId()) && text(context.taskId());
        }
        return RECEIPT.equals(participant)
                && text(context.operationId())
                && text(context.taskId());
    }

    @Override
    public boolean resourcePresent(
            String participant, TerminalCleanupContext context) {
        if (!supports(participant, context)) return false;
        if (TOKEN.equals(participant)) {
            return tokens.hasTaskScopedToken(
                    context.tenantId(), context.taskId());
        }
        return audits.hasDurableTaskOperationReceipt(
                context.taskId(),
                RuntimeRequestAuditService.OPERATION_TASK_TERMINATE);
    }

    @Override
    public String execute(
            String participant,
            TerminalCleanupContext context,
            String idempotencyKey) {
        if (!supports(participant, context)) {
            throw new IllegalStateException(
                    "TERMINAL_CLEANUP_CONTEXT_UNSUPPORTED_" + participant);
        }
        if (TOKEN.equals(participant)) {
            tokens.revokeTaskScopedTokensForTask(
                    context.tenantId(),
                    context.taskId(),
                    "lifecycle-owner",
                    "canonical task terminal");
        } else if (RECEIPT.equals(participant)) {
            audits.refreshCompletedTaskOperation(
                    context.taskId(),
                    RuntimeRequestAuditService.OPERATION_TASK_TERMINATE,
                    new RuntimeRequestAuditService.TaskEvidence(
                            context.taskId(),
                            compatibilityStatus(context.terminalOutcome()),
                            true,
                            null,
                            context.sourceAgentId(),
                            context.providerTaskUserId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "REVOKED",
                            true,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "TERMINAL_EVIDENCE_OBSERVED"));
        } else {
            throw new IllegalArgumentException(
                    "TERMINAL_CLEANUP_PARTICIPANT_UNSUPPORTED");
        }
        return idempotencyKey;
    }

    private String compatibilityStatus(String outcome) {
        return switch (outcome) {
            case "SUCCEEDED", "COMPLETED" -> "COMPLETED";
            case "CANCELLED" -> "ABORTED";
            default -> "FAILED";
        };
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
