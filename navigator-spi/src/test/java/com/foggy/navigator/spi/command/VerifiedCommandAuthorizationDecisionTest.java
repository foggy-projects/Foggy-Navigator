package com.foggy.navigator.spi.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationDecisionOutcome;
import com.foggy.navigator.common.authorization.AuthorizationEvaluationMode;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.PolicyDecisionV1;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.AUTHORIZATION_METADATA_SCHEMA_VERSION;
import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.SCHEMA_VERSION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedCommandAuthorizationDecisionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant BASE_TIME = Instant.parse("2026-08-03T02:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(5);

    @Test
    void canonicalAuthorityIssuesAndReturnsItsHiddenExactBinding() {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority authority = authority(clock);
        CanonicalCommandEnvelope.CommandBinding binding = authenticatedBinding();

        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        CanonicalCommandEnvelope envelope = envelope(binding, decision.metadata());

        assertSame(binding, authority.requireVerified(envelope, decision));
        assertEquals(AUTHORIZATION_METADATA_SCHEMA_VERSION, decision.metadata().schemaVersion());
        assertEquals("stage4-policy-v1", decision.metadata().policyVersion());
        assertEquals(binding.request().correlationId(), decision.metadata().correlationId());
        assertEquals(BASE_TIME, decision.metadata().issuedAt());
        assertEquals(BASE_TIME, decision.metadata().notBefore());
        assertEquals(BASE_TIME.plus(VALIDITY), decision.metadata().expiresAt());
        assertNotNull(UUID.fromString(decision.metadata().decisionId()));
        assertArrayEquals(new String[]{
                        "schemaVersion", "decisionId", "policyVersion", "correlationId",
                        "issuedAt", "notBefore", "expiresAt"
                },
                Arrays.stream(decision.metadata().getClass().getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
    }

    @Test
    void differentAuthorityRejectsAndServerReferenceCannotSelectAVerifier() {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority canonical = authority(clock);
        VerifiedCommandAuthorizationDecision.ServerAuthority other = authority(clock);
        CanonicalCommandEnvelope.CommandBinding binding = serverProcessBinding(
                "text-that-names-the-other-authority");
        VerifiedCommandAuthorizationDecision decision = canonical.issue(binding);
        CanonicalCommandEnvelope envelope = envelope(binding, decision.metadata());

        assertSame(binding, canonical.requireVerified(envelope, decision));
        assertThrows(SecurityException.class, () -> other.requireVerified(envelope, decision));
    }

    @Test
    void bindingActorAndTargetDriftAllFailClosed() {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority authority = authority(clock);
        CanonicalCommandEnvelope.CommandBinding binding = authenticatedBinding();
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);

        CanonicalCommandEnvelope.CommandBinding kindDrift = replaceBinding(
                binding,
                CanonicalCommandEnvelope.CommandKind.TERMINATE,
                binding.actor(),
                binding.target(),
                binding.request());
        CanonicalCommandEnvelope.Actor actorDrift = new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                AuthorizationPrincipalType.NAVIGATOR_USER,
                AuthorizationCredentialLane.NAVIGATOR_JWT,
                "sha256:other-fingerprint",
                null);
        CanonicalCommandEnvelope.CommandBinding principalDrift = replaceBinding(
                binding, binding.commandKind(), actorDrift, binding.target(), binding.request());
        CanonicalCommandEnvelope.Target changedTarget = new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.TASK,
                "task-2", "agent-1", "codex-worker", "worker-1", "model-1",
                "task-2", "session-1");
        CanonicalCommandEnvelope.CommandBinding targetDrift = replaceBinding(
                binding, binding.commandKind(), binding.actor(), changedTarget, binding.request());

        assertThrows(SecurityException.class,
                () -> authority.requireVerified(envelope(kindDrift, decision.metadata()), decision));
        assertThrows(SecurityException.class,
                () -> authority.requireVerified(envelope(principalDrift, decision.metadata()), decision));
        assertThrows(SecurityException.class,
                () -> authority.requireVerified(envelope(targetDrift, decision.metadata()), decision));
    }

    @Test
    void correlationAndSafeMetadataDriftIncludingAllowLikePrefixesFailClosed() {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority authority = authority(clock);
        CanonicalCommandEnvelope.CommandBinding binding = authenticatedBinding();
        VerifiedCommandAuthorizationDecision decision = authority.issue(binding);
        CanonicalCommandEnvelope.AuthorizationMetadata issued = decision.metadata();

        CanonicalCommandEnvelope.Request changedRequest = new CanonicalCommandEnvelope.Request(
                binding.request().clientRequestId(),
                binding.request().idempotencyKey(),
                "correlation-other");
        CanonicalCommandEnvelope.CommandBinding correlationDrift = replaceBinding(
                binding, binding.commandKind(), binding.actor(), binding.target(), changedRequest);
        CanonicalCommandEnvelope.AuthorizationMetadata correlationMetadata = copyMetadata(
                issued, issued.decisionId(), issued.policyVersion(), "correlation-other",
                issued.issuedAt(), issued.notBefore(), issued.expiresAt());
        CanonicalCommandEnvelope.AuthorizationMetadata prefixedDecisionId = copyMetadata(
                issued, "ALLOW:" + issued.decisionId(), issued.policyVersion(), issued.correlationId(),
                issued.issuedAt(), issued.notBefore(), issued.expiresAt());
        CanonicalCommandEnvelope.AuthorizationMetadata policyDrift = copyMetadata(
                issued, issued.decisionId(), "stage4-policy-v2", issued.correlationId(),
                issued.issuedAt(), issued.notBefore(), issued.expiresAt());

        assertThrows(SecurityException.class, () -> authority.requireVerified(
                envelope(correlationDrift, correlationMetadata), decision));
        assertThrows(SecurityException.class, () -> authority.requireVerified(
                envelope(binding, prefixedDecisionId), decision));
        assertThrows(SecurityException.class, () -> authority.requireVerified(
                envelope(binding, policyDrift), decision));
    }

    @Test
    void notYetValidAndExpiredCapabilitiesFailAtTheAuthorityClock() {
        MutableClock notYetClock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority notYetAuthority = authority(notYetClock);
        CanonicalCommandEnvelope.CommandBinding binding = authenticatedBinding();
        VerifiedCommandAuthorizationDecision notYetDecision = notYetAuthority.issue(binding);
        CanonicalCommandEnvelope notYetEnvelope = envelope(binding, notYetDecision.metadata());
        notYetClock.set(BASE_TIME.minusNanos(1));

        assertThrows(SecurityException.class,
                () -> notYetAuthority.requireVerified(notYetEnvelope, notYetDecision));

        MutableClock expiredClock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority expiredAuthority = authority(expiredClock);
        VerifiedCommandAuthorizationDecision expiredDecision = expiredAuthority.issue(binding);
        CanonicalCommandEnvelope expiredEnvelope = envelope(binding, expiredDecision.metadata());
        expiredClock.set(BASE_TIME.plus(VALIDITY));

        assertThrows(SecurityException.class,
                () -> expiredAuthority.requireVerified(expiredEnvelope, expiredDecision));
    }

    @Test
    void authorityRequiresAPositiveValidityAndBoundedPolicyVersion() {
        MutableClock clock = new MutableClock(BASE_TIME);

        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        " ", clock, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "policy-1", clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifiedCommandAuthorizationDecision.ServerAuthority(
                        "p".repeat(CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH + 1),
                        clock,
                        VALIDITY));
    }

    @Test
    void reflectiveReconstructionWithANewSealHasNoAuthority() throws Exception {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision.ServerAuthority authority = authority(clock);
        CanonicalCommandEnvelope.CommandBinding binding = authenticatedBinding();
        VerifiedCommandAuthorizationDecision genuine = authority.issue(binding);
        Constructor<VerifiedCommandAuthorizationDecision> constructor =
                VerifiedCommandAuthorizationDecision.class.getDeclaredConstructor(
                        CanonicalCommandEnvelope.CommandBinding.class,
                        CanonicalCommandEnvelope.AuthorizationMetadata.class,
                        Object.class);
        constructor.setAccessible(true);
        VerifiedCommandAuthorizationDecision reconstructed = constructor.newInstance(
                binding, genuine.metadata(), new Object());

        assertThrows(SecurityException.class, () -> authority.requireVerified(
                envelope(binding, genuine.metadata()), reconstructed));
    }

    @Test
    void serializationExposesOnlyMetadataAndCannotRecreateTheCapability() throws Exception {
        MutableClock clock = new MutableClock(BASE_TIME);
        VerifiedCommandAuthorizationDecision decision = authority(clock).issue(authenticatedBinding());

        byte[] serialized = MAPPER.writeValueAsBytes(decision);
        JsonNode tree = MAPPER.readTree(serialized);

        assertEquals(decision.metadata(), MAPPER.treeToValue(
                tree, CanonicalCommandEnvelope.AuthorizationMetadata.class));
        assertFalse(tree.has("binding"));
        assertFalse(tree.has("authoritySeal"));
        assertFalse(tree.has("allowed"));
        assertFalse(tree.has("outcome"));
        assertFalse(tree.has("issuer"));
        assertThrows(JsonProcessingException.class,
                () -> MAPPER.readValue(serialized, VerifiedCommandAuthorizationDecision.class));
    }

    @Test
    void shadowPolicyBooleanAndLegacyDecisionShapesHaveNoConversionPath() throws Exception {
        PolicyDecisionV1 shadow = new PolicyDecisionV1(
                "navi.authorization.v1",
                "shadow-policy-v1",
                "catalog-v1",
                "test-build",
                "ALLOW:shadow-decision",
                "correlation-create",
                AuthorizationEvaluationMode.ENFORCEMENT,
                AuthorizationDecisionOutcome.ALLOW,
                AuthorizationReasonCode.AUTHZ_POLICY_SHADOW_ALLOW,
                true,
                "task.create",
                "task.create",
                BASE_TIME);

        assertThrows(IllegalArgumentException.class,
                () -> MAPPER.convertValue(shadow, VerifiedCommandAuthorizationDecision.class));
        assertThrows(IllegalArgumentException.class,
                () -> MAPPER.convertValue(Boolean.TRUE,
                        VerifiedCommandAuthorizationDecision.class));
        assertThrows(JsonProcessingException.class, () -> MAPPER.readValue(
                "{\"allowed\":true,\"decisionId\":\"ALLOW:shadow-decision\","
                        + "\"correlationId\":\"correlation-create\"}",
                VerifiedCommandAuthorizationDecision.class));

        assertFalse(Arrays.stream(VerifiedCommandAuthorizationDecision.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(PolicyDecisionV1.class::equals));
    }

    @Test
    void publicCapabilitySurfaceHasMetadataOnlyAndNoBooleanOrIdentifierFactory() {
        Set<String> publicDeclaredMethods =
                Arrays.stream(VerifiedCommandAuthorizationDecision.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(Method::getName)
                        .collect(Collectors.toSet());

        assertEquals(Set.of("metadata"), publicDeclaredMethods);
        assertEquals(0, VerifiedCommandAuthorizationDecision.class.getConstructors().length);
        assertFalse(Arrays.stream(VerifiedCommandAuthorizationDecision.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .anyMatch(method -> method.getReturnType() == boolean.class
                        || method.getReturnType() == Boolean.class
                        || method.getName().toLowerCase().contains("allow")
                        || method.getName().toLowerCase().contains("frompolicy")
                        || method.getName().toLowerCase().contains("fromid")));
        assertTrue(Arrays.stream(VerifiedCommandAuthorizationDecision.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    private static VerifiedCommandAuthorizationDecision.ServerAuthority authority(Clock clock) {
        return new VerifiedCommandAuthorizationDecision.ServerAuthority(
                "stage4-policy-v1", clock, VALIDITY);
    }

    private static CanonicalCommandEnvelope envelope(
            CanonicalCommandEnvelope.CommandBinding binding,
            CanonicalCommandEnvelope.AuthorizationMetadata metadata) {
        return new CanonicalCommandEnvelope(SCHEMA_VERSION, binding, metadata);
    }

    private static CanonicalCommandEnvelope.AuthorizationMetadata copyMetadata(
            CanonicalCommandEnvelope.AuthorizationMetadata source,
            String decisionId,
            String policyVersion,
            String correlationId,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        return new CanonicalCommandEnvelope.AuthorizationMetadata(
                source.schemaVersion(),
                decisionId,
                policyVersion,
                correlationId,
                issuedAt,
                notBefore,
                expiresAt);
    }

    private static CanonicalCommandEnvelope.CommandBinding replaceBinding(
            CanonicalCommandEnvelope.CommandBinding source,
            CanonicalCommandEnvelope.CommandKind kind,
            CanonicalCommandEnvelope.Actor actor,
            CanonicalCommandEnvelope.Target target,
            CanonicalCommandEnvelope.Request request) {
        return new CanonicalCommandEnvelope.CommandBinding(
                kind,
                source.ingress(),
                request,
                actor,
                source.ownership(),
                target,
                source.effect());
    }

    private static CanonicalCommandEnvelope.CommandBinding authenticatedBinding() {
        return new CanonicalCommandEnvelope.CommandBinding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                new CanonicalCommandEnvelope.Ingress(
                        CanonicalCommandEnvelope.CommandIngress.A2A,
                        "workers",
                        "task.create"),
                new CanonicalCommandEnvelope.Request(
                        "request-create", "idempotency-create", "correlation-create"),
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                        AuthorizationPrincipalType.NAVIGATOR_USER,
                        AuthorizationCredentialLane.NAVIGATOR_JWT,
                        "sha256:fingerprint-1",
                        null),
                new CanonicalCommandEnvelope.Ownership(
                        "tenant-1", "owner-1", "client-app-1", null),
                new CanonicalCommandEnvelope.Target(
                        CanonicalCommandEnvelope.TargetKind.TASK,
                        "task-1", "agent-1", "codex-worker", "worker-1", "model-1",
                        "task-1", "session-1"),
                new CanonicalCommandEnvelope.Effect("task.create", "tenant-1"));
    }

    private static CanonicalCommandEnvelope.CommandBinding serverProcessBinding(
            String serverAuthorityReference) {
        CanonicalCommandEnvelope.CommandBinding source = authenticatedBinding();
        return replaceBinding(
                source,
                CanonicalCommandEnvelope.CommandKind.RUNTIME_RECOVERY,
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS,
                        null,
                        null,
                        null,
                        serverAuthorityReference),
                source.target(),
                new CanonicalCommandEnvelope.Request(
                        "request-recovery", "idempotency-recovery", "correlation-recovery"));
    }

    private static final class MutableClock extends Clock {

        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current) {
            this(current, ZoneOffset.UTC);
        }

        private MutableClock(Instant current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        private void set(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
