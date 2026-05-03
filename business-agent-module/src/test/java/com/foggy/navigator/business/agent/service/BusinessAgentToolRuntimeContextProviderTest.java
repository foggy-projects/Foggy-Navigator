package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextKeys;
import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessAgentToolRuntimeContextProviderTest {

    @Mock
    private BusinessAgentTaskScopedTokenRuntimeStore tokenStore;

    @InjectMocks
    private BusinessAgentToolRuntimeContextProvider provider;

    @Test
    void provide_success_withTenantId() {
        when(tokenStore.getToken("tenant1", "session1", "task1")).thenReturn("plainToken123");

        ToolRuntimeContextRequest request = ToolRuntimeContextRequest.builder()
                .tenantId("tenant1")
                .sessionId("session1")
                .taskId("task1")
                .toolName("test_tool")
                .build();

        Map<String, Object> context = provider.provide(request);

        assertNotNull(context);
        assertEquals(1, context.size());
        assertEquals("plainToken123", context.get(ToolRuntimeContextKeys.TASK_SCOPED_TOKEN));
    }

    @Test
    void provide_missingTenantId_returnsEmptyMap() {
        // userId should not be used as a fallback anymore
        ToolRuntimeContextRequest request = ToolRuntimeContextRequest.builder()
                .tenantId(null)
                .userId("user1")
                .sessionId("session1")
                .toolName("test_tool")
                .build();

        Map<String, Object> context = provider.provide(request);

        assertNotNull(context);
        assertTrue(context.isEmpty());
        verifyNoInteractions(tokenStore);
    }

    @Test
    void provide_noToken_returnsEmptyMap() {
        when(tokenStore.getToken("tenant1", "session1", null)).thenReturn(null);

        ToolRuntimeContextRequest request = ToolRuntimeContextRequest.builder()
                .tenantId("tenant1")
                .sessionId("session1")
                .toolName("test_tool")
                .build();

        Map<String, Object> context = provider.provide(request);

        assertNotNull(context);
        assertTrue(context.isEmpty());
    }
}
