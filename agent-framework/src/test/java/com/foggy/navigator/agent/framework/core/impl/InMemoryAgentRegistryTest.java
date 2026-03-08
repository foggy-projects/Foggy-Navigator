package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentStatus;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAgentRegistryTest {

    private InMemoryAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryAgentRegistry();
    }

    @Test
    void register_shouldAddAgentToRegistry() {
        AgentConfig config = createTestConfig("agent-1", "Test Agent");

        registry.register(config);

        assertTrue(registry.exists("agent-1"));
        AgentInfo info = registry.findById("agent-1");
        assertNotNull(info);
        assertEquals("agent-1", info.getId());
        assertEquals("Test Agent", info.getName());
        assertEquals(AgentStatus.REGISTERED, info.getStatus());
    }

    @Test
    void unregister_shouldRemoveAgentFromRegistry() {
        AgentConfig config = createTestConfig("agent-1", "Test Agent");
        registry.register(config);

        registry.unregister("agent-1");

        assertFalse(registry.exists("agent-1"));
        assertNull(registry.findById("agent-1"));
    }

    @Test
    void findById_shouldReturnNullForNonExistent() {
        assertNull(registry.findById("non-existent"));
    }

    @Test
    void findByCapability_shouldReturnMatchingAgents() {
        AgentConfig config1 = AgentConfig.builder()
                .id("agent-1")
                .name("Agent 1")
                .capabilities(List.of("coding", "testing"))
                .build();
        AgentConfig config2 = AgentConfig.builder()
                .id("agent-2")
                .name("Agent 2")
                .capabilities(List.of("coding"))
                .build();
        AgentConfig config3 = AgentConfig.builder()
                .id("agent-3")
                .name("Agent 3")
                .capabilities(List.of("documentation"))
                .build();

        registry.register(config1);
        registry.register(config2);
        registry.register(config3);

        List<AgentInfo> codingAgents = registry.findByCapability("coding");
        assertEquals(2, codingAgents.size());

        List<AgentInfo> testingAgents = registry.findByCapability("testing");
        assertEquals(1, testingAgents.size());
        assertEquals("agent-1", testingAgents.get(0).getId());
    }

    @Test
    void findAll_shouldReturnAllAgents() {
        registry.register(createTestConfig("agent-1", "Agent 1"));
        registry.register(createTestConfig("agent-2", "Agent 2"));

        List<AgentInfo> all = registry.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void updateStatus_shouldUpdateAgentStatus() {
        registry.register(createTestConfig("agent-1", "Test Agent"));

        registry.updateStatus("agent-1", AgentStatus.ACTIVE);

        AgentInfo info = registry.findById("agent-1");
        assertEquals(AgentStatus.ACTIVE, info.getStatus());
        assertNotNull(info.getLastActiveAt());
    }

    @Test
    void updateStatus_shouldDoNothingForNonExistent() {
        registry.updateStatus("non-existent", AgentStatus.ACTIVE);
        // No exception should be thrown
        assertNull(registry.findById("non-existent"));
    }

    private AgentConfig createTestConfig(String id, String name) {
        return AgentConfig.builder()
                .id(id)
                .name(name)
                .type("test")
                .build();
    }
}
