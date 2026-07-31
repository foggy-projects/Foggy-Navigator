package com.foggy.navigator.business.agent.lifecycle;

import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;
import com.foggy.navigator.spi.lifecycle.TerminalTombstoneParticipant;
import com.foggy.navigator.spi.lifecycle.TombstoneApplicability;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BusinessTaskTerminalTombstoneParticipant
        implements TerminalTombstoneParticipant {

    private final BusinessTaskScopedTokenLifecycleService tokenLifecycleService;

    public BusinessTaskTerminalTombstoneParticipant(
            BusinessTaskScopedTokenLifecycleService tokenLifecycleService) {
        this.tokenLifecycleService = tokenLifecycleService;
    }

    @Override
    public TombstoneApplicability applicability(TerminalTombstoneContext context) {
        boolean supported = context != null
                && text(context.tenantId())
                && text(context.providerTaskId())
                && text(context.providerTaskUserId())
                && ("codex-biz-worker".equals(context.providerType())
                    || "codex-worker".equals(context.providerType()));
        return new TombstoneApplicability(
                supported,
                supported ? null : "CAPABILITY_DOMAIN_UNSUPPORTED");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAuthoritativeTombstone(
            TerminalTombstoneContext context,
            String terminalOutcome,
            String terminalSource,
            String idempotencyKey) {
        TombstoneApplicability applicability = applicability(context);
        if (!applicability.capabilityDomainSupported()) {
            throw new IllegalStateException("CAPABILITY_TOMBSTONE_CONTEXT_UNSUPPORTED");
        }
        tokenLifecycleService.recordTerminalState(
                context.tenantId(),
                context.providerTaskId(),
                context.providerTaskUserId(),
                context.sourceAgentId(),
                compatibilityStatus(terminalOutcome));
    }

    private String compatibilityStatus(String terminalOutcome) {
        return switch (terminalOutcome) {
            case "CANCELLED" -> "ABORTED";
            case "SUCCEEDED", "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            default -> throw new IllegalArgumentException(
                    "LIFECYCLE_TERMINAL_OUTCOME_UNSUPPORTED");
        };
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
