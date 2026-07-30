package com.foggy.navigator.session.lifecycle;

public enum TerminalCleanupParticipant {
    TERMINAL_TOMBSTONE,
    PHYSICAL_TOKEN_REVOKE,
    COMPATIBILITY_TASK_PROJECTION,
    TERMINATION_COMPAT_RECEIPT,
    ACTIVE_REGISTRATION_RESOURCE
}
