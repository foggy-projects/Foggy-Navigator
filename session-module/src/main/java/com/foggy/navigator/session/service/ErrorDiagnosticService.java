package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorCategory;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticInput;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticSanitizer;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.agent.framework.diagnostic.ErrorRuntimePhase;
import com.foggy.navigator.common.entity.ErrorDiagnosticEntity;
import com.foggy.navigator.common.entity.ErrorDiagnosticShareEntity;
import com.foggy.navigator.session.config.ErrorDiagnosticProperties;
import com.foggy.navigator.session.dto.ErrorDiagnosticDTO;
import com.foggy.navigator.session.dto.ErrorDiagnosticShareDTO;
import com.foggy.navigator.session.repository.ErrorDiagnosticRepository;
import com.foggy.navigator.session.repository.ErrorDiagnosticShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Persistence, ownership and temporary sharing boundary for diagnostic snapshots. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorDiagnosticService {

    public static final String DIAGNOSTIC_REF_PREFIX = "diagnostic://";
    private static final String NOT_AVAILABLE = "Diagnostic not available";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ErrorDiagnosticRepository diagnosticRepository;
    private final ErrorDiagnosticShareRepository shareRepository;
    private final ErrorDiagnosticProperties properties;

    /**
     * Saves only allowlisted and sanitized fields. Failure is deliberately
     * swallowed so diagnostics can never replace the task's real terminal state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String createSnapshotSafely(ErrorEnvelope envelope,
                                       ErrorDiagnosticInput input,
                                       String sessionId,
                                       String ownerUserId,
                                       String tenantId) {
        try {
            return createSnapshot(envelope, input, sessionId, ownerUserId, tenantId);
        } catch (Exception e) {
            log.warn("Diagnostic snapshot persistence failed: taskId={}, code={}, failureType={}",
                    envelope != null ? envelope.getTaskId() : null,
                    envelope != null ? envelope.getErrorCode() : null,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Returns only the safe envelope used by task and SSE projections. */
    @Transactional(readOnly = true)
    public ErrorEnvelope findLatestEnvelope(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        return diagnosticRepository.findFirstByTaskIdOrderByCreatedAtDesc(taskId)
                .filter(entity -> entity.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(this::toEnvelope)
                .orElse(null);
    }

    @Transactional
    public String createSnapshot(ErrorEnvelope envelope,
                                 ErrorDiagnosticInput input,
                                 String sessionId,
                                 String ownerUserId,
                                 String tenantId) {
        requireText(envelope != null ? envelope.getTaskId() : null);
        requireText(sessionId);
        requireText(ownerUserId);
        requireText(envelope.getErrorCode());

        LocalDateTime now = LocalDateTime.now();
        ErrorCategory category = envelope.getCategory() != null
                ? envelope.getCategory()
                : ErrorDiagnosticSanitizer.classify(envelope.getErrorCode());
        ErrorRuntimePhase phase = envelope.getRuntimePhase() != null
                ? envelope.getRuntimePhase()
                : ErrorRuntimePhase.UNKNOWN;

        ErrorDiagnosticEntity entity = new ErrorDiagnosticEntity();
        entity.setDiagnosticId("dg_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setSchemaVersion(ErrorDiagnosticInput.SCHEMA_VERSION);
        entity.setRedactionVersion(ErrorDiagnosticSanitizer.VERSION);
        entity.setTaskId(envelope.getTaskId());
        entity.setSessionId(sessionId);
        entity.setOwnerUserId(ownerUserId);
        entity.setTenantId(blankToNull(tenantId));
        entity.setProviderType(safeEnumLike(envelope.getProviderType(), "UNKNOWN", 32));
        entity.setRuntimeType(safeEnumLike(envelope.getRuntimeType(), null, 32));
        entity.setErrorCode(safeCode(envelope.getErrorCode()));
        entity.setCategory(category.name());
        entity.setRuntimePhase(phase.name());
        entity.setSafeMessage(safeMessage(envelope.getMessage(), envelope.getErrorCode()));
        entity.setRecoverable(Boolean.TRUE.equals(envelope.getRecoverable()));
        if (input != null) {
            entity.setWorkerLabel(safeLabel(input.getWorkerLabel(), 128));
            entity.setProviderStatus(safeLabel(input.getProviderStatus(), 160));
            entity.setHttpStatus(validHttpStatus(input.getHttpStatus()));
            entity.setRetryCount(validRetryCount(input.getRetryCount()));
            entity.setExceptionType(ErrorDiagnosticSanitizer.sanitizeType(input.getExceptionType()));
            entity.setDiagnosticText(ErrorDiagnosticSanitizer.sanitize(input.getDiagnosticText()));
        }
        entity.setOccurredAt(toLocal(envelope.getOccurredAt(), now));
        entity.setExpiresAt(now.plusDays(Math.max(1, properties.getRetentionDays())));
        diagnosticRepository.save(entity);
        return DIAGNOSTIC_REF_PREFIX + entity.getDiagnosticId();
    }

    @Transactional(readOnly = true)
    public ErrorDiagnosticDTO getOwned(String diagnosticRefOrId, String userId, String tenantId) {
        ErrorDiagnosticEntity entity = findOwnedActive(diagnosticRefOrId, userId, tenantId);
        return toDto(entity, null, true);
    }

    @Transactional(readOnly = true)
    public List<ErrorDiagnosticShareDTO> listShares(String diagnosticRefOrId, String userId, String tenantId) {
        ErrorDiagnosticEntity diagnostic = findOwnedActive(diagnosticRefOrId, userId, tenantId);
        return shareRepository.findByDiagnosticIdOrderByCreatedAtDesc(diagnostic.getDiagnosticId()).stream()
                .map(this::toShareStatusDto)
                .toList();
    }

    @Transactional
    public ErrorDiagnosticShareDTO createShare(String diagnosticRefOrId,
                                               String userId,
                                               String tenantId,
                                               Integer requestedDays) {
        requireSharingEnabled();
        ErrorDiagnosticEntity diagnostic = findOwnedActive(diagnosticRefOrId, userId, tenantId);
        int days = requestedDays == null ? properties.getDefaultShareDays() : requestedDays;
        if (days < 1 || days > properties.getMaxShareDays()) {
            throw unavailable();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(days);
        if (expiresAt.isAfter(diagnostic.getExpiresAt())) {
            expiresAt = diagnostic.getExpiresAt();
        }
        if (!expiresAt.isAfter(now)) {
            throw unavailable();
        }

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        ErrorDiagnosticShareEntity share = new ErrorDiagnosticShareEntity();
        share.setShareId("ds_" + UUID.randomUUID().toString().replace("-", ""));
        share.setDiagnosticId(diagnostic.getDiagnosticId());
        share.setTokenHash(hashToken(token));
        share.setCreatedBy(userId);
        share.setCreatedAt(now);
        share.setExpiresAt(expiresAt);
        share.setAccessCount(0L);
        shareRepository.save(share);

        return ErrorDiagnosticShareDTO.builder()
                .shareId(share.getShareId())
                .diagnosticId(diagnostic.getDiagnosticId())
                .shareUrl("/diagnostic-share/" + token)
                .createdAt(now)
                .expiresAt(expiresAt)
                .accessCount(0L)
                .build();
    }

    @Transactional
    public void revokeShare(String diagnosticRefOrId,
                            String shareId,
                            String userId,
                            String tenantId) {
        ErrorDiagnosticEntity diagnostic = findOwnedActive(diagnosticRefOrId, userId, tenantId);
        ErrorDiagnosticShareEntity share = shareRepository.findById(shareId).orElseThrow(ErrorDiagnosticService::unavailable);
        if (!diagnostic.getDiagnosticId().equals(share.getDiagnosticId())) {
            throw unavailable();
        }
        if (share.getRevokedAt() == null) {
            share.setRevokedAt(LocalDateTime.now());
            shareRepository.save(share);
        }
    }

    @Transactional
    public ErrorDiagnosticDTO getPublic(String token) {
        requireSharingEnabled();
        String tokenHash = hashToken(token);
        ErrorDiagnosticShareEntity share = shareRepository.findByTokenHash(tokenHash)
                .orElseThrow(ErrorDiagnosticService::unavailable);
        LocalDateTime now = LocalDateTime.now();
        if (share.getRevokedAt() != null || !share.getExpiresAt().isAfter(now)) {
            throw unavailable();
        }
        ErrorDiagnosticEntity diagnostic = diagnosticRepository.findById(share.getDiagnosticId())
                .orElseThrow(ErrorDiagnosticService::unavailable);
        if (!diagnostic.getExpiresAt().isAfter(now)) {
            throw unavailable();
        }
        shareRepository.recordAccess(share.getShareId(), now);
        return toDto(diagnostic, share.getExpiresAt(), false);
    }

    @Scheduled(cron = "${navigator.error-diagnostics.cleanup-cron:0 23 3 * * *}")
    @Transactional
    public void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        long deletedShares = shareRepository.deleteByExpiresAtBefore(now);
        long deletedDiagnostics = diagnosticRepository.deleteByExpiresAtBefore(now);
        if (deletedShares > 0 || deletedDiagnostics > 0) {
            log.info("Expired diagnostic cleanup completed: diagnostics={}, shares={}",
                    deletedDiagnostics, deletedShares);
        }
    }

    private ErrorDiagnosticEntity findOwnedActive(String diagnosticRefOrId, String userId, String tenantId) {
        String diagnosticId = diagnosticId(diagnosticRefOrId);
        requireText(userId);
        ErrorDiagnosticEntity entity = blankToNull(tenantId) == null
                ? diagnosticRepository.findByDiagnosticIdAndOwnerUserIdAndTenantIdIsNull(diagnosticId, userId)
                    .orElseThrow(ErrorDiagnosticService::unavailable)
                : diagnosticRepository.findByDiagnosticIdAndOwnerUserIdAndTenantId(diagnosticId, userId, tenantId)
                    .orElseThrow(ErrorDiagnosticService::unavailable);
        if (!entity.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw unavailable();
        }
        return entity;
    }

    private ErrorDiagnosticDTO toDto(ErrorDiagnosticEntity entity, LocalDateTime shareExpiresAt, boolean internal) {
        return ErrorDiagnosticDTO.builder()
                .diagnosticId(entity.getDiagnosticId())
                .diagnosticRef(DIAGNOSTIC_REF_PREFIX + entity.getDiagnosticId())
                .taskId(internal ? entity.getTaskId() : null)
                .providerType(entity.getProviderType())
                .runtimeType(entity.getRuntimeType())
                .errorCode(entity.getErrorCode())
                .category(entity.getCategory())
                .runtimePhase(entity.getRuntimePhase())
                .safeMessage(entity.getSafeMessage())
                .recoverable(entity.getRecoverable())
                .providerStatus(entity.getProviderStatus())
                .httpStatus(entity.getHttpStatus())
                .retryCount(entity.getRetryCount())
                .exceptionType(entity.getExceptionType())
                .diagnosticText(entity.getDiagnosticText())
                .occurredAt(entity.getOccurredAt())
                .expiresAt(entity.getExpiresAt())
                .shareExpiresAt(shareExpiresAt)
                .publicSharingEnabled(internal && properties.isPublicSharingEnabled())
                .defaultShareDays(internal ? properties.getDefaultShareDays() : null)
                .maxShareDays(internal ? properties.getMaxShareDays() : null)
                .build();
    }

    private ErrorEnvelope toEnvelope(ErrorDiagnosticEntity entity) {
        return ErrorEnvelope.builder()
                .errorCode(entity.getErrorCode())
                .message(entity.getSafeMessage())
                .category(parseCategory(entity.getCategory()))
                .runtimePhase(parsePhase(entity.getRuntimePhase()))
                .recoverable(entity.getRecoverable())
                .diagnosticRef(DIAGNOSTIC_REF_PREFIX + entity.getDiagnosticId())
                .occurredAt(entity.getOccurredAt() != null
                        ? entity.getOccurredAt().toInstant(ZoneOffset.UTC) : null)
                .taskId(entity.getTaskId())
                .providerType(entity.getProviderType())
                .runtimeType(entity.getRuntimeType())
                .build();
    }

    private static ErrorCategory parseCategory(String value) {
        try {
            return value == null ? ErrorCategory.UNKNOWN : ErrorCategory.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ErrorCategory.UNKNOWN;
        }
    }

    private static ErrorRuntimePhase parsePhase(String value) {
        try {
            return value == null ? ErrorRuntimePhase.UNKNOWN : ErrorRuntimePhase.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ErrorRuntimePhase.UNKNOWN;
        }
    }

    private ErrorDiagnosticShareDTO toShareStatusDto(ErrorDiagnosticShareEntity share) {
        return ErrorDiagnosticShareDTO.builder()
                .shareId(share.getShareId())
                .diagnosticId(share.getDiagnosticId())
                .createdAt(share.getCreatedAt())
                .expiresAt(share.getExpiresAt())
                .revokedAt(share.getRevokedAt())
                .lastAccessAt(share.getLastAccessAt())
                .accessCount(share.getAccessCount())
                .build();
    }

    private void requireSharingEnabled() {
        if (!properties.isPublicSharingEnabled()) throw unavailable();
    }

    private static String diagnosticId(String refOrId) {
        requireText(refOrId);
        String id = refOrId.startsWith(DIAGNOSTIC_REF_PREFIX)
                ? refOrId.substring(DIAGNOSTIC_REF_PREFIX.length())
                : refOrId;
        if (!id.matches("dg_[a-f0-9]{32}")) throw unavailable();
        return id;
    }

    private static String hashToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safeMessage(String message, String errorCode) {
        String sanitized = ErrorDiagnosticSanitizer.sanitize(message);
        if (sanitized == null) return "任务执行失败（" + safeCode(errorCode) + "）";
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 511) + "…";
    }

    private static String safeCode(String value) {
        if (value != null && value.matches("[A-Z][A-Z0-9_]{1,159}")) return value;
        return "WORKER_REMOTE_ERROR";
    }

    private static String safeEnumLike(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{0," + (max - 1) + "}") ? normalized : fallback;
    }

    private static String safeLabel(String value, int max) {
        String sanitized = ErrorDiagnosticSanitizer.sanitize(value);
        if (sanitized == null) return null;
        return sanitized.length() <= max ? sanitized : sanitized.substring(0, max);
    }

    private static Integer validHttpStatus(Integer value) {
        return value != null && value >= 100 && value <= 599 ? value : null;
    }

    private static Integer validRetryCount(Integer value) {
        return value != null && value >= 0 && value <= 1000 ? value : null;
    }

    private static LocalDateTime toLocal(Instant instant, LocalDateTime fallback) {
        return instant == null ? fallback : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw unavailable();
    }

    private static IllegalArgumentException unavailable() {
        return new IllegalArgumentException(NOT_AVAILABLE);
    }
}
