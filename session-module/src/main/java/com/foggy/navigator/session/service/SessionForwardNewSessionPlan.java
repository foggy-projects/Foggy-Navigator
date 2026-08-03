package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, server-derived semantic plan for a {@code NEW_SESSION} forward.
 *
 * <p>The plan is deliberately package-local and effect-free. It freezes the facts later consumed
 * by the target Session reservation and canonical task-create command without carrying an HTTP
 * credential, caller-provided digest or mutable request DTO.</p>
 */
record SessionForwardNewSessionPlan(
        String ownerUserId,
        String tenantId,
        SourceSnapshot source,
        String rootParentSessionId,
        String prompt,
        TargetExecution target) {

    private static final String DIGEST_DOMAIN = "navi.session-forward-plan.v1";
    private static final String OPERATION = "NEW_SESSION";
    private static final String REQUEST_SOURCE = "UI_FORWARD";
    private static final int SESSION_TITLE_LIMIT = 120;

    SessionForwardNewSessionPlan {
        ownerUserId = requireReference(ownerUserId, 64, "ownerUserId");
        tenantId = normalizeReference(tenantId, 64, "tenantId");
        source = Objects.requireNonNull(source, "source must not be null");
        rootParentSessionId = requireReference(
                rootParentSessionId, 64, "rootParentSessionId");
        prompt = requireOpaqueText(prompt, "prompt");
        target = Objects.requireNonNull(target, "target must not be null");
    }

    String sessionTitle() {
        if (prompt.length() <= SESSION_TITLE_LIMIT) {
            return prompt;
        }
        int end = SESSION_TITLE_LIMIT;
        if (Character.isHighSurrogate(prompt.charAt(end - 1))
                && Character.isLowSurrogate(prompt.charAt(end))) {
            end--;
        }
        return prompt.substring(0, end);
    }

    SessionForwardTargetSessionReservationService.ReservationSpec reservationSpec() {
        return new SessionForwardTargetSessionReservationService.ReservationSpec(
                ownerUserId,
                tenantId,
                target.logicalAgentId(),
                rootParentSessionId,
                sessionTitle(),
                target.directoryId(),
                target.milestoneId(),
                target.model());
    }

    AgentTaskSubmitRequest toSubmitRequest(
            String canonicalClientRequestId,
            String deterministicTargetSessionId) {
        String clientRequestId = canonicalUuid(canonicalClientRequestId);
        String targetSessionId = requireReference(
                deterministicTargetSessionId, 64, "targetSessionId");
        String expectedTargetSessionId =
                SessionForwardTargetSessionReservationService.deriveSessionId(
                        clientRequestId, ownerUserId, tenantId);
        if (!expectedTargetSessionId.equals(targetSessionId)) {
            throw new IllegalArgumentException(
                    "targetSessionId does not match the forward reservation identity");
        }
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(ownerUserId)
                .tenantId(tenantId)
                .sessionId(targetSessionId)
                .modelConfigId(target.modelConfigId())
                .requestSource(REQUEST_SOURCE)
                .build();

        return AgentTaskSubmitRequest.builder()
                .agentId(target.logicalAgentId())
                .resolveContext(context)
                .sessionId(targetSessionId)
                .workerId(target.workerId())
                .prompt(prompt)
                .cwd(target.cwd())
                .directoryId(target.directoryId())
                .model(target.model())
                .modelConfigId(target.modelConfigId())
                .maxTurns(target.maxTurns())
                .permissionMode(target.permissionMode())
                .images(target.images())
                .agentTeamsConfigId(target.agentTeamsConfigId())
                .agentTeamsJson(target.agentTeamsJson())
                .clientRequestId(clientRequestId)
                .initializeRuntimeAffinity(true)
                .build();
    }

    void requireExactPreparedSubmitRequest(
            AgentTaskSubmitRequest actual,
            String canonicalClientRequestId,
            String deterministicTargetSessionId) {
        Objects.requireNonNull(actual, "submit request must not be null");
        AgentTaskSubmitRequest expected = toSubmitRequest(
                canonicalClientRequestId, deterministicTargetSessionId);
        AgentResolveContext actualContext = actual.getResolveContext();
        AgentResolveContext expectedContext = expected.getResolveContext();
        Map<String, Object> expectedMetadata = target.directoryId() == null
                ? null
                : Map.of("directoryId", target.directoryId());

        boolean exact = Objects.equals(actual.getAgentId(), expected.getAgentId())
                && actual.getProviderType() == null
                && Objects.equals(actual.getSessionId(), expected.getSessionId())
                && Objects.equals(actual.getWorkerId(), expected.getWorkerId())
                && Objects.equals(actual.getPrompt(), expected.getPrompt())
                && Objects.equals(actual.getCwd(), expected.getCwd())
                && Objects.equals(actual.getDirectoryId(), expected.getDirectoryId())
                && Objects.equals(actual.getModel(), expected.getModel())
                && Objects.equals(actual.getModelConfigId(), expected.getModelConfigId())
                && Objects.equals(actual.getMaxTurns(), expected.getMaxTurns())
                && Objects.equals(actual.getPermissionMode(), expected.getPermissionMode())
                && Objects.equals(actual.getImages(), expected.getImages())
                && actual.getAttachments() == null
                && Objects.equals(
                        actual.getAgentTeamsConfigId(), expected.getAgentTeamsConfigId())
                && Objects.equals(actual.getAgentTeamsJson(), expected.getAgentTeamsJson())
                && actual.getMessage() == null
                && actual.getContextId() == null
                && actual.getContext() == null
                && actual.getContextAlias() == null
                && Objects.equals(actual.getMetadata(), expectedMetadata)
                && Objects.equals(
                        actual.getClientRequestId(), expected.getClientRequestId())
                && actual.isInitializeRuntimeAffinity()
                && actualContext != null
                && Objects.equals(actualContext.getUserId(), expectedContext.getUserId())
                && Objects.equals(actualContext.getTenantId(), expectedContext.getTenantId())
                && Objects.equals(actualContext.getSessionId(), expectedContext.getSessionId())
                && Objects.equals(
                        actualContext.getModelConfigId(), expectedContext.getModelConfigId())
                && Objects.equals(
                        actualContext.getRequestSource(), expectedContext.getRequestSource());
        if (!exact) {
            throw new IllegalStateException("FORWARD_PLAN_PREPARED_REQUEST_CONFLICT");
        }
    }

    /** Preserves the existing forward wire convention without parsing or rewriting image JSON. */
    static List<String> imagesFromWire(String images) {
        String normalized = normalizeTrimmedOpaque(images);
        return normalized == null ? null : List.of(normalized);
    }

    String semanticFingerprint() {
        MessageDigest digest = sha256();
        putString(digest, 1, DIGEST_DOMAIN);
        putString(digest, 2, OPERATION);
        putString(digest, 3, REQUEST_SOURCE);
        putString(digest, 4, ownerUserId);
        putString(digest, 5, tenantId);
        putString(digest, 6, source.sessionId());
        putString(digest, 7, source.kind().name());
        putString(digest, 8, source.referenceId());
        putString(digest, 9, source.taskId());
        putString(digest, 10, source.content());
        putString(digest, 11, source.workerId());
        putString(digest, 12, source.directoryId());
        putString(digest, 13, source.milestoneId());
        putString(digest, 14, rootParentSessionId);
        putString(digest, 15, prompt);
        putString(digest, 16, sessionTitle());
        putString(digest, 17, target.logicalAgentId());
        putString(digest, 18, target.workerId());
        putString(digest, 19, target.directoryId());
        putString(digest, 20, target.cwd());
        putString(digest, 21, target.milestoneId());
        putString(digest, 22, target.model());
        putString(digest, 23, target.modelConfigId());
        putString(digest, 24, target.permissionMode());
        putNullableInt(digest, 25, target.maxTurns());
        putString(digest, 26, target.agentTeamsConfigId());
        putString(digest, 27, target.agentTeamsJson());
        putStringList(digest, 28, target.images());
        putBoolean(digest, 29, true);
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override
    public String toString() {
        return "SessionForwardNewSessionPlan[operation=NEW_SESSION"
                + ", ownerBound=true"
                + ", tenantBound=" + (tenantId != null)
                + ", sourceKind=" + source.kind()
                + ", sourceSessionId=" + source.sessionId()
                + ", sourceReferenceId=" + source.referenceId()
                + ", rootParentSessionId=" + rootParentSessionId
                + ", logicalAgentId=" + target.logicalAgentId()
                + ", workerId=" + target.workerId()
                + ", directoryId=" + target.directoryId()
                + ", milestoneId=" + target.milestoneId()
                + ", model=" + target.model()
                + ", modelConfigId=" + target.modelConfigId()
                + ", prompt=<redacted>"
                + ", sourceContent=<redacted>"
                + ", images=<redacted:" + (target.images() == null ? "null" : target.images().size()) + ">"
                + ", teamsJson=<redacted>" + ']';
    }

    enum SourceKind {
        MESSAGE,
        TASK_RESULT
    }

    record SourceSnapshot(
            String sessionId,
            SourceKind kind,
            String referenceId,
            String taskId,
            String content,
            String workerId,
            String directoryId,
            String milestoneId) {

        SourceSnapshot {
            sessionId = requireReference(sessionId, 64, "source.sessionId");
            kind = Objects.requireNonNull(kind, "source.kind must not be null");
            referenceId = requireReference(referenceId, 64, "source.referenceId");
            taskId = normalizeReference(taskId, 64, "source.taskId");
            content = requireOpaqueText(content, "source.content");
            workerId = normalizeReference(workerId, 64, "source.workerId");
            directoryId = normalizeReference(directoryId, 64, "source.directoryId");
            milestoneId = normalizeReference(milestoneId, 64, "source.milestoneId");
            if (kind == SourceKind.TASK_RESULT && taskId == null) {
                throw new IllegalArgumentException(
                        "source.taskId is required for TASK_RESULT");
            }
        }

        @Override
        public String toString() {
            return "SourceSnapshot[sessionId=" + sessionId
                    + ", kind=" + kind
                    + ", referenceId=" + referenceId
                    + ", taskId=" + taskId
                    + ", workerId=" + workerId
                    + ", directoryId=" + directoryId
                    + ", milestoneId=" + milestoneId
                    + ", content=<redacted>]";
        }
    }

    record TargetExecution(
            String workerId,
            String directoryId,
            String cwd,
            String logicalAgentId,
            String milestoneId,
            String model,
            String modelConfigId,
            String permissionMode,
            Integer maxTurns,
            String agentTeamsConfigId,
            String agentTeamsJson,
            List<String> images) {

        TargetExecution {
            workerId = requireReference(workerId, 64, "target.workerId");
            directoryId = normalizeReference(directoryId, 64, "target.directoryId");
            cwd = normalizeOpaque(cwd);
            if (cwd != null && cwd.length() > 2048) {
                throw new IllegalArgumentException("target.cwd is invalid");
            }
            logicalAgentId = normalizeReference(
                    logicalAgentId, 64, "target.logicalAgentId");
            if (logicalAgentId != null && directoryId == null) {
                throw new IllegalArgumentException(
                        "target.directoryId is required when target.logicalAgentId is set");
            }
            milestoneId = normalizeReference(milestoneId, 64, "target.milestoneId");
            model = normalizeReference(model, 128, "target.model");
            modelConfigId = normalizeReference(
                    modelConfigId, 64, "target.modelConfigId");
            permissionMode = normalizeReference(
                    permissionMode, 64, "target.permissionMode");
            agentTeamsConfigId = normalizeReference(
                    agentTeamsConfigId, 64, "target.agentTeamsConfigId");
            agentTeamsJson = normalizeOpaque(agentTeamsJson);
            images = images == null ? null : List.copyOf(images);
            if (images != null) {
                images.forEach(value -> requireWellFormedUnicode(
                        value, "target.images entry"));
            }
        }

        @Override
        public String toString() {
            return "TargetExecution[workerId=" + workerId
                    + ", directoryId=" + directoryId
                    + ", cwd=<redacted>"
                    + ", logicalAgentId=" + logicalAgentId
                    + ", milestoneId=" + milestoneId
                    + ", model=" + model
                    + ", modelConfigId=" + modelConfigId
                    + ", permissionMode=" + permissionMode
                    + ", maxTurns=" + maxTurns
                    + ", agentTeamsConfigId=" + agentTeamsConfigId
                    + ", agentTeamsJson=<redacted>"
                    + ", images=<redacted:" + (images == null ? "null" : images.size()) + ">]";
        }
    }

    private static String canonicalUuid(String value) {
        String normalized = requireReference(value, 64, "clientRequestId").toLowerCase();
        UUID parsed;
        try {
            parsed = UUID.fromString(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("clientRequestId must be a canonical UUID", invalid);
        }
        if (!parsed.toString().equals(normalized)) {
            throw new IllegalArgumentException("clientRequestId must be a canonical UUID");
        }
        return normalized;
    }

    private static String requireReference(String value, int maxLength, String field) {
        requireWellFormedUnicode(value, field);
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalizeReference(String value, int maxLength, String field) {
        return value == null || value.isBlank()
                ? null
                : requireReference(value, maxLength, field);
    }

    private static String requireOpaqueText(String value, String field) {
        requireWellFormedUnicode(value, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String normalizeOpaque(String value) {
        requireWellFormedUnicode(value, "opaque value");
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeTrimmedOpaque(String value) {
        requireWellFormedUnicode(value, "opaque value");
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void requireWellFormedUnicode(String value, String field) {
        if (value == null) {
            return;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(field + " contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(field + " contains malformed Unicode");
            }
        }
    }

    private static void putString(MessageDigest digest, int tag, String value) {
        putTag(digest, tag);
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putNullableInt(MessageDigest digest, int tag, Integer value) {
        putTag(digest, tag);
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        putInt(digest, value);
    }

    private static void putStringList(MessageDigest digest, int tag, List<String> values) {
        putTag(digest, tag);
        if (values == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        putInt(digest, values.size());
        for (String value : values) {
            byte[] bytes = Objects.requireNonNull(value, "image entry must not be null")
                    .getBytes(StandardCharsets.UTF_8);
            putInt(digest, bytes.length);
            digest.update(bytes);
        }
    }

    private static void putBoolean(MessageDigest digest, int tag, boolean value) {
        putTag(digest, tag);
        digest.update((byte) (value ? 1 : 0));
    }

    private static void putTag(MessageDigest digest, int tag) {
        putInt(digest, tag);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
