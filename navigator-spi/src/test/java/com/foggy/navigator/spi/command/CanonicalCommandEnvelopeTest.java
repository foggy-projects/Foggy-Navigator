package com.foggy.navigator.spi.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.AUTHORIZATION_METADATA_SCHEMA_VERSION;
import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.MAX_CLIENT_SURFACE_LENGTH;
import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.MAX_REFERENCE_LENGTH;
import static com.foggy.navigator.spi.command.CanonicalCommandEnvelope.SCHEMA_VERSION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalCommandEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Instant ISSUED_AT = Instant.parse("2026-08-03T01:00:00Z");

    @Test
    void freezesSevenKindsAndFiveIngressesWithRepresentativeBindingsOnly() {
        assertArrayEquals(new CanonicalCommandEnvelope.CommandKind[]{
                        CanonicalCommandEnvelope.CommandKind.CREATE,
                        CanonicalCommandEnvelope.CommandKind.TERMINATE,
                        CanonicalCommandEnvelope.CommandKind.RESUME,
                        CanonicalCommandEnvelope.CommandKind.RECONNECT,
                        CanonicalCommandEnvelope.CommandKind.RESYNC,
                        CanonicalCommandEnvelope.CommandKind.APPROVAL_RESUME,
                        CanonicalCommandEnvelope.CommandKind.RUNTIME_RECOVERY
                },
                CanonicalCommandEnvelope.CommandKind.values());
        assertArrayEquals(new CanonicalCommandEnvelope.CommandIngress[]{
                        CanonicalCommandEnvelope.CommandIngress.A2A,
                        CanonicalCommandEnvelope.CommandIngress.DIRECT,
                        CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                        CanonicalCommandEnvelope.CommandIngress.SHARED,
                        CanonicalCommandEnvelope.CommandIngress.SYSTEM_RECOVERY
                },
                CanonicalCommandEnvelope.CommandIngress.values());

        CanonicalCommandEnvelope.CommandIngress[] representativeIngresses = {
                CanonicalCommandEnvelope.CommandIngress.A2A,
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                CanonicalCommandEnvelope.CommandIngress.SHARED,
                CanonicalCommandEnvelope.CommandIngress.SYSTEM_RECOVERY,
                CanonicalCommandEnvelope.CommandIngress.A2A,
                CanonicalCommandEnvelope.CommandIngress.SYSTEM_RECOVERY
        };
        CanonicalCommandEnvelope.CommandKind[] kinds =
                CanonicalCommandEnvelope.CommandKind.values();

        for (int index = 0; index < kinds.length; index++) {
            int sample = index;
            assertDoesNotThrow(() -> envelope(binding(
                    kinds[sample],
                    representativeIngresses[sample],
                    authenticatedActor(),
                    taskTarget())));
        }
    }

    @Test
    void acceptsBothDiscriminatedActorShapes() {
        CanonicalCommandEnvelope.Actor authenticated = authenticatedActor();
        CanonicalCommandEnvelope.Actor serverProcess = new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS,
                null,
                null,
                null,
                "session-recovery-coordinator");

        assertEquals(CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                authenticated.kind());
        assertEquals(CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS, serverProcess.kind());
        assertDoesNotThrow(() -> envelope(binding(
                CanonicalCommandEnvelope.CommandKind.RUNTIME_RECOVERY,
                CanonicalCommandEnvelope.CommandIngress.SYSTEM_RECOVERY,
                serverProcess,
                taskTarget())));
    }

    @Test
    void rejectsUnknownOrConflictingActorShapes() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                AuthorizationPrincipalType.UNKNOWN,
                AuthorizationCredentialLane.NAVIGATOR_JWT,
                "fingerprint-1",
                null));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                AuthorizationPrincipalType.NAVIGATOR_USER,
                AuthorizationCredentialLane.UNKNOWN,
                "fingerprint-1",
                null));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                AuthorizationPrincipalType.NAVIGATOR_USER,
                AuthorizationCredentialLane.NAVIGATOR_JWT,
                "fingerprint-1",
                "server-authority"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS,
                AuthorizationPrincipalType.NAVIGATOR_USER,
                null,
                null,
                "server-authority"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS,
                null,
                null,
                null,
                " "));
    }

    @Test
    void supportsExistingTaskAndCompleteCreateTargetsWithoutACommandMatrix() {
        CanonicalCommandEnvelope.Target existingTask = taskTarget();
        CanonicalCommandEnvelope.Target completeCreate = new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                "agent-1",
                "agent-1",
                "codex-worker",
                "worker-1",
                "model-1",
                null,
                "session-1");

        assertEquals("task-1", existingTask.taskId());
        assertDoesNotThrow(() -> envelope(binding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                authenticatedActor(),
                completeCreate)));
        assertArrayEquals(new CanonicalCommandEnvelope.TargetKind[]{
                        CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                        CanonicalCommandEnvelope.TargetKind.TASK,
                        CanonicalCommandEnvelope.TargetKind.SESSION,
                        CanonicalCommandEnvelope.TargetKind.APPROVAL,
                        CanonicalCommandEnvelope.TargetKind.RUNTIME
                },
                CanonicalCommandEnvelope.TargetKind.values());
    }

    @Test
    void createDoesNotPrematurelyRequireProviderWorkerOrModel() {
        CanonicalCommandEnvelope.Target unresolvedCreate = new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                "agent-1",
                "agent-1",
                null,
                null,
                null,
                null,
                null);

        CanonicalCommandEnvelope envelope = envelope(binding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                authenticatedActor(),
                unresolvedCreate));

        assertNull(envelope.binding().target().providerType());
        assertNull(envelope.binding().target().physicalWorkerId());
        assertNull(envelope.binding().target().modelConfigId());
    }

    @Test
    void targetKindRequiresOnlyKnownMatchingTypedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                "agent-1", "agent-2", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.TASK,
                "task-1", null, null, null, null, "task-2", null));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.SESSION,
                "session-1", null, null, null, null, null, "session-2"));

        assertDoesNotThrow(() -> new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.APPROVAL,
                "approval-1", null, null, null, null, null, null));
        assertDoesNotThrow(() -> new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.RUNTIME,
                "runtime-1", null, null, null, null, null, null));
    }

    @Test
    void rejectsBlankControlAndOversizedReferences() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Ingress(
                CanonicalCommandEnvelope.CommandIngress.A2A, " ", "task.create"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Ingress(
                CanonicalCommandEnvelope.CommandIngress.A2A, "workers", "task\ncreate"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Ingress(
                CanonicalCommandEnvelope.CommandIngress.A2A,
                "s".repeat(MAX_CLIENT_SURFACE_LENGTH + 1),
                "task.create"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Request(
                "r".repeat(MAX_REFERENCE_LENGTH + 1), "idem-1", "corr-1"));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCommandEnvelope.Ownership(
                "tenant-1", "owner-1", "", null));
        assertDoesNotThrow(() -> new CanonicalCommandEnvelope.Ingress(
                CanonicalCommandEnvelope.CommandIngress.A2A,
                "s".repeat(MAX_CLIENT_SURFACE_LENGTH),
                "r".repeat(MAX_REFERENCE_LENGTH)));
    }

    @Test
    void enforcesExactSchemasCorrelationAndMetadataTimeOrder() {
        CanonicalCommandEnvelope.CommandBinding binding = binding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                CanonicalCommandEnvelope.CommandIngress.A2A,
                authenticatedActor(),
                taskTarget());
        CanonicalCommandEnvelope.AuthorizationMetadata metadata = metadata(binding);

        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalCommandEnvelope("navi.command-envelope.v2", binding, metadata));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalCommandEnvelope.AuthorizationMetadata(
                        "navi.command-authorization-metadata.v2",
                        "decision-1", "policy-1", binding.request().correlationId(),
                        ISSUED_AT, ISSUED_AT, ISSUED_AT.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalCommandEnvelope.AuthorizationMetadata(
                        AUTHORIZATION_METADATA_SCHEMA_VERSION,
                        "decision-1", "policy-1", binding.request().correlationId(),
                        ISSUED_AT.plusSeconds(1), ISSUED_AT, ISSUED_AT.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalCommandEnvelope.AuthorizationMetadata(
                        AUTHORIZATION_METADATA_SCHEMA_VERSION,
                        "decision-1", "policy-1", binding.request().correlationId(),
                        ISSUED_AT, ISSUED_AT, ISSUED_AT));
        CanonicalCommandEnvelope.AuthorizationMetadata wrongCorrelation =
                new CanonicalCommandEnvelope.AuthorizationMetadata(
                        AUTHORIZATION_METADATA_SCHEMA_VERSION,
                        "decision-1", "policy-1", "corr-other",
                        ISSUED_AT, ISSUED_AT, ISSUED_AT.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalCommandEnvelope(SCHEMA_VERSION, binding, wrongCorrelation));
    }

    @Test
    void explicitNullAndOmittedOptionalPropertiesHaveTheSameAbsentMeaning() throws Exception {
        CanonicalCommandEnvelope original = envelope(binding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                CanonicalCommandEnvelope.CommandIngress.DIRECT,
                authenticatedActor(),
                new CanonicalCommandEnvelope.Target(
                        CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                        "agent-1", "agent-1", null, null, null, null, null)));
        List<String> optionalTargetProperties = List.of(
                "providerType", "physicalWorkerId", "modelConfigId", "taskId", "sessionId");
        ObjectNode explicitNullTree = (ObjectNode) MAPPER.valueToTree(original);
        ObjectNode explicitNullTarget =
                (ObjectNode) explicitNullTree.path("binding").path("target");
        optionalTargetProperties.forEach(explicitNullTarget::putNull);
        optionalTargetProperties.forEach(property -> {
            assertTrue(explicitNullTarget.has(property));
            assertTrue(explicitNullTarget.get(property).isNull());
        });

        ObjectNode omittedTree = explicitNullTree.deepCopy();
        ObjectNode omittedTarget = (ObjectNode) omittedTree.path("binding").path("target");
        optionalTargetProperties.forEach(omittedTarget::remove);
        optionalTargetProperties.forEach(property -> assertFalse(omittedTarget.has(property)));

        CanonicalCommandEnvelope fromOmitted = MAPPER.treeToValue(
                omittedTree, CanonicalCommandEnvelope.class);
        CanonicalCommandEnvelope fromExplicitNull = MAPPER.treeToValue(
                explicitNullTree, CanonicalCommandEnvelope.class);

        assertEquals(original, fromOmitted);
        assertEquals(original, fromExplicitNull);
    }

    @Test
    void serializesAndRoundTripsOnlyTheRecursivePropertyAllowlist() throws Exception {
        CanonicalCommandEnvelope authenticatedEnvelope = envelope(binding(
                CanonicalCommandEnvelope.CommandKind.CREATE,
                CanonicalCommandEnvelope.CommandIngress.OPENAPI,
                authenticatedActor(),
                new CanonicalCommandEnvelope.Target(
                        CanonicalCommandEnvelope.TargetKind.LOGICAL_AGENT,
                        "agent-1", "agent-1", "codex-worker", "worker-1", "model-1",
                        null, "session-1")));
        CanonicalCommandEnvelope serverEnvelope = envelope(binding(
                CanonicalCommandEnvelope.CommandKind.RUNTIME_RECOVERY,
                CanonicalCommandEnvelope.CommandIngress.SYSTEM_RECOVERY,
                new CanonicalCommandEnvelope.Actor(
                        CanonicalCommandEnvelope.ActorKind.SERVER_PROCESS,
                        null, null, null, "recovery-authority"),
                taskTarget()));

        Set<String> propertyNames = new HashSet<>();
        for (CanonicalCommandEnvelope value : List.of(authenticatedEnvelope, serverEnvelope)) {
            byte[] json = MAPPER.writeValueAsBytes(value);
            assertEquals(value, MAPPER.readValue(json, CanonicalCommandEnvelope.class));
            collectPropertyNames(MAPPER.readTree(json), propertyNames);
        }

        assertEquals(allowedJsonProperties(), propertyNames);
        for (String propertyName : propertyNames) {
            String normalized = propertyName.toLowerCase();
            assertFalse(normalized.matches(
                    ".*(credential|token|secret|prompt|body|content|message|attachment|file|path|url|env|header|raw|params|businesscontext|allowed|outcome|issuer|seal|verifier).*"),
                    () -> "unsafe JSON property: " + propertyName);
        }
    }

    @Test
    void valueGraphIsDeeplyImmutableAndHasNoFreeFormCarrierType() {
        List<Class<?>> recordTypes = List.of(
                CanonicalCommandEnvelope.class,
                CanonicalCommandEnvelope.CommandBinding.class,
                CanonicalCommandEnvelope.Ingress.class,
                CanonicalCommandEnvelope.Request.class,
                CanonicalCommandEnvelope.Actor.class,
                CanonicalCommandEnvelope.Ownership.class,
                CanonicalCommandEnvelope.Target.class,
                CanonicalCommandEnvelope.Effect.class,
                CanonicalCommandEnvelope.AuthorizationMetadata.class);

        for (Class<?> type : recordTypes) {
            assertTrue(type.isRecord(), () -> type.getName() + " must remain a record");
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    assertTrue(Modifier.isFinal(field.getModifiers()),
                            () -> type.getSimpleName() + "." + field.getName() + " must be final");
                }
            }
            Arrays.stream(type.getRecordComponents()).forEach(component -> {
                Class<?> componentType = component.getType();
                assertFalse(componentType.isArray(),
                        () -> component.getName() + " must not be an array carrier");
                assertFalse(Map.class.isAssignableFrom(componentType),
                        () -> component.getName() + " must not be a Map carrier");
                assertFalse(Collection.class.isAssignableFrom(componentType),
                        () -> component.getName() + " must not be a Collection carrier");
                assertFalse(Object.class.equals(componentType),
                        () -> component.getName() + " must not be an Object carrier");
                assertFalse(JsonNode.class.isAssignableFrom(componentType),
                        () -> component.getName() + " must not be a JsonNode carrier");
            });
        }
    }

    private static CanonicalCommandEnvelope envelope(
            CanonicalCommandEnvelope.CommandBinding binding) {
        return new CanonicalCommandEnvelope(SCHEMA_VERSION, binding, metadata(binding));
    }

    private static CanonicalCommandEnvelope.AuthorizationMetadata metadata(
            CanonicalCommandEnvelope.CommandBinding binding) {
        return new CanonicalCommandEnvelope.AuthorizationMetadata(
                AUTHORIZATION_METADATA_SCHEMA_VERSION,
                "decision-1",
                "policy-1",
                binding.request().correlationId(),
                ISSUED_AT,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60));
    }

    private static CanonicalCommandEnvelope.CommandBinding binding(
            CanonicalCommandEnvelope.CommandKind kind,
            CanonicalCommandEnvelope.CommandIngress ingress,
            CanonicalCommandEnvelope.Actor actor,
            CanonicalCommandEnvelope.Target target) {
        String suffix = kind.name().toLowerCase();
        return new CanonicalCommandEnvelope.CommandBinding(
                kind,
                new CanonicalCommandEnvelope.Ingress(ingress, "workers", "task." + suffix),
                new CanonicalCommandEnvelope.Request(
                        "request-" + suffix, "idempotency-" + suffix, "correlation-" + suffix),
                actor,
                new CanonicalCommandEnvelope.Ownership(
                        "tenant-1", "owner-1", "client-app-1", "upstream-1"),
                target,
                new CanonicalCommandEnvelope.Effect("task." + suffix, "tenant-1"));
    }

    private static CanonicalCommandEnvelope.Actor authenticatedActor() {
        return new CanonicalCommandEnvelope.Actor(
                CanonicalCommandEnvelope.ActorKind.AUTHENTICATED_PRINCIPAL,
                AuthorizationPrincipalType.NAVIGATOR_USER,
                AuthorizationCredentialLane.NAVIGATOR_JWT,
                "sha256:fingerprint-1",
                null);
    }

    private static CanonicalCommandEnvelope.Target taskTarget() {
        return new CanonicalCommandEnvelope.Target(
                CanonicalCommandEnvelope.TargetKind.TASK,
                "task-1",
                "agent-1",
                "codex-worker",
                "worker-1",
                "model-1",
                "task-1",
                "session-1");
    }

    private static void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                collectPropertyNames(entry.getValue(), names);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectPropertyNames(child, names));
        }
    }

    private static Set<String> allowedJsonProperties() {
        return Set.of(
                "schemaVersion", "binding", "authorizationMetadata",
                "commandKind", "ingress", "request", "actor", "ownership", "target", "effect",
                "clientSurface", "routeId",
                "clientRequestId", "idempotencyKey", "correlationId",
                "kind", "principalType", "lane", "fingerprint",
                "serverProcessAuthorityReference",
                "tenantReference", "ownerReference", "clientAppReference", "upstreamReference",
                "targetId", "logicalAgentId", "providerType", "physicalWorkerId", "modelConfigId",
                "taskId", "sessionId",
                "actionId", "effectScopeReference",
                "decisionId", "policyVersion", "issuedAt", "notBefore", "expiresAt");
    }
}
