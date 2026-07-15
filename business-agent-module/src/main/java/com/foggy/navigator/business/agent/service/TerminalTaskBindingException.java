package com.foggy.navigator.business.agent.service;

/**
 * Signals that a task-token bind raced with an already committed terminal
 * provider state. The lifecycle transaction deliberately commits the binding,
 * revocation and tombstone correlation before this exception reaches the
 * caller, so callers must treat it as a failed dispatch rather than retrying
 * the same capability.
 */
public class TerminalTaskBindingException extends IllegalStateException {

    public TerminalTaskBindingException(String message) {
        super(message);
    }
}
