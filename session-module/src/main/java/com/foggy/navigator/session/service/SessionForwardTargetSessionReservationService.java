package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import com.foggy.navigator.session.repository.SessionForwardTargetSessionReservationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserves the deterministic target Session owned by one new-session forward command.
 *
 * <p>The service is deliberately dormant until the forward command adapter is wired. It can only
 * insert a new row or read and verify an exact existing row; it never updates, deletes or repairs a
 * Session.</p>
 */
@Service
public class SessionForwardTargetSessionReservationService {

    private static final String ID_DOMAIN = "navi.session-forward-target.v1";
    private static final String ID_PREFIX = "fwd_";
    private static final int ID_DIGEST_CHARS = 60;
    private static final String CONFLICT_CODE = "FORWARD_SESSION_RESERVATION_CONFLICT";

    private final SessionForwardTargetSessionReservationRepository inserts;
    private final SessionRepository sessions;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public SessionForwardTargetSessionReservationService(
            SessionForwardTargetSessionReservationRepository inserts,
            SessionRepository sessions,
            PlatformTransactionManager transactionManager) {
        this.inserts = Objects.requireNonNull(inserts, "inserts must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.writes = requiresNew(transactionManager, false);
        this.reads = requiresNew(transactionManager, true);
    }

    public ReservationResult reserve(String clientRequestId, ReservationSpec requested) {
        String requestId = canonicalUuid(clientRequestId);
        ReservationSpec spec = Objects.requireNonNull(requested, "requested must not be null");
        String sessionId = deriveSessionId(requestId, spec.ownerUserId(), spec.tenantId());

        try {
            ReservationResult result = writes.execute(status -> {
                SessionEntity existing = sessions.findById(sessionId).orElse(null);
                if (existing != null) {
                    requireExact(existing, spec);
                    return new ReservationResult(sessionId, ReservationDisposition.EXACT_REPLAY);
                }
                inserts.insertAndFlush(newSession(sessionId, spec));
                return new ReservationResult(sessionId, ReservationDisposition.CREATED);
            });
            return Objects.requireNonNull(result, "reservation transaction returned no result");
        } catch (RuntimeException duplicate) {
            if (!isIntegrityViolation(duplicate)) {
                throw duplicate;
            }
            ReservationResult recovered = reads.execute(status -> sessions.findById(sessionId)
                    .map(existing -> {
                        requireExact(existing, spec);
                        return new ReservationResult(
                                sessionId, ReservationDisposition.EXACT_REPLAY);
                    })
                    .orElse(null));
            if (recovered != null) {
                return recovered;
            }
            throw duplicate;
        }
    }

    private static boolean isIntegrityViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static String deriveSessionId(String canonicalClientRequestId,
                                  String ownerUserId,
                                  String tenantId) {
        MessageDigest digest = sha256();
        put(digest, ID_DOMAIN);
        put(digest, requireReference(ownerUserId, 64, "ownerUserId", false));
        put(digest, normalizeOptional(tenantId, 64, "tenantId"));
        put(digest, canonicalUuid(canonicalClientRequestId));
        String hex = HexFormat.of().formatHex(digest.digest());
        return ID_PREFIX + hex.substring(0, ID_DIGEST_CHARS);
    }

    private static SessionEntity newSession(String sessionId, ReservationSpec spec) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(spec.ownerUserId());
        session.setTenantId(spec.tenantId());
        session.setAgentId(spec.logicalAgentId());
        if (ProviderRouteRegistry.isKnownProviderType(spec.logicalAgentId())) {
            session.setProviderType(spec.logicalAgentId());
            session.setBindingSource("EXPLICIT_AGENT");
        }
        session.setParentSessionId(spec.rootParentSessionId());
        session.setTitle(spec.title());
        session.setStatus("ACTIVE");
        session.setInteractionState("PROCESSING");
        session.setCurrentDirectoryId(spec.directoryId());
        session.setMilestoneId(spec.milestoneId());
        session.setLatestModel(spec.model());
        session.setLastActivityAt(LocalDateTime.now());
        return session;
    }

    private static void requireExact(SessionEntity actual, ReservationSpec expected) {
        boolean activeRow = actual.getDeletedAt() == null
                && !"DELETED".equals(actual.getStatus());
        boolean exact = activeRow
                && Objects.equals(actual.getUserId(), expected.ownerUserId())
                && Objects.equals(normalizeTenant(actual.getTenantId()), expected.tenantId())
                && Objects.equals(actual.getAgentId(), expected.logicalAgentId())
                && Objects.equals(actual.getParentSessionId(), expected.rootParentSessionId())
                && Objects.equals(actual.getTitle(), expected.title())
                && Objects.equals(actual.getCurrentDirectoryId(), expected.directoryId())
                && Objects.equals(actual.getMilestoneId(), expected.milestoneId())
                && Objects.equals(actual.getLatestModel(), expected.model());
        if (!exact) {
            throw new SessionReservationConflictException(CONFLICT_CODE);
        }
        if (ProviderRouteRegistry.isKnownProviderType(expected.logicalAgentId())
                && (!Objects.equals(actual.getProviderType(), expected.logicalAgentId())
                || !Objects.equals(actual.getBindingSource(), "EXPLICIT_AGENT"))) {
            throw new SessionReservationConflictException(CONFLICT_CODE);
        }
    }

    private static TransactionTemplate requiresNew(
            PlatformTransactionManager transactionManager,
            boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    private static String canonicalUuid(String value) {
        String normalized = requireReference(value, 64, "clientRequestId", false).toLowerCase();
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

    private static String normalizeTenant(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeOptional(String value, int maxLength, String field) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        return normalized == null ? null : requireReference(normalized, maxLength, field, true);
    }

    private static String requireReference(
            String value,
            int maxLength,
            String field,
            boolean allowNull) {
        if (value == null) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException(field + " must not be null");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ReservationSpec(
            String ownerUserId,
            String tenantId,
            String logicalAgentId,
            String rootParentSessionId,
            String title,
            String directoryId,
            String milestoneId,
            String model) {

        public ReservationSpec {
            ownerUserId = requireReference(ownerUserId, 64, "ownerUserId", false);
            tenantId = normalizeOptional(tenantId, 64, "tenantId");
            logicalAgentId = normalizeOptional(logicalAgentId, 64, "logicalAgentId");
            rootParentSessionId = requireReference(
                    rootParentSessionId, 64, "rootParentSessionId", false);
            title = requireReference(title, 256, "title", false);
            directoryId = normalizeOptional(directoryId, 64, "directoryId");
            milestoneId = normalizeOptional(milestoneId, 64, "milestoneId");
            model = normalizeOptional(model, 128, "model");
        }
    }

    public record ReservationResult(String sessionId, ReservationDisposition disposition) {
    }

    public enum ReservationDisposition {
        CREATED,
        EXACT_REPLAY
    }

    public static final class SessionReservationConflictException extends IllegalStateException {
        private SessionReservationConflictException(String safeCode) {
            super(safeCode);
        }
    }
}
