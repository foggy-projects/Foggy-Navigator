package com.foggy.navigator.common.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IssuedManagementTokenTest {

    @Test
    void isNeverSerializedOrRenderedWithBearerOrInternalTokenIdentifiers() throws Exception {
        String bearer = "fixture-newly-issued-opaque-bearer";
        String tokenId = "fixture-token-id";
        String tokenReference = "fixture-token-reference";
        IssuedManagementToken token = new IssuedManagementToken(
                bearer, tokenId, tokenReference, ManagementTokenPurpose.CONTROL_ACCESS,
                Instant.parse("2030-01-01T00:05:00Z"));

        assertThrows(JsonProcessingException.class,
                () -> new ObjectMapper().findAndRegisterModules().writeValueAsString(token));
        assertFalse(token.toString().contains(bearer));
        assertFalse(token.toString().contains(tokenId));
        assertFalse(token.toString().contains(tokenReference));
    }
}
