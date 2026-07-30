package com.foggy.navigator.session.lifecycle;

public record TerminalCleanupPlanEntry(
        TerminalCleanupParticipant participant,
        CleanupApplicability applicability,
        String reasonCode
) {
}
