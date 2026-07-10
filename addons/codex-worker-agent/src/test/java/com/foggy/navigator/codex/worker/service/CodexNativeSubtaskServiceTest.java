package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskUpdatePayload;
import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexNativeSubtaskServiceTest {

    private NativeSubtaskStateRepository repository;
    private CodexTaskRepository taskRepository;
    private CodexNativeSubtaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(NativeSubtaskStateRepository.class);
        taskRepository = mock(CodexTaskRepository.class);
        service = new CodexNativeSubtaskService(repository, taskRepository);
        when(taskRepository.findByTaskIdForUpdate("task-1"))
                .thenReturn(Optional.of(new CodexTaskEntity()));
        when(repository.save(any(NativeSubtaskStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void appliesCompleteSnapshotForNewSubtask() {
        NativeSubtaskUpdatePayload update = update("child-1", "running", 1);
        update.setLabel("reviewer");
        update.setRole("review");
        update.setActivity("started");
        update.setStartedAt("2026-07-10T01:00:00Z");
        update.setUpdatedAt("2026-07-10T01:00:01Z");
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-1"))
                .thenReturn(Optional.empty());

        NativeSubtaskSnapshotDTO result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 10, update).orElseThrow();

        assertEquals("child-1", result.getSubtaskId());
        assertEquals("running", result.getStatus());
        assertEquals(Instant.parse("2026-07-10T01:00:00Z"), result.getStartedAt());
        assertEquals(Instant.parse("2026-07-10T01:00:01Z"), result.getUpdatedAt());
        assertNull(result.getCompletedAt());
        assertEquals(10, result.getLastEventSeq());
        assertEquals("reviewer", result.getLabel());
        assertEquals("review", result.getRole());
        assertEquals("started", result.getActivity());
    }

    @Test
    void dropsUnsafeProviderDisplayMetadataBeforePersistence() {
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-secret"))
                .thenReturn(Optional.empty());
        NativeSubtaskUpdatePayload update = update("child-secret", "running", 1);
        update.setLabel("C:\\Users\\name\\.codex\\auth.json");
        update.setRole("Bearer sk-provider-secret");
        update.setActivity("raw child prompt");

        NativeSubtaskSnapshotDTO result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 23, update).orElseThrow();

        assertNull(result.getLabel());
        assertNull(result.getRole());
        assertNull(result.getActivity());
    }

    @Test
    void ignoresOlderSnapshotWithoutWriting() {
        NativeSubtaskStateEntity existing = existing("child-1", "running", 20);
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-1"))
                .thenReturn(Optional.of(existing));

        Optional<NativeSubtaskSnapshotDTO> result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 19,
                update("child-1", "completed", 1));

        assertTrue(result.isEmpty());
        assertEquals("running", existing.getStatus());
        verify(repository, never()).save(any());
    }

    @Test
    void equalSequenceReplayReturnsStoredSnapshotWithoutWriting() {
        NativeSubtaskStateEntity existing = existing("child-1", "running", 20);
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-1"))
                .thenReturn(Optional.of(existing));

        NativeSubtaskSnapshotDTO result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 20,
                update("child-1", "completed", 1)).orElseThrow();

        assertEquals("running", result.getStatus());
        assertEquals(20, result.getLastEventSeq());
        verify(repository, never()).save(any());
    }

    @Test
    void newerTerminalSnapshotComputesDurationAndKeepsParentMapping() {
        NativeSubtaskStateEntity existing = existing("child-2", "running", 20);
        existing.setStartedAt(Instant.parse("2026-07-10T01:00:00Z"));
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-2"))
                .thenReturn(Optional.of(existing));
        NativeSubtaskUpdatePayload update = update("child-2", "completed", 2);
        update.setParentSubtaskId("child-1");
        update.setUpdatedAt("2026-07-10T01:00:05Z");
        update.setCompletedAt("2026-07-10T01:00:05Z");

        NativeSubtaskSnapshotDTO result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 21, update).orElseThrow();

        assertEquals("completed", result.getStatus());
        assertEquals("child-1", result.getParentSubtaskId());
        assertEquals(2, result.getDepth());
        assertEquals(5_000L, result.getDurationMs());
        assertEquals(Instant.parse("2026-07-10T01:00:05Z"), result.getCompletedAt());
    }

    @Test
    void replacesProviderFailureTextWithStableCodeBeforePersistence() {
        when(repository.findByTaskIdAndSubtaskId("task-1", "child-secret"))
                .thenReturn(Optional.empty());
        NativeSubtaskUpdatePayload update = update("child-secret", "failed", 1);
        update.setMessage("Bearer sk-provider-secret\nraw child output");

        NativeSubtaskSnapshotDTO result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 22, update).orElseThrow();

        assertEquals(CodexNativeSubtaskService.FAILURE_MESSAGE_CODE, result.getMessage());
    }

    @Test
    void rejectsUnsupportedContractOrStatus() {
        NativeSubtaskUpdatePayload wrongContract = update("child-1", "running", 1);
        wrongContract.setContractVersion(2);
        assertThrows(IllegalArgumentException.class, () -> service.applyUpdate(
                "task-1", "session-1", "codex-worker", 1, wrongContract));

        NativeSubtaskUpdatePayload wrongStatus = update("child-1", "unknown", 1);
        assertThrows(IllegalArgumentException.class, () -> service.applyUpdate(
                "task-1", "session-1", "codex-worker", 1, wrongStatus));
        verify(repository, never()).save(any());
    }

    @Test
    void ignoresLateUpdateAfterParentTaskWasDeleted() {
        when(taskRepository.findByTaskIdForUpdate("task-1")).thenReturn(Optional.empty());

        Optional<NativeSubtaskSnapshotDTO> result = service.applyUpdate(
                "task-1", "session-1", "codex-worker", 30,
                update("child-late", "running", 1));

        assertTrue(result.isEmpty());
        verify(repository, never()).findByTaskIdAndSubtaskId(any(), any());
        verify(repository, never()).save(any());
    }

    private NativeSubtaskUpdatePayload update(String subtaskId, String status, int depth) {
        NativeSubtaskUpdatePayload update = new NativeSubtaskUpdatePayload();
        update.setContractVersion(1);
        update.setSubtaskId(subtaskId);
        update.setStatus(status);
        update.setDepth(depth);
        return update;
    }

    private NativeSubtaskStateEntity existing(String subtaskId, String status, int seq) {
        NativeSubtaskStateEntity entity = new NativeSubtaskStateEntity();
        entity.setTaskId("task-1");
        entity.setSessionId("session-1");
        entity.setProviderType("codex-worker");
        entity.setSubtaskId(subtaskId);
        entity.setDepth(1);
        entity.setStatus(status);
        entity.setContractVersion(1);
        entity.setLastEventSeq(seq);
        entity.setEventUpdatedAt(Instant.parse("2026-07-10T01:00:00Z"));
        return entity;
    }
}
