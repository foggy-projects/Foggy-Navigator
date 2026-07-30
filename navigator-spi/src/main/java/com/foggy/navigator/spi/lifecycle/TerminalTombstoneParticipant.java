package com.foggy.navigator.spi.lifecycle;

public interface TerminalTombstoneParticipant {
    TombstoneApplicability applicability(TerminalTombstoneContext context);

    void recordAuthoritativeTombstone(
            TerminalTombstoneContext context,
            String terminalOutcome,
            String terminalSource,
            String idempotencyKey);
}
