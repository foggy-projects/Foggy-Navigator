package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillMaterializeTargetResolverTest {

    @Mock
    private ClientAppService clientAppService;
    @Mock
    private BusinessCodingAgentRepository agentRepository;
    @Mock
    private BizWorkerPoolRepository workerPoolRepository;
    @Mock
    private BizWorkerPoolMemberRepository workerPoolMemberRepository;
    @Mock
    private BizWorkerIdentityRepository workerIdentityRepository;

    @InjectMocks
    private SkillMaterializeTargetResolver resolver;

    @Test
    void resolveTargets_fansOutAgentWorkerPoolMembers() {
        ClientAppEntity app = clientApp("tms_app", "tms");
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(app);

        CodingAgentEntity agent = agent("pm-agent", ResourceOwnerType.CLIENT_APP, "tms_app", "pool-1");
        when(agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant_1", ResourceOwnerType.CLIENT_APP, "tms_app")).thenReturn(List.of(agent));
        when(agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "tms")).thenReturn(List.of());

        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant_1"))
                .thenReturn(Optional.of(pool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "tms")));
        when(workerPoolMemberRepository.findByPoolIdOrderByCreatedAtAsc("pool-1"))
                .thenReturn(List.of(poolMember("worker-one"), poolMember("worker-two")));
        when(workerIdentityRepository.findByWorkerId("worker-one"))
                .thenReturn(Optional.of(worker("worker-one", "http://worker-one")));
        when(workerIdentityRepository.findByWorkerId("worker-two"))
                .thenReturn(Optional.of(worker("worker-two", "http://worker-two")));

        List<SkillMaterializeTargetResolver.Target> targets = resolver.resolveTargets("tenant_1", "tms_app");

        assertEquals(2, targets.size());
        assertEquals("worker-one", targets.get(0).workerId());
        assertEquals("http://worker-one", targets.get(0).baseUrl());
        assertEquals("AGENT_ROUTE:pm-agent:WORKER_POOL:pool-1", targets.get(0).source());
        assertEquals("worker-two", targets.get(1).workerId());
    }

    @Test
    void resolveTargets_usesUpstreamSystemWorkerIdentityWhenNoAgentRouteExists() {
        ClientAppEntity app = clientApp("tms_app", "tms");
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(app);
        when(agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant_1", ResourceOwnerType.CLIENT_APP, "tms_app")).thenReturn(List.of());
        when(agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "tms")).thenReturn(List.of());
        when(workerIdentityRepository.findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
                ResourceOwnerType.UPSTREAM_SYSTEM,
                "tms",
                ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND,
                BizWorkerPoolService.STATUS_ENABLED,
                BizWorkerPoolService.HEALTHY)).thenReturn(List.of(worker("worker-live", "http://worker-live")));

        List<SkillMaterializeTargetResolver.Target> targets = resolver.resolveTargets("tenant_1", "tms_app");

        assertEquals(1, targets.size());
        assertEquals("worker-live", targets.get(0).workerId());
        assertEquals("http://worker-live", targets.get(0).baseUrl());
        assertEquals("UPSTREAM_SYSTEM_WORKER_IDENTITY", targets.get(0).source());
    }

    private ClientAppEntity clientApp(String clientAppId, String upstreamSystemId) {
        ClientAppEntity app = new ClientAppEntity();
        app.setTenantId("tenant_1");
        app.setClientAppId(clientAppId);
        app.setUpstreamSystemId(upstreamSystemId);
        return app;
    }

    private CodingAgentEntity agent(String agentId, ResourceOwnerType ownerType, String ownerId, String workerId) {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant_1");
        agent.setAgentId(agentId);
        agent.setOwnerType(ownerType);
        agent.setOwnerId(ownerId);
        agent.setClientAppId("tms_app");
        agent.setWorkerId(workerId);
        agent.setEnabled(true);
        return agent;
    }

    private BizWorkerPoolEntity pool(String poolId, ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setTenantId("tenant_1");
        pool.setPoolId(poolId);
        pool.setOwnerType(ownerType);
        pool.setOwnerId(ownerId);
        pool.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return pool;
    }

    private BizWorkerPoolMemberEntity poolMember(String workerId) {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setPoolId("pool-1");
        member.setWorkerId(workerId);
        member.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        return member;
    }

    private BizWorkerIdentityEntity worker(String workerId, String baseUrl) {
        BizWorkerIdentityEntity worker = new BizWorkerIdentityEntity();
        worker.setWorkerId(workerId);
        worker.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        worker.setOwnerId("tms");
        worker.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        worker.setBaseUrl(baseUrl);
        worker.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        worker.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return worker;
    }
}
