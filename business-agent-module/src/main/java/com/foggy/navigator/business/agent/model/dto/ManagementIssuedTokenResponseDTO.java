package com.foggy.navigator.business.agent.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foggy.navigator.common.authorization.IssuedManagementToken;
import com.foggy.navigator.common.authorization.ManagementTokenPurpose;

import java.time.Instant;

/**
 * One-time response envelope for the newly issued opaque bearer.
 *
 * <p>The {@code token} field is the one intentional credential disclosure:
 * it is returned only to the already authenticated caller as part of the
 * successful issuance response. Presented credentials, token identifiers,
 * token references, verifier material, and diagnostic rendering are never
 * included in this DTO.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManagementIssuedTokenResponseDTO {

    private final String token;
    private final ManagementTokenPurpose purpose;
    private final Instant expiresAt;

    private ManagementIssuedTokenResponseDTO(String token, ManagementTokenPurpose purpose, Instant expiresAt) {
        this.token = token;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public static ManagementIssuedTokenResponseDTO from(IssuedManagementToken token) {
        if (token == null) {
            throw new IllegalArgumentException("issued management token is required");
        }
        return new ManagementIssuedTokenResponseDTO(token.bearerToken(), token.purpose(), token.expiresAt());
    }

    public String getToken() {
        return token;
    }

    public ManagementTokenPurpose getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "ManagementIssuedTokenResponseDTO[token=[redacted], purpose=" + purpose
                + ", expiresAt=" + expiresAt + "]";
    }
}
