package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotDTO;
import com.foggy.navigator.common.dto.NativeSubtaskSnapshotResponseDTO;
import com.foggy.navigator.common.entity.NativeSubtaskStateEntity;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read-only access to provider-native execution subtasks. */
@Service
@RequiredArgsConstructor
public class NativeSubtaskQueryService {

    private static final int CONTRACT_VERSION = 1;
    private final NativeSubtaskStateRepository repository;

    @Transactional(readOnly = true)
    public NativeSubtaskSnapshotResponseDTO getSnapshot(DispatchTaskDTO task) {
        List<NativeSubtaskStateEntity> entities = repository.findByTaskIdOrderByIdAsc(task.getTaskId());
        List<NativeSubtaskSnapshotDTO> subtasks = entities.stream()
                .map(NativeSubtaskSnapshotDTO::fromEntity)
                .toList();
        int latestEventSeq = entities.stream()
                .map(NativeSubtaskStateEntity::getLastEventSeq)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);

        return NativeSubtaskSnapshotResponseDTO.builder()
                .contractVersion(CONTRACT_VERSION)
                .taskId(task.getTaskId())
                .sessionId(task.getSessionId())
                .providerType(task.getProviderType())
                .latestEventSeq(latestEventSeq)
                .subtasks(subtasks)
                .build();
    }
}
