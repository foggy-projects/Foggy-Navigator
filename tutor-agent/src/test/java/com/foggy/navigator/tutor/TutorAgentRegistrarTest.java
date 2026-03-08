package com.foggy.navigator.tutor;

import com.foggy.navigator.agent.framework.core.AgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TutorAgentRegistrar 测试")
class TutorAgentRegistrarTest {

    @Mock
    private AgentRegistry agentRegistry;

    private TutorAgentRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new TutorAgentRegistrar(agentRegistry);
    }

    @Test
    @DisplayName("DB 中不存在时应从 YAML 注册 agent")
    void register_shouldSeedFromYaml_whenNotInRegistry() {
        when(agentRegistry.exists(anyString())).thenReturn(false);

        registrar.register();

        // tutor-agent.yml and coding-agent.yml → 2 registrations
        verify(agentRegistry, times(2)).register(any());
    }

    @Test
    @DisplayName("DB 中已存在时应跳过注册")
    void register_shouldSkip_whenAlreadyInRegistry() {
        when(agentRegistry.exists(anyString())).thenReturn(true);

        registrar.register();

        verify(agentRegistry, never()).register(any());
    }

    @Test
    @DisplayName("YAML 文件不存在时不应抛出异常")
    void register_shouldNotThrow_whenYamlMissing() {
        // The real YAML files exist on classpath, so this tests the error handling path
        // by verifying register() completes without throwing even when agentRegistry throws
        when(agentRegistry.exists(anyString())).thenThrow(new RuntimeException("simulated error"));

        // Should not throw — errors are caught internally
        registrar.register();
    }

    @Test
    @DisplayName("部分 agent 存在时应只注册缺失的")
    void register_shouldSeedOnlyMissing() {
        // First call (tutor-agent) exists, second call (coding-agent) does not
        when(agentRegistry.exists(anyString())).thenReturn(true).thenReturn(false);

        registrar.register();

        verify(agentRegistry, times(1)).register(any());
    }
}
