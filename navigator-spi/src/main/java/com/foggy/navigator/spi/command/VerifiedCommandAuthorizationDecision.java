package com.foggy.navigator.spi.command;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.AUTHORIZATION_METADATA_SCHEMA_VERSION;

/**
 * Process-local capability proving that a server authority minted an authorization for one exact
 * command binding.
 *
 * <p>Serialization intentionally exposes only safe metadata. Deserializing that metadata cannot
 * recreate this capability. This is an in-process object-capability boundary, not a JVM sandbox.</p>
 */
@JsonAutoDetect(
        creatorVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE)
public final class VerifiedCommandAuthorizationDecision {

    private final CanonicalCommandEnvelope.CommandBinding binding;
    private final CanonicalCommandEnvelope.AuthorizationMetadata metadata;
    private final Object authoritySeal;

    private VerifiedCommandAuthorizationDecision(
            CanonicalCommandEnvelope.CommandBinding binding,
            CanonicalCommandEnvelope.AuthorizationMetadata metadata,
            Object authoritySeal) {
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.authoritySeal = Objects.requireNonNull(authoritySeal, "authoritySeal must not be null");
    }

    /** Returns safe audit/transport metadata, never the process-local authority seal. */
    @JsonValue
    public CanonicalCommandEnvelope.AuthorizationMetadata metadata() {
        return metadata;
    }

    /**
     * Server-owned mint and verifier. Each instance has a distinct, unselectable identity seal.
     */
    public static final class ServerAuthority {

        private final String policyVersion;
        private final Clock clock;
        private final Duration validity;
        private final Object seal = new Object();

        public ServerAuthority(String policyVersion, Clock clock, Duration validity) {
            this.policyVersion = requirePolicyVersion(policyVersion);
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            this.validity = requirePositive(validity);
        }

        public VerifiedCommandAuthorizationDecision issue(
                CanonicalCommandEnvelope.CommandBinding binding) {
            Objects.requireNonNull(binding, "binding must not be null");
            Instant issuedAt = clock.instant();
            CanonicalCommandEnvelope.AuthorizationMetadata metadata =
                    new CanonicalCommandEnvelope.AuthorizationMetadata(
                            AUTHORIZATION_METADATA_SCHEMA_VERSION,
                            UUID.randomUUID().toString(),
                            policyVersion,
                            binding.request().correlationId(),
                            issuedAt,
                            issuedAt,
                            issuedAt.plus(validity));
            return new VerifiedCommandAuthorizationDecision(binding, metadata, seal);
        }

        /**
         * Verifies authority identity, exact binding/metadata, correlation and current validity.
         * On success the returned value is the binding hidden inside the minted capability.
         */
        public CanonicalCommandEnvelope.CommandBinding requireVerified(
                CanonicalCommandEnvelope envelope,
                VerifiedCommandAuthorizationDecision decision) {
            if (envelope == null || decision == null) {
                throw rejected("envelope and decision are required");
            }
            if (decision.authoritySeal != seal) {
                throw rejected("decision was not issued by this server authority");
            }
            if (!CanonicalCommandEnvelope.SCHEMA_VERSION.equals(envelope.schemaVersion())
                    || !AUTHORIZATION_METADATA_SCHEMA_VERSION.equals(
                    envelope.authorizationMetadata().schemaVersion())) {
                throw rejected("command or authorization metadata schema is unsupported");
            }
            if (!decision.binding.equals(envelope.binding())) {
                throw rejected("command binding does not match issued authorization");
            }
            if (!decision.metadata.equals(envelope.authorizationMetadata())) {
                throw rejected("authorization metadata does not match issued authorization");
            }
            if (!decision.metadata.correlationId()
                    .equals(decision.binding.request().correlationId())) {
                throw rejected("authorization correlation does not match command binding");
            }
            Instant now = clock.instant();
            if (now.isBefore(decision.metadata.notBefore())) {
                throw rejected("authorization is not yet valid");
            }
            if (!now.isBefore(decision.metadata.expiresAt())) {
                throw rejected("authorization has expired");
            }
            return decision.binding;
        }

        private static String requirePolicyVersion(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("policyVersion must not be blank");
            }
            if (value.length() > CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH) {
                throw new IllegalArgumentException("policyVersion exceeds maximum length");
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("policyVersion must not contain control characters");
            }
            return value;
        }

        private static Duration requirePositive(Duration value) {
            Objects.requireNonNull(value, "validity must not be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("validity must be positive");
            }
            return value;
        }

        private static SecurityException rejected(String reason) {
            return new SecurityException("unverified command authorization: " + reason);
        }
    }
}
