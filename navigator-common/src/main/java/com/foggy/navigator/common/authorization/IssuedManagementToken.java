package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Internal issuance result. Its bearer is converted to the one-time endpoint
 * response only after the caller has been authenticated.
 */
public final class IssuedManagementToken {

    private final String bearerToken;
    private final String tokenId;
    private final String tokenReference;
    private final ManagementTokenPurpose purpose;
    private final Instant expiresAt;

    public IssuedManagementToken(String bearerToken,
                                 String tokenId,
                                 String tokenReference,
                                 ManagementTokenPurpose purpose,
                                 Instant expiresAt) {
        this.bearerToken = bearerToken;
        this.tokenId = tokenId;
        this.tokenReference = tokenReference;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    /** The only raw-secret accessor; use solely when serializing the one-time endpoint response. */
    @JsonIgnore
    public String bearerToken() {
        return bearerToken;
    }

    @JsonIgnore
    public String tokenId() {
        return tokenId;
    }

    @JsonIgnore
    public String tokenReference() {
        return tokenReference;
    }

    public ManagementTokenPurpose purpose() {
        return purpose;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "IssuedManagementToken[tokenId=[redacted], tokenReference=[redacted]"
                + ", purpose=" + purpose + ", expiresAt=" + expiresAt + ", bearerToken=[redacted]]";
    }
}
