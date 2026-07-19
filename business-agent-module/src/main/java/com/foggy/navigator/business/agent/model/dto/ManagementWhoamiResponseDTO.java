package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.ManagementAuthenticationInspection;

import java.time.Instant;
import java.util.Set;

/** Safe identity summary; no credential presentation, verifier or fingerprint is exposed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManagementWhoamiResponseDTO {

    private final AuthorizationPrincipalType principalType;
    private final String principalId;
    private final String sourceUpstreamSystemId;
    private final String navigatorInstanceId;
    private final String environmentProfile;
    private final AuthorizationCredentialLane credentialLane;
    private final String credentialStatus;
    private final Instant credentialExpiresAt;
    private final Set<String> authorityCeilingActions;
    private final Set<String> effectiveCredentialActions;

    private ManagementWhoamiResponseDTO(
            AuthorizationPrincipalType principalType,
            String principalId,
            String sourceUpstreamSystemId,
            String navigatorInstanceId,
            String environmentProfile,
            AuthorizationCredentialLane credentialLane,
            String credentialStatus,
            Instant credentialExpiresAt,
            Set<String> authorityCeilingActions,
            Set<String> effectiveCredentialActions
    ) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.sourceUpstreamSystemId = sourceUpstreamSystemId;
        this.navigatorInstanceId = navigatorInstanceId;
        this.environmentProfile = environmentProfile;
        this.credentialLane = credentialLane;
        this.credentialStatus = credentialStatus;
        this.credentialExpiresAt = credentialExpiresAt;
        this.authorityCeilingActions = Set.copyOf(authorityCeilingActions);
        this.effectiveCredentialActions = Set.copyOf(effectiveCredentialActions);
    }

    public static ManagementWhoamiResponseDTO from(ManagementAuthenticationInspection inspection) {
        if (inspection == null || inspection.authenticationContext() == null) {
            throw new IllegalArgumentException("management authentication inspection is required");
        }
        var context = inspection.authenticationContext();
        return new ManagementWhoamiResponseDTO(
                context.principalType(),
                context.principalId(),
                context.sourceUpstreamSystemId(),
                context.navigatorInstanceId(),
                context.environmentProfile(),
                context.credentialLane(),
                context.credentialStatus(),
                context.credentialExpiresAt(),
                inspection.authorityCeilingActions(),
                inspection.effectiveCredentialActions());
    }

    public AuthorizationPrincipalType getPrincipalType() {
        return principalType;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getSourceUpstreamSystemId() {
        return sourceUpstreamSystemId;
    }

    public String getNavigatorInstanceId() {
        return navigatorInstanceId;
    }

    public String getEnvironmentProfile() {
        return environmentProfile;
    }

    public AuthorizationCredentialLane getCredentialLane() {
        return credentialLane;
    }

    public String getCredentialStatus() {
        return credentialStatus;
    }

    public Instant getCredentialExpiresAt() {
        return credentialExpiresAt;
    }

    public Set<String> getAuthorityCeilingActions() {
        return authorityCeilingActions;
    }

    public Set<String> getEffectiveCredentialActions() {
        return effectiveCredentialActions;
    }
}
