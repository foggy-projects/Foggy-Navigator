package com.foggy.navigator.business.agent.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestBusinessFunctionAdapterInvokerTest {

    private ObjectMapper objectMapper;
    private RestTemplate restTemplate;
    private Environment environment;
    private ClientAppUserGrantService userGrantService;
    private RestBusinessFunctionAdapterInvoker invoker;

    private BusinessFunctionRuntimeContextDTO context;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        restTemplate = mock(RestTemplate.class);
        environment = mock(Environment.class);
        userGrantService = mock(ClientAppUserGrantService.class);
        invoker = new RestBusinessFunctionAdapterInvoker(objectMapper, restTemplate, environment, userGrantService);

        context = new BusinessFunctionRuntimeContextDTO();
        context.setTenantId("tenant_123");
        context.setClientAppId("app_123");
        context.setUpstreamUserId("user_123");
        context.setTaskId("task_123");
        context.setSessionId("session_123");
        context.setVersion("v1");
        BusinessFunctionDTO function = new BusinessFunctionDTO();
        function.setFunctionId("test_func");
        context.setFunction(function);
        context.setFunctionId("test_func");
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
    void invoke_rejects_controlled_navigator_header() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/api",
              "adapter": {
                "headers": { "X-Navigator-Task-Id": "$.input.taskId" }
              }
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{\"taskId\":\"forged\"}")
        );
        assertTrue(ex.getMessage().contains("Forbidden REST adapter header"));
    }

    @Test
    void invoke_rejects_manifest_token_header_when_server_injection_configured() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/api",
              "adapter": {
                "headers": { "X-TMS-Agent-Token": "$.input.token" }
              }
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://tms");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.user-token-header")).thenReturn("X-TMS-Agent-Token");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invoker.invoke(context, "{\"token\":\"forged\"}")
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
        assertEquals("tenant_123", entity.getHeaders().getFirst("X-Navigator-Tenant-Id"));
        assertEquals("app_123", entity.getHeaders().getFirst("X-Navigator-Client-App-Id"));
        assertEquals("user_123", entity.getHeaders().getFirst("X-Navigator-Upstream-User-Id"));
        assertEquals("task_123", entity.getHeaders().getFirst("X-Navigator-Task-Id"));
        assertEquals("session_123", entity.getHeaders().getFirst("X-Navigator-Session-Id"));
        assertEquals("test_func", entity.getHeaders().getFirst("X-Navigator-Function-Id"));
        assertEquals("v1", entity.getHeaders().getFirst("X-Navigator-Function-Version"));

        Map<String, Object> body = (Map<String, Object>) entity.getBody();
        assertNotNull(body);
        assertEquals("customer request", body.get("reason"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invoke_body_mapping_preserves_nested_json_values() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/x3-agent/dataset/query_model",
              "adapter": {
                "body": {
                  "model": "$.input.model",
                  "payload": "$.input.payload",
                  "mode": "$.input.mode"
                }
              }
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://internal-tms:8080");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":200}"));

        invoker.invoke(context, """
            {
              "model": "OrderSettlementQuery",
              "payload": {
                "columns": ["sum(rpValue)"],
                "slice": [
                  {"field": "openingTime", "op": "[)", "value": ["2026-02-01", "2026-03-01"]}
                ],
                "limit": 1
              },
              "mode": "execute"
            }
            """);

        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://internal-tms:8080/x3-agent/dataset/query_model"), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));

        Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertNotNull(body);
        assertEquals("OrderSettlementQuery", body.get("model"));
        assertEquals("execute", body.get("mode"));
        assertTrue(body.get("payload") instanceof Map);

        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        assertTrue(payload.get("columns") instanceof List);
        assertTrue(payload.get("slice") instanceof List);
        assertEquals(1, payload.get("limit"));
    }

    @Test
    void invoke_injects_configured_upstream_user_token_header() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/api"
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://internal-tms:8080");
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.user-token-header")).thenReturn("X-TMS-Agent-Token");
        when(userGrantService.resolveUpstreamUserToken("tenant_123", "app_123", "user_123")).thenReturn("tms-user-token");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        invoker.invoke(context, "{}");

        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://internal-tms:8080/api"), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
        assertEquals("tms-user-token", entityCaptor.getValue().getHeaders().getFirst("X-TMS-Agent-Token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invoke_post_with_explicit_empty_body_mapping_sends_empty_json_object() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/dataset/list_models",
              "adapter": {
                "body": {}
              }
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://internal-tms:8080");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        invoker.invoke(context, "{}");

        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://internal-tms:8080/dataset/list_models"), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
        Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertNotNull(body);
        assertTrue(body.isEmpty());
    }

    @Test
    void invoke_without_token_header_config_does_not_resolve_or_inject_upstream_user_token() {
        context.setAdapterConfigJson("""
            {
              "type": "rest",
              "method": "POST",
              "upstream_ref": "tms",
              "path": "/api"
            }
            """);
        when(environment.getProperty("foggy.navigator.business.agent.upstreams.tms.url")).thenReturn("http://internal-tms:8080");
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"ok\"}"));

        invoker.invoke(context, "{}");

        ArgumentCaptor<HttpEntity<Object>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://internal-tms:8080/api"), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
        assertNull(entityCaptor.getValue().getHeaders().getFirst("X-TMS-Agent-Token"));
        verifyNoInteractions(userGrantService);
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
