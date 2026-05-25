package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanggraphWorkerServiceTest {

    @Mock
    private LanggraphWorkerRepository workerRepository;

    @Mock
    private BizWorkerIdentityRepository workerIdentityRepository;

    private LanggraphWorkerService service;

    @BeforeEach
    void setUp() {
        service = new LanggraphWorkerService(workerRepository);
    }

    @Test
    void resolveTaskWorkerIdReturnsPreferredWorkerWhenRegistered() {
        when(workerRepository.findByWorkerId("worker_01"))
                .thenReturn(Optional.of(worker("worker_01", "UNKNOWN")));

        assertEquals("worker_01", service.resolveTaskWorkerId("worker_01"));
    }

    @Test
    void resolveTaskWorkerIdFallsBackToOnlyRegisteredWorkerForMissingPreferredWorker() {
        when(workerRepository.findByWorkerId("old_pool_id")).thenReturn(Optional.empty());
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(worker("worker_01", "UNKNOWN")));

        assertEquals("worker_01", service.resolveTaskWorkerId("old_pool_id"));
    }

    @Test
    void resolveDefaultWorkerUsesConfiguredWorkerId() {
        ReflectionTestUtils.setField(service, "defaultWorkerId", "worker_cfg");
        when(workerRepository.findByWorkerId("worker_cfg"))
                .thenReturn(Optional.of(worker("worker_cfg", "ONLINE")));

        assertEquals("worker_cfg", service.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void resolveDefaultWorkerUsesSingleOnlineWorkerWhenMultipleWorkersExist() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(
                worker("worker_01", "OFFLINE"),
                worker("worker_02", "ONLINE")));

        assertEquals("worker_02", service.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void resolveDefaultWorkerRejectsMissingWorker() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDefaultWorker());

        assertTrue(error.getMessage().contains("No LangGraph BizWorker"));
    }

    @Test
    void resolveDefaultWorkerRejectsMultipleWorkersWithoutUniqueOnlineWorker() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(
                worker("worker_01", "UNKNOWN"),
                worker("worker_02", "UNKNOWN")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDefaultWorker());

        assertTrue(error.getMessage().contains("Multiple LangGraph BizWorkers"));
    }

    @Test
    void getWorkerEntityReturnsIdentityBackedWorkerWhenLegacyEntityIsMissing() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.empty());
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        LanggraphWorkerEntity worker = serviceWithIdentity.getWorkerEntity("biz_worker_01");

        assertEquals("biz_worker_01", worker.getWorkerId());
        assertEquals("http://127.0.0.1:3161", worker.getBaseUrl());
        assertEquals("IDENTITY", worker.getAuthMode());
        assertEquals("ONLINE", worker.getStatus());
    }

    @Test
    void resolveTaskWorkerIdReturnsPreferredIdentityWorkerWhenLegacyEntityIsMissing() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.empty());
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertEquals("biz_worker_01", serviceWithIdentity.resolveTaskWorkerId("biz_worker_01"));
    }

    @Test
    void resolveDefaultWorkerSupportsConfiguredIdentityWorkerId() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        ReflectionTestUtils.setField(serviceWithIdentity, "defaultWorkerId", "biz_worker_01");
        when(workerRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.empty());
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertEquals("biz_worker_01", serviceWithIdentity.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void getWorkerEntityRejectsDisabledIdentityBackedWorker() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(workerRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.empty());
        when(workerIdentityRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.of(identity));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> serviceWithIdentity.getWorkerEntity("biz_worker_01"));

        assertTrue(error.getMessage().contains("disabled"));
    }

    private LanggraphWorkerEntity worker(String workerId, String status) {
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setStatus(status);
        return worker;
    }

    private BizWorkerIdentityEntity identity(String workerId, String workerBackend) {
        BizWorkerIdentityEntity identity = new BizWorkerIdentityEntity();
        identity.setWorkerId(workerId);
        identity.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        identity.setOwnerId("ups_01");
        identity.setWorkerBackend(workerBackend);
        identity.setBaseUrl("http://127.0.0.1:3161");
        identity.setVersion("1.0.8");
        identity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        identity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return identity;
    }
}
