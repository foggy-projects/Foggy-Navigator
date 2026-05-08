package com.foggy.navigator.business.agent.service.adapter;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionVersionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalEchoBusinessFunctionAdapterInvokerTest {

    private LocalEchoBusinessFunctionAdapterInvoker invoker;
    private BusinessFunctionRuntimeContextDTO context;

    @BeforeEach
    void setUp() {
        invoker = new LocalEchoBusinessFunctionAdapterInvoker(new ObjectMapper());

        context = new BusinessFunctionRuntimeContextDTO();
        BusinessFunctionDTO function = new BusinessFunctionDTO();
        function.setFunctionId("test_func");
        context.setFunction(function);
    }

    @Test
    void invoke_echo_success_using_type() {
        context.setAdapterConfigJson("{\"type\":\"echo\"}");

        BusinessFunctionAdapterResult result = invoker.invoke(context, "{\"foo\":\"bar\"}");

        assertEquals(BusinessFunctionAdapterResult.STATUS_SUCCESS, result.getStatus());
        assertEquals("{\"foo\":\"bar\"}", result.getOutputJson());
    }

    @Test
    void invoke_echo_success_using_adapterType() {
        context.setAdapterConfigJson("{\"adapterType\":\"ECHO\"}");

        BusinessFunctionAdapterResult result = invoker.invoke(context, "{\"foo\":\"bar\"}");

        assertEquals(BusinessFunctionAdapterResult.STATUS_SUCCESS, result.getStatus());
        assertEquals("{\"foo\":\"bar\"}", result.getOutputJson());
    }

    @Test
    void invoke_rejects_missing_config() {
        context.setAdapterConfigJson(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            invoker.invoke(context, "{}")
        );
        assertEquals("Adapter config is missing or blank", ex.getMessage());
    }

    @Test
    void invoke_rejects_invalid_json() {
        context.setAdapterConfigJson("invalid json");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("Invalid adapter config JSON"));
    }

    @Test
    void invoke_rejects_missing_type() {
        context.setAdapterConfigJson("{\"other\":\"value\"}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            invoker.invoke(context, "{}")
        );
        assertEquals("Adapter type not specified in config", ex.getMessage());
    }

    @Test
    void invoke_rejects_unsupported_type() {
        context.setAdapterConfigJson("{\"type\":\"rest\"}");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            invoker.invoke(context, "{}")
        );
        assertEquals("Unsupported adapter type: rest", ex.getMessage());
    }
}
