package com.foggy.navigator.business.agent.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestBusinessFunctionAdapterInvokerTest {

    private ObjectMapper objectMapper;
    private RestTemplate restTemplate;
    private Environment environment;
    private RestBusinessFunctionAdapterInvoker invoker;

    private BusinessFunctionRuntimeContextDTO context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        restTemplate = mock(RestTemplate.class);
        environment = mock(Environment.class);
        invoker = new RestBusinessFunctionAdapterInvoker(objectMapper, restTemplate, environment);

        context = new BusinessFunctionRuntimeContextDTO();
        context.setTenantId("tenant_123");
        BusinessFunctionDTO function = new BusinessFunctionDTO();
        function.setFunctionId("test_func");
        context.setFunction(function);
    }

    @Test
    void supports_rest_type() {
        assertTrue(invoker.supports("rest"));
        assertTrue(invoker.supports("REST"));
        assertFalse(invoker.supports("echo"));
    }

    @Test
    void invoke_missing_upstream_ref_throws() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"path\":\"/api\"}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("requires 'upstream_ref'"));
    }

    @Test
    void invoke_unconfigured_upstream_ref_throws_ssrf_prevention() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"upstream_ref\":\"missing-service\",\"path\":\"/api\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.missing-service.url")).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("Unauthorized or unconfigured upstream_ref"));
    }

    @Test
    void invoke_missing_path_throws() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"POST\",\"upstream_ref\":\"tms\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("requires 'path'"));
    }

    @Test
    void invoke_missing_method_throws() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"upstream_ref\":\"tms\",\"path\":\"/api\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("requires 'method'"));
    }

    @Test
    void invoke_unsupported_method_throws() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"TRACE\",\"upstream_ref\":\"tms\",\"path\":\"/api\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("Unsupported REST method"));
    }

    @Test
    void invoke_rejects_invalid_base_url() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"POST\",\"upstream_ref\":\"tms\",\"path\":\"/api\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("file:///tmp/data");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("http or https"));
    }

    @Test
    void invoke_rejects_absolute_url_path() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"POST\",\"upstream_ref\":\"tms\",\"path\":\"http://evil.example/api\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("absolute path"));
    }

    @Test
    void invoke_rejects_forbidden_header() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/api",
              "adapter": {
                "headers": { "Authorization": "$.input.token" }
              }
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{\"token\":\"secret\"}")
        );
        assertTrue(ex.getMessage().contains("Forbidden REST adapter header"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invoke_success_resolves_paths_headers_and_body() {
        String configJson = """
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/orders/{id}/submit",
              "adapter": {
                "path_params": { "id": "$.input.orderId" },
                "headers": { "X-Tenant": "$.context.tenantId" },
                "body": { "reason": "$.input.reasonText" }
              }
            }
            """;
        context.setAdapterConfigJson(configJson);
        String inputJson = "{\"orderId\":\"1001\",\"reasonText\":\"customer request\"}";

        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url"))
                .thenReturn("http://internal-tms:8080");

        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        BusinessFunctionAdapterResult result = invoker.invoke(context, inputJson);

        assertNotNull(result);
        assertEquals(BusinessFunctionAdapterResult.STATUS_SUCCESS, result.getStatus());
        assertEquals("{\"status\":\"ok\"}", result.getOutputJson());

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpMethod> methodCaptor = ArgumentCaptor.forClass(HttpMethod.class);
        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        verify(restTemplate).exchange(urlCaptor.capture(), methodCaptor.capture(), entityCaptor.capture(), eq(String.class));

        assertEquals("http://internal-tms:8080/orders/1001/submit", urlCaptor.getValue());
        assertEquals(HttpMethod.POST, methodCaptor.getValue());

        HttpEntity<Object> entity = entityCaptor.getValue();
        assertEquals("tenant_123", entity.getHeaders().getFirst("X-Tenant"));

        Map<String, Object> body = (Map<String, Object>) entity.getBody();
        assertNotNull(body);
        assertEquals("customer request", body.get("reason"));
    }

    @Test
    void invoke_get_does_not_send_body() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "GET",
              "upstream_ref": "tms",
              "path": "/orders/{id}",
              "adapter": {
                "path_params": { "id": "$.input.orderId" },
                "body": { "reason": "$.input.reasonText" }
              }
            }
            """);

        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url"))
                .thenReturn("http://internal-tms:8080/");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        invoker.invoke(context, "{\"orderId\":\"1001\",\"reasonText\":\"should not be sent\"}");

        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://internal-tms:8080/orders/1001"), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));
        assertNull(entityCaptor.getValue().getBody());
    }

    @Test
    void invoke_non2xx_fails_closed() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"POST\",\"upstream_ref\":\"tms\",\"path\":\"/err\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request", "{\"err\":\"validation\"}".getBytes(), null));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("HTTP 400"));
        assertTrue(ex.getMessage().contains("validation"));
    }

    @Test
    void invoke_non2xx_returned_response_fails_closed() {
        context.setAdapterConfigJson("{\"type\":\"rest\",\"method\":\"POST\",\"upstream_ref\":\"tms\",\"path\":\"/redirect\"}");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND).body(""));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{}")
        );
        assertTrue(ex.getMessage().contains("HTTP 302"));
    }
}
