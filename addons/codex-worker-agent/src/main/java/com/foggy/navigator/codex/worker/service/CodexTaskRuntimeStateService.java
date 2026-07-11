package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.util.ProviderStateCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodexTaskRuntimeStateService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CodexTaskRepository taskRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Nullable
    private SessionTaskRepository sessionTaskRepository;

    @Transactional
    public void prepareAcceptance(String taskId, Map<String, Object> requestBody) {
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (isTerminal(task.getStatus()) || isAcceptanceCancelled(task.getRuntimeAcceptanceState())) {
            throw new AcceptanceCancelledException();
        }
        String requestJson = writeRequest(requestBody);
        String requestHash = sha256(requestJson);
        if (task.getRuntimeRequestHash() != null && !task.getRuntimeRequestHash().equals(requestHash)) {
            throw new IllegalStateException("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: request payload changed for " + taskId);
        }
        task.setRuntimeRequestHash(requestHash);
        task.setRuntimeRequestCiphertext(credentialEncryptor.encrypt(requestJson));
        if (task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank()) {
            task.setRuntimeAcceptanceState("ACCEPTING");
        }
        taskRepository.saveAndFlush(task);
        syncRuntimeAcceptanceState(task);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> loadPreparedRequest(String taskId) {
        CodexTaskEntity task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireAppServer(task);
        if (task.getRuntimeRequestCiphertext() == null || task.getRuntimeRequestCiphertext().isBlank()) {
            throw new IllegalStateException("CODEX_RUNTIME_REQUEST_MISSING: no recoverable request for " + taskId);
        }
        try {
            String json = credentialEncryptor.decrypt(task.getRuntimeRequestCiphertext());
            Map<String, Object> request = objectMapper.readValue(json, MAP_TYPE);
            if (!sha256(objectMapper.writeValueAsString(request)).equals(task.getRuntimeRequestHash())) {
                throw new IllegalStateException("request hash mismatch");
            }
            return request;
        } catch (Exception e) {
            throw new IllegalStateException("CODEX_RUNTIME_REQUEST_INVALID: cannot recover request for " + taskId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccepted(String taskId, String workerTaskId) {
        if (workerTaskId == null || workerTaskId.isBlank()) {
            throw new IllegalArgumentException("workerTaskId is required after app-server acceptance");
        }
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (task.getWorkerTaskId() != null && !task.getWorkerTaskId().equals(workerTaskId)) {
            throw new IllegalStateException("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT: worker task id changed for " + taskId);
        }
        if (isTerminal(task.getStatus()) || "ABORTED_BEFORE_ACCEPT".equals(task.getRuntimeAcceptanceState())) {
            throw new AcceptanceCancelledException();
        }
        task.setWorkerTaskId(workerTaskId);
        if (!"ABORT_REQUESTED".equals(task.getRuntimeAcceptanceState())) {
            task.setRuntimeAcceptanceState("ACCEPTED");
        }
        taskRepository.saveAndFlush(task);
        syncRuntimeAcceptanceState(task);
        log.info("Persisted Codex app-server acceptance: taskId={}, workerTaskId={}, runtime={}@{}",
                taskId, workerTaskId, task.getRuntimeId(), task.getRuntimeRevision());
    }

    @Transactional
    public boolean markSubscribed(String taskId) {
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (isTerminal(task.getStatus())
                || "ABORT_REQUESTED".equals(task.getRuntimeAcceptanceState())
                || "ABORTED_BEFORE_ACCEPT".equals(task.getRuntimeAcceptanceState())) {
            return false;
        }
        if (task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank()) {
            throw new IllegalStateException("Cannot subscribe before workerTaskId is persisted");
        }
        if (!"COMMITTED".equals(task.getRuntimeAcceptanceState())
                && !"TERMINAL".equals(task.getRuntimeAcceptanceState())) {
            task.setRuntimeAcceptanceState("SUBSCRIBED");
            taskRepository.save(task);
            syncRuntimeAcceptanceState(task);
        }
        return true;
    }

    @Transactional
    public void markAcceptanceUnknown(String taskId) {
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (!isTerminal(task.getStatus())
                && !"ABORT_REQUESTED".equals(task.getRuntimeAcceptanceState())
                && !"ABORTED_BEFORE_ACCEPT".equals(task.getRuntimeAcceptanceState())
                && (task.getWorkerTaskId() == null || task.getWorkerTaskId().isBlank())) {
            task.setRuntimeAcceptanceState("UNKNOWN");
            taskRepository.save(task);
            syncRuntimeAcceptanceState(task);
        }
    }

    /**
     * Claims cancellation before any remote abort/recovery call. PREPARED is the
     * only state that proves no POST can have started because prepareAcceptance
     * changes it while holding the same parent row lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AbortClaim claimAbort(String taskId) {
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (isTerminal(task.getStatus()) || "TERMINAL".equals(task.getRuntimeAcceptanceState())) {
            return AbortClaim.ALREADY_TERMINAL;
        }
        if ("PREPARED".equals(task.getRuntimeAcceptanceState())
                || "ABORTED_BEFORE_ACCEPT".equals(task.getRuntimeAcceptanceState())) {
            task.setRuntimeAcceptanceState("ABORTED_BEFORE_ACCEPT");
            taskRepository.saveAndFlush(task);
            syncRuntimeAcceptanceState(task);
            return AbortClaim.LOCAL_UNACCEPTED;
        }
        task.setRuntimeAcceptanceState("ABORT_REQUESTED");
        taskRepository.saveAndFlush(task);
        syncRuntimeAcceptanceState(task);
        return AbortClaim.REMOTE_REQUIRED;
    }

    @Transactional(readOnly = true)
    public boolean isAbortRequested(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .map(task -> "ABORT_REQUESTED".equals(task.getRuntimeAcceptanceState()))
                .orElse(false);
    }

    /** Remote completion won the abort race; allow durable terminal-event replay. */
    @Transactional
    public void allowTerminalReplay(String taskId) {
        CodexTaskEntity task = requireForUpdate(taskId);
        requireAppServer(task);
        if (!isTerminal(task.getStatus()) && task.getWorkerTaskId() != null
                && !task.getWorkerTaskId().isBlank()
                && "ABORT_REQUESTED".equals(task.getRuntimeAcceptanceState())) {
            task.setRuntimeAcceptanceState("SUBSCRIBED");
            taskRepository.save(task);
            syncRuntimeAcceptanceState(task);
        }
    }

    /** Claims a terminal app-server task for remote tombstone cleanup. */
    @Transactional
    public CodexTaskEntity claimTerminalDeletion(String taskId, String userId) {
        CodexTaskEntity task = taskRepository.findByTaskIdAndUserIdForUpdate(taskId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        requireAppServer(task);
        if (!isTerminal(task.getStatus())) {
            throw new IllegalStateException(
                    "Cannot delete a non-terminal app-server task. Please abort it first.");
        }
        task.setRuntimeAcceptanceState("DELETE_REQUESTED");
        CodexTaskEntity saved = taskRepository.saveAndFlush(task);
        syncRuntimeAcceptanceState(saved);
        return saved;
    }

    private void syncRuntimeAcceptanceState(CodexTaskEntity task) {
        if (sessionTaskRepository == null || task.getTaskId() == null || task.getTaskId().isBlank()) {
            return;
        }
        sessionTaskRepository.findByTaskIdForUpdate(task.getTaskId()).ifPresent(projection -> {
            projection.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                    projection.getTaskStateJson(),
                    task.getProviderType(),
                    ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE,
                    task.getRuntimeAcceptanceState()));
            sessionTaskRepository.save(projection);
        });
    }

    private CodexTaskEntity requireForUpdate(String taskId) {
        return taskRepository.findByTaskIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private void requireAppServer(CodexTaskEntity task) {
        if (!"APP_SERVER".equals(task.getRuntimeType())) {
            throw new IllegalStateException("Task is not bound to APP_SERVER: " + task.getTaskId());
        }
    }

    private boolean isAcceptanceCancelled(String state) {
        return "ABORT_REQUESTED".equals(state) || "ABORTED_BEFORE_ACCEPT".equals(state);
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status);
    }

    private String writeRequest(Map<String, Object> requestBody) {
        if (requestBody == null || requestBody.isEmpty()) {
            throw new IllegalArgumentException("requestBody is required");
        }
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize app-server request", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum AbortClaim {
        LOCAL_UNACCEPTED,
        REMOTE_REQUIRED,
        ALREADY_TERMINAL
    }

    public static final class AcceptanceCancelledException extends IllegalStateException {
        private AcceptanceCancelledException() {
            super("CODEX_RUNTIME_ACCEPTANCE_CANCELLED");
        }
    }
}
