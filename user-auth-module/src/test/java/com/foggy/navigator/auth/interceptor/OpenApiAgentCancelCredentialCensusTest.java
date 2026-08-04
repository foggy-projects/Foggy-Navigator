package com.foggy.navigator.auth.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiAgentCancelCredentialCensusTest {

    @Test
    void classifiesEachExactManagementCredentialSource() {
        MockHttpServletRequest bearer = cancelRequest();
        bearer.addHeader("Authorization", "Bearer jwt-value");
        assertEquals(OpenApiAgentCancelCredentialCensus.Lane.MANAGEMENT,
                inspect(bearer).lane());
        assertEquals(OpenApiAgentCancelCredentialCensus.ManagementSource.BEARER,
                inspect(bearer).managementSource());

        MockHttpServletRequest query = cancelRequest();
        query.setQueryString("token=query-jwt-value");
        assertEquals(OpenApiAgentCancelCredentialCensus.Lane.MANAGEMENT,
                inspect(query).lane());
        assertEquals(OpenApiAgentCancelCredentialCensus.ManagementSource.QUERY_TOKEN,
                inspect(query).managementSource());

        MockHttpServletRequest apiKey = cancelRequest();
        apiKey.addHeader("X-API-Key", "api-key-value");
        assertEquals(OpenApiAgentCancelCredentialCensus.Lane.MANAGEMENT,
                inspect(apiKey).lane());
        assertEquals(OpenApiAgentCancelCredentialCensus.ManagementSource.API_KEY,
                inspect(apiKey).managementSource());
    }

    @Test
    void classifiesRuntimeAliasesWithoutRetainingCredentialValues() {
        MockHttpServletRequest request = cancelRequest();
        request.addHeader("X-Foggy-App-Key", "secret-app-key");
        request.addHeader("X-App-Access-Token", "secret-access-token");
        request.addHeader("X-Client-Upstream-User-Id", "upstream-user-1");

        OpenApiAgentCancelCredentialCensus.Decision decision = inspect(request);

        assertEquals(OpenApiAgentCancelCredentialCensus.Lane.RUNTIME_ACCESS,
                decision.lane());
        assertEquals("X-Foggy-App-Key", decision.appKeyHeader());
        assertEquals("X-App-Access-Token", decision.accessTokenHeader());
        assertEquals("X-Client-Upstream-User-Id", decision.upstreamUserHeader());
        assertFalse(decision.toString().contains("secret-app-key"));
        assertFalse(decision.toString().contains("secret-access-token"));
        assertFalse(decision.toString().contains("upstream-user-1"));
    }

    @Test
    void rejectsMixedManagementAndRuntimeBeforePrecedenceCanApply() {
        MockHttpServletRequest request = runtimeRequest();
        request.addHeader("Authorization", "Bearer jwt-value");

        assertRejected(request,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_MIXED);
    }

    @Test
    void rejectsRepeatedManagementSourcesAndRuntimeAliases() {
        MockHttpServletRequest management = cancelRequest();
        management.addHeader("X-API-Key", "key-1");
        management.addHeader("X-API-Key", "key-2");
        assertRejected(management,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_AMBIGUOUS);

        MockHttpServletRequest runtime = runtimeRequest();
        runtime.addHeader("X-App-Key", "second-app-key");
        assertRejected(runtime,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_AMBIGUOUS);

        MockHttpServletRequest folded = cancelRequest();
        folded.addHeader("Authorization", "Bearer jwt-1,Bearer jwt-2");
        assertRejected(folded,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_AMBIGUOUS);
    }

    @Test
    void rawQueryTokenNeverReadsFormParametersOrRetainsCredentialValue() {
        MockHttpServletRequest query = cancelRequest();
        query.setQueryString("token=query%2Djwt%2Dsecret");
        query.addParameter("token", "form-jwt-must-not-win");

        OpenApiAgentCancelCredentialCensus.Decision decision = inspect(query);

        assertTrue(decision.management());
        assertEquals(OpenApiAgentCancelCredentialCensus.ManagementSource.QUERY_TOKEN,
                decision.managementSource());
        assertEquals("query-jwt-secret",
                OpenApiAgentCancelCredentialCensus
                        .requireSelectedManagementQueryToken(query, decision));
        assertFalse(decision.toString().contains("query-jwt-secret"));
        assertFalse(decision.toString().contains("form-jwt-must-not-win"));

        MockHttpServletRequest formOnly = cancelRequest();
        formOnly.setContentType("application/x-www-form-urlencoded");
        formOnly.addParameter("token", "form-only-secret");
        assertRejected(formOnly,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_LANE_REJECTED);

        MockHttpServletRequest queryAndForm = cancelRequest();
        queryAndForm.setQueryString("token=query-secret");
        queryAndForm.setContentType("application/x-www-form-urlencoded;charset=UTF-8");
        queryAndForm.addParameter("token", "form-secret");
        assertRejected(queryAndForm,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_LANE_REJECTED);
    }

    @Test
    void rejectsMalformedRawQueryBeforeCredentialResolution() {
        MockHttpServletRequest request = cancelRequest();
        request.setQueryString("token=%ZZ");

        assertRejected(request,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_MALFORMED);
    }

    @Test
    void rejectsPartialBlankAndMalformedCredentials() {
        MockHttpServletRequest partial = cancelRequest();
        partial.addHeader("X-Client-App-Key", "app-key");
        assertRejected(partial,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_REQUIRED);

        MockHttpServletRequest blank = runtimeRequest();
        blank.removeHeader("X-Client-App-Access-Token");
        blank.addHeader("X-Client-App-Access-Token", " ");
        assertRejected(blank,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_MALFORMED);

        MockHttpServletRequest malformedBearer = cancelRequest();
        malformedBearer.addHeader("Authorization", "Basic value");
        assertRejected(malformedBearer,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_MALFORMED);
    }

    @Test
    void rejectsForeignCredentialAndRuntimeTenantOverride() {
        MockHttpServletRequest foreign = cancelRequest();
        foreign.addHeader("X-Client-App-Control-Key", "control-key");
        assertRejected(foreign,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_LANE_REJECTED);

        MockHttpServletRequest runtime = runtimeRequest();
        runtime.addHeader("X-Tenant-Id", "caller-tenant");
        assertRejected(runtime,
                OpenApiAgentCancelCredentialCensus.CREDENTIAL_LANE_REJECTED);

        MockHttpServletRequest management = cancelRequest();
        management.addHeader("X-API-Key", "api-key");
        management.addHeader("X-Tenant-Id", "ignored-management-tenant");
        management.setContentType("application/json;charset=UTF-8");
        assertTrue(inspect(management).management());
    }

    @Test
    void ignoresEveryNonCancelRoute() {
        MockHttpServletRequest request = cancelRequest();
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/open/agents/{agentId}/tasks/{taskId}");
        request.setRequestURI("/api/v1/open/agents/agent-1/tasks/task-1");
        request.addHeader("X-API-Key", "api-key");

        assertNull(OpenApiAgentCancelCredentialCensus.inspect(request));

        MockHttpServletRequest conflictingServerPattern = cancelRequest();
        conflictingServerPattern.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/open/agents/{agentId}/tasks/{taskId}");
        conflictingServerPattern.addHeader("X-API-Key", "api-key");
        assertNull(OpenApiAgentCancelCredentialCensus.inspect(
                conflictingServerPattern));

        MockHttpServletRequest preRoutingFallback = cancelRequest();
        preRoutingFallback.removeAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        preRoutingFallback.addHeader("X-API-Key", "api-key");
        assertTrue(inspect(preRoutingFallback).management());
    }

    private static MockHttpServletRequest runtimeRequest() {
        MockHttpServletRequest request = cancelRequest();
        request.addHeader("X-Client-App-Key", "app-key");
        request.addHeader("X-Client-App-Access-Token", "access-token");
        request.addHeader("X-Upstream-User-Id", "upstream-user");
        return request;
    }

    private static MockHttpServletRequest cancelRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/open/agents/agent-1/tasks/task-1/cancel");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                OpenApiAgentCancelCredentialCensus.ROUTE_PATTERN);
        return request;
    }

    private static OpenApiAgentCancelCredentialCensus.Decision inspect(
            MockHttpServletRequest request) {
        OpenApiAgentCancelCredentialCensus.Decision decision =
                OpenApiAgentCancelCredentialCensus.inspect(request);
        assertTrue(decision != null);
        return decision;
    }

    private static void assertRejected(
            MockHttpServletRequest request,
            String safeCode) {
        OpenApiAgentCancelCredentialCensus.Decision decision = inspect(request);
        assertTrue(decision.rejected());
        assertEquals(safeCode, decision.rejectionCode());
    }
}
