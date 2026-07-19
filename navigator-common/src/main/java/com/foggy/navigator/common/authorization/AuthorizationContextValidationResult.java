package com.foggy.navigator.common.authorization;

/** Validation result intentionally exposes only a stable reason code. */
public record AuthorizationContextValidationResult(
        boolean valid,
        AuthorizationReasonCode reasonCode
) {

    public static AuthorizationContextValidationResult accepted() {
        return new AuthorizationContextValidationResult(true, null);
    }

    public static AuthorizationContextValidationResult invalid(AuthorizationReasonCode reasonCode) {
        return new AuthorizationContextValidationResult(false, reasonCode);
    }
}
