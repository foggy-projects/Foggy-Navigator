package com.foggy.navigator.business.agent.lifecycle;

import com.foggy.navigator.business.agent.service.BusinessTaskScopedTokenLifecycleService;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupCompletenessPort;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Exposes the business-owned task-token terminal fact without leaking token
 * persistence into the lifecycle owner.  It is intentionally read-only;
 * {@code PHYSICAL_TOKEN_REVOKE} remains the sole mutation participant.
 */
@Component
@RequiredArgsConstructor
public class BusinessTerminalCleanupCompletenessPort
        implements TerminalCleanupCompletenessPort {

    private static final String PHYSICAL_TOKEN_REVOKE = "PHYSICAL_TOKEN_REVOKE";

    private final BusinessTaskScopedTokenLifecycleService tokens;

    @Override
    public boolean supports(TerminalCleanupContext context) {
        return context != null
                && text(context.tenantId())
                && text(context.taskId());
    }

    @Override
    public List<ParticipantCompleteness> assess(TerminalCleanupContext context) {
        if (!supports(context)) {
            return List.of();
        }
        return List.of(new ParticipantCompleteness(
                PHYSICAL_TOKEN_REVOKE,
                tokens.taskScopedTokensRevokedOrAbsent(
                        context.tenantId(), context.taskId())));
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
