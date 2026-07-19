package com.foggy.navigator.common.authorization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpaqueSecretMaterialTest {

    private final OpaqueSecretGenerator generator = new OpaqueSecretGenerator();
    private final OpaqueSecretHasher hasher = new OpaqueSecretHasher();
    private final TypedManagementPresentationCodec codec = new TypedManagementPresentationCodec();

    @Test
    void hashesAndParsesOpaquePresentationsWithoutRenderingSecrets() {
        OpaqueSecretMaterial secret = generator.generateSecret();
        String otherSecret = generator.generateSecret().value();
        String hash = hasher.hash(secret);
        String presentation = codec.encodePrincipalCredential("fixture-ref", secret);

        assertTrue(hasher.matches(hash, secret));
        assertFalse(hasher.matches(hash, OpaqueSecretMaterial.of(otherSecret)));
        assertNotEquals(secret.value(), otherSecret);
        assertEquals("[redacted]", secret.toString());
        assertFalse(secret.toString().contains(secret.value()));
        assertFalse(TypedManagementAuthenticationRequest.fromHttpHeaders(
                "route", "action", "correlation", presentation, null, false).toString().contains(secret.value()));

        TypedManagementPresentationCodec.DecodedPresentation decoded = codec.decodePrincipalCredential(
                OpaqueSecretMaterial.of(presentation)).orElseThrow();
        assertEquals("fixture-ref", decoded.reference());
        assertTrue(hasher.matches(hash, decoded.secret()));
        assertFalse(decoded.toString().contains(secret.value()));
    }

    @Test
    void rejectsMalformedAndWrongTypePresentations() {
        OpaqueSecretMaterial secret = generator.generateSecret();
        String token = codec.encodeManagementToken("fixture-token", secret);

        assertTrue(codec.decodePrincipalCredential(OpaqueSecretMaterial.of(token)).isEmpty());
        assertTrue(codec.decodeManagementToken(OpaqueSecretMaterial.of("navi-mt1.not-base64.secret")).isEmpty());
        assertTrue(codec.decodeManagementToken(OpaqueSecretMaterial.of("navi-mt1..secret")).isEmpty());
        assertTrue(codec.decodeManagementToken(OpaqueSecretMaterial.of("Bearer arbitrary.jwt.parts")).isEmpty());
    }
}
