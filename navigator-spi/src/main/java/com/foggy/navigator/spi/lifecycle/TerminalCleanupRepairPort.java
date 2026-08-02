package com.foggy.navigator.spi.lifecycle;

/**
 * Provider-neutral terminal cleanup repair boundary.
 *
 * <p>The command deliberately contains only an already persisted task identity,
 * the caller's expected physical Worker fence, and a request correlation id.
 * It carries no client-asserted terminal outcome, provider effect, or cleanup
 * state. Implementations must derive those facts from durable lifecycle state.</p>
 */
public interface TerminalCleanupRepairPort {

    TerminalCleanupRepairAssessment assess(
            TerminalCleanupRepairAssessmentCommand command);

    TerminalCleanupRepairResult repair(TerminalCleanupRepairCommand command);

    record TerminalCleanupRepairAssessmentCommand(
            String taskId,
            String expectedPhysicalWorkerId) {
    }

    record TerminalCleanupRepairAssessment(
            boolean repairEligible,
            boolean terminalTombstonePresent,
            boolean cleanupComplete,
            String safeReasonCode) {
    }

    record TerminalCleanupRepairCommand(
            String taskId,
            String expectedPhysicalWorkerId,
            String clientRequestId) {
    }

    record TerminalCleanupRepairResult(
            boolean repairAccepted,
            boolean terminalTombstonePresent,
            boolean cleanupComplete,
            String safeReasonCode) {
    }
}
