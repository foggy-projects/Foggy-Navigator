package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Compact presentation parser. References are opaque lookup keys; the secret
 * segment is separately hash-verified and never included in a result DTO.
 */
@Component
public final class TypedManagementPresentationCodec {

    private static final String PRINCIPAL_PREFIX = "navi-pc1";
    private static final String TOKEN_PREFIX = "navi-mt1";

    public Optional<DecodedPresentation> decodePrincipalCredential(OpaqueSecretMaterial presentation) {
        return decode(PRINCIPAL_PREFIX, presentation);
    }

    public Optional<DecodedPresentation> decodeManagementToken(OpaqueSecretMaterial presentation) {
        return decode(TOKEN_PREFIX, presentation);
    }

    public String encodePrincipalCredential(String verifierReference, OpaqueSecretMaterial secret) {
        return encode(PRINCIPAL_PREFIX, verifierReference, secret);
    }

    public String encodeManagementToken(String tokenReference, OpaqueSecretMaterial secret) {
        return encode(TOKEN_PREFIX, tokenReference, secret);
    }

    private Optional<DecodedPresentation> decode(String expectedPrefix, OpaqueSecretMaterial presentation) {
        if (presentation == null || presentation.isBlank()) {
            return Optional.empty();
        }
        String[] parts = presentation.value().split("\\.", -1);
        if (parts.length != 3 || !expectedPrefix.equals(parts[0]) || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        try {
            String reference = decodeReference(parts[1]);
            if (!validReference(reference)) {
                return Optional.empty();
            }
            return Optional.of(new DecodedPresentation(reference, OpaqueSecretMaterial.of(parts[2])));
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    private static String decodeReference(String encodedReference) throws CharacterCodingException {
        byte[] decoded = Base64.getUrlDecoder().decode(encodedReference);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString();
    }

    private static boolean validReference(String reference) {
        return reference != null && !reference.isBlank() && reference.length() <= 192
                && reference.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }

    private String encode(String prefix, String reference, OpaqueSecretMaterial secret) {
        if (reference == null || reference.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("reference and secret must be non-blank");
        }
        return prefix + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(reference.getBytes(StandardCharsets.UTF_8)) + "." + secret.value();
    }

    /** Safe decoded form: its secret segment remains redacted by OpaqueSecretMaterial. */
    public record DecodedPresentation(String reference, OpaqueSecretMaterial secret) {
    }
}
