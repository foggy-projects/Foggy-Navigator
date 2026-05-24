package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.BindAgentWorkerForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentWorkerBindingRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkerBindingServiceTest {

    private BusinessAgentWorkerBindingRepository bindingRepository;
    private BusinessCodingAgentRepository agentRepository;
    private BizWorkerPoolRepository workerPoolRepository;
    private ClientAppService clientAppService;
    private AgentWorkerBindingService service;

    @BeforeEach
    void setUp() {
        bindingRepository = mock(BusinessAgentWorkerBindingRepository.class);
        agentRepository = mock(BusinessCodingAgentRepository.class);
        workerPoolRepository = mock(BizWorkerPoolRepository.class);
        clientAppService = mock(ClientAppService.class);
        service = new AgentWorkerBindingService(
                bindingRepository,
                agentRepository,
                workerPoolRepository,
                clientAppService);

        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(clientAppAgent()));
        when(clientAppService.requireActiveClientApp("tenant-1", "capp-1"))
                .thenReturn(clientApp("capp-1", "ups-1"));
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void bind_allowsClientAppOwnedAgentAndPlatformPool() {
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-1", ResourceOwnerType.PLATFORM, "platform")));
        when(bindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId("tenant-1", "agent-1", "pool-1"))
                .thenReturn(Optional.empty());

        var result = service.bind("tenant-1", "capp-1", "agent-1", bindForm(" pool-1 "));

        assertEquals("tenant-1", result.getTenantId());
        assertEquals("capp-1", result.getClientAppId());
        assertEquals("agent-1", result.getAgentId());
        assertEquals("pool-1", result.getWorkerPoolId());
        assertEquals("pool-pool-1", result.getWorkerPoolName());
        assertEquals(ResourceOwnerType.PLATFORM, result.getWorkerPoolOwnerType());
        assertFalse(result.getDefaultWorkerPool());
        verify(clientAppService, times(2)).requireActiveClientApp("tenant-1", "capp-1");
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "pool-1".equals(binding.getWorkerPoolId())));
    }

    @Test
    void bind_allowsClientAppOwnedAgentAndSameUpstreamSystemPool() {
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        when(bindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId("tenant-1", "agent-1", "pool-1"))
                .thenReturn(Optional.empty());

        var result = service.bind("tenant-1", "capp-1", "agent-1", bindForm("pool-1"));

        assertEquals("pool-1", result.getWorkerPoolId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getWorkerPoolOwnerType());
    }

    @Test
    void bind_rejectsOtherSystemPoolFromClientAppPlane() {
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2")));

        assertThrows(SecurityException.class,
                () -> service.bind("tenant-1", "capp-1", "agent-1", bindForm("pool-1")));
    }

    @Test
    void setDefault_updatesAgentWorkerIdAndEnsuresBinding() {
        CodingAgentEntity agent = clientAppAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-2", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-2", ResourceOwnerType.PLATFORM, "platform")));
        when(bindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId("tenant-1", "agent-1", "pool-2"))
                .thenReturn(Optional.empty());
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.setDefault("tenant-1", "capp-1", "agent-1", bindForm("pool-2"));

        assertEquals("pool-2", result.getWorkerPoolId());
        assertTrue(result.getDefaultWorkerPool());
        verify(agentRepository).save(argThat(saved -> "pool-2".equals(saved.getWorkerId())));
    }

    @Test
    void unbind_rejectsDefaultWorkerPoolBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> service.unbind("tenant-1", "capp-1", "agent-1", " pool-default "));

        verify(bindingRepository, never()).deleteByTenantIdAndAgentIdAndWorkerPoolId(anyString(), anyString(), anyString());
    }

    @Test
    void bindSystemOwned_requiresSystemOwnedAgentAndVisiblePool() {
        CodingAgentEntity agent = systemAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1")));
        when(bindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId("tenant-1", "agent-1", "pool-1"))
                .thenReturn(Optional.empty());

        var result = service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("pool-1"));

        assertEquals("pool-1", result.getWorkerPoolId());
        assertEquals(ResourceOwnerType.UPSTREAM_SYSTEM, result.getWorkerPoolOwnerType());
        verify(bindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "pool-1".equals(binding.getWorkerPoolId())));
    }

    @Test
    void bindSystemOwned_rejectsOtherSystemPool() {
        CodingAgentEntity agent = systemAgent();
        when(agentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(agent));
        when(workerPoolRepository.findByPoolIdAndTenantId("pool-1", "tenant-1"))
                .thenReturn(Optional.of(workerPool("pool-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2")));

        assertThrows(SecurityException.class,
                () -> service.bindSystemOwned("tenant-1", principal(), "agent-1", bindForm("pool-1")));
    }

    private BindAgentWorkerForm bindForm(String workerPoolId) {
        BindAgentWorkerForm form = new BindAgentWorkerForm();
        form.setWorkerPoolId(workerPoolId);
        return form;
    }

    private CodingAgentEntity clientAppAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setClientAppId("capp-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.CLIENT_APP);
        agent.setOwnerId("capp-1");
        agent.setWorkerId("pool-default");
        agent.setEnabled(true);
        return agent;
    }

    private CodingAgentEntity systemAgent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setAgentId("agent-1");
        agent.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        agent.setOwnerId("ups-1");
        agent.setWorkerId("pool-default");
        agent.setEnabled(true);
        return agent;
    }

    private ClientAppEntity clientApp(String clientAppId, String upstreamSystemId) {
        ClientAppEntity app = new ClientAppEntity();
        app.setTenantId("tenant-1");
        app.setClientAppId(clientAppId);
        app.setUpstreamSystemId(upstreamSystemId);
        app.setStatus("ENABLED");
        return app;
    }

    private BizWorkerPoolEntity workerPool(String poolId, ResourceOwnerType ownerType, String ownerId) {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setTenantId("tenant-1");
        pool.setPoolId(poolId);
        pool.setName("pool-" + poolId);
        pool.setWorkerBackend("LANGGRAPH_BIZ");
        pool.setRoutingPolicy("ROUND_ROBIN");
        pool.setOwnerType(ownerType);
        pool.setOwnerId(ownerId);
        pool.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        pool.setHealthStatus("HEALTHY");
        return pool;
    }

    private UpstreamClientAppAdminPrincipal principal() {
        return UpstreamClientAppAdminPrincipal.builder()
                .upstreamSystemId("ups-1")
                .authorizedTenantIds(Set.of("tenant-1"))
                .build();
    }
}
