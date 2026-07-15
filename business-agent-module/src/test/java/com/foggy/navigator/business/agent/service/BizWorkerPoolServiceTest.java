package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BizWorkerIdentityDTO;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPoolDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm;
import com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm;
import com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BizWorkerPoolServiceTest {

    private BizWorkerIdentityRepository identityRepository;
    private BizWorkerPoolRepository poolRepository;
    private BizWorkerPoolMemberRepository memberRepository;
    private BizWorkerPoolService service;

    @BeforeEach
    void setUp() {
        identityRepository = mock(BizWorkerIdentityRepository.class);
        poolRepository = mock(BizWorkerPoolRepository.class);
        memberRepository = mock(BizWorkerPoolMemberRepository.class);
        service = new BizWorkerPoolService(identityRepository, poolRepository, memberRepository);

        when(identityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(poolRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerWorkerIdentity_creates_enabled_healthy_worker() {
        RegisterWorkerIdentityForm form = new RegisterWorkerIdentityForm();
        form.setWorkerId("worker-1");
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setBaseUrl("http://worker");
        form.setIdentityToken("token");

        BizWorkerIdentityDTO dto = service.registerWorkerIdentity(form);

        assertEquals("worker-1", dto.getWorkerId());
        assertEquals(ResourceOwnerType.PLATFORM, dto.getOwnerType());
        assertEquals(BizWorkerPoolService.PLATFORM_OWNER_ID, dto.getOwnerId());
        assertEquals(BizWorkerPoolService.STATUS_ENABLED, dto.getStatus());
        assertEquals(BizWorkerPoolService.HEALTHY, dto.getHealthStatus());
        assertEquals(0, dto.getCredentialVersion());
        ArgumentCaptor<BizWorkerIdentityEntity> saved = ArgumentCaptor.forClass(BizWorkerIdentityEntity.class);
        verify(identityRepository).save(saved.capture());
        assertEquals(SecretTokenSupport.sha256("token"), saved.getValue().getTokenHash());
        assertNull(saved.getValue().getCredentialExpiresAt());
    }

    @Test
    void registerWorkerIdentity_withUpstreamSystemOwner_setsOwner() {
        RegisterWorkerIdentityForm form = new RegisterWorkerIdentityForm();
        form.setWorkerId("worker-1");
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setBaseUrl("http://worker");

        BizWorkerIdentityDTO dto = service.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                form);

        assertEquals("worker-1", dto.getWorkerId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, dto.getOwnerType());
        assertEquals("ups-1", dto.getOwnerId());
    }

    @Test
    void registerWorkerIdentity_rejectsExistingWorkerOwnedByAnotherUpstream() {
        BizWorkerIdentityEntity existing = worker(
                "LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        existing.setBaseUrl("http://original-worker");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(existing));

        RegisterWorkerIdentityForm form = workerIdentityForm(
                "worker-1", "LANGGRAPH_BIZ", "http://attacker-worker");

        assertThrows(SecurityException.class, () -> service.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2", form));

        assertEquals("ups-1", existing.getOwnerId());
        assertEquals("http://original-worker", existing.getBaseUrl());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void registerWorkerIdentity_rejectsExistingWorkerBackendChange() {
        BizWorkerIdentityEntity existing = worker(
                "LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(existing));

        RegisterWorkerIdentityForm form = workerIdentityForm(
                "worker-1", "OPENAI_CODEX", "http://worker");

        assertThrows(IllegalArgumentException.class, () -> service.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", form));

        assertEquals("LANGGRAPH_BIZ", existing.getWorkerBackend());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void registerWorkerIdentity_sameOwnerUpdatePreservesStatusAndHealth() {
        BizWorkerIdentityEntity existing = worker(
                "LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        existing.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        existing.setHealthStatus(BizWorkerPoolService.UNHEALTHY);
        existing.setBaseUrl("http://old-worker");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(existing));

        RegisterWorkerIdentityForm form = workerIdentityForm(
                "worker-1", "LANGGRAPH_BIZ", "http://new-worker");

        BizWorkerIdentityDTO result = service.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", form);

        assertEquals("http://new-worker", result.getBaseUrl());
        assertEquals(BizWorkerPoolService.STATUS_DISABLED, result.getStatus());
        assertEquals(BizWorkerPoolService.UNHEALTHY, result.getHealthStatus());
    }

    @Test
    void registerWorkerIdentity_cannotOverwriteModernCredentialWithLegacyIdentityToken() {
        BizWorkerIdentityEntity existing = worker(
                "LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        existing.setCredentialVersion(2);
        existing.setTokenHash("modern-hash");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(existing));
        RegisterWorkerIdentityForm form = workerIdentityForm(
                "worker-1", "LANGGRAPH_BIZ", "http://worker");
        form.setIdentityToken("attacker-selected-token");

        assertThrows(IllegalStateException.class, () -> service.registerWorkerIdentity(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", form));

        assertEquals(2, existing.getCredentialVersion());
        assertEquals("modern-hash", existing.getTokenHash());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void registerWorkerIdentity_rejectsClientAppOwner() {
        RegisterWorkerIdentityForm form = new RegisterWorkerIdentityForm();
        form.setWorkerId("worker-1");
        form.setWorkerBackend("LANGGRAPH_BIZ");
        form.setBaseUrl("http://worker");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerWorkerIdentity(ResourceOwnerType.CLIENT_APP, "capp-1", form));

        verify(identityRepository, never()).save(any());
    }

    @Test
    void registerWorkerIdentity_rejectsRouteIdAlreadyUsedByPool() {
        when(poolRepository.findByPoolId("shared-route"))
                .thenReturn(Optional.of(pool("tenant-1")));
        RegisterWorkerIdentityForm form = workerIdentityForm(
                "shared-route", "LANGGRAPH_BIZ", "http://worker");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.registerWorkerIdentity(form));

        assertEquals("worker route id is already used by a worker pool", failure.getMessage());
        verify(identityRepository, never()).save(any());
    }

    @Test
    void createPool_rejects_duplicate_pool_id() {
        when(poolRepository.findByPoolId("pool-1")).thenReturn(Optional.of(pool("tenant-1")));

        assertThrows(IllegalArgumentException.class,
                () -> service.createPool("tenant-1", createPoolForm("pool-1")));
    }

    @Test
    void createPool_rejectsRouteIdAlreadyUsedByWorkerIdentity() {
        when(identityRepository.findByWorkerId("shared-route"))
                .thenReturn(Optional.of(worker("LANGGRAPH_BIZ")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.createPool("tenant-1", createPoolForm("shared-route")));

        assertEquals("worker route id is already used by a worker identity", failure.getMessage());
        verify(poolRepository, never()).save(any());
    }

    @Test
    void addMember_rejects_backend_mismatch() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        BizWorkerIdentityEntity worker = worker("PYTHON_OTHER");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));
    }

    @Test
    void addMember_rejects_duplicate_member() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker("LANGGRAPH_BIZ")));
        when(memberRepository.findByPoolIdAndWorkerId("pool-1", "worker-1"))
                .thenReturn(Optional.of(new BizWorkerPoolMemberEntity()));

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));
    }

    @Test
    void addMember_normalizesPoolIdForLookupDuplicateCheckAndSave() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        when(identityRepository.findByWorkerId("worker-1"))
                .thenReturn(Optional.of(worker("LANGGRAPH_BIZ")));

        service.addMember("tenant-1", "  pool-1  ", addMemberForm("worker-1"));

        verify(poolRepository).findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1");
        verify(memberRepository).findByPoolIdAndWorkerId("pool-1", "worker-1");
        ArgumentCaptor<BizWorkerPoolMemberEntity> memberCaptor =
                ArgumentCaptor.forClass(BizWorkerPoolMemberEntity.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertEquals("pool-1", memberCaptor.getValue().getPoolId());
    }

    @Test
    void requireAvailablePool_rejects_disabled_pool() {
        BizWorkerPoolEntity pool = pool("tenant-1");
        pool.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool));

        assertThrows(IllegalStateException.class,
                () -> service.requireAvailablePool("tenant-1", "pool-1"));
    }

    @Test
    void requireAvailablePool_rejectsPoolOwnedByAnotherUpstreamSystem() {
        BizWorkerPoolEntity ownerBPool = pool(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-b");
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(ownerBPool));
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.requireAvailablePool(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a", "pool-1"));

        verify(poolRepository).findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a");
        verify(poolRepository, never()).findByPoolIdAndTenantId(anyString(), anyString());
    }

    @Test
    void createPool_defaults_routing_policy() {
        BizWorkerPoolDTO dto = service.createPool("tenant-1", createPoolForm("pool-1"));

        assertEquals("ROUND_ROBIN", dto.getRoutingPolicy());
        assertEquals(ResourceOwnerType.PLATFORM, dto.getOwnerType());
        assertEquals("tenant-1", dto.getOwnerId());
        assertEquals(BizWorkerPoolService.STATUS_ENABLED, dto.getStatus());
    }

    @Test
    void createPool_normalizesPersistedLookupKeysAndTextFields() {
        CreateWorkerPoolForm form = createPoolForm("  pool-1  ");
        form.setName("  Default Pool  ");
        form.setWorkerBackend("  LANGGRAPH_BIZ  ");
        form.setRoutingPolicy("  ROUND_ROBIN  ");

        service.createPool("  tenant-1  ", form);

        verify(poolRepository).findByPoolId("pool-1");
        ArgumentCaptor<BizWorkerPoolEntity> saved =
                ArgumentCaptor.forClass(BizWorkerPoolEntity.class);
        verify(poolRepository).save(saved.capture());
        assertEquals("pool-1", saved.getValue().getPoolId());
        assertEquals("tenant-1", saved.getValue().getTenantId());
        assertEquals("tenant-1", saved.getValue().getOwnerId());
        assertEquals("Default Pool", saved.getValue().getName());
        assertEquals("LANGGRAPH_BIZ", saved.getValue().getWorkerBackend());
        assertEquals("ROUND_ROBIN", saved.getValue().getRoutingPolicy());
    }

    @Test
    void createPool_withUpstreamSystemOwner_setsOwner() {
        BizWorkerPoolDTO dto = service.createPool(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                createPoolForm("pool-1"));

        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, dto.getOwnerType());
        assertEquals("ups-1", dto.getOwnerId());
    }

    @Test
    void createPool_rejectsClientAppOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPool(
                        "tenant-1",
                        ResourceOwnerType.CLIENT_APP,
                        "capp-1",
                        createPoolForm("pool-1")));

        verify(poolRepository, never()).save(any());
    }

    @Test
    void addMember_rejects_upstreamSystem_worker_from_other_owner() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1"))
                .thenReturn(Optional.of(pool("tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        BizWorkerIdentityEntity worker = worker("LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(SecurityException.class,
                () -> service.addMember(
                        "tenant-1",
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        "ups-1",
                        "pool-1",
                        addMemberForm("worker-1")));
    }

    @Test
    void addMember_rejectsUpstreamSystemWorkerInPlatformPool() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        BizWorkerIdentityEntity worker = worker("LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(SecurityException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));

        verify(memberRepository, never()).save(any());
    }

    @Test
    void addMember_rejectsDisabledWorker() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        BizWorkerIdentityEntity worker = worker("LANGGRAPH_BIZ");
        worker.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(IllegalStateException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));

        verify(memberRepository, never()).save(any());
    }

    @Test
    void addMember_rejectsUnhealthyWorker() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        BizWorkerIdentityEntity worker = worker("LANGGRAPH_BIZ");
        worker.setHealthStatus(BizWorkerPoolService.UNHEALTHY);
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(IllegalStateException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));

        verify(memberRepository, never()).save(any());
    }

    @Test
    void listPools_usesExactOwnerScope() {
        BizWorkerPoolEntity owned = pool(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        when(poolRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1"))
                .thenReturn(java.util.List.of(owned));

        java.util.List<BizWorkerPoolDTO> result = service.listPools(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");

        assertEquals(1, result.size());
        assertEquals("ups-1", result.get(0).getOwnerId());
        verify(poolRepository).findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        verify(poolRepository, never()).findByTenantIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    void updatePoolStatus_rejectsUnsupportedStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updatePoolStatus("tenant-1", "pool-1", "DRAINING"));

        verify(poolRepository, never()).save(any());
    }

    @Test
    void updatePoolStatus_usesExactOwnerScopeAndNormalizesAllowedStatus() {
        BizWorkerPoolEntity owned = pool(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1"))
                .thenReturn(Optional.of(owned));

        BizWorkerPoolDTO result = service.updatePoolStatus(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-1",
                "pool-1",
                " disabled ");

        assertEquals(BizWorkerPoolService.STATUS_DISABLED, result.getStatus());
        verify(poolRepository).findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
    }

    @Test
    void internalAddMemberCannotManageUpstreamOwnedPool() {
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.PLATFORM, "tenant-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));

        verify(identityRepository, never()).findByWorkerId(anyString());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void upstreamCredentialACannotAddMemberToPoolOwnedByUpstreamB() {
        BizWorkerPoolEntity ownerBPool = pool(
                "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-b");
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(ownerBPool));
        when(poolRepository.findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.addMember(
                "tenant-1",
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "ups-a",
                "pool-1",
                addMemberForm("worker-1")));

        verify(poolRepository).findByPoolIdAndTenantIdAndOwnerTypeAndOwnerId(
                "pool-1", "tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-a");
        verify(poolRepository, never()).findByPoolIdAndTenantId(anyString(), anyString());
        verify(identityRepository, never()).findByWorkerId(anyString());
        verify(memberRepository, never()).save(any());
    }

    private CreateWorkerPoolForm createPoolForm(String poolId) {
        CreateWorkerPoolForm form = new CreateWorkerPoolForm();
        form.setPoolId(poolId);
        form.setName("Default Pool");
        form.setWorkerBackend("LANGGRAPH_BIZ");
        return form;
    }

    private AddWorkerPoolMemberForm addMemberForm(String workerId) {
        AddWorkerPoolMemberForm form = new AddWorkerPoolMemberForm();
        form.setWorkerId(workerId);
        return form;
    }

    private RegisterWorkerIdentityForm workerIdentityForm(
            String workerId, String backend, String baseUrl) {
        RegisterWorkerIdentityForm form = new RegisterWorkerIdentityForm();
        form.setWorkerId(workerId);
        form.setWorkerBackend(backend);
        form.setBaseUrl(baseUrl);
        return form;
    }

    private BizWorkerPoolEntity pool(String tenantId) {
        return pool(tenantId, ResourceOwnerType.PLATFORM, tenantId);
    }

    private BizWorkerPoolEntity pool(String tenantId, ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity entity = new BizWorkerPoolEntity();
        entity.setPoolId("pool-1");
        entity.setTenantId(tenantId);
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        entity.setWorkerBackend("LANGGRAPH_BIZ");
        entity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        entity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return entity;
    }

    private BizWorkerIdentityEntity worker(String backend) {
        return worker(backend, ResourceOwnerType.PLATFORM, BizWorkerPoolService.PLATFORM_OWNER_ID);
    }

    private BizWorkerIdentityEntity worker(String backend, ResourceOwnerType ownerType, String ownerId) {
        BizWorkerIdentityEntity entity = new BizWorkerIdentityEntity();
        entity.setWorkerId("worker-1");
        entity.setOwnerType(ownerType);
        entity.setOwnerId(ownerId);
        entity.setWorkerBackend(backend);
        entity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        entity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return entity;
    }
}
