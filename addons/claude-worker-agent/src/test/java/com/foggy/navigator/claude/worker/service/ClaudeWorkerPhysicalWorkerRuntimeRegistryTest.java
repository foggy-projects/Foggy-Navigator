package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeWorkerPhysicalWorkerRuntimeRegistryTest {

    @Test
    void resolve_allowsOnlineTenantWorkerWithoutBackendLock() {
        ClaudeWorkerRepository repository = mock(ClaudeWorkerRepository.class);
        ClaudeWorkerPhysicalWorkerRuntimeRegistry registry =
                new ClaudeWorkerPhysicalWorkerRuntimeRegistry(repository);
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker("tenant-1", "ONLINE")));

        Optional<ResolvedPhysicalWorker> result = registry.resolve("tenant-1", "system-1", "worker-1");

        assertTrue(result.isPresent());
        assertEquals("worker-1", result.get().workerId());
        assertNull(result.get().workerBackend());
        assertEquals(ResourceOwnerType.PLATFORM, result.get().ownerType());
        assertEquals("TENANT:tenant-1", result.get().ownerId());
        assertEquals("CLAUDE_WORKER:TENANT", result.get().source());
    }

    @Test
    void resolve_rejectsDifferentTenantWorker() {
        ClaudeWorkerRepository repository = mock(ClaudeWorkerRepository.class);
        ClaudeWorkerPhysicalWorkerRuntimeRegistry registry =
                new ClaudeWorkerPhysicalWorkerRuntimeRegistry(repository);
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker("tenant-2", "ONLINE")));

        assertThrows(SecurityException.class, () -> registry.resolve("tenant-1", "system-1", "worker-1"));
    }

    @Test
    void resolve_rejectsOfflineWorker() {
        ClaudeWorkerRepository repository = mock(ClaudeWorkerRepository.class);
        ClaudeWorkerPhysicalWorkerRuntimeRegistry registry =
                new ClaudeWorkerPhysicalWorkerRuntimeRegistry(repository);
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker("tenant-1", "OFFLINE")));

        assertThrows(IllegalStateException.class, () -> registry.resolve("tenant-1", "system-1", "worker-1"));
    }

    private ClaudeWorkerEntity worker(String tenantId, String status) {
        ClaudeWorkerEntity entity = new ClaudeWorkerEntity();
        entity.setWorkerId("worker-1");
        entity.setTenantId(tenantId);
        entity.setStatus(status);
        return entity;
    }
}
