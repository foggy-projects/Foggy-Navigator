package com.foggy.navigator.business.agent.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompositeBusinessFunctionAdapterInvokerTest {

    private ObjectMapper objectMapper;
    private CompositeBusinessFunctionAdapterInvoker composite;
    private BusinessFunctionAdapterInvoker mockEcho;
    private BusinessFunctionAdapterInvoker mockRest;

    private BusinessFunctionRuntimeContextDTO context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockEcho = mock(BusinessFunctionAdapterInvoker.class);
        mockRest = mock(BusinessFunctionAdapterInvoker.class);

        when(mockEcho.supports("echo")).thenReturn(true);
        when(mockRest.supports("rest")).thenReturn(true);

        composite = new CompositeBusinessFunctionAdapterInvoker(Arrays.asList(mockEcho, mockRest), objectMapper);

        context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO function = new BusinessFunctionDTO();
        function.setFunctionId("test_func");
        context.setFunction(function);
    }

    @Test
    void supports_always_true() {
        assertTrue(composite.supports("anything"));
    }

    @Test
    void invoke_routes_to_echo() {
        context.setAdapterConfigJson("{\"type\":\"echo\"}");
        BusinessFunctionAdapterResult expected = BusinessFunctionAdapterResult.success("foo");
        when(mockEcho.invoke(context, "{}")).thenReturn(expected);

        BusinessFunctionAdapterResult result = composite.invoke(context, "{}");

        assertSame(expected, result);
        verify(mockEcho).invoke(context, "{}");
        verify(mockRest, never()).invoke(any(), any());
    }

    @Test
    void invoke_routes_to_rest() {
        context.setAdapterConfigJson("{\"adapterType\":\"REST\"}");
        BusinessFunctionAdapterResult expected = BusinessFunctionAdapterResult.success("bar");
        when(mockRest.invoke(context, "{}")).thenReturn(expected);

        // mockRest.supports is configured for "rest", so we need to ensure case insensitivity or configure the mock
        when(mockRest.supports("REST")).thenReturn(true);

        BusinessFunctionAdapterResult result = composite.invoke(context, "{}");

        assertSame(expected, result);
        verify(mockRest).invoke(context, "{}");
        verify(mockEcho, never()).invoke(any(), any());
    }

    @Test
    void invoke_throws_on_unsupported_type() {
        context.setAdapterConfigJson("{\"type\":\"unknown\"}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                composite.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("Unsupported adapter type: unknown"));
    }
}
