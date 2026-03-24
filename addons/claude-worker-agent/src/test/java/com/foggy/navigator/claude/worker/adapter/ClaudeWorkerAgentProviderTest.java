package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.repository.AgentDirectoryBindingRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.agent.A2aAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaudeWorkerAgentProviderTest {

    @Mock
    private CodingAgentRepository agentRepository;
    @Mock
    private AgentDirectoryBindingRepository bindingRepository;
    @Mock
    private ClaudeTaskService taskService;
    @Mock
    private WorkingDirectoryRepository directoryRepository;

    private ClaudeWorkerAgentProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ClaudeWorkerAgentProvider(agentRepository, bindingRepository, taskService, directoryRepository, null);
    }

    @Test
    void resolveAgent_directoryIdResolvesBoundAgent() {
        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId("agent-2");
        entity.setUserId("user-1");
        entity.setName("agent-two");
        entity.setAgentType("LOCAL_CLAUDE_WORKER");
        entity.setWorkerId("worker-1");

        AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
        binding.setAgentId("agent-2");
        binding.setDirectoryId("dir-2");

        when(agentRepository.findByAgentIdAndUserId("dir-2", "user-1")).thenReturn(Optional.empty());
        when(agentRepository.findByNameAndUserId("dir-2", "user-1")).thenReturn(Optional.empty());
        when(agentRepository.findByDefaultDirectoryIdAndUserId("dir-2", "user-1")).thenReturn(Optional.empty());
        when(bindingRepository.findByDirectoryId("dir-2")).thenReturn(List.of(binding));
        when(agentRepository.findByAgentIdAndUserId("agent-2", "user-1")).thenReturn(Optional.of(entity));

        Optional<A2aAgent> result = provider.resolveAgent("dir-2", "user-1");

        assertTrue(result.isPresent());
        assertEquals("agent-2", result.get().getAgentCard().getId());
    }
}
