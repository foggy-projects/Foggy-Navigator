package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskUpdatePayload;
import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Maintains the latest state projection for Codex-native child threads. */
@Service
@RequiredArgsConstructor
public class CodexNativeSubtaskService {

    public static final int CONTRACT_VERSION = 1;
    public static final String FAILURE_MESSAGE_CODE = NativeSubtaskSnapshotDTO.FAILURE_MESSAGE_CODE;
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            "pending", "running", "completed", "failed", "interrupted");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "completed", "failed", "interrupted");
    private static final Set<String> SUPPORTED_ACTIVITIES = Set.of(
            "started", "interacted", "interrupted");
    private static final Pattern SAFE_DISPLAY_METADATA = Pattern.compile(
            "[\\p{L}\\p{N} ._-]{1,64}");
    private static final Pattern SENSITIVE_DISPLAY_METADATA = Pattern.compile(
            "(?i)(https?://|(?:^|\\s)(?:bearer|basic)\\s|\\bsk-[a-z0-9_-]{4,}|"
                    + "(?:token|secret|password|credential|authorization|api[-_ ]?key)|"
                    + "[a-z]:[\\\\/]|(?:^|\\s)[~/][^\\s]*)");

    private final NativeSubtaskStateRepository repository;
    private final CodexTaskRepository taskRepository;

    /**
     * Applies a complete state snapshot when it is not older than the stored one.
     * Equal-sequence replays return the existing snapshot so SSE delivery can be retried safely.
     */
    @Transactional
    public Optional<NativeSubtaskSnapshotDTO> applyUpdate(
            String taskId,
            String sessionId,
            String providerType,
            Integer eventSeq,
            NativeSubtaskUpdatePayload payload) {
        validateEnvelope(taskId, sessionId, providerType, eventSeq, payload);

        // Serialize first insert and later updates across relay instances. Locking the
        // parent also prevents a late Worker event from recreating state after deletion.
        if (taskRepository.findByTaskIdForUpdate(taskId).isEmpty()) {
            return Optional.empty();
        }

        String subtaskId = requireText(payload.getSubtaskId(), "subtask_id", 128);
        Optional<NativeSubtaskStateEntity> existing = repository.findByTaskIdAndSubtaskId(taskId, subtaskId);
        if (existing.isPresent()) {
            int comparison = Integer.compare(eventSeq, existing.get().getLastEventSeq());
            if (comparison < 0) {
                return Optional.empty();
            }
            if (comparison == 0) {
                return Optional.of(NativeSubtaskSnapshotDTO.fromEntity(existing.get()));
            }
        }

        NativeSubtaskStateEntity entity = existing.orElseGet(NativeSubtaskStateEntity::new);
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setProviderType(providerType);
        entity.setSubtaskId(subtaskId);
        entity.setParentSubtaskId(optionalIdentifier(payload.getParentSubtaskId(), "parent_subtask_id", 128));
        entity.setDepth(normalizeDepth(payload.getDepth()));
        entity.setLabel(optionalDisplayMetadata(payload.getLabel()));
        entity.setRole(optionalDisplayMetadata(payload.getRole()));

        String status = requireText(payload.getStatus(), "status", 32).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported native subtask status: " + status);
        }
        entity.setStatus(status);
        entity.setActivity(normalizeActivity(payload.getActivity()));
        // Never persist provider error text: it can contain prompts, filesystem
        // paths, tool output, or credentials. The projection stores a stable code.
        entity.setMessage("failed".equals(status) ? FAILURE_MESSAGE_CODE : null);
        entity.setDurationMs(nonNegative(payload.getDurationMs(), "duration_ms"));
        entity.setContractVersion(CONTRACT_VERSION);
        entity.setLastEventSeq(eventSeq);

        Instant updatedAt = parseInstant(payload.getUpdatedAt()).orElseGet(Instant::now);
        Instant startedAt = parseInstant(payload.getStartedAt()).orElse(entity.getStartedAt());
        if (startedAt == null) {
            startedAt = updatedAt;
        }
        entity.setStartedAt(startedAt);
        entity.setEventUpdatedAt(updatedAt);

        if (TERMINAL_STATUSES.contains(status)) {
            Instant completedAt = parseInstant(payload.getCompletedAt()).orElse(updatedAt);
            entity.setCompletedAt(completedAt);
            if (entity.getDurationMs() == null && !completedAt.isBefore(startedAt)) {
                entity.setDurationMs(Duration.between(startedAt, completedAt).toMillis());
            }
        } else {
            entity.setCompletedAt(null);
        }

        return Optional.of(NativeSubtaskSnapshotDTO.fromEntity(repository.save(entity)));
    }

    private void validateEnvelope(String taskId, String sessionId, String providerType,
                                  Integer eventSeq, NativeSubtaskUpdatePayload payload) {
        requireText(taskId, "task_id", 64);
        requireText(sessionId, "session_id", 64);
        requireText(providerType, "provider_type", 32);
        if (eventSeq == null || eventSeq <= 0) {
            throw new IllegalArgumentException("native_subtask_update requires a positive seq");
        }
        if (payload == null || payload.getContractVersion() == null
                || payload.getContractVersion() != CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported native subtask contract_version");
        }
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("native_subtask_update requires " + field);
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }

    private String optionalIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, field, maxLength);
    }

    private String optionalDisplayMetadata(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!SAFE_DISPLAY_METADATA.matcher(normalized).matches()
                || SENSITIVE_DISPLAY_METADATA.matcher(normalized).find()) {
            return null;
        }
        return normalized;
    }

    private String normalizeActivity(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_ACTIVITIES.contains(normalized) ? normalized : null;
    }

    private int normalizeDepth(Integer depth) {
        if (depth == null) {
            return 1;
        }
        return Math.max(1, Math.min(depth, 32));
    }

    private Long nonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim();
        try {
            return Optional.of(Instant.parse(normalized));
        } catch (DateTimeParseException ignored) {
            try {
                return Optional.of(OffsetDateTime.parse(normalized).toInstant());
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    long epoch = Long.parseLong(normalized);
                    return Optional.of(epoch > 10_000_000_000L
                            ? Instant.ofEpochMilli(epoch)
                            : Instant.ofEpochSecond(epoch));
                } catch (NumberFormatException ignoredEpoch) {
                    throw new IllegalArgumentException("Invalid native subtask timestamp: " + value);
                }
            }
        }
    }
}
