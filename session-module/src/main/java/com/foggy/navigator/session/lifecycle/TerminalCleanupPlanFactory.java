package com.foggy.navigator.session.lifecycle;

import java.util.List;

public final class TerminalCleanupPlanFactory {

    public List<TerminalCleanupPlanEntry> freeze(TerminalCleanupResources resources) {
        if (resources.terminationAccepted() && !resources.terminationReceiptPersisted()) {
            throw new IllegalArgumentException(
                    "ACCEPTED_ENFORCED_TERMINATION_RECEIPT_REQUIRED");
        }
        return List.of(
                required(TerminalCleanupParticipant.TERMINAL_TOMBSTONE),
                resources.physicalTokenIssued()
                        ? required(TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE)
                        : notApplicable(
                                TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE,
                                "TOKEN_NOT_ISSUED"),
                required(TerminalCleanupParticipant.COMPATIBILITY_TASK_PROJECTION),
                resources.terminationAccepted()
                        ? required(TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT)
                        : notApplicable(
                                TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT,
                                "NO_TERMINATION_OPERATION"),
                notApplicable(
                        TerminalCleanupParticipant.ACTIVE_REGISTRATION_RESOURCE,
                        "DERIVED_PROJECTION_NO_RESOURCE"));
    }

    private TerminalCleanupPlanEntry required(TerminalCleanupParticipant participant) {
        return new TerminalCleanupPlanEntry(participant, CleanupApplicability.REQUIRED, null);
    }

    private TerminalCleanupPlanEntry notApplicable(
            TerminalCleanupParticipant participant, String reason) {
        return new TerminalCleanupPlanEntry(
                participant, CleanupApplicability.NOT_APPLICABLE, reason);
    }
}
