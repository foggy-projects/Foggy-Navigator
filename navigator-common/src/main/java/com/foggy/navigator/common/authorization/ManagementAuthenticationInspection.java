package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Set;

/** Safe whoami/permissions payload; authority ceiling and current lane remain separate. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagementAuthenticationInspection(
        ManagementAuthenticationContext authenticationContext,
        Set<String> authorityCeilingActions,
        Set<String> effectiveCredentialActions
) {

    public ManagementAuthenticationInspection {
        authorityCeilingActions = authorityCeilingActions == null ? Set.of() : Set.copyOf(authorityCeilingActions);
        effectiveCredentialActions = effectiveCredentialActions == null ? Set.of() : Set.copyOf(effectiveCredentialActions);
    }
}
