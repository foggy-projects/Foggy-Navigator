package com.foggy.navigator.common.authorization;

/**
 * The only credential presentations accepted by the typed management ingress.
 * A request containing no source, more than one source, or any legacy/runtime
 * source is rejected by {@link TypedManagementIngressAuthorizer}; callers must
 * not treat this enum as a source-priority list.
 */
public enum TypedManagementCredentialSource {
    NONE,
    PRINCIPAL_CREDENTIAL,
    MANAGEMENT_BEARER
}
