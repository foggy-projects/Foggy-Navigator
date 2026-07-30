package com.foggy.navigator.session.lifecycle;

public record TerminalCleanupResources(
        boolean physicalTokenIssued,
        boolean terminationAccepted,
        boolean terminationReceiptPersisted,
        boolean capabilityDomainSupported
) {
}
