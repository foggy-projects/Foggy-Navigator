package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable, process-local identity and effect plan for one fresh Business Task create. */
record BusinessAgentTaskCreatePlan(
        Identity identity,
        AgentRoute agentRoute,
        ModelTarget modelTarget,
        WorkspaceTarget workspaceTarget,
        InputBinding inputBinding,
        String semanticFingerprint) {

    static final String PLAN_DRIFT = "BUSINESS_TASK_CREATE_PLAN_DRIFT";
    private static final String FINGERPRINT_DOMAIN = "navi.business-task-create-plan.v1";
    private static final String CONTENT_DIGEST_DOMAIN = "navi.business-task-create-content.v1";
    private static final String POLICY_DIGEST_DOMAIN = "navi.business-task-create-policy.v1";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    BusinessAgentTaskCreatePlan {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(agentRoute, "agentRoute must not be null");
        Objects.requireNonNull(modelTarget, "modelTarget must not be null");
        Objects.requireNonNull(inputBinding, "inputBinding must not be null");
        semanticFingerprint = fingerprint(
                identity, agentRoute, modelTarget, workspaceTarget, inputBinding);
    }

    void requireExactRevalidation(BusinessAgentTaskCreatePlan current) {
        if (!equals(current)) {
            throw new SecurityException(PLAN_DRIFT);
        }
    }

    static String clientContextDigest(String clientContextJson) {
        return digestText(CONTENT_DIGEST_DOMAIN, clientContextJson);
    }

    static String policyDigest(Object policy) {
        if (policy == null) {
            return digestText(POLICY_DIGEST_DOMAIN, null);
        }
        try {
            return digestText(POLICY_DIGEST_DOMAIN, OBJECT_MAPPER.writeValueAsString(policy));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("workspace policy is not serializable", error);
        }
    }

    @Override
    public String toString() {
        return "BusinessAgentTaskCreatePlan[semanticFingerprint=" + semanticFingerprint
                + ", tenantId=" + identity.tenantId()
                + ", actorUserId=" + identity.actorUserId()
                + ", clientAppId=" + identity.clientAppId()
                + ", agentId=" + agentRoute.agentId()
                + ", selectedWorkerId=" + agentRoute.selectedWorkerId()
                + ", modelConfigId=" + modelTarget.modelConfigId()
                + ", directoryId="
                + (workspaceTarget != null ? workspaceTarget.directoryId() : null)
                + "]";
    }

    record Identity(
            String tenantId,
            String actorUserId,
            String clientAppId,
            String upstreamSystemId,
            String upstreamUserId,
            String sessionId,
            String contextId) {

        Identity {
            requireText(tenantId, "tenantId");
            requireText(actorUserId, "actorUserId");
            requireText(clientAppId, "clientAppId");
            requireText(upstreamUserId, "upstreamUserId");
            requireText(sessionId, "sessionId");
        }
    }

    record AgentRoute(
            String agentId,
            ResourceOwnerType agentOwnerType,
            String agentOwnerId,
            String agentClientAppId,
            String agentSource,
            String skillId,
            String skillName,
            String internalWorkerRouteId,
            String workerPoolId,
            ResourceOwnerType workerPoolOwnerType,
            String workerPoolOwnerId,
            String workerPoolSource,
            String workerBackend,
            String agentPhysicalWorkerId,
            ResourceOwnerType physicalWorkerOwnerType,
            String physicalWorkerOwnerId,
            String physicalWorkerSource,
            String launchPhysicalWorkerId,
            String selectedWorkerId,
            String launcherType,
            String expectedProviderType) {

        AgentRoute {
            requireText(agentId, "agentId");
            requireText(skillId, "skillId");
            requireText(internalWorkerRouteId, "internalWorkerRouteId");
            requireText(workerBackend, "workerBackend");
            if ((selectedWorkerId == null) != (launcherType == null)) {
                throw new IllegalArgumentException(
                        "selectedWorkerId and launcherType must have the same presence");
            }
        }
    }

    record ModelTarget(
            String modelConfigId,
            String modelName,
            String visionModelConfigId,
            String resolvedRequestedModelConfigId,
            String resolvedRequestedModelVariant,
            LlmModelCategory category,
            String modelNameSource,
            String workerBackend,
            String source) {

        ModelTarget {
            requireText(modelConfigId, "modelConfigId");
            Objects.requireNonNull(category, "category must not be null");
        }
    }

    record WorkspaceTarget(
            String directoryId,
            String physicalWorkerId,
            WorkspaceScope workspaceScope,
            WorkingDirectoryResolverType resolverType,
            String workdir,
            List<String> allowedDirs,
            boolean readOnly,
            String quotaPolicyDigest,
            String retentionPolicyDigest,
            String concurrencyPolicyDigest,
            String source) {

        WorkspaceTarget {
            requireText(directoryId, "directoryId");
            Objects.requireNonNull(workspaceScope, "workspaceScope must not be null");
            Objects.requireNonNull(resolverType, "resolverType must not be null");
            allowedDirs = immutableCopy(allowedDirs);
            requireText(quotaPolicyDigest, "quotaPolicyDigest");
            requireText(retentionPolicyDigest, "retentionPolicyDigest");
            requireText(concurrencyPolicyDigest, "concurrencyPolicyDigest");
        }
    }

    record InputBinding(
            String requestedModelConfigIdRaw,
            String requestedModelVariant,
            String requestedDirectoryId,
            List<String> allowedTools,
            String clientContextDigest) {

        InputBinding {
            allowedTools = immutableCopy(allowedTools);
            requireText(clientContextDigest, "clientContextDigest");
        }
    }

    private static String fingerprint(
            Identity identity,
            AgentRoute route,
            ModelTarget model,
            WorkspaceTarget workspace,
            InputBinding input) {
        CanonicalDigest digest = new CanonicalDigest(FINGERPRINT_DOMAIN)
                .field(identity.tenantId())
                .field(identity.actorUserId())
                .field(identity.clientAppId())
                .field(identity.upstreamSystemId())
                .field(identity.upstreamUserId())
                .field(identity.sessionId())
                .field(identity.contextId())
                .field(route.agentId())
                .field(name(route.agentOwnerType()))
                .field(route.agentOwnerId())
                .field(route.agentClientAppId())
                .field(route.agentSource())
                .field(route.skillId())
                .field(route.skillName())
                .field(route.internalWorkerRouteId())
                .field(route.workerPoolId())
                .field(name(route.workerPoolOwnerType()))
                .field(route.workerPoolOwnerId())
                .field(route.workerPoolSource())
                .field(route.workerBackend())
                .field(route.agentPhysicalWorkerId())
                .field(name(route.physicalWorkerOwnerType()))
                .field(route.physicalWorkerOwnerId())
                .field(route.physicalWorkerSource())
                .field(route.launchPhysicalWorkerId())
                .field(route.selectedWorkerId())
                .field(route.launcherType())
                .field(route.expectedProviderType())
                .field(model.modelConfigId())
                .field(model.modelName())
                .field(model.visionModelConfigId())
                .field(model.resolvedRequestedModelConfigId())
                .field(model.resolvedRequestedModelVariant())
                .field(name(model.category()))
                .field(model.modelNameSource())
                .field(model.workerBackend())
                .field(model.source());
        if (workspace == null) {
            digest.marker(false);
        } else {
            digest.marker(true)
                    .field(workspace.directoryId())
                    .field(workspace.physicalWorkerId())
                    .field(name(workspace.workspaceScope()))
                    .field(name(workspace.resolverType()))
                    .field(workspace.workdir())
                    .list(workspace.allowedDirs())
                    .marker(workspace.readOnly())
                    .field(workspace.quotaPolicyDigest())
                    .field(workspace.retentionPolicyDigest())
                    .field(workspace.concurrencyPolicyDigest())
                    .field(workspace.source());
        }
        return digest
                .field(input.requestedModelConfigIdRaw())
                .field(input.requestedModelVariant())
                .field(input.requestedDirectoryId())
                .list(input.allowedTools())
                .field(input.clientContextDigest())
                .finish();
    }

    private static String digestText(String domain, String value) {
        return new CanonicalDigest(domain).field(value).finish();
    }

    private static List<String> immutableCopy(List<String> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static final class CanonicalDigest {

        private final MessageDigest digest;

        private CanonicalDigest(String domain) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
            field(domain);
        }

        private CanonicalDigest field(String value) {
            if (value == null) {
                digest.update((byte) 0);
                return this;
            }
            digest.update((byte) 1);
            byte[] encoded = strictUtf8(value);
            int length = encoded.length;
            digest.update((byte) (length >>> 24));
            digest.update((byte) (length >>> 16));
            digest.update((byte) (length >>> 8));
            digest.update((byte) length);
            digest.update(encoded);
            return this;
        }

        private byte[] strictUtf8(String value) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (index + 1 >= value.length()
                            || !Character.isLowSurrogate(value.charAt(index + 1))) {
                        throw new IllegalArgumentException(
                                "canonical value contains malformed Unicode");
                    }
                    index++;
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException(
                            "canonical value contains malformed Unicode");
                }
            }
            return value.getBytes(StandardCharsets.UTF_8);
        }

        private CanonicalDigest marker(boolean value) {
            digest.update(value ? (byte) 1 : (byte) 0);
            return this;
        }

        private CanonicalDigest list(List<String> values) {
            if (values == null) {
                digest.update((byte) 0);
                return this;
            }
            digest.update((byte) 1);
            int size = values.size();
            digest.update((byte) (size >>> 24));
            digest.update((byte) (size >>> 16));
            digest.update((byte) (size >>> 8));
            digest.update((byte) size);
            values.forEach(this::field);
            return this;
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
