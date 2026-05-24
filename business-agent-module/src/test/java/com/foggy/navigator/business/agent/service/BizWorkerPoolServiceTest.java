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
    void createPool_rejects_duplicate_pool_id() {
        when(poolRepository.findByPoolId("pool-1")).thenReturn(Optional.of(pool("tenant-1")));

        assertThrows(IllegalArgumentException.class,
                () -> service.createPool("tenant-1", createPoolForm("pool-1")));
    }

    @Test
    void addMember_rejects_backend_mismatch() {
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        BizWorkerIdentityEntity worker = worker("PYTHON_OTHER");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));
    }

    @Test
    void addMember_rejects_duplicate_member() {
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1")));
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker("LANGGRAPH_BIZ")));
        when(memberRepository.findByPoolIdAndWorkerId("pool-1", "worker-1"))
                .thenReturn(Optional.of(new BizWorkerPoolMemberEntity()));

        assertThrows(IllegalArgumentException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));
    }

    @Test
    void requireAvailablePool_rejects_disabled_pool() {
        BizWorkerPoolEntity pool = pool("tenant-1");
        pool.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1")).thenReturn(Optional.of(pool));

        assertThrows(IllegalStateException.class,
                () -> service.requireAvailablePool("tenant-1", "pool-1"));
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
    void addMember_rejects_upstreamSystem_worker_from_other_owner() {
        when(poolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(pool("tenant-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        BizWorkerIdentityEntity worker = worker("LANGGRAPH_BIZ", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2");
        when(identityRepository.findByWorkerId("worker-1")).thenReturn(Optional.of(worker));

        assertThrows(SecurityException.class,
                () -> service.addMember("tenant-1", "pool-1", addMemberForm("worker-1")));
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
