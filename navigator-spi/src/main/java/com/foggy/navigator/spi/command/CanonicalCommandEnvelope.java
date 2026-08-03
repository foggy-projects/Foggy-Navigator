package com.foggy.navigator.spi.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.foggy.navigator.common.authorization.AuthorizationCredentialLane;
import com.foggy.navigator.common.authorization.AuthorizationPrincipalType;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, content-free identity binding for a Navigator command.
 *
 * <p>This value deliberately carries no business payload or execution capability. A serialized
 * envelope is safe transport/audit metadata only and must still be paired with the process-local
 * capability issued by {@link VerifiedCommandAuthorizationDecision.ServerAuthority}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalCommandEnvelope(
        String schemaVersion,
        CommandBinding binding,
        AuthorizationMetadata authorizationMetadata) {

    public static final String SCHEMA_VERSION = "navi.command-envelope.v1";
    public static final String AUTHORIZATION_METADATA_SCHEMA_VERSION =
            "navi.command-authorization-metadata.v1";
    public static final int MAX_REFERENCE_LENGTH = 256;
    public static final int MAX_CLIENT_SURFACE_LENGTH = 128;

    public CanonicalCommandEnvelope {
        requireExactSchema(schemaVersion, SCHEMA_VERSION, "schemaVersion");
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(authorizationMetadata, "authorizationMetadata must not be null");
        if (!binding.request().correlationId().equals(authorizationMetadata.correlationId())) {
            throw new IllegalArgumentException(
                    "authorizationMetadata correlationId must match binding request correlationId");
        }
    }

    public enum CommandKind {
        CREATE,
        TERMINATE,
        RESUME,
        RECONNECT,
        RESYNC,
        APPROVAL_RESUME,
        RUNTIME_RECOVERY
    }

    public enum CommandIngress {
        A2A,
        DIRECT,
        OPENAPI,
        SHARED,
        SYSTEM_RECOVERY
    }

    public enum ActorKind {
        AUTHENTICATED_PRINCIPAL,
        SERVER_PROCESS
    }

    public enum TargetKind {
        LOGICAL_AGENT,
        TASK,
        SESSION,
        APPROVAL,
        RUNTIME
    }

    public record CommandBinding(
            CommandKind commandKind,
            Ingress ingress,
            Request request,
            Actor actor,
            Ownership ownership,
            Target target,
            Effect effect) {

        public CommandBinding {
            Objects.requireNonNull(commandKind, "commandKind must not be null");
            Objects.requireNonNull(ingress, "ingress must not be null");
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(ownership, "ownership must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(effect, "effect must not be null");
        }
    }

    public record Ingress(
            CommandIngress ingress,
            String clientSurface,
            String routeId) {

        public Ingress {
            Objects.requireNonNull(ingress, "ingress must not be null");
            requireReference(clientSurface, MAX_CLIENT_SURFACE_LENGTH, "clientSurface");
            requireReference(routeId, MAX_REFERENCE_LENGTH, "routeId");
        }
    }

    public record Request(
            String clientRequestId,
            String idempotencyKey,
            String correlationId) {

        public Request {
            requireReference(clientRequestId, MAX_REFERENCE_LENGTH, "clientRequestId");
            requireReference(idempotencyKey, MAX_REFERENCE_LENGTH, "idempotencyKey");
            requireReference(correlationId, MAX_REFERENCE_LENGTH, "correlationId");
        }
    }

    public record Actor(
            ActorKind kind,
            AuthorizationPrincipalType principalType,
            AuthorizationCredentialLane lane,
            String fingerprint,
            String serverProcessAuthorityReference) {

        public Actor {
            Objects.requireNonNull(kind, "kind must not be null");
            if (kind == ActorKind.AUTHENTICATED_PRINCIPAL) {
                if (principalType == null || principalType == AuthorizationPrincipalType.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "authenticated actor requires a non-UNKNOWN principalType");
                }
                if (lane == null || lane == AuthorizationCredentialLane.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "authenticated actor requires a non-UNKNOWN lane");
                }
                requireReference(fingerprint, MAX_REFERENCE_LENGTH, "fingerprint");
                requireAbsent(serverProcessAuthorityReference,
                        "authenticated actor serverProcessAuthorityReference");
            } else {
                requireAbsent(principalType, "server-process actor principalType");
                requireAbsent(lane, "server-process actor lane");
                requireAbsent(fingerprint, "server-process actor fingerprint");
                requireReference(serverProcessAuthorityReference, MAX_REFERENCE_LENGTH,
                        "serverProcessAuthorityReference");
            }
        }
    }

    public record Ownership(
            String tenantReference,
            String ownerReference,
            String clientAppReference,
            String upstreamReference) {

        public Ownership {
            requireReference(tenantReference, MAX_REFERENCE_LENGTH, "tenantReference");
            requireReference(ownerReference, MAX_REFERENCE_LENGTH, "ownerReference");
            requireOptionalReference(clientAppReference, MAX_REFERENCE_LENGTH, "clientAppReference");
            requireOptionalReference(upstreamReference, MAX_REFERENCE_LENGTH, "upstreamReference");
        }
    }

    public record Target(
            TargetKind kind,
            String targetId,
            String logicalAgentId,
            String providerType,
            String physicalWorkerId,
            String modelConfigId,
            String taskId,
            String sessionId) {

        public Target {
            Objects.requireNonNull(kind, "kind must not be null");
            requireReference(targetId, MAX_REFERENCE_LENGTH, "targetId");
            requireOptionalReference(logicalAgentId, MAX_REFERENCE_LENGTH, "logicalAgentId");
            requireOptionalReference(providerType, MAX_REFERENCE_LENGTH, "providerType");
            requireOptionalReference(physicalWorkerId, MAX_REFERENCE_LENGTH, "physicalWorkerId");
            requireOptionalReference(modelConfigId, MAX_REFERENCE_LENGTH, "modelConfigId");
            requireOptionalReference(taskId, MAX_REFERENCE_LENGTH, "taskId");
            requireOptionalReference(sessionId, MAX_REFERENCE_LENGTH, "sessionId");
            requireMatchingTarget(kind, TargetKind.LOGICAL_AGENT, targetId, logicalAgentId,
                    "logicalAgentId");
            requireMatchingTarget(kind, TargetKind.TASK, targetId, taskId, "taskId");
            requireMatchingTarget(kind, TargetKind.SESSION, targetId, sessionId, "sessionId");
        }
    }

    public record Effect(
            String actionId,
            String effectScopeReference) {

        public Effect {
            requireReference(actionId, MAX_REFERENCE_LENGTH, "actionId");
            requireReference(effectScopeReference, MAX_REFERENCE_LENGTH, "effectScopeReference");
        }
    }

    /** Safe, serializable evidence. It is not an execution authorization by itself. */
    public record AuthorizationMetadata(
            String schemaVersion,
            String decisionId,
            String policyVersion,
            String correlationId,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        public AuthorizationMetadata {
            requireExactSchema(schemaVersion, AUTHORIZATION_METADATA_SCHEMA_VERSION,
                    "authorizationMetadata.schemaVersion");
            requireReference(decisionId, MAX_REFERENCE_LENGTH, "decisionId");
            requireReference(policyVersion, MAX_REFERENCE_LENGTH, "policyVersion");
            requireReference(correlationId, MAX_REFERENCE_LENGTH, "correlationId");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            Objects.requireNonNull(notBefore, "notBefore must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            if (issuedAt.isAfter(notBefore)) {
                throw new IllegalArgumentException("issuedAt must not be after notBefore");
            }
            if (!notBefore.isBefore(expiresAt)) {
                throw new IllegalArgumentException("notBefore must be before expiresAt");
            }
        }
    }

    private static void requireExactSchema(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    private static void requireReference(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
    }

    private static void requireOptionalReference(String value, int maxLength, String field) {
        if (value != null) {
            requireReference(value, maxLength, field);
        }
    }

    private static void requireAbsent(Object value, String field) {
        if (value != null) {
            throw new IllegalArgumentException(field + " must be absent");
        }
    }

    private static void requireMatchingTarget(TargetKind actualKind,
                                              TargetKind matchingKind,
                                              String targetId,
                                              String typedId,
                                              String field) {
        if (actualKind == matchingKind && typedId != null && !targetId.equals(typedId)) {
            throw new IllegalArgumentException(field + " must equal targetId for " + matchingKind);
        }
    }
}
