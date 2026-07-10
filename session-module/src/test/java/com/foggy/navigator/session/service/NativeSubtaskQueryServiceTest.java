package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotResponseDTO;
import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeSubtaskQueryServiceTest {

    @Test
    void returnsLatestSnapshotForTask() {
        NativeSubtaskStateRepository repository = mock(NativeSubtaskStateRepository.class);
        NativeSubtaskQueryService service = new NativeSubtaskQueryService(repository);
        NativeSubtaskStateEntity first = subtask(1L, "child-1", 4);
        NativeSubtaskStateEntity second = subtask(2L, "child-2", 9);
        second.setStatus("failed");
        second.setMessage("Bearer sk-provider-secret raw child output");
        when(repository.findByTaskIdOrderByIdAsc("task-1")).thenReturn(List.of(first, second));
        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .providerType("codex-worker")
                .build();

        NativeSubtaskSnapshotResponseDTO result = service.getSnapshot(task);

        assertEquals(1, result.getContractVersion());
        assertEquals("task-1", result.getTaskId());
        assertEquals("session-1", result.getSessionId());
        assertEquals("codex-worker", result.getProviderType());
        assertEquals(9, result.getLatestEventSeq());
        assertEquals(List.of("child-1", "child-2"), result.getSubtasks().stream()
                .map(subtask -> subtask.getSubtaskId())
                .toList());
        assertEquals(NativeSubtaskSnapshotDTO.FAILURE_MESSAGE_CODE,
                result.getSubtasks().get(1).getMessage());
    }

    private NativeSubtaskStateEntity subtask(long id, String subtaskId, int seq) {
        NativeSubtaskStateEntity entity = new NativeSubtaskStateEntity();
        entity.setId(id);
        entity.setTaskId("task-1");
        entity.setSessionId("session-1");
        entity.setProviderType("codex-worker");
        entity.setSubtaskId(subtaskId);
        entity.setDepth(1);
        entity.setStatus("running");
        entity.setContractVersion(1);
        entity.setLastEventSeq(seq);
        return entity;
    }
}
