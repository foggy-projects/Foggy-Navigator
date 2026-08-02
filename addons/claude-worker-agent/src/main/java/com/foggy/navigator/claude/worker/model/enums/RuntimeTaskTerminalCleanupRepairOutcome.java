package com.foggy.navigator.claude.worker.model.enums;

/**
 * Outcome of the dedicated terminal-cleanup repair contract.
 *
 * <p>This is deliberately separate from termination outcomes: a repair never
 * sends a termination request or a provider/Worker command.</p>
 */
public enum RuntimeTaskTerminalCleanupRepairOutcome {
    /** A dry-run proved the stable repair gate and may be confirmed once. */
    READY,
    /** Navigator accepted and ran the provider-neutral durable cleanup repair. */
    REPAIRED,
    /** Navigator already observed a fully converged terminal cleanup. */
    ALREADY_CONVERGED,
    /** The durable server facts did not permit this repair. */
    REJECTED,
    /** A null, ambiguous, or future state. */
    UNKNOWN
}
