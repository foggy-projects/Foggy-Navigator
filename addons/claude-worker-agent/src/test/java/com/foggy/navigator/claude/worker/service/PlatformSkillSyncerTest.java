package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSkillSyncerTest {

    @Test
    void syncWorkerSkills_requestsWorkerLocalReconciliationWithoutSkillContent() {
        ClaudeWorkerRepository workerRepository = mock(ClaudeWorkerRepository.class);
        ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
        ClaudeWorkerClient client = mock(ClaudeWorkerClient.class);
        ClaudeWorkerEntity worker = mock(ClaudeWorkerEntity.class);

        when(workerRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));
        when(workerService.createClient(worker)).thenReturn(client);
        when(client.deploySkills(Collections.emptyMap()))
                .thenReturn(Mono.just(Map.of("deployed", Collections.emptyList())));

        new PlatformSkillSyncer(workerRepository, workerService).syncWorkerSkills("worker-1");

        verify(client).deploySkills(Collections.emptyMap());
    }
}
