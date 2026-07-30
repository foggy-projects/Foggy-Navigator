package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TerminalCleanupPlanFactoryTest {

    private final TerminalCleanupPlanFactory factory = new TerminalCleanupPlanFactory();

    @Test
    void naturalTerminalWithoutTokenOrTerminationFreezesExactApplicability() {
        Map<TerminalCleanupParticipant, TerminalCleanupPlanEntry> plan = factory.freeze(
                        new TerminalCleanupResources(false, false, false, false))
                .stream()
                .collect(Collectors.toMap(TerminalCleanupPlanEntry::participant, Function.identity()));

        assertEquals(CleanupApplicability.REQUIRED,
                plan.get(TerminalCleanupParticipant.TERMINAL_TOMBSTONE).applicability());
        assertEquals(CleanupApplicability.NOT_APPLICABLE,
                plan.get(TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE).applicability());
        assertEquals("TOKEN_NOT_ISSUED",
                plan.get(TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE).reasonCode());
        assertEquals(CleanupApplicability.REQUIRED,
                plan.get(TerminalCleanupParticipant.COMPATIBILITY_TASK_PROJECTION).applicability());
        assertEquals("NO_TERMINATION_OPERATION",
                plan.get(TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT).reasonCode());
        assertEquals("DERIVED_PROJECTION_NO_RESOURCE",
                plan.get(TerminalCleanupParticipant.ACTIVE_REGISTRATION_RESOURCE).reasonCode());
    }

    @Test
    void acceptedEnforcedTerminationCanNeverMarkReceiptNotApplicable() {
        Map<TerminalCleanupParticipant, TerminalCleanupPlanEntry> plan = factory.freeze(
                        new TerminalCleanupResources(true, true, true, true))
                .stream()
                .collect(Collectors.toMap(TerminalCleanupPlanEntry::participant, Function.identity()));

        assertEquals(CleanupApplicability.REQUIRED,
                plan.get(TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT).applicability());
        assertNull(plan.get(TerminalCleanupParticipant.TERMINATION_COMPAT_RECEIPT).reasonCode());
        assertEquals(CleanupApplicability.REQUIRED,
                plan.get(TerminalCleanupParticipant.PHYSICAL_TOKEN_REVOKE).applicability());
    }
}
