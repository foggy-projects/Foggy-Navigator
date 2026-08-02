package com.foggy.navigator.spi.lifecycle;

import java.util.List;

/**
 * Provider-neutral read-only evidence for terminal cleanup components that
 * live outside the lifecycle owner.  A terminal snapshot marked complete is
 * not sufficient when an owned capability remains usable; the lifecycle
 * owner consumes this port only to decide whether an existing checkpoint can
 * be reopened for its already-defined cleanup participant.
 */
public interface TerminalCleanupCompletenessPort {

    boolean supports(TerminalCleanupContext context);

    List<ParticipantCompleteness> assess(TerminalCleanupContext context);

    record ParticipantCompleteness(String participant, boolean complete) {
    }
}
