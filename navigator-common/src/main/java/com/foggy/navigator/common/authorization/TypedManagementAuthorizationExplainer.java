package com.foggy.navigator.common.authorization;

/**
 * Produces a {@code PREFLIGHT + nonBinding=true} decision from a context that
 * the ingress guard has already authenticated. Mutation handlers must call
 * canonical enforcement again and must not reuse this result or decision id.
 */
public interface TypedManagementAuthorizationExplainer {

    ManagementAuthorizationExplanation explain(
            ManagementAuthenticationContext authenticationContext,
            ManagementAuthorizationExplainRequest request
    );
}
