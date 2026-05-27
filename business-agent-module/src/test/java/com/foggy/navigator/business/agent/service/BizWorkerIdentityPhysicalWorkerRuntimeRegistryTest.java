package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.worker.ResolvedPhysicalWorker;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BizWorkerIdentityPhysicalWorkerRuntimeRegistryTest {

    @Test
    void resolve_allowsUpstreamSystemOwnedHealthyWorker() {
        BizWorkerIdentityRepository repository = mock(BizWorkerIdentityRepository.class);
        BizWorkerIdentityPhysicalWorkerRuntimeRegistry registry =
                new BizWorkerIdentityPhysicalWorkerRuntimeRegistry(repository);
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "system-1",
                "LANGGRAPH_BIZ",
                BizWorkerPoolService.STATUS_ENABLED,
                BizWorkerPoolService.HEALTHY)));

        Optional<ResolvedPhysicalWorker> result = registry.resolve("tenant-1", "system-1", "worker-1");

        assertTrue(result.isPresent());
        assertEquals("worker-1", result.get().workerId());
        assertEquals("LANGGRAPH_BIZ", result.get().workerBackend());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.get().ownerType());
        assertEquals("BIZ_WORKER_IDENTITY", result.get().source());
    }

    @Test
    void resolve_rejectsOtherUpstreamSystemWorker() {
        BizWorkerIdentityRepository repository = mock(BizWorkerIdentityRepository.class);
        BizWorkerIdentityPhysicalWorkerRuntimeRegistry registry =
                new BizWorkerIdentityPhysicalWorkerRuntimeRegistry(repository);
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "system-2",
                "LANGGRAPH_BIZ",
                BizWorkerPoolService.STATUS_ENABLED,
                BizWorkerPoolService.HEALTHY)));

        assertThrows(SecurityException.class, () -> registry.resolve("tenant-1", "system-1", "worker-1"));
    }

    private BizWorkerIdentityEntity worker(ResourceOwnerType ownerType,
                                           String ownerId,
                                           String workerBackend,
                                           String status,
                                           String healthStatus) {
        BizWorkerIdentityEntity entity = new BizWorkerIdentityEntity();
        entity.setWorkerId("worker-1");
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        entity.setWorkerBackend(workerBackend);
        entity.setStatus(status);
        entity.setHealthStatus(healthStatus);
        return entity;
    }
}
