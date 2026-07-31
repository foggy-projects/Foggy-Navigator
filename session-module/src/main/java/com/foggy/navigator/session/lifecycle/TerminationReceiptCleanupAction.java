package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.TerminalCleanupPort;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TerminationReceiptCleanupAction implements TerminalCleanupAction {
    private final PhysicalTokenCleanupAction contexts;
    private final List<TerminalCleanupPort> ports;

    public TerminationReceiptCleanupAction(
            PhysicalTokenCleanupAction contexts,
            List<TerminalCleanupPort> ports) {
        this.contexts = contexts;
        this.ports = List.copyOf(ports);
    }

    @Override
    public TerminalCleanupParticipant participant() {
        return TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT;
    }

    @Override
    public String execute(String taskId, String idempotencyKey) {
        var context = contexts.context(taskId);
        return ports.stream()
                .filter(port -> port.supports(participant().name(), context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TERMINATION_RECEIPT_CLEANUP_PORT_MISSING"))
                .execute(participant().name(), context, idempotencyKey);
    }
}
