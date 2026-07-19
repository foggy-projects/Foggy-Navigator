package com.foggy.navigator.common.authorization;

/**
 * Opaque server-verification input for issuing one action-bound management
 * security token. A controller forwards proof material only; it must not
 * declare that step-up or approval succeeded.
 */
public record ManagementSecurityActionAuthorizationRequest(
        ManagementSecurityActionBinding actionBinding,
        OpaqueSecretMaterial stepUpProof,
        OpaqueSecretMaterial approvalProof
) {
}
