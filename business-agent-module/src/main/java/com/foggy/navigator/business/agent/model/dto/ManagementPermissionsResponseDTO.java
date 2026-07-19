package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.ManagementAuthenticationInspection;

import java.util.Set;

/** Keeps a principal's ceiling distinct from the actions on the presented credential lane. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManagementPermissionsResponseDTO {

    private final AuthorizationPrincipalType principalType;
    private final String navigatorInstanceId;
    private final AuthorizationCredentialLane credentialLane;
    private final Set<String> authorityCeilingActions;
    private final Set<String> effectiveCredentialActions;

    private ManagementPermissionsResponseDTO(
            AuthorizationPrincipalType principalType,
            String navigatorInstanceId,
            AuthorizationCredentialLane credentialLane,
            Set<String> authorityCeilingActions,
            Set<String> effectiveCredentialActions
    ) {
        this.principalType = principalType;
        this.navigatorInstanceId = navigatorInstanceId;
        this.credentialLane = credentialLane;
        this.authorityCeilingActions = Set.copyOf(authorityCeilingActions);
        this.effectiveCredentialActions = Set.copyOf(effectiveCredentialActions);
    }

    public static ManagementPermissionsResponseDTO from(ManagementAuthenticationInspection inspection) {
        if (inspection == null || inspection.authenticationContext() == null) {
            throw new IllegalArgumentException("management authentication inspection is required");
        }
        var context = inspection.authenticationContext();
        return new ManagementPermissionsResponseDTO(
                context.principalType(),
                context.navigatorInstanceId(),
                context.credentialLane(),
                inspection.authorityCeilingActions(),
                inspection.effectiveCredentialActions());
    }

    public AuthorizationPrincipalType getPrincipalType() {
        return principalType;
    }

    public String getNavigatorInstanceId() {
        return navigatorInstanceId;
    }

    public AuthorizationCredentialLane getCredentialLane() {
        return credentialLane;
    }

    public Set<String> getAuthorityCeilingActions() {
        return authorityCeilingActions;
    }

    public Set<String> getEffectiveCredentialActions() {
        return effectiveCredentialActions;
    }
}
