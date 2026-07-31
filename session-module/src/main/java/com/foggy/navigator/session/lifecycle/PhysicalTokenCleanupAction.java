package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.TaskTerminalTombstoneRepository;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupContext;
import com.foggy.navigator.spi.lifecycle.TerminalCleanupPort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PhysicalTokenCleanupAction implements TerminalCleanupAction {
    private final TaskTerminalTombstoneRepository tombstones;
    private final List<TerminalCleanupPort> ports;

    public PhysicalTokenCleanupAction(
            TaskTerminalTombstoneRepository tombstones,
            List<TerminalCleanupPort> ports) {
        this.tombstones = tombstones;
        this.ports = List.copyOf(ports);
    }

    @Override
    public TerminalCleanupParticipant participant() {
        return TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE;
    }

    @Override
    public String execute(String taskId, String idempotencyKey) {
        TerminalCleanupContext context = context(taskId);
        return ports.stream()
                .filter(port -> port.supports(participant().name(), context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "PHYSICAL_TOKEN_CLEANUP_PORT_MISSING"))
                .execute(participant().name(), context, idempotencyKey);
    }

    TerminalCleanupContext context(String taskId) {
        var value = tombstones.findById(taskId).orElseThrow();
        return new TerminalCleanupContext(
                value.getTaskId(), value.getSessionId(), value.getProviderType(),
                value.getTenantId(), value.getProviderTaskId(),
                value.getProviderTaskUserId(), value.getSourceAgentId(),
                value.getOperationId(), value.getTerminalOutcome());
    }
}
