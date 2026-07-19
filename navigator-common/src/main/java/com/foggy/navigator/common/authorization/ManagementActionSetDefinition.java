package com.foggy.navigator.common.authorization;

import java.util.Set;

/** Immutable, source-controlled typed management action-set definition. */
public record ManagementActionSetDefinition(
        AuthorizationCredentialLane credentialLane,
        AuthorizationPrincipalType principalType,
        String actionSetRef,
        Set<String> actions
) {

    public ManagementActionSetDefinition {
        actions = Set.copyOf(actions);
    }
}
