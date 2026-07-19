package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Safe authenticated attributes for request-local use. Store this, rather than
 * {@link TypedManagementAuthenticationRequest}, under
 * {@code ManagementAuthenticationContext.class.getName()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManagementAuthenticationContext(
        String principalRecordId,
        AuthorizationPrincipalType principalType,
        String principalId,
        String sourceUpstreamSystemId,
        String navigatorInstanceId,
        String environmentProfile,
        String upstreamTrustProfile,
        String credentialId,
        AuthorizationCredentialLane credentialLane,
        String credentialFingerprint,
        Integer credentialGeneration,
        String actionSetRef,
        String credentialStatus,
        Instant credentialExpiresAt,
        TypedManagementCredentialSource credentialSource,
        ManagementTokenPurpose tokenPurpose,
        String routeId,
        String actionId,
        String correlationId
) {
}
