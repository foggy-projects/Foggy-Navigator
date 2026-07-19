package com.foggy.navigator.common.authorization;

/**
 * Canonical binding guard for {@code /api/v1/management/v1/**}. It owns typed
 * source conflict detection and resolver verification; web guards/controllers
 * must not reimplement header preference, credential lookup, or token checks.
 */
public interface TypedManagementIngressAuthorizer {

    TypedManagementAuthorizationResult authorize(TypedManagementAuthenticationRequest request);

    /**
     * Validates and atomically consumes an exact SECURITY_ACTION token. Calling
     * {@link #authorize(TypedManagementAuthenticationRequest)} for whoami,
     * permissions or explain never consumes a security token.
     */
    TypedManagementAuthorizationResult consumeSecurityAction(
            TypedManagementAuthenticationRequest request,
            ManagementSecurityActionBinding binding
    );
}
