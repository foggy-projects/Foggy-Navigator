package com.foggy.navigator.spi.lifecycle;

import java.util.List;

/**
 * Participates in the public termination-receipt transaction. Implementations
 * may persist owner intent/outbox binding but must not call a provider.
 */
public interface RuntimeTerminationIntentPort {
    RuntimeTerminationDelivery recordIntent(
            RuntimeTerminationIntent intent);

    RuntimeTerminationDelivery find(String clientRequestId);

    default List<RuntimeTerminationDelivery> findPrepared(int limit) {
        return List.of();
    }

    RuntimeTerminationAuthorization authorizeEffect(String clientRequestId);

    void resultObserved(String clientRequestId, String safeResultCode);

    record RuntimeTerminationIntent(
            String clientRequestId,
            String taskId,
            String sessionId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String dispatchId,
            String operationId,
            String ownershipMode,
            String stateGeneration,
            String instanceEpoch,
            String bindingDigestVersion,
            String bindingDigest,
            String ownerUserId,
            String tenantId,
            String authorizationBindingClaim) {
        public static final String LEGACY_AUTHORIZATION_BINDING_CLAIM =
                "TERMINATION_PROVIDER_CALL";

        public RuntimeTerminationIntent(
                String clientRequestId,
                String taskId,
                String sessionId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String dispatchId,
                String operationId,
                String ownershipMode,
                String stateGeneration,
                String instanceEpoch,
                String bindingDigestVersion,
                String bindingDigest,
                String ownerUserId,
                String tenantId) {
            this(clientRequestId, taskId, sessionId, providerType,
                    physicalWorkerId, providerTaskId, dispatchId,
                    operationId, ownershipMode, stateGeneration,
                    instanceEpoch, bindingDigestVersion, bindingDigest,
                    ownerUserId, tenantId,
                    LEGACY_AUTHORIZATION_BINDING_CLAIM);
        }

        public RuntimeTerminationIntent(
                String clientRequestId,
                String taskId,
                String sessionId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String dispatchId,
                String operationId,
                String ownershipMode,
                String stateGeneration,
                String instanceEpoch,
                String bindingDigestVersion,
                String bindingDigest) {
            this(clientRequestId, taskId, sessionId, providerType,
                    physicalWorkerId, providerTaskId, dispatchId,
                    operationId, ownershipMode, stateGeneration,
                    instanceEpoch, bindingDigestVersion, bindingDigest,
                    null, null, LEGACY_AUTHORIZATION_BINDING_CLAIM);
        }

        public RuntimeTerminationIntent(
                String clientRequestId,
                String taskId,
                String sessionId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String operationId,
                String bindingDigest) {
            this(clientRequestId, taskId, sessionId, providerType,
                    physicalWorkerId, providerTaskId, operationId,
                    operationId, "ENFORCED", null, null,
                    "JCS_SHA256_V1", bindingDigest, null, null,
                    LEGACY_AUTHORIZATION_BINDING_CLAIM);
        }
    }

    record RuntimeTerminationDelivery(
            String effectId,
            String clientRequestId,
            String taskId,
            String providerType,
            String physicalWorkerId,
            String providerTaskId,
            String operationId,
            String ownershipMode,
            String stateGeneration,
            String instanceEpoch,
            String bindingDigestVersion,
            String bindingDigest,
            String effectState,
            String ownerUserId,
            String tenantId,
            String authorizationBindingClaim) {
        public RuntimeTerminationDelivery(
                String effectId,
                String clientRequestId,
                String taskId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String operationId,
                String ownershipMode,
                String stateGeneration,
                String instanceEpoch,
                String bindingDigestVersion,
                String bindingDigest,
                String effectState,
                String ownerUserId,
                String tenantId) {
            this(effectId, clientRequestId, taskId, providerType,
                    physicalWorkerId, providerTaskId, operationId,
                    ownershipMode, stateGeneration, instanceEpoch,
                    bindingDigestVersion, bindingDigest, effectState,
                    ownerUserId, tenantId,
                    RuntimeTerminationIntent.LEGACY_AUTHORIZATION_BINDING_CLAIM);
        }

        public RuntimeTerminationDelivery(
                String effectId,
                String clientRequestId,
                String taskId,
                String providerType,
                String physicalWorkerId,
                String providerTaskId,
                String operationId,
                String ownershipMode,
                String stateGeneration,
                String instanceEpoch,
                String bindingDigestVersion,
                String bindingDigest,
                String effectState) {
            this(effectId, clientRequestId, taskId, providerType,
                    physicalWorkerId, providerTaskId, operationId,
                    ownershipMode, stateGeneration, instanceEpoch,
                    bindingDigestVersion, bindingDigest, effectState,
                    null, null,
                    RuntimeTerminationIntent.LEGACY_AUTHORIZATION_BINDING_CLAIM);
        }
    }

    record RuntimeTerminationAuthorization(
            RuntimeTerminationDelivery delivery,
            boolean providerCallAuthorized,
            boolean alreadyStarted,
            boolean resultObserved,
            String safeReasonCode) {
    }
}
