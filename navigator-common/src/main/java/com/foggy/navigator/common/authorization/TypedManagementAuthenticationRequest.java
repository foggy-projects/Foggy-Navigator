package com.foggy.navigator.common.authorization;

import java.util.Locale;

/**
 * Ingress-only transport for typed management credentials. The raw materials
 * are redacted objects and are consumed only by the common authorization
 * facade; this object must never be placed into a request attribute or audit
 * record.
 */
public record TypedManagementAuthenticationRequest(
        String routeId,
        String actionId,
        String correlationId,
        OpaqueSecretMaterial principalCredential,
        OpaqueSecretMaterial managementBearer,
        boolean prohibitedCredentialSourcePresent,
        boolean malformedTypedCredentialPresentation
) {

    /**
     * Compatibility constructor for non-HTTP callers. HTTP ingress should use
     * {@link #fromHttpHeaders(String, String, String, String, String, boolean)}
     * so malformed typed carriers remain distinguishable from a missing one.
     */
    public TypedManagementAuthenticationRequest(
            String routeId,
            String actionId,
            String correlationId,
            OpaqueSecretMaterial principalCredential,
            OpaqueSecretMaterial managementBearer,
            boolean prohibitedCredentialSourcePresent
    ) {
        this(routeId, actionId, correlationId, principalCredential, managementBearer,
                prohibitedCredentialSourcePresent, false);
    }

    public static TypedManagementAuthenticationRequest fromHttpHeaders(
            String routeId,
            String actionId,
            String correlationId,
            String principalCredentialHeader,
            String authorizationHeader,
            boolean prohibitedCredentialSourcePresent
    ) {
        boolean malformedAuthorization = authorizationHeader != null && !isBearerHeader(authorizationHeader);
        String bearer = isBearerHeader(authorizationHeader)
                ? authorizationHeader.trim().substring("bearer".length()).trim() : null;
        boolean malformedPrincipalCredential = principalCredentialHeader != null
                && principalCredentialHeader.isBlank();
        return new TypedManagementAuthenticationRequest(
                routeId,
                actionId,
                correlationId,
                OpaqueSecretMaterial.of(principalCredentialHeader),
                OpaqueSecretMaterial.of(bearer),
                prohibitedCredentialSourcePresent,
                malformedAuthorization || malformedPrincipalCredential
        );
    }

    private static boolean isBearerHeader(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String trimmed = authorizationHeader.trim();
        return trimmed.length() >= "bearer".length()
                && trimmed.regionMatches(true, 0, "bearer", 0, "bearer".length())
                && trimmed.length() > "bearer".length()
                && Character.isWhitespace(trimmed.charAt("bearer".length()));
    }

    /** Never render raw credential material in diagnostics. */
    @Override
    public String toString() {
        return "TypedManagementAuthenticationRequest[routeId=" + routeId
                + ", actionId=" + actionId
                + ", correlationId=" + correlationId
                + ", principalCredential=" + (principalCredential == null ? "absent" : principalCredential.redacted())
                + ", managementBearer=" + (managementBearer == null ? "absent" : managementBearer.redacted())
                + ", prohibitedCredentialSourcePresent=" + prohibitedCredentialSourcePresent
                + ", malformedTypedCredentialPresentation=" + malformedTypedCredentialPresentation + "]";
    }
}
