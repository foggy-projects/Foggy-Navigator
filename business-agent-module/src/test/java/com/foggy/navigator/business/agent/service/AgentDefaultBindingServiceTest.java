package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentWorkerBindingRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import com.foggy.navigator.common.entity.AgentWorkerBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AgentDefaultBindingServiceTest {

    private BusinessAgentModelBindingRepository modelBindingRepository;
    private BusinessAgentDirectoryBindingRepository directoryBindingRepository;
    private BusinessAgentWorkerBindingRepository workerBindingRepository;
    private AgentDefaultBindingService service;

    @BeforeEach
    void setUp() {
        modelBindingRepository = mock(BusinessAgentModelBindingRepository.class);
        directoryBindingRepository = mock(BusinessAgentDirectoryBindingRepository.class);
        workerBindingRepository = mock(BusinessAgentWorkerBindingRepository.class);
        service = new AgentDefaultBindingService(
                modelBindingRepository,
                directoryBindingRepository,
                workerBindingRepository);
    }

    @Test
    void ensureDefaults_createsMissingModelDirectoryAndWorkerBindings() {
        CodingAgentEntity agent = agent();

        service.ensureDefaults(agent);

        verify(modelBindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "model-1".equals(binding.getModelConfigId())));
        verify(directoryBindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "dir-1".equals(binding.getDirectoryId())));
        verify(workerBindingRepository).save(argThat(binding ->
                "tenant-1".equals(binding.getTenantId())
                        && "agent-1".equals(binding.getAgentId())
                        && "pool-1".equals(binding.getWorkerPoolId())));
    }

    @Test
    void ensureDefaults_skipsExistingBindings() {
        CodingAgentEntity agent = agent();
        when(modelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId("tenant-1", "agent-1", "model-1"))
                .thenReturn(Optional.of(new AgentModelBindingEntity()));
        when(directoryBindingRepository.findByTenantIdAndAgentIdAndDirectoryId("tenant-1", "agent-1", "dir-1"))
                .thenReturn(Optional.of(new AgentDirectoryBindingEntity()));
        when(workerBindingRepository.findByTenantIdAndAgentIdAndWorkerPoolId("tenant-1", "agent-1", "pool-1"))
                .thenReturn(Optional.of(new AgentWorkerBindingEntity()));

        service.ensureDefaults(agent);

        verify(modelBindingRepository, never()).save(any());
        verify(directoryBindingRepository, never()).save(any());
        verify(workerBindingRepository, never()).save(any());
    }

    @Test
    void ensureDefaults_requiresAgentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> service.ensureDefaults(new CodingAgentEntity()));
    }

    private CodingAgentEntity agent() {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setTenantId("tenant-1");
        agent.setAgentId("agent-1");
        agent.setDefaultModelConfigId(" model-1 ");
        agent.setDefaultDirectoryId(" dir-1 ");
        agent.setWorkerId(" pool-1 ");
        return agent;
    }
}
